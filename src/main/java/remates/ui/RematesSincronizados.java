package remates.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.UnaryOperator;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JInternalFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.event.InternalFrameAdapter;
import javax.swing.event.InternalFrameEvent;
import javax.swing.table.AbstractTableModel;

import remates.sheets.ClienteSheets;
import remates.sheets.ConfiguracionSheets;
import remates.sheets.FuenteSheets;
import remates.sincronizados.Cambios;
import remates.sincronizados.CeldaSincronizada;
import remates.sincronizados.ColaGuardado;
import remates.sincronizados.FilaSincronizada;
import remates.sincronizados.Formato;
import remates.sincronizados.HojaSincronizada;
import remates.sincronizados.Historial;

/**
 * "Remates sincronizados": the auctions kept in the user's Google spreadsheet (tabs "Terrenos" and
 * "Vehiculos"), with the same columns, sorting and filters as the Boletín form, the file's formatting, and
 * editing: cell values, deleting rows, basic formatting of cells or full rows, Undo/Redo. Every change is saved to
 * the file at once (typed values after a 1.5 s pause); a change that could not be saved is shown in light red.
 * Only one instance exists (see {@link Remates#abrirSincronizados()}).
 */
public class RematesSincronizados extends JInternalFrame {

    private static final String TIPO_T = Boletin.TIPO_TERRENOS;
    private static final int ESPERA_TEXTO_MS = 1500;
    private static final Integer[] TAMANOS = {8, 9, 10, 11, 12, 14, 16, 18, 20, 24, 28, 36};

    private final Remates remates;
    private final ConfiguracionSheets configuracion;
    private final FuenteSheets.Conector conector;
    /** Current connection; replaced when the sign-in is renewed (read by the save thread, hence volatile). */
    private volatile FuenteSheets fuente;
    private ColaGuardado cola;
    private Historial historial;

    private final ModeloHoja modeloT = new ModeloHoja(ColumnasRemate.TERRENOS_HEADERS);
    private final ModeloHoja modeloV = new ModeloHoja(ColumnasRemate.VEH_HEADERS);
    private final JTable tablaT = TablasRemates.crearTabla(modeloT, ColumnasRemate.TERRENOS_WIDTHS, modeloT::estilo);
    private final JTable tablaV = TablasRemates.crearTabla(modeloV, ColumnasRemate.VEH_WIDTHS, modeloV::estilo);
    private final NumerosFila numerosT = new NumerosFila(tablaT);
    private final NumerosFila numerosV = new NumerosFila(tablaV);
    private final JTabbedPane tabs = new JTabbedPane();
    private final PanelFiltros filtros = new PanelFiltros(this::tablaActual, this::actualizarTitulosTabs);

    private final JButton btnDeshacer = boton("Deshacer", "Deshacer (Ctrl+Z)");
    private final JButton btnRehacer = boton("Rehacer", "Rehacer (Ctrl+Y)");
    /** Colors the "Fondo" / "Color de letra" buttons start with when none was chosen yet. */
    static final Color FONDO_INICIAL = new Color(0xFF, 0xF2, 0xCC);
    static final Color LETRA_INICIAL = new Color(0xC0, 0x00, 0x00);
    private final Preferencias preferencias;
    private final BotonColor fondo;
    private final BotonColor letra;
    /** Windows light/dark switch: the color buttons are painted again with the new look's colors. */
    private final java.beans.PropertyChangeListener alCambiarApariencia = this::aparienciaCambiada;
    private final JComboBox<Integer> comboTamano = new JComboBox<>(TAMANOS);
    private final JButton btnNegrita = boton("N", "Negrita");
    private final JButton btnCursiva = boton("K", "Cursiva");
    private final JButton btnSubrayado = boton("S", "Subrayado");
    private final JButton btnTachado = boton("abc", "Tachado");
    private final JButton btnIzquierda = boton("⯇", "Alinear a la izquierda");
    private final JButton btnCentro = boton("☰", "Centrar");
    private final JButton btnDerecha = boton("⯈", "Alinear a la derecha");
    private final JButton btnBorrarFormato = boton("Borrar formato", "Quita el formato de la selección");
    private final JButton btnEliminar = boton("Eliminar filas", "Elimina las filas seleccionadas (Supr)");
    private final JButton btnActualizar = boton("Actualizar", "Vuelve a cargar la hoja desde Google Sheets");
    private final JLabel lblGuardado = new JLabel(" ");
    private final JButton btnReintentar = boton("Reintentar", "Vuelve a enviar los cambios que no se guardaron");
    private final JLabel lblEstado = new JLabel("Conectando con Google Sheets…");
    /** Overlay over the filters and tables while the spreadsheet is loading. */
    private final IndicadorCarga indicador = new IndicadorCarga();

    private final List<Runnable> alCargar = new ArrayList<>();
    private boolean cargado;
    private Set<String> refsT = Set.of();
    private Set<String> refsV = Set.of();

    RematesSincronizados(Remates remates, ConfiguracionSheets configuracion, FuenteSheets.Conector conector) {
        super("Remates sincronizados", true, true, true, true);
        setName("RematesSincronizados");
        this.remates = remates;
        this.configuracion = configuracion;
        this.conector = conector;
        this.preferencias = new Preferencias(configuracion.carpeta());
        fondo = new BotonColor("Fondo", "de fondo", Preferencias.COLOR_FONDO, FONDO_INICIAL, true, Formato::conFondo);
        letra = new BotonColor("Color de letra", "de letra", Preferencias.COLOR_LETRA, LETRA_INICIAL, false,
            Formato::conFuente);
        setFrameIcon(IconoApp.iconoPequeno());
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        UIManager.addPropertyChangeListener(alCambiarApariencia);
        setContentPane(construir());
        setSize(1240, 720);
        habilitarEdicion(false);
        addInternalFrameListener(new InternalFrameAdapter() {
            @Override
            public void internalFrameClosing(InternalFrameEvent e) {
                cerrar();
            }

            @Override
            public void internalFrameClosed(InternalFrameEvent e) {
                UIManager.removePropertyChangeListener(alCambiarApariencia);
                remates.sincronizadosCerrado(RematesSincronizados.this);
            }
        });
    }

    // ------------------------------------------------------------------ layout

    private JComponent construir() {
        JToolBar barra = new JToolBar();
        barra.setFloatable(false);
        barra.add(btnDeshacer);
        barra.add(btnRehacer);
        barra.addSeparator();
        barra.add(fondo.aplicar);
        barra.add(fondo.elegir);
        barra.add(letra.aplicar);
        barra.add(letra.elegir);
        comboTamano.setMaximumSize(new Dimension(70, 28));
        comboTamano.setSelectedItem(10);
        comboTamano.setToolTipText("Tamaño de letra");
        barra.add(comboTamano);
        btnNegrita.setFont(btnNegrita.getFont().deriveFont(Font.BOLD));
        btnCursiva.setFont(btnCursiva.getFont().deriveFont(Font.ITALIC));
        btnSubrayado.setFont(TablasRemates.fuente(btnSubrayado.getFont(), Formato.VACIO.conSubrayado(true)));
        btnTachado.setFont(TablasRemates.fuente(btnTachado.getFont(), Formato.VACIO.conTachado(true)));
        barra.add(btnNegrita);
        barra.add(btnCursiva);
        barra.add(btnSubrayado);
        barra.add(btnTachado);
        barra.add(btnIzquierda);
        barra.add(btnCentro);
        barra.add(btnDerecha);
        barra.add(btnBorrarFormato);
        barra.addSeparator();
        barra.add(btnEliminar);
        barra.addSeparator();
        barra.add(btnActualizar);
        barra.add(Box.createHorizontalGlue());
        barra.add(lblGuardado);
        barra.add(Box.createHorizontalStrut(6));
        btnReintentar.setVisible(false);
        barra.add(btnReintentar);

        btnDeshacer.addActionListener(e -> deshacer());
        btnRehacer.addActionListener(e -> rehacer());
        // the combo is only changed by the user (its initial value is set before this listener exists)
        comboTamano.addActionListener(e -> {
            Integer t = (Integer) comboTamano.getSelectedItem();
            formatear(f -> f.conTamano(t), "Tamaño de letra");
        });
        btnNegrita.addActionListener(e -> alternar(Formato::esNegrita, Formato::conNegrita, "Negrita"));
        btnCursiva.addActionListener(e -> alternar(Formato::esCursiva, Formato::conCursiva, "Cursiva"));
        btnSubrayado.addActionListener(e -> alternar(Formato::esSubrayado, Formato::conSubrayado, "Subrayado"));
        btnTachado.addActionListener(e -> alternar(Formato::esTachado, Formato::conTachado, "Tachado"));
        btnIzquierda.addActionListener(e -> formatear(f -> f.conAlineacion("LEFT"), "Alinear"));
        btnCentro.addActionListener(e -> formatear(f -> f.conAlineacion("CENTER"), "Alinear"));
        btnDerecha.addActionListener(e -> formatear(f -> f.conAlineacion("RIGHT"), "Alinear"));
        btnBorrarFormato.addActionListener(e -> formatear(f -> Formato.VACIO, "Borrar formato"));
        btnEliminar.addActionListener(e -> eliminarFilas());
        btnActualizar.addActionListener(e -> actualizar());
        btnReintentar.addActionListener(e -> {
            if (cola != null) {
                cola.reintentar();
            }
        });

        tablaT.setName("Terrenos");
        tablaV.setName("Vehículos");
        for (JTable t : List.of(tablaT, tablaV)) {
            ModeloHoja m = (ModeloHoja) t.getModel();
            for (int c = 0; c < t.getColumnCount(); c++) {
                t.getColumnModel().getColumn(c).setCellEditor(new EditorCelda(m.getColumnName(c)));
            }
            t.getRowSorter().addRowSorterListener(e -> refrescarNumeros(t));
        }
        tabs.addTab("", numerosT.scroll());
        tabs.addTab("", numerosV.scroll());
        filtros.registrar(tablaT);
        filtros.registrar(tablaV);
        tabs.addChangeListener(e -> filtros.tablaVisibleCambio());
        actualizarTitulosTabs();

        JPanel centro = new JPanel(new BorderLayout());
        centro.add(filtros, BorderLayout.NORTH);
        centro.add(tabs, BorderLayout.CENTER);
        atajo(centro, KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK), "deshacer", this::deshacer);
        atajo(centro, KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK), "rehacer", this::rehacer);
        atajo(centro, KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "eliminar", this::eliminarFilas);

        JPanel raiz = new JPanel(new BorderLayout());
        raiz.add(barra, BorderLayout.NORTH);
        raiz.add(indicador.envolver(centro), BorderLayout.CENTER);
        lblEstado.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        raiz.add(lblEstado, BorderLayout.SOUTH);
        return raiz;
    }

    private static JButton boton(String texto, String ayuda) {
        JButton b = new JButton(texto);
        b.setToolTipText(ayuda);
        b.setFocusable(false);   // keeps the table selection while formatting
        return b;
    }

    private static void atajo(JComponent c, KeyStroke tecla, String nombre, Runnable accion) {
        c.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(tecla, nombre);
        c.getActionMap().put(nombre, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                accion.run();
            }
        });
    }

    private NumerosFila cabecera(JTable tabla) {
        return tabla == tablaT ? numerosT : numerosV;
    }

    private JTable tablaActual() {
        return tabs.getSelectedIndex() == 1 ? tablaV : tablaT;
    }

    private ModeloHoja modeloActual() {
        return (ModeloHoja) tablaActual().getModel();
    }

    /** Called after filtering and after every change: tab counts and the row-number columns. */
    private void actualizarTitulosTabs() {
        tabs.setTitleAt(0, ClienteSheets.TAB_TERRENOS + " (" + TablasRemates.conteo(tablaT) + ")");
        tabs.setTitleAt(1, ClienteSheets.TAB_VEHICULOS + " (" + TablasRemates.conteo(tablaV) + ")");
        refrescarNumeros(tablaT);
        refrescarNumeros(tablaV);
    }

    /** The row-number column follows the visible rows (added, deleted, filtered or sorted) and their heights. */
    private void refrescarNumeros(JTable tabla) {
        NumerosFila numeros = cabecera(tabla);
        if (numeros == null) {
            return;   // still building the form
        }
        TablasRemates.ajustarAltos(tabla, ((ModeloHoja) tabla.getModel())::estilo, null);
        numeros.refrescar();   // recount + the same row heights
    }

    private void habilitarEdicion(boolean si) {
        for (JComponent c : List.of(fondo.aplicar, fondo.elegir, letra.aplicar, letra.elegir, comboTamano, btnNegrita, btnCursiva, btnSubrayado,
            btnTachado, btnIzquierda, btnCentro, btnDerecha, btnBorrarFormato, btnEliminar, btnActualizar)) {
            c.setEnabled(si);
        }
        actualizarBotonesHistorial();
    }

    // ------------------------------------------------------------------ loading

    /** Asks for the credentials when they are missing, then loads the spreadsheet. */
    void iniciar() {
        if (!configuracion.completa() && !DialogoCredenciales.pedir(this, configuracion)) {
            dispose();
            return;
        }
        cargar();
    }

    private static final String CONECTANDO =
        "Conectando con Google Sheets… (si se abre el navegador, autorice el acceso)";
    private static final String CARGANDO = "Cargando la hoja desde Google Sheets…";

    /** True while the spreadsheet is being loaded (the overlay is shown). */
    public boolean cargando() {
        return indicador.activo();
    }

    private void cargar() {
        cargado = false;
        habilitarEdicion(false);
        String mensaje = fuente == null ? CONECTANDO : CARGANDO;
        lblEstado.setText(mensaje);
        indicador.mostrar(mensaje);
        new SwingWorker<ClienteSheets.Carga, String>() {
            @Override
            protected ClienteSheets.Carga doInBackground() throws Exception {
                if (fuente == null) {
                    fuente = conector.conectar(configuracion);
                    publish(CARGANDO);
                }
                try {
                    return fuente.cargar();
                } catch (Exception e) {
                    if (!ClienteSheets.esSesionInvalida(e)) {
                        throw e;
                    }
                    publish(CONECTANDO);
                    synchronized (RematesSincronizados.this) {   // same lock as reconnecting while saving
                        configuracion.borrarSesion();   // revoked or expired sign-in: sign in again once
                        fuente = conector.conectar(configuracion);
                    }
                    publish(CARGANDO);
                    return fuente.cargar();
                }
            }

            @Override
            protected void process(List<String> pasos) {
                indicador.mostrar(pasos.get(pasos.size() - 1));
                lblEstado.setText(pasos.get(pasos.size() - 1));
            }

            @Override
            protected void done() {
                indicador.ocultar();
                try {
                    mostrar(get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    errorAlCargar(e.getCause());
                }
            }
        }.execute();
    }

    /**
     * Sends a batch with the current connection. When the stored sign-in expired or was revoked meanwhile, signs in
     * again (the browser opens) and sends the same batch once more, so nothing is lost. Runs on the save thread.
     */
    private void enviarConSesion(List<com.google.api.services.sheets.v4.model.Request> peticiones) throws Exception {
        FuenteSheets actual = fuente;
        try {
            actual.enviar(peticiones);
        } catch (Exception e) {
            if (!ClienteSheets.esSesionInvalida(e)) {
                throw e;
            }
            synchronized (this) {
                if (fuente == actual) {           // nobody reconnected meanwhile
                    configuracion.borrarSesion();
                    fuente = conector.conectar(configuracion);
                }
            }
            fuente.enviar(peticiones);
        }
    }

    private void mostrar(ClienteSheets.Carga carga) {
        if (cola == null) {
            // the queue uses the current connection at send time (it may be renewed after an expired sign-in)
            cola = new ColaGuardado(this::enviarConSesion, ESPERA_TEXTO_MS);
            cola.alCambiar(this::actualizarGuardado);
            historial = new Historial(cola);
            historial.alCambiar(this::refrescar);
        }
        historial.limpiar();
        modeloT.setHoja(carga.terrenos());
        modeloV.setHoja(carga.vehiculos());
        cargado = true;
        habilitarEdicion(true);
        refrescar();
        lblEstado.setText("Hoja «" + carga.tituloArchivo() + "»: " + carga.terrenos().filas().size()
            + " terrenos, " + carga.vehiculos().filas().size() + " vehículos. Doble clic en una fila de un "
            + "Boletín para agregarla aquí.");
        actualizarGuardado();
        List<Runnable> pendientes = new ArrayList<>(alCargar);
        alCargar.clear();
        pendientes.forEach(Runnable::run);
    }

    private void errorAlCargar(Throwable e) {
        lblEstado.setText("No se pudo cargar: " + ClienteSheets.mensaje(e));
        Object[] opciones = {"Reintentar", "Configurar credenciales", "Cerrar"};
        int r = JOptionPane.showOptionDialog(this, ClienteSheets.mensaje(e), "No se pudo cargar la hoja",
            JOptionPane.DEFAULT_OPTION, JOptionPane.ERROR_MESSAGE, null, opciones, opciones[0]);
        if (r == 0) {
            cargar();
        } else if (r == 1 && DialogoCredenciales.pedir(this, configuracion)) {
            fuente = null;    // new settings: connect again
            cola = null;
            cargar();
        } else {
            alCargar.clear();
            dispose();
        }
    }

    private void actualizar() {
        if (cola != null && cola.sinGuardar() > 0) {
            int r = JOptionPane.showConfirmDialog(this, "Hay " + cola.sinGuardar() + " cambios que aún no se "
                + "guardaron en Google Sheets. ¿Descartarlos y volver a cargar la hoja?", "Actualizar",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r != JOptionPane.YES_OPTION) {
                return;
            }
            cola.descartar();
        }
        cargar();
    }

    private void cerrar() {
        if (cola != null) {
            cola.enviarYa();
            if (cola.estado() == ColaGuardado.Estado.ERROR) {
                int r = JOptionPane.showConfirmDialog(this, "Hay " + cola.sinGuardar() + " cambios que no se "
                    + "pudieron guardar en Google Sheets. ¿Cerrar de todos modos?", "Cambios sin guardar",
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (r != JOptionPane.YES_OPTION) {
                    return;
                }
            }
        }
        dispose();
    }

    // ------------------------------------------------------------------ state after each change

    /** The model changed (do / undo / redo / load): tables, filters, row heights, marks in Boletín forms. */
    private void refrescar() {
        modeloT.avisarCambio();
        modeloV.avisarCambio();
        refsT = modeloT.referencias();
        refsV = modeloV.referencias();
        filtros.datosActualizados();
        actualizarTitulosTabs();   // also refreshes the row numbers and row heights
        actualizarBotonesHistorial();
        remates.refrescarMarcas();
    }

    private void actualizarBotonesHistorial() {
        boolean d = historial != null && historial.puedeDeshacer();
        boolean r = historial != null && historial.puedeRehacer();
        btnDeshacer.setEnabled(d);
        btnRehacer.setEnabled(r);
        btnDeshacer.setToolTipText(d ? "Deshacer: " + historial.proximoDeshacer() + " (Ctrl+Z)" : "Deshacer (Ctrl+Z)");
        btnRehacer.setToolTipText(r ? "Rehacer: " + historial.proximoRehacer() + " (Ctrl+Y)" : "Rehacer (Ctrl+Y)");
    }

    private void actualizarGuardado() {
        if (cola == null) {
            return;
        }
        int n = cola.sinGuardar();
        switch (cola.estado()) {
            case GUARDADO -> {
                lblGuardado.setText("Guardado ✓");
                lblGuardado.setForeground(new Color(0x2E, 0x7D, 0x32));
            }
            case ESPERANDO -> {
                lblGuardado.setText("Cambios por guardar…");
                lblGuardado.setForeground(Color.DARK_GRAY);
            }
            case GUARDANDO -> {
                lblGuardado.setText("Guardando…");
                lblGuardado.setForeground(Color.DARK_GRAY);
            }
            case ERROR -> {
                lblGuardado.setText(n + (n == 1 ? " cambio sin guardar" : " cambios sin guardar"));
                lblGuardado.setForeground(new Color(0xB0, 0x20, 0x20));
            }
        }
        lblGuardado.setToolTipText(cola.ultimoError());
        btnReintentar.setVisible(cola.estado() == ColaGuardado.Estado.ERROR);
        tablaT.repaint();
        tablaV.repaint();
    }

    // ------------------------------------------------------------------ actions

    private void deshacer() {
        if (historial != null && !tablaActual().isEditing()) {
            historial.deshacer();
        }
    }

    private void rehacer() {
        if (historial != null && !tablaActual().isEditing()) {
            historial.rehacer();
        }
    }

    /** Selected cells of the visible tab (full rows when selected with the row numbers). */
    private List<Cambios.Celda> seleccion() {
        JTable t = tablaActual();
        HojaSincronizada hoja = modeloActual().hoja;
        List<Cambios.Celda> celdas = new ArrayList<>();
        if (hoja == null) {
            return celdas;
        }
        for (int filaVista : t.getSelectedRows()) {
            FilaSincronizada fila = hoja.filas().get(t.convertRowIndexToModel(filaVista));
            for (int colVista : t.getSelectedColumns()) {
                celdas.add(new Cambios.Celda(fila, t.convertColumnIndexToModel(colVista)));
            }
        }
        return celdas;
    }

    private void formatear(UnaryOperator<Formato> cambio, String descripcion) {
        List<Cambios.Celda> celdas = seleccion();
        if (celdas.isEmpty()) {
            lblEstado.setText("Seleccione celdas, o filas completas con el número de fila, para darles formato.");
            return;
        }
        historial.hacer(new Cambios.FormatearCeldas(modeloActual().hoja, celdas, cambio, descripcion));
    }

    /** Bold / italic / ...: on when not every selected cell has it, off otherwise. */
    private void alternar(java.util.function.Predicate<Formato> tiene,
                          java.util.function.BiFunction<Formato, Boolean, Formato> poner, String descripcion) {
        boolean todas = !seleccion().isEmpty() && seleccion().stream().allMatch(c -> tiene.test(c.celda().getFormato()));
        formatear(f -> poner.apply(f, !todas), descripcion);
    }

    private void aparienciaCambiada(java.beans.PropertyChangeEvent e) {
        if ("lookAndFeel".equals(e.getPropertyName())) {
            SwingUtilities.invokeLater(() -> {
                fondo.repintar();
                letra.repintar();
            });
        }
    }

    Color colorFondo() {
        return fondo.color;
    }

    Color colorLetra() {
        return letra.color;
    }

    /**
     * A toolbar color: the main button is painted with the current color and applies it to the selected cells in one
     * click; "▾" opens the color picker on it, the chosen color becomes the button's color, is saved in the
     * preferences (loaded again next time) and, when cells are selected, applied to them.
     */
    private final class BotonColor {
        final JButton aplicar;
        final JButton elegir;
        private final String nombre;
        private final String clave;
        private final boolean esFondo;
        private final java.util.function.BiFunction<Formato, Color, Formato> poner;
        Color color;

        BotonColor(String texto, String que, String clave, Color inicial, boolean esFondo,
                   java.util.function.BiFunction<Formato, Color, Formato> poner) {
            this.nombre = "Color " + que;
            this.clave = clave;
            this.esFondo = esFondo;
            this.poner = poner;
            aplicar = boton(texto, "Pone el color " + que + " del botón en las celdas o filas seleccionadas");
            elegir = boton("▾", "Elegir el color " + que + " (queda guardado para la próxima vez)");
            aplicar.addActionListener(e -> formatear(f -> poner.apply(f, color), nombre));
            elegir.addActionListener(e -> elegirColor());
            pintar(preferencias.color(clave, inicial));
        }

        private void elegirColor() {
            Color c = JColorChooser.showDialog(RematesSincronizados.this, nombre, color);
            if (c == null) {
                return;
            }
            pintar(c);
            try {
                preferencias.guardarColor(clave, c);
            } catch (java.io.IOException ex) {
                lblEstado.setText("No se pudo guardar el color elegido: " + ex.getMessage());
            }
            if (!seleccion().isEmpty()) {
                formatear(f -> poner.apply(f, c), nombre);
            }
        }

        /**
         * Fill color: the button's background (text black or white, whichever reads better). Font color: the
         * button's text, bold, on the normal button unless the color is too light to read (then on dark gray).
         */
        private void pintar(Color c) {
            color = c;
            double luz = Apariencia.luz(c);
            Color fondoBoton;
            if (esFondo) {
                fondoBoton = c;
                aplicar.setForeground(luz < 0.55 ? Color.WHITE : Color.BLACK);
            } else {
                aplicar.setForeground(c);
                aplicar.setFont(aplicar.getFont().deriveFont(Font.BOLD));
                // too close to the button's own color to read: on dark gray (light colors) or light gray (dark ones)
                Color normal = UIManager.getColor("Button.background");
                double base = normal == null ? 0.9 : Apariencia.luz(normal);
                fondoBoton = Math.abs(luz - base) >= 0.3 ? null
                    : base > 0.5 ? new Color(0x40, 0x40, 0x40) : new Color(0xE0, 0xE0, 0xE0);
            }
            aplicar.setBackground(fondoBoton);   // FlatLaf paints it as the (rounded) button background
            aplicar.repaint();
        }

        /** Repaints with the current color (after a light/dark switch: the button's own colors changed). */
        void repintar() {
            pintar(color);
        }
    }

    private void eliminarFilas() {
        JTable t = tablaActual();
        HojaSincronizada hoja = modeloActual().hoja;
        if (hoja == null || t.isEditing() || t.getSelectedRowCount() == 0) {
            return;
        }
        List<FilaSincronizada> filas = new ArrayList<>();
        for (int filaVista : t.getSelectedRows()) {
            filas.add(hoja.filas().get(t.convertRowIndexToModel(filaVista)));
        }
        historial.hacer(new Cambios.EliminarFilas(hoja, filas));
        t.clearSelection();
        cabecera(t).numeros().clearSelection();
        lblEstado.setText(filas.size() == 1 ? "Fila eliminada (Ctrl+Z para deshacer)."
            : filas.size() + " filas eliminadas (Ctrl+Z para deshacer).");
    }

    // ------------------------------------------------------------------ from Boletín forms

    /**
     * Adds a Boletín row to the same tab (after loading, if still loading). If its Referencia N° is already there,
     * shows both rows with the differences and asks whether to replace the values.
     */
    void agregarDesdeBoletin(String tipo, Object[] valores) {
        if (!cargado) {
            alCargar.add(() -> agregarDesdeBoletin(tipo, valores));
            return;
        }
        boolean terrenos = TIPO_T.equals(tipo);
        tabs.setSelectedIndex(terrenos ? 0 : 1);
        ModeloHoja modelo = terrenos ? modeloT : modeloV;
        HojaSincronizada hoja = modelo.hoja;
        int colRef = hoja.columnaReferencia();
        String ref = HojaSincronizada.clave(valores[colRef]);
        int existente = hoja.indiceDeReferencia(ref);   // stays behind the Boletín (not brought to front)
        if (existente < 0) {
            FilaSincronizada fila = FilaSincronizada.deValores(valores);
            historial.hacer(new Cambios.AgregarFila(hoja, fila));
            seleccionar(fila);
            lblEstado.setText("Agregada la referencia " + (ref == null ? "(sin referencia)" : ref) + ".");
            return;
        }
        FilaSincronizada fila = hoja.filas().get(existente);
        seleccionar(fila);
        // "Estado" is the user's own column: not compared and never overwritten from the Boletín
        Object[] actuales = fila.valores();
        Object[] nuevos = valores.clone();
        boolean[] ignorar = new boolean[nuevos.length];
        int colEstado = hoja.columnas().indexOf(ColumnasRemate.ESTADO);
        if (colEstado >= 0 && colEstado < nuevos.length) {
            nuevos[colEstado] = actuales[colEstado];
            ignorar[colEstado] = true;
        }
        if (DialogoDiferencias.preguntar(this, hoja.columnas(), nuevos, actuales, ignorar, ref)) {
            historial.hacer(new Cambios.ReemplazarValores(hoja, fila, nuevos));
            lblEstado.setText("Valores de la referencia " + ref + " reemplazados.");
        }
    }

    boolean contieneReferencia(String tipo, Object referencia) {
        String ref = HojaSincronizada.clave(referencia);
        return ref != null && (TIPO_T.equals(tipo) ? refsT : refsV).contains(ref);
    }

    private void seleccionar(FilaSincronizada fila) {
        JTable t = tablaActual();
        HojaSincronizada hoja = modeloActual().hoja;
        int vista = t.convertRowIndexToView(hoja.indiceDe(fila));
        if (vista >= 0) {
            t.setRowSelectionInterval(vista, vista);
            t.setColumnSelectionInterval(0, t.getColumnCount() - 1);
            // scroll to the row (also when the window is not showing it yet: after the layout)
            t.scrollRectToVisible(t.getCellRect(vista, 0, true));
            SwingUtilities.invokeLater(() -> {
                int v = t.convertRowIndexToView(hoja.indiceDe(fila));
                if (v >= 0) {
                    t.scrollRectToVisible(t.getCellRect(v, 0, true));
                }
            });
        }
    }

    // ------------------------------------------------------------------ table model over the sheet

    /** The sheet's rows as a table: editing a cell goes through the undo history and is saved. */
    private final class ModeloHoja extends AbstractTableModel {
        private final List<String> columnas;
        private HojaSincronizada hoja;
        private int filasAvisadas;

        ModeloHoja(List<String> columnas) {
            this.columnas = columnas;
        }

        void setHoja(HojaSincronizada hoja) {
            this.hoja = hoja;
            filasAvisadas = -1;
        }

        /** Rows added or removed → full refresh; otherwise only repaint (keeps the selection). */
        void avisarCambio() {
            int n = getRowCount();
            if (n != filasAvisadas) {
                filasAvisadas = n;
                fireTableDataChanged();
            } else if (n > 0) {
                fireTableRowsUpdated(0, n - 1);
            }
        }

        Set<String> referencias() {
            return hoja == null ? Set.of() : new HashSet<>(hoja.referencias());
        }

        Formato estilo(int fila, int columna) {
            if (hoja == null || fila >= hoja.filas().size()) {
                return null;
            }
            CeldaSincronizada c = hoja.filas().get(fila).celda(columna);
            return c.isSinGuardar() ? c.getFormato().conFondo(TablasRemates.ROJO_SIN_GUARDAR) : c.getFormato();
        }

        @Override
        public int getRowCount() {
            return hoja == null ? 0 : hoja.filas().size();
        }

        @Override
        public int getColumnCount() {
            return columnas.size();
        }

        @Override
        public String getColumnName(int c) {
            return columnas.get(c);
        }

        @Override
        public Object getValueAt(int r, int c) {
            return hoja.filas().get(r).celda(c).getValor();
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return hoja != null && cargado;
        }

        @Override
        public void setValueAt(Object valor, int r, int c) {
            if (hoja == null || Objects.equals(valor, getValueAt(r, c))) {
                return;
            }
            historial.hacer(new Cambios.EditarCelda(hoja, hoja.filas().get(r), c, valor));
        }
    }

    // ------------------------------------------------------------------ cell editor with validation

    /** Amounts accept 20.000.000 / 20,000,000.00 (saved as numbers); dates must be dd/mm/aaaa; empty = "No indicado". */
    private static final class EditorCelda extends DefaultCellEditor {
        private final JTextField campo;
        private final String columna;
        private Object valor;

        EditorCelda(String columna) {
            super(new JTextField());
            this.campo = (JTextField) getComponent();
            this.columna = columna;
            setClickCountToStart(2);
        }

        @Override
        public Component getTableCellEditorComponent(JTable t, Object v, boolean sel, int r, int c) {
            campo.setBorder(BorderFactory.createLineBorder(Color.GRAY));
            campo.setText(ColumnasRemate.NO_INDICADO.equals(v) || v == null ? "" : ColumnasRemate.textoCelda(v));
            campo.setToolTipText(ayuda());
            return campo;
        }

        private String ayuda() {
            if (ColumnasRemate.NUM_COLS.contains(columna)) {
                return "Número, por ejemplo 20.000.000 o 20,000,000.00";
            }
            return ColumnasRemate.FECHA_COLS.contains(columna) ? "Fecha dd/mm/aaaa" : null;
        }

        @Override
        public boolean stopCellEditing() {
            String t = campo.getText().strip();
            if (t.isEmpty()) {
                valor = ColumnasRemate.ESTADO.equals(columna) ? "" : ColumnasRemate.NO_INDICADO;
            } else if (ColumnasRemate.NUM_COLS.contains(columna)) {
                Double d = FiltroTexto.parseNumero(t);
                if (d == null) {
                    return rechazar();
                }
                valor = d;
            } else if (ColumnasRemate.FECHA_COLS.contains(columna)) {
                java.time.LocalDate f = FiltroTexto.parseFecha(t);
                if (f == null) {
                    return rechazar();
                }
                valor = f.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/uuuu"));
            } else {
                valor = t;
            }
            return super.stopCellEditing();
        }

        private boolean rechazar() {
            campo.setBorder(BorderFactory.createLineBorder(new Color(0xC0, 0x30, 0x30), 2));
            return false;
        }

        @Override
        public Object getCellEditorValue() {
            return valor;
        }
    }
}
