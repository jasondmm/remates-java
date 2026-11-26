package remates.ui;

import java.text.Collator;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.Locale;

import javax.swing.SortOrder;
import javax.swing.table.TableModel;
import javax.swing.table.TableRowSorter;

/**
 * Row sorter of the result tables. The table model declares every column as {@code Object}, so the default
 * {@code TableRowSorter} compares {@code toString()} values: amounts sort as text (9,000,000 after 10,000,000)
 * and dd/mm/yyyy dates by day. This sorter compares by value instead:
 * <ul>
 *   <li>numbers numerically; dd/mm/yyyy dates chronologically; digit-only text (Referencia N°, Año) as numbers;</li>
 *   <li>other text alphabetically in Spanish (accents and case are not significant first);</li>
 *   <li>missing values ("No indicado", empty) always last, in ascending and in descending order.</li>
 * </ul>
 * It is still a {@link TableRowSorter}, so the filter panel can set its row filter.
 */
final class OrdenadorRemates extends TableRowSorter<TableModel> {

    private static final Collator TEXTO = Collator.getInstance(Locale.forLanguageTag("es"));

    OrdenadorRemates(TableModel modelo) {
        super(modelo);
    }

    @Override
    public Comparator<?> getComparator(int column) {
        return (Object a, Object b) -> comparar(column, a, b);
    }

    /** Compare the values themselves, never their {@code toString()}. */
    @Override
    protected boolean useToString(int column) {
        return false;
    }

    private int comparar(int column, Object a, Object b) {
        boolean faltaA = falta(a);
        boolean faltaB = falta(b);
        if (faltaA || faltaB) {
            if (faltaA && faltaB) {
                return 0;
            }
            int despues = faltaA ? 1 : -1;
            // the sorter negates the result for descending order; negate first so missing values stay last
            return descendente(column) ? -despues : despues;
        }
        return compararValores(a, b);
    }

    private boolean descendente(int column) {
        for (SortKey k : getSortKeys()) {
            if (k.getColumn() == column) {
                return k.getSortOrder() == SortOrder.DESCENDING;
            }
        }
        return false;
    }

    /** "No indicado", empty or null. */
    static boolean falta(Object v) {
        return v == null || v instanceof String s && (s.isBlank() || s.equals(ColumnasRemate.NO_INDICADO));
    }

    /** Natural order of two present values: number, date, digit-only text, then Spanish alphabetical order. */
    static int compararValores(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return Double.compare(x.doubleValue(), y.doubleValue());
        }
        LocalDate fa = fecha(a);
        LocalDate fb = fecha(b);
        if (fa != null && fb != null) {
            return fa.compareTo(fb);
        }
        Long ea = entero(a);
        Long eb = entero(b);
        if (ea != null && eb != null) {
            return Long.compare(ea, eb);
        }
        synchronized (TEXTO) { // Collator is not thread-safe
            return TEXTO.compare(ColumnasRemate.textoCelda(a), ColumnasRemate.textoCelda(b));
        }
    }

    private static LocalDate fecha(Object v) {
        return v instanceof String s && s.length() <= 10 ? FiltroTexto.parseFecha(s) : null;
    }

    private static Long entero(Object v) {
        if (v instanceof String s && !s.isEmpty() && s.length() <= 18 && s.chars().allMatch(Character::isDigit)) {
            return Long.parseLong(s);
        }
        return null;
    }
}
