package remates.model;

/**
 * Data common to every auction (terreno or vehículo) read from a Boletín Judicial edict.
 * Fields that could not be found are {@code null} or empty.
 *
 * @param referencia       "Referencia N°" of the publication
 * @param baseTexto        raw text of the base amount, as written in the edict
 * @param moneda           currency symbol (₡ or $)
 * @param montoBase        base amount of the first auction
 * @param fechaPrimerRemate dd/mm/yyyy
 */
public record DatosRemate(
    String referencia,
    String baseTexto,
    String moneda,
    Double montoBase,
    String fechaPrimerRemate,
    String gravamenes,
    Double montoSegundoRemate,
    String fechaSegundoRemate,
    Double montoTercerRemate,
    String fechaTercerRemate,
    String expediente,
    String juzgado,
    String beneficiario,
    String demandado) {
}
