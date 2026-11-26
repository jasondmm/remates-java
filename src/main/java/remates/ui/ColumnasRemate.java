package remates.ui;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import remates.model.DatosRemate;
import remates.model.Terreno;
import remates.model.Vehiculo;

/**
 * Column layout of the two result lists. Same headers, order, widths and "No indicado" filler as the
 * Excel sheets produced by {@code procesar_boletin.py} ("Terrenos" / "Vehiculos").
 */
public final class ColumnasRemate {
    private ColumnasRemate() {
    }

    public static final String NO_INDICADO = "No indicado";

    /** Tab names: the Excel sheets of {@code procesar_boletin.py}, the Google Sheets tabs and the app's tabs. */
    public static final String HOJA_TERRENOS = "Terrenos";
    public static final String HOJA_VEHICULOS = "Vehiculos";

    public static final List<String> TERRENOS_HEADERS = List.of("Número de boletín", "Referencia N°", "Moneda",
        "Monto primer remate", "Fecha primer remate", "Medida de la finca", "Distrito", "Cantón", "Provincia",
        "Descripción del terreno", "Gravámenes y anotaciones", "Monto segundo remate", "Fecha segundo remate",
        "Monto tercer remate", "Fecha tercer remate", "Número de matrícula", "Número de plano", "Expediente",
        "Juzgado", "Beneficiario", "Afectado", "Estado");
    /** Excel column widths (characters). */
    public static final int[] TERRENOS_WIDTHS = {12, 16, 9, 16, 14, 12, 16, 16, 14, 38, 42, 16, 14, 16, 14, 26, 16,
        20, 42, 34, 34, 14};

    public static final List<String> VEH_HEADERS = List.of("Número de boletín", "Referencia N°", "Placa", "Moneda",
        "Monto primer remate", "Fecha primer remate", "Marca", "Estilo", "Año de fabricación",
        "Gravámenes y anotaciones", "Monto segundo remate", "Fecha segundo remate", "Monto tercer remate",
        "Fecha tercer remate", "Expediente", "Juzgado", "Beneficiario", "Afectado", "Estado");
    public static final int[] VEH_WIDTHS = {12, 16, 14, 9, 16, 14, 22, 24, 12, 42, 16, 14, 16, 14, 20, 42, 34, 34, 14};

    /** The person the auction is brought against (the defendant, "... contra X"). */
    public static final String AFECTADO = "Afectado";
    /** Free column for the user; empty when written by the app (never "No indicado"). */
    public static final String ESTADO = "Estado";

    /** Columns shown with the #,##0.00 number format. */
    public static final Set<String> NUM_COLS = Set.of("Monto primer remate", "Medida de la finca",
        "Monto segundo remate", "Monto tercer remate");

    /** Columns holding dd/mm/yyyy dates (sorted and range-filtered as dates). */
    public static final Set<String> FECHA_COLS = Set.of("Fecha primer remate", "Fecha segundo remate",
        "Fecha tercer remate");

    private static final DecimalFormat NUM_FORMAT =
        new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));

    /** Display format of the numeric columns, as Excel's {@code [$-409]#,##0.00}. */
    public static String formatearNumero(Number n) {
        synchronized (NUM_FORMAT) { // DecimalFormat is not thread-safe
            return NUM_FORMAT.format(n);
        }
    }

    /** Text shown in a cell: numbers formatted, null as empty. */
    public static String textoCelda(Object v) {
        return v == null ? "" : v instanceof Number n ? formatearNumero(n) : v.toString();
    }

    /** {@code _v}: missing values become "No indicado" (0 is kept). */
    private static Object v(Object x) {
        return x == null || "".equals(x) ? NO_INDICADO : x;
    }

    /** {@code fila_terreno}. */
    public static Object[] filaTerreno(Terreno t, String boletinNum) {
        DatosRemate d = t.datos();
        String matricula = t.matriculaNumero() == null || t.matriculaNumero().isEmpty()
            ? NO_INDICADO : t.matriculaNumero();
        if (t.derechosFormateados() != null && !t.derechosFormateados().isEmpty()) {
            matricula = matricula + " (der. " + t.derechosFormateados() + ")";
        }
        return new Object[] {boletinNum, d.referencia(), v(d.moneda()), v(d.montoBase()), v(d.fechaPrimerRemate()),
            v(t.medida()), v(t.distrito()), v(t.canton()), v(t.provincia()), v(t.descripcion()), v(d.gravamenes()),
            v(d.montoSegundoRemate()), v(d.fechaSegundoRemate()), v(d.montoTercerRemate()),
            v(d.fechaTercerRemate()), matricula, v(t.plano()), v(d.expediente()), v(d.juzgado()),
            v(d.beneficiario()), v(d.demandado()), null};   // Afectado, Estado (empty)
    }

    /** {@code fila_vehiculo}. */
    public static Object[] filaVehiculo(Vehiculo veh, String boletinNum) {
        DatosRemate d = veh.datos();
        return new Object[] {boletinNum, d.referencia(), v(veh.placa()), v(d.moneda()), v(d.montoBase()),
            v(d.fechaPrimerRemate()), v(veh.marca()), v(veh.estilo()), v(veh.anioFabricacion()), v(d.gravamenes()),
            v(d.montoSegundoRemate()), v(d.fechaSegundoRemate()), v(d.montoTercerRemate()),
            v(d.fechaTercerRemate()), v(d.expediente()), v(d.juzgado()), v(d.beneficiario()),
            v(d.demandado()), null};   // Afectado, Estado (empty)
    }
}
