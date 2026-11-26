package remates.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.KeyStroke;

/**
 * Slider with two handles (minimum and maximum) over {@code pasos} discrete positions 0..pasos-1.
 * The owner maps positions to values. Mouse: drag a handle or click the track to move the nearest one.
 * Keyboard: ←/→ move the last used handle one step, PgUp/PgDn ten percent, Home/End to the ends.
 */
final class SelectorRango extends JComponent {

    private static final int RADIO = 7;
    private static final Color PISTA = new Color(0xC8, 0xC8, 0xC8);
    private static final Color ACENTO = new Color(0x1F, 0x4E, 0x78);

    private final List<Runnable> oyentes = new ArrayList<>();
    private int pasos;
    private int inferior;
    private int superior;
    private int arrastrando = -1;   // 0 = lower handle, 1 = upper handle
    private int ultimo;             // handle moved by the keyboard

    SelectorRango() {
        setFocusable(true);
        setOpaque(false);
        MouseAdapter raton = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (!isEnabled()) {
                    return;
                }
                requestFocusInWindow();
                int i = indice(e.getX());
                arrastrando = manijaCercana(e.getX(), i);
                mover(arrastrando, i);
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (arrastrando >= 0) {
                    mover(arrastrando, indice(e.getX()));
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                arrastrando = -1;
            }
        };
        addMouseListener(raton);
        addMouseMotionListener(raton);
        tecla(KeyEvent.VK_LEFT, () -> mover(ultimo, actual(ultimo) - 1));
        tecla(KeyEvent.VK_RIGHT, () -> mover(ultimo, actual(ultimo) + 1));
        tecla(KeyEvent.VK_PAGE_DOWN, () -> mover(ultimo, actual(ultimo) - Math.max(1, pasos / 10)));
        tecla(KeyEvent.VK_PAGE_UP, () -> mover(ultimo, actual(ultimo) + Math.max(1, pasos / 10)));
        tecla(KeyEvent.VK_HOME, () -> mover(ultimo, 0));
        tecla(KeyEvent.VK_END, () -> mover(ultimo, pasos - 1));
        setPasos(0);
    }

    /** Number of positions; both handles go back to the ends. */
    void setPasos(int n) {
        pasos = Math.max(0, n);
        inferior = 0;
        superior = Math.max(0, pasos - 1);
        setEnabled(pasos > 1);
        repaint();
    }

    int getPasos() {
        return pasos;
    }

    int getInferior() {
        return inferior;
    }

    int getSuperior() {
        return superior;
    }

    /** Moves both handles without notifying listeners (used when the owner syncs from typed values). */
    void setRango(int inf, int sup) {
        int max = Math.max(0, pasos - 1);
        inferior = Math.min(Math.max(inf, 0), max);
        superior = Math.min(Math.max(sup, inferior), max);
        repaint();
    }

    /** Called when the user moves a handle. */
    void alCambiar(Runnable r) {
        oyentes.add(r);
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(170, 2 * RADIO + 8);
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(80, 2 * RADIO + 8);
    }

    // ------------------------------------------------------------------ geometry and movement

    private int ancho() {
        return Math.max(1, getWidth() - 2 * RADIO - 2);
    }

    private int x(int idx) {
        int base = RADIO + 1;
        return pasos <= 1 ? base : base + (int) Math.round((double) idx * ancho() / (pasos - 1));
    }

    private int indice(int px) {
        if (pasos <= 1) {
            return 0;
        }
        int i = (int) Math.round((double) (px - RADIO - 1) * (pasos - 1) / ancho());
        return Math.min(Math.max(i, 0), pasos - 1);
    }

    private int manijaCercana(int px, int i) {
        if (inferior == superior) {
            return i < inferior ? 0 : i > superior ? 1 : ultimo;
        }
        return Math.abs(px - x(inferior)) <= Math.abs(px - x(superior)) ? 0 : 1;
    }

    private int actual(int manija) {
        return manija == 0 ? inferior : superior;
    }

    private void mover(int manija, int i) {
        if (!isEnabled()) {
            return;
        }
        ultimo = manija;
        boolean cambio;
        if (manija == 0) {
            int nuevo = Math.min(Math.max(i, 0), superior);
            cambio = nuevo != inferior;
            inferior = nuevo;
        } else {
            int nuevo = Math.max(Math.min(i, pasos - 1), inferior);
            cambio = nuevo != superior;
            superior = nuevo;
        }
        repaint();
        if (cambio) {
            oyentes.forEach(Runnable::run);
        }
    }

    private void tecla(int codigo, Runnable accion) {
        String nombre = "rango-" + codigo;
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(codigo, 0), nombre);
        getActionMap().put(nombre, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                accion.run();
            }
        });
    }

    // ------------------------------------------------------------------ painting

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int cy = getHeight() / 2;
        int x0 = RADIO + 1;
        int x1 = getWidth() - RADIO - 1;
        g2.setColor(PISTA);
        g2.fillRoundRect(x0, cy - 2, x1 - x0, 4, 4, 4);
        if (pasos > 1) {
            Color color = isEnabled() ? ACENTO : PISTA;
            g2.setColor(color);
            g2.fillRoundRect(x(inferior), cy - 2, x(superior) - x(inferior), 4, 4, 4);
            manija(g2, x(inferior), cy, color, hasFocus() && ultimo == 0);
            manija(g2, x(superior), cy, color, hasFocus() && ultimo == 1);
        }
        g2.dispose();
    }

    private static void manija(Graphics2D g2, int cx, int cy, Color borde, boolean foco) {
        g2.setColor(Color.WHITE);
        g2.fillOval(cx - RADIO, cy - RADIO, 2 * RADIO, 2 * RADIO);
        g2.setColor(borde);
        g2.setStroke(new BasicStroke(foco ? 3f : 2f));
        g2.drawOval(cx - RADIO, cy - RADIO, 2 * RADIO, 2 * RADIO);
    }
}
