package remates.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JInternalFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;

import remates.model.ResultadoBoletin;
import remates.parser.BoletinParser;
import remates.sincronizados.Formato;
import remates.sincronizados.HojaSincronizada;

/**
 * "Boletín" form: paste the text of a Boletín Judicial edict, press Procesar and the auctions are listed
 * in two tables (terrenos / vehículos) with the columns of {@link ColumnasRemate}.
 * It is a child window of the {@link Remates} MDI window; several can be open at once.
 */
public class Boletin extends JInternalFrame {

    private final BoletinParser parser = new BoletinParser();

    /** Pasting (Ctrl+V, Mayús+Insert) processes the text right away. */
    private final JTextArea txtEdicto = new JTextArea() {
        @Override
        public void paste() {
            super.paste();
            SwingUtilities.invokeLater(Boletin.this::procesar);
        }
    };
    private final BuscadorTexto buscador = new BuscadorTexto(txtEdicto);
    /** Number of the processed boletín (from the text), "" when unknown. */
    private String numeroBoletin = "";
    private final JButton btnProcesar = new JButton("Procesar");
    private final JButton btnLimpiar = new JButton("Limpiar");
    private final JButton btnCopiar = new JButton("Copiar tabla");
    private final JButton btnAbrir = new JButton("Abrir archivo…");
    /** Last folder used in "Abrir archivo…", shared by every Boletín window of the session. */
    private static File ultimaCarpeta;
    private final JLabel lblEstado = new JLabel(
        "Pegue el texto del boletín o ábralo desde un archivo (Ctrl+O), y presione Procesar (Ctrl+Enter).");
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
        acciones.add(buscador);
        acciones.add(btnProcesar);
        acciones.add(btnLimpiar);
        acciones.add(btnCopiar);
        acciones.add(btnAbrir);
        entrada.add(acciones, BorderLayout.SOUTH);

        btnAbrir.setToolTipText("Carga el texto de un boletín desde un archivo .txt (Ctrl+O)");
        btnAbrir.addActionListener(e -> abrirArchivo());
        btnProcesar.addActionListener(e -> procesar());
        btnLimpiar.addActionListener(e -> limpiar());
        btnCopiar.addActionListener(e -> copiarTablaActual());

        tablaTerrenos.setName("Terrenos");
        tablaVehiculos.setName("Vehículos");
        agregarConDobleClic(tablaTerrenos, TIPO_TERRENOS);
        agregarConDobleClic(tablaVehiculos, TIPO_VEHICULOS);
        tabs.addTab("", new NumerosFila(tablaTerrenos).scroll());   // "#" row numbers, click = full row
        tabs.addTab("", new NumerosFila(tablaVehiculos).scroll());
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
        root.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_B, InputEvent.CTRL_DOWN_MASK), "buscarTexto");
        root.getActionMap().put("buscarTexto", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                buscador.enfocar();
            }
        });
        root.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK), "abrir");
        root.getActionMap().put("abrir", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                abrirArchivo();
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
                    mostrar(get(), texto);
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

    private void mostrar(ResultadoBoletin r, String texto) {
        resultado = r;
        numeroBoletin = r.numeroBoletin() == null ? "" : r.numeroBoletin().strip();
        llenarTablas();
        String estado = r.terrenos().size() + " terrenos, " + r.vehiculos().size() + " vehículos";
        if (numeroBoletin.isEmpty()) {
            estado += ". No se encontró el número de boletín en el texto";
        }
        lblEstado.setText(estado);
        if (r.terrenos().isEmpty() && !r.vehiculos().isEmpty()) {
            tabs.setSelectedIndex(1);
        } else {
            tabs.setSelectedIndex(0);
        }
        guardarProcesado(texto, estado);
    }

    // ------------------------------------------------------------------ text files

    /** Saves the processed text in Documents\Remates\Procesados (in the background) and reports it. */
    private void guardarProcesado(String texto, String estado) {
        String numero = numeroBoletin;
        Path carpeta = ArchivosTexto.carpetaProcesados();
        new SwingWorker<ArchivosTexto.Guardado, Void>() {
            @Override
            protected ArchivosTexto.Guardado doInBackground() throws IOException {
                return ArchivosTexto.guardarProcesado(texto, numero, carpeta);
            }

            @Override
            protected void done() {
                try {
                    ArchivosTexto.Guardado g = get();
                    lblEstado.setText(estado + (g.nuevo() ? ". Texto guardado en " : ". El texto ya estaba guardado en ")
                        + g.archivo());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    lblEstado.setText(estado + ". No se pudo guardar el texto en " + carpeta + ": "
                        + e.getCause().getMessage());
                }
            }
        }.execute();
    }

    /** Loads a .txt file into the text box (the previous result is cleared) and processes it. */
    private void abrirArchivo() {
        File inicial = ultimaCarpeta;
        if (inicial == null) {
            Path procesados = ArchivosTexto.carpetaProcesados();
            inicial = Files.isDirectory(procesados) ? procesados.toFile() : null;
        }
        JFileChooser fc = new JFileChooser(inicial);
        fc.setDialogTitle("Abrir texto de un boletín");
        fc.setFileFilter(new FileNameExtensionFilter("Archivos de texto (*.txt)", "txt"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File archivo = fc.getSelectedFile();
        ultimaCarpeta = archivo.getParentFile();
        try {
            String texto = ArchivosTexto.leer(archivo.toPath());
            resultado = null;
            numeroBoletin = "";   // the new text brings its own number
            llenarTablas();
            txtEdicto.setText(texto);
            txtEdicto.setCaretPosition(0);
            txtEdicto.requestFocusInWindow();
            procesar();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "No se pudo leer " + archivo + ":\n" + e.getMessage(),
                "Abrir archivo", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void llenarTablas() {
        modeloTerrenos.setRowCount(0);
        modeloVehiculos.setRowCount(0);
        if (resultado != null) {
            String num = numeroBoletin;
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
     * "Boletín - 191" once a text is processed (the number found in the text, as used in the rows), without the window
     * number. An empty form shows "Boletín", or "Boletín (2)", "Boletín (3)"... for further windows.
     */
    private void actualizarTitulo() {
        if (resultado != null && !numeroBoletin.isEmpty()) {
            setTitle(TITULO + " - " + numeroBoletin);
        } else {
            setTitle(numeroVentana > 1 ? TITULO + " (" + numeroVentana + ")" : TITULO);
        }
    }

    private void limpiar() {
        resultado = null;
        txtEdicto.setText("");
        numeroBoletin = "";
        llenarTablas();
        lblEstado.setText("Pegue el texto del boletín o ábralo desde un archivo (Ctrl+O), y presione Procesar (Ctrl+Enter).");
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
                remates.agregarASincronizados(Boletin.this, tipo, valores);
            }
        });
    }
}
