package remates.sincronizados;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Undo/redo of the synchronized form. Doing, undoing and redoing apply the change to the model and send it to the
 * file through the {@link ColaGuardado} (undo and redo are saved at once, they are not typing).
 */
public final class Historial {
    private final ColaGuardado cola;
    private final Deque<Cambio> deshechos = new ArrayDeque<>();
    private final Deque<Cambio> hechos = new ArrayDeque<>();
    private final List<Runnable> oyentes = new ArrayList<>();

    public Historial(ColaGuardado cola) {
        this.cola = cola;
    }

    /** Called after every do / undo / redo / clear (the model changed). */
    public void alCambiar(Runnable r) {
        oyentes.add(r);
    }

    public void hacer(Cambio c) {
        cola.encolar(c.aplicar(), c.celdas(), c.esTexto());
        hechos.push(c);
        deshechos.clear();
        avisar();
    }

    public boolean deshacer() {
        if (hechos.isEmpty()) {
            return false;
        }
        Cambio c = hechos.pop();
        cola.encolar(c.revertir(), c.celdas(), false);
        deshechos.push(c);
        avisar();
        return true;
    }

    public boolean rehacer() {
        if (deshechos.isEmpty()) {
            return false;
        }
        Cambio c = deshechos.pop();
        cola.encolar(c.aplicar(), c.celdas(), false);
        hechos.push(c);
        avisar();
        return true;
    }

    /** Forgets the history (the file was reloaded). */
    public void limpiar() {
        hechos.clear();
        deshechos.clear();
        avisar();
    }

    public boolean puedeDeshacer() {
        return !hechos.isEmpty();
    }

    public boolean puedeRehacer() {
        return !deshechos.isEmpty();
    }

    public String proximoDeshacer() {
        return hechos.isEmpty() ? null : hechos.peek().descripcion();
    }

    public String proximoRehacer() {
        return deshechos.isEmpty() ? null : deshechos.peek().descripcion();
    }

    private void avisar() {
        oyentes.forEach(Runnable::run);
    }
}
