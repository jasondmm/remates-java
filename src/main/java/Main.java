import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import remates.ui.Remates;

public class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // keep the default look and feel
            }
            Remates remates = new Remates();
            remates.setVisible(true);
            remates.abrirBoletin();   // the Boletin form opens as a child window
        });
    }
}
