import javax.swing.SwingUtilities;

import remates.ui.Apariencia;
import remates.ui.Remates;

public class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            Apariencia.aplicarAlIniciar();   // light or dark, as Windows is set
            Remates remates = new Remates();
            remates.setVisible(true);
            remates.abrirBoletin();   // the Boletin form opens as a child window
        });
    }
}
