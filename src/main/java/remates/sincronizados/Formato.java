package remates.sincronizados;

import java.awt.Color;

import com.google.api.services.sheets.v4.model.CellFormat;
import com.google.api.services.sheets.v4.model.TextFormat;

/**
 * Basic formatting of one cell, as kept in Google Sheets: background, font color, font size, bold, italic,
 * underline, strikethrough and horizontal alignment. A null field means "not set" (the file's default).
 *
 * @param alineacion "LEFT", "CENTER", "RIGHT" or null
 */
public record Formato(Color fondo, Color fuente, Integer tamano, Boolean negrita, Boolean cursiva,
                      Boolean subrayado, Boolean tachado, String alineacion) {

    public static final Formato VACIO = new Formato(null, null, null, null, null, null, null, null);

    /**
     * Field mask for writing exactly these properties (and nothing else of the cell's format, so font family,
     * borders or number formats set in the file are kept).
     */
    public static final String CAMPOS = "userEnteredFormat.backgroundColor,"
        + "userEnteredFormat.textFormat.foregroundColor,userEnteredFormat.textFormat.fontSize,"
        + "userEnteredFormat.textFormat.bold,userEnteredFormat.textFormat.italic,"
        + "userEnteredFormat.textFormat.underline,userEnteredFormat.textFormat.strikethrough,"
        + "userEnteredFormat.horizontalAlignment";

    public Formato conFondo(Color c) {
        return new Formato(c, fuente, tamano, negrita, cursiva, subrayado, tachado, alineacion);
    }

    public Formato conFuente(Color c) {
        return new Formato(fondo, c, tamano, negrita, cursiva, subrayado, tachado, alineacion);
    }

    public Formato conTamano(Integer t) {
        return new Formato(fondo, fuente, t, negrita, cursiva, subrayado, tachado, alineacion);
    }

    public Formato conNegrita(Boolean b) {
        return new Formato(fondo, fuente, tamano, b, cursiva, subrayado, tachado, alineacion);
    }

    public Formato conCursiva(Boolean b) {
        return new Formato(fondo, fuente, tamano, negrita, b, subrayado, tachado, alineacion);
    }

    public Formato conSubrayado(Boolean b) {
        return new Formato(fondo, fuente, tamano, negrita, cursiva, b, tachado, alineacion);
    }

    public Formato conTachado(Boolean b) {
        return new Formato(fondo, fuente, tamano, negrita, cursiva, subrayado, b, alineacion);
    }

    public Formato conAlineacion(String a) {
        return new Formato(fondo, fuente, tamano, negrita, cursiva, subrayado, tachado, a);
    }

    public boolean esNegrita() {
        return Boolean.TRUE.equals(negrita);
    }

    public boolean esCursiva() {
        return Boolean.TRUE.equals(cursiva);
    }

    public boolean esSubrayado() {
        return Boolean.TRUE.equals(subrayado);
    }

    public boolean esTachado() {
        return Boolean.TRUE.equals(tachado);
    }

    // ------------------------------------------------------------------ Google Sheets conversion

    /** From a cell's {@code userEnteredFormat} (null → {@link #VACIO}). */
    public static Formato desde(CellFormat f) {
        if (f == null) {
            return VACIO;
        }
        TextFormat t = f.getTextFormat();
        return new Formato(color(f.getBackgroundColor()),
            t == null ? null : color(t.getForegroundColor()),
            t == null ? null : t.getFontSize(),
            t == null ? null : t.getBold(),
            t == null ? null : t.getItalic(),
            t == null ? null : t.getUnderline(),
            t == null ? null : t.getStrikethrough(),
            f.getHorizontalAlignment());
    }

    /** To a {@code CellFormat} carrying only the properties of {@link #CAMPOS} (nulls clear them). */
    public CellFormat aCellFormat() {
        return new CellFormat()
            .setBackgroundColor(color(fondo))
            .setTextFormat(new TextFormat()
                .setForegroundColor(color(fuente))
                .setFontSize(tamano)
                .setBold(negrita)
                .setItalic(cursiva)
                .setUnderline(subrayado)
                .setStrikethrough(tachado))
            .setHorizontalAlignment(alineacion);
    }

    /** Sheets colors are 0..1 floats; a missing component means 0. */
    static Color color(com.google.api.services.sheets.v4.model.Color c) {
        if (c == null) {
            return null;
        }
        return new Color(comp(c.getRed()), comp(c.getGreen()), comp(c.getBlue()));
    }

    static com.google.api.services.sheets.v4.model.Color color(Color c) {
        if (c == null) {
            return null;
        }
        return new com.google.api.services.sheets.v4.model.Color()
            .setRed(c.getRed() / 255f).setGreen(c.getGreen() / 255f).setBlue(c.getBlue() / 255f);
    }

    private static int comp(Float f) {
        return f == null ? 0 : Math.round(Math.max(0f, Math.min(1f, f)) * 255);
    }
}
