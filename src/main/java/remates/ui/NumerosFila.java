package remates.ui;

import java.awt.Dimension;

import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * Row-number column ("#") for a result table, shown as the scroll pane's row header: numbers the visible rows
 * (after filtering and sorting) and clicking it selects full rows (Shift/Ctrl for several). Used by Boletín and
 * "Remates sincronizados".
 */
final class NumerosFila {
    private final JTable tabla;
    private final JTable numeros;
    private boolean sincronizando;

    NumerosFila(JTable tabla) {
        this.tabla = tabla;
        numeros = new JTable(new AbstractTableModel() {
            @Override
            public int getRowCount() {
                return tabla.getRowCount();
            }

            @Override
            public int getColumnCount() {
                return 1;
            }

            @Override
            public Object getValueAt(int r, int c) {
                return r + 1;
            }
        });
        numeros.setRowHeight(tabla.getRowHeight());
        numeros.setFocusable(false);
        numeros.setTableHeader(null);
        numeros.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        numeros.setToolTipText("Clic para seleccionar la fila completa (Mayús/Ctrl para varias)");
        DefaultTableCellRenderer r = new DefaultTableCellRenderer();
        r.setHorizontalAlignment(SwingConstants.CENTER);
        r.setBackground(UIManager.getColor("TableHeader.background"));
        numeros.setDefaultRenderer(Object.class, r);
        numeros.setPreferredScrollableViewportSize(new Dimension(46, 0));

        // clicking numbers selects full rows
        numeros.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || sincronizando) {
                return;
            }
            int[] filas = numeros.getSelectedRows();
            if (filas.length == 0) {
                return;
            }
            sincronizando = true;
            try {
                tabla.clearSelection();
                for (int f : filas) {
                    tabla.addRowSelectionInterval(f, f);
                }
                tabla.setColumnSelectionInterval(0, tabla.getColumnCount() - 1);
            } finally {
                sincronizando = false;
            }
        });
        // clicking cells: cell selection, not full rows
        tabla.getSelectionModel().addListSelectionListener(e -> {
            if (!sincronizando) {
                numeros.clearSelection();
            }
        });
        // follows added/removed rows, filtering and sorting (after the sorter has updated)
        tabla.getModel().addTableModelListener(e -> SwingUtilities.invokeLater(this::refrescar));
        if (tabla.getRowSorter() != null) {
            tabla.getRowSorter().addRowSorterListener(e -> refrescar());
        }
    }

    JTable numeros() {
        return numeros;
    }

    /** Recounts the rows and copies the table's row heights (cells with a large font are taller). */
    void refrescar() {
        ((AbstractTableModel) numeros.getModel()).fireTableDataChanged();
        for (int r = 0; r < Math.min(tabla.getRowCount(), numeros.getRowCount()); r++) {
            int alto = tabla.getRowHeight(r);   // 0 while the table is still updating after a filter change
            if (alto > 0 && numeros.getRowHeight(r) != alto) {
                numeros.setRowHeight(r, alto);
            }
        }
    }

    /** The table in a scroll pane with this column on its left and "#" in the corner. */
    JScrollPane scroll() {
        JScrollPane s = new JScrollPane(tabla);
        s.setRowHeaderView(numeros);
        s.setCorner(JScrollPane.UPPER_LEFT_CORNER, new JLabel("#", SwingConstants.CENTER));
        return s;
    }
}
