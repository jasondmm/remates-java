package remates.ui;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

import javax.swing.filechooser.FileSystemView;

/**
 * Text files of the Boletín form: the "Procesados" folder where every processed text is saved, and reading
 * {@code .txt} files whatever their encoding.
 */
public final class ArchivosTexto {
    private ArchivosTexto() {
    }

    /**
     * {@code Documents\Remates\Procesados} (the real Documents folder, also when Windows moved it, e.g. to OneDrive).
     * The system property {@code remates.procesados} replaces it (tests).
     */
    public static Path carpetaProcesados() {
        String prop = System.getProperty("remates.procesados");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop);
        }
        File documentos = FileSystemView.getFileSystemView().getDefaultDirectory();
        Path base = documentos != null ? documentos.toPath() : Path.of(System.getProperty("user.home"), "Documents");
        return base.resolve("Remates").resolve("Procesados");
    }

    /**
     * Saves a processed text as {@code Boletin_<número>.txt} (or {@code Boletin_<fecha_hora>.txt} without a number),
     * UTF-8. When a file with that name already holds exactly the same text, nothing is written and that file is
     * returned; a different text gets {@code _2}, {@code _3}...
     *
     * @return the file holding the text, and whether it was written now ({@code nuevo}) or already held this text
     */
    public static Guardado guardarProcesado(String texto, String numeroBoletin, Path carpeta) throws IOException {
        Files.createDirectories(carpeta);
        String numero = numeroBoletin == null ? "" : numeroBoletin.strip().replaceAll("[^0-9A-Za-z_-]", "");
        String base = numero.isEmpty()
            ? "Boletin_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"))
            : "Boletin_" + numero;
        byte[] contenido = texto.getBytes(StandardCharsets.UTF_8);
        for (int i = 1; ; i++) {
            Path archivo = carpeta.resolve(i == 1 ? base + ".txt" : base + "_" + i + ".txt");
            if (!Files.exists(archivo)) {
                Files.write(archivo, contenido);
                return new Guardado(archivo, true);
            }
            if (Arrays.equals(Files.readAllBytes(archivo), contenido)) {
                return new Guardado(archivo, false);   // already saved, same text
            }
        }
    }

    /** Result of {@link #guardarProcesado}: the file, and false when it already held the same text. */
    public record Guardado(Path archivo, boolean nuevo) {
    }

    /** Reads a text file: UTF-8 (with or without BOM); if it is not valid UTF-8, Windows-1252 (old Windows files). */
    public static String leer(Path archivo) throws IOException {
        byte[] bytes = Files.readAllBytes(archivo);
        int inicio = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
            && (bytes[2] & 0xFF) == 0xBF ? 3 : 0;
        ByteBuffer datos = ByteBuffer.wrap(bytes, inicio, bytes.length - inicio);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(datos).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, inicio, bytes.length - inicio, Charset.forName("windows-1252"));
        }
    }
}
