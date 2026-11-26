package remates.sincronizados;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import remates.ui.ColumnasRemate;

/**
 * One tab of the synchronized spreadsheet ("Terrenos" or "Vehiculos") as the app sees it:
 * the app's columns (same as the Boletín tables), where each one is in the sheet, and the data rows.
 * Row {@code i} of the model is sheet row {@code i + 1} (0-based; row 0 is the header).
 */
public final class HojaSincronizada {

    public static final String REFERENCIA = "Referencia N°";

    private final String titulo;
    private final int sheetId;
    private final List<String> columnas;
    private final int[] columnaEnHoja;
    private final List<FilaSincronizada> filas;

    /**
     * @param columnas      the app's columns, in table order
     * @param columnaEnHoja for each app column, its 0-based column index in the sheet
     */
    public HojaSincronizada(String titulo, int sheetId, List<String> columnas, int[] columnaEnHoja,
                            List<FilaSincronizada> filas) {
        this.titulo = titulo;
        this.sheetId = sheetId;
        this.columnas = List.copyOf(columnas);
        this.columnaEnHoja = columnaEnHoja.clone();
        this.filas = new ArrayList<>(filas);
    }

    public String titulo() {
        return titulo;
    }

    public int sheetId() {
        return sheetId;
    }

    public List<String> columnas() {
        return columnas;
    }

    public int columnaEnHoja(int columna) {
        return columnaEnHoja[columna];
    }

    public List<FilaSincronizada> filas() {
        return filas;
    }

    /** 0-based sheet row of a model row (the header is sheet row 0). */
    public static int filaEnHoja(int indiceModelo) {
        return indiceModelo + 1;
    }

    public int indiceDe(FilaSincronizada fila) {
        for (int i = 0; i < filas.size(); i++) {
            if (filas.get(i) == fila) {
                return i;
            }
        }
        return -1;
    }

    public int columnaReferencia() {
        return columnas.indexOf(REFERENCIA);
    }

    /** Referencia N° values present (blank and "No indicado" excluded). */
    public Set<String> referencias() {
        Set<String> r = new HashSet<>();
        int c = columnaReferencia();
        if (c < 0) {
            return r;
        }
        for (FilaSincronizada f : filas) {
            String ref = clave(f.celda(c).getValor());
            if (ref != null) {
                r.add(ref);
            }
        }
        return r;
    }

    /** Model index of the row with this Referencia N°, or -1. */
    public int indiceDeReferencia(String referencia) {
        String buscada = clave(referencia);
        int c = columnaReferencia();
        if (buscada == null || c < 0) {
            return -1;
        }
        for (int i = 0; i < filas.size(); i++) {
            if (buscada.equals(clave(filas.get(i).celda(c).getValor()))) {
                return i;
            }
        }
        return -1;
    }

    /** Comparable form of a Referencia N° value, or null when there is none. */
    public static String clave(Object valor) {
        if (valor == null) {
            return null;
        }
        String s = valor instanceof Double d && d == Math.rint(d) ? String.valueOf(d.longValue()) : valor.toString();
        s = s.strip();
        return s.isEmpty() || s.equals(ColumnasRemate.NO_INDICADO) ? null : s;
    }
}
