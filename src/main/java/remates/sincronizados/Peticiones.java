package remates.sincronizados;

import java.util.ArrayList;
import java.util.List;

import com.google.api.services.sheets.v4.model.CellData;
import com.google.api.services.sheets.v4.model.CellFormat;
import com.google.api.services.sheets.v4.model.DeleteDimensionRequest;
import com.google.api.services.sheets.v4.model.DimensionRange;
import com.google.api.services.sheets.v4.model.ExtendedValue;
import com.google.api.services.sheets.v4.model.GridCoordinate;
import com.google.api.services.sheets.v4.model.InsertDimensionRequest;
import com.google.api.services.sheets.v4.model.NumberFormat;
import com.google.api.services.sheets.v4.model.Request;
import com.google.api.services.sheets.v4.model.RowData;
import com.google.api.services.sheets.v4.model.UpdateCellsRequest;

import remates.ui.ColumnasRemate;

/** Builds the Google Sheets {@code batchUpdate} requests for the changes of the synchronized form. */
public final class Peticiones {
    private Peticiones() {
    }

    /** Amounts written by the app get the same number format as the Excel ({@code #,##0.00}). */
    private static final NumberFormat FORMATO_NUMERO = new NumberFormat().setType("NUMBER").setPattern("#,##0.00");

    /** The cell's value as a Sheets value: numbers as numbers, everything else as text. */
    public static ExtendedValue valor(Object v) {
        if (v instanceof Number n) {
            return new ExtendedValue().setNumberValue(n.doubleValue());
        }
        return new ExtendedValue().setStringValue(v == null ? "" : v.toString());
    }

    /** Writes the value of one cell (its formatting is left as it is). */
    public static Request valorCelda(HojaSincronizada hoja, int indiceModelo, int columna, Object valor) {
        return celda(hoja, indiceModelo, columna, new CellData().setUserEnteredValue(valor(valor)),
            "userEnteredValue");
    }

    /** Writes the basic formatting of one cell (only the properties the app manages, see {@link Formato#CAMPOS}). */
    public static Request formatoCelda(HojaSincronizada hoja, int indiceModelo, int columna, Formato formato) {
        return celda(hoja, indiceModelo, columna, new CellData().setUserEnteredFormat(formato.aCellFormat()),
            Formato.CAMPOS);
    }

    private static Request celda(HojaSincronizada hoja, int indiceModelo, int columna, CellData dato, String campos) {
        return new Request().setUpdateCells(new UpdateCellsRequest()
            .setStart(new GridCoordinate().setSheetId(hoja.sheetId())
                .setRowIndex(HojaSincronizada.filaEnHoja(indiceModelo))
                .setColumnIndex(hoja.columnaEnHoja(columna)))
            .setRows(List.of(new RowData().setValues(List.of(dato))))
            .setFields(campos));
    }

    /**
     * Inserts a sheet row at the model position and writes its values and formatting (amounts with
     * {@code #,##0.00}). Used to add a row and to bring back a deleted one.
     */
    public static List<Request> insertarFila(HojaSincronizada hoja, int indiceModelo, FilaSincronizada fila) {
        int filaHoja = HojaSincronizada.filaEnHoja(indiceModelo);
        List<Request> r = new ArrayList<>();
        r.add(new Request().setInsertDimension(new InsertDimensionRequest()
            .setRange(new DimensionRange().setSheetId(hoja.sheetId()).setDimension("ROWS")
                .setStartIndex(filaHoja).setEndIndex(filaHoja + 1))
            .setInheritFromBefore(filaHoja > 1)));
        for (int c = 0; c < fila.columnas(); c++) {
            CeldaSincronizada celda = fila.celda(c);
            CellFormat formato = celda.getFormato().aCellFormat();
            if (ColumnasRemate.NUM_COLS.contains(hoja.columnas().get(c))) {
                formato.setNumberFormat(FORMATO_NUMERO);
            }
            r.add(celda(hoja, indiceModelo, c,
                new CellData().setUserEnteredValue(valor(celda.getValor())).setUserEnteredFormat(formato),
                "userEnteredValue," + Formato.CAMPOS + ",userEnteredFormat.numberFormat"));
        }
        return r;
    }

    /** Deletes the sheet row of a model position. */
    public static Request eliminarFila(HojaSincronizada hoja, int indiceModelo) {
        int filaHoja = HojaSincronizada.filaEnHoja(indiceModelo);
        return new Request().setDeleteDimension(new DeleteDimensionRequest()
            .setRange(new DimensionRange().setSheetId(hoja.sheetId()).setDimension("ROWS")
                .setStartIndex(filaHoja).setEndIndex(filaHoja + 1)));
    }
}
