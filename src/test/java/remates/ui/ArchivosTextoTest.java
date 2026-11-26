package remates.ui;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Saving processed texts to "Procesados" and reading .txt files ({@link ArchivosTexto}). Plain main: exit 0 = pass.
 * Works in a temporary folder (never the user's Documents).
 */
public final class ArchivosTextoTest {
    private static int fallos;
    private static int total;

    public static void main(String[] args) throws Exception {
        Path carpeta = Files.createTempDirectory("procesados-test").resolve("Remates").resolve("Procesados");
        String texto = "BOLETÍN JUDICIAL\nNúmero 191\nsáquese a remate la finca… ¢4.660.000\n";

        ArchivosTexto.Guardado g1 = ArchivosTexto.guardarProcesado(texto, "191", carpeta);
        check("se crea la carpeta Procesados", Files.isDirectory(carpeta));
        check("nombre Boletin_191.txt", g1.archivo().getFileName().toString().equals("Boletin_191.txt") && g1.nuevo());
        check("contenido UTF-8 idéntico", Files.readString(g1.archivo(), StandardCharsets.UTF_8).equals(texto));

        ArchivosTexto.Guardado g2 = ArchivosTexto.guardarProcesado(texto, "191", carpeta);
        check("mismo texto otra vez: no se duplica", g2.archivo().equals(g1.archivo()) && !g2.nuevo());

        ArchivosTexto.Guardado g3 = ArchivosTexto.guardarProcesado(texto + "otra línea\n", "191", carpeta);
        check("otro texto con el mismo número: Boletin_191_2.txt",
            g3.archivo().getFileName().toString().equals("Boletin_191_2.txt") && g3.nuevo());
        ArchivosTexto.Guardado g4 = ArchivosTexto.guardarProcesado(texto + "otra línea\n", "191", carpeta);
        check("ese otro texto de nuevo: reutiliza Boletin_191_2.txt", g4.archivo().equals(g3.archivo()) && !g4.nuevo());

        ArchivosTexto.Guardado g5 = ArchivosTexto.guardarProcesado("sin número", "  ", carpeta);
        check("sin número: Boletin_<fecha_hora>.txt (" + g5.archivo().getFileName() + ")",
            g5.archivo().getFileName().toString().matches("Boletin_\\d{4}-\\d{2}-\\d{2}_\\d{6}\\.txt"));
        ArchivosTexto.Guardado g6 = ArchivosTexto.guardarProcesado("x", "19/1 ..", carpeta);
        // "19/1 .." → "191"; Boletin_191.txt and _2 already hold other texts → _3
        check("caracteres no válidos en el número se quitan (" + g6.archivo().getFileName() + ")",
            g6.archivo().getFileName().toString().equals("Boletin_191_3.txt") && g6.nuevo());

        // reading
        Path dir = carpeta.getParent();
        Path utf8 = dir.resolve("utf8.txt");
        Files.writeString(utf8, texto, StandardCharsets.UTF_8);
        check("leer UTF-8", ArchivosTexto.leer(utf8).equals(texto));
        Path bom = dir.resolve("bom.txt");
        byte[] cuerpo = texto.getBytes(StandardCharsets.UTF_8);
        byte[] conBom = new byte[cuerpo.length + 3];
        conBom[0] = (byte) 0xEF;
        conBom[1] = (byte) 0xBB;
        conBom[2] = (byte) 0xBF;
        System.arraycopy(cuerpo, 0, conBom, 3, cuerpo.length);
        Files.write(bom, conBom);
        check("leer UTF-8 con BOM (sin el BOM)", ArchivosTexto.leer(bom).equals(texto));
        Path ansi = dir.resolve("ansi.txt");
        String latino = "Número 191: sáquese a remate, ¢4.660.000, Limón, Cañas";
        Files.write(ansi, latino.getBytes(Charset.forName("windows-1252")));
        check("leer Windows-1252 (archivo viejo de Windows)", ArchivosTexto.leer(ansi).equals(latino));

        System.out.printf("%d comprobaciones, %d fallos%n", total, fallos);
        System.exit(fallos == 0 ? 0 : 1);
    }

    private static void check(String que, boolean ok) {
        total++;
        if (!ok) {
            fallos++;
            System.out.println("FALLA: " + que);
        }
    }

    private ArchivosTextoTest() {
    }
}
