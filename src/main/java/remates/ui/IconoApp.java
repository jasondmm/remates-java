package remates.ui;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Taskbar;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JFrame;

/**
 * Application icon, rendered from {@code resources/icon.svg}.
 *
 * <p>Java has no SVG support, so this draws the SVG's {@code <path>} elements directly (path data commands
 * M L H V C S Q T Z, absolute and relative; {@code fill} and {@code fill-rule}) at every size the OS may ask
 * for. Enough for simple icons such as this one; gradients, strokes, transforms and arcs are not supported.
 *
 * <p>The SVG is looked up on the classpath ({@code /icon.svg}, for a packaged jar) and then at
 * {@code resources/icon.svg} relative to the working directory (IntelliJ / command line from the project root).
 */
public final class IconoApp {
    private IconoApp() {
    }

    private static final int[] SIZES = {16, 20, 24, 32, 40, 48, 64, 128, 256};

    /** Sets the window (title bar / taskbar) icon; does nothing if the SVG cannot be read. */
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

    /** 16 px icon for child (internal) window title bars, or null if the SVG cannot be read. */
    public static Icon iconoPequeno() {
        try {
            return new ImageIcon(render(parse(leerSvg()), 16));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The icon rendered at the usual window/taskbar sizes. */
    public static List<Image> imagenes() throws IOException {
        Svg svg = parse(leerSvg());
        List<Image> images = new ArrayList<>();
        for (int size : SIZES) {
            images.add(render(svg, size));
        }
        return images;
    }

    private static String leerSvg() throws IOException {
        try (InputStream in = IconoApp.class.getResourceAsStream("/icon.svg")) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        Path file = Path.of("resources", "icon.svg");
        if (Files.exists(file)) {
            return Files.readString(file, StandardCharsets.UTF_8);
        }
        throw new IOException("icon.svg no está en el classpath ni en " + file.toAbsolutePath());
    }

    // ------------------------------------------------------------------ rendering

    private record Forma(Path2D path, Color fill) {
    }

    private record Svg(double minX, double minY, double width, double height, List<Forma> formas) {
    }

    static BufferedImage render(Svg svg, int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        double scale = size / Math.max(svg.width(), svg.height());
        AffineTransform t = new AffineTransform();
        t.translate((size - svg.width() * scale) / 2, (size - svg.height() * scale) / 2); // center, keep ratio
        t.scale(scale, scale);
        t.translate(-svg.minX(), -svg.minY());
        for (Forma f : svg.formas()) {
            g.setColor(f.fill());
            g.fill(t.createTransformedShape(f.path()));
        }
        g.dispose();
        return img;
    }

    // ------------------------------------------------------------------ SVG parsing

    private static final Pattern VIEWBOX = Pattern.compile("viewBox\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern PATH_ELEMENT = Pattern.compile("<path\\b([^>]*)>");
    private static final Pattern NUMBER = Pattern.compile("[+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?");
    private static final Pattern TOKEN = Pattern.compile("[MmLlHhVvCcSsQqTtZzAa]|" + NUMBER.pattern());

    private static String atributo(String attrs, String name) {
        Matcher m = Pattern.compile("(?<![\\w-])" + name + "\\s*=\\s*\"([^\"]*)\"").matcher(attrs);
        return m.find() ? m.group(1) : null;
    }

    static Svg parse(String text) {
        Matcher vb = VIEWBOX.matcher(text);
        if (!vb.find()) {
            throw new IllegalArgumentException("icon.svg no tiene viewBox");
        }
        String[] v = vb.group(1).trim().split("[\\s,]+");
        List<Forma> formas = new ArrayList<>();
        Matcher pm = PATH_ELEMENT.matcher(text);
        while (pm.find()) {
            String attrs = pm.group(1);
            String d = atributo(attrs, "d");
            String fill = atributo(attrs, "fill");
            if (d == null || "none".equals(fill)) {
                continue;
            }
            Path2D path = pathData(d);
            path.setWindingRule("evenodd".equals(atributo(attrs, "fill-rule"))
                ? Path2D.WIND_EVEN_ODD : Path2D.WIND_NON_ZERO);
            formas.add(new Forma(path, color(fill)));
        }
        if (formas.isEmpty()) {
            throw new IllegalArgumentException("icon.svg no tiene elementos <path>");
        }
        return new Svg(Double.parseDouble(v[0]), Double.parseDouble(v[1]), Double.parseDouble(v[2]),
            Double.parseDouble(v[3]), formas);
    }

    private static Color color(String fill) {
        if (fill == null || fill.equalsIgnoreCase("black")) {
            return Color.BLACK; // SVG default fill
        }
        if (fill.equalsIgnoreCase("white")) {
            return Color.WHITE;
        }
        if (fill.matches("#[0-9a-fA-F]{3}")) {
            fill = "#" + fill.charAt(1) + fill.charAt(1) + fill.charAt(2) + fill.charAt(2) + fill.charAt(3)
                + fill.charAt(3);
        }
        return Color.decode(fill);
    }

    /** SVG path data ({@code d} attribute) to a Java path. */
    static Path2D pathData(String d) {
        List<String> tokens = new ArrayList<>();
        Matcher m = TOKEN.matcher(d);
        while (m.find()) {
            tokens.add(m.group());
        }
        Path2D.Double p = new Path2D.Double();
        double x = 0;
        double y = 0;
        double startX = 0;
        double startY = 0;
        double ctrlX = 0;      // last control point, for S/T reflection
        double ctrlY = 0;
        char prev = ' ';
        char cmd = ' ';
        int i = 0;
        while (i < tokens.size()) {
            String tok = tokens.get(i);
            if (Character.isLetter(tok.charAt(0))) {
                cmd = tok.charAt(0);
                i++;
                if (cmd == 'Z' || cmd == 'z') {
                    p.closePath();
                    x = startX;
                    y = startY;
                    prev = cmd;
                    continue;
                }
            } else if (cmd == ' ') {
                throw new IllegalArgumentException("path data debe empezar con un comando: " + tok);
            }
            boolean rel = Character.isLowerCase(cmd);
            double ox = rel ? x : 0;
            double oy = rel ? y : 0;
            switch (Character.toUpperCase(cmd)) {
                case 'M' -> {
                    x = ox + num(tokens, i++);
                    y = oy + num(tokens, i++);
                    p.moveTo(x, y);
                    startX = x;
                    startY = y;
                    cmd = rel ? 'l' : 'L'; // extra coordinate pairs are implicit line-to
                }
                case 'L' -> {
                    x = ox + num(tokens, i++);
                    y = oy + num(tokens, i++);
                    p.lineTo(x, y);
                }
                case 'H' -> {
                    x = ox + num(tokens, i++);
                    p.lineTo(x, y);
                }
                case 'V' -> {
                    y = oy + num(tokens, i++);
                    p.lineTo(x, y);
                }
                case 'C' -> {
                    double x1 = ox + num(tokens, i++);
                    double y1 = oy + num(tokens, i++);
                    ctrlX = ox + num(tokens, i++);
                    ctrlY = oy + num(tokens, i++);
                    x = ox + num(tokens, i++);
                    y = oy + num(tokens, i++);
                    p.curveTo(x1, y1, ctrlX, ctrlY, x, y);
                }
                case 'S' -> {
                    boolean afterCubic = "CcSs".indexOf(prev) >= 0;
                    double x1 = afterCubic ? 2 * x - ctrlX : x;
                    double y1 = afterCubic ? 2 * y - ctrlY : y;
                    ctrlX = ox + num(tokens, i++);
                    ctrlY = oy + num(tokens, i++);
                    x = ox + num(tokens, i++);
                    y = oy + num(tokens, i++);
                    p.curveTo(x1, y1, ctrlX, ctrlY, x, y);
                }
                case 'Q' -> {
                    ctrlX = ox + num(tokens, i++);
                    ctrlY = oy + num(tokens, i++);
                    x = ox + num(tokens, i++);
                    y = oy + num(tokens, i++);
                    p.quadTo(ctrlX, ctrlY, x, y);
                }
                case 'T' -> {
                    boolean afterQuad = "QqTt".indexOf(prev) >= 0;
                    ctrlX = afterQuad ? 2 * x - ctrlX : x;
                    ctrlY = afterQuad ? 2 * y - ctrlY : y;
                    x = ox + num(tokens, i++);
                    y = oy + num(tokens, i++);
                    p.quadTo(ctrlX, ctrlY, x, y);
                }
                default -> throw new IllegalArgumentException("Comando de path no soportado: " + cmd);
            }
            prev = cmd;
        }
        return p;
    }

    private static double num(List<String> tokens, int i) {
        return Double.parseDouble(tokens.get(i));
    }
}
