package remates.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

import remates.sheets.ConfiguracionSheets;

/**
 * Asks for what "Remates sincronizados" needs to reach the spreadsheet: the OAuth credentials file downloaded from
 * Google Cloud and the spreadsheet link. Saves them in {@link ConfiguracionSheets}.
 */
final class DialogoCredenciales extends JDialog {

    private static final String AYUDA = "<html><body style='width:480px'>"
        + "<b>Para conectar con Google Sheets se necesita, una sola vez:</b><ol>"
        + "<li>En <i>Google Cloud Console</i> cree un proyecto y habilite <b>Google Sheets API</b>.</li>"
        + "<li>En <i>Pantalla de consentimiento de OAuth</i>, elija «Externo» y agregue su correo como usuario de "
        + "prueba.</li>"
        + "<li>En <i>Credenciales</i> → <i>Crear credenciales</i> → <i>ID de cliente de OAuth</i> → tipo "
        + "<b>«Aplicación de escritorio»</b>, y descargue el archivo JSON.</li></ol>"
        + "Elija ese archivo aquí y pegue el enlace de la hoja (debe tener las pestañas «" + ColumnasRemate.HOJA_TERRENOS
        + "» y «" + ColumnasRemate.HOJA_VEHICULOS + "»). Al conectar, el navegador se abrirá para que autorice el acceso.</body></html>";

    private final ConfiguracionSheets configuracion;
    private final JTextField credenciales = new JTextField(36);
    private final JTextField enlace = new JTextField(36);
    private final JLabel error = new JLabel(" ");
    private boolean aceptado;

    private DialogoCredenciales(Component padre, ConfiguracionSheets configuracion) {
        super(SwingUtilities.getWindowAncestor(padre), "Credenciales de Google Sheets", ModalityType.APPLICATION_MODAL);
        this.configuracion = configuracion;
        if (Files.isRegularFile(configuracion.credenciales())) {
            credenciales.setText(configuracion.credenciales().toString());
        }
        if (!configuracion.spreadsheetId().isEmpty()) {
            enlace.setText("https://docs.google.com/spreadsheets/d/" + configuracion.spreadsheetId() + "/edit");
        }
        error.setForeground(new Color(0xD0, 0x30, 0x30));   // readable on the light and dark look

        JPanel campos = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        campos.add(new JLabel("Archivo de credenciales:"), c);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        campos.add(credenciales, c);
        c.weightx = 0;
        JButton buscar = new JButton("Buscar…");
        buscar.addActionListener(e -> buscarArchivo());
        campos.add(buscar, c);
        c.gridy = 1;
        c.fill = GridBagConstraints.NONE;
        campos.add(new JLabel("Enlace de la hoja:"), c);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridwidth = 2;
        campos.add(enlace, c);
        c.gridy = 2;
        c.gridx = 0;
        c.gridwidth = 3;
        campos.add(error, c);

        JButton cancelar = new JButton("Cancelar");
        cancelar.addActionListener(e -> dispose());
        JButton guardar = new JButton("Guardar y conectar");
        guardar.addActionListener(e -> guardar());
        JPanel botones = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        botones.add(cancelar);
        botones.add(guardar);

        JPanel raiz = new JPanel(new BorderLayout(0, 10));
        raiz.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));
        raiz.add(new JLabel(AYUDA), BorderLayout.NORTH);
        raiz.add(campos, BorderLayout.CENTER);
        raiz.add(botones, BorderLayout.SOUTH);
        setContentPane(raiz);
        getRootPane().setDefaultButton(guardar);
        pack();
        setLocationRelativeTo(padre);
    }

    /** Shows the dialog; true when the settings were saved. */
    static boolean pedir(Component padre, ConfiguracionSheets configuracion) {
        DialogoCredenciales d = new DialogoCredenciales(padre, configuracion);
        d.setVisible(true);
        return d.aceptado;
    }

    private void buscarArchivo() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("Credenciales OAuth (*.json)", "json"));
        if (!credenciales.getText().isBlank()) {
            fc.setSelectedFile(Path.of(credenciales.getText().strip()).toFile());
        }
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            credenciales.setText(fc.getSelectedFile().getAbsolutePath());
        }
    }

    private void guardar() {
        String ruta = credenciales.getText().strip();
        if (ruta.isEmpty()) {
            error.setText("Elija el archivo de credenciales descargado de Google Cloud.");
            return;
        }
        try {
            configuracion.guardar(Path.of(ruta), enlace.getText());
            aceptado = true;
            dispose();
        } catch (IllegalArgumentException | IOException ex) {
            error.setText(ex.getMessage());
            pack();
        }
    }
}
