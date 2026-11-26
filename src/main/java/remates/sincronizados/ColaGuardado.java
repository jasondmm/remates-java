package remates.sincronizados;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.swing.SwingUtilities;
import javax.swing.Timer;

import com.google.api.services.sheets.v4.model.Request;

/**
 * Sends the changes of the synchronized form to Google Sheets, live and in order.
 * <ul>
 *   <li>Every change is sent at once; changes made while a request is in flight are sent together as the next
 *       request (keeps well below Google's ~60 writes per minute).</li>
 *   <li>Typed cell values wait {@code esperaTextoMs} (1.5 s) without new typing; any other change sends the waiting
 *       text first, so the order is kept.</li>
 *   <li>When a request fails, its cells are marked "sin guardar" and it stays at the front of the queue; it is sent
 *       again with the next change or with {@link #reintentar()}.</li>
 * </ul>
 * All methods must be called on the Swing event thread; the request itself runs on a background thread.
 */
public final class ColaGuardado {

    /** Where the requests go (Google Sheets {@code batchUpdate}; a fake in tests). */
    @FunctionalInterface
    public interface Destino {
        void enviar(List<Request> peticiones) throws Exception;
    }

    public enum Estado { GUARDADO, ESPERANDO, GUARDANDO, ERROR }

    private record Lote(List<Request> peticiones, List<CeldaSincronizada> celdas) {
    }

    private final Destino destino;
    private final ExecutorService hilo = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "guardado-sheets");
        t.setDaemon(true);
        return t;
    });
    private final Deque<Lote> pendientes = new ArrayDeque<>();
    private final List<Lote> textoEnEspera = new ArrayList<>();
    private final Timer temporizadorTexto;
    private final List<Runnable> oyentes = new ArrayList<>();
    private List<Lote> enVuelo;
    private Estado estado = Estado.GUARDADO;
    private String ultimoError;

    public ColaGuardado(Destino destino, int esperaTextoMs) {
        this.destino = destino;
        this.temporizadorTexto = new Timer(esperaTextoMs, e -> {
            volcarTexto();
            enviar();
        });
        this.temporizadorTexto.setRepeats(false);
    }

    /** Called (on the event thread) whenever the state or the "sin guardar" marks change. */
    public void alCambiar(Runnable r) {
        oyentes.add(r);
    }

    public void encolar(List<Request> peticiones, List<CeldaSincronizada> celdas, boolean texto) {
        if (peticiones.isEmpty()) {
            return;
        }
        Lote lote = new Lote(List.copyOf(peticiones), List.copyOf(celdas));
        if (texto) {
            textoEnEspera.add(lote);
            temporizadorTexto.restart();    // a new typed value restarts the 1.5 s pause
            if (estado != Estado.GUARDANDO && estado != Estado.ERROR) {
                estado = Estado.ESPERANDO;
            }
            avisar();
            return;
        }
        volcarTexto();                      // keep the order: waiting text goes first
        pendientes.add(lote);
        enviar();
    }

    /** Sends the failed (and any waiting) changes now. */
    public void reintentar() {
        volcarTexto();
        enviar();
    }

    /** Sends waiting text right away (e.g. when the form closes). */
    public void enviarYa() {
        reintentar();
    }

    /** Forgets everything not yet sent (used before reloading the file). */
    public void descartar() {
        temporizadorTexto.stop();
        textoEnEspera.clear();
        pendientes.clear();
        if (enVuelo == null) {
            estado = Estado.GUARDADO;
            ultimoError = null;
        }
        avisar();
    }

    public Estado estado() {
        return estado;
    }

    public String ultimoError() {
        return ultimoError;
    }

    /** Changes not saved yet (waiting, queued, failed or in flight). */
    public int sinGuardar() {
        return textoEnEspera.size() + pendientes.size() + (enVuelo == null ? 0 : enVuelo.size());
    }

    private void volcarTexto() {
        temporizadorTexto.stop();
        pendientes.addAll(textoEnEspera);
        textoEnEspera.clear();
    }

    private void enviar() {
        if (enVuelo != null || pendientes.isEmpty()) {
            if (enVuelo == null && pendientes.isEmpty() && textoEnEspera.isEmpty() && estado != Estado.ERROR) {
                estado = Estado.GUARDADO;
            }
            avisar();
            return;
        }
        List<Lote> lotes = new ArrayList<>(pendientes);
        pendientes.clear();
        enVuelo = lotes;
        List<Request> todas = new ArrayList<>();
        lotes.forEach(l -> todas.addAll(l.peticiones()));
        estado = Estado.GUARDANDO;
        avisar();
        hilo.execute(() -> {
            Exception error = null;
            try {
                destino.enviar(todas);
            } catch (Exception e) {
                error = e;
            }
            Exception e = error;
            SwingUtilities.invokeLater(() -> terminado(lotes, e));
        });
    }

    private void terminado(List<Lote> lotes, Exception error) {
        enVuelo = null;
        if (error == null) {
            lotes.forEach(l -> l.celdas().forEach(c -> c.setSinGuardar(false)));
            ultimoError = null;
            estado = Estado.GUARDADO;
            if (!pendientes.isEmpty()) {
                enviar();                   // changes made meanwhile go now, together
                return;
            }
            if (!textoEnEspera.isEmpty()) {
                estado = Estado.ESPERANDO;
            }
        } else {
            lotes.forEach(l -> l.celdas().forEach(c -> c.setSinGuardar(true)));
            for (int i = lotes.size() - 1; i >= 0; i--) {
                pendientes.addFirst(lotes.get(i));      // retried first, before anything newer
            }
            ultimoError = error.getMessage() == null ? error.toString() : error.getMessage();
            estado = Estado.ERROR;
        }
        avisar();
    }

    private void avisar() {
        oyentes.forEach(Runnable::run);
    }
}
