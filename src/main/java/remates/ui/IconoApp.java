package remates.ui;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Taskbar;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JFrame;

/**
 * Application icon, read from {@code resources/icon.png} and scaled to each size the OS asks for.
 *
 * <p>The PNG is looked up on the classpath ({@code /icon.png}, for a packaged jar) and then at
 * {@code resources/icon.png} relative to the working directory (IntelliJ / command line from the project root).
 */
public final class IconoApp {
    private IconoApp() {
    }

    private static final int[] SIZES = {16, 20, 24, 32, 40, 48, 64, 128, 256};

    /** Sets the window (title bar / taskbar) icon; does nothing if the icon cannot be read. */
    public static void aplicar(JFrame frame) {
        try {
            List<Image> images = imagenes();
            frame.setIconImages(images);
            if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) {
                Taskbar.getTaskbar().setIconImage(images.get(images.size() - 1)); // macOS dock
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("No se pudo cargar el ícono de la aplicación: " + e.getMessage());
        }
    }

    /** 16 px icon for child (internal) window title bars, or null if the icon cannot be read. */
    public static Icon iconoPequeno() {
        try {
            return new ImageIcon(imagenes(new int[] {16}).get(0));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The icon at the usual window/taskbar sizes. */
    public static List<Image> imagenes() throws IOException {
        return imagenes(SIZES);
    }

    /** The icon at the given sizes (square, transparent background). */
    public static List<Image> imagenes(int[] tamanos) throws IOException {
        BufferedImage origen = leerPng();
        List<Image> images = new ArrayList<>();
        for (int size : tamanos) {
            images.add(escalar(origen, size));
        }
        return images;
    }

    private static BufferedImage leerPng() throws IOException {
        BufferedImage img = null;
        try (InputStream in = IconoApp.class.getResourceAsStream("/icon.png")) {
            if (in != null) {
                img = ImageIO.read(in);
            }
        }
        Path file = Path.of("resources", "icon.png");
        if (img == null && Files.exists(file)) {
            img = ImageIO.read(file.toFile());
        }
        if (img == null) {
            throw new IOException("icon.png no está en el classpath ni en " + file.toAbsolutePath());
        }
        return img;
    }

    /** Scales to size×size keeping the aspect ratio (centered); shrinks in halving steps so small sizes stay smooth. */
    static BufferedImage escalar(BufferedImage src, int size) {
        if (src.getWidth() == size && src.getHeight() == size) {
            return src;
        }
        BufferedImage img = src;
        int lado = Math.max(src.getWidth(), src.getHeight());
        while (lado / 2 >= size) {
            lado /= 2;
            img = dibujar(img, lado);
        }
        return dibujar(img, size);
    }

    private static BufferedImage dibujar(BufferedImage src, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        double scale = (double) size / Math.max(src.getWidth(), src.getHeight());
        int w = (int) Math.round(src.getWidth() * scale);
        int h = (int) Math.round(src.getHeight() * scale);
        g.drawImage(src, (size - w) / 2, (size - h) / 2, w, h, null);
        g.dispose();
        return out;
    }
}
