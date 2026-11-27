package remates.ui;

import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * User preferences of the app, in {@code preferencias.properties} inside the settings folder
 * ({@code %USERPROFILE%\.remates\}, next to the Google Sheets settings, see
 * {@link remates.sheets.ConfiguracionSheets#carpeta()}). Kept apart from {@code config.properties} so saving one never
 * touches the other. An unreadable or missing file just gives the defaults.
 */
final class Preferencias {
    static final String COLOR_FONDO = "colorFondo";
    static final String COLOR_LETRA = "colorLetra";

    private final Path archivo;
    private Properties valores;

    Preferencias(Path carpeta) {
        archivo = carpeta.resolve("preferencias.properties");
        valores = leer();
    }

    private Properties leer() {
        Properties p = new Properties();
        if (Files.isRegularFile(archivo)) {
            try (InputStream in = Files.newInputStream(archivo)) {
                p.load(in);
            } catch (IOException e) {
                // unreadable: defaults
            }
        }
        return p;
    }

    Path archivo() {
        return archivo;
    }

    /** A color stored as {@code RRGGBB} hex (a leading {@code #} is accepted), or {@code defecto} when missing or invalid. */
    Color color(String clave, Color defecto) {
        String v = valores.getProperty(clave, "").strip().replaceFirst("^#", "");
        if (v.matches("[0-9a-fA-F]{6}")) {
            return new Color(Integer.parseInt(v, 16));
        }
        return defecto;
    }

    /** Stores the color and writes the file right away. */
    void guardarColor(String clave, Color color) throws IOException {
        escribir(clave, String.format("%06X", color.getRGB() & 0xFFFFFF));
    }

    /**
     * Re-reads the file and changes only this key, so values written meanwhile (another window, another
     * instance of the app) are never lost.
     */
    private void escribir(String clave, String valor) throws IOException {
        Properties actual = leer();
        actual.setProperty(clave, valor);
        Files.createDirectories(archivo.getParent());
        try (OutputStream out = Files.newOutputStream(archivo)) {
            actual.store(out, "Remates - preferencias");
        }
        valores = actual;
    }
}
