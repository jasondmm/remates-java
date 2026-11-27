package remates.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

/**
 * Shown when a Boletín row is added to "Remates sincronizados" and its Referencia N° is already there: both rows
 * side by side, the differing values highlighted; the user replaces the values or cancels. Columns flagged as ignored
 * (Estado, the user's own column) are neither shown nor compared.
 */
final class DialogoDiferencias extends JDialog {

    private static final Color DIFERENTE = new Color(0xFF, 0xF2, 0xCC);
    private boolean reemplazar;

    private DialogoDiferencias(Component padre, List<String> columnas, Object[] boletin, Object[] sincronizado,
                               boolean[] difiere, boolean[] ignorar, String referencia, int cuantas) {
        super(SwingUtilities.getWindowAncestor(padre), "La referencia ya está en Remates sincronizados",
            ModalityType.APPLICATION_MODAL);
        DefaultTableModel modelo = new DefaultTableModel(new Object[] {"Columna", "Boletín", "Remates sincronizados"}, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
        List<Boolean> difiereFila = new java.util.ArrayList<>();
        for (int i = 0; i < columnas.size(); i++) {
            if (i < ignorar.length && ignorar[i]) {
                continue;
            }
            modelo.addRow(new Object[] {columnas.get(i), ColumnasRemate.textoCelda(boletin[i]),
                ColumnasRemate.textoCelda(sincronizado[i])});
            difiereFila.add(difiere[i]);
        }
        JTable tabla = new JTable(modelo);
        tabla.setRowHeight(22);
        tabla.getColumnModel().getColumn(0).setPreferredWidth(170);
        tabla.getColumnModel().getColumn(1).setPreferredWidth(330);
        tabla.getColumnModel().getColumn(2).setPreferredWidth(330);
        tabla.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int c) {
                super.getTableCellRendererComponent(t, v, sel, foc, r, c);
                boolean d = difiereFila.get(r);
                if (!sel) {
                    setBackground(d ? DIFERENTE : t.getBackground());
                    setForeground(d ? Color.BLACK : t.getForeground());
                }
                setFont(getFont().deriveFont(d ? Font.BOLD : Font.PLAIN));
                setToolTipText(v == null ? null : v.toString());
                return this;
            }
        });
        JScrollPane scroll = new JScrollPane(tabla);
        scroll.setPreferredSize(new Dimension(860, Math.min(520, 26 + 22 * modelo.getRowCount())));

        JButton cancelar = new JButton("Cancelar");
        cancelar.addActionListener(e -> dispose());
        JButton aceptar = new JButton("Reemplazar valores");
        aceptar.setToolTipText("Pone los valores del Boletín en la fila de Remates sincronizados (el formato se mantiene)");
        aceptar.addActionListener(e -> {
            reemplazar = true;
            dispose();
        });
        JPanel botones = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        botones.add(cancelar);
        botones.add(aceptar);

        JPanel raiz = new JPanel(new BorderLayout(0, 8));
        raiz.setBorder(BorderFactory.createEmptyBorder(10, 10, 8, 10));
        raiz.add(new JLabel("<html>La referencia <b>" + referencia + "</b> ya está en Remates sincronizados. "
            + cuantas + " de " + modelo.getRowCount() + " columnas tienen valores distintos (resaltadas). "
            + "El Estado no se compara ni se reemplaza.</html>"),
            BorderLayout.NORTH);
        raiz.add(scroll, BorderLayout.CENTER);
        raiz.add(botones, BorderLayout.SOUTH);
        setContentPane(raiz);
        getRootPane().setDefaultButton(cancelar);
        pack();
        setLocationRelativeTo(padre);
    }

    /**
     * Asks whether to replace the values; false when cancelled or when both rows are already equal (then the user
     * is just told so).
     */
    static boolean preguntar(Component padre, List<String> columnas, Object[] boletin, Object[] sincronizado,
                             boolean[] ignorar, String referencia) {
        boolean[] difiere = diferencias(boletin, sincronizado);
        int cuantas = 0;
        for (int i = 0; i < difiere.length; i++) {
            if (i < ignorar.length && ignorar[i]) {
                difiere[i] = false;
            }
            cuantas += difiere[i] ? 1 : 0;
        }
        if (cuantas == 0) {
            JOptionPane.showMessageDialog(padre, "La referencia " + referencia
                + " ya está en Remates sincronizados con los mismos valores.", "Sin cambios",
                JOptionPane.INFORMATION_MESSAGE);
            return false;
        }
        DialogoDiferencias d = new DialogoDiferencias(padre, columnas, boletin, sincronizado, difiere, ignorar,
            referencia, cuantas);
        d.setVisible(true);
        return d.reemplazar;
    }

    /** Per column: true when the values differ (amounts compared to the cent, text as shown). */
    static boolean[] diferencias(Object[] a, Object[] b) {
        boolean[] d = new boolean[a.length];
        for (int i = 0; i < a.length; i++) {
            Object x = a[i];
            Object y = i < b.length ? b[i] : null;
            if (x instanceof Number n && y instanceof Number m) {
                d[i] = Math.abs(n.doubleValue() - m.doubleValue()) >= 0.005;
            } else {
                d[i] = !ColumnasRemate.textoCelda(x).strip().equals(ColumnasRemate.textoCelda(y).strip());
            }
        }
        return d;
    }
}
