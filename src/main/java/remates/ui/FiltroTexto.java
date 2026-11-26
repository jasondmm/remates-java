package remates.ui;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Matching rules of the search/filter panel, independent from Swing.
 *
 * <ul>
 *   <li>Text: every word typed must be contained in the value, ignoring case and accents
 *       ("san jose" finds "SAN JOSÉ").</li>
 *   <li>Numbers can be typed as shown (20,500,000.00), in Costa Rican format (20.500.000,00) or plain (20500000).</li>
 *   <li>Comparisons for number and date columns: {@code >}, {@code <}, {@code >=}, {@code <=}, {@code =}
 *       followed by a number or a dd/mm/yyyy date, e.g. {@code >20.000.000} or {@code <=31/01/2026}.
 *       {@code =} with text means "exactly this value".</li>
 * </ul>
 */
public final class FiltroTexto {
    private FiltroTexto() {
    }

    public static final String AYUDA = "<html>Sin distinguir mayúsculas ni tildes; cada palabra debe aparecer.<br>"
        + "<b>-palabra</b> excluye: ej. <b>cartago -turrialba -paraiso</b>.<br>"
        + "<b>+a +b</b>: al menos una de ellas, ej. <b>+cartago +heredia</b>.<br>"
        + "Números y fechas: &gt;, &lt;, &gt;=, &lt;=, = &nbsp;(ej. <b>&gt;20.000.000</b>, <b>&lt;=31/01/2026</b>)."
        + "<br>Esc borra el campo.</html>";

    private static final Pattern MARCAS = Pattern.compile("\\p{M}+");
    private static final Pattern ESPACIOS = Pattern.compile("\\s+");
    private static final Pattern OPERADOR = Pattern.compile("^(>=|<=|>|<|=)\\s*(.*)$");
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("d/M/uuuu")
        .withResolverStyle(ResolverStyle.STRICT);
    private static final DecimalFormat NUM_ES;
    private static final DecimalFormat NUM_PLANO = new DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.US));

    static {
        DecimalFormatSymbols es = new DecimalFormatSymbols(Locale.ROOT);
        es.setGroupingSeparator('.');
        es.setDecimalSeparator(',');
        NUM_ES = new DecimalFormat("#,##0.00", es);
    }

    /** Lower case, without accents and with single spaces. */
    public static String normalizar(String s) {
        String sinTildes = MARCAS.matcher(Normalizer.normalize(s, Normalizer.Form.NFD)).replaceAll("");
        return ESPACIOS.matcher(sinTildes.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }

    /** Words of a search, normalized; empty when the text is blank. */
    public static List<String> palabras(String texto) {
        String n = normalizar(texto == null ? "" : texto);
        return n.isEmpty() ? List.of() : List.of(n.split(" "));
    }

    /**
     * Words of a search: plain words are required (incluir), "-word" excluded (excluir), "+word" alternatives
     * of which at least one must appear (alguno). "terreno +cartago +heredia -turrialba" → incluir [terreno],
     * alguno [cartago, heredia], excluir [turrialba]. Words are normalized; a lone "-" or "+" is ignored.
     */
    public record Terminos(List<String> incluir, List<String> excluir, List<String> alguno) {
        public boolean vacio() {
            return incluir.isEmpty() && excluir.isEmpty() && alguno.isEmpty();
        }
    }

    public static Terminos terminos(String texto) {
        List<String> incluir = new ArrayList<>();
        List<String> excluir = new ArrayList<>();
        List<String> alguno = new ArrayList<>();
        for (String p : palabras(texto)) {
            if (p.startsWith("-")) {
                if (p.length() > 1) {
                    excluir.add(p.substring(1));
                }
            } else if (p.startsWith("+")) {
                if (p.length() > 1) {
                    alguno.add(p.substring(1));
                }
            } else {
                incluir.add(p);
            }
        }
        return new Terminos(List.copyOf(incluir), List.copyOf(excluir), List.copyOf(alguno));
    }

    /**
     * Checkbox list condition: the cell's text (as shown, "No indicado" for missing values) must be one of the
     * ticked values.
     */
    public static Predicate<Object> enValores(Set<String> visibles) {
        return celda -> visibles.contains(ColumnasRemate.textoCelda(celda));
    }

    /** True when the cell contains the (already normalized) word in any of its representations. */
    public static boolean contiene(Object celda, String palabra) {
        for (String r : representaciones(celda)) {
            if (r.contains(palabra)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> representaciones(Object v) {
        if (v == null) {
            return List.of("");
        }
        if (v instanceof Number n) {
            double d = n.doubleValue();
            List<String> l = new ArrayList<>(4);
            l.add(ColumnasRemate.formatearNumero(n));   // 20,500,000.00 (as shown)
            l.add(NUM_ES.format(d));                      // 20.500.000,00
            l.add(NUM_PLANO.format(d));                   // 20500000 / 250.3
            l.add(NUM_PLANO.format(d).replace('.', ',')); // 250,3
            return l;
        }
        return List.of(normalizar(v.toString()));
    }

    /**
     * Condition of a column filter, or {@code null} when the text is blank (filter inactive).
     * An operator with an operand that is not a number or date (e.g. "{@code >abc}") matches nothing.
     */
    public static Predicate<Object> condicion(String texto) {
        String t = texto == null ? "" : texto.strip();
        if (t.isEmpty()) {
            return null;
        }
        Matcher m = OPERADOR.matcher(t);
        if (m.matches() && !m.group(2).isBlank()) {
            return comparacion(m.group(1), m.group(2).strip());
        }
        Terminos terminos = terminos(t);
        if (terminos.vacio()) {
            return null;   // e.g. just "-" or "+"
        }
        return celda -> terminos.incluir().stream().allMatch(p -> contiene(celda, p))
            && terminos.excluir().stream().noneMatch(p -> contiene(celda, p))
            && (terminos.alguno().isEmpty() || terminos.alguno().stream().anyMatch(p -> contiene(celda, p)));
    }

    /**
     * Range condition for numeric columns: {@code minimo <= value <= maximo}; a null bound means "no limit".
     * Returns null when both bounds are null (inactive). Non-numeric cells ("No indicado") never match.
     */
    public static Predicate<Object> rango(Double minimo, Double maximo) {
        if (minimo == null && maximo == null) {
            return null;
        }
        return celda -> celda instanceof Number n
            && (minimo == null || n.doubleValue() >= minimo)
            && (maximo == null || n.doubleValue() <= maximo);
    }

    /**
     * Range condition for dd/mm/yyyy date columns: {@code minimo <= date <= maximo}; a null bound means "no limit".
     * Returns null when both bounds are null (inactive). Cells that are not dates ("No indicado") never match.
     */
    public static Predicate<Object> rangoFechas(LocalDate minimo, LocalDate maximo) {
        if (minimo == null && maximo == null) {
            return null;
        }
        return celda -> {
            LocalDate f = celda == null ? null : parseFecha(celda.toString());
            return f != null && (minimo == null || !f.isBefore(minimo)) && (maximo == null || !f.isAfter(maximo));
        };
    }

    /** True when the text is a comparison (starts with an operator followed by something). */
    public static boolean esComparacion(String texto) {
        Matcher m = OPERADOR.matcher(texto == null ? "" : texto.strip());
        return m.matches() && !m.group(2).isBlank();
    }

    private static Predicate<Object> comparacion(String op, String operando) {
        Double numero = parseNumero(operando);
        if (numero != null) {
            return celda -> celda instanceof Number n && cumple(op, Double.compare(n.doubleValue(), numero));
        }
        LocalDate fecha = parseFecha(operando);
        if (fecha != null) {
            return celda -> {
                LocalDate f = celda == null ? null : parseFecha(celda.toString());
                return f != null && cumple(op, f.compareTo(fecha));
            };
        }
        if (op.equals("=")) {
            String exacto = normalizar(operando);
            return celda -> celda != null && !(celda instanceof Number) && normalizar(celda.toString()).equals(exacto);
        }
        return celda -> false;
    }

    private static boolean cumple(String op, int cmp) {
        return switch (op) {
            case ">" -> cmp > 0;
            case "<" -> cmp < 0;
            case ">=" -> cmp >= 0;
            case "<=" -> cmp <= 0;
            default -> cmp == 0;
        };
    }

    /** "20.500.000,50", "20,500,000.50", "20500000", "250.3" or "250,3"; null if it is not a number. */
    static Double parseNumero(String s) {
        String t = s.replace(" ", "").replace("₡", "").replace("¢", "").replace("$", "");
        try {
            if (t.matches("\\d{1,3}(\\.\\d{3})+(,\\d+)?")) {
                return Double.parseDouble(t.replace(".", "").replace(',', '.'));
            }
            if (t.matches("\\d{1,3}(,\\d{3})+(\\.\\d+)?")) {
                return Double.parseDouble(t.replace(",", ""));
            }
            if (t.matches("\\d+([.,]\\d+)?")) {
                return Double.parseDouble(t.replace(',', '.'));
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    static LocalDate parseFecha(String s) {
        try {
            return LocalDate.parse(s.strip(), FECHA);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
