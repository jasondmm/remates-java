package remates.ui;

import java.awt.AWTEvent;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;

import javax.swing.JComponent;
import javax.swing.JLayer;
import javax.swing.Timer;
import javax.swing.plaf.LayerUI;

/**
 * Loading overlay for a panel (wrap it with {@link #envolver}): while {@link #mostrar} is on, the panel is dimmed,
 * mouse and keyboard input to it is blocked, and a card in the middle shows a spinner, a message and the seconds
 * elapsed.
 */
final class IndicadorCarga extends LayerUI<JComponent> {
    private static final int TAMANO_SPINNER = 36;
    private static final int SEGMENTOS = 12;

    private final Timer timer = new Timer(80, e -> avanzar());
    private JLayer<JComponent> capa;
    private boolean activo;
    private String mensaje = "";
    private long inicio;
    private int paso;

    /** Wraps {@code contenido}: add the returned component to the layout instead of it. */
    JLayer<JComponent> envolver(JComponent contenido) {
        capa = new JLayer<>(contenido, this);
        capa.setLayerEventMask(AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK
            | AWTEvent.MOUSE_WHEEL_EVENT_MASK | AWTEvent.KEY_EVENT_MASK);
        return capa;
    }

    /** Shows the overlay with {@code mensaje} (or just changes the message when it is already shown). */
    void mostrar(String mensaje) {
        this.mensaje = mensaje;
        if (!activo) {
            activo = true;
            inicio = System.currentTimeMillis();
            paso = 0;
            timer.start();
        }
        repintar();
    }

    void ocultar() {
        activo = false;
        timer.stop();
        repintar();
    }

    boolean activo() {
        return activo;
    }

    String mensaje() {
        return mensaje;
    }

    private void avanzar() {
        paso = (paso + 1) % SEGMENTOS;
        repintar();
    }

    private void repintar() {
        if (capa != null) {
            capa.repaint();
        }
    }

    @Override
    protected void processMouseEvent(MouseEvent e, JLayer<? extends JComponent> l) {
        bloquear(e);
    }

    @Override
    protected void processMouseMotionEvent(MouseEvent e, JLayer<? extends JComponent> l) {
        bloquear(e);
    }

    @Override
    protected void processMouseWheelEvent(java.awt.event.MouseWheelEvent e, JLayer<? extends JComponent> l) {
        bloquear(e);
    }

    @Override
    protected void processKeyEvent(KeyEvent e, JLayer<? extends JComponent> l) {
        bloquear(e);
    }

    private void bloquear(InputEvent e) {
        if (activo) {
            e.consume();
        }
    }

    @Override
    public void paint(Graphics g, JComponent c) {
        super.paint(g, c);
        if (!activo) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = c.getWidth();
            int h = c.getHeight();
            g2.setComposite(AlphaComposite.SrcOver.derive(0.55f));
            g2.setColor(Color.WHITE);
            g2.fillRect(0, 0, w, h);
            g2.setComposite(AlphaComposite.SrcOver);

            Font fuente = c.getFont().deriveFont(Font.PLAIN, c.getFont().getSize2D() + 1f);
            g2.setFont(fuente);
            FontMetrics fm = g2.getFontMetrics();
            String segundos = (System.currentTimeMillis() - inicio) / 1000 + " s";
            int anchoTexto = Math.max(fm.stringWidth(mensaje), fm.stringWidth(segundos));
            int anchoCaja = Math.min(w - 20, Math.max(260, anchoTexto + 48));
            int altoCaja = TAMANO_SPINNER + 2 * fm.getHeight() + 44;
            int x = (w - anchoCaja) / 2;
            int y = (h - altoCaja) / 2;
            RoundRectangle2D caja = new RoundRectangle2D.Float(x, y, anchoCaja, altoCaja, 16, 16);
            g2.setColor(new Color(0, 0, 0, 40));
            g2.fill(new RoundRectangle2D.Float(x + 2, y + 3, anchoCaja, altoCaja, 16, 16));
            g2.setColor(Color.WHITE);
            g2.fill(caja);
            g2.setColor(new Color(0xC8, 0xC8, 0xC8));
            g2.draw(caja);

            // spinner: 12 strokes, the "current" one darkest
            int cx = w / 2;
            int cy = y + 16 + TAMANO_SPINNER / 2;
            g2.setStroke(new BasicStroke(3.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int i = 0; i < SEGMENTOS; i++) {
                int edad = Math.floorMod(paso - i, SEGMENTOS);
                int alfa = 230 - edad * 16;
                g2.setColor(new Color(0x1A, 0x73, 0xE8, Math.max(35, alfa)));
                double ang = 2 * Math.PI * i / SEGMENTOS - Math.PI / 2;
                double r1 = TAMANO_SPINNER * 0.24;
                double r2 = TAMANO_SPINNER * 0.48;
                g2.drawLine((int) Math.round(cx + r1 * Math.cos(ang)), (int) Math.round(cy + r1 * Math.sin(ang)),
                    (int) Math.round(cx + r2 * Math.cos(ang)), (int) Math.round(cy + r2 * Math.sin(ang)));
            }

            int yTexto = y + 16 + TAMANO_SPINNER + 14 + fm.getAscent();
            g2.setColor(new Color(0x20, 0x20, 0x20));
            String m = recortar(mensaje, fm, anchoCaja - 24);
            g2.drawString(m, cx - fm.stringWidth(m) / 2, yTexto);
            g2.setColor(new Color(0x70, 0x70, 0x70));
            g2.drawString(segundos, cx - fm.stringWidth(segundos) / 2, yTexto + fm.getHeight());
        } finally {
            g2.dispose();
        }
    }

    private static String recortar(String s, FontMetrics fm, int ancho) {
        if (fm.stringWidth(s) <= ancho) {
            return s;
        }
        String r = s;
        while (r.length() > 1 && fm.stringWidth(r + "…") > ancho) {
            r = r.substring(0, r.length() - 1);
        }
        return r + "…";
    }
}
