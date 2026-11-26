package remates.ui;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Build tool: writes the application icon ({@code resources/icon.svg}, rendered by {@link IconoApp}) as a Windows
 * {@code .ico} with several sizes, for {@code Remates.exe}. Usage: {@code java remates.ui.GenerarIcono salida.ico}.
 */
public final class GenerarIcono {
    private GenerarIcono() {
    }

    /** Sizes Windows uses for an .exe (Explorer views, taskbar, Alt+Tab, desktop). */
    private static final int[] TAMANOS = {16, 20, 24, 32, 40, 48, 64, 128, 256};

    public static void main(String[] args) throws IOException {
        Path salida = Path.of(args.length > 0 ? args[0] : "remates.ico");
        Files.write(salida, ico());
        System.out.println("Icono escrito: " + salida.toAbsolutePath());
    }

    /** ICO file with one PNG image per size (format supported since Windows Vista). */
    static byte[] ico() throws IOException {
        List<byte[]> pngs = new ArrayList<>();
        List<Image> imagenes = IconoApp.imagenes(TAMANOS);
        for (Image img : imagenes) {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write((BufferedImage) img, "png", png);
            pngs.add(png.toByteArray());
        }
        int cabecera = 6 + 16 * pngs.size();
        int total = cabecera + pngs.stream().mapToInt(p -> p.length).sum();
        ByteBuffer b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) 0).putShort((short) 1).putShort((short) pngs.size());   // reserved, type = icon, count
        int desplazamiento = cabecera;
        for (int i = 0; i < pngs.size(); i++) {
            int t = TAMANOS[i];
            b.put((byte) (t >= 256 ? 0 : t)).put((byte) (t >= 256 ? 0 : t));       // 0 means 256
            b.put((byte) 0).put((byte) 0);                                           // no palette, reserved
            b.putShort((short) 1).putShort((short) 32);                              // planes, bits per pixel
            b.putInt(pngs.get(i).length).putInt(desplazamiento);
            desplazamiento += pngs.get(i).length;
        }
        pngs.forEach(b::put);
        return b.array();
    }
}
