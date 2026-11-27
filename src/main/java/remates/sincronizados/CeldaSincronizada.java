package remates.sincronizados;

/**
 * One cell of the synchronized sheet: its value (Double for amounts, String otherwise, "" when empty),
 * its formatting, and whether its last change could not be saved to Google Sheets yet.
 */
public final class CeldaSincronizada {
    private Object valor;
    private Formato formato;
    private boolean sinGuardar;

    public CeldaSincronizada(Object valor, Formato formato) {
        this.valor = valor;
        this.formato = formato == null ? Formato.VACIO : formato;
    }

    public Object getValor() {
        return valor;
    }

    public void setValor(Object valor) {
        this.valor = valor;
    }

    public Formato getFormato() {
        return formato;
    }

    public void setFormato(Formato formato) {
        this.formato = formato == null ? Formato.VACIO : formato;
    }

    /** True while a change of this cell failed to save (shown in light red). */
    public boolean isSinGuardar() {
        return sinGuardar;
    }

    public void setSinGuardar(boolean sinGuardar) {
        this.sinGuardar = sinGuardar;
    }
}
