package remates.sincronizados;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

import com.google.api.services.sheets.v4.model.Request;

/** The changes the "Remates sincronizados" form can make, each one undoable. */
public final class Cambios {
    private Cambios() {
    }

    /** A cell of a row, identified by the row object (stable while rows are added or deleted) and the column. */
    public record Celda(FilaSincronizada fila, int columna) {
        public CeldaSincronizada celda() {
            return fila.celda(columna);
        }
    }

    // ------------------------------------------------------------------ edit one value

    public static final class EditarCelda implements Cambio {
        private final HojaSincronizada hoja;
        private final Celda celda;
        private final Object antes;
        private final Object despues;

        public EditarCelda(HojaSincronizada hoja, FilaSincronizada fila, int columna, Object despues) {
            this.hoja = hoja;
            this.celda = new Celda(fila, columna);
            this.antes = celda.celda().getValor();
            this.despues = despues;
        }

        @Override
        public String descripcion() {
            return "Editar celda";
        }

        @Override
        public List<Request> aplicar() {
            return poner(despues);
        }

        @Override
        public List<Request> revertir() {
            return poner(antes);
        }

        private List<Request> poner(Object valor) {
            celda.celda().setValor(valor);
            return List.of(Peticiones.valorCelda(hoja, hoja.indiceDe(celda.fila()), celda.columna(), valor));
        }

        @Override
        public List<CeldaSincronizada> celdas() {
            return List.of(celda.celda());
        }

        @Override
        public boolean esTexto() {
            return true;
        }
    }

    // ------------------------------------------------------------------ format cells (or full rows)

    public static final class FormatearCeldas implements Cambio {
        private final HojaSincronizada hoja;
        private final List<Celda> celdas;
        private final List<Formato> antes = new ArrayList<>();
        private final List<Formato> despues = new ArrayList<>();
        private final String descripcion;

        /**
         * @param cambio how each cell's format changes (e.g. {@code f -> f.conNegrita(true)}); applied once, the
         *               result is kept so redo gives exactly the same formats
         */
        public FormatearCeldas(HojaSincronizada hoja, List<Celda> celdas, UnaryOperator<Formato> cambio,
                               String descripcion) {
            this.hoja = hoja;
            this.celdas = List.copyOf(celdas);
            this.descripcion = descripcion;
            for (Celda c : celdas) {
                Formato f = c.celda().getFormato();
                antes.add(f);
                despues.add(cambio.apply(f));
            }
        }

        @Override
        public String descripcion() {
            return descripcion;
        }

        @Override
        public List<Request> aplicar() {
            return poner(despues);
        }

        @Override
        public List<Request> revertir() {
            return poner(antes);
        }

        private List<Request> poner(List<Formato> formatos) {
            List<Request> r = new ArrayList<>();
            for (int i = 0; i < celdas.size(); i++) {
                Celda c = celdas.get(i);
                c.celda().setFormato(formatos.get(i));
                r.add(Peticiones.formatoCelda(hoja, hoja.indiceDe(c.fila()), c.columna(), formatos.get(i)));
            }
            return r;
        }

        @Override
        public List<CeldaSincronizada> celdas() {
            return celdas.stream().map(Celda::celda).toList();
        }
    }

    // ------------------------------------------------------------------ delete rows

    public static final class EliminarFilas implements Cambio {
        private final HojaSincronizada hoja;
        private final List<FilaSincronizada> filas;
        /** Model positions at deletion time, ascending (same order as {@link #filas}). */
        private final List<Integer> posiciones = new ArrayList<>();

        public EliminarFilas(HojaSincronizada hoja, List<FilaSincronizada> filas) {
            this.hoja = hoja;
            this.filas = new ArrayList<>(filas);
            this.filas.sort(Comparator.comparingInt(hoja::indiceDe));
        }

        @Override
        public String descripcion() {
            return filas.size() == 1 ? "Eliminar fila" : "Eliminar " + filas.size() + " filas";
        }

        @Override
        public List<Request> aplicar() {
            posiciones.clear();
            for (FilaSincronizada f : filas) {
                posiciones.add(hoja.indiceDe(f));
            }
            List<Request> r = new ArrayList<>();
            for (int i = filas.size() - 1; i >= 0; i--) {   // bottom-up, so earlier positions stay valid
                int p = posiciones.get(i);
                hoja.filas().remove(p);
                r.add(Peticiones.eliminarFila(hoja, p));
            }
            return r;
        }

        @Override
        public List<Request> revertir() {
            List<Request> r = new ArrayList<>();
            for (int i = 0; i < filas.size(); i++) {          // top-down, back to their original places
                int p = posiciones.get(i);
                hoja.filas().add(p, filas.get(i));
                r.addAll(Peticiones.insertarFila(hoja, p, filas.get(i)));
            }
            return r;
        }

        @Override
        public List<CeldaSincronizada> celdas() {
            return filas.stream().flatMap(f -> f.celdas().stream()).toList();
        }
    }

    // ------------------------------------------------------------------ add a row (from a Boletín form)

    public static final class AgregarFila implements Cambio {
        private final HojaSincronizada hoja;
        private final FilaSincronizada fila;

        public AgregarFila(HojaSincronizada hoja, FilaSincronizada fila) {
            this.hoja = hoja;
            this.fila = fila;
        }

        @Override
        public String descripcion() {
            return "Agregar fila";
        }

        @Override
        public List<Request> aplicar() {
            int p = hoja.filas().size();
            hoja.filas().add(fila);
            return Peticiones.insertarFila(hoja, p, fila);
        }

        @Override
        public List<Request> revertir() {
            int p = hoja.indiceDe(fila);
            hoja.filas().remove(p);
            return List.of(Peticiones.eliminarFila(hoja, p));
        }

        @Override
        public List<CeldaSincronizada> celdas() {
            return fila.celdas();
        }

        public FilaSincronizada fila() {
            return fila;
        }
    }

    // ------------------------------------------------------------------ replace values (keeps formatting)

    public static final class ReemplazarValores implements Cambio {
        private final HojaSincronizada hoja;
        private final FilaSincronizada fila;
        private final Object[] antes;
        private final Object[] despues;

        public ReemplazarValores(HojaSincronizada hoja, FilaSincronizada fila, Object[] nuevos) {
            this.hoja = hoja;
            this.fila = fila;
            this.antes = fila.valores();
            this.despues = new Object[nuevos.length];
            for (int i = 0; i < nuevos.length; i++) {
                despues[i] = FilaSincronizada.normal(nuevos[i]);
            }
        }

        @Override
        public String descripcion() {
            return "Reemplazar valores";
        }

        @Override
        public List<Request> aplicar() {
            return poner(despues, antes);
        }

        @Override
        public List<Request> revertir() {
            return poner(antes, despues);
        }

        /** Writes only the cells whose value differs. */
        private List<Request> poner(Object[] valores, Object[] actuales) {
            int p = hoja.indiceDe(fila);
            List<Request> r = new ArrayList<>();
            for (int c = 0; c < valores.length; c++) {
                if (!Objects.equals(valores[c], actuales[c])) {
                    fila.celda(c).setValor(valores[c]);
                    r.add(Peticiones.valorCelda(hoja, p, c, valores[c]));
                }
            }
            return r;
        }

        @Override
        public List<CeldaSincronizada> celdas() {
            List<CeldaSincronizada> l = new ArrayList<>();
            for (int c = 0; c < despues.length; c++) {
                if (!Objects.equals(antes[c], despues[c])) {
                    l.add(fila.celda(c));
                }
            }
            return l;
        }
    }
}
