package remates.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;

/** Text field with a placeholder and a built-in "×" button that clears it (Esc clears it too). */
final class CampoBorrable extends JPanel {

    private final JTextField campo;
    private final JButton borrar;
    private boolean invalido;

    CampoBorrable(String placeholder) {
        super(new BorderLayout());
        campo = new JTextField() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (getText().isEmpty()) {
                    pintarPlaceholder(this, g, placeholder);
                }
            }
        };
        borrar = botonBorrar("Borrar este campo", () -> setTexto(""));
        setBorder(UIManager.getBorder("TextField.border"));
        setBackground(campo.getBackground());
        campo.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 2));
        add(campo, BorderLayout.CENTER);
        add(borrar, BorderLayout.EAST);
        escBorra(campo);
        alCambiar(() -> borrar.setEnabled(!campo.getText().isEmpty()));
        borrar.setEnabled(false);
    }

    String texto() {
        return campo.getText();
    }

    void setTexto(String s) {
        campo.setText(s);
    }

    void enfocar() {
        campo.requestFocusInWindow();
    }

    JTextField campo() {
        return campo;
    }

    /** After a light/dark switch: the text field's border and background of the new look (the children update later). */
    @Override
    public void updateUI() {
        super.updateUI();
        if (campo != null) {
            javax.swing.SwingUtilities.invokeLater(() -> {
                setBorder(invalido ? BorderFactory.createLineBorder(new Color(0xC0, 0x30, 0x30), 2)
                    : UIManager.getBorder("TextField.border"));
                setBackground(campo.getBackground());
            });
        }
    }

    /** Red border when the content is not valid (e.g. a range bound that is not a number). */
    void marcarInvalido(boolean invalido) {
        this.invalido = invalido;
        setBorder(invalido ? BorderFactory.createLineBorder(new Color(0xC0, 0x30, 0x30), 2)
            : UIManager.getBorder("TextField.border"));
    }

    void alCambiar(Runnable r) {
        alCambiar(campo, r);
    }

    @Override
    public void setToolTipText(String text) {
        super.setToolTipText(text);
        campo.setToolTipText(text);
    }

    // ------------------------------------------------------------------ shared helpers

    /** Small flat "×" button. */
    static JButton botonBorrar(String tooltip, Runnable accion) {
        JButton b = new JButton("×");
        b.setToolTipText(tooltip);
        b.setFont(b.getFont().deriveFont(Font.BOLD, b.getFont().getSize2D() + 3f));
        b.setMargin(new Insets(0, 6, 0, 6));
        b.setBorderPainted(false);
        b.setContentAreaFilled(false);
        b.setFocusable(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addActionListener(e -> accion.run());
        return b;
    }

    /** Esc clears the field. */
    static void escBorra(JTextComponent c) {
        c.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "borrar");
        c.getActionMap().put("borrar", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                c.setText("");
            }
        });
    }

    /** Runs {@code r} on every change of the text (typing, paste, programmatic set). */
    static void alCambiar(JTextComponent c, Runnable r) {
        c.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                r.run();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                r.run();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                r.run();
            }
        });
    }

    static void pintarPlaceholder(JTextComponent c, Graphics g, String texto) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setColor(new Color(0x80, 0x80, 0x80));
        g2.setFont(c.getFont().deriveFont(Font.ITALIC));
        FontMetrics fm = g2.getFontMetrics();
        Insets in = c.getInsets();
        int y = in.top + (c.getHeight() - in.top - in.bottom - fm.getHeight()) / 2 + fm.getAscent();
        g2.drawString(texto, in.left, y);
        g2.dispose();
    }
}
