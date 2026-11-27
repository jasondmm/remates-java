package remates.sheets;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.api.services.sheets.v4.model.AppendDimensionRequest;
import com.google.api.services.sheets.v4.model.CellData;
import com.google.api.services.sheets.v4.model.CellFormat;
import com.google.api.services.sheets.v4.model.ExtendedValue;
import com.google.api.services.sheets.v4.model.GridCoordinate;
import com.google.api.services.sheets.v4.model.GridData;
import com.google.api.services.sheets.v4.model.Request;
import com.google.api.services.sheets.v4.model.RowData;
import com.google.api.services.sheets.v4.model.Sheet;
import com.google.api.services.sheets.v4.model.TextFormat;
import com.google.api.services.sheets.v4.model.UpdateCellsRequest;

import remates.sincronizados.CeldaSincronizada;
import remates.sincronizados.FilaSincronizada;
import remates.sincronizados.Formato;
import remates.sincronizados.HojaSincronizada;
import remates.ui.ColumnasRemate;

/**
 * Turns a tab read from Google Sheets (values + formats) into a {@link HojaSincronizada} with the app's columns.
 * Columns are found by their header name, so the sheet may order them differently or have extra columns (those are
 * left untouched). An empty tab gets the header row written, and columns the sheet lacks get their header appended
 * at the end (both returned as requests).
 */
public final class LectorHoja {
    private LectorHoja() {
    }

    /** The sheet as read, plus the requests to write the header row when the tab was empty (else none). */
    public record Lectura(HojaSincronizada hoja, List<Request> inicializacion) {
    }

    public static Lectura leer(Sheet sheet, List<String> columnasApp) {
        String titulo = sheet.getProperties().getTitle();
        int sheetId = sheet.getProperties().getSheetId();
        List<RowData> filas = filas(sheet);
        List<String> encabezado = filas.isEmpty() ? List.of() : textos(filas.get(0));
        if (encabezado.stream().allMatch(String::isEmpty)) {
            int[] identidad = new int[columnasApp.size()];
            for (int i = 0; i < identidad.length; i++) {
                identidad[i] = i;
            }
            HojaSincronizada hoja = new HojaSincronizada(titulo, sheetId, columnasApp, identidad, List.of());
            return new Lectura(hoja, List.of(escribirEncabezado(sheetId, columnasApp)));
        }

        Map<String, Integer> posicion = new HashMap<>();
        for (int i = 0; i < encabezado.size(); i++) {
            posicion.putIfAbsent(encabezado.get(i).strip(), i);
        }
        // columns the sheet does not have yet (e.g. added to the app later) get their header at the end
        List<Request> inicializacion = new ArrayList<>();
        int siguiente = encabezado.size();
        int[] enHoja = new int[columnasApp.size()];
        for (int c = 0; c < columnasApp.size(); c++) {
            Integer p = posicion.get(columnasApp.get(c));
            if (p == null) {
                enHoja[c] = siguiente;
                inicializacion.add(encabezadoEn(sheetId, siguiente, columnasApp.get(c)));
                siguiente++;
            } else {
                enHoja[c] = p;
            }
        }
        Integer columnasGrilla = sheet.getProperties().getGridProperties() == null ? null
            : sheet.getProperties().getGridProperties().getColumnCount();
        if (columnasGrilla != null && siguiente > columnasGrilla) {
            inicializacion.add(0, new Request().setAppendDimension(new AppendDimensionRequest()
                .setSheetId(sheetId).setDimension("COLUMNS").setLength(siguiente - columnasGrilla)));
        }

        List<FilaSincronizada> datos = new ArrayList<>();
        for (int r = 1; r < filas.size(); r++) {
            List<CellData> celdas = filas.get(r).getValues();
            List<CeldaSincronizada> fila = new ArrayList<>(columnasApp.size());
            for (int c = 0; c < columnasApp.size(); c++) {
                CellData dato = celdas == null || enHoja[c] >= celdas.size() ? null : celdas.get(enHoja[c]);
                fila.add(new CeldaSincronizada(valor(dato, columnasApp.get(c)),
                    Formato.desde(dato == null ? null : dato.getUserEnteredFormat())));
            }
            datos.add(new FilaSincronizada(fila));
        }
        // trailing rows without any value (only formatting) are not data
        while (!datos.isEmpty() && vacia(datos.get(datos.size() - 1))) {
            datos.remove(datos.size() - 1);
        }
        return new Lectura(new HojaSincronizada(titulo, sheetId, columnasApp, enHoja, datos), inicializacion);
    }

    /**
     * Amount columns: the number itself; other columns: the text as shown in Sheets; an empty cell stays
     * empty ("") in every column, as in the file.
     */
    static Object valor(CellData dato, String columna) {
        boolean numerica = ColumnasRemate.NUM_COLS.contains(columna);
        String vacio = "";
        if (dato == null) {
            return vacio;
        }
        ExtendedValue v = dato.getEffectiveValue();
        if (numerica && v != null && v.getNumberValue() != null) {
            return v.getNumberValue();
        }
        String texto = dato.getFormattedValue();
        if (texto == null && v != null) {
            texto = v.getStringValue() != null ? v.getStringValue()
                : v.getNumberValue() != null ? String.valueOf(v.getNumberValue()) : null;
        }
        texto = texto == null ? "" : texto.strip();
        return texto.isEmpty() ? vacio : texto;
    }

    private static boolean vacia(FilaSincronizada f) {
        return f.celdas().stream().allMatch(c -> ColumnasRemate.NO_INDICADO.equals(c.getValor())
            || "".equals(c.getValor()) || c.getValor() == null);
    }

    private static List<RowData> filas(Sheet sheet) {
        List<GridData> data = sheet.getData();
        if (data == null || data.isEmpty() || data.get(0).getRowData() == null) {
            return List.of();
        }
        return data.get(0).getRowData();
    }

    private static List<String> textos(RowData fila) {
        List<String> t = new ArrayList<>();
        if (fila.getValues() != null) {
            for (CellData c : fila.getValues()) {
                t.add(c == null || c.getFormattedValue() == null ? "" : c.getFormattedValue().strip());
            }
        }
        return t;
    }

    private static Request encabezadoEn(int sheetId, int columna, String nombre) {
        return new Request().setUpdateCells(new UpdateCellsRequest()
            .setStart(new GridCoordinate().setSheetId(sheetId).setRowIndex(0).setColumnIndex(columna))
            .setRows(List.of(new RowData().setValues(List.of(new CellData()
                .setUserEnteredValue(new ExtendedValue().setStringValue(nombre))
                .setUserEnteredFormat(new CellFormat().setTextFormat(new TextFormat().setBold(true)))))))
            .setFields("userEnteredValue,userEnteredFormat.textFormat.bold"));
    }

    private static Request escribirEncabezado(int sheetId, List<String> columnas) {
        List<CellData> celdas = new ArrayList<>();
        for (String c : columnas) {
            celdas.add(new CellData()
                .setUserEnteredValue(new ExtendedValue().setStringValue(c))
                .setUserEnteredFormat(new CellFormat().setTextFormat(new TextFormat().setBold(true))));
        }
        return new Request().setUpdateCells(new UpdateCellsRequest()
            .setStart(new GridCoordinate().setSheetId(sheetId).setRowIndex(0).setColumnIndex(0))
            .setRows(List.of(new RowData().setValues(celdas)))
            .setFields("userEnteredValue,userEnteredFormat.textFormat.bold"));
    }
}
