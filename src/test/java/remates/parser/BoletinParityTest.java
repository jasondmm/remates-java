package remates.parser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import remates.model.ResultadoBoletin;
import remates.model.Terreno;
import remates.model.Vehiculo;
import remates.ui.ColumnasRemate;

/**
 * Parity test against the original Python scripts on real data.
 *
 * <p>For every {@code .txt} in {@code resources/boletintxt} it runs the Python scripts
 * ({@code resources/python-scripts}, through {@code src/test/python/dump_rows.py}) and the Java {@link BoletinParser},
 * then compares the resulting Excel rows ("Terrenos" / "Vehiculos" sheets) cell by cell.
 * Numbers must be bit-for-bit equal and text must match exactly. The column layout (names, order, widths and
 * numeric columns) of {@code procesar_boletin.py} must also match {@link ColumnasRemate}.
 *
 * <p>No test framework is needed: run {@link #main} from the project root (IntelliJ's default working directory).
 * It exits with 0 when every row matches and 1 otherwise. Options:
 * <pre>
 *   --python=&lt;exe&gt;     Python executable (default: $PYTHON or "python"); needs openpyxl installed
 *   --txt=&lt;dir&gt;        folder with the boletín .txt files (default: resources/boletintxt)
 *   --scripts=&lt;dir&gt;    folder with the Python scripts (default: resources/python-scripts)
 *   --max-diffs=&lt;n&gt;    differences printed per file (default: 10)
 * </pre>
 */
public final class BoletinParityTest {

    private record Row(String tipo, List<Object> cells) {
    }

    /** Rows of one boletín, as produced by Python or by Java. */
    private record FileResult(String numero, List<Row> terrenos, List<Row> vehiculos) {
        List<Row> rows(String tipo) {
            return tipo.equals("T") ? terrenos : vehiculos;
        }
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parseArgs(args);
        String python = opts.getOrDefault("python", Objects.requireNonNullElse(System.getenv("PYTHON"), "python"));
        Path txtDir = Path.of(opts.getOrDefault("txt", "resources/boletintxt"));
        Path scriptsDir = Path.of(opts.getOrDefault("scripts", "resources/python-scripts"));
        Path dumpScript = Path.of(opts.getOrDefault("dump", "src/test/python/dump_rows.py"));
        int maxDiffs = Integer.parseInt(opts.getOrDefault("max-diffs", "10"));

        for (Path required : List.of(txtDir, scriptsDir, dumpScript)) {
            if (!Files.exists(required)) {
                System.err.println("No existe: " + required.toAbsolutePath()
                    + "\nEjecute la prueba desde la raíz del proyecto o indique la ruta con --txt/--scripts/--dump.");
                System.exit(2);
            }
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(txtDir)) {
            files = s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".txt"))
                .sorted().toList();
        }
        if (files.isEmpty()) {
            System.err.println("No hay archivos .txt en " + txtDir.toAbsolutePath());
            System.exit(2);
        }

        System.out.printf("Ejecutando scripts de Python sobre %d archivos...%n", files.size());
        long t0 = System.nanoTime();
        Map<String, FileResult> pythonResults = runPython(python, dumpScript, scriptsDir, txtDir);
        long tPython = System.nanoTime() - t0;

        // Excel layout: column names, order, widths and numeric columns must be the same in both versions
        List<String> layoutDiffs = compareLayout();
        System.out.println(layoutDiffs.isEmpty()
            ? "\nHojas y columnas (nombres, orden, anchos, numéricas): OK"
            : "\nColumnas: DIFERENCIAS");
        layoutDiffs.forEach(d -> System.out.println("      " + d));

        int totalRows = 0;
        int totalDiffRows = 0;
        int filesWithDiffs = layoutDiffs.isEmpty() ? 0 : 1;
        long tJava = 0;
        System.out.printf("%n%-12s %8s %22s %22s  %s%n", "Archivo", "Boletín", "Terrenos (py/java)",
            "Vehículos (py/java)", "Resultado");
        for (Path file : files) {
            String name = file.getFileName().toString();
            long t1 = System.nanoTime();
            FileResult java = runJava(file);
            tJava += System.nanoTime() - t1;
            FileResult py = pythonResults.get(name);
            if (py == null) {
                System.out.printf("%-12s  Python no devolvió resultados para este archivo%n", name);
                filesWithDiffs++;
                continue;
            }
            List<String> diffs = new ArrayList<>();
            if (!py.numero().equals(java.numero())) {
                diffs.add("número de boletín: python=" + py.numero() + " java=" + java.numero());
            }
            int diffRows = compare("Terrenos", ColumnasRemate.TERRENOS_HEADERS, py.terrenos(), java.terrenos(), diffs)
                + compare("Vehículos", ColumnasRemate.VEH_HEADERS, py.vehiculos(), java.vehiculos(), diffs);
            totalRows += py.terrenos().size() + py.vehiculos().size();
            totalDiffRows += diffRows;
            boolean ok = diffs.isEmpty();
            if (!ok) {
                filesWithDiffs++;
            }
            System.out.printf("%-12s %8s %22s %22s  %s%n", name, py.numero(),
                py.terrenos().size() + " / " + java.terrenos().size(),
                py.vehiculos().size() + " / " + java.vehiculos().size(),
                ok ? "OK" : "DIFERENCIAS (" + diffs.size() + ")");
            diffs.stream().limit(maxDiffs).forEach(d -> System.out.println("      " + d));
            if (diffs.size() > maxDiffs) {
                System.out.printf("      ... y %d diferencias más (use --max-diffs=N)%n", diffs.size() - maxDiffs);
            }
        }

        System.out.printf("%n%d archivos, %d filas comparadas, %d filas con diferencias, %d archivos con diferencias."
                + "%nTiempo: Python %.1f s (incluye arranque), Java %.1f s.%n",
            files.size(), totalRows, totalDiffRows, filesWithDiffs, tPython / 1e9, tJava / 1e9);
        System.out.println(filesWithDiffs == 0 ? "RESULTADO: OK, Java coincide con Python." : "RESULTADO: FALLÓ.");
        System.exit(filesWithDiffs == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ comparison

    /** Excel layout reported by Python ({@code #LAYOUT} lines), by key. */
    private static final Map<String, List<String>> LAYOUT_PYTHON = new LinkedHashMap<>();

    private static List<String> compareLayout() {
        Map<String, List<String>> java = new LinkedHashMap<>();
        java.put("HOJAS", List.of(ColumnasRemate.HOJA_TERRENOS, ColumnasRemate.HOJA_VEHICULOS));
        java.put("HEADERS_T", ColumnasRemate.TERRENOS_HEADERS);
        java.put("WIDTHS_T", Arrays.stream(ColumnasRemate.TERRENOS_WIDTHS).mapToObj(String::valueOf).toList());
        java.put("HEADERS_V", ColumnasRemate.VEH_HEADERS);
        java.put("WIDTHS_V", Arrays.stream(ColumnasRemate.VEH_WIDTHS).mapToObj(String::valueOf).toList());
        java.put("NUM_COLS", ColumnasRemate.NUM_COLS.stream().sorted().toList());
        List<String> diffs = new ArrayList<>();
        java.forEach((key, valor) -> {
            List<String> py = LAYOUT_PYTHON.get(key);
            if (py == null) {
                diffs.add(key + ": Python no lo informó");
            } else if (!py.equals(valor)) {
                diffs.add(key + ":\n         python=" + py + "\n         java  =" + valor);
            }
        });
        return diffs;
    }

    /** Compares two row lists in order; adds readable differences and returns the number of differing rows. */
    private static int compare(String hoja, List<String> headers, List<Row> py, List<Row> java, List<String> diffs) {
        int differing = 0;
        if (py.size() != java.size()) {
            diffs.add(hoja + ": python tiene " + py.size() + " filas, java " + java.size());
            Set<Object> refsPy = refs(py);
            Set<Object> refsJava = refs(java);
            Set<Object> soloPy = new LinkedHashSet<>(refsPy);
            soloPy.removeAll(refsJava);
            Set<Object> soloJava = new LinkedHashSet<>(refsJava);
            soloJava.removeAll(refsPy);
            if (!soloPy.isEmpty()) {
                diffs.add(hoja + ": referencias solo en python: " + soloPy);
            }
            if (!soloJava.isEmpty()) {
                diffs.add(hoja + ": referencias solo en java: " + soloJava);
            }
        }
        for (int i = 0; i < Math.min(py.size(), java.size()); i++) {
            List<Object> a = py.get(i).cells();
            List<Object> b = java.get(i).cells();
            boolean rowDiffers = a.size() != b.size();
            for (int c = 0; c < Math.min(a.size(), b.size()); c++) {
                if (!sameCell(a.get(c), b.get(c))) {
                    rowDiffers = true;
                    diffs.add(String.format("%s fila %d (ref %s) «%s»: python=%s | java=%s", hoja, i + 1, a.get(1),
                        headers.get(c), show(a.get(c)), show(b.get(c))));
                }
            }
            if (rowDiffers) {
                differing++;
            }
        }
        return differing + Math.abs(py.size() - java.size());
    }

    private static Set<Object> refs(List<Row> rows) {
        Set<Object> s = new LinkedHashSet<>();
        rows.forEach(r -> s.add(r.cells().get(1)));
        return s;
    }

    private static boolean sameCell(Object py, Object java) {
        if (py instanceof Double d1 && java instanceof Double d2) {
            return Double.doubleToLongBits(d1) == Double.doubleToLongBits(d2);
        }
        if (py instanceof Long l && java instanceof Number n) {
            return n.doubleValue() == l && n.doubleValue() == Math.rint(n.doubleValue());
        }
        return Objects.equals(py, java);
    }

    private static String show(Object v) {
        if (v == null) {
            return "None";
        }
        if (v instanceof Double d) {
            return d + " (num)";
        }
        String s = v.toString();
        return "\"" + (s.length() > 120 ? s.substring(0, 117) + "..." : s) + "\"";
    }

    // ------------------------------------------------------------------ java side

    private static FileResult runJava(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        ResultadoBoletin r = new BoletinParser().parse(text);
        // procesar_boletin.py uses the file name when the number is not in the first three lines
        String stem = file.getFileName().toString().replaceFirst("\\.[^.]*$", "");
        String numero = r.numeroBoletin() != null ? r.numeroBoletin() : stem;
        List<Row> t = new ArrayList<>();
        for (Terreno x : r.terrenos()) {
            t.add(new Row("T", Arrays.asList(ColumnasRemate.filaTerreno(x, numero))));
        }
        List<Row> v = new ArrayList<>();
        for (Vehiculo x : r.vehiculos()) {
            v.add(new Row("V", Arrays.asList(ColumnasRemate.filaVehiculo(x, numero))));
        }
        return new FileResult(numero, t, v);
    }

    // ------------------------------------------------------------------ python side

    private static Map<String, FileResult> runPython(String python, Path dumpScript, Path scriptsDir, Path txtDir)
        throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(python, "-X", "utf8", dumpScript.toString(),
            scriptsDir.toString(), txtDir.toString());
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            System.err.println("No se pudo ejecutar Python (\"" + python + "\"). Indique el ejecutable con --python=<ruta>"
                + " o la variable de entorno PYTHON.");
            throw e;
        }
        Map<String, FileResult> results = new LinkedHashMap<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            FileResult current = null;
            String line;
            while ((line = in.readLine()) != null) {
                String[] parts = line.split("\t", -1);
                switch (parts[0]) {
                    case "#LAYOUT" -> LAYOUT_PYTHON.put(parts[1], List.of(parts).subList(2, parts.length));
                    case "#FILE" -> {
                        current = new FileResult(parts[2], new ArrayList<>(), new ArrayList<>());
                        results.put(parts[1], current);
                    }
                    case "T", "V" -> {
                        List<Object> cells = new ArrayList<>(parts.length - 1);
                        for (int i = 1; i < parts.length; i++) {
                            cells.add(decodeCell(parts[i]));
                        }
                        Objects.requireNonNull(current, "fila antes de #FILE")
                            .rows(parts[0]).add(new Row(parts[0], cells));
                    }
                    default -> throw new IllegalStateException("Línea inesperada de Python: " + line);
                }
            }
        }
        int exit = p.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("Los scripts de Python terminaron con código " + exit);
        }
        return results;
    }

    private static Object decodeCell(String s) {
        char type = s.charAt(0);
        String v = s.substring(1);
        return switch (type) {
            case 'N' -> null;
            case 'F' -> Double.parseDouble(v); // repr(float) round-trips exactly
            case 'I' -> Long.parseLong(v);
            case 'S' -> unescape(v);
            default -> throw new IllegalStateException("Celda desconocida: " + s);
        };
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(switch (n) {
                    case 't' -> '\t';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    default -> n;
                });
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String a : args) {
            if (a.startsWith("--") && a.contains("=")) {
                m.put(a.substring(2, a.indexOf('=')), a.substring(a.indexOf('=') + 1));
            } else {
                throw new IllegalArgumentException("Argumento no reconocido: " + a);
            }
        }
        return m;
    }

    private BoletinParityTest() {
    }
}
