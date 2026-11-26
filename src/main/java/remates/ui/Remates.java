package remates.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.beans.PropertyVetoException;

import javax.swing.ButtonGroup;
import javax.swing.JDesktopPane;
import javax.swing.JFrame;
import javax.swing.JInternalFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.KeyStroke;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;

import remates.sheets.ClienteSheets;
import remates.sheets.ConfiguracionSheets;
import remates.sheets.FuenteSheets;

/**
 * "Remates": the application's MDI (multiple document interface) window. Child forms such as {@link Boletin} open
 * inside its desktop; the "Ventana" menu arranges them and switches between them.
 */
public class Remates extends JFrame {

    private final JDesktopPane escritorio = new JDesktopPane();
    private final JMenu menuVentana = new JMenu("Ventana");
    private int boletinesAbiertos;
    /** The only "Remates sincronizados" form, or null while it is closed. */
    private RematesSincronizados sincronizados;
    private ConfiguracionSheets configuracionSheets = new ConfiguracionSheets();
    private FuenteSheets.Conector conectorSheets = ClienteSheets::conectar;

    public Remates() {
        super("Remates");
        setName("Remates");
        IconoApp.aplicar(this);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        escritorio.setBackground(new Color(0x6B, 0x7B, 0x8C));
        setContentPane(escritorio);
        setJMenuBar(crearMenu());
        setSize(1366, 860);                 // size when the window is restored
        setMinimumSize(new Dimension(800, 500));
        setLocationRelativeTo(null);
        setExtendedState(getExtendedState() | MAXIMIZED_BOTH);   // always opens maximized
    }

    /** Opens a new Boletin child window at its normal size (cascaded) and gives it the focus. */
    public Boletin abrirBoletin() {
        Boletin b = new Boletin();
        b.setRemates(this);
        boletinesAbiertos++;
        if (escritorio.getAllFrames().length > 0) {
            b.setNumeroVentana(boletinesAbiertos);
        }
        int desplazamiento = 28 * escritorio.getAllFrames().length;
        b.setLocation(desplazamiento, desplazamiento);
        escritorio.add(b);
        b.setVisible(true);
        try {
            b.setSelected(true);   // normal size (not maximized); the user can maximize it
        } catch (PropertyVetoException ignored) {
            // the frame refused; it stays as it is
        }
        return b;
    }

    public JDesktopPane getEscritorio() {
        return escritorio;
    }

    // ------------------------------------------------------------------ Remates sincronizados

    /**
     * Opens "Remates sincronizados" (only one is allowed: if it is open it is brought to the front). The first time
     * it asks for the Google credentials, then loads the spreadsheet.
     */
    public RematesSincronizados abrirSincronizados() {
        if (sincronizados != null) {
            activar(sincronizados);
            return sincronizados;
        }
        return crearSincronizados(true);
    }

    private RematesSincronizados crearSincronizados(boolean alFrente) {
        RematesSincronizados s = new RematesSincronizados(this, configuracionSheets, conectorSheets);
        sincronizados = s;
        s.setLocation(28 * escritorio.getAllFrames().length, 28 * escritorio.getAllFrames().length);
        escritorio.add(s);
        s.setVisible(true);
        if (alFrente) {
            activar(s);
        } else {
            escritorio.moveToBack(s);
        }
        s.iniciar();
        return s;
    }

    /**
     * Adds a Boletín row to the same tab of "Remates sincronizados" without bringing it to the front: the Boletín
     * keeps the focus. When sincronizados is closed it is opened (and loaded) behind the Boletín.
     */
    void agregarASincronizados(Boletin origen, String tipo, Object[] valores) {
        RematesSincronizados s = sincronizados != null ? sincronizados : crearSincronizados(false);
        if (origen != null && origen.isVisible() && sincronizados == s) {
            activar(origen);   // also after a credentials dialog of a newly opened sincronizados
        }
        s.agregarDesdeBoletin(tipo, valores);
    }

    /** True when "Remates sincronizados" is open and has a row with this Referencia N° in that tab. */
    boolean estaSincronizado(String tipo, Object referencia) {
        return sincronizados != null && sincronizados.contieneReferencia(tipo, referencia);
    }

    /** Repaints the green marks of every Boletín window. */
    void refrescarMarcas() {
        for (JInternalFrame f : escritorio.getAllFrames()) {
            if (f instanceof Boletin b) {
                b.refrescarMarcas();
            }
        }
    }

    void sincronizadosCerrado(RematesSincronizados s) {
        if (sincronizados == s) {
            sincronizados = null;
        }
        refrescarMarcas();
    }

    /** For tests: a fake spreadsheet and settings folder instead of the user's Google account. */
    public void setFuenteSheets(ConfiguracionSheets configuracion, FuenteSheets.Conector conector) {
        this.configuracionSheets = configuracion;
        this.conectorSheets = conector;
    }

    // ------------------------------------------------------------------ menus

    private JMenuBar crearMenu() {
        JMenu archivo = new JMenu("Archivo");
        archivo.setMnemonic(KeyEvent.VK_A);
        JMenuItem nuevo = new JMenuItem("Nuevo boletín");
        nuevo.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK));
        nuevo.addActionListener(e -> abrirBoletin());
        JMenuItem sinc = new JMenuItem("Remates sincronizados");
        sinc.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK));
        sinc.addActionListener(e -> abrirSincronizados());
        JMenuItem salir = new JMenuItem("Salir");
        salir.addActionListener(e -> dispatchEvent(new WindowEvent(this, WindowEvent.WINDOW_CLOSING)));
        archivo.add(nuevo);
        archivo.add(sinc);
        archivo.addSeparator();
        archivo.add(salir);

        menuVentana.setMnemonic(KeyEvent.VK_V);
        menuVentana.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent e) {
                llenarMenuVentana();
            }

            @Override
            public void menuDeselected(MenuEvent e) {
            }

            @Override
            public void menuCanceled(MenuEvent e) {
            }
        });

        JMenuBar barra = new JMenuBar();
        barra.add(archivo);
        barra.add(menuVentana);
        return barra;
    }

    /** "Ventana": Cascada, Mosaico and one entry per open child window (the active one marked). */
    private void llenarMenuVentana() {
        menuVentana.removeAll();
        JInternalFrame[] ventanas = escritorio.getAllFrames();
        JMenuItem cascada = new JMenuItem("Cascada");
        cascada.addActionListener(e -> cascada());
        JMenuItem mosaico = new JMenuItem("Mosaico");
        mosaico.addActionListener(e -> mosaico());
        cascada.setEnabled(ventanas.length > 0);
        mosaico.setEnabled(ventanas.length > 0);
        menuVentana.add(cascada);
        menuVentana.add(mosaico);
        if (ventanas.length > 0) {
            menuVentana.addSeparator();
        }
        ButtonGroup grupo = new ButtonGroup();
        for (JInternalFrame v : ventanas) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(v.getTitle(), v.isSelected());
            item.addActionListener(e -> activar(v));
            grupo.add(item);
            menuVentana.add(item);
        }
    }

    static void activar(JInternalFrame v) {
        try {
            v.setIcon(false);
            v.setSelected(true);
        } catch (PropertyVetoException ignored) {
            // the frame refused
        }
        v.toFront();
    }

    /** Child windows overlapped diagonally, each 80% of the desktop. */
    public void cascada() {
        JInternalFrame[] ventanas = escritorio.getAllFrames();
        int ancho = (int) (escritorio.getWidth() * 0.8);
        int alto = (int) (escritorio.getHeight() * 0.8);
        for (int i = 0; i < ventanas.length; i++) {
            JInternalFrame v = ventanas[ventanas.length - 1 - i];
            restaurar(v);
            v.setBounds(28 * i, 28 * i, ancho, alto);
            v.toFront();
        }
    }

    /** Child windows side by side in a grid that fills the desktop. */
    public void mosaico() {
        JInternalFrame[] ventanas = escritorio.getAllFrames();
        int n = ventanas.length;
        if (n == 0) {
            return;
        }
        int columnas = (int) Math.ceil(Math.sqrt(n));
        int filas = (int) Math.ceil((double) n / columnas);
        int ancho = escritorio.getWidth() / columnas;
        int alto = escritorio.getHeight() / filas;
        for (int i = 0; i < n; i++) {
            JInternalFrame v = ventanas[i];
            restaurar(v);
            v.setBounds((i % columnas) * ancho, (i / columnas) * alto, ancho, alto);
        }
    }

    private static void restaurar(JInternalFrame v) {
        try {
            v.setIcon(false);
            v.setMaximum(false);
        } catch (PropertyVetoException ignored) {
            // the frame refused
        }
    }
}
