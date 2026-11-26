package remates.parser;

import static remates.parser.Py.*;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import remates.model.DatosRemate;
import remates.model.ResultadoBoletin;
import remates.model.Terreno;
import remates.model.Vehiculo;

/**
 * Port of {@code parse_full3.py}: reads the text of a Boletín Judicial and extracts every
 * land (finca) and vehicle auction. Each edict is one line containing "sáquese a remate"
 * (or "sáquese en remate"); its "Referencia N°" appears in one of the next lines.
 *
 * <p>The regular expressions and the order of the fallbacks are kept identical to the Python
 * script on purpose, so both produce the same results. Change them here as you would there.
 */
public final class BoletinParser {

    // ------------------------------------------------------------------ entry points

    /** Parses the whole text of a boletín (equivalent of {@code process_file}). */
    public ResultadoBoletin parse(String content) {
        String text = Normalizer.normalize(content.replace("\r\n", "\n").replace('\r', '\n'), Normalizer.Form.NFC);
        List<Terreno> fincas = new ArrayList<>();
        List<Vehiculo> vehs = new ArrayList<>();
        Set<List<String>> fincaKeys = new HashSet<>();
        Set<List<String>> vehKeys = new HashSet<>();

        for (Block block : getBlocks(text)) {
            String t = block.text();
            Matcher idx = search(SAQUESE, t);
            String after = slice(t, idx.end(), idx.end() + 150).toLowerCase(Locale.ROOT);
            String tipo = after.contains("finca") ? "finca" : after.contains("veh") ? "vehiculo" : "otro";
            if (tipo.equals("otro")) {
                continue;
            }
            DatosRemate datos = datosComunes(block.ref(), t, idx);
            if (tipo.equals("finca")) {
                Terreno finca = parseFinca(datos, t, idx.end());
                // dedupe(fincas, ['base_raw','matricula','medida','descripcion'])
                if (fincaKeys.add(List.of(datos.baseTexto(), finca.matricula(), finca.medidaTexto(),
                    finca.descripcion()))) {
                    fincas.add(finca);
                }
            } else {
                Vehiculo veh = getVehData(datos, t, idx.end());
                // dedupe(vehs, ['base_raw','placa','marca','estilo','anio'])
                if (vehKeys.add(List.of(datos.baseTexto(), veh.placa(), veh.marca(), veh.estilo(),
                    veh.anioFabricacion()))) {
                    vehs.add(veh);
                }
            }
        }
        return new ResultadoBoletin(detectarNumero(text), fincas, vehs);
    }

    private static final Pattern NUMERO_BOLETIN = rei("n[uú]mero\\s*(\\d+)");

    /** Boletín number from the first three lines ("... número 123"), or null (as {@code procesar_boletin.py}). */
    public static String detectarNumero(String text) {
        String[] lines = text.split("\n", 4);
        for (int i = 0; i < Math.min(3, lines.length); i++) {
            Matcher m = search(NUMERO_BOLETIN, lines[i]);
            if (m != null) {
                return m.group(1);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ blocks

    private record Block(String ref, String text) {
    }

    private static final Pattern REFERENCIA = re("Referencia N.?:\\s*([0-9]+)");
    private static final Pattern SAQUESE = re("s[aá]quese (?:a|en) remate");

    /** {@code get_blocks}. */
    private static List<Block> getBlocks(String content) {
        String[] lines = content.split("\n", -1);
        List<Block> blocks = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.contains("sáquese a remate") || line.contains("sáquese en remate")) {
                String ref = null;
                for (int j = i + 1; j < Math.min(i + 10, lines.length); j++) {
                    Matcher m = search(REFERENCIA, lines[j]);
                    if (m != null) {
                        ref = m.group(1);
                        break;
                    }
                }
                blocks.add(new Block(ref, strip(line)));
            }
        }
        return blocks;
    }

    private DatosRemate datosComunes(String ref, String text, Matcher idx) {
        Posteriores post = rematesPosteriores(text, idx.start());
        String[] baseGrav = baseAndGrav(text, idx.start(), post.fin1());
        String baseRaw = baseGrav[0];
        String grav = baseGrav[1];
        NumerosEs.Monto monto = NumerosEs.parseAmountNumeric(baseRaw);
        String[] benef = extractBeneficiario(text);
        String seg1 = slice(text, idx.start(), post.fin1());
        String fecha = parseFecha(seg1);
        if (fecha == null) {
            fecha = fechaEn(seg1);
        }
        if (fecha == null) {
            fecha = fechaEn(text.substring(0, idx.start()));
        }
        if (fecha == null) {
            fecha = parseFecha(text.substring(idx.start()));
        }
        String gravClean = grav.isEmpty() ? grav : grav.substring(0, 1).toUpperCase(Locale.ROOT) + grav.substring(1);
        return new DatosRemate(ref, baseRaw, monto.moneda(), monto.valor(), fecha, gravClean,
            post.monto2(), post.fecha2(), post.monto3(), post.fecha3(),
            extractExpediente(text), extractJuzgado(text), benef[0], benef[1]);
    }

    // ------------------------------------------------------------------ dates

    private static final Map<String, Integer> MESES = Map.ofEntries(
        Map.entry("enero", 1), Map.entry("febrero", 2), Map.entry("marzo", 3), Map.entry("abril", 4),
        Map.entry("mayo", 5), Map.entry("junio", 6), Map.entry("julio", 7), Map.entry("agosto", 8),
        Map.entry("setiembre", 9), Map.entry("septiembre", 9), Map.entry("octubre", 10),
        Map.entry("noviembre", 11), Map.entry("diciembre", 12));

    private static final Map<String, Integer> UNIDADES = new LinkedHashMap<>();

    static {
        Object[] u = {"cero", 0, "uno", 1, "un", 1, "una", 1, "dos", 2, "tres", 3, "cuatro", 4, "cinco", 5,
            "seis", 6, "siete", 7, "ocho", 8, "nueve", 9, "diez", 10, "once", 11, "doce", 12, "trece", 13,
            "catorce", 14, "quince", 15, "dieciseis", 16, "dieciséis", 16, "diecisiete", 17, "dieciocho", 18,
            "diecinueve", 19, "veinte", 20, "veintiuno", 21, "veintiún", 21, "veintidos", 22, "veintidós", 22,
            "veintitres", 23, "veintitrés", 23, "veinticuatro", 24, "veinticinco", 25, "veintiseis", 26,
            "veintiséis", 26, "veintisiete", 27, "veintiocho", 28, "veintinueve", 29, "treinta", 30};
        for (int i = 0; i < u.length; i += 2) {
            UNIDADES.put((String) u[i], (Integer) u[i + 1]);
        }
    }

    private static final Pattern TREINTA_Y_UNO = re("treinta y (uno|un)");
    private static final Pattern DOS_MIL = re("dos mil ([a-záéíóúñ]*)");

    private static Integer wordToDay(String s) {
        s = strip(s).toLowerCase(Locale.ROOT);
        if (s.equals("primero")) {
            return 1;
        }
        if (UNIDADES.containsKey(s)) {
            return UNIDADES.get(s);
        }
        if (match(TREINTA_Y_UNO, s) != null) {
            return 31;
        }
        return null;
    }

    private static Integer wordToYear(String s) {
        s = strip(s).toLowerCase(Locale.ROOT);
        Matcher m = search(DOS_MIL, s);
        if (m == null) {
            return null;
        }
        String rest = strip(m.group(1));
        return rest.isEmpty() ? 2000 : 2000 + UNIDADES.getOrDefault(rest, 0);
    }

    private static final Pattern FECHA_PALABRAS = rei(
        "del ([a-záéíóúñ]+(?: y [a-záéíóúñ]+)?) de ([a-záéíóúñ]+) del?(?: año)? (dos mil [a-záéíóúñ]*)");
    private static final Pattern FECHA_PALABRAS_ANIO_NUM = rei(
        "del ([a-záéíóúñ]+(?: y [a-záéíóúñ]+)?) de ([a-záéíóúñ]+) del?\\s*(\\d{4})\\b");
    private static final Pattern FECHA_SENALAN = rei(
        "se se[ñn]alan?\\s*las?[^\\n]{0,30}?del\\s*(\\d{1,2})[-/](\\d{1,2})[-/](\\d{4})");

    /** {@code parse_fecha}. */
    private static String parseFecha(String text) {
        Matcher m = search(FECHA_PALABRAS, text);
        if (m != null) {
            Integer day = wordToDay(m.group(1));
            Integer month = MESES.get(m.group(2).toLowerCase(Locale.ROOT));
            Integer year = wordToYear(m.group(3));
            if (truthy(day) && truthy(month) && truthy(year)) {
                return String.format("%02d/%02d/%d", day, month, year);
            }
        }
        Matcher m1b = search(FECHA_PALABRAS_ANIO_NUM, text);
        if (m1b != null) {
            Integer day = wordToDay(m1b.group(1));
            Integer month = MESES.get(m1b.group(2).toLowerCase(Locale.ROOT));
            if (truthy(day) && truthy(month)) {
                return String.format("%02d/%02d/%s", day, month, m1b.group(3));
            }
        }
        Matcher m2 = search(FECHA_SENALAN, text);
        if (m2 != null) {
            return String.format("%02d/%02d/%s",
                Integer.parseInt(m2.group(1)), Integer.parseInt(m2.group(2)), m2.group(3));
        }
        return null;
    }

    private static final String MES_RE =
        "(enero|febrero|marzo|abril|mayo|junio|julio|agosto|setiembre|septiembre|octubre|noviembre|diciembre)";
    private static final String DIA_RE = "(\\d{1,2}|primero|[a-záéíóúñ]+(?: y [a-záéíóúñ]+)?)";
    private static final String ANIO_RE = "(dos mil(?: [a-záéíóúñ]+)?|\\d{4})";
    private static final Pattern FECHA_HORAS_RE = rei("horas[^.]{0,80}?\\bdel?\\s+" + DIA_RE + "\\s+de\\s+" + MES_RE
        + "\\s+(?:del?\\s+)?(?:año\\s+)?" + ANIO_RE);
    private static final Pattern FECHA_NUM_RE = rei("\\bdel?\\s+(\\d{1,2})[-/](\\d{1,2})[-/](\\d{4})\\b");

    /** {@code fecha_en}: first auction date ("horas ... del") in a segment, as dd/mm/yyyy. */
    private static String fechaEn(String seg) {
        if (!truthy(seg)) {
            return null;
        }
        Matcher m = search(FECHA_HORAS_RE, seg);
        if (m != null) {
            String dW = m.group(1);
            String aW = m.group(3);
            Integer d = isDigits(dW) ? Integer.valueOf(dW) : wordToDay(dW);
            Integer a = isDigits(aW) ? Integer.valueOf(aW) : wordToYear(aW);
            Integer mes = MESES.get(m.group(2).toLowerCase(Locale.ROOT));
            if (truthy(d) && truthy(mes) && truthy(a)) {
                return String.format("%02d/%02d/%d", d, mes, a);
            }
        }
        m = search(FECHA_NUM_RE, seg);
        if (m != null) {
            return String.format("%02d/%02d/%s",
                Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), m.group(3));
        }
        return null;
    }

    // ------------------------------------------------------------------ amounts and later auctions

    private static final Pattern SEGUNDO_RE = rei("segund[oa]\\s+(?:remate|subasta|almoneda)");
    private static final Pattern TERCER_RE = rei("tercer[oa]?\\s+(?:remate|subasta|almoneda)");
    private static final Pattern FIN_RE = rei("\\bNOTAS?\\b|Publ[ií]quese|Se remata por|\\bEXP\\w*\\s*[:.]");
    private static final Pattern BASE_RE = rei("base\\s+(?:[a-záéíóúñ]+\\s+)?de\\s+");

    private static final Pattern NUM_ES_1 = re("\\d{1,3}(?:\\.\\d{3})*(?:,\\d{1,2})?");
    private static final Pattern NUM_ES_2 = re("\\d+(?:,\\d{1,2})?");
    private static final Pattern NUM_ES_US = re("\\d{1,3}(?:,\\d{3})*\\.\\d{2}");

    /** {@code _num_es}: "38.788.454,88" -> 38788454.88 ; "20.000.000" -> 20000000.0 */
    private static Double numEs(String s) {
        s = strip(s);
        if (fullmatch(NUM_ES_1, s) || fullmatch(NUM_ES_2, s)) {
            return NumerosEs.parseDouble(s.replace(".", "").replace(",", "."));
        }
        if (fullmatch(NUM_ES_US, s)) {
            return NumerosEs.parseDouble(s.replace(",", ""));
        }
        return null;
    }

    private static final Pattern MONTO_CORTE = rei("\\(|\\bse se[ñn]al|\\ba las\\b|\\bpara\\b|;");
    private static final Pattern MONTO_CIFRAS = re("([¢₡$]?)\\s*(\\d[\\d\\.,]*\\d)(?!\\s*%)(?![\\d\\.,])");

    /** {@code monto_en}: base amount inside a segment. */
    private static NumerosEs.Monto montoEn(String seg) {
        seg = seg == null ? "" : seg;
        Matcher m = search(BASE_RE, seg);
        if (m == null) {
            return NumerosEs.Monto.NINGUNO;
        }
        String rest = slice(seg, m.end(), m.end() + 400);
        String raw = strip(beforeFirst(MONTO_CORTE, rest), " ,.");
        NumerosEs.Monto monto = NumerosEs.parseAmountNumeric(raw);
        String moneda = monto.moneda();
        Double val = monto.valor();
        if (val == null) {
            // written figures (e.g. ¢15.750.000,00 or (38.788.454,88)); never a percentage such as "(25%"
            Matcher mp = search(MONTO_CIFRAS, rest);
            if (mp != null) {
                val = numEs(mp.group(2));
                if (!truthy(moneda)) {
                    String sym = mp.group(1).equals("¢") ? "₡" : mp.group(1);
                    moneda = sym.isEmpty() ? null : sym;
                }
            }
        }
        return new NumerosEs.Monto(moneda, val);
    }

    private record Posteriores(int fin1, Double monto2, String fecha2, Double monto3, String fecha3) {
    }

    /** {@code remates_posteriores}: end of the first auction segment and the 2nd/3rd auction data. */
    private static Posteriores rematesPosteriores(String text, int start) {
        String sub = text.substring(start);
        Matcher m2 = search(SEGUNDO_RE, sub);
        Matcher m3 = search(TERCER_RE, sub, m2 != null ? m2.end() : 0);
        int fin1 = m2 != null ? m2.start() : m3 != null ? m3.start() : sub.length();
        Double monto2 = null;
        String fecha2 = null;
        Double monto3 = null;
        String fecha3 = null;
        if (m2 != null) {
            int end2;
            if (m3 != null) {
                end2 = m3.start();
            } else {
                Matcher f = search(FIN_RE, sub, m2.end());
                // Same arithmetic as the Python script (f.start() is already absolute there too).
                end2 = f != null ? m2.end() + f.start() : m2.end() + 500;
            }
            String seg2 = slice(sub, m2.start(), end2);
            monto2 = montoEn(seg2).valor();
            fecha2 = fechaEn(seg2);
        }
        if (m3 != null) {
            Matcher f = search(FIN_RE, sub, m3.end());
            String seg3 = slice(sub, m3.start(), f != null ? f.start() : m3.end() + 500);
            monto3 = montoEn(seg3).valor();
            fecha3 = fechaEn(seg3);
        }
        return new Posteriores(start + fin1, monto2, fecha2, monto3, fecha3);
    }

    private static final Pattern BASE_FULL = rei("base\\s+(?:[a-záéíóúñ]+\\s+)?de\\s+(.*)");
    private static final Pattern GRAVAMENES = rei("(libre de gravámenes.*|soportando.*)");
    private static final Pattern CORTE_BASE = re("[)\\.]");

    /** {@code base_and_grav}: [raw base amount, liens text]. */
    private static String[] baseAndGrav(String text, int idxStart, int idxFin) {
        String before = text.substring(0, idxStart);
        Matcher baseM = search(BASE_FULL, before);
        String baseFull;
        if (baseM != null) {
            baseFull = baseM.group(1);
        } else {
            Matcher baseM2 = search(BASE_FULL, slice(text, idxStart, idxFin));
            baseFull = baseM2 != null ? baseM2.group(1) : "";
        }
        Matcher gravM = search(GRAVAMENES, baseFull);
        if (gravM != null) {
            return new String[] {strip(baseFull.substring(0, gravM.start()), " ,;"), strip(gravM.group(1), " ,;.")};
        }
        Matcher cut = search(CORTE_BASE, baseFull);
        String baseAmt = strip(cut != null ? baseFull.substring(0, cut.start()) : baseFull, " ,;()");
        return new String[] {baseAmt, ""};
    }

    // ------------------------------------------------------------------ expediente, juzgado, beneficiario

    private static final String EXP_PREFIX = "EXP\\w*\\s*[:.]{0,2}\\s*(?:N[uú°]?\\.?\\s*)?";
    private static final String EXP_NUM = "[\\d]{2}-\\s?[\\d]{6}\\s?-?[\\d]{4}-[A-Za-z]{2}";
    private static final Pattern EXP_1 = rei(EXP_PREFIX + "(" + EXP_NUM + ")\\s*,?\\s*JUZGADO");
    private static final Pattern EXP_2 = rei(EXP_PREFIX + "(" + EXP_NUM + ")");
    private static final Pattern EXP_3 = rei("expediente\\s*n[uú]mero\\s*(" + EXP_NUM + ")");
    private static final Pattern EXP_4 = rei(EXP_PREFIX + "([\\d\\-A-Za-z]+)");
    private static final Pattern WHITESPACE = re("\\s+");

    /** {@code extract_expediente}. */
    private static String extractExpediente(String text) {
        for (Pattern p : List.of(EXP_1, EXP_2, EXP_3)) {
            Matcher m = search(p, text);
            if (m != null) {
                return sub(WHITESPACE, "", m.group(1));
            }
        }
        Matcher m = search(EXP_4, text);
        return m != null ? rstrip(m.group(1), ".") : "";
    }

    private static final Pattern JUEZ_HACE_SABER = rei(
        "\\b(?:Juez|Jueza)\\s+del\\s+(Juzgado[^;,.]+?)\\s*[;,.]?\\s*HACE SABER");
    private static final Pattern JUZGADO_HACE_SABER = rei("\\s*(?:EL|LA)?\\s*(Juzgado[^,]+?)\\s*,\\s*hace saber");
    private static final Pattern JUZGADO_ANTES_EXP = rei("((?:JUZGADO|TRIBUNAL)[^.]{0,100}?)\\s*\\.\\s*EXP\\w*\\s*[:.]");
    private static final Pattern EXP_ANTES_JUZGADO = rei(EXP_PREFIX + EXP_NUM + "\\s*,?\\s*(?=JUZGADO)");
    private static final Pattern EXP_NUM_COMPLETO = rei(EXP_PREFIX + EXP_NUM);
    private static final Pattern EXP_CUALQUIERA = rei(EXP_PREFIX + "[\\d\\-A-Za-z]+");
    private static final Pattern JUZGADO_CORTE = rei("\\bpara tal efecto\\b|\\bse se[ñn]alan?\\b"
        + "|\\bhora y fecha de emisi[oó]n\\b|\\d{1,2}\\s+de\\s+[a-záéíóúñ]+\\s+del?\\s+año\\s+\\d{4}");
    private static final Pattern FECHA_EMISION = rei("\\d{1,2}\\s+de\\s+[a-záéíóúñ]+\\s+del?\\s+año\\s+\\d{4}");
    private static final Pattern PUNTUACION_INICIAL = re("^[,;:\\s]+");

    /** {@code extract_juzgado}. */
    private static String extractJuzgado(String text) {
        Matcher m = search(JUEZ_HACE_SABER, text);
        if (m != null) {
            return strip(m.group(1));
        }
        m = match(JUZGADO_HACE_SABER, text);
        if (m != null) {
            return strip(m.group(1));
        }
        m = search(JUZGADO_ANTES_EXP, text);
        if (m != null) {
            return strip(m.group(1));
        }
        Matcher mExp = search(EXP_ANTES_JUZGADO, text);
        if (mExp == null) {
            mExp = search(EXP_NUM_COMPLETO, text);
        }
        if (mExp == null) {
            mExp = search(EXP_CUALQUIERA, text);
        }
        if (mExp == null) {
            return "";
        }
        String seg = lstrip(slice(text, mExp.end(), mExp.end() + 300), ". ");
        int dot = seg.indexOf('.');
        String juzgado = strip(dot >= 0 ? seg.substring(0, dot) : seg, " .");
        Matcher cutoff = search(JUZGADO_CORTE, juzgado);
        if (cutoff != null) {
            juzgado = strip(juzgado.substring(0, cutoff.start()), " .,;");
        }
        if (juzgado.isEmpty() || juzgado.length() > 120) {
            Matcher mDate = search(FECHA_EMISION, seg);
            if (mDate != null) {
                juzgado = strip(seg.substring(0, mDate.start()), " .");
            }
        }
        return sub(PUNTUACION_INICIAL, "", juzgado);
    }

    private static final Pattern INTERPUESTO_POR = rei("interpuesto\\s+por");
    private static final Pattern BENEFICIARIO_RE = re(
        "(?i:PROCESO\\s+(?:[A-ZÁÉÍÓÚÑ]+\\s+){1,8}?)de:?\\s+(.+?)\\s+(?i:contra)\\s+(.+?)\\s+(?i:EXP\\b|expediente)");
    private static final Pattern BENEFICIARIO_RE2 = rei(
        "proceso\\s+.{0,60}?interpuesto\\s+por\\s+(.+?)\\s+contra\\s+(?:la\\s+|el\\s+)?(.+?)\\s*[,.]?\\s*EXP");

    /** {@code extract_beneficiario}: [beneficiario, demandado]. */
    private static String[] extractBeneficiario(String text) {
        if (search(INTERPUESTO_POR, text) != null) {
            Matcher m2 = search(BENEFICIARIO_RE2, text);
            if (m2 != null) {
                return new String[] {strip(m2.group(1), " .,"), strip(m2.group(2), " .,")};
            }
        }
        Matcher m = search(BENEFICIARIO_RE, text);
        if (m != null) {
            String demandado = m.group(2);
            int comma = demandado.indexOf(',');
            return new String[] {strip(m.group(1), " .,"),
                strip(comma >= 0 ? demandado.substring(0, comma) : demandado, " .,")};
        }
        Matcher m2 = search(BENEFICIARIO_RE2, text);
        if (m2 != null) {
            return new String[] {strip(m2.group(1), " .,"), strip(m2.group(2), " .,")};
        }
        return new String[] {"", ""};
    }

    // ------------------------------------------------------------------ vehicles

    private static final List<String> VEH_LABELS = List.of("Año Fabricación", "Año de Fabricación",
        "Ano Fabricacion", "Ano de Fabricacion", "Número de Serie", "Numero de Serie", "N. de Serie",
        "# de Serie", "N de Serie", "# de Chasis", "Número Chasis", "Numero Chasis", "N. Motor", "Número Motor",
        "Numero Motor", "N Motor", "Motor N", "Motor Nº", "Motor N°", "Nº Motor", "N. de Motor", "Marca", "Maraca",
        "Estilo y Modelo", "Estilo", "Categoría", "Categoria", "Clase", "Capacidad", "Color", "Tracción",
        "Carrocería", "Carroceria", "Combustible", "Cilindrada", "Cilindros", "Potencia", "VIN", "Vin", "Chasis",
        "Serie", "Placas", "Placa", "Modelo", "Uso", "Año", "AO");
    private static final Pattern LABEL_PATTERN;

    static {
        List<String> quoted = new ArrayList<>();
        for (String label : new LinkedHashSet<>(VEH_LABELS)) {
            quoted.add(Pattern.quote(label));
        }
        // longest labels first, like sorted(set(VEH_LABELS), key=len, reverse=True)
        quoted.sort((a, b) -> Integer.compare(b.length(), a.length()));
        LABEL_PATTERN = rei("\\b(" + String.join("|", quoted) + ")\\s*:?\\s*");
    }

    private static final Pattern DOS_MIL_INICIO = rei("\\s*dos mil\\b");
    private static final Pattern DE_DEL_FINAL = rei("\\bdel?\\s+$");
    private static final Pattern PUNTO_O_PUNTO_COMA = re("[.;]");

    /** {@code parse_vehiculo_fields}: "Label: value" pairs found in the segment (first one wins). */
    private static Map<String, String> parseVehiculoFields(String seg) {
        List<int[]> matches = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        Matcher m = LABEL_PATTERN.matcher(seg);
        while (m.find()) {
            String label = strip(m.group(1)).toLowerCase(Locale.ROOT);
            if (label.equals("año") || label.equals("ao")) {
                // "... de noviembre del año 2025" is a date, not the vehicle's year
                if (search(DE_DEL_FINAL, seg.substring(0, m.start())) != null) {
                    continue;
                }
                // "año dos mil..." without a colon is also a date; "año: dos mil diez" is the value
                String tail = slice(seg, m.end(), m.end() + 20);
                if (!m.group().contains(":") && match(DOS_MIL_INICIO, tail) != null) {
                    continue;
                }
            }
            matches.add(new int[] {m.start(), m.end()});
            labels.add(label);
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (int i = 0; i < matches.size(); i++) {
            int start = matches.get(i)[1];
            int end;
            if (i + 1 < matches.size()) {
                end = matches.get(i + 1)[0];
            } else {
                String window = slice(seg, start, start + 120);
                Matcher stop = search(PUNTO_O_PUNTO_COMA, window);
                end = start + (stop != null ? stop.start() : Math.min(window.length(), 40));
            }
            fields.putIfAbsent(labels.get(i), strip(slice(seg, start, end), " ,.;"));
        }
        return fields;
    }

    private static final Pattern PREFIJO_NUMERO = rei("^(n[uú]mero|n[°ºo]\\.?|#)\\s*");
    private static final Pattern GUION_ESPACIO = re("-\\s+");
    private static final Pattern PLACA_PREFIJO = re("([A-Za-z]{1,4})[\\s\\-]+([0-9]{3,7})");
    private static final Set<String> PREFIJOS_PLACA = Set.of("MOT", "CL", "TL", "TSJ", "C");

    /** {@code normalize_placa}. */
    private static String normalizePlaca(String raw) {
        if (!truthy(raw)) {
            return raw;
        }
        raw = strip(raw, " .,;");
        raw = strip(sub(PREFIJO_NUMERO, "", raw));
        raw = sub(GUION_ESPACIO, "-", raw);
        Matcher m = PLACA_PREFIJO.matcher(raw);
        if (m.matches()) {
            String prefix = m.group(1).toUpperCase(Locale.ROOT);
            if (PREFIJOS_PLACA.contains(prefix)) {
                return prefix + "-" + m.group(2);
            }
        }
        return raw;
    }

    private static final Pattern NO_INSCRITO = rei(
        "\\s*(?:(?:el|del)?\\s*veh[ií]culo\\s*){1,2}(?:NO\\s+INSCRITO|SIN\\s+PLACA)");
    private static final Pattern CORTE_PLACA = re("[,.;]");
    private static final Pattern INICIO_VEHICULO = rei("^\\s*(?:(?:el|del)?\\s*veh[ií]culo\\s*:?\\s*){1,2}");
    private static final Pattern INICIO_NUMERO = rei("^\\s*(?:n[uú]mero|n[°ºo]\\.?|#)\\s*");
    private static final Pattern PLACA_INICIAL = re("\\s*([A-Z0-9][A-Z0-9\\-]{3,12})\\b");
    private static final Pattern PLACA_LETRAS_NUM = re("\\s*([A-Za-z]{1,4})\\s+(\\d{3,7})\\b");
    private static final Pattern MARCA_ANTES_ESTILO = re(
        "\\s*([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑ0-9]{1,20})\\s+(?=Estilo|Categor)");
    private static final Pattern CATEGORIA = rei(",?\\s*CATEGOR[IÍ]A");
    private static final Pattern NO_DIGITO = re("\\D");
    private static final Pattern PLACA_ETIQUETA = rei("PLACA[S]?\\s*#?\\s*:?\\s*([A-Z0-9][A-Z0-9\\-]{2,12})");

    /** {@code get_veh_data}. */
    private static Vehiculo getVehData(DatosRemate datos, String text, int idxEnd) {
        String seg = slice(text, idxEnd, idxEnd + 700);
        boolean noInscrito = match(NO_INSCRITO, seg) != null;
        Map<String, String> fields = parseVehiculoFields(seg);
        String placa = null;
        if (!noInscrito) {
            for (String key : List.of("placas", "placa")) {
                if (fields.containsKey(key)) {
                    String raw = or(fields.get(key));
                    Matcher cut = search(CORTE_PLACA, raw);
                    String rawPlaca = strip(cut != null ? raw.substring(0, cut.start()) : raw);
                    placa = normalizePlaca(rawPlaca);
                    String extra = strip(slice(raw, rawPlaca.length()), " ,.;");
                    if (truthy(extra) && !truthy(fields.get("marca")) && !truthy(fields.get("maraca"))) {
                        fields.put("marca", extra);
                    }
                    break;
                }
            }
            if (!truthy(placa)) {
                String seg2 = sub(INICIO_VEHICULO, "", seg);
                seg2 = sub(INICIO_NUMERO, "", seg2);
                Matcher m = match(PLACA_INICIAL, seg2);
                if (m != null) {
                    placa = normalizePlaca(m.group(1));
                } else {
                    Matcher m2b = match(PLACA_LETRAS_NUM, seg2);
                    if (m2b != null) {
                        placa = normalizePlaca(m2b.group(1) + "-" + m2b.group(2));
                        Matcher brand = match(MARCA_ANTES_ESTILO, seg2.substring(m2b.end()));
                        if (brand != null && !truthy(fields.get("marca")) && !truthy(fields.get("maraca"))) {
                            fields.put("marca", brand.group(1));
                        }
                    }
                }
            }
        }
        String marca = or(fields.get("marca"), fields.get("maraca"));
        String estilo = or(fields.get("estilo"), fields.get("estilo y modelo"));
        estilo = strip(beforeFirst(CATEGORIA, estilo));
        marca = strip(beforeFirst(CATEGORIA, marca));
        String anio = or(fields.get("año fabricación"), fields.get("año de fabricación"),
            fields.get("ano fabricacion"), fields.get("ano de fabricacion"), fields.get("año"), fields.get("ao"));
        if (!anio.isEmpty() && search(DIGITO, anio) == null) {
            Long anioNum = NumerosEs.wordsToInt(anio); // "dos mil diez" -> 2010
            anio = anioNum != null && anioNum != 0 ? String.valueOf(anioNum) : anio;
        }
        anio = sub(NO_DIGITO, "", anio);
        anio = anio.substring(0, Math.min(4, anio.length()));
        placa = strip(or(placa), " .#:");
        if (!noInscrito && (placa.isEmpty() || placa.equals("#"))) {
            Matcher m2 = search(PLACA_ETIQUETA, seg);
            if (m2 != null) {
                placa = normalizePlaca(m2.group(1));
            }
        }
        return new Vehiculo(datos, placa, strip(marca, " ."), strip(estilo, " ."), anio);
    }

    // ------------------------------------------------------------------ fincas

    private static final List<String> PROVINCIAS = List.of("SAN JOSÉ", "SAN JOSE", "ALAJUELA", "CARTAGO",
        "HEREDIA", "GUANACASTE", "PUNTARENAS", "LIMÓN", "LIMON");
    private static final List<Pattern> PROVINCIA_PATTERNS = new ArrayList<>();

    static {
        List<String> sorted = new ArrayList<>(PROVINCIAS);
        sorted.sort((a, b) -> Integer.compare(b.length(), a.length()));
        for (String prov : sorted) {
            PROVINCIA_PATTERNS.add(rei("\\b" + prov.replace("É", "[EÉ]").replace("Ó", "[OÓ]") + "\\b"));
        }
    }

    /** {@code find_provincia}: province name in the segment, or "". */
    private static String findProvincia(String segAfter) {
        for (Pattern p : PROVINCIA_PATTERNS) {
            Matcher m = search(p, segAfter);
            if (m != null) {
                String name = m.group().toUpperCase(Locale.ROOT);
                if (name.equals("SAN JOSE")) {
                    name = "SAN JOSÉ";
                }
                if (name.equals("LIMON")) {
                    name = "LIMÓN";
                }
                return name;
            }
        }
        return "";
    }

    private static final Pattern NUM_WORD_PREFIX_RE = rei(
        "^(?:primero|primer|cero|uno|una|un|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|diez|once|doce|trece|"
            + "catorce|quince|dieciseis|dieciséis|diecisiete|dieciocho|diecinueve|veinte|veintiuno|veintiún|"
            + "veintidos|veintidós|veintitres|veintitrés|veinticuatro|veinticinco|veintiseis|veintiséis|"
            + "veintisiete|veintiocho|veintinueve|treinta)\\s*:?\\s*(?:de\\s+)?");
    private static final Pattern PREFIJO_CODIGO = re("^[0-9\\-\\s]+");
    private static final Pattern DEL_FINAL = rei("\\s+del?$");

    /** {@code strip_code_prefix}: "01 SAN PEDRO" / "uno: SAN PEDRO" -> "SAN PEDRO". */
    private static String stripCodePrefix(String s) {
        String original = strip(s, " ,.");
        String s2 = sub(PREFIJO_CODIGO, "", s);
        s2 = sub(NUM_WORD_PREFIX_RE, "", s2);
        s2 = sub(DEL_FINAL, "", strip(s2));
        s2 = strip(s2, " ,.");
        return s2.isEmpty() ? original : s2;
    }

    private static final String MATRICULA = "matr[ií]cula\\s*(?:de folio real\\s*)?(?:n[uú]mero\\s*)?";
    private static final Pattern MAT_DERECHO_CUAL = rei(
        MATRICULA + "([^,]+?),\\s*derecho[s]?\\s*(.+?),?\\s*la cual es");
    private static final Pattern MAT_DERECHO = rei(MATRICULA + "([^,]+?),\\s*derecho[s]?\\s*([^,\\.]+)");
    private static final Pattern MAT_SOLA = rei(MATRICULA + "([0-9A-Za-z\\-]+)");
    private static final Pattern PARTIDO_GUION = rei("partido de\\s*[A-ZÁÉÍÓÚÑ]+-([0-9A-Za-z\\-]+),\\s*la cual es");
    // the value must contain a digit: in "la finca: FINCA: 123456" the registration number is not "FINCA"
    private static final Pattern FINCA_DOS_PUNTOS = rei("\\bFINCA:\\s*(?=[0-9A-Za-z\\-]*\\d)([0-9A-Za-z\\-]+)");
    private static final Pattern DERECHO_DOS_PUNTOS = rei("\\bDERECHO:\\s*([0-9A-Za-z\\-]+)");
    private static final Pattern FINCA_DERECHO = rei("\\bfinca\\s+([0-9A-Za-z\\-]+)\\s+derecho\\s+([0-9A-Za-z\\-]+)");
    private static final Pattern PARTIDO_NUMERO = rei("partido de\\s*[A-ZÁÉÍÓÚÑ]+(?:\\s+[A-ZÁÉÍÓÚÑ]+)*\\s+n[uú]mero"
        + "\\s+([0-9A-Za-z\\-]+)\\s+DERECHO:?\\s*([0-9A-Za-z\\-]+)");
    private static final Pattern NUMERO_DE_FINCA = rei("n[uú]mero de finca\\s+([0-9A-Za-z\\-]+)");
    // a period followed by a digit is a thousands/decimal separator ("MIDE: 1.500 METROS"), not the end of the phrase
    private static final Pattern MIDE = rei("MIDE[:.]?\\s*(.*?)(?=PLANO:|\\.(?!\\d)|$)");
    private static final Pattern AREA_DE = rei(
        "(?:con\\s+un[a]?\\s+)?[aá]rea\\s+de\\s+(.*?)(?=,\\s*ubicad|\\.(?!\\d)|$)");
    private static final Pattern DISTRITO = rei("DISTRITO\\s*");
    private static final Pattern CANTON = rei("CANT?[OÓ]N\\s*");
    private static final Pattern PROVINCIA = rei("(?:DE\\s+LA\\s+PROVINCIA\\s+(?:DE\\s+)?|provincia\\s+(?:de\\s+)?)");
    private static final Pattern EN_EL_INICIO = rei("^en el\\s+");
    private static final Pattern EN_EL_FINAL = rei(",?\\s*en el\\s*$");
    private static final Pattern NATURALEZA = rei("naturaleza:");
    private static final Pattern CANTON_PALABRA = rei("CANT?[OÓ]N");
    private static final Pattern PLANO = rei(
        "PLAN[OP]\\w*\\s*(?:catastrado\\s*)?(?:n[uú]mero\\s*)?:?\\s*([A-Za-z]{0,3}-?\\d[\\dA-Za-z\\-]*)");
    private static final String FIN_DESC = "(?:\\s*[Ss]ituada en|\\s*SITUADA EN|\\s*MIDE:)";
    private static final Pattern DESC_CUAL_ES = rei("la cual es\\s*(.+?)" + FIN_DESC);
    private static final Pattern DESC_DERECHO = rei("derecho[s]?\\s*[^\\.,]+,\\s*(?:la cual es\\s*)?(.+?)" + FIN_DESC);
    private static final Pattern DESC_NATURALEZA = rei("NATURALEZA:\\s*(.+?)(?:\\s*SITUADA EN|\\s*MIDE:)");

    /** {@code parse_finca_fields} plus the derived columns computed in {@code process_file}. */
    private static Terreno parseFinca(DatosRemate datos, String text, int idxEnd) {
        String seg = slice(text, idxEnd, idxEnd + 1600);
        String matricula = "";
        String derechos = "";
        Matcher m;
        if ((m = search(MAT_DERECHO_CUAL, seg)) != null) {
            matricula = strip(m.group(1));
            derechos = strip(m.group(2), " ,");
        } else if ((m = search(MAT_DERECHO, seg)) != null) {
            matricula = strip(m.group(1));
            derechos = strip(m.group(2));
        } else if ((m = search(MAT_SOLA, seg)) != null) {
            matricula = strip(m.group(1));
        } else if ((m = search(PARTIDO_GUION, seg)) != null) {
            matricula = strip(m.group(1));
        } else if ((m = search(FINCA_DOS_PUNTOS, seg)) != null) {
            matricula = strip(m.group(1));
            Matcher der = search(DERECHO_DOS_PUNTOS, seg);
            if (der != null) {
                derechos = strip(der.group(1));
            }
        } else if ((m = search(FINCA_DERECHO, seg)) != null) {
            matricula = strip(m.group(1));
            derechos = strip(m.group(2));
        } else if ((m = search(PARTIDO_NUMERO, seg)) != null) {
            matricula = strip(m.group(1));
            derechos = strip(m.group(2));
        } else if ((m = search(NUMERO_DE_FINCA, seg)) != null) {
            matricula = strip(m.group(1));
        }

        String medida = "";
        if ((m = search(MIDE, seg)) != null) {
            medida = strip(m.group(1), " .");
        } else if ((m = search(AREA_DE, seg)) != null) {
            medida = strip(m.group(1), " .");
        }

        String distrito = "";
        String canton = "";
        Matcher mDist = search(DISTRITO, seg);
        Matcher mCant = search(CANTON, seg);
        Matcher mProv = search(PROVINCIA, seg);
        if (mDist != null && mCant != null && mCant.start() < mDist.end()) {
            String cantonRaw = strip(slice(seg, mCant.end(), mDist.start()), " ,.");
            cantonRaw = strip(sub(EN_EL_INICIO, "", cantonRaw), " ,.");
            cantonRaw = strip(sub(EN_EL_FINAL, "", cantonRaw), " ,.");
            canton = stripCodePrefix(cantonRaw);
            int distEnd = mProv != null && mProv.start() > mDist.end() ? mProv.start() : mDist.end() + 40;
            distrito = stripCodePrefix(strip(slice(seg, mDist.end(), distEnd), " ,."));
        } else {
            if (mDist != null && mCant != null && mCant.start() > mDist.end()) {
                distrito = stripCodePrefix(strip(slice(seg, mDist.end(), mCant.start()), " ,."));
            }
            if (mCant != null) {
                Matcher stop = search(NATURALEZA, seg.substring(mCant.end()));
                int cantEnd;
                if (mProv != null && mProv.start() > mCant.end()) {
                    cantEnd = mProv.start();
                } else if (stop != null) {
                    cantEnd = mCant.end() + stop.start();
                } else {
                    cantEnd = mCant.end() + 40;
                }
                String cantonRaw = strip(slice(seg, mCant.end(), cantEnd), " ,.");
                cantonRaw = strip(sub(CANTON_PALABRA, "", cantonRaw), " ,.");
                canton = stripCodePrefix(cantonRaw);
            }
        }
        String provincia = "";
        if (mProv != null) {
            provincia = findProvincia(slice(seg, mProv.end(), mProv.end() + 60));
        }
        if (provincia.isEmpty()) {
            provincia = findProvincia(slice(seg, 0, 400));
        }

        String plano = "";
        if ((m = search(PLANO, seg)) != null) {
            plano = strip(m.group(1));
        }
        String desc = "";
        if ((m = search(DESC_CUAL_ES, seg)) != null) {
            desc = strip(m.group(1), " .-");
        } else if ((m = search(DESC_NATURALEZA, seg)) != null) {
            // explicit "NATURALEZA:" label is tried before "derecho ..., <descripción>", which could run past
            // "SITUADA EN" and return the location as the description
            desc = strip(m.group(1), " .-");
        } else if ((m = search(DESC_DERECHO, seg)) != null) {
            desc = strip(m.group(1), " .-");
        }
        desc = limpiarDescripcion(strip(desc, " .-"));

        return new Terreno(datos, matricula, NumerosEs.parseMatriculaNum(matricula), derechos,
            cleanDerechosDisplay(formatDerechos(derechos)), medida, NumerosEs.parseAreaNumeric(medida), desc,
            distrito, canton, provincia, plano);
    }

    // "FINCA SE ENCUENTRA EN ZONA CATASTRADA" is a cadastral note, not part of the description
    // (sometimes it arrives as "INCA SE ENCUENTRA...", without the F)
    private static final Pattern ZONA_CATASTRADA = rei(
        "[,.;]?\\s*\\bF?INCA\\s+SE\\s+ENCUENTRA\\s+EN\\s+ZONA\\s+(?:NO\\s+)?CATASTRADA\\b[,.;]?");

    // "la cual es terreno TERRENO PARA CONSTRUIR": the edict's "terreno" plus the registry's
    private static final Pattern TERRENO_REPETIDO = rei("^terreno\\s*(?=terreno\\b)");

    // "... matrícula ..., soportando ... Dicha finca se describe así: Terreno para construir": the text before is
    // not description
    private static final Pattern SE_DESCRIBE = rei("\\b(?:dicha\\s+finca\\s+|que\\s+)?se\\s+describe\\s+as[ií]\\s*:?\\s*");
    // "la cual es la cual es terreno ...": the edict repeats the phrase
    private static final Pattern LA_CUAL_ES = rei("^la\\s+cual\\s+es\\s+");

    /**
     * {@code limpiar_descripcion}: removes the NATURALEZA label, the "Dicha finca se describe así:" phrase, the
     * "zona catastrada" note, leftover punctuation at both ends ("TERRENO CON 1 CASA," -> "TERRENO CON 1 CASA"),
     * a leading "la cual es" and a duplicated leading "terreno".
     */
    private static String limpiarDescripcion(String desc) {
        desc = limpiarNaturaleza(desc);
        Matcher m = search(SE_DESCRIBE, desc);
        if (m != null) {
            desc = desc.substring(m.end());
        }
        desc = strip(sub(ZONA_CATASTRADA, "", desc), " .,;:-");
        desc = sub(LA_CUAL_ES, "", desc);
        return sub(TERRENO_REPETIDO, "", desc);
    }

    private static final Pattern NATURALEZA_ETIQUETA = rei("\\bnaturaleza\\s*:\\s*");
    private static final Pattern NATURALEZA_INICIO = rei("^(?:(?:la cual es|terreno|de)\\s+)*naturaleza\\b\\s*");

    /**
     * {@code limpiar_naturaleza}: removes the NATURALEZA label from the description.
     * "terreno NATURALEZA: SOLAR CON UNA CASA" -> "SOLAR CON UNA CASA";
     * "de naturaleza terreno con una casa" -> "terreno con una casa";
     * "... FOLIO REAL ... DERECHO CERO. NATURALEZA: LOTE 5" -> "LOTE 5" (text before the label is not description).
     */
    private static String limpiarNaturaleza(String desc) {
        Matcher m = search(NATURALEZA_ETIQUETA, desc);
        if (m != null) {
            return strip(desc.substring(m.end()), " .-");
        }
        return strip(sub(NATURALEZA_INICIO, "", desc), " .-");
    }

    private static final Pattern DERECHO_WORD_RE =rei("\\b(cero|uno|una|un|dos|tres|cuatro|cinco|seis|siete|ocho"
        + "|nueve|diez|once|doce|trece|catorce|quince|dieciseis|dieciséis|diecisiete|dieciocho|diecinueve|veinte"
        + "|treinta)\\b");
    private static final Pattern DIGITO = re("\\d");
    private static final Pattern SEPARADOR_DERECHOS = rei(",|\\by\\b");
    private static final Pattern Y_ENTRE_ESPACIOS = rei("\\s+y\\s+");

    /** {@code format_derechos}: "uno y dos" -> "001, 002". */
    private static String formatDerechos(String raw) {
        if (!truthy(raw)) {
            return raw;
        }
        String s = strip(raw);
        if (search(DIGITO, s) != null && search(DERECHO_WORD_RE, s) == null) {
            return s;
        }
        List<String> codes = new ArrayList<>();
        for (String part : SEPARADOR_DERECHOS.split(s)) {
            String p = strip(part);
            if (p.isEmpty()) {
                continue;
            }
            Long val = NumerosEs.wordsToInt(p);
            codes.add(val != null ? String.format("%03d", val) : p);
        }
        return codes.isEmpty() ? s : String.join(", ", codes);
    }

    /** {@code clean_derechos_display}. */
    private static String cleanDerechosDisplay(String s) {
        if (!truthy(s)) {
            return s;
        }
        s = strip(lstrip(strip(s), "-"));
        return sub(Y_ENTRE_ESPACIOS, ", ", s);
    }
}
