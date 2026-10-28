// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.DocumentExtras;
import com.mirakyux.export4j.layout.CellFragment;
import com.mirakyux.export4j.layout.LayoutPage;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.layout.TableRowFragment;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.StreamingTable;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.TableRowSource;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.text.InlineFieldInk;
import com.mirakyux.export4j.text.ParagraphLayout;
import com.mirakyux.export4j.text.ParagraphLayouter;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.StyledSpan;
import com.mirakyux.export4j.text.TextAlignment;
import com.mirakyux.export4j.text.TextLayoutException;

/** Single-pass fixed-column pagination. Measures each source row once and retains only finite headers,
 * the current page and one pending measured row. Consumers may retain pages explicitly, but this
 * layouter does not. Calls source and callbacks synchronously, with cooperative execution checkpoints.
 * Immutable and reusable across threads; each invocation owns its layout state, and borrows its source.
 * Text, inline images and passive links share the ordinary measured-ink geometry. PAGE resolves at
 * actual presentation; PAGES requires an exact caller-declared total. Complete-line body continuation
 * is opt-in. Optional vertical spans retain one explicitly bounded atomic connected row group;
 * the default path still retains only one pending row. No block cells, global references, page
 * templates or automatic widths.
 * Horizontal spans cover consecutive fixed columns; source counters count real anchors once.
 */
public final class StreamingTableLayouter {
    /** Receives one immutable page at a time, exactly once in increasing physical page order.
     * A failure stops consumption immediately; the layouter does not retry or close this consumer.
     */
    @FunctionalInterface
    public interface PageConsumer {
        void accept(LayoutPage page) throws IOException;
        /** Receives this page's resolved passive links. Existing consumers explicitly reject
         * nonempty interactions until they implement this overload; none are silently discarded. */
        default void accept(LayoutPage page, List<DocumentExtras.ExternalLink> links) throws IOException {
            if (!links.isEmpty()) { throw new IOException("page consumer does not support external links"); }
            accept(page);
        }
    }

    /** Bounded successful result; source counts include headers once, bodyRows excludes headers.
     * Output lines and diagnostics include repeated headers. No IR, text or diagnostics list is retained.
     */
    public record Summary(long bodyRows, int pages, long sourceCells, long sourceCodePoints,
            long outputLines, long diagnostics) { }

    /** Lays out into a borrowed synchronous page consumer. Neither source nor consumer is closed.
     * Empty sources emit one page, optionally containing only headers. Oversized first rows fail before
     * any header-only page is delivered. Font policy is explicit; no system fonts or resources are fetched.
     * @throws IOException on source or page-consumer failure, preserving the original exception
     * @throws DocumentLayoutException on invalid content, geometry, text or budgets
     */
    public Summary layout(StreamingTable table, TableRowSource source, FontGroup fonts, PageConsumer pages,
            Consumer<StreamingTableDiagnostic> diagnostics, StreamingTableLimits limits) throws IOException {
        return layout(table, source, fonts, Map.of(), pages, diagnostics, limits);
    }

    /** Lays out supported mixed cells with explicit default fonts and named font groups.
     * The role map is snapshotted before reading the borrowed source; names must be nonblank and
     * keys and values nonnull. Each run retains its group's missing-glyph policy. Unregistered roles
     * fail at their run's cell-local UTF-16 start. No synthetic fonts or implicit fallback are used.
     * All cells in a source row are checked for supported content, roles and budgets before shaping.
     * Source and consumers are called synchronously and are never closed by this method.
     * @throws IOException on source or page-consumer failure, preserving the original exception
     * @throws DocumentLayoutException on invalid content, geometry, text or budgets
     * @throws IllegalArgumentException if a registered font role name is blank
     * @throws NullPointerException if a required argument or role key/value is null
     */
    public Summary layout(StreamingTable table, TableRowSource source, FontGroup fonts,
            Map<String, FontGroup> fontRoles, PageConsumer pages,
            Consumer<StreamingTableDiagnostic> diagnostics, StreamingTableLimits limits) throws IOException {
        if (Objects.requireNonNull(table, "table").semanticCells()) {
            throw new IllegalArgumentException("semantic streaming tables require the explicit replay exporter");
        }
        var roles = Map.copyOf(Objects.requireNonNull(fontRoles, "fontRoles"));
        for (var name : roles.keySet()) {
            ExecutionContext.checkpoint();
            if (name.isBlank()) { throw new IllegalArgumentException("font role is blank"); }
        }
        return new Run(Objects.requireNonNull(table, "table"), Objects.requireNonNull(source, "source"),
                Objects.requireNonNull(fonts, "fonts"), roles, Objects.requireNonNull(pages, "pages"),
                Objects.requireNonNull(diagnostics, "diagnostics"), Objects.requireNonNull(limits, "limits")).layout();
    }

    private record MeasuredCell(int column, double width, ParagraphLayout layout, TableCell source,
            double step, int firstLine, int rowSpan, double height) {
        private MeasuredCell(int column, double width, ParagraphLayout layout, TableCell source, double step, int firstLine) {
            this(column, width, layout, source, step, firstLine, 1, 0);
        }
    }
    private record MeasuredRow(int index, double height, List<MeasuredCell> cells, boolean continued) {
        private MeasuredRow(int index, double height, List<MeasuredCell> cells) { this(index, height, cells, false); }
    }

    private static final class Run {
        private final StreamingTable table;
        private final TableRowSource source;
        private final FontGroup fonts;
        private final Map<String, FontGroup> roles;
        private final PageConsumer pages;
        private final Consumer<StreamingTableDiagnostic> diagnostics;
        private final StreamingTableLimits limits;
        private final ParagraphLayouter text = new ParagraphLayouter();
        private final List<MeasuredRow> headers = new ArrayList<>();
        private final List<TableRowFragment> current = new ArrayList<>();
        private final List<DocumentExtras.ExternalLink> currentLinks = new ArrayList<>();
        private final Rect bounds;
        private final double width;
        private final double[] columnOffsets;
        private double headerHeight;
        private double used;
        private long bodyRows;
        private int pageIndex;
        private long sourceCells;
        private long sourceSlots;
        private long sourceCodePoints;
        private long outputLines;
        private long diagnosticCount;
        private long outputLinks;
        private long outputFields;
        private long generatedCodePoints;
        private long groupStartCodePoints;

        Run(StreamingTable table, TableRowSource source, FontGroup fonts, Map<String, FontGroup> roles, PageConsumer pages,
                Consumer<StreamingTableDiagnostic> diagnostics, StreamingTableLimits limits) {
            this.table = table; this.source = source; this.fonts = fonts; this.pages = pages;
            this.roles = roles;
            this.diagnostics = diagnostics; this.limits = limits;
            if (table.columnWidths().size() > limits.document().maxTableColumns()) {
                throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, -1, -1, -1,
                        "streaming column budget exceeded", null);
            }
            var settings = table.pageSettings();
            bounds = new Rect(settings.margins().left(), settings.margins().top(), settings.contentWidth(), settings.contentHeight());
            width = table.columnWidths().stream().mapToDouble(Double::doubleValue).sum();
            columnOffsets = new double[table.columnWidths().size()];
            for (int column = 1; column < columnOffsets.length; column++) {
                columnOffsets[column] = columnOffsets[column - 1] + table.columnWidths().get(column - 1);
            }
        }

        Summary layout() throws IOException {
            ExecutionContext.progress("STREAMING_TABLE", table.id(), 0, 0, -1);
            if (table.headerRows().size() > limits.document().maxTableRows()) {
                throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, -1, -1, -1, "streaming row budget exceeded", null);
            }
            if (table.expectedPages() > limits.document().maxPages()) {
                throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, -1, -1, -1,
                        "declared page total exceeds streaming page budget", null);
            }
            for (int row = 0; row < table.headerRows().size(); row++) {
                var measured = measure(table.headerRows().get(row), row);
                headerHeight += measured.height();
                if (!fits(headerHeight, bounds.height())) {
                    throw failure(DocumentLayoutException.Code.CONTENT_TOO_TALL, row, -1, -1, "streaming headers exceed page content height", null);
                }
                headers.add(measured);
            }
            beginPage();
            while (true) {
                ExecutionContext.progress("STREAMING_ROWS", table.id(), pageIndex, bodyRows, -1);
                var next = source.next();
                ExecutionContext.checkpoint();
                if (next == null) {
                    throw failure(DocumentLayoutException.Code.INVALID_GEOMETRY, nextRowIndex(), -1, -1, "streaming source returned null", null);
                }
                if (next.isEmpty()) { break; }
                if (table.allowRowSpans()) {
                    placeGroup(readGroup(next.orElseThrow()));
                    continue;
                }
                var measured = measure(next.orElseThrow(), nextRowIndex());
                bodyRows++;
                if (table.allowRowSplit()) {
                    splitBody(measured);
                    continue;
                }
                if (!fits(measured.height(), bounds.height() - headerHeight)) {
                    throw failure(DocumentLayoutException.Code.CONTENT_TOO_TALL, measured.index(), -1, -1,
                            "streaming row cannot fit with repeated headers", null);
                }
                if (!fits(measured.height(), bounds.height() - used)) {
                    emit();
                    pageIndex++;
                    beginPage();
                }
                current.add(place(measured, used, false));
                used += measured.height();
                ExecutionContext.checkpoint();
            }
            if (table.expectedPages() > 0 && table.expectedPages() != pageIndex + 1) {
                throw failure(DocumentLayoutException.Code.INVALID_GEOMETRY, -1, -1, -1,
                        "actual streaming page count differs from declared total", null);
            }
            emit();
            ExecutionContext.progress("STREAMING_TABLE_DONE", table.id(), pageIndex, bodyRows, bodyRows);
            return new Summary(bodyRows, pageIndex + 1, sourceCells, sourceCodePoints, outputLines, diagnosticCount);
        }

        private int nextRowIndex() {
            return Math.toIntExact(table.headerRows().size() + bodyRows);
        }

        private MeasuredRow measure(TableRow row, int rowIndex) {
            return measure(row, rowIndex, null, rowIndex);
        }

        private MeasuredRow measure(TableRow row, int rowIndex, int[] coveredUntil, int groupStart) {
            ExecutionContext.checkpoint();
            if (rowIndex >= limits.document().maxTableRows()) {
                throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, rowIndex, -1, -1, "streaming source row budget exceeded", null);
            }
            long rowPoints = 0;
            int rowInlines = 0;
            int column = 0;
            var columns = new ArrayList<Integer>(row.cells().size());
            for (var cell : row.cells()) {
                ExecutionContext.checkpoint();
                while (coveredUntil != null && column < coveredUntil.length && coveredUntil[column] > rowIndex) { column++; }
                if (cell.columnSpan() > table.columnWidths().size() - column) {
                    throw failure(DocumentLayoutException.Code.INVALID_GEOMETRY, rowIndex, column, -1,
                            "streaming row spans exceed column count", null);
                }
                if (cell.hasBlocks()) {
                    throw failure(DocumentLayoutException.Code.UNSUPPORTED_COMPONENT, rowIndex, column, -1,
                            "streaming tables do not support semantic block cells", null);
                }
                if (cell.rowSpan() != 1 && !table.allowRowSpans()) {
                    throw failure(DocumentLayoutException.Code.UNSUPPORTED_COMPONENT, rowIndex, column, -1,
                            "streaming tables do not support row spans", null);
                }
                if (coveredUntil != null) {
                    if ((long) rowIndex - groupStart + cell.rowSpan() > limits.maxBufferedRows()) {
                        throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, rowIndex, column, -1,
                                "streaming connected row group exceeds buffered row budget", null);
                    }
                    if ((long) rowIndex + cell.rowSpan() > limits.document().maxTableRows()) {
                        throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, rowIndex, column, -1,
                                "streaming vertical span exceeds source row budget", null);
                    }
                    for (int covered = column; covered < column + cell.columnSpan(); covered++) {
                        if (coveredUntil[covered] > rowIndex) {
                            throw failure(DocumentLayoutException.Code.INVALID_GEOMETRY, rowIndex, column, -1,
                                    "streaming cell spans overlap", null);
                        }
                        coveredUntil[covered] = rowIndex + cell.rowSpan();
                    }
                }
                columns.add(column);
                for (var span : cell.fields()) {
                    var field = span.field();
                    if (field.kind() != InlineField.Kind.PAGE && field.kind() != InlineField.Kind.PAGES
                            || field.kind() == InlineField.Kind.PAGES && table.expectedPages() == 0) {
                        throw failure(DocumentLayoutException.Code.UNSUPPORTED_COMPONENT, rowIndex, column, span.sourceIndex(),
                                "streaming fields require PAGE or an explicitly declared PAGES total", null);
                    }
                }
                if (cell.inlines().isEmpty()) {
                    if (rowInlines == limits.maxRowInlines()) {
                        throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, rowIndex, column, 0,
                                "streaming source inline run budget exceeded", null);
                    }
                    rowInlines++;
                } else {
                    int cellInlines = 0;
                    int offset = 0;
                    for (var inline : cell.inlines()) {
                        ExecutionContext.checkpoint();
                        if (cellInlines == limits.maxCellInlines() || rowInlines == limits.maxRowInlines()) {
                            throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, rowIndex, column, offset,
                                    "streaming source inline run budget exceeded", null);
                        }
                        cellInlines++; rowInlines++;
                        String role = inline.style().fontRole();
                        if (!role.isEmpty() && !roles.containsKey(role)) {
                            throw failure(DocumentLayoutException.Code.TEXT_LAYOUT_FAILED, rowIndex, column, offset,
                                    "streaming cell font role is unregistered", null);
                        }
                        offset += inline.text().length();
                    }
                }
                long slots = (long) cell.columnSpan() * cell.rowSpan();
                if (slots > limits.document().maxCells() - sourceSlots) {
                    throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, rowIndex, column, -1, "streaming source cell budget exceeded", null);
                }
                sourceSlots += slots;
                sourceCells++;
                int cellPoints = 0;
                for (int offset = 0; offset < cell.text().length();) {
                    if ((cellPoints & 1023) == 0) { ExecutionContext.checkpoint(); }
                    if (cellPoints == limits.maxCellCodePoints() || rowPoints == limits.maxRowCodePoints()
                            || sourceCodePoints == limits.document().maxCodePoints()
                            || coveredUntil != null && sourceCodePoints - groupStartCodePoints == limits.maxBufferedCodePoints()) {
                        throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, rowIndex, column, offset,
                                "streaming source code point budget exceeded", null);
                    }
                    offset += Character.charCount(cell.text().codePointAt(offset));
                    cellPoints++; rowPoints++; sourceCodePoints++;
                }
                column += cell.columnSpan();
            }
            boolean uncovered = coveredUntil == null ? column != table.columnWidths().size() : false;
            if (coveredUntil != null) {
                for (int end : coveredUntil) { uncovered |= end <= rowIndex; }
            }
            if (uncovered) {
                throw failure(DocumentLayoutException.Code.INVALID_GEOMETRY, rowIndex, column, -1,
                        "streaming row must cover every column", null);
            }
            var cells = new ArrayList<MeasuredCell>();
            double height = 0;
            int anchorIndex = 0;
            for (var cell : row.cells()) {
                ExecutionContext.checkpoint();
                column = columns.get(anchorIndex++);
                double columnWidth = 0;
                for (int offset = 0; offset < cell.columnSpan(); offset++) {
                    ExecutionContext.checkpoint();
                    columnWidth += table.columnWidths().get(column + offset);
                }
                var format = cell.format();
                var style = new ParagraphStyle(format.fontSize(), format.lineHeight(), 0, 0,
                        TextAlignment.valueOf(format.alignment().name()), format.lineBreakPolicy());
                ParagraphLayout measured;
                try {
                    double cellWidth = columnWidth - table.padding().left() - table.padding().right();
                    if (cell.inlines().isEmpty() && cell.images().isEmpty() && cell.fields().isEmpty()) {
                        measured = text.layout(cell.text(), fonts, cellWidth, style);
                    } else {
                        var spans = new ArrayList<StyledSpan>(cell.inlines().size());
                        if (cell.inlines().isEmpty()) {
                            spans.add(new StyledSpan(cell.text(), fonts, format.fontSize(), -1, 0, false, false));
                        }
                        for (var inline : cell.inlines()) {
                            ExecutionContext.checkpoint();
                            var ink = inline.style();
                            var group = ink.fontRole().isEmpty() ? fonts : roles.get(ink.fontRole());
                            spans.add(new StyledSpan(inline.text(), group, ink.fontSize(), ink.rgb(),
                                    ink.baselineShift(), ink.underline(), ink.strikeThrough()));
                        }
                        measured = text.layout(spans, cellWidth, style, cell.images().stream().collect(
                                java.util.stream.Collectors.toMap(com.mirakyux.export4j.model.Paragraph.ImageSpan::sourceIndex,
                                        com.mirakyux.export4j.model.Paragraph.ImageSpan::image)), cell.fields().stream().collect(
                                java.util.stream.Collectors.toMap(com.mirakyux.export4j.model.Paragraph.FieldSpan::sourceIndex,
                                        com.mirakyux.export4j.model.Paragraph.FieldSpan::field)));
                    }
                } catch (TextLayoutException cause) {
                    var code = cause.code() == TextLayoutException.Code.BUDGET_EXCEEDED
                            ? DocumentLayoutException.Code.BUDGET_EXCEEDED : DocumentLayoutException.Code.TEXT_LAYOUT_FAILED;
                    throw failure(code, rowIndex, column, cause.sourceIndex(), "streaming cell text layout failed", cause);
                } catch (IllegalArgumentException cause) {
                    throw failure(DocumentLayoutException.Code.TEXT_LAYOUT_FAILED, rowIndex, column, -1,
                            "streaming cell geometry is invalid", cause);
                }
                ExecutionContext.checkpoint();
                double cellHeight = measured.height() + table.padding().top() + table.padding().bottom();
                if (!Double.isFinite(cellHeight) || cellHeight <= 0) {
                    throw failure(DocumentLayoutException.Code.INVALID_GEOMETRY, rowIndex, column, -1,
                            "streaming cell height is invalid", null);
                }
                if (cell.rowSpan() == 1) { height = Math.max(height, cellHeight); }
                boolean linked = cell.inlines().stream().anyMatch(inline -> !inline.hyperlink().isEmpty())
                        || cell.images().stream().anyMatch(image -> !image.image().hyperlink().isEmpty());
                cells.add(new MeasuredCell(column, columnWidth, measured, linked ? cell : null,
                        format.lineHeight(), 0, cell.rowSpan(), cellHeight));
            }
            if (!Double.isFinite(height) || height <= 0 && coveredUntil == null) {
                throw failure(DocumentLayoutException.Code.INVALID_GEOMETRY, rowIndex, -1, -1, "streaming row height is invalid", null);
            }
            return new MeasuredRow(rowIndex, height, List.copyOf(cells));
        }

        /** Reads exactly one connected group, never its following independent row. */
        private List<MeasuredRow> readGroup(TableRow first) throws IOException {
            int start = nextRowIndex();
            int[] coveredUntil = new int[table.columnWidths().size()];
            var rows = new ArrayList<MeasuredRow>();
            groupStartCodePoints = sourceCodePoints;
            var row = first;
            while (true) {
                ExecutionContext.checkpoint();
                rows.add(measure(row, nextRowIndex(), coveredUntil, start));
                bodyRows++;
                int end = nextRowIndex();
                for (int value : coveredUntil) { end = Math.max(end, value); }
                if (end == nextRowIndex()) { return resolveGroup(rows); }
                var next = source.next();
                ExecutionContext.checkpoint();
                if (next == null || next.isEmpty()) {
                    throw failure(DocumentLayoutException.Code.INVALID_GEOMETRY, nextRowIndex(), -1, -1,
                            "streaming source ended inside a vertical span", null);
                }
                row = next.orElseThrow();
            }
        }

        private List<MeasuredRow> resolveGroup(List<MeasuredRow> rows) {
            double[] heights = rows.stream().mapToDouble(MeasuredRow::height).toArray();
            record Constraint(int row, int column, int span, double height) { }
            var constraints = new ArrayList<Constraint>();
            for (int row = 0; row < rows.size(); row++) {
                for (var cell : rows.get(row).cells()) {
                    if (cell.rowSpan() > 1) { constraints.add(new Constraint(row, cell.column(), cell.rowSpan(), cell.height())); }
                }
            }
            constraints.sort(java.util.Comparator.comparingInt(Constraint::span)
                    .thenComparingInt(Constraint::row).thenComparingInt(Constraint::column));
            for (var constraint : constraints) {
                ExecutionContext.checkpoint();
                double currentHeight = 0;
                for (int row = constraint.row(); row < constraint.row() + constraint.span(); row++) { currentHeight += heights[row]; }
                if (currentHeight < constraint.height()) {
                    double increment = (constraint.height() - currentHeight) / constraint.span();
                    for (int row = constraint.row(); row < constraint.row() + constraint.span(); row++) { heights[row] += increment; }
                    double actual = 0;
                    for (int row = constraint.row(); row < constraint.row() + constraint.span(); row++) { actual += heights[row]; }
                    if (actual < constraint.height()) { heights[constraint.row() + constraint.span() - 1] += constraint.height() - actual; }
                }
            }
            var resolved = new ArrayList<MeasuredRow>(rows.size());
            for (int row = 0; row < rows.size(); row++) {
                var original = rows.get(row);
                var cells = new ArrayList<MeasuredCell>(original.cells().size());
                for (var cell : original.cells()) {
                    double cellHeight = 0;
                    for (int covered = row; covered < row + cell.rowSpan(); covered++) { cellHeight += heights[covered]; }
                    cells.add(new MeasuredCell(cell.column(), cell.width(), cell.layout(), cell.source(),
                            cell.step(), cell.firstLine(), cell.rowSpan(), cellHeight));
                }
                resolved.add(new MeasuredRow(original.index(), heights[row], List.copyOf(cells)));
            }
            return List.copyOf(resolved);
        }

        private void placeGroup(List<MeasuredRow> group) throws IOException {
            double height = group.stream().mapToDouble(MeasuredRow::height).sum();
            if (!fits(height, bounds.height() - headerHeight)) {
                throw failure(DocumentLayoutException.Code.CONTENT_TOO_TALL, group.getFirst().index(), -1, -1,
                        "streaming connected row group cannot fit with repeated headers", null);
            }
            if (!fits(height, bounds.height() - used)) { emit(); pageIndex++; beginPage(); }
            for (var row : group) {
                if (!row.cells().isEmpty()) { current.add(place(row, used, false)); }
                used += row.height();
            }
            ExecutionContext.checkpoint();
        }

        private void splitBody(MeasuredRow row) throws IOException {
            int[] consumed = new int[row.cells().size()];
            boolean continued = false;
            while (true) {
                ExecutionContext.checkpoint();
                double available = bounds.height() - used;
                double content = available - table.padding().top() - table.padding().bottom();
                var cells = new ArrayList<MeasuredCell>();
                int[] taken = new int[consumed.length];
                boolean complete = true;
                double height = 0;
                for (int index = 0; index < consumed.length; index++) {
                    var cell = row.cells().get(index);
                    int remaining = cell.layout().lines().size() - consumed[index];
                    if (!fits(cell.step(), content)) { cells.clear(); break; }
                    int count = (int) Math.min(remaining, Math.floor(Math.nextUp(content / cell.step())));
                    ParagraphLayout layout;
                    if (count == 0) {
                        int end = cell.layout().lines().getLast().sourceEnd();
                        layout = new ParagraphLayout(cell.layout().width(), cell.step(), List.of(
                                new com.mirakyux.export4j.text.TextLine(end, end, 0,
                                        cell.layout().lines().getFirst().baselineY(), 0, List.of())), List.of());
                    } else {
                        layout = DocumentLayouter.fragment(cell.layout(), consumed[index], count, cell.step());
                    }
                    height = Math.max(height, layout.height());
                    taken[index] = count;
                    complete &= count == remaining;
                    cells.add(new MeasuredCell(cell.column(), cell.width(), layout, cell.source(), cell.step(), consumed[index]));
                }
                height += table.padding().top() + table.padding().bottom();
                if (cells.isEmpty() || !fits(height, available)) {
                    if (used == headerHeight) {
                        throw failure(DocumentLayoutException.Code.CONTENT_TOO_TALL, row.index(), -1, -1,
                                "streaming headers and next complete cell line cannot fit", null);
                    }
                } else {
                    current.add(place(new MeasuredRow(row.index(), height, List.copyOf(cells), continued), used, false));
                    used += height;
                    for (int index = 0; index < consumed.length; index++) { consumed[index] += taken[index]; }
                    if (complete) { return; }
                    continued = true;
                }
                emit(); pageIndex++; beginPage();
            }
        }

        private void beginPage() {
            ExecutionContext.checkpoint();
            if (pageIndex >= limits.document().maxPages()) {
                throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, -1, -1, -1, "streaming page budget exceeded", null);
            }
            if (table.expectedPages() > 0 && pageIndex >= table.expectedPages()) {
                throw failure(DocumentLayoutException.Code.INVALID_GEOMETRY, -1, -1, -1,
                        "actual streaming page count exceeds declared total", null);
            }
            current.clear(); currentLinks.clear(); used = 0;
            for (var header : headers) {
                current.add(place(header, used, pageIndex > 0));
                used += header.height();
            }
        }

        private TableRowFragment place(MeasuredRow row, double y, boolean repeated) {
            var cells = new ArrayList<CellFragment>();
            for (var cell : row.cells()) {
                double x = columnOffsets[cell.column()];
                var layout = resolveFields(cell.layout(), cell.step(), row.index(), cell.column());
                if (cell.source() != null) {
                    var owner = cell.source();
                    InlineLinkGeometry.collect(owner.text(), owner.inlines(), owner.format().lineHeight(),
                            new Point(bounds.x() + x + table.padding().left(), bounds.y() + y + table.padding().top()),
                            layout, pageIndex, table.pageSettings().size().width(),
                            (link, offset) -> appendLink(link, row.index(), cell.column(), offset));
                }
                double cellHeight = table.allowRowSpans() && row.index() >= table.headerRows().size()
                        ? cell.height() : row.height();
                cells.add(new CellFragment(cell.column(), new Rect(x, y, cell.width(), cellHeight),
                        new Point(x + table.padding().left(), y + table.padding().top()), layout, cell.firstLine()));
            }
            return new TableRowFragment(row.index(), row.index() < headers.size(), repeated, cells, row.continued());
        }

        private ParagraphLayout resolveFields(ParagraphLayout source, double step, int row, int column) {
            if (source.lines().stream().allMatch(line -> line.fields().isEmpty())) { return source; }
            var lines = new ArrayList<com.mirakyux.export4j.text.TextLine>();
            for (var line : source.lines()) {
                if (line.fields().isEmpty()) { lines.add(line); continue; }
                var ink = new ArrayList<>(line.outlines());
                for (var box : line.fields()) {
                    ExecutionContext.checkpoint();
                    if (outputFields == limits.maxFields()) {
                        throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, row, column, box.sourceIndex(),
                                "streaming field presentation budget exceeded", null);
                    }
                    outputFields++;
                    String value = Integer.toString(box.field().kind() == InlineField.Kind.PAGE ? pageIndex + 1 : table.expectedPages());
                    if (value.length() > limits.maxGeneratedCodePoints() - generatedCodePoints) {
                        throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, row, column, box.sourceIndex(),
                                "streaming generated field value budget exceeded", null);
                    }
                    generatedCodePoints += value.length();
                    var style = box.field().style();
                    var group = style.fontRole().isEmpty() ? fonts : roles.get(style.fontRole());
                    InlineFieldInk.Resolved resolved;
                    try {
                        if (group == null) { throw new IllegalArgumentException("unregistered field font role"); }
                        resolved = InlineFieldInk.resolve(box, value, step, group, text);
                    } catch (IllegalArgumentException cause) {
                        throw failure(DocumentLayoutException.Code.TEXT_LAYOUT_FAILED, row, column, box.sourceIndex(),
                                "streaming field resolution failed", cause);
                    }
                    ink.set(box.outlineIndex(), resolved.outline());
                    for (var diagnostic : resolved.diagnostics()) {
                        if (diagnosticCount == limits.maxDiagnostics()) {
                            throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, row, column, box.sourceIndex(),
                                    "streaming diagnostic budget exceeded", null);
                        }
                        diagnosticCount++;
                        diagnostics.accept(new StreamingTableDiagnostic(table.id(), pageIndex, row, column, diagnostic,
                                "INLINE_FIELD:" + box.sourceIndex()));
                        ExecutionContext.checkpoint();
                    }
                }
                lines.add(new com.mirakyux.export4j.text.TextLine(line.sourceStart(), line.sourceEnd(), line.offsetX(),
                        line.baselineY(), line.width(), line.runs(), ink, line.images()));
            }
            return new ParagraphLayout(source.width(), source.height(), lines, source.diagnostics());
        }

        private void appendLink(DocumentExtras.ExternalLink link, int row, int column, int offset) {
            if (!currentLinks.isEmpty()) {
                var previous = currentLinks.getLast();
                var a = previous.bounds(); var b = link.bounds();
                if (previous.destination().equals(link.destination()) && a.y() == b.y() && a.height() == b.height()
                        && Math.abs(a.x() + a.width() - b.x()) < 1e-8) {
                    currentLinks.set(currentLinks.size() - 1, new DocumentExtras.ExternalLink(pageIndex,
                            new Rect(a.x(), a.y(), b.x() + b.width() - a.x(), a.height()), link.destination()));
                    return;
                }
            }
            if (outputLinks == limits.maxLinks()) {
                throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, row, column, offset,
                        "streaming output link budget exceeded, including repeated headers", null);
            }
            outputLinks++;
            currentLinks.add(link);
        }

        private void emit() throws IOException {
            ExecutionContext.checkpoint();
            for (var row : current) {
                for (var cell : row.cells()) {
                    if (cell.layout().lines().size() > limits.document().maxLines() - outputLines) {
                        throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, row.rowIndex(), cell.columnIndex(), -1,
                                "streaming output line budget exceeded, including repeated headers", null);
                    }
                    outputLines += cell.layout().lines().size();
                    for (var diagnostic : cell.layout().diagnostics()) {
                        ExecutionContext.checkpoint();
                        if (diagnosticCount == limits.maxDiagnostics()) {
                            throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, row.rowIndex(), cell.columnIndex(), diagnostic.sourceStart(),
                                    "streaming diagnostic budget exceeded", null);
                        }
                        diagnosticCount++;
                        diagnostics.accept(new StreamingTableDiagnostic(table.id(), pageIndex, row.rowIndex(), cell.columnIndex(), diagnostic));
                        ExecutionContext.checkpoint();
                    }
                }
            }
            List<TableFragment> elements = current.isEmpty() ? List.of() : List.of(new TableFragment(table.id(), 0,
                    new Point(bounds.x(), bounds.y()), width, used, current));
            var page = new LayoutPage(pageIndex, table.pageSettings().size(), bounds, elements);
            ExecutionContext.progress("STREAMING_PAGE", table.id(), pageIndex, pageIndex, -1);
            pages.accept(page, List.copyOf(currentLinks));
            ExecutionContext.checkpoint();
        }

        private DocumentLayoutException failure(DocumentLayoutException.Code code, int row, int column,
                int offset, String message, Throwable cause) {
            return new DocumentLayoutException(code, table.id(), 0, pageIndex, row, column, offset, message, cause);
        }

        private static boolean fits(double height, double available) {
            // Match ordinary pagination: absorb one arithmetic ULP, not visible overflow.
            return Double.isFinite(height) && height <= Math.nextUp(available);
        }
    }
}
