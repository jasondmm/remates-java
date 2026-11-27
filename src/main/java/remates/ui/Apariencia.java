package remates.ui;

import java.awt.Color;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.LookAndFeel;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.UIManager;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;

/**
 * Look and feel of the app: FlatLaf light or dark, following the Windows setting (Settings → Personalization →
 * Colors → app mode). Applied on startup and re-checked every few seconds and when the main window is activated, so
 * a change in Windows is picked up without restarting.
 */
public final class Apariencia {
    private Apariencia() {
    }

    /** How often the Windows setting is read. */
    private static final int CADA_MS = 15_000;

    private static boolean oscuro;
    private static Timer vigilante;
    /** Reads whether Windows uses dark apps; replaceable by checks. */
    private static BooleanSupplier leerWindowsOscuro = Apariencia::windowsOscuro;

    /** Installs light or dark as Windows is set and starts following it. */
    public static void aplicarAlIniciar() {
        oscuro = leerWindowsOscuro.getAsBoolean();
        try {
            UIManager.setLookAndFeel(crear(oscuro));
        } catch (Exception e) {
            System.err.println("No se pudo aplicar la apariencia: " + e.getMessage());
        }
        if (vigilante == null) {
            vigilante = new Timer(CADA_MS, e -> revisarWindows());
            vigilante.start();
        }
    }

    private static LookAndFeel crear(boolean oscuro) {
        return oscuro ? new FlatDarkLaf() : new FlatLightLaf();
    }

    /** For checks: replaces the Windows setting reader. */
    static void setLectorWindows(BooleanSupplier lector) {
        leerWindowsOscuro = lector;
    }

    /**
     * Reads the Windows setting off the event thread and, if it changed, switches every open window to light or
     * dark. Called by the timer and when the main window is activated.
     */
    static void revisarWindows() {
        new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() {
                return leerWindowsOscuro.getAsBoolean();
            }

            @Override
            protected void done() {
                try {
                    boolean ahora = get();
                    if (ahora != oscuro) {
                        UIManager.setLookAndFeel(crear(ahora));
                        oscuro = ahora;
                        FlatLaf.updateUI();   // every window, internal frame, dialog and table renderer
                    }
                } catch (Exception ignored) {
                    // keep the current look
                }
            }
        }.execute();
    }

    private static final Pattern VALOR_REG = Pattern.compile("AppsUseLightTheme\\s+REG_DWORD\\s+0x([0-9a-fA-F]+)");

    /**
     * True when Windows is set to dark apps: registry value {@code AppsUseLightTheme} = 0. False when it is light,
     * missing, unreadable or not on Windows.
     */
    static boolean windowsOscuro() {
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            return false;
        }
        try {
            Process p = new ProcessBuilder("reg", "query",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "/v", "AppsUseLightTheme")
                .redirectErrorStream(true).start();
            String salida = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroy();
                return false;
            }
            Matcher m = VALOR_REG.matcher(salida);
            return m.find() && Integer.parseInt(m.group(1), 16) == 0;
        } catch (IOException | RuntimeException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ------------------------------------------------------------------ colors

    /** True while the dark look is in use. */
    public static boolean esOscuro() {
        return FlatLaf.isLafDark();
    }

    /**
     * Text color that reads on {@code fondo}: {@code normal} (the look's text color) unless both are light or both
     * dark, then black or white. Used where a cell or label gets a fixed background (green, red, yellow...).
     */
    public static Color textoSobre(Color fondo, Color normal) {
        double f = luz(fondo);
        double n = luz(normal);
        if (f > 0.6 && n > 0.5) {
            return Color.BLACK;
        }
        if (f < 0.4 && n < 0.5) {
            return Color.WHITE;
        }
        return normal;
    }

    static double luz(Color c) {
        return (0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue()) / 255;
    }
}
