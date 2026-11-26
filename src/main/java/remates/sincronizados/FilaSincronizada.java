package remates.sincronizados;

import java.util.ArrayList;
import java.util.List;

/** One row of the synchronized sheet: one cell per column of the app (same order as the Boletín tables). */
public final class FilaSincronizada {
    private final List<CeldaSincronizada> celdas;

    public FilaSincronizada(List<CeldaSincronizada> celdas) {
        this.celdas = new ArrayList<>(celdas);
    }

    /** A new row with the given values and no formatting (e.g. added from a Boletín form). */
    public static FilaSincronizada deValores(Object[] valores) {
        List<CeldaSincronizada> c = new ArrayList<>(valores.length);
        for (Object v : valores) {
            c.add(new CeldaSincronizada(normal(v), Formato.VACIO));
        }
        return new FilaSincronizada(c);
    }

    /** An empty cell ("Estado" from a Boletín is null) is kept as "", as it comes back from Google Sheets. */
    public static Object normal(Object v) {
        return v == null ? "" : v;
    }

    public CeldaSincronizada celda(int columna) {
        return celdas.get(columna);
    }

    public List<CeldaSincronizada> celdas() {
        return celdas;
    }

    public int columnas() {
        return celdas.size();
    }

    public Object[] valores() {
        Object[] v = new Object[celdas.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = celdas.get(i).getValor();
        }
        return v;
    }
}
