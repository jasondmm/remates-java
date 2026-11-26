package remates.model;

import java.util.List;

/**
 * Auctions read from one Boletín Judicial.
 *
 * @param numeroBoletin number detected in the first lines of the text, or {@code null}
 */
public record ResultadoBoletin(String numeroBoletin, List<Terreno> terrenos, List<Vehiculo> vehiculos) {
}
