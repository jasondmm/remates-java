package remates.model;

/**
 * A land (finca) auction.
 *
 * @param matricula           registration number as written in the edict
 * @param matriculaNumero     registration number with words converted to digits
 * @param derechos            rights ("derechos") as written
 * @param derechosFormateados rights as codes, e.g. "001, 002"
 * @param medidaTexto         area as written
 * @param medida              area in square meters
 */
public record Terreno(
    DatosRemate datos,
    String matricula,
    String matriculaNumero,
    String derechos,
    String derechosFormateados,
    String medidaTexto,
    Double medida,
    String descripcion,
    String distrito,
    String canton,
    String provincia,
    String plano) {
}
