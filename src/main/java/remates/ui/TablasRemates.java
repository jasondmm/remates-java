package remates.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.font.TextAttribute;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import javax.swing.table.TableModel;

import remates.sincronizados.Formato;

/**
 * Table setup shared by the Boletín and "Remates sincronizados" forms: Excel-like header, number format, column
 * widths, value sorting ({@link OrdenadorRemates}) and an optional per-cell {@link Estilo} (background, font color
 * and size, bold, italic, underline, strikethrough, alignment).
 */
public final class TablasRemates {
    private TablasRemates() {
    }

    static final Color HEADER_FILL = new Color(0x1F, 0x4E, 0x78);
    /** Boletín rows already in "Remates sincronizados". */
    public static final Color VERDE_SINCRONIZADO = new Color(0xE2, 0xF0, 0xD9);
    /** Cells whose last change could not be saved to Google Sheets. */
    public static final Color ROJO_SIN_GUARDAR = new Color(0xF8, 0xD7, 0xDA);
    private static final int ALTO_FILA = 22;
    /** Row and column of the selected cell (crosshair): tint mixed into each cell's own background. */
    static final Color CRUZ = new Color(0x9C, 0xC3, 0xEE);
    private static final float MEZCLA_CRUZ = 0.35f;
    /** Header of the selected cell's column. */
    static final Color HEADER_FILL_ACTIVO = new Color(0x3A, 0x7C, 0xBF);

    /** Formatting of a cell by model position; null = plain. */
    @FunctionalInterface
    public interface Estilo {
        Formato de(int filaModelo, int columnaModelo);
    }

    /** Read-only model with the given headers. */
    public static DefaultTableModel crearModelo(List<String> headers) {
        return new DefaultTableModel(headers.toArray(), 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }

    public static JTable crearTabla(TableModel modelo, int[] widths, Estilo estilo) {
        JTable tabla = new JTable(modelo);
        tabla.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        tabla.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        tabla.setCellSelectionEnabled(true);
        tabla.setRowHeight(ALTO_FILA);
        tabla.setRowSorter(new OrdenadorRemates(modelo)); // sorts by value (numbers, dates) + filtering (PanelFiltros)
        tabla.getTableHeader().setReorderingAllowed(false);

        DefaultTableCellRenderer header = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus,
                                                           int row, int col) {
                super.getTableCellRendererComponent(t, value, selected, focus, row, col);
                setBackground(t != null && col == columnaActiva(t) ? HEADER_FILL_ACTIVO : HEADER_FILL);
                return this;
            }
        };
        header.setOpaque(true);
        header.setForeground(Color.WHITE);
        header.setFont(header.getFont().deriveFont(Font.BOLD));
        header.setHorizontalAlignment(SwingConstants.CENTER);

        DefaultTableCellRenderer celda = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus,
                                                           int row, int col) {
                Object shown = value instanceof Number n ? ColumnasRemate.formatearNumero(n) : value;
                super.getTableCellRendererComponent(t, shown, selected, focus, row, col);
                Formato f = estilo == null ? null
                    : estilo.de(t.convertRowIndexToModel(row), t.convertColumnIndexToModel(col));
                setFont(fuente(t.getFont(), f));
                if (!selected) {
                    Color fondo = f != null && f.fondo() != null ? f.fondo() : t.getBackground();
                    if (row == filaActiva(t) || col == columnaActiva(t)) {
                        fondo = mezclar(fondo, CRUZ, MEZCLA_CRUZ);   // crosshair of the selected cell
                    }
                    setBackground(fondo);
                    setForeground(f != null && f.fuente() != null ? f.fuente() : t.getForeground());
                }
                setHorizontalAlignment(alineacion(f, value, t.getColumnName(col)));
                String texto = shown == null ? null : shown.toString();
                setToolTipText(texto != null && texto.length() > 30 ? wrapTooltip(texto) : null);
                return this;
            }
        };

        for (int i = 0; i < tabla.getColumnCount(); i++) {
            TableColumn col = tabla.getColumnModel().getColumn(i);
            col.setHeaderRenderer(header);
            col.setCellRenderer(celda);
            col.setPreferredWidth(widths[i] * 7 + 10); // Excel character width -> pixels
        }
        resaltarCruz(tabla);
        return tabla;
    }

    /**
     * Repaints the whole table (and its header) when the selected cell moves, so the crosshair follows it: the
     * default repaints only the cells whose selection changed.
     */
    static void resaltarCruz(JTable tabla) {
        int[] ultima = {-2, -2};
        Runnable alMover = () -> {
            int f = filaActiva(tabla);
            int c = columnaActiva(tabla);
            if (f != ultima[0] || c != ultima[1]) {
                ultima[0] = f;
                ultima[1] = c;
                tabla.repaint();
                if (tabla.getTableHeader() != null) {
                    tabla.getTableHeader().repaint();
                }
            }
        };
        tabla.getSelectionModel().addListSelectionListener(e -> alMover.run());
        tabla.getColumnModel().getSelectionModel().addListSelectionListener(e -> alMover.run());
        tabla.getModel().addTableModelListener(e -> alMover.run());
    }

    /** View row of the selected (lead) cell, or -1 when nothing is selected. */
    static int filaActiva(JTable t) {
        if (t.getSelectedRowCount() == 0 || t.getSelectedColumnCount() == 0) {
            return -1;
        }
        int f = t.getSelectionModel().getLeadSelectionIndex();
        return f >= 0 && f < t.getRowCount() && t.isRowSelected(f) ? f : -1;
    }

    /** View column of the selected (lead) cell, or -1 when nothing is selected. */
    static int columnaActiva(JTable t) {
        if (filaActiva(t) < 0 || t.getSelectedColumnCount() == t.getColumnCount()) {
            return -1;   // nothing selected, or full rows (row numbers): no column to mark
        }
        int c = t.getColumnModel().getSelectionModel().getLeadSelectionIndex();
        return c >= 0 && c < t.getColumnCount() && t.isColumnSelected(c) ? c : -1;
    }

    /** {@code base} with {@code peso} (0..1) of {@code tinte}. */
    static Color mezclar(Color base, Color tinte, float peso) {
        return new Color(
            Math.round(base.getRed() * (1 - peso) + tinte.getRed() * peso),
            Math.round(base.getGreen() * (1 - peso) + tinte.getGreen() * peso),
            Math.round(base.getBlue() * (1 - peso) + tinte.getBlue() * peso));
    }

    /** The table font with a cell's size (Sheets points, 10 = normal), bold, italic, underline and strikethrough. */
    static Font fuente(Font base, Formato f) {
        if (f == null) {
            return base;
        }
        int estilo = (f.esNegrita() ? Font.BOLD : 0) | (f.esCursiva() ? Font.ITALIC : 0);
        Font r = base.deriveFont(estilo, f.tamano() == null ? base.getSize2D() : base.getSize2D() * f.tamano() / 10f);
        if (f.esSubrayado() || f.esTachado()) {
            Map<TextAttribute, Object> atributos = new HashMap<>();
            if (f.esSubrayado()) {
                atributos.put(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON);
            }
            if (f.esTachado()) {
                atributos.put(TextAttribute.STRIKETHROUGH, TextAttribute.STRIKETHROUGH_ON);
            }
            r = r.deriveFont(atributos);
        }
        return r;
    }

    /** The cell's own alignment if it has one; otherwise amounts and dates (whole date columns) to the right. */
    private static int alineacion(Formato f, Object value, String columna) {
        String a = f == null ? null : f.alineacion();
        if ("CENTER".equals(a)) {
            return SwingConstants.CENTER;
        }
        if ("RIGHT".equals(a)) {
            return SwingConstants.RIGHT;
        }
        if ("LEFT".equals(a)) {
            return SwingConstants.LEFT;
        }
        return value instanceof Number || ColumnasRemate.FECHA_COLS.contains(columna)
            ? SwingConstants.RIGHT : SwingConstants.LEFT;
    }

    /** Taller rows where a cell uses a bigger font; {@code acompanante} (row header) gets the same heights. */
    public static void ajustarAltos(JTable tabla, Estilo estilo, JTable acompanante) {
        for (int r = 0; r < tabla.getRowCount(); r++) {
            int alto = ALTO_FILA;
            int filaModelo = tabla.convertRowIndexToModel(r);
            for (int c = 0; c < tabla.getColumnCount(); c++) {
                Formato f = estilo.de(filaModelo, tabla.convertColumnIndexToModel(c));
                if (f != null && f.tamano() != null && f.tamano() > 10) {
                    alto = Math.max(alto, tabla.getFontMetrics(fuente(tabla.getFont(), f)).getHeight() + 6);
                }
            }
            if (tabla.getRowHeight(r) != alto) {
                tabla.setRowHeight(r, alto);
            }
            if (acompanante != null && r < acompanante.getRowCount() && acompanante.getRowHeight(r) != alto) {
                acompanante.setRowHeight(r, alto);
            }
        }
    }

    /** Tab titles: the row count, or "visible de total" while filters hide rows. */
    public static String conteo(JTable tabla) {
        int total = tabla.getModel().getRowCount();
        int visibles = tabla.getRowCount();
        return visibles == total ? String.valueOf(total) : visibles + " de " + total;
    }

    /** Copies the visible rows (filtered and sorted, with headers) as tab-separated text; returns the row count. */
    public static int copiar(JTable tabla) {
        if (tabla.getRowCount() == 0) {
            return 0;
        }
        DecimalFormat plain = new DecimalFormat("0.00");
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < tabla.getColumnCount(); c++) {
            sb.append(c > 0 ? "\t" : "").append(tabla.getColumnName(c));
        }
        sb.append('\n');
        for (int r = 0; r < tabla.getRowCount(); r++) {
            for (int c = 0; c < tabla.getColumnCount(); c++) {
                Object v = tabla.getValueAt(r, c);
                String s = v == null ? "" : v instanceof Number n ? plain.format(n) : v.toString();
                sb.append(c > 0 ? "\t" : "").append(s.replaceAll("[\\t\\r\\n]+", " "));
            }
            sb.append('\n');
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(sb.toString()), null);
        return tabla.getRowCount();
    }

    private static String wrapTooltip(String s) {
        String escaped = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<html><div style='width:400px'>" + escaped + "</div></html>";
    }
}
