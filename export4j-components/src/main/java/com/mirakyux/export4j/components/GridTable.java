// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import com.mirakyux.export4j.graphics.AffineTransform;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.VectorPath;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.SemanticPlacement;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.ComponentSemantics;
import com.mirakyux.export4j.layout.core.FlowRegions;
import com.mirakyux.export4j.layout.core.RegionFlow;
import com.mirakyux.export4j.layout.core.TextInheritance;
import com.mirakyux.export4j.model.LineBreakPolicy;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextAlignment;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.text.TextDiagnostic;

/** Component cells with row/column spans. By default connected row groups stay together in each region;
 * headers repeat without remeasurement. Point dimensions, local top-left coordinates, no resource ownership.
 * Explicit allowRowSplit enables complete-line continuation; fixed/custom children remain atomic.
 * Immutable structure; thread safety depends on cells. No content is shrunk or clipped to fit.
 */
public record GridTable(int rowCount, List<Double> columnWeights, int headerRows, List<Cell> cells,
        List<Double> minimumRowHeights, boolean allowRowSplit) implements RegionFlow {
    /** Compatibility constructor: connected row groups remain atomic. */
    public GridTable(int rowCount, List<Double> columnWeights, int headerRows, List<Cell> cells, List<Double> minimumRowHeights) {
        this(rowCount, columnWeights, headerRows, cells, minimumRowHeights, false);
    }
    /** Compatibility constructor: all row minima are zero. */
    public GridTable(int rowCount, List<Double> columnWeights, int headerRows, List<Cell> cells) {
        this(rowCount, columnWeights, headerRows, cells, java.util.Collections.nCopies(rowCount, 0.0));
    }

    /** Declarative task-confined builder. Rows are explicit; columns use relative weights.
     * Cells auto-place left to right, skipping earlier row spans. Holes and overflow fail at build(). */
    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private List<Double> columns = List.of(1.0);
        private int headerRows;
        private boolean allowRowSplit;
        private CellBuilder defaults = new CellBuilder();
        private final List<RowSpec> rows = new ArrayList<>();

        private Builder() { }

        public Builder columns(double... weights) {
            columns = java.util.Arrays.stream(weights).boxed().toList(); return this;
        }
        public Builder headerRows(int value) { headerRows = value; return this; }
        /** Allows complete-line continuation within body cells and merged groups. Default false.
         * Fixed/custom components remain atomic; padding and headers repeat per occupied region. */
        public Builder allowRowSplit(boolean value) { allowRowSplit = value; return this; }
        /** Applies a snapshot to subsequent rows/cells; later cell settings override it. */
        public Builder defaults(java.util.function.Consumer<CellBuilder> configure) {
            var value = new CellBuilder(defaults); configure.accept(value); defaults = new CellBuilder(value); return this;
        }
        public Builder row(java.util.function.Consumer<RowBuilder> configure) {
            var value = new RowBuilder(defaults); configure.accept(value);
            rows.add(new RowSpec(value.minimumHeight, List.copyOf(value.cells))); return this;
        }
        public GridTable build() {
            if (rows.isEmpty() || columns.isEmpty() || (long) rows.size() * columns.size() > 1_000_000) {
                throw new IllegalArgumentException("invalid grid dimensions or budget");
            }
            var occupied = new java.util.BitSet[rows.size()];
            for (int row = 0; row < rows.size(); row++) { occupied[row] = new java.util.BitSet(columns.size()); }
            List<Cell> cells = new ArrayList<>();
            for (int row = 0; row < rows.size(); row++) {
                int column = 0;
                for (var spec : rows.get(row).cells()) {
                    column = occupied[row].nextClearBit(column);
                    if ((long) row + spec.rowSpan() > rows.size() || (long) column + spec.columnSpan() > columns.size()) {
                        throw new IllegalArgumentException("cell span exceeds declared rows or columns");
                    }
                    for (int r = row; r < row + spec.rowSpan(); r++) {
                        int collision = occupied[r].nextSetBit(column);
                        if (collision >= 0 && collision < column + spec.columnSpan()) {
                            throw new IllegalArgumentException("cell span overlaps an earlier cell");
                        }
                        occupied[r].set(column, column + spec.columnSpan());
                    }
                    cells.add(new Cell(row, column, spec.rowSpan(), spec.columnSpan(), spec.content(), spec.padding(),
                            spec.verticalAlignment(), spec.background(), spec.border(), spec.borderWidth()));
                    column += spec.columnSpan();
                }
            }
            return new GridTable(rows.size(), columns, headerRows, cells, rows.stream().map(RowSpec::minimumHeight).toList(), allowRowSplit);
        }
    }

    private record RowSpec(double minimumHeight, List<Cell> cells) { }

    /** One explicitly declared row. minHeight is a lower bound in points; content may grow it. */
    public static final class RowBuilder {
        private final CellBuilder defaults;
        private final List<Cell> cells = new ArrayList<>();
        private double minimumHeight;
        private RowBuilder(CellBuilder defaults) { this.defaults = new CellBuilder(defaults); }
        public RowBuilder minHeight(double value) {
            if (!Double.isFinite(value) || value < 0) { throw new IllegalArgumentException("invalid row minimum height"); }
            minimumHeight = value; return this;
        }
        public RowBuilder cells(String... values) {
            for (var value : values) { cell(value); } return this;
        }
        public RowBuilder cell(String text) { return cell(text, value -> { }); }
        public RowBuilder cell(String text, java.util.function.Consumer<CellBuilder> configure) {
            var value = new CellBuilder(defaults); value.text(text); configure.accept(value);
            cells.add(value.snapshot()); return this;
        }
        public RowBuilder cell(Component content) { return cell(content, value -> { }); }
        public RowBuilder cell(Component content, java.util.function.Consumer<CellBuilder> configure) {
            var value = new CellBuilder(defaults); value.content(content); configure.accept(value);
            cells.add(value.snapshot()); return this;
        }
        /** Adds a semantic table; fields and links resolve after final document pagination. */
        public RowBuilder semanticTable(Table table) { return semanticTable(table, value -> { }); }
        /** Adds a semantic table with a synchronous snapshot of padding, spans and other cell settings. */
        public RowBuilder semanticTable(Table table, java.util.function.Consumer<CellBuilder> configure) {
            return cell(new SemanticTable(table), configure);
        }
    }

    /** Named cell settings. Text defaults to 12 pt, white background, black 0.5 pt border, 4 pt padding.
     * Custom components/factories remain borrowed and determine their own thread-safety contract. */
    public static final class CellBuilder {
        private int rowSpan = 1;
        private int columnSpan = 1;
        private String text = "";
        private Component content;
        private ComponentTextStyle typography = new ComponentTextStyle();
        private java.util.function.Function<String, Component> textFactory;
        private PageMargins padding = PageMargins.uniform(4);
        private FlowColumn.Alignment verticalAlignment = FlowColumn.Alignment.START;
        private RgbColor background = RgbColor.WHITE;
        private RgbColor border = RgbColor.BLACK;
        private double borderWidth = 0.5;
        private CellBuilder() { }
        private CellBuilder(CellBuilder value) {
            rowSpan = value.rowSpan; columnSpan = value.columnSpan; text = value.text; content = value.content;
            typography = new ComponentTextStyle(value.typography); textFactory = value.textFactory;
            padding = value.padding; verticalAlignment = value.verticalAlignment; background = value.background;
            border = value.border; borderWidth = value.borderWidth;
        }
        public CellBuilder rowSpan(int value) { rowSpan = value; return this; }
        public CellBuilder columnSpan(int value) { columnSpan = value; return this; }
        public CellBuilder text(String value) { text = Objects.requireNonNull(value, "text"); content = null; return this; }
        public CellBuilder content(Component value) { content = Objects.requireNonNull(value, "content"); return this; }
        /** Regional default for all unset text descendants, including custom cell content. */
        public CellBuilder textStyle(ParagraphStyle value) { typography.style(value); return this; }
        public CellBuilder textColor(RgbColor value) { typography.color(value); return this; }
        /** Clears the local declaration; subsequent cells inherit the surrounding scope. */
        public CellBuilder inheritTextStyle() { typography.inheritStyle(); return this; }
        public CellBuilder inheritTextColor() { typography.inheritColor(); return this; }
        /** Default for this property in unset text descendants; lengths use points. */
        public CellBuilder fontSize(double value) { typography.fontSize(value); return this; }
        /** Restores only this property to the surrounding text defaults. */
        public CellBuilder inheritFontSize() { typography.inherit(TextInheritance.Property.FONT_SIZE); return this; }
        /** Default for this property in unset text descendants; lengths use points. */
        public CellBuilder lineHeight(double value) { typography.lineHeight(value); return this; }
        /** Restores only this property to the surrounding text defaults. */
        public CellBuilder inheritLineHeight() { typography.inherit(TextInheritance.Property.LINE_HEIGHT); return this; }
        /** Default for this property in unset text descendants; lengths use points. */
        public CellBuilder firstLineIndent(double value) { typography.firstLineIndent(value); return this; }
        /** Restores only this property to the surrounding text defaults. */
        public CellBuilder inheritFirstLineIndent() { typography.inherit(TextInheritance.Property.FIRST_LINE_INDENT); return this; }
        /** Default for this property in unset text descendants; lengths use points. */
        public CellBuilder subsequentLineIndent(double value) { typography.subsequentLineIndent(value); return this; }
        /** Restores only this property to the surrounding text defaults. */
        public CellBuilder inheritSubsequentLineIndent() { typography.inherit(TextInheritance.Property.SUBSEQUENT_LINE_INDENT); return this; }
        /** Default for this property in unset text descendants; lengths use points. */
        public CellBuilder alignment(TextAlignment value) { typography.alignment(value); return this; }
        /** Restores only this property to the surrounding text defaults. */
        public CellBuilder inheritAlignment() { typography.inherit(TextInheritance.Property.ALIGNMENT); return this; }
        /** Default for this property in unset text descendants; lengths use points. */
        public CellBuilder lineBreakPolicy(LineBreakPolicy value) { typography.lineBreakPolicy(value); return this; }
        /** Restores only this property to the surrounding text defaults. */
        public CellBuilder inheritLineBreakPolicy() { typography.inherit(TextInheritance.Property.LINE_BREAK_POLICY); return this; }
        /** Replaces the standard text renderer for subsequent text cells. Receives the original text. */
        public CellBuilder textFactory(java.util.function.Function<String, Component> value) { textFactory = Objects.requireNonNull(value, "textFactory"); return this; }
        public CellBuilder padding(PageMargins value) { padding = Objects.requireNonNull(value, "padding"); return this; }
        public CellBuilder padding(double points) { return padding(PageMargins.uniform(points)); }
        public CellBuilder verticalAlignment(FlowColumn.Alignment value) { verticalAlignment = Objects.requireNonNull(value, "verticalAlignment"); return this; }
        public CellBuilder background(RgbColor value) { background = Objects.requireNonNull(value, "background"); return this; }
        public CellBuilder border(RgbColor value) { border = Objects.requireNonNull(value, "border"); return this; }
        public CellBuilder borderWidth(double value) { borderWidth = value; return this; }
        private Cell snapshot() {
            Component child = content != null ? content : textFactory != null ? textFactory.apply(text) : FlowText.builder(text).build();
            if (!typography.inheritance().equals(TextInheritance.ALL)) {
                child = new StyleScope(child, typography.style(), typography.color(), typography.inheritance());
            }
            return new Cell(0, 0, rowSpan, columnSpan, child, padding, verticalAlignment, background, border, borderWidth);
        }
    }
    /** Top-left grid position and spans; every grid slot must be covered exactly once. */
    public record Cell(int row, int column, int rowSpan, int columnSpan, Component content,
            PageMargins padding, FlowColumn.Alignment verticalAlignment, RgbColor background, RgbColor border, double borderWidth) {
        public Cell {
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(padding, "padding");
            Objects.requireNonNull(verticalAlignment, "verticalAlignment");
            Objects.requireNonNull(background, "background");
            Objects.requireNonNull(border, "border");
            if (row < 0 || column < 0 || rowSpan <= 0 || columnSpan <= 0 || !Double.isFinite(borderWidth) || borderWidth < 0) {
                throw new IllegalArgumentException("invalid grid cell");
            }
        }
    }

    public GridTable {
        columnWeights = List.copyOf(columnWeights);
        cells = List.copyOf(cells);
        if (rowCount <= 0 || columnWeights.isEmpty() || headerRows < 0 || headerRows >= rowCount
                || (long) rowCount * columnWeights.size() > 1_000_000 || cells.size() > 1_000_000
                || columnWeights.stream().anyMatch(w -> !Double.isFinite(w) || w <= 0)) {
            throw new IllegalArgumentException("invalid grid dimensions or budget");
        }
        if (minimumRowHeights.size() != rowCount || minimumRowHeights.stream().anyMatch(h -> !Double.isFinite(h) || h < 0)) {
            throw new IllegalArgumentException("invalid row minimum heights");
        }
        minimumRowHeights = List.copyOf(minimumRowHeights);
        boolean[][] covered = new boolean[rowCount][columnWeights.size()];
        for (var cell : cells) {
            if ((long) cell.row() + cell.rowSpan() > rowCount || (long) cell.column() + cell.columnSpan() > columnWeights.size()
                    || cell.row() < headerRows && cell.row() + cell.rowSpan() > headerRows) {
                throw new IllegalArgumentException("cell span crosses grid or header boundary");
            }
            for (int row = cell.row(); row < cell.row() + cell.rowSpan(); row++) {
                for (int col = cell.column(); col < cell.column() + cell.columnSpan(); col++) {
                    if (covered[row][col]) {
                        throw new IllegalArgumentException("overlapping cell spans");
                    }
                    covered[row][col] = true;
                }
            }
        }
        for (var row : covered) {
            for (boolean slot : row) {
                if (!slot) {
                    throw new IllegalArgumentException("uncovered grid slot");
                }
            }
        }
    }

    @Override
    public List<ComponentLayout> layoutParts(ComponentContext context) {
        return RegionFlow.super.layoutParts(context);
    }

    @Override
    public List<ComponentLayout> layoutParts(ComponentContext context, double firstPageHeight) {
        return RegionFlow.super.layoutParts(context, firstPageHeight);
    }

    @Override
    public List<RegionFlow.Fragment> layoutRegions(ComponentContext context, FlowRegions regions) {
        ComponentSupport.fits(context.width(), regions.continuationHeight(), context);
        if (allowRowSplit) { return GridTableContinuation.layout(this, context, regions); }
        ExecutionContext.checkpoint();
        double max = columnWeights.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
        double sum = columnWeights.stream().mapToDouble(w -> w / max).sum();
        double[] xs = new double[columnWeights.size() + 1];
        for (int col = 0; col < columnWeights.size(); col++) {
            xs[col + 1] = xs[col] + context.width() * (columnWeights.get(col) / max) / sum;
        }
        double[] heights = minimumRowHeights.stream().mapToDouble(Double::doubleValue).toArray();
        List<ComponentLayout> layouts = new ArrayList<>();
        var streams = new GridSemanticStreams();
        for (var cell : cells) {
            ExecutionContext.checkpoint();
            double width = xs[cell.column() + cell.columnSpan()] - xs[cell.column()] - cell.padding().left() - cell.padding().right();
            double height = regions.continuationHeight() - cell.padding().top() - cell.padding().bottom();
            var childContext = context.viewport(width, height);
            var layout = cell.content().layout(childContext);
            ComponentSupport.fits(layout.width(), layout.height(), childContext);
            streams.registerFixed(layout, cell.row() < headerRows);
            layouts.add(layout);
        }
        // Establish individual rows first, then satisfy merged constraints in canonical order.
        // Measurement and paint retain source order; geometry is independent of declaration order.
        var constraints = java.util.stream.IntStream.range(0, cells.size()).boxed().sorted(
                java.util.Comparator.<Integer>comparingInt(i -> cells.get(i).rowSpan())
                        .thenComparingInt(i -> cells.get(i).row()).thenComparingInt(i -> cells.get(i).column())).toList();
        for (int index : constraints) {
            ExecutionContext.checkpoint();
            var cell = cells.get(index);
            double required = layouts.get(index).height() + cell.padding().top() + cell.padding().bottom();
            double actual = 0;
            for (int row = cell.row(); row < cell.row() + cell.rowSpan(); row++) {
                actual += heights[row];
            }
            if (required > actual) {
                double extra = (required - actual) / cell.rowSpan();
                for (int row = cell.row(); row < cell.row() + cell.rowSpan(); row++) {
                    heights[row] += extra;
                }
            }
        }
        // Any span crossing a row boundary makes that boundary indivisible.
        boolean[] joined = new boolean[rowCount];
        for (var cell : cells) {
            for (int row = cell.row() + 1; row < cell.row() + cell.rowSpan(); row++) {
                joined[row] = true;
            }
        }
        double headerHeight = 0;
        for (int row = 0; row < headerRows; row++) {
            headerHeight += heights[row];
        }
        List<RegionFlow.Fragment> result = new ArrayList<>();
        int first = headerRows;
        for (int region = 0; first < rowCount; region++) {
            ExecutionContext.checkpoint();
            int last = first;
            double used = headerHeight;
            double capacity = regions.height(region);
            while (last < rowCount) {
                ExecutionContext.checkpoint();
                int end = last + 1;
                while (end < rowCount && joined[end]) {
                    end++;
                }
                double groupHeight = 0;
                for (int row = last; row < end; row++) {
                    groupHeight += heights[row];
                }
                if (headerHeight + groupHeight > Math.nextUp(regions.continuationHeight())) {
                    throw new IllegalArgumentException("merged row group and headers exceed fresh page");
                }
                if (used + groupHeight > Math.nextUp(capacity)) {
                    break;
                }
                used += groupHeight;
                last = end;
            }
            if (last == first) {
                if (region < regions.initialHeights().size()) { continue; }
                throw new IllegalArgumentException("table cannot advance in continuation region");
            }
            if (result.size() >= 100_000) { throw new IllegalArgumentException("table region fragment budget exceeded"); }
            result.add(new RegionFlow.Fragment(region, paint(context.width(), used, first, last, xs, heights, layouts)));
            first = last;
        }
        return streams.finish(result);
    }

    private ComponentLayout paint(double width, double height, int first, int last, double[] xs,
            double[] heights, List<ComponentLayout> layouts) {
        double[] ys = new double[rowCount + 1];
        double y = 0;
        for (int row = 0; row < rowCount; row++) {
            ys[row] = y;
            if (row < headerRows || row >= first && row < last) {
                y += heights[row];
            }
        }
        ys[rowCount] = y;
        var builder = new DisplayListBuilder(new PageSize(width, height));
        List<TextDiagnostic> diagnostics = new ArrayList<>();
        List<SemanticPlacement> placements = new ArrayList<>();
        int lines = 0;
        for (int index = 0; index < cells.size(); index++) {
            ExecutionContext.checkpoint();
            var cell = cells.get(index);
            if (cell.row() >= headerRows && (cell.row() < first || cell.row() >= last)) {
                continue;
            }
            double x = xs[cell.column()];
            double top = ys[cell.row()];
            double w = xs[cell.column() + cell.columnSpan()] - x;
            double h = ys[cell.row() + cell.rowSpan()] - top;
            var layout = layouts.get(index);
            paintCell(builder, placements, cell, new Rect(x, top, w, h), java.util.Optional.of(layout));
            diagnostics.addAll(layout.diagnostics());
            lines = Math.addExact(lines, layout.textLines());
        }
        return new ComponentLayout(width, height, lines, builder.build().commands(), diagnostics, placements);
    }

    static void paintCell(DisplayListBuilder builder, List<SemanticPlacement> placements,
            Cell cell, Rect bounds, java.util.Optional<ComponentLayout> content) {
        double x = bounds.x();
        double top = bounds.y();
        double w = bounds.width();
        double h = bounds.height();
        builder.fillRect(bounds, cell.background());
        double stroke = Math.min(cell.borderWidth(), Math.min(w, h));
        if (stroke > 0) {
            double l = x + stroke / 2;
            double t = top + stroke / 2;
            double r = x + w - stroke / 2;
            double b = top + h - stroke / 2;
            builder.strokePath(new VectorPath(List.of(new VectorPath.MoveTo(new Point(l, t)), new VectorPath.LineTo(new Point(r, t)),
                    new VectorPath.LineTo(new Point(r, b)), new VectorPath.LineTo(new Point(l, b)), new VectorPath.Close())), cell.border(), stroke);
        }
        if (content.isPresent()) {
            var layout = content.orElseThrow();
            double free = h - cell.padding().top() - cell.padding().bottom() - layout.height();
            double offset = switch (cell.verticalAlignment()) { case START -> 0; case CENTER -> free / 2; case END -> free; };
            builder.save().transform(AffineTransform.translation(x + cell.padding().left(), top + cell.padding().top() + offset));
            ComponentSemantics.append(builder, placements, layout);
            builder.restore();
        }
    }
}
