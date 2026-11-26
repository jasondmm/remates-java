package remates.sincronizados;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;

import com.google.api.services.sheets.v4.model.CellData;
import com.google.api.services.sheets.v4.model.ExtendedValue;
import com.google.api.services.sheets.v4.model.GridData;
import com.google.api.services.sheets.v4.model.RowData;
import com.google.api.services.sheets.v4.model.Sheet;
import com.google.api.services.sheets.v4.model.SheetProperties;

import remates.sheets.ClienteSheets;
import remates.sheets.ConfiguracionSheets;
import remates.sheets.LectorHoja;
import remates.sheets.SimuladorSheets;
import remates.ui.ColumnasRemate;

/**
 * Offline tests of "Remates sincronizados": formatting conversion, links, reading sheets, every change with
 * undo/redo checked against a simulated spreadsheet, a random sequence of changes, and the live save queue
 * (immediate sending, batching while in flight, 1.5 s text pause, failures and retry). Plain main: exit 0 = pass.
 */
public final class SincronizadosTest {
    private static int fallos;
    private static int total;

    public static void main(String[] args) throws Exception {
        formatos();
        enlaces();
        lecturaDeHojas();
        cambiosYDeshacer();
        secuenciaAleatoria();
        colaDeGuardado();
        System.out.printf("%d comprobaciones, %d fallos%n", total, fallos);
        System.exit(fallos == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ helpers

    private static void check(String que, boolean ok) {
        total++;
        if (!ok) {
            fallos++;
            System.out.println("FALLA: " + que);
        }
    }

    private static void edt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    private static <T> T edt(java.util.concurrent.Callable<T> c) throws Exception {
        AtomicReference<T> r = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                r.set(c.call());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return r.get();
    }

    private static Object[] fila(String ref, double monto, String provincia) {
        Object[] v = new Object[ColumnasRemate.TERRENOS_HEADERS.size()];
        java.util.Arrays.fill(v, ColumnasRemate.NO_INDICADO);
        v[0] = "191";
        v[1] = ref;
        v[2] = "₡";
        v[3] = monto;
        v[4] = "15/01/2027";
        v[ColumnasRemate.TERRENOS_HEADERS.indexOf("Provincia")] = provincia;
        return v;
    }

    /** The simulated file, reloaded, has exactly the same rows (values and formats) as the app's model. */
    private static String diferencia(HojaSincronizada local, HojaSincronizada remota) {
        if (local.filas().size() != remota.filas().size()) {
            return "filas: local " + local.filas().size() + ", archivo " + remota.filas().size();
        }
        for (int r = 0; r < local.filas().size(); r++) {
            for (int c = 0; c < local.columnas().size(); c++) {
                CeldaSincronizada a = local.filas().get(r).celda(c);
                CeldaSincronizada b = remota.filas().get(r).celda(c);
                if (!Objects.equals(a.getValor(), b.getValor())) {
                    return "fila " + r + " col " + c + ": valor local «" + a.getValor() + "» archivo «" + b.getValor() + "»";
                }
                if (!a.getFormato().equals(b.getFormato())) {
                    return "fila " + r + " col " + c + ": formato local " + a.getFormato() + " archivo " + b.getFormato();
                }
            }
        }
        return null;
    }

    private static void esperarGuardado(ColaGuardado cola) throws Exception {
        for (int i = 0; i < 400; i++) {
            if (edt(() -> cola.sinGuardar() == 0 && cola.estado() == ColaGuardado.Estado.GUARDADO)) {
                return;
            }
            Thread.sleep(25);
        }
        throw new IllegalStateException("la cola no terminó: " + edt(cola::estado));
    }

    // ------------------------------------------------------------------ 1. Formato <-> CellFormat

    private static void formatos() {
        Formato f = new Formato(new Color(0xE2, 0xF0, 0xD9), new Color(0x1F, 0x4E, 0x78), 14, true, false, true,
            true, "CENTER");
        check("Formato → CellFormat → Formato conserva todo", Formato.desde(f.aCellFormat()).equals(f));
        check("Formato vacío ida y vuelta", Formato.desde(Formato.VACIO.aCellFormat()).equals(Formato.VACIO));
        check("CellFormat nulo = vacío", Formato.desde(null).equals(Formato.VACIO));
        check("negro (componentes omitidos por Sheets)",
            Formato.color(new com.google.api.services.sheets.v4.model.Color()).equals(Color.BLACK));
    }

    // ------------------------------------------------------------------ 2. links

    private static void enlaces() {
        String id = "1AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abc";
        check("ID de enlace completo", id.equals(ConfiguracionSheets.extraerId(
            "https://docs.google.com/spreadsheets/d/" + id + "/edit#gid=0")));
        check("ID suelto", id.equals(ConfiguracionSheets.extraerId("  " + id + " ")));
        check("enlace que no es de Sheets", ConfiguracionSheets.extraerId("https://example.com/x") == null);
        check("texto vacío", ConfiguracionSheets.extraerId("") == null);
    }

    // ------------------------------------------------------------------ 3. reading sheets

    private static CellData texto(String s) {
        return new CellData().setFormattedValue(s).setEffectiveValue(new ExtendedValue().setStringValue(s));
    }

    private static CellData numero(double d) {
        return new CellData().setFormattedValue(String.valueOf(d)).setEffectiveValue(new ExtendedValue().setNumberValue(d));
    }

    private static Sheet hoja(List<List<CellData>> filas) {
        List<RowData> rows = new ArrayList<>();
        filas.forEach(f -> rows.add(new RowData().setValues(f)));
        return new Sheet().setProperties(new SheetProperties().setTitle(ColumnasRemate.HOJA_TERRENOS).setSheetId(7))
            .setData(List.of(new GridData().setRowData(rows)));
    }

    private static void lecturaDeHojas() {
        List<String> cols = ColumnasRemate.TERRENOS_HEADERS;
        // header reversed + one extra column at the start
        List<CellData> encabezado = new ArrayList<>();
        encabezado.add(texto("Notas"));
        for (int i = cols.size() - 1; i >= 0; i--) {
            encabezado.add(texto(cols.get(i)));
        }
        List<CellData> dato = new ArrayList<>();
        dato.add(texto("nota libre"));
        for (int i = cols.size() - 1; i >= 0; i--) {
            String c = cols.get(i);
            dato.add(c.equals("Monto primer remate") ? numero(1500000.5) : c.equals("Referencia N°") ? texto("2026211951")
                : c.equals("Provincia") ? new CellData() : texto("x" + i));
        }
        List<CellData> soloFormato = List.of(new CellData().setUserEnteredFormat(Formato.VACIO.conNegrita(true).aCellFormat()));
        LectorHoja.Lectura l = LectorHoja.leer(hoja(List.of(encabezado, dato, soloFormato, soloFormato)), cols);
        HojaSincronizada h = l.hoja();
        boolean todas = true;
        for (int c = 0; c < cols.size(); c++) {   // header reversed after one extra column
            todas &= h.columnaEnHoja(c) == 1 + (cols.size() - 1 - c);
        }
        check("columnas encontradas por nombre (orden distinto, columna extra)", todas);
        check("filas finales solo con formato no son datos", h.filas().size() == 1);
        check("monto como número", Double.valueOf(1500000.5).equals(h.filas().get(0).celda(cols.indexOf("Monto primer remate")).getValor()));
        check("celda vacía = «No indicado»", ColumnasRemate.NO_INDICADO.equals(h.filas().get(0).celda(cols.indexOf("Provincia")).getValor()));
        check("referencia", h.referencias().contains("2026211951") && h.indiceDeReferencia("2026211951") == 0);
        check("sin petición de encabezado", l.inicializacion().isEmpty());

        // a sheet made before "Afectado" and "Estado" existed: their headers are added at the end
        List<CellData> viejo = new ArrayList<>();
        for (String c : cols) {
            if (!c.equals(ColumnasRemate.AFECTADO) && !c.equals(ColumnasRemate.ESTADO)) {
                viejo.add(texto(c));
            }
        }
        List<CellData> datoViejo = new ArrayList<>();
        for (int i = 0; i < viejo.size(); i++) {
            datoViejo.add(texto("v" + i));
        }
        LectorHoja.Lectura lv = LectorHoja.leer(hoja(List.of(viejo, datoViejo)), cols);
        int nuevaAfectado = lv.hoja().columnaEnHoja(cols.indexOf(ColumnasRemate.AFECTADO));
        int nuevaEstado = lv.hoja().columnaEnHoja(cols.indexOf(ColumnasRemate.ESTADO));
        check("columnas faltantes: encabezado agregado al final (Afectado en " + nuevaAfectado + ", Estado en "
            + nuevaEstado + ", " + lv.inicializacion().size() + " peticiones)",
            nuevaAfectado == viejo.size() && nuevaEstado == viejo.size() + 1 && lv.inicializacion().size() == 2);
        check("columnas faltantes: Afectado «No indicado», Estado vacío",
            ColumnasRemate.NO_INDICADO.equals(lv.hoja().filas().get(0).celda(cols.indexOf(ColumnasRemate.AFECTADO)).getValor())
                && "".equals(lv.hoja().filas().get(0).celda(cols.indexOf(ColumnasRemate.ESTADO)).getValor()));
        SimuladorSheets simViejo = new SimuladorSheets()
            .agregarPestana(ClienteSheets.TAB_TERRENOS, cols.subList(0, cols.size() - 2))
            .agregarPestana(ClienteSheets.TAB_VEHICULOS, ColumnasRemate.VEH_HEADERS.subList(0, ColumnasRemate.VEH_HEADERS.size() - 2));
        simViejo.cargar();
        HojaSincronizada recargada = simViejo.cargar().terrenos();
        check("tras la primera carga el archivo ya tiene Afectado y Estado (sin más peticiones)",
            recargada.columnaEnHoja(cols.indexOf(ColumnasRemate.ESTADO)) == cols.size() - 1 && simViejo.lotes().size() == 1);
        LectorHoja.Lectura vacia = LectorHoja.leer(hoja(List.of()), cols);
        check("pestaña vacía → se escribe el encabezado", vacia.inicializacion().size() == 1 && vacia.hoja().filas().isEmpty());
    }

    // ------------------------------------------------------------------ 4. each change + undo/redo vs the simulated file

    private static void cambiosYDeshacer() throws Exception {
        SimuladorSheets sim = SimuladorSheets.conPestanas();
        sim.agregarFila(ClienteSheets.TAB_TERRENOS, fila("A1", 1_000_000, "CARTAGO"), null);
        sim.agregarFila(ClienteSheets.TAB_TERRENOS, fila("A2", 2_000_000, "HEREDIA"), null);
        sim.agregarFila(ClienteSheets.TAB_TERRENOS, fila("A3", 3_000_000, "LIMÓN"), null);
        sim.agregarFila(ClienteSheets.TAB_TERRENOS, fila("A4", 4_000_000, "ALAJUELA"), null);
        HojaSincronizada h = sim.cargar().terrenos();
        ColaGuardado cola = edt(() -> new ColaGuardado(sim, 50));
        Historial hist = new Historial(cola);
        int prov = h.columnas().indexOf("Provincia");
        int monto = h.columnas().indexOf("Monto primer remate");

        record Paso(String nombre, Runnable accion) {
        }
        FilaSincronizada nueva = FilaSincronizada.deValores(fila("B9", 9_999.99, "GUANACASTE"));
        List<Paso> pasos = List.of(
            new Paso("editar texto", () -> hist.hacer(new Cambios.EditarCelda(h, h.filas().get(1), prov, "SAN JOSÉ"))),
            new Paso("editar monto", () -> hist.hacer(new Cambios.EditarCelda(h, h.filas().get(2), monto, 123.45))),
            new Paso("formato de celda", () -> hist.hacer(new Cambios.FormatearCeldas(h,
                List.of(new Cambios.Celda(h.filas().get(0), prov)), f -> f.conFondo(Color.YELLOW).conNegrita(true), "f"))),
            new Paso("formato de fila completa", () -> {
                List<Cambios.Celda> fila = new ArrayList<>();
                for (int c = 0; c < h.columnas().size(); c++) {
                    fila.add(new Cambios.Celda(h.filas().get(3), c));
                }
                hist.hacer(new Cambios.FormatearCeldas(h, fila, f -> f.conTamano(14).conFuente(Color.RED).conAlineacion("CENTER"), "f"));
            }),
            new Paso("agregar fila", () -> hist.hacer(new Cambios.AgregarFila(h, nueva))),
            new Paso("eliminar filas no contiguas", () -> hist.hacer(new Cambios.EliminarFilas(h,
                List.of(h.filas().get(0), h.filas().get(2))))),
            new Paso("reemplazar valores", () -> hist.hacer(new Cambios.ReemplazarValores(h, nueva,
                fila("B9", 10_000, "PUNTARENAS")))));

        for (Paso p : pasos) {
            edt(p.accion());
            esperarGuardado(cola);
            String d = diferencia(h, sim.cargar().terrenos());
            check("«" + p.nombre() + "»: archivo = modelo" + (d == null ? "" : " — " + d), d == null);
        }
        for (int i = pasos.size() - 1; i >= 0; i--) {
            edt(() -> {
                hist.deshacer();
            });
            esperarGuardado(cola);
            String d = diferencia(h, sim.cargar().terrenos());
            check("deshacer «" + pasos.get(i).nombre() + "»: archivo = modelo" + (d == null ? "" : " — " + d), d == null);
        }
        check("todo deshecho: 4 filas originales", h.filas().size() == 4 && "A1".equals(h.filas().get(0).celda(1).getValor()));
        for (int i = 0; i < 3; i++) {
            edt(() -> {
                hist.rehacer();
            });
        }
        esperarGuardado(cola);
        String d = diferencia(h, sim.cargar().terrenos());
        check("rehacer 3: archivo = modelo" + (d == null ? "" : " — " + d), d == null);
        check("historial: puede deshacer y rehacer", hist.puedeDeshacer() && hist.puedeRehacer());
        edt(() -> hist.hacer(new Cambios.EditarCelda(h, h.filas().get(0), prov, "OTRA")));
        check("un cambio nuevo borra lo rehacible", !hist.puedeRehacer());
        esperarGuardado(cola);
    }

    // ------------------------------------------------------------------ 5. random sequence

    private static void secuenciaAleatoria() throws Exception {
        Random rnd = new Random(7);
        SimuladorSheets sim = SimuladorSheets.conPestanas();
        for (int i = 0; i < 12; i++) {
            sim.agregarFila(ClienteSheets.TAB_TERRENOS, fila("R" + i, i * 1000.0, "P" + i), null);
        }
        HojaSincronizada h = sim.cargar().terrenos();
        ColaGuardado cola = edt(() -> new ColaGuardado(sim, 30));
        Historial hist = new Historial(cola);
        sim.setDemoraMs(5);
        int ops = 300;
        for (int i = 0; i < ops; i++) {
            int op = rnd.nextInt(8);
            edt(() -> {
                int n = h.filas().size();
                switch (op) {
                    case 0, 1 -> {
                        if (n > 0) {
                            hist.hacer(new Cambios.EditarCelda(h, h.filas().get(rnd.nextInt(n)), rnd.nextInt(h.columnas().size()),
                                "v" + rnd.nextInt(1000)));
                        }
                    }
                    case 2 -> {
                        if (n > 0) {
                            List<Cambios.Celda> cs = new ArrayList<>();
                            FilaSincronizada f = h.filas().get(rnd.nextInt(n));
                            cs.add(new Cambios.Celda(f, rnd.nextInt(h.columnas().size())));
                            Color c = new Color(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256));
                            hist.hacer(new Cambios.FormatearCeldas(h, cs, x -> x.conFondo(c).conCursiva(rnd.nextBoolean()), "f"));
                        }
                    }
                    case 3 -> hist.hacer(new Cambios.AgregarFila(h, FilaSincronizada.deValores(fila("N" + rnd.nextInt(99), 1, "X"))));
                    case 4 -> {
                        if (n > 1) {
                            List<FilaSincronizada> fs = new ArrayList<>();
                            fs.add(h.filas().get(rnd.nextInt(n)));
                            FilaSincronizada otra = h.filas().get(rnd.nextInt(n));
                            if (!fs.contains(otra)) {
                                fs.add(otra);
                            }
                            hist.hacer(new Cambios.EliminarFilas(h, fs));
                        }
                    }
                    case 5, 6 -> hist.deshacer();
                    default -> hist.rehacer();
                }
            });
        }
        esperarGuardado(cola);
        String d = diferencia(h, sim.cargar().terrenos());
        check(ops + " operaciones aleatorias con deshacer/rehacer: archivo = modelo (" + h.filas().size() + " filas, "
            + sim.lotes().size() + " envíos)" + (d == null ? "" : " — " + d), d == null);
    }

    // ------------------------------------------------------------------ 6. live save queue

    private static void colaDeGuardado() throws Exception {
        SimuladorSheets sim = SimuladorSheets.conPestanas();
        sim.agregarFila(ClienteSheets.TAB_TERRENOS, fila("A1", 1, "CARTAGO"), null);
        sim.agregarFila(ClienteSheets.TAB_TERRENOS, fila("A2", 2, "HEREDIA"), null);
        HojaSincronizada h = sim.cargar().terrenos();
        ColaGuardado cola = edt(() -> new ColaGuardado(sim, 1500));
        Historial hist = new Historial(cola);
        int prov = h.columnas().indexOf("Provincia");
        List<Cambios.Celda> celda0 = List.of(new Cambios.Celda(h.filas().get(0), prov));

        // immediate + batching while in flight
        sim.setDemoraMs(300);
        int antes = sim.lotes().size();
        edt(() -> {
            hist.hacer(new Cambios.FormatearCeldas(h, celda0, f -> f.conNegrita(true), "n"));
            hist.hacer(new Cambios.FormatearCeldas(h, celda0, f -> f.conCursiva(true), "k"));
            hist.hacer(new Cambios.FormatearCeldas(h, celda0, f -> f.conSubrayado(true), "s"));
        });
        check("formato: se envía de inmediato (Guardando…)", edt(cola::estado) == ColaGuardado.Estado.GUARDANDO);
        esperarGuardado(cola);
        check("3 cambios rápidos = 2 envíos (1 inmediato + 2 juntos)", sim.lotes().size() - antes == 2);
        sim.setDemoraMs(0);

        // 1.5 s pause for typed values
        antes = sim.lotes().size();
        long t0 = System.currentTimeMillis();
        edt(() -> hist.hacer(new Cambios.EditarCelda(h, h.filas().get(1), prov, "LIMÓN")));
        Thread.sleep(700);
        edt(() -> hist.hacer(new Cambios.EditarCelda(h, h.filas().get(1), prov, "LIMÓN 2")));
        check("texto: espera (Cambios por guardar…)", edt(cola::estado) == ColaGuardado.Estado.ESPERANDO);
        Thread.sleep(1000);
        check("texto: a 1 s del último cambio todavía no se envió", sim.lotes().size() == antes);
        esperarGuardado(cola);
        long ms = System.currentTimeMillis() - t0;
        check("texto: enviado ~1.5 s después del último cambio (" + ms + " ms) en 1 envío",
            ms >= 2100 && sim.lotes().size() - antes == 1);

        // a non-text change sends waiting text first, in order
        antes = sim.lotes().size();
        edt(() -> {
            hist.hacer(new Cambios.EditarCelda(h, h.filas().get(0), prov, "TEXTO"));
            hist.hacer(new Cambios.EliminarFilas(h, List.of(h.filas().get(0))));
        });
        esperarGuardado(cola);
        String d = diferencia(h, sim.cargar().terrenos());
        check("texto pendiente + eliminar: orden correcto, archivo = modelo" + (d == null ? "" : " — " + d), d == null);

        // failure: cells marked, kept, retried
        sim.setFallar(true);
        FilaSincronizada f0 = h.filas().get(0);
        edt(() -> hist.hacer(new Cambios.FormatearCeldas(h, List.of(new Cambios.Celda(f0, prov)), f -> f.conFondo(Color.CYAN), "c")));
        for (int i = 0; i < 100 && edt(cola::estado) != ColaGuardado.Estado.ERROR; i++) {
            Thread.sleep(20);
        }
        check("fallo: estado ERROR", edt(cola::estado) == ColaGuardado.Estado.ERROR);
        check("fallo: celda marcada sin guardar", f0.celda(prov).isSinGuardar());
        check("fallo: 1 cambio sin guardar", edt(cola::sinGuardar) == 1);
        check("fallo: mensaje del error", "Sin conexión (simulada)".equals(edt(cola::ultimoError)));
        edt(() -> hist.hacer(new Cambios.EditarCelda(h, f0, prov, "NUEVO")));
        Thread.sleep(300);
        check("otro cambio sin conexión: 2 sin guardar, en rojo", edt(cola::sinGuardar) == 2 && f0.celda(prov).isSinGuardar());
        sim.setFallar(false);
        edt(cola::reintentar);
        esperarGuardado(cola);
        check("reintentar: guardado y sin marca", !f0.celda(prov).isSinGuardar());
        d = diferencia(h, sim.cargar().terrenos());
        check("reintentar: archivo = modelo" + (d == null ? "" : " — " + d), d == null);
    }

    private SincronizadosTest() {
    }
}
