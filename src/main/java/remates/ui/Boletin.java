package remates.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.concurrent.ExecutionException;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JInternalFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;

import remates.model.ResultadoBoletin;
import remates.parser.BoletinParser;
import remates.sincronizados.Formato;
import remates.sincronizados.HojaSincronizada;

/**
 * "Boletín" form: paste the text of a Boletín Judicial edict, press Procesar and the auctions are listed
 * in two tables (terrenos / vehículos) with the same columns as the Excel produced by the Python scripts.
 * It is a child window of the {@link Remates} MDI window; several can be open at once.
 */
public class Boletin extends JInternalFrame {

    private final BoletinParser parser = new BoletinParser();

    private final JTextArea txtEdicto = new JTextArea();
    private final JTextField txtNumeroBoletin = new JTextField(10);
    private final JButton btnProcesar = new JButton("Procesar");
    private final JButton btnLimpiar = new JButton("Limpiar");
    private final JButton btnCopiar = new JButton("Copiar tabla");
    private final JLabel lblEstado = new JLabel("Pegue el texto del boletín y presione Procesar (Ctrl+Enter).");
    private final JTabbedPane tabs = new JTabbedPane();
    private final DefaultTableModel modeloTerrenos = TablasRemates.crearModelo(ColumnasRemate.TERRENOS_HEADERS);
    private final DefaultTableModel modeloVehiculos = TablasRemates.crearModelo(ColumnasRemate.VEH_HEADERS);
    private final JTable tablaTerrenos = TablasRemates.crearTabla(modeloTerrenos, ColumnasRemate.TERRENOS_WIDTHS,
        (fila, col) -> marca(TIPO_TERRENOS, modeloTerrenos, fila));
    private final JTable tablaVehiculos = TablasRemates.crearTabla(modeloVehiculos, ColumnasRemate.VEH_WIDTHS,
        (fila, col) -> marca(TIPO_VEHICULOS, modeloVehiculos, fila));
    private final PanelFiltros filtros = new PanelFiltros(this::tablaActual, this::actualizarTitulosTabs);

    private ResultadoBoletin resultado;
    /** The MDI window (for "Remates sincronizados"); null when the form is used on its own. */
    private Remates remates;
    public static final String TIPO_TERRENOS = "T";
    public static final String TIPO_VEHICULOS = "V";
    private static final Formato MARCA_SINCRONIZADO = Formato.VACIO.conFondo(TablasRemates.VERDE_SINCRONIZADO);
    private static final String TITULO = "Boletín";
    /** Window number given by the Remates window (1 for the first one); shown only while the form is empty. */
    private int numeroVentana = 1;

    public Boletin() {
        super(TITULO, true, true, true, true);   // resizable, closable, maximizable, iconifiable
        setName("Boletin");
        setFrameIcon(IconoApp.iconoPequeno());
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE); // closes this child only, not the app
        setContentPane(construirContenido());
        setSize(1200, 760);
        actualizarTitulosTabs();
    }

    private JComponent construirContenido() {
        txtEdicto.setLineWrap(true);
        txtEdicto.setWrapStyleWord(true);
        txtEdicto.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        txtEdicto.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "procesar");
        txtEdicto.getActionMap().put("procesar", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                procesar();
            }
        });

        JPanel entrada = new JPanel(new BorderLayout(4, 4));
        entrada.setBorder(BorderFactory.createTitledBorder("Texto del edicto"));
        entrada.add(new JScrollPane(txtEdicto), BorderLayout.CENTER);

        JPanel acciones = new JPanel(new FlowLayout(FlowLayout.LEFT));
        acciones.add(new JLabel("Número de boletín:"));
        acciones.add(txtNumeroBoletin);
        acciones.add(btnProcesar);
        acciones.add(btnLimpiar);
        acciones.add(btnCopiar);
        entrada.add(acciones, BorderLayout.SOUTH);

        btnProcesar.addActionListener(e -> procesar());
        btnLimpiar.addActionListener(e -> limpiar());
        btnCopiar.addActionListener(e -> copiarTablaActual());
        txtNumeroBoletin.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                llenarTablas();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                llenarTablas();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                llenarTablas();
            }
        });

        tablaTerrenos.setName("Terrenos");
        tablaVehiculos.setName("Vehículos");
        agregarConDobleClic(tablaTerrenos, TIPO_TERRENOS);
        agregarConDobleClic(tablaVehiculos, TIPO_VEHICULOS);
        tabs.addTab("", new JScrollPane(tablaTerrenos));
        tabs.addTab("", new JScrollPane(tablaVehiculos));
        filtros.registrar(tablaTerrenos);
        filtros.registrar(tablaVehiculos);
        tabs.addChangeListener(e -> filtros.tablaVisibleCambio()); // filters follow the visible tab

        // search/filter accordion (collapsed at start) above the result list
        JPanel resultados = new JPanel(new BorderLayout());
        resultados.add(filtros, BorderLayout.NORTH);
        resultados.add(tabs, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, entrada, resultados);
        split.setResizeWeight(0.35);
        split.setDividerLocation(260);

        JPanel root = new JPanel(new BorderLayout());
        root.add(split, BorderLayout.CENTER);
        lblEstado.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        root.add(lblEstado, BorderLayout.SOUTH);
        // Ctrl+F only for the Boletin that has the focus (several can be open in the MDI window)
        root.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), "buscar");
        root.getActionMap().put("buscar", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                filtros.abrirYEnfocarBusqueda();
            }
        });
        return root;
    }

    private JTable tablaActual() {
        return tabs.getSelectedIndex() == 1 ? tablaVehiculos : tablaTerrenos;
    }

    // ------------------------------------------------------------------ actions

    private void procesar() {
        String texto = txtEdicto.getText();
        if (texto.isBlank()) {
            lblEstado.setText("No hay texto para procesar.");
            return;
        }
        btnProcesar.setEnabled(false);
        lblEstado.setText("Procesando...");
        new SwingWorker<ResultadoBoletin, Void>() {
            @Override
            protected ResultadoBoletin doInBackground() {
                return parser.parse(texto);
            }

            @Override
            protected void done() {
                btnProcesar.setEnabled(true);
                try {
                    mostrar(get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    lblEstado.setText("Error al procesar el texto.");
                    JOptionPane.showMessageDialog(Boletin.this, String.valueOf(e.getCause()),
                        "Error al procesar", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void mostrar(ResultadoBoletin r) {
        resultado = r;
        if (txtNumeroBoletin.getText().isBlank() && r.numeroBoletin() != null) {
            txtNumeroBoletin.setText(r.numeroBoletin()); // fires llenarTablas()
        } else {
            llenarTablas();
        }
        String estado = r.terrenos().size() + " terrenos, " + r.vehiculos().size() + " vehículos";
        if (txtNumeroBoletin.getText().isBlank()) {
            estado += ". No se encontró el número de boletín en el texto; puede escribirlo arriba.";
        }
        lblEstado.setText(estado);
        if (r.terrenos().isEmpty() && !r.vehiculos().isEmpty()) {
            tabs.setSelectedIndex(1);
        } else {
            tabs.setSelectedIndex(0);
        }
    }

    private void llenarTablas() {
        modeloTerrenos.setRowCount(0);
        modeloVehiculos.setRowCount(0);
        if (resultado != null) {
            String num = txtNumeroBoletin.getText().strip();
            resultado.terrenos().forEach(t -> modeloTerrenos.addRow(ColumnasRemate.filaTerreno(t, num)));
            resultado.vehiculos().forEach(v -> modeloVehiculos.addRow(ColumnasRemate.filaVehiculo(v, num)));
        }
        filtros.datosActualizados(); // refreshes value suggestions and re-applies the filters
        actualizarTitulosTabs();
        actualizarTitulo();
    }

    /** Window number given by the Remates window; an empty form shows it as "Boletín (2)". */
    public void setNumeroVentana(int numero) {
        numeroVentana = numero;
        actualizarTitulo();
    }

    /**
     * "Boletín - 191" once a text is processed (the number of the box, as used in the rows), without the window
     * number. An empty form shows "Boletín", or "Boletín (2)", "Boletín (3)"... for further windows.
     */
    private void actualizarTitulo() {
        String num = txtNumeroBoletin.getText().strip();
        if (resultado != null && !num.isEmpty()) {
            setTitle(TITULO + " - " + num);
        } else {
            setTitle(numeroVentana > 1 ? TITULO + " (" + numeroVentana + ")" : TITULO);
        }
    }

    private void limpiar() {
        resultado = null;
        txtEdicto.setText("");
        txtNumeroBoletin.setText("");
        llenarTablas();
        lblEstado.setText("Pegue el texto del boletín y presione Procesar (Ctrl+Enter).");
        txtEdicto.requestFocusInWindow();
    }

    /** Tab titles show the row count, or "visible de total" while filters hide rows. */
    private void actualizarTitulosTabs() {
        tabs.setTitleAt(0, ColumnasRemate.HOJA_TERRENOS + " (" + TablasRemates.conteo(tablaTerrenos) + ")");
        tabs.setTitleAt(1, ColumnasRemate.HOJA_VEHICULOS + " (" + TablasRemates.conteo(tablaVehiculos) + ")");
    }

    /** Copies the rows visible in the current tab (filtered and sorted, with headers) as tab-separated text. */
    private void copiarTablaActual() {
        int filas = TablasRemates.copiar(tablaActual());
        lblEstado.setText(filas == 0 ? "No hay filas para copiar." : filas + " filas copiadas al portapapeles.");
    }

    // ------------------------------------------------------------------ Remates sincronizados

    /** Set by the Remates window: enables double-click → sincronizados and the green marks. */
    void setRemates(Remates remates) {
        this.remates = remates;
    }

    /** Repaints the green marks (rows whose Referencia N° is in "Remates sincronizados"). */
    void refrescarMarcas() {
        tablaTerrenos.repaint();
        tablaVehiculos.repaint();
    }

    private Formato marca(String tipo, DefaultTableModel modelo, int fila) {
        if (remates == null || fila >= modelo.getRowCount()) {
            return null;
        }
        int colRef = modelo.findColumn(HojaSincronizada.REFERENCIA);
        return remates.estaSincronizado(tipo, modelo.getValueAt(fila, colRef)) ? MARCA_SINCRONIZADO : null;
    }

    /** Double-click on a row adds the complete row to the same tab of "Remates sincronizados". */
    private void agregarConDobleClic(JTable tabla, String tipo) {
        tabla.setToolTipText("Doble clic en una fila para agregarla a Remates sincronizados");
        tabla.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2 || !SwingUtilities.isLeftMouseButton(e) || remates == null) {
                    return;
                }
                int filaVista = tabla.rowAtPoint(e.getPoint());
                if (filaVista < 0) {
                    return;
                }
                int filaModelo = tabla.convertRowIndexToModel(filaVista);
                Object[] valores = new Object[tabla.getModel().getColumnCount()];
                for (int c = 0; c < valores.length; c++) {
                    valores[c] = tabla.getModel().getValueAt(filaModelo, c);
                }
                remates.agregarASincronizados(tipo, valores);
            }
        });
    }
}
