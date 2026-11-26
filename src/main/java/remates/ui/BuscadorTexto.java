package remates.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import javax.swing.text.JTextComponent;

/**
 * Search bar for a text area: highlights every match as you type (case and accents ignored), Enter / Shift+Enter
 * (or the ▲ ▼ buttons) move to the next / previous one, which is selected and scrolled into view; "3 de 12".
 */
final class BuscadorTexto extends JPanel {
    /** More highlights than this make the text area slow; the count still includes every match. */
    private static final int MAX_RESALTADOS = 2000;
    private static final Highlighter.HighlightPainter TODAS =
        new DefaultHighlighter.DefaultHighlightPainter(new Color(0xFF, 0xF0, 0x80));
    private static final Highlighter.HighlightPainter ACTUAL =
        new DefaultHighlighter.DefaultHighlightPainter(new Color(0xFF, 0xA5, 0x40));

    private final JTextComponent area;
    private final CampoBorrable campo = new CampoBorrable("Buscar en el texto…");
    private final JLabel lblConteo = new JLabel();
    private final JButton btnAnterior = boton("▲", "Coincidencia anterior (Shift+Enter)");
    private final JButton btnSiguiente = boton("▼", "Siguiente coincidencia (Enter)");
    private final List<Object> resaltados = new ArrayList<>();
    private List<Integer> posiciones = List.of();
    private int largo;
    private int actual = -1;
    private Object resaltadoActual;
    private boolean pendiente;

    BuscadorTexto(JTextComponent area) {
        super(new FlowLayout(FlowLayout.LEFT, 2, 0));
        this.area = area;
        setOpaque(false);
        campo.setPreferredSize(new Dimension(220, campo.getPreferredSize().height));
        campo.setToolTipText("Busca en el texto del edicto (sin distinguir mayúsculas ni tildes). "
            + "Enter: siguiente, Shift+Enter: anterior, Esc: borrar");
        lblConteo.setPreferredSize(new Dimension(90, lblConteo.getPreferredSize().height));
        add(new JLabel("Buscar:"));
        add(campo);
        add(btnAnterior);
        add(btnSiguiente);
        add(lblConteo);

        campo.alCambiar(() -> buscar(true));
        CampoBorrable.alCambiar(area, this::textoCambio);
        btnSiguiente.addActionListener(e -> mover(1));
        btnAnterior.addActionListener(e -> mover(-1));
        JTextComponent c = campo.campo();
        c.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "siguiente");
        c.getInputMap(JComponent.WHEN_FOCUSED)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "anterior");
        c.getActionMap().put("siguiente", accion(() -> mover(1)));
        c.getActionMap().put("anterior", accion(() -> mover(-1)));
        actualizarConteo();
    }

    /** Puts the cursor in the search box, with its text selected. */
    void enfocar() {
        campo.enfocar();
        campo.campo().selectAll();
    }

    String termino() {
        return campo.texto();
    }

    int coincidencias() {
        return posiciones.size();
    }

    /** Index (0-based) of the selected match, or -1. */
    int actual() {
        return actual;
    }

    /** The edict text changed (typed, pasted, loaded): search again once, after the change. */
    private void textoCambio() {
        if (!pendiente) {
            pendiente = true;
            SwingUtilities.invokeLater(() -> {
                pendiente = false;
                buscar(false);
            });
        }
    }

    /** Finds every match; {@code irAPrimera}: select the first one at or after the cursor (when typing a term). */
    private void buscar(boolean irAPrimera) {
        Highlighter h = area.getHighlighter();
        resaltados.forEach(h::removeHighlight);
        resaltados.clear();
        quitarActual();
        String termino = normalizar(campo.texto().strip());
        largo = termino.length();
        actual = -1;
        if (largo == 0) {
            posiciones = List.of();
            actualizarConteo();
            return;
        }
        String texto = normalizar(area.getText());
        List<Integer> encontradas = new ArrayList<>();
        for (int i = texto.indexOf(termino); i >= 0; i = texto.indexOf(termino, i + 1)) {
            encontradas.add(i);
        }
        posiciones = encontradas;
        for (int i = 0; i < Math.min(MAX_RESALTADOS, encontradas.size()); i++) {
            try {
                resaltados.add(h.addHighlight(encontradas.get(i), encontradas.get(i) + largo, TODAS));
            } catch (BadLocationException ignored) {
                // text changed meanwhile; the next change searches again
            }
        }
        if (irAPrimera && !encontradas.isEmpty()) {
            int desde = area.getSelectionStart();
            int k = 0;
            while (k < encontradas.size() && encontradas.get(k) < desde) {
                k++;
            }
            seleccionar(k < encontradas.size() ? k : 0);
        } else {
            actualizarConteo();
        }
    }

    private void mover(int paso) {
        if (posiciones.isEmpty()) {
            return;
        }
        int n = posiciones.size();
        seleccionar(actual < 0 ? (paso > 0 ? 0 : n - 1) : Math.floorMod(actual + paso, n));
    }

    private void seleccionar(int k) {
        quitarActual();
        actual = k;
        int inicio = posiciones.get(k);
        try {
            resaltadoActual = area.getHighlighter().addHighlight(inicio, inicio + largo, ACTUAL);
            java.awt.geom.Rectangle2D r = area.modelToView2D(inicio);
            if (r != null) {
                java.awt.Rectangle vista = r.getBounds();
                vista.grow(0, 3 * vista.height);   // a few lines of context around it
                area.scrollRectToVisible(vista);
            }
        } catch (BadLocationException ignored) {
            // text changed meanwhile
        }
        area.setCaretPosition(inicio + largo);   // the cursor follows (no selection: it would hide the colour)
        actualizarConteo();
    }

    private void quitarActual() {
        if (resaltadoActual != null) {
            area.getHighlighter().removeHighlight(resaltadoActual);
            resaltadoActual = null;
        }
    }

    private void actualizarConteo() {
        boolean hay = !posiciones.isEmpty();
        btnAnterior.setEnabled(hay);
        btnSiguiente.setEnabled(hay);
        if (largo == 0) {
            lblConteo.setText("");
        } else if (!hay) {
            lblConteo.setText("Sin resultados");
        } else {
            lblConteo.setText((actual >= 0 ? (actual + 1) + " de " : "") + posiciones.size());
        }
        campo.marcarInvalido(largo > 0 && !hay);
    }

    /**
     * Lower case without accents, one char per char (so positions match the original text): "Á" → "a", "ñ" → "n".
     */
    static String normalizar(String s) {
        char[] out = new char[s.length()];
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c > 127) {
                String d = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD);
                c = d.charAt(0);
            }
            out[i] = Character.toLowerCase(c);
        }
        return new String(out);
    }

    private static JButton boton(String texto, String tooltip) {
        JButton b = new JButton(texto);
        b.setToolTipText(tooltip);
        b.setMargin(new Insets(1, 5, 1, 5));
        b.setFocusable(false);
        return b;
    }

    private static AbstractAction accion(Runnable r) {
        return new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        };
    }
}
