package remates.sincronizados;

import java.util.List;

import com.google.api.services.sheets.v4.model.Request;

/**
 * A change of the synchronized sheet that can be undone. {@link #aplicar()} and {@link #revertir()} modify the
 * local model and return the Google Sheets requests that make the file match it. Requests are computed when the
 * change is applied or reverted, from the row positions at that moment.
 */
public interface Cambio {

    /** Short text for the undo/redo tooltips, e.g. "Editar celda". */
    String descripcion();

    List<Request> aplicar();

    List<Request> revertir();

    /** Cells affected, to mark them when the change cannot be saved. */
    List<CeldaSincronizada> celdas();

    /** True for typed cell values: they are saved after a pause instead of at once. */
    default boolean esTexto() {
        return false;
    }
}
