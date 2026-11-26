package remates.parser;

import static remates.parser.Py.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Port of {@code es_numbers.py}: Spanish number words, amounts and areas.
 * The display formatters that only produced unused fields ({@code parse_amount}, {@code parse_area})
 * were not ported; their numeric counterparts are what the Excel columns use.
 */
public final class NumerosEs {
    private NumerosEs() {
    }

    /** Currency and value parsed from an amount. Either part may be null. */
    public record Monto(String moneda, Double valor) {
        static final Monto NINGUNO = new Monto(null, null);
    }

    private static final Map<String, Integer> UNITS = new LinkedHashMap<>();
    private static final Map<String, Integer> TENS = new LinkedHashMap<>();
    private static final Map<String, Integer> HUNDREDS = new LinkedHashMap<>();

    static {
        Object[] units = {
            "cero", 0, "un", 1, "uno", 1, "una", 1, "dos", 2, "tres", 3, "cuatro", 4, "cinco", 5, "seis", 6,
            "siete", 7, "ocho", 8, "nueve", 9, "diez", 10, "once", 11, "doce", 12, "trece", 13, "catorce", 14,
            "quince", 15, "dieciseis", 16, "dieciséis", 16, "diecisiete", 17, "dieciocho", 18, "diecinueve", 19,
            "veinte", 20, "veintiuno", 21, "veintiun", 21, "veintiún", 21, "veintiuna", 21, "veintidos", 22,
            "veintidós", 22, "veintitres", 23, "veintitrés", 23, "veinticuatro", 24, "veinticinco", 25,
            "veintiseis", 26, "veintiséis", 26, "veintisiete", 27, "veintiocho", 28, "veintinueve", 29,
        };
        for (int i = 0; i < units.length; i += 2) {
            UNITS.put((String) units[i], (Integer) units[i + 1]);
        }
        Object[] tens = {"treinta", 30, "cuarenta", 40, "cincuenta", 50, "sesenta", 60, "setenta", 70,
            "ochenta", 80, "noventa", 90};
        for (int i = 0; i < tens.length; i += 2) {
            TENS.put((String) tens[i], (Integer) tens[i + 1]);
        }
        Object[] hundreds = {"cien", 100, "ciento", 100, "doscientos", 200, "doscientas", 200, "trescientos", 300,
            "trescientas", 300, "cuatrocientos", 400, "cuatrocientas", 400, "quinientos", 500, "quinientas", 500,
            "seiscientos", 600, "seiscientas", 600, "setecientos", 700, "setecientas", 700, "ochocientos", 800,
            "ochocientas", 800, "novecientos", 900, "novecientas", 900};
        for (int i = 0; i < hundreds.length; i += 2) {
            HUNDREDS.put((String) hundreds[i], (Integer) hundreds[i + 1]);
        }
    }

    /** Alternation of words, longest first (same order as Python's stable {@code sorted(..., reverse=True)}). */
    static String alternation(List<String> words) {
        List<String> sorted = new ArrayList<>(words);
        sorted.sort(Comparator.comparingInt(String::length).reversed());
        return String.join("|", sorted);
    }

    private static final List<String> TENS_UNITS_WORDS = new ArrayList<>();

    static {
        TENS_UNITS_WORDS.addAll(TENS.keySet());
        TENS_UNITS_WORDS.addAll(UNITS.keySet());
    }

    private static final Pattern CONCAT_FIX_RE = rei(
        "\\b(" + alternation(new ArrayList<>(HUNDREDS.keySet())) + ")(" + alternation(TENS_UNITS_WORDS) + ")\\b");

    private static final Pattern MIL_PEGADO_RE;

    static {
        List<String> all = new ArrayList<>(HUNDREDS.keySet());
        all.addAll(TENS_UNITS_WORDS);
        MIL_PEGADO_RE = rei("\\bmil(?=(?:" + alternation(all) + ")\\b)");
    }

    private static final Pattern PUNCT = re("[.,]");
    private static final Pattern PARENS = re("[()]");
    private static final Pattern WS = re("\\s+");

    /** {@code words_to_int}: "dos millones quinientos mil" -> 2500000, or null if no number word is found. */
    public static Long wordsToInt(String phrase) {
        if (phrase == null) {
            return null;
        }
        String s = strip(phrase.toLowerCase(Locale.ROOT));
        s = sub(PARENS, " ", s); // "(treinta" -> " treinta"
        s = sub(PUNCT, "", s);
        s = CONCAT_FIX_RE.matcher(s).replaceAll("$1 $2");
        s = sub(MIL_PEGADO_RE, "mil ", s); // "milcuatrocientos" -> "mil cuatrocientos"
        long total = 0;
        long current = 0;
        boolean foundAny = false;
        for (String t : WS.split(s)) {
            if (t.equals("de") || t.equals("y")) {
                continue;
            }
            if (t.equals("mil")) {
                current = current != 0 ? current : 1;
                total += current * 1000;
                current = 0;
                foundAny = true;
            } else if (t.equals("millon") || t.equals("millón") || t.equals("millones")) {
                if (total < 1_000_000) {
                    if (current == 0 && total == 0) {
                        current = 1;
                    }
                    // "mil ochenta y cuatro millones" = (1000 + 84) millones
                    total = (total + current) * 1_000_000;
                } else {
                    total += (current != 0 ? current : 1) * 1_000_000;
                }
                current = 0;
                foundAny = true;
            } else if (HUNDREDS.containsKey(t)) {
                current += HUNDREDS.get(t);
                foundAny = true;
            } else if (TENS.containsKey(t)) {
                current += TENS.get(t);
                foundAny = true;
            } else if (UNITS.containsKey(t)) {
                current += UNITS.get(t);
                foundAny = true;
            }
        }
        total += current;
        return foundAny ? total : null;
    }

    private static final Map<String, String> CURRENCY_SYMBOL = Map.of(
        "colones", "₡", "colon", "₡", "colón", "₡", "dolares", "$", "dólares", "$", "dolar", "$", "dólar", "$");

    private static final Pattern US_FORMAT = re("([¢₡$])\\s*([\\d,]+)\\.(\\d{2})\\b");
    /** Thousands separated by "." or by a space ("¢ 4 660 000"). */
    private static final Pattern ES_FORMAT = re(
        "([¢₡$])\\s*(\\d{1,3}(?:\\.\\d{3})+|\\d{1,3}(?:[ \\u00a0]\\d{3})+|\\d+)(?:,(\\d{2}))?(?![\\d.,]*\\d)");
    private static final Pattern SEPARADOR_MILES = re("[. \\u00a0]");
    private static final Pattern CURRENCY_WORD = rei(
        "(colones|colon(?:es)?|colón|d[oó]lares?\\s*(?:estadounidenses|americanos)?|dolar|dólar)");
    private static final Pattern NUM_US = re("[¢₡$]?\\s*([\\d,]+)\\.(\\d{2})");
    private static final Pattern NUM_THOUSANDS = re("[¢₡$]?\\s*(\\d{1,3}(?:\\.\\d{3})+)");
    private static final Pattern CENTS = rei("con\\s+(.*?)\\s*(?:c[eé]ntimos|centavos)");
    private static final Pattern SYMBOL_NUM = re("([¢₡$])\\s*([\\d\\.]+)(?:,(\\d{2}))?");

    /** {@code parse_amount_numeric}: (currency symbol, value) or (null, null) if unparseable. */
    public static Monto parseAmountNumeric(String text) {
        if (!truthy(text)) {
            return Monto.NINGUNO;
        }
        String s = strip(text);
        Matcher mUs = match(US_FORMAT, s);
        if (mUs != null) {
            String symbol = mUs.group(1).equals("¢") ? "₡" : mUs.group(1);
            Double v = parseDouble(mUs.group(2).replace(",", "") + "." + mUs.group(3));
            if (v != null) {
                return new Monto(symbol, v);
            }
        }
        // es-CR figure at the start ("¢38.788.454,88 (treinta y ocho millones...)"): the figure wins
        // over the amount in words that follows it.
        Matcher mEs = match(ES_FORMAT, s);
        if (mEs != null) {
            String symbol = mEs.group(1).equals("¢") ? "₡" : mEs.group(1);
            String cents = mEs.group(3) != null ? mEs.group(3) : "00";
            return new Monto(symbol, Double.parseDouble(sub(SEPARADOR_MILES, "", mEs.group(2)) + "." + cents));
        }
        Matcher mCur = search(CURRENCY_WORD, s);
        if (mCur != null) {
            String word = mCur.group(1).toLowerCase(Locale.ROOT);
            String symbol = word.contains("dolar") || word.contains("dólar")
                ? "$" : CURRENCY_SYMBOL.getOrDefault(word, "");
            String mainWords = strip(s.substring(0, mCur.start()));
            String rest = strip(s.substring(mCur.end()));
            Matcher mNum = NUM_US.matcher(mainWords);
            if (mNum.matches()) {
                Double v = parseDouble(mNum.group(1).replace(",", "") + "." + mNum.group(2));
                if (v != null) {
                    return new Monto(symbol, v);
                }
            }
            Matcher mNum2 = NUM_THOUSANDS.matcher(mainWords);
            if (mNum2.matches()) {
                Double v = parseDouble(mNum2.group(1).replace(".", ""));
                if (v != null) {
                    return new Monto(symbol, v);
                }
            }
            Long mainVal = wordsToInt(mainWords);
            long cents = 0;
            Matcher mCents = search(CENTS, rest);
            if (mCents != null) {
                Long c = wordsToInt(mCents.group(1));
                cents = c != null ? c : 0;
            }
            if (mainVal == null) {
                return Monto.NINGUNO;
            }
            return new Monto(symbol, round2(mainVal + cents / 100.0));
        }
        Matcher m2 = match(SYMBOL_NUM, s);
        if (m2 != null) {
            String symbol = m2.group(1).equals("¢") ? "₡" : m2.group(1);
            String intPart = m2.group(2).replace(".", "");
            String decPart = m2.group(3) != null ? m2.group(3) : "00";
            Double v = parseDouble(intPart + "." + decPart);
            return v == null ? Monto.NINGUNO : new Monto(symbol, v);
        }
        Long val = wordsToInt(s);
        if (val != null) {
            return new Monto(null, val.doubleValue());
        }
        return Monto.NINGUNO;
    }

    private static final Pattern AREA = rei(
        "(.*?)\\s*METROS\\s*(?:CON\\s+(.*?)\\s*DEC[IÍ]MET+ROS)?\\s*(?:CUADRADOS?)?");
    private static final Pattern DIGITS_SEP = re("[\\d.,]+");

    /** {@code parse_area_numeric}: area phrase to square meters, or null. */
    public static Double parseAreaNumeric(String text) {
        if (!truthy(text)) {
            return null;
        }
        String s = strip(text);
        Matcher m = search(AREA, s);
        if (m == null) {
            return null;
        }
        String mainWords = strip(m.group(1));
        Double mainVal;
        if (fullmatch(DIGITS_SEP, mainWords)) {
            mainVal = numeroCifras(mainWords);
        } else {
            Long w = wordsToInt(mainWords);
            mainVal = w != null ? w.doubleValue() : null;
        }
        if (mainVal == null) {
            return null;
        }
        long decVal = 0;
        if (m.group(2) != null) {
            Long dv = numberOrWords(strip(m.group(2)));
            decVal = dv != null ? dv : 0;
        }
        return round2(mainVal + decVal / 100.0);
    }

    private static final Pattern CIFRAS_MILES_PUNTO = re("\\d{1,3}(?:\\.\\d{3})+(?:,\\d+)?");
    private static final Pattern CIFRAS_MILES_COMA = re("\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?");
    private static final Pattern CIFRAS_SIMPLE = re("\\d+(?:[.,]\\d+)?");

    /** {@code numero_cifras}: "1.500" -> 1500, "1.500,25" -> 1500.25, "1,500.25" -> 1500.25, "250.30" -> 250.3. */
    static Double numeroCifras(String s) {
        if (fullmatch(CIFRAS_MILES_PUNTO, s)) {
            return parseDouble(s.replace(".", "").replace(",", "."));
        }
        if (fullmatch(CIFRAS_MILES_COMA, s)) {
            return parseDouble(s.replace(",", ""));
        }
        if (fullmatch(CIFRAS_SIMPLE, s)) {
            return parseDouble(s.replace(",", "."));
        }
        return null;
    }

    private static Long numberOrWords(String s) {
        if (fullmatch(DIGITS_SEP, s)) {
            String digits = sub(PUNCT, "", s);
            try {
                return Long.parseLong(digits);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return wordsToInt(s);
    }

    private static final Pattern ASCII_LETTER = re("[a-zA-Z]");
    private static final Pattern DIGIT = re("\\d");

    /** {@code parse_matricula_num}: converts a registration number written in words into digits. */
    public static String parseMatriculaNum(String text) {
        if (!truthy(text)) {
            return text;
        }
        String s = strip(text);
        if (search(ASCII_LETTER, s) != null && search(DIGIT, s) == null) {
            Long val = wordsToInt(s);
            return val != null ? String.valueOf(val) : s;
        }
        return s;
    }

    static Double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_EVEN).doubleValue();
    }

    static Double parseDouble(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
