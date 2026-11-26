package remates.sheets;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.google.api.services.sheets.v4.model.CellData;
import com.google.api.services.sheets.v4.model.CellFormat;
import com.google.api.services.sheets.v4.model.DimensionRange;
import com.google.api.services.sheets.v4.model.ExtendedValue;
import com.google.api.services.sheets.v4.model.GridData;
import com.google.api.services.sheets.v4.model.Request;
import com.google.api.services.sheets.v4.model.RowData;
import com.google.api.services.sheets.v4.model.Sheet;
import com.google.api.services.sheets.v4.model.SheetProperties;
import com.google.api.services.sheets.v4.model.TextFormat;
import com.google.api.services.sheets.v4.model.UpdateCellsRequest;

import remates.sincronizados.Formato;
import remates.ui.ColumnasRemate;

/**
 * In-memory Google spreadsheet for tests: applies the app's batchUpdate requests (updateCells with field masks,
 * insertDimension, deleteDimension) to a grid of cells, and loads from it the way {@link ClienteSheets#cargar()}
 * does. Lets tests check that the requests leave the "file" exactly as the app's model.
 */
public final class SimuladorSheets implements FuenteSheets {

    private static final class Pestana {
        final String titulo;
        final int id;
        final List<List<CellData>> filas = new ArrayList<>();

        Pestana(String titulo, int id) {
            this.titulo = titulo;
            this.id = id;
        }
    }

    private final List<Pestana> pestanas = new ArrayList<>();
    private final List<List<Request>> lotes = new ArrayList<>();
    private volatile boolean fallar;
    private volatile long demoraMs;

    /** A spreadsheet with both tabs and the app's headers. */
    public static SimuladorSheets conPestanas() {
        SimuladorSheets s = new SimuladorSheets();
        s.agregarPestana(ClienteSheets.TAB_TERRENOS, ColumnasRemate.TERRENOS_HEADERS);
        s.agregarPestana(ClienteSheets.TAB_VEHICULOS, ColumnasRemate.VEH_HEADERS);
        return s;
    }

    public synchronized SimuladorSheets agregarPestana(String titulo, List<String> encabezado) {
        Pestana p = new Pestana(titulo, 100 + pestanas.size());
        if (encabezado != null) {
            List<CellData> fila = new ArrayList<>();
            encabezado.forEach(h -> fila.add(new CellData().setUserEnteredValue(new ExtendedValue().setStringValue(h))));
            p.filas.add(fila);
        }
        pestanas.add(p);
        return this;
    }

    /** Appends a data row (values in the tab's header order; formats may be null). */
    public synchronized void agregarFila(String titulo, Object[] valores, Formato[] formatos) {
        List<CellData> fila = new ArrayList<>();
        for (int i = 0; i < valores.length; i++) {
            CellData c = new CellData().setUserEnteredValue(remates.sincronizados.Peticiones.valor(valores[i]));
            if (formatos != null && formatos[i] != null) {
                c.setUserEnteredFormat(formatos[i].aCellFormat());
            }
            fila.add(c);
        }
        pestana(titulo).filas.add(fila);
    }

    public void setFallar(boolean fallar) {
        this.fallar = fallar;
    }

    public void setDemoraMs(long demoraMs) {
        this.demoraMs = demoraMs;
    }

    /** Batches received (one per batchUpdate call). */
    public synchronized List<List<Request>> lotes() {
        return new ArrayList<>(lotes);
    }

    // ------------------------------------------------------------------ FuenteSheets

    @Override
    public ClienteSheets.Carga cargar() {
        List<Sheet> hojas;
        synchronized (this) {
            for (String t : List.of(ClienteSheets.TAB_TERRENOS, ClienteSheets.TAB_VEHICULOS)) {
                if (pestanas.stream().noneMatch(p -> p.titulo.equals(t))) {
                    throw new IllegalStateException("La hoja «Hoja de prueba» no tiene las pestañas: " + t);
                }
            }
            hojas = new ArrayList<>();
            for (Pestana p : pestanas) {
                List<RowData> filas = new ArrayList<>();
                for (List<CellData> f : p.filas) {
                    List<CellData> celdas = new ArrayList<>();
                    for (CellData c : f) {
                        celdas.add(comoLeida(c));
                    }
                    filas.add(new RowData().setValues(celdas));
                }
                hojas.add(new Sheet().setProperties(new SheetProperties().setTitle(p.titulo).setSheetId(p.id))
                    .setData(List.of(new GridData().setRowData(filas))));
            }
        }
        LectorHoja.Lectura t = LectorHoja.leer(buscar(hojas, ClienteSheets.TAB_TERRENOS), ColumnasRemate.TERRENOS_HEADERS);
        LectorHoja.Lectura v = LectorHoja.leer(buscar(hojas, ClienteSheets.TAB_VEHICULOS), ColumnasRemate.VEH_HEADERS);
        List<Request> inicio = new ArrayList<>(t.inicializacion());
        inicio.addAll(v.inicializacion());
        if (!inicio.isEmpty()) {
            aplicar(inicio);
        }
        return new ClienteSheets.Carga("Hoja de prueba", t.hoja(), v.hoja());
    }

    @Override
    public void enviar(List<Request> peticiones) throws Exception {
        if (demoraMs > 0) {
            Thread.sleep(demoraMs);
        }
        if (fallar) {
            throw new IOException("Sin conexión (simulada)");
        }
        aplicar(peticiones);
    }

    private synchronized void aplicar(List<Request> peticiones) {
        lotes.add(List.copyOf(peticiones));
        for (Request r : peticiones) {
            if (r.getUpdateCells() != null) {
                actualizar(r.getUpdateCells());
            } else if (r.getInsertDimension() != null) {
                DimensionRange d = r.getInsertDimension().getRange();
                for (int i = d.getStartIndex(); i < d.getEndIndex(); i++) {
                    pestana(d.getSheetId()).filas.add(i, new ArrayList<>());
                }
            } else if (r.getAppendDimension() != null) {
                // grid size is not limited in the simulation: nothing to do
            } else if (r.getDeleteDimension() != null) {
                DimensionRange d = r.getDeleteDimension().getRange();
                for (int i = d.getEndIndex() - 1; i >= d.getStartIndex(); i--) {
                    pestana(d.getSheetId()).filas.remove(i);
                }
            } else {
                throw new IllegalArgumentException("Petición no simulada: " + r);
            }
        }
    }

    private void actualizar(UpdateCellsRequest u) {
        Pestana p = pestana(u.getStart().getSheetId());
        int fila = u.getStart().getRowIndex();
        int col = u.getStart().getColumnIndex();
        String campos = u.getFields();
        for (RowData rd : u.getRows()) {
            while (p.filas.size() <= fila) {
                p.filas.add(new ArrayList<>());
            }
            List<CellData> celdas = p.filas.get(fila);
            for (int k = 0; k < rd.getValues().size(); k++) {
                while (celdas.size() <= col + k) {
                    celdas.add(new CellData());
                }
                CellData actual = celdas.get(col + k);
                CellData nuevo = rd.getValues().get(k);
                if (campos.contains("userEnteredValue")) {
                    actual.setUserEnteredValue(nuevo.getUserEnteredValue());
                }
                if (campos.contains("userEnteredFormat.backgroundColor")) {
                    CellFormat f = actual.getUserEnteredFormat() == null ? new CellFormat() : actual.getUserEnteredFormat();
                    CellFormat n = nuevo.getUserEnteredFormat() == null ? new CellFormat() : nuevo.getUserEnteredFormat();
                    TextFormat tf = f.getTextFormat() == null ? new TextFormat() : f.getTextFormat();
                    TextFormat tn = n.getTextFormat() == null ? new TextFormat() : n.getTextFormat();
                    f.setBackgroundColor(n.getBackgroundColor()).setHorizontalAlignment(n.getHorizontalAlignment());
                    tf.setForegroundColor(tn.getForegroundColor()).setFontSize(tn.getFontSize()).setBold(tn.getBold())
                        .setItalic(tn.getItalic()).setUnderline(tn.getUnderline()).setStrikethrough(tn.getStrikethrough());
                    f.setTextFormat(tf);
                    if (campos.contains("numberFormat")) {
                        f.setNumberFormat(n.getNumberFormat());
                    }
                    actual.setUserEnteredFormat(f);
                } else if (campos.contains("userEnteredFormat.textFormat.bold")) {   // header row
                    CellFormat f = actual.getUserEnteredFormat() == null ? new CellFormat() : actual.getUserEnteredFormat();
                    f.setTextFormat(nuevo.getUserEnteredFormat().getTextFormat());
                    actual.setUserEnteredFormat(f);
                }
            }
            fila++;
        }
    }

    /** The cell as {@code spreadsheets.get} returns it: effective and formatted value plus the format. */
    private static CellData comoLeida(CellData c) {
        ExtendedValue v = c.getUserEnteredValue();
        String texto = v == null ? null
            : v.getStringValue() != null ? v.getStringValue()
            : v.getNumberValue() != null ? String.valueOf(v.getNumberValue()) : null;
        return new CellData().setEffectiveValue(v).setFormattedValue(texto).setUserEnteredFormat(c.getUserEnteredFormat());
    }

    private Pestana pestana(String titulo) {
        return pestanas.stream().filter(p -> p.titulo.equals(titulo)).findFirst().orElseThrow();
    }

    private Pestana pestana(int id) {
        return pestanas.stream().filter(p -> p.id == id).findFirst().orElseThrow();
    }

    private static Sheet buscar(List<Sheet> hojas, String titulo) {
        return hojas.stream().filter(s -> s.getProperties().getTitle().equals(titulo)).findFirst().orElseThrow();
    }
}
