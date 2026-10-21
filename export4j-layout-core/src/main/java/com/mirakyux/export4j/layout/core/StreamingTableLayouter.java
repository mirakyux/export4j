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
import com.mirakyux.export4j.layout.CellFragment;
import com.mirakyux.export4j.layout.LayoutPage;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.layout.TableRowFragment;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.StreamingTable;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.TableRowSource;
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
 * Plain and pure rich-text cells share text layout. No hyperlinks, images, fields, block cells,
 * merges, row splitting, global references, page templates or automatic widths.
 */
public final class StreamingTableLayouter {
    /** Receives one immutable page at a time, exactly once in increasing physical page order.
     * A failure stops consumption immediately; the layouter does not retry or close this consumer.
     */
    @FunctionalInterface
    public interface PageConsumer { void accept(LayoutPage page) throws IOException; }

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

    /** Lays out plain or pure rich-text cells with explicit default fonts and named font groups.
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
        var roles = Map.copyOf(Objects.requireNonNull(fontRoles, "fontRoles"));
        for (var name : roles.keySet()) {
            ExecutionContext.checkpoint();
            if (name.isBlank()) { throw new IllegalArgumentException("font role is blank"); }
        }
        return new Run(Objects.requireNonNull(table, "table"), Objects.requireNonNull(source, "source"),
                Objects.requireNonNull(fonts, "fonts"), roles, Objects.requireNonNull(pages, "pages"),
                Objects.requireNonNull(diagnostics, "diagnostics"), Objects.requireNonNull(limits, "limits")).layout();
    }

    private record MeasuredRow(int index, double height, List<ParagraphLayout> cells) { }

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
        private final Rect bounds;
        private final double width;
        private double headerHeight;
        private double used;
        private long bodyRows;
        private int pageIndex;
        private long sourceCells;
        private long sourceCodePoints;
        private long outputLines;
        private long diagnosticCount;

        Run(StreamingTable table, TableRowSource source, FontGroup fonts, Map<String, FontGroup> roles, PageConsumer pages,
                Consumer<StreamingTableDiagnostic> diagnostics, StreamingTableLimits limits) {
            this.table = table; this.source = source; this.fonts = fonts; this.pages = pages;
            this.roles = roles;
            this.diagnostics = diagnostics; this.limits = limits;
            var settings = table.pageSettings();
            bounds = new Rect(settings.margins().left(), settings.margins().top(), settings.contentWidth(), settings.contentHeight());
            width = table.columnWidths().stream().mapToDouble(Double::doubleValue).sum();
        }

        Summary layout() throws IOException {
            ExecutionContext.progress("STREAMING_TABLE", table.id(), 0, 0, -1);
            if (table.columnWidths().size() > limits.document().maxTableColumns()) {
                throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, -1, -1, -1, "streaming column budget exceeded", null);
            }
            if (table.headerRows().size() > limits.document().maxTableRows()) {
                throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, -1, -1, -1, "streaming row budget exceeded", null);
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
                var measured = measure(next.orElseThrow(), nextRowIndex());
                bodyRows++;
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
            emit();
            ExecutionContext.progress("STREAMING_TABLE_DONE", table.id(), pageIndex, bodyRows, bodyRows);
            return new Summary(bodyRows, pageIndex + 1, sourceCells, sourceCodePoints, outputLines, diagnosticCount);
        }

        private int nextRowIndex() {
            return Math.toIntExact(table.headerRows().size() + bodyRows);
        }

        private MeasuredRow measure(TableRow row, int rowIndex) {
            ExecutionContext.checkpoint();
            if (rowIndex >= limits.document().maxTableRows()) {
                throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, rowIndex, -1, -1, "streaming source row budget exceeded", null);
            }
            if (row.cells().size() != table.columnWidths().size()) {
                throw failure(DocumentLayoutException.Code.INVALID_GEOMETRY, rowIndex, -1, -1, "streaming row does not match column count", null);
            }
            long rowPoints = 0;
            int rowInlines = 0;
            for (int column = 0; column < row.cells().size(); column++) {
                ExecutionContext.checkpoint();
                var cell = row.cells().get(column);
                if (cell.hasBlocks()) {
                    throw failure(DocumentLayoutException.Code.UNSUPPORTED_COMPONENT, rowIndex, column, -1,
                            "streaming tables do not support semantic block cells", null);
                }
                if (cell.columnSpan() != 1 || cell.rowSpan() != 1) {
                    throw failure(DocumentLayoutException.Code.UNSUPPORTED_COMPONENT, rowIndex, column, -1,
                            "streaming tables require unmerged cells", null);
                }
                if (!cell.images().isEmpty() || !cell.fields().isEmpty()) {
                    throw failure(DocumentLayoutException.Code.UNSUPPORTED_COMPONENT, rowIndex, column, -1,
                            "streaming tables do not support images or fields", null);
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
                        if (!inline.hyperlink().isEmpty()) {
                            throw failure(DocumentLayoutException.Code.UNSUPPORTED_COMPONENT, rowIndex, column, offset,
                                    "streaming tables do not support text hyperlinks", null);
                        }
                        String role = inline.style().fontRole();
                        if (!role.isEmpty() && !roles.containsKey(role)) {
                            throw failure(DocumentLayoutException.Code.TEXT_LAYOUT_FAILED, rowIndex, column, offset,
                                    "streaming cell font role is unregistered", null);
                        }
                        offset += inline.text().length();
                    }
                }
                if (sourceCells == limits.document().maxCells()) {
                    throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, rowIndex, column, -1, "streaming source cell budget exceeded", null);
                }
                sourceCells++;
                int cellPoints = 0;
                for (int offset = 0; offset < cell.text().length();) {
                    if ((cellPoints & 1023) == 0) { ExecutionContext.checkpoint(); }
                    if (cellPoints == limits.maxCellCodePoints() || rowPoints == limits.maxRowCodePoints()
                            || sourceCodePoints == limits.document().maxCodePoints()) {
                        throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, rowIndex, column, offset,
                                "streaming source code point budget exceeded", null);
                    }
                    offset += Character.charCount(cell.text().codePointAt(offset));
                    cellPoints++; rowPoints++; sourceCodePoints++;
                }
            }
            var cells = new ArrayList<ParagraphLayout>();
            double height = 0;
            for (int column = 0; column < row.cells().size(); column++) {
                ExecutionContext.checkpoint();
                var cell = row.cells().get(column);
                var format = cell.format();
                var style = new ParagraphStyle(format.fontSize(), format.lineHeight(), 0, 0,
                        TextAlignment.valueOf(format.alignment().name()), format.lineBreakPolicy());
                ParagraphLayout measured;
                try {
                    double cellWidth = table.columnWidths().get(column) - table.padding().left() - table.padding().right();
                    if (cell.inlines().isEmpty()) {
                        measured = text.layout(cell.text(), fonts, cellWidth, style);
                    } else {
                        var spans = new ArrayList<StyledSpan>(cell.inlines().size());
                        for (var inline : cell.inlines()) {
                            ExecutionContext.checkpoint();
                            var ink = inline.style();
                            var group = ink.fontRole().isEmpty() ? fonts : roles.get(ink.fontRole());
                            spans.add(new StyledSpan(inline.text(), group, ink.fontSize(), ink.rgb(),
                                    ink.baselineShift(), ink.underline(), ink.strikeThrough()));
                        }
                        measured = text.layout(spans, cellWidth, style);
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
                height = Math.max(height, measured.height());
                cells.add(measured);
            }
            height += table.padding().top() + table.padding().bottom();
            if (!Double.isFinite(height) || height <= 0) {
                throw failure(DocumentLayoutException.Code.INVALID_GEOMETRY, rowIndex, -1, -1, "streaming row height is invalid", null);
            }
            return new MeasuredRow(rowIndex, height, List.copyOf(cells));
        }

        private void beginPage() {
            ExecutionContext.checkpoint();
            if (pageIndex >= limits.document().maxPages()) {
                throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, -1, -1, -1, "streaming page budget exceeded", null);
            }
            current.clear(); used = 0;
            for (var header : headers) {
                current.add(place(header, used, pageIndex > 0));
                used += header.height();
            }
        }

        private TableRowFragment place(MeasuredRow row, double y, boolean repeated) {
            var cells = new ArrayList<CellFragment>();
            double x = 0;
            for (int column = 0; column < row.cells().size(); column++) {
                double columnWidth = table.columnWidths().get(column);
                cells.add(new CellFragment(column, new Rect(x, y, columnWidth, row.height()),
                        new Point(x + table.padding().left(), y + table.padding().top()), row.cells().get(column)));
                x += columnWidth;
            }
            return new TableRowFragment(row.index(), row.index() < headers.size(), repeated, cells);
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
            pages.accept(page);
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
