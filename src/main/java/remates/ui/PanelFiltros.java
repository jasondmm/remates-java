package remates.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.Collator;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.RowFilter;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableModel;
import javax.swing.table.TableRowSorter;

/**
 * Collapsible "Búsqueda y filtros" section shown above the result tables (collapsed at start).
 *
 * <ul>
 *   <li>A general search box, always present, that looks in every column.</li>
 *   <li>Any number of column filters (several per column allowed), added with the button or by right-clicking a
 *       column header. Each column filter offers the column's values as suggestions.</li>
 *   <li>Everything is combined with AND and applied while typing or selecting.</li>
 *   <li>Every input has its own "×" clear button; "Restablecer" clears all inputs; "Quitar filtros de columna"
 *       removes every column filter and leaves only the general search.</li>
 * </ul>
 * The same filters apply to every registered table; a column filter is ignored by tables that lack that column.
 */
public class PanelFiltros extends JPanel {

    /** A column offered in the column selector. */
    private record OpcionColumna(String nombre, String etiqueta) {
        @Override
        public String toString() {
            return etiqueta;
        }
    }

    private static final Color ACENTO = new Color(0x1F, 0x4E, 0x78);
    private static final int MAX_FILAS_VISIBLES = 5;
    private static final int MAX_SUGERENCIAS = 500;

    private final Supplier<JTable> tablaActiva;
    private final Runnable alCambiar;
    private final List<JTable> tablas = new ArrayList<>();
    private final List<FiltroColumna> filtros = new ArrayList<>();
    private List<OpcionColumna> columnas = List.of();

    private final JButton encabezado = new JButton();
    private final JLabel resumen = new JLabel();
    private final JPanel contenido = new JPanel(new BorderLayout(0, 6));
    private final CampoBorrable general = new CampoBorrable("Buscar en todas las columnas…");
    private final JPanel filas = new JPanel();
    private final JScrollPane filasScroll = new JScrollPane(filas,
        JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    private final JButton btnAgregar = new JButton("+ Agregar filtro de columna");
    private final JButton btnRestablecer = new JButton("Restablecer");
    private final JButton btnQuitar = new JButton("Quitar filtros de columna");

    private boolean abierto;
    /** True while inputs are changed programmatically, so filtering runs once at the end. */
    private boolean suspendido;

    /**
     * @param tablaActiva table currently shown (default column of a new filter)
     * @param alCambiar   called after the filters were applied (e.g. to update row counts)
     */
    public PanelFiltros(Supplier<JTable> tablaActiva, Runnable alCambiar) {
        super(new BorderLayout());
        this.tablaActiva = tablaActiva;
        this.alCambiar = alCambiar;
        construir();
        setAbierto(false);
        actualizarResumen();
    }

    private void construir() {
        encabezado.setFont(encabezado.getFont().deriveFont(Font.BOLD));
        encabezado.setHorizontalAlignment(SwingConstants.LEFT);
        encabezado.setBorderPainted(false);
        encabezado.setContentAreaFilled(false);
        encabezado.setFocusPainted(false);
        encabezado.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        encabezado.setToolTipText("Mostrar u ocultar la búsqueda y los filtros (Ctrl+F)");
        encabezado.addActionListener(e -> setAbierto(!abierto));
        resumen.setForeground(ACENTO);
        resumen.setFont(resumen.getFont().deriveFont(Font.BOLD));
        resumen.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 10));

        JPanel cabecera = new JPanel(new BorderLayout());
        cabecera.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, new Color(0xC8, 0xC8, 0xC8)));
        cabecera.add(encabezado, BorderLayout.CENTER);
        cabecera.add(resumen, BorderLayout.EAST);
        cabecera.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                setAbierto(!abierto);
            }
        });

        general.setToolTipText(FiltroTexto.AYUDA);
        general.alCambiar(this::aplicar);

        JPanel barra = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(0, 0, 0, 6);
        c.fill = GridBagConstraints.HORIZONTAL;
        barra.add(new JLabel("Buscar:"), c);
        c.weightx = 1;
        barra.add(general, c);
        c.weightx = 0;
        barra.add(btnAgregar, c);
        barra.add(btnRestablecer, c);
        c.insets = new Insets(0, 0, 0, 0);
        barra.add(btnQuitar, c);

        btnAgregar.setToolTipText("Agrega un filtro para una columna específica (también: clic derecho en el "
            + "encabezado de una columna)");
        btnRestablecer.setToolTipText("Borra el texto de la búsqueda y de todos los filtros");
        btnQuitar.setToolTipText("Elimina todos los filtros de columna; deja solo la búsqueda general");
        btnAgregar.addActionListener(e -> agregarFiltro(columnaPorDefecto()));
        btnRestablecer.addActionListener(e -> restablecer());
        btnQuitar.addActionListener(e -> quitarFiltrosDeColumna());

        filas.setLayout(new BoxLayout(filas, BoxLayout.Y_AXIS));
        filasScroll.setBorder(BorderFactory.createEmptyBorder());
        filasScroll.getVerticalScrollBar().setUnitIncrement(16);
        filasScroll.setVisible(false);

        contenido.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));
        contenido.add(barra, BorderLayout.NORTH);
        contenido.add(filasScroll, BorderLayout.CENTER);

        add(cabecera, BorderLayout.NORTH);
        add(contenido, BorderLayout.CENTER);
    }

    // ------------------------------------------------------------------ public API

    /**
     * Adds a table to filter. Its row sorter must be a {@code TableRowSorter}. Right-clicking one of its column
     * headers offers "Filtrar por «columna»".
     */
    public void registrar(JTable tabla) {
        if (!(tabla.getRowSorter() instanceof TableRowSorter<?>)) {
            throw new IllegalArgumentException("La tabla necesita un TableRowSorter");
        }
        tablas.add(tabla);
        recalcularColumnas();
        JTableHeader header = tabla.getTableHeader();
        header.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                menuEncabezado(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                menuEncabezado(e);
            }

            private void menuEncabezado(MouseEvent e) {
                if (!e.isPopupTrigger()) {
                    return;
                }
                int col = header.columnAtPoint(e.getPoint());
                if (col < 0) {
                    return;
                }
                String nombre = tabla.getColumnName(col);
                JPopupMenu menu = new JPopupMenu();
                JMenuItem item = new JMenuItem("Filtrar por «" + nombre + "»");
                item.addActionListener(a -> agregarFiltro(nombre));
                menu.add(item);
                menu.show(header, e.getX(), e.getY());
            }
        });
    }

    /** Call after the table data changed: refreshes value suggestions and re-applies the filters. */
    public void datosActualizados() {
        filtros.forEach(FiltroColumna::actualizarSugerencias);
        aplicar();
    }

    /**
     * Call when the visible tab changes: the filters move to the newly visible table (the other one shows all its
     * rows) and suggestions / slider positions are reloaded from it.
     */
    public void tablaVisibleCambio() {
        datosActualizados();
    }

    /** Opens the section and puts the cursor in the general search (Ctrl+F). */
    public void abrirYEnfocarBusqueda() {
        setAbierto(true);
        general.enfocar();
        general.campo().selectAll();
    }

    /** Opens the section and adds a filter for the given column, with the cursor in its value. */
    public void agregarFiltro(String columna) {
        setAbierto(true);
        FiltroColumna f = new FiltroColumna(columna);
        filtros.add(f);
        reorganizarFilas();
        actualizarResumen();
        SwingUtilities.invokeLater(() -> {
            f.editor().requestFocusInWindow();
            filas.scrollRectToVisible(f.getBounds());
        });
    }

    public void setAbierto(boolean abierto) {
        this.abierto = abierto;
        contenido.setVisible(abierto);
        encabezado.setText((abierto ? "▼" : "▶") + "  Búsqueda y filtros");
        revalidarVentana();
    }

    public boolean isAbierto() {
        return abierto;
    }

    // ------------------------------------------------------------------ actions

    private void restablecer() {
        suspendido = true;
        try {
            general.setTexto("");
            filtros.forEach(FiltroColumna::restablecer);
        } finally {
            suspendido = false;
        }
        aplicar();
    }

    private void quitarFiltrosDeColumna() {
        filtros.clear();
        reorganizarFilas();
        aplicar();
    }

    private void quitar(FiltroColumna f) {
        filtros.remove(f);
        reorganizarFilas();
        aplicar();
    }

    private String columnaPorDefecto() {
        JTable t = tablaActiva.get();
        if (t != null && t.getSelectedColumn() >= 0) {
            return t.getColumnName(t.getSelectedColumn());
        }
        List<String> usadas = filtros.stream().map(FiltroColumna::columna).toList();
        if (t != null) {
            for (int i = 0; i < t.getColumnCount(); i++) {
                if (!usadas.contains(t.getColumnName(i))) {
                    return t.getColumnName(i);
                }
            }
        }
        return columnas.isEmpty() ? null : columnas.get(0).nombre();
    }

    /**
     * Builds the row filter of the visible table from the current inputs; the other tables are not filtered.
     */
    private void aplicar() {
        if (suspendido) {
            return;
        }
        FiltroTexto.Terminos busqueda = FiltroTexto.terminos(general.texto());
        List<String> palabras = busqueda.incluir();
        List<String> excluidas = busqueda.excluir();   // "-palabra": no column may contain it
        List<String> alguna = busqueda.alguno();       // "+a +b": at least one must appear in some column
        Map<FiltroColumna, Predicate<Object>> condiciones = new LinkedHashMap<>();
        for (FiltroColumna f : filtros) {
            Predicate<Object> p = f.condicion();
            if (p != null) {
                condiciones.put(f, p);
            }
        }
        JTable visible = tablaActiva.get();
        filtros.forEach(f -> f.actualizarEtiqueta(aplicaEnVisible(f)));
        for (JTable tabla : tablas) {
            @SuppressWarnings("unchecked")
            TableRowSorter<TableModel> sorter = (TableRowSorter<TableModel>) tabla.getRowSorter();
            if (tabla != visible) {           // filters only apply to the visible tab
                sorter.setRowFilter(null);
                continue;
            }
            TableModel modelo = tabla.getModel();
            List<Integer> indices = new ArrayList<>();
            List<Predicate<Object>> preds = new ArrayList<>();
            condiciones.forEach((f, p) -> {
                int idx = indiceColumna(modelo, f.columna());
                if (idx >= 0) {               // tables without this column ignore the filter
                    indices.add(idx);
                    preds.add(p);
                }
            });
            if (busqueda.vacio() && preds.isEmpty()) {
                sorter.setRowFilter(null);
                continue;
            }
            sorter.setRowFilter(new RowFilter<>() {
                @Override
                public boolean include(Entry<? extends TableModel, ? extends Integer> fila) {
                    for (int i = 0; i < preds.size(); i++) {
                        if (!preds.get(i).test(fila.getValue(indices.get(i)))) {
                            return false;
                        }
                    }
                    for (String palabra : palabras) {
                        boolean encontrada = false;
                        for (int c = 0; c < fila.getValueCount() && !encontrada; c++) {
                            encontrada = FiltroTexto.contiene(fila.getValue(c), palabra);
                        }
                        if (!encontrada) {
                            return false;
                        }
                    }
                    if (!alguna.isEmpty()) {
                        boolean hay = false;
                        for (int c = 0; c < fila.getValueCount() && !hay; c++) {
                            for (String a : alguna) {
                                if (FiltroTexto.contiene(fila.getValue(c), a)) {
                                    hay = true;
                                    break;
                                }
                            }
                        }
                        if (!hay) {
                            return false;
                        }
                    }
                    for (String excluida : excluidas) {
                        for (int c = 0; c < fila.getValueCount(); c++) {
                            if (FiltroTexto.contiene(fila.getValue(c), excluida)) {
                                return false;
                            }
                        }
                    }
                    return true;
                }
            });
        }
        actualizarResumen();
        alCambiar.run();
    }

    /** True when the visible table has the filter's column. */
    private boolean aplicaEnVisible(FiltroColumna f) {
        JTable visible = tablaActiva.get();
        return visible != null && indiceColumna(visible.getModel(), f.columna()) >= 0;
    }

    /** Active filters counted for the visible tab (column filters for columns it lacks don't count). */
    private void actualizarResumen() {
        long activos = (FiltroTexto.terminos(general.texto()).vacio() ? 0 : 1)
            + filtros.stream().filter(f -> f.condicion() != null && aplicaEnVisible(f)).count();
        resumen.setText(activos == 0 ? "" : activos == 1 ? "1 filtro activo" : activos + " filtros activos");
        btnRestablecer.setEnabled(activos > 0);
        btnQuitar.setEnabled(!filtros.isEmpty());
        btnAgregar.setEnabled(!columnas.isEmpty());
    }

    private void reorganizarFilas() {
        filas.removeAll();
        for (FiltroColumna f : filtros) {
            filas.add(f);
        }
        filasScroll.setVisible(!filtros.isEmpty());
        if (!filtros.isEmpty()) {
            int alto = filtros.get(0).getPreferredSize().height;
            int visibles = Math.min(filtros.size(), MAX_FILAS_VISIBLES);
            filasScroll.setPreferredSize(new Dimension(10, visibles * alto + 2));
        }
        actualizarResumen();
        revalidarVentana();
    }

    private void revalidarVentana() {
        revalidate();
        repaint();
        if (getParent() != null) {
            getParent().revalidate();
        }
    }

    // ------------------------------------------------------------------ columns and suggestions

    private void recalcularColumnas() {
        Map<String, List<String>> tablasPorColumna = new LinkedHashMap<>();
        for (JTable t : tablas) {
            for (int i = 0; i < t.getModel().getColumnCount(); i++) {
                tablasPorColumna.computeIfAbsent(t.getModel().getColumnName(i), k -> new ArrayList<>())
                    .add(t.getName() != null ? t.getName() : "tabla " + (tablas.indexOf(t) + 1));
            }
        }
        List<OpcionColumna> l = new ArrayList<>();
        tablasPorColumna.forEach((nombre, en) -> l.add(new OpcionColumna(nombre,
            en.size() == tablas.size() ? nombre : nombre + " (" + String.join(", ", en) + ")")));
        columnas = l;
        actualizarResumen();
    }

    private static int indiceColumna(TableModel modelo, String nombre) {
        for (int i = 0; i < modelo.getColumnCount(); i++) {
            if (modelo.getColumnName(i).equals(nombre)) {
                return i;
            }
        }
        return -1;
    }

    /** The visible table, as a list (empty before any table is registered). */
    private List<JTable> tablasVisibles() {
        JTable visible = tablaActiva.get();
        return visible == null ? List.of() : List.of(visible);
    }

    /** Distinct values of a column in the visible table (none for numeric columns). */
    private List<String> valoresDe(String columna) {
        if (columna == null || ColumnasRemate.NUM_COLS.contains(columna)) {
            return List.of();
        }
        Collator collator = Collator.getInstance(Locale.forLanguageTag("es"));
        TreeSet<String> valores = new TreeSet<>(collator);
        for (JTable t : tablasVisibles()) {
            int idx = indiceColumna(t.getModel(), columna);
            if (idx < 0) {
                continue;
            }
            for (int r = 0; r < t.getModel().getRowCount() && valores.size() < MAX_SUGERENCIAS; r++) {
                String s = ColumnasRemate.textoCelda(t.getModel().getValueAt(r, idx));
                if (!s.isBlank()) {
                    valores.add(s);
                }
            }
        }
        return new ArrayList<>(valores);
    }


    /** Kind of range a column offers: none (text), number, or dd/mm/yyyy date. */
    private enum TipoRango {
        NINGUNO, NUMERO, FECHA;

        static TipoRango de(String columna) {
            if (ColumnasRemate.NUM_COLS.contains(columna)) {
                return NUMERO;
            }
            return ColumnasRemate.FECHA_COLS.contains(columna) ? FECHA : NINGUNO;
        }

        /** Slider value of a cell (amount, or a date as epoch day), or null when it has none. */
        Double valorDe(Object celda) {
            if (this == NUMERO) {
                return celda instanceof Number n ? n.doubleValue() : null;
            }
            LocalDate f = celda == null ? null : FiltroTexto.parseFecha(celda.toString());
            return f == null ? null : (double) f.toEpochDay();
        }

        /** Text of a slider value in the min/max boxes. */
        String formatear(double v) {
            return this == FECHA ? LocalDate.ofEpochDay((long) v).format(FORMATO_FECHA) : ColumnasRemate.formatearNumero(v);
        }

        /** Typed bound to a slider value, or null when it is not a valid number / date. */
        Double parsear(String texto) {
            if (this == FECHA) {
                LocalDate f = FiltroTexto.parseFecha(texto);
                return f == null ? null : (double) f.toEpochDay();
            }
            return FiltroTexto.parseNumero(texto);
        }
    }

    private static final DateTimeFormatter FORMATO_FECHA = DateTimeFormatter.ofPattern("dd/MM/uuuu");

    /** Sorted distinct range values of a column in the visible table (positions of the range slider). */
    private double[] valoresRango(String columna, TipoRango tipo) {
        TreeSet<Double> valores = new TreeSet<>();
        for (JTable t : tablasVisibles()) {
            int idx = indiceColumna(t.getModel(), columna);
            if (idx < 0) {
                continue;
            }
            for (int r = 0; r < t.getModel().getRowCount(); r++) {
                Double v = tipo.valorDe(t.getModel().getValueAt(r, idx));
                if (v != null) {
                    valores.add(v);
                }
            }
        }
        return valores.stream().mapToDouble(Double::doubleValue).toArray();
    }

    // ------------------------------------------------------------------ one column filter row

    /**
     * [column ▼] [contiene] [value ▼ (editable, with suggestions)] [×] [Rango: mín × ━●━━●━ máx ×] [Quitar]
     * The range part is shown for numeric and date columns. Its boxes start with the column's smallest and
     * largest values; a bound at (or beyond) the column's extreme means "no limit", so the range only filters
     * once it is narrowed.
     */
    private final class FiltroColumna extends JPanel {
        private final JComboBox<OpcionColumna> selectorColumna = new JComboBox<>();
        private final JComboBox<String> valor = new JComboBox<>();
        private final JPanel panelRango = new JPanel(new GridBagLayout());
        private final CampoBorrable minimo = new CampoBorrable("mín");
        private final CampoBorrable maximo = new CampoBorrable("máx");
        private final SelectorRango selector = new SelectorRango();
        /** Slider positions: the distinct values of the column (amounts or epoch days), ascending. */
        private double[] valoresRango = new double[0];
        private TipoRango tipo = TipoRango.NINGUNO;
        /** True while the min/max boxes are being filled from the slider (no slider feedback). */
        private boolean sincronizando;
        /** "contiene" / "es" (comparison) / "no contiene" / "no es" (Excluir) / "no aplica aquí". */
        private final JLabel etiqueta = new JLabel();
        /** Incluir: show what matches. Excluir: hide what matches the text / range (the checkbox list is not inverted). */
        private final JComboBox<String> modo = new JComboBox<>(new String[] {"Incluir", "Excluir"});
        /** Opens the checkbox list of the column's values (text columns only). */
        private final JButton valores = new JButton();
        /** Values unticked in the checkbox list (their rows are hidden), by displayed text; kept across tabs. */
        private final Set<String> ocultos = new HashSet<>();
        /** Distinct values of the column in the visible tab, as shown in the checkbox list. */
        private List<String> valoresLista = List.of();

        boolean esExcluir() {
            return "Excluir".equals(modo.getSelectedItem());
        }

        /** "Monto ... es >20.000.000" instead of "contiene"; warns when the visible tab lacks the column. */
        void actualizarEtiqueta(boolean aplica) {
            if (aplica) {
                boolean comparacion = FiltroTexto.esComparacion(texto());
                etiqueta.setText(esExcluir() ? (comparacion ? "no es" : "no contiene")
                    : (comparacion ? "es" : "contiene"));
                etiqueta.setForeground(new Color(0x60, 0x60, 0x60));
                etiqueta.setToolTipText(null);
            } else {
                etiqueta.setText("no aplica aquí");
                etiqueta.setForeground(new Color(0xA0, 0x50, 0x00));
                etiqueta.setToolTipText("La pestaña visible no tiene la columna «" + columna()
                    + "»; este filtro se ignora mientras no cambie de pestaña");
            }
        }

        FiltroColumna(String columna) {
            super(new GridBagLayout());
            setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
            for (OpcionColumna o : columnas) {
                selectorColumna.addItem(o);
                if (o.nombre().equals(columna)) {
                    selectorColumna.setSelectedItem(o);
                }
            }
            selectorColumna.setMaximumRowCount(20);
            selectorColumna.setToolTipText("Columna a filtrar");
            valor.setEditable(true);
            valor.setMaximumRowCount(15);
            valor.setPrototypeDisplayValue("XXXXXXXXXXXXXXXXXX");
            valor.setToolTipText(FiltroTexto.AYUDA);
            editor().setToolTipText(FiltroTexto.AYUDA);
            CampoBorrable.escBorra(editor());
            CampoBorrable.alCambiar(editor(), PanelFiltros.this::aplicar);
            construirRango();
            modo.setToolTipText("<html>Incluir: muestra las filas que coinciden.<br>Excluir: oculta las filas que "
                + "coinciden con el texto o el rango (con rango, deja las que quedan fuera).<br>"
                + "La lista de valores siempre oculta los valores desmarcados.</html>");
            modo.addActionListener(e -> {
                actualizarEtiqueta(aplicaEnVisible(this));
                aplicar();
            });
            valores.setToolTipText("Marque o desmarque valores de la columna; los desmarcados se ocultan");
            valores.addActionListener(e -> abrirLista());
            recargar(true);
            selectorColumna.addActionListener(e -> {
                recargar(true);   // new column: its own extremes in the boxes
                aplicar();
            });

            JButton borrar = CampoBorrable.botonBorrar("Borrar el valor de este filtro", this::borrar);
            JButton quitar = new JButton("Quitar");
            quitar.setToolTipText("Eliminar este filtro");
            quitar.addActionListener(e -> quitar(this));

            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(0, 0, 0, 6);
            c.fill = GridBagConstraints.HORIZONTAL;
            add(modo, c);
            selectorColumna.setPreferredSize(new Dimension(210, selectorColumna.getPreferredSize().height));
            add(selectorColumna, c);
            // wide enough for the longest text, so the row does not jump when it changes
            etiqueta.setText("no aplica aquí");
            etiqueta.setPreferredSize(etiqueta.getPreferredSize());
            CampoBorrable.alCambiar(editor(), () -> actualizarEtiqueta(aplicaEnVisible(this)));
            actualizarEtiqueta(aplicaEnVisible(this));
            add(etiqueta, c);
            c.weightx = 1;
            add(valor, c);
            c.weightx = 0;
            c.insets = new Insets(0, 0, 0, 6);
            add(borrar, c);
            add(valores, c);      // text columns
            add(panelRango, c);   // number / date columns
            c.insets = new Insets(0, 0, 0, 0);
            JPanel derecha = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            derecha.add(quitar);
            add(derecha, c);
        }

        private void construirRango() {
            minimo.campo().setColumns(9);
            maximo.campo().setColumns(9);
            selector.setToolTipText("Arrastre los extremos para elegir el rango (←/→ con el teclado)");
            CampoBorrable.escBorra(minimo.campo());
            CampoBorrable.escBorra(maximo.campo());
            minimo.alCambiar(this::cotasEscritas);
            maximo.alCambiar(this::cotasEscritas);
            selector.alCambiar(this::selectorMovido);

            JLabel rotulo = new JLabel("Rango");
            rotulo.setForeground(new Color(0x60, 0x60, 0x60));
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(0, 0, 0, 6);
            c.fill = GridBagConstraints.HORIZONTAL;
            panelRango.add(rotulo, c);
            panelRango.add(minimo, c);
            c.fill = GridBagConstraints.BOTH;
            panelRango.add(selector, c);
            c.fill = GridBagConstraints.HORIZONTAL;
            c.insets = new Insets(0, 0, 0, 0);
            panelRango.add(maximo, c);
        }

        private void actualizarAyudasCotas() {
            if (tipo == TipoRango.FECHA) {
                minimo.setToolTipText("Fecha mínima (incluida), dd/mm/aaaa. Vacío o la primera fecha = sin límite");
                maximo.setToolTipText("Fecha máxima (incluida), dd/mm/aaaa. Vacío o la última fecha = sin límite");
            } else {
                minimo.setToolTipText("Valor mínimo (incluido). Vacío o el menor valor = sin límite. "
                    + "Ej. 20.000.000 o 20,000,000.00");
                maximo.setToolTipText("Valor máximo (incluido). Vacío o el mayor valor = sin límite. "
                    + "Ej. 50.000.000 o 50,000,000.00");
            }
        }

        JTextField editor() {
            return (JTextField) valor.getEditor().getEditorComponent();
        }

        String columna() {
            OpcionColumna o = (OpcionColumna) selectorColumna.getSelectedItem();
            return o == null ? null : o.nombre();
        }

        String texto() {
            return editor().getText();
        }

        /** (text AND range), inverted when Excluir, AND not one of the unticked values; null when inactive. */
        Predicate<Object> condicion() {
            Predicate<Object> porTexto = FiltroTexto.condicion(texto());
            Predicate<Object> porRango = condicionRango();
            Predicate<Object> base = porTexto == null ? porRango
                : porRango == null ? porTexto : porTexto.and(porRango);
            if (base != null && esExcluir()) {
                base = base.negate();   // Excluir + range keeps rows outside it, "No indicado" included
            }
            Predicate<Object> porLista = condicionLista();
            if (base == null) {
                return porLista;
            }
            return porLista == null ? base : base.and(porLista);
        }

        /** Checkbox list: hide the unticked values present in the column; null when every value is ticked. */
        private Predicate<Object> condicionLista() {
            if (tipo != TipoRango.NINGUNO) {
                return null;
            }
            Set<String> ocultosPresentes = new HashSet<>(valoresLista);
            ocultosPresentes.retainAll(ocultos);
            // hide the unticked ones (rather than "show the ticked ones"), so values beyond the list cap never hide
            return ocultosPresentes.isEmpty() ? null : FiltroTexto.enValores(ocultosPresentes).negate();
        }

        /** Range condition, or null while both bounds are at (or beyond) the column's extremes. */
        private Predicate<Object> condicionRango() {
            if (tipo == TipoRango.NINGUNO) {
                return null;
            }
            Double min = cota(minimo);
            Double max = cota(maximo);
            int n = valoresRango.length;
            if (n > 0) {   // the prefilled extremes (or anything beyond them) mean "no limit"
                if (min != null && min <= valoresRango[0]) {
                    min = null;
                }
                if (max != null && max >= valoresRango[n - 1]) {
                    max = null;
                }
            }
            if (tipo == TipoRango.NUMERO) {
                return FiltroTexto.rango(min, max);
            }
            return FiltroTexto.rangoFechas(min == null ? null : LocalDate.ofEpochDay(min.longValue()),
                max == null ? null : LocalDate.ofEpochDay(max.longValue()));
        }

        /** Clears the text value (its × button). */
        void borrar() {
            editor().setText("");
            valor.setSelectedItem("");
        }

        /** Restablecer: Incluir, text cleared, every value ticked, range back to the column's extremes. */
        void restablecer() {
            borrar();
            modo.setSelectedIndex(0);
            ocultos.clear();
            actualizarTextoValores();
            ponerExtremos(true, true);
        }

        // -------------------------------------------------------------- checkbox list

        private void actualizarTextoValores() {
            int n = valoresLista.size();
            long desmarcados = valoresLista.stream().filter(ocultos::contains).count();
            valores.setEnabled(n > 0);
            valores.setText(n == 0 ? "Valores ▼"
                : desmarcados == 0 ? "Valores: todos ▼" : (n - desmarcados) + " de " + n + " ▼");
        }

        /** Excel-style list: search box, "(Todos)" for the values shown, one checkbox per value. */
        private void abrirLista() {
            JPopupMenu menu = new JPopupMenu();
            JPanel contenidoLista = new JPanel(new BorderLayout(0, 4));
            contenidoLista.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
            CampoBorrable buscar = new CampoBorrable("Buscar valor…");
            JCheckBox todos = new JCheckBox("(Todos)");
            JPanel lista = new JPanel();
            lista.setLayout(new BoxLayout(lista, BoxLayout.Y_AXIS));
            List<JCheckBox> cajas = new ArrayList<>();
            Runnable sincronizarTodos = () -> todos.setSelected(cajas.stream().filter(JCheckBox::isVisible)
                .allMatch(JCheckBox::isSelected));
            for (String v : valoresLista) {
                JCheckBox cb = new JCheckBox(v, !ocultos.contains(v));
                cb.addActionListener(e -> {
                    if (cb.isSelected()) {
                        ocultos.remove(v);
                    } else {
                        ocultos.add(v);
                    }
                    sincronizarTodos.run();
                    listaCambio();
                });
                cajas.add(cb);
                lista.add(cb);
            }
            todos.addActionListener(e -> {
                for (JCheckBox cb : cajas) {
                    if (cb.isVisible()) {   // "(Todos)" applies to the values the search box shows
                        cb.setSelected(todos.isSelected());
                        if (todos.isSelected()) {
                            ocultos.remove(cb.getText());
                        } else {
                            ocultos.add(cb.getText());
                        }
                    }
                }
                listaCambio();
            });
            buscar.alCambiar(() -> {
                String q = FiltroTexto.normalizar(buscar.texto());
                for (JCheckBox cb : cajas) {
                    cb.setVisible(q.isEmpty() || FiltroTexto.normalizar(cb.getText()).contains(q));
                }
                sincronizarTodos.run();
                lista.revalidate();
                lista.repaint();
            });
            sincronizarTodos.run();
            JScrollPane scroll = new JScrollPane(lista);
            scroll.getVerticalScrollBar().setUnitIncrement(16);
            int alto = cajas.isEmpty() ? 40
                : Math.min(320, cajas.size() * cajas.get(0).getPreferredSize().height + 4);
            scroll.setPreferredSize(new Dimension(360, alto));
            JPanel centro = new JPanel(new BorderLayout());
            centro.add(todos, BorderLayout.NORTH);
            centro.add(scroll, BorderLayout.CENTER);
            contenidoLista.add(buscar, BorderLayout.NORTH);
            contenidoLista.add(centro, BorderLayout.CENTER);
            menu.add(contenidoLista);
            menu.show(valores, 0, valores.getHeight());
            SwingUtilities.invokeLater(buscar::enfocar);
        }

        private void listaCambio() {
            actualizarTextoValores();
            aplicar();
        }

        // -------------------------------------------------------------- range

        /** Parsed bound, or null when blank or invalid (an invalid box is marked in red and ignored). */
        private Double cota(CampoBorrable campo) {
            String t = campo.texto().strip();
            return t.isEmpty() || tipo == TipoRango.NINGUNO ? null : tipo.parsear(t);
        }

        private String extremo(int i) {
            return valoresRango.length == 0 ? "" : tipo.formatear(valoresRango[i]);
        }

        /** Puts the column's smallest / largest value in the boxes and the handles at the ends (no filtering). */
        private void ponerExtremos(boolean enMinimo, boolean enMaximo) {
            boolean previo = suspendido;
            suspendido = true;
            sincronizando = true;
            try {
                if (enMinimo) {
                    minimo.setTexto(extremo(0));
                }
                if (enMaximo) {
                    maximo.setTexto(extremo(valoresRango.length - 1));
                }
            } finally {
                sincronizando = false;
                suspendido = previo;
            }
            marcarCotas();
            sincronizarSelector();
        }

        /** User typed a bound: validate it, move the slider handles to match and filter. */
        private void cotasEscritas() {
            marcarCotas();
            if (!sincronizando) {
                sincronizarSelector();
            }
            aplicar();
        }

        private void marcarCotas() {
            minimo.marcarInvalido(!minimo.texto().isBlank() && cota(minimo) == null);
            maximo.marcarInvalido(!maximo.texto().isBlank() && cota(maximo) == null);
        }

        /** Handles follow the typed bounds: first value >= mín, last value <= máx (empty = the ends). */
        private void sincronizarSelector() {
            int n = valoresRango.length;
            if (n == 0) {
                return;
            }
            Double min = cota(minimo);
            Double max = cota(maximo);
            int inf = 0;
            if (min != null) {
                while (inf < n - 1 && valoresRango[inf] < min) {
                    inf++;
                }
            }
            int sup = n - 1;
            if (max != null) {
                while (sup > 0 && valoresRango[sup] > max) {
                    sup--;
                }
            }
            selector.setRango(inf, Math.max(inf, sup));
            actualizarAyudaSelector();
        }

        /** User moved a handle: put the handles' values in the boxes and filter once. */
        private void selectorMovido() {
            boolean previo = suspendido;
            suspendido = true;
            sincronizando = true;
            try {
                minimo.setTexto(extremo(selector.getInferior()));
                maximo.setTexto(extremo(selector.getSuperior()));
            } finally {
                sincronizando = false;
                suspendido = previo;
            }
            marcarCotas();
            actualizarAyudaSelector();
            aplicar();
        }

        private void actualizarAyudaSelector() {
            int n = valoresRango.length;
            selector.setToolTipText(n == 0 ? "Sin valores en esta columna"
                : extremo(selector.getInferior()) + "  –  " + extremo(selector.getSuperior())
                + "   (arrastre los extremos; ←/→ con el teclado)");
        }

        // -------------------------------------------------------------- suggestions

        /** Data or visible tab changed: reload suggestions and range values, keeping what the user typed. */
        void actualizarSugerencias() {
            recargar(false);
        }

        /**
         * Reloads the value suggestions and the range of the selected column from the visible table.
         *
         * @param reiniciarRango true for a new column: both boxes get the column's extremes. Otherwise a box keeps
         *                       what the user typed, unless it still showed the previous extreme (then it moves
         *                       to the new one).
         */
        private void recargar(boolean reiniciarRango) {
            String actual = texto();
            String minAnterior = extremo(0);
            String maxAnterior = extremo(Math.max(0, valoresRango.length - 1));
            TipoRango tipoAnterior = tipo;
            boolean previo = suspendido;
            suspendido = true;
            try {
                List<String> distintos = valoresDe(columna());
                valor.setModel(new DefaultComboBoxModel<>(distintos.toArray(String[]::new)));
                valor.setSelectedItem(actual);
                if (!editor().getText().equals(actual)) {
                    editor().setText(actual);
                }
                tipo = TipoRango.de(columna());
                panelRango.setVisible(tipo != TipoRango.NINGUNO);
                valores.setVisible(tipo == TipoRango.NINGUNO);
                valoresLista = tipo == TipoRango.NINGUNO ? distintos : List.of();
                if (reiniciarRango) {
                    ocultos.clear();   // another column: its own list, everything ticked
                }
                actualizarTextoValores();
                actualizarAyudasCotas();
                valoresRango = tipo == TipoRango.NINGUNO ? new double[0] : valoresRango(columna(), tipo);
                selector.setPasos(valoresRango.length);
            } finally {
                suspendido = previo;
            }
            boolean nuevo = reiniciarRango || tipo != tipoAnterior;
            ponerExtremos(nuevo || minimo.texto().equals(minAnterior),
                nuevo || maximo.texto().equals(maxAnterior));
            revalidate();
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); // no vertical stretch in the box
        }
    }
}
