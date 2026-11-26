package remates.sheets;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Settings of the Google Sheets connection, kept outside the project in {@code %USERPROFILE%\.remates\}:
 * {@code credentials.json} (OAuth client of type "Desktop app" from Google Cloud), {@code tokens\} (the sign-in,
 * written by Google's library) and {@code config.properties} (the spreadsheet ID).
 * The folder can be changed with the system property {@code remates.config} (used by tests).
 */
public final class ConfiguracionSheets {

    private static final Pattern ID_EN_ENLACE = Pattern.compile("/spreadsheets/d/([a-zA-Z0-9_-]+)");
    private static final Pattern ID_SOLO = Pattern.compile("[a-zA-Z0-9_-]{20,}");

    private final Path carpeta;
    private String spreadsheetId;

    public ConfiguracionSheets() {
        this(Path.of(System.getProperty("remates.config",
            Path.of(System.getProperty("user.home"), ".remates").toString())));
    }

    public ConfiguracionSheets(Path carpeta) {
        this.carpeta = carpeta;
        Properties p = new Properties();
        if (Files.exists(archivo())) {
            try (InputStream in = Files.newInputStream(archivo())) {
                p.load(in);
            } catch (IOException e) {
                // unreadable settings: treated as not configured
            }
        }
        spreadsheetId = p.getProperty("spreadsheetId", "").strip();
    }

    public Path carpeta() {
        return carpeta;
    }

    public Path credenciales() {
        return carpeta.resolve("credentials.json");
    }

    public Path tokens() {
        return carpeta.resolve("tokens");
    }

    private Path archivo() {
        return carpeta.resolve("config.properties");
    }

    public String spreadsheetId() {
        return spreadsheetId;
    }

    /** Both the credentials file and the spreadsheet are set. */
    public boolean completa() {
        return Files.isRegularFile(credenciales()) && !spreadsheetId.isEmpty();
    }

    /**
     * Saves the settings: copies the chosen credentials file (when given and different) and stores the spreadsheet
     * ID taken from the link.
     *
     * @throws IllegalArgumentException when the file is not a desktop OAuth client or the link has no ID
     */
    public void guardar(Path credencialesElegidas, String enlaceOId) throws IOException {
        String id = extraerId(enlaceOId);
        if (id == null) {
            throw new IllegalArgumentException("El enlace no es de una hoja de Google Sheets.");
        }
        Files.createDirectories(carpeta);
        if (credencialesElegidas != null
            && !credencialesElegidas.toAbsolutePath().normalize().equals(credenciales().toAbsolutePath().normalize())) {
            validarCredenciales(credencialesElegidas);
            Files.copy(credencialesElegidas, credenciales(), StandardCopyOption.REPLACE_EXISTING);
            borrarSesion();   // a different OAuth client needs a new sign-in
        }
        if (!id.equals(spreadsheetId)) {
            spreadsheetId = id;
        }
        Properties p = new Properties();
        p.setProperty("spreadsheetId", spreadsheetId);
        try (OutputStream out = Files.newOutputStream(archivo())) {
            p.store(out, "Remates - Google Sheets");
        }
    }

    /** Forgets the stored sign-in (next connection opens the browser again). */
    public void borrarSesion() throws IOException {
        Path t = tokens();
        if (Files.exists(t)) {
            try (var archivos = Files.walk(t)) {
                for (Path p : archivos.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    /** Spreadsheet ID from a full link ({@code .../spreadsheets/d/<id>/edit...}) or the bare ID; null if none. */
    public static String extraerId(String enlaceOId) {
        if (enlaceOId == null) {
            return null;
        }
        String s = enlaceOId.strip();
        Matcher m = ID_EN_ENLACE.matcher(s);
        if (m.find()) {
            return m.group(1);
        }
        return ID_SOLO.matcher(s).matches() ? s : null;
    }

    /** The file must be an OAuth client of type "Desktop app" (JSON with an "installed" section). */
    public static void validarCredenciales(Path archivo) throws IOException {
        if (!Files.isRegularFile(archivo)) {
            throw new IllegalArgumentException("No existe el archivo de credenciales: " + archivo);
        }
        String json = Files.readString(archivo, StandardCharsets.UTF_8);
        if (!json.contains("\"installed\"") || !json.contains("\"client_id\"")) {
            throw new IllegalArgumentException("El archivo no es una credencial OAuth de tipo «Aplicación de "
                + "escritorio» (debe tener la sección \"installed\").");
        }
    }
}
