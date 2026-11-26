package remates.sheets;

import remates.sincronizados.ColaGuardado;

/**
 * Where "Remates sincronizados" loads from and saves to: the user's Google spreadsheet ({@link ClienteSheets}),
 * or a fake one in tests.
 */
public interface FuenteSheets extends ColaGuardado.Destino {

    ClienteSheets.Carga cargar() throws Exception;

    /** Creates the source from the settings (for Google: signs in, opening the browser the first time). */
    @FunctionalInterface
    interface Conector {
        FuenteSheets conectar(ConfiguracionSheets configuracion) throws Exception;
    }
}
