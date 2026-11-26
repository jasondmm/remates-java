package remates.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javax.swing.RowSorter.SortKey;
import javax.swing.SortOrder;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableModel;
import javax.swing.table.TableRowSorter;

import remates.model.ResultadoBoletin;
import remates.parser.BoletinParser;

/**
 * Sorting of the result tables ({@link OrdenadorRemates}): numbers and dates by value, missing values last in
 * both directions. Runs on small cases and on every column of every boletín in {@code resources/boletintxt}.
 * Plain {@code main}, run from the project root: exit 0 = pass.
 */
public final class OrdenadorRematesTest {
    private static int fallos;
    private static int total;

    public static void main(String[] args) throws Exception {
        casosPequenos();
        datosReales(Path.of(args.length > 0 ? args[0] : "resources/boletintxt"));
        System.out.printf("%d comprobaciones, %d fallos%n", total, fallos);
        System.exit(fallos == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ small cases

    private static void casosPequenos() {
        String ni = ColumnasRemate.NO_INDICADO;
        orden("montos asc", new Object[] {10_000_000.0, 9_000_000.0, ni, 100.5, 20_500_000.0}, SortOrder.ASCENDING,
            new Object[] {100.5, 9_000_000.0, 10_000_000.0, 20_500_000.0, ni});
        orden("montos desc (No indicado al final)", new Object[] {10_000_000.0, 9_000_000.0, ni, 100.5},
            SortOrder.DESCENDING, new Object[] {10_000_000.0, 9_000_000.0, 100.5, ni});
        orden("fechas asc", new Object[] {"02/03/2026", "15/01/2026", ni, "01/12/2025", "31/01/2026"},
            SortOrder.ASCENDING, new Object[] {"01/12/2025", "15/01/2026", "31/01/2026", "02/03/2026", ni});
        orden("fechas desc", new Object[] {"02/03/2026", ni, "15/01/2026", "01/12/2025"}, SortOrder.DESCENDING,
            new Object[] {"02/03/2026", "15/01/2026", "01/12/2025", ni});
        orden("texto de dígitos (año, referencia)", new Object[] {"2015", "998", ni, "2009", "10000"},
            SortOrder.ASCENDING, new Object[] {"998", "2009", "2015", "10000", ni});
        orden("texto en español", new Object[] {"Zarcero", "álamo", "Alajuela", ni, "ÁRBOL", "barva"},
            SortOrder.ASCENDING, new Object[] {"Alajuela", "álamo", "ÁRBOL", "barva", "Zarcero", ni});
    }

    private static void orden(String nombre, Object[] valores, SortOrder sentido, Object[] esperado) {
        DefaultTableModel m = new DefaultTableModel(new Object[] {"c"}, 0);
        for (Object v : valores) {
            m.addRow(new Object[] {v});
        }
        OrdenadorRemates s = new OrdenadorRemates(m);
        s.setSortKeys(List.of(new SortKey(0, sentido)));
        Object[] real = new Object[valores.length];
        for (int i = 0; i < valores.length; i++) {
            real[i] = m.getValueAt(s.convertRowIndexToModel(i), 0);
        }
        check(nombre + ": " + java.util.Arrays.toString(real), java.util.Arrays.equals(real, esperado));
    }

    // ------------------------------------------------------------------ real data

    private static void datosReales(Path dir) throws Exception {
        List<Path> archivos;
        try (Stream<Path> s = Files.list(dir)) {
            archivos = s.filter(p -> p.toString().endsWith(".txt")).sorted().toList();
        }
        int columnasOrdenadas = 0;
        int columnasMalViejo = 0;
        for (Path archivo : archivos) {
            ResultadoBoletin r = new BoletinParser().parse(Files.readString(archivo));
            DefaultTableModel t = new DefaultTableModel(ColumnasRemate.TERRENOS_HEADERS.toArray(), 0);
            r.terrenos().forEach(x -> t.addRow(ColumnasRemate.filaTerreno(x, "1")));
            DefaultTableModel v = new DefaultTableModel(ColumnasRemate.VEH_HEADERS.toArray(), 0);
            r.vehiculos().forEach(x -> v.addRow(ColumnasRemate.filaVehiculo(x, "1")));
            for (DefaultTableModel m : List.of(t, v)) {
                for (int c = 0; c < m.getColumnCount(); c++) {
                    for (SortOrder sentido : List.of(SortOrder.ASCENDING, SortOrder.DESCENDING)) {
                        OrdenadorRemates s = new OrdenadorRemates(m);
                        s.setSortKeys(List.of(new SortKey(c, sentido)));
                        String error = verificar(m, c, sentido, s);
                        columnasOrdenadas++;
                        if (error != null) {
                            check(archivo.getFileName() + " «" + m.getColumnName(c) + "» " + sentido + ": " + error, false);
                        } else {
                            total++;
                        }
                    }
                    // control: the default sorter (compares toString) gets amounts and dates wrong
                    String nombre = m.getColumnName(c);
                    if (ColumnasRemate.NUM_COLS.contains(nombre) || nombre.startsWith("Fecha")) {
                        TableRowSorter<TableModel> viejo = new TableRowSorter<>(m);
                        viejo.setSortKeys(List.of(new SortKey(c, SortOrder.ASCENDING)));
                        if (verificar(m, c, SortOrder.ASCENDING, viejo) != null) {
                            columnasMalViejo++;
                        }
                    }
                }
            }
        }
        System.out.printf("Datos reales: %d archivos, %d ordenamientos (columna × sentido) verificados%n",
            archivos.size(), columnasOrdenadas);
        System.out.printf("Control: el ordenador anterior desordenaba %d columnas de montos/fechas%n", columnasMalViejo);
        check("el control detecta el problema anterior", columnasMalViejo > 0);
    }

    /** Null when the view order is correct: present values in order, missing values all at the end. */
    private static String verificar(TableModel m, int c, SortOrder sentido, TableRowSorter<?> s) {
        List<Object> vista = new ArrayList<>();
        for (int i = 0; i < m.getRowCount(); i++) {
            vista.add(m.getValueAt(s.convertRowIndexToModel(i), c));
        }
        boolean vistoFaltante = false;
        Object anterior = null;
        for (Object v : vista) {
            if (OrdenadorRemates.falta(v)) {
                vistoFaltante = true;
                continue;
            }
            if (vistoFaltante) {
                return "valor «" + v + "» después de un valor faltante";
            }
            if (anterior != null) {
                int cmp = OrdenadorRemates.compararValores(anterior, v);
                if (sentido == SortOrder.ASCENDING ? cmp > 0 : cmp < 0) {
                    return "«" + anterior + "» antes de «" + v + "»";
                }
            }
            anterior = v;
        }
        return null;
    }

    private static void check(String descripcion, boolean ok) {
        total++;
        if (!ok) {
            fallos++;
            System.out.println("FALLA: " + descripcion);
        }
    }

    private OrdenadorRematesTest() {
    }
}
