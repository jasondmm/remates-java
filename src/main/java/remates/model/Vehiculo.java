package remates.model;

/** A vehicle auction. */
public record Vehiculo(
    DatosRemate datos,
    String placa,
    String marca,
    String estilo,
    String anioFabricacion) {
}
