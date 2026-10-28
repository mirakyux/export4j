// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.layout.CellFragment;
import com.mirakyux.export4j.layout.LayoutPage;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.layout.TableRowFragment;
import com.mirakyux.export4j.model.*;
import com.mirakyux.export4j.text.ParagraphLayouter;

/** Bounded semantic-table stream. Retains one connected source group, finite headers and one page.
 * Recursive cells use the ordinary fixed-width table cursor, preserving complete lines, field slots
 * and original child ordinals. Fields remain unresolved for the explicit final-page replay layer.
 * Source and consumer are borrowed and synchronous. No source is reopened by this class.
 */
public final class SemanticStreamingTableLayouter {
    @FunctionalInterface public interface PageConsumer {
        void accept(LayoutPage page, List<Long> footnoteGroups, Map<CellKey, TableCell> sources) throws IOException;
    }
    /** Original outer grid owner; child paragraphs keep their own IDs and source block ordinals. */
    public record CellKey(int rowIndex, int columnIndex) { }
    public record Summary(long bodyRows, int pages, long sourceCells, long sourceCodePoints, long outputLines) { }

    /** Footnote heights are explicitly declared against the first body row of a connected group.
     * They are reserved on that group's first presentation; a declaration inside a vertical group
     * fails rather than moving a note to an unrelated source row. Values use points.
     */
    public Summary layout(StreamingTable table, TableRowSource source, FontGroup fonts,
            Map<String, FontGroup> roles, StreamingTableLimits limits, Map<Long, Double> footnoteHeights,
            PageConsumer consumer) throws IOException {
        return new Run(table, source, fonts, Map.copyOf(roles), limits, Map.copyOf(footnoteHeights), consumer).layout();
    }

    private static final class Run {
        private final StreamingTable table;
        private final TableRowSource source;
        private final FontGroup fonts;
        private final Map<String, FontGroup> roles;
        private final StreamingTableLimits limits;
        private final Map<Long, Double> noteHeights;
        private final PageConsumer consumer;
        private final DocumentLayouter layouter;
        private final double width;
        private final Rect bounds;
        private final List<TableRowFragment> current = new ArrayList<>();
        private final List<Long> currentNotes = new ArrayList<>();
        private final Map<CellKey, TableCell> pageSources = new java.util.HashMap<>();
        private final Map<CellKey, TableCell> groupSources = new java.util.HashMap<>();
        private final Map<CellKey, TableCell> headerSources = new java.util.HashMap<>();
        private double headerHeight;
        private double used;
        private double notesHeight;
        private long bodyRows;
        private long cells;
        private long slots;
        private long points;
        private long nodes = 1;
        private long lines;
        private final java.util.Set<String> sourceIds = new java.util.HashSet<>();
        private int page;
        private boolean pageHasBody;

        private Run(StreamingTable table, TableRowSource source, FontGroup fonts, Map<String, FontGroup> roles,
                StreamingTableLimits limits, Map<Long, Double> notes, PageConsumer consumer) {
            this.table = Objects.requireNonNull(table, "table"); this.source = Objects.requireNonNull(source, "source");
            this.fonts = Objects.requireNonNull(fonts, "fonts"); this.roles = roles; this.limits = Objects.requireNonNull(limits, "limits");
            sourceIds.add(table.id());
            noteHeights = notes; this.consumer = Objects.requireNonNull(consumer, "consumer");
            width = table.columnWidths().stream().mapToDouble(Double::doubleValue).sum();
            var settings = table.pageSettings();
            bounds = new Rect(settings.margins().left(), settings.margins().top(), settings.contentWidth(), settings.contentHeight());
            layouter = new DocumentLayouter(new ParagraphLayouter(), limits.document());
            if (table.columnWidths().size() > limits.document().maxTableColumns()) { throw fail("streaming column budget exceeded", -1); }
            for (var entry : notes.entrySet()) {
                if (entry.getKey() < 0 || !Double.isFinite(entry.getValue()) || entry.getValue() <= 0) {
                    throw new IllegalArgumentException("invalid streaming footnote reservation");
                }
            }
        }

        private Summary layout() throws IOException {
            for (int index = 0; index < table.headerRows().size(); index++) {
                var row = table.headerRows().get(index); countRow(row, index); int column = 0;
                for (var cell : row.cells()) { headerSources.put(new CellKey(index, column), cell); column += cell.columnSpan(); }
            }
            var blank = new TableRow(java.util.stream.IntStream.range(0, table.columnWidths().size())
                    .mapToObj(ignored -> new TableCell("", CellFormat.of(1))).toList());
            var measured = cursor(List.of(blank), 0).take(bounds.height()).orElseThrow(() -> tooTall("streaming headers exceed viewport", 0));
            var header = measured.rows().stream().filter(TableRowFragment::header).toList();
            headerHeight = header.stream().flatMap(row -> row.cells().stream())
                    .mapToDouble(cell -> cell.bounds().y() + cell.bounds().height()).max().orElse(0);
            while (true) {
                ExecutionContext.checkpoint();
                var next = Objects.requireNonNull(source.next(), "row source returned null");
                if (next.isEmpty()) { break; }
                long start = bodyRows;
                var group = readGroup(next.orElseThrow());
                double addedNote = noteHeights.getOrDefault(start, 0.0);
                for (long row = start + 1; row < bodyRows; row++) {
                    if (noteHeights.containsKey(row)) { throw new IllegalArgumentException("streaming footnotes require a connected group start"); }
                }
                var cursor = cursor(group, start);
                boolean first = true;
                while (!cursor.done()) {
                    double note = first ? addedNote : 0;
                    double available = bounds.height() - used - notesHeight - note + (pageHasBody ? headerHeight : 0);
                    if (note + headerHeight >= bounds.height()) { throw tooTall("streaming footnote leaves no body viewport", Math.toIntExact(start)); }
                    java.util.Optional<TableFragment> taken;
                    try { taken = available <= 0 ? java.util.Optional.empty() : cursor.take(available); }
                    catch (DocumentLayoutException failure) { throw remap(failure, start); }
                    if (taken.isEmpty()) {
                        if (!pageHasBody) { throw tooTall("streaming semantic group cannot fit", Math.toIntExact(start)); }
                        emit(); nextPage(); continue;
                    }
                    var fragment = taken.orElseThrow();
                    pageSources.putAll(headerSources); pageSources.putAll(groupSources);
                    double remove = pageHasBody ? headerHeight : 0;
                    double shift = used - remove;
                    for (var row : fragment.rows()) {
                        if (row.header() && pageHasBody) { continue; }
                        int sourceRow = row.header() ? row.rowIndex() : Math.toIntExact(start + row.rowIndex());
                        var mapped = new ArrayList<CellFragment>();
                        for (var cell : row.cells()) {
                            var b = cell.bounds(); var o = cell.textOrigin();
                            mapped.add(new CellFragment(cell.columnIndex(), new Rect(b.x(), b.y() + shift, b.width(), b.height()),
                                    new Point(o.x(), o.y() + shift), cell.content()));
                        }
                        current.add(new TableRowFragment(sourceRow, row.header(), row.header() && page > 0, mapped, row.continuedRow()));
                    }
                    used += fragment.height() - remove;
                    pageHasBody = true;
                    if (first && note > 0) { notesHeight += note; currentNotes.add(start); }
                    first = false;
                    if (!cursor.done()) { emit(); nextPage(); }
                }
                ExecutionContext.progress("STREAMING_SEMANTIC_ROWS", table.id(), page, bodyRows, -1);
            }
            for (long anchor : noteHeights.keySet()) {
                if (anchor >= bodyRows) { throw new IllegalArgumentException("streaming footnote source row missing"); }
            }
            if (!pageHasBody && !header.isEmpty()) { current.addAll(header); used = headerHeight; pageSources.putAll(headerSources); }
            if (table.expectedPages() > 0 && table.expectedPages() != page + 1) {
                throw new IllegalArgumentException("streaming page count differs from declared total");
            }
            emit();
            return new Summary(bodyRows, page + 1, cells, points, lines);
        }

        private TableCursor cursor(List<TableRow> body, long start) {
            var rows = new ArrayList<>(table.headerRows()); rows.addAll(body);
            var semantic = new Table(table.id(), table.columnWidths().stream().<ColumnWidth>map(ColumnWidth.Fixed::new).toList(),
                    rows, table.headerRows().size(), new TableFormat(table.padding(), 0, 0, table.allowRowSplit()));
            try { return layouter.prepareTable(semantic, new ComponentContext(width, bounds.height(), fonts, 0, 0, 1, roles)); }
            catch (DocumentLayoutException failure) { throw remap(failure, start); }
        }

        private DocumentLayoutException remap(DocumentLayoutException failure, long start) {
            var path = new ArrayList<CellLocation>(failure.sourcePath());
            for (int index = 0; index < path.size(); index++) {
                var cell = path.get(index);
                if (cell.tableId().equals(table.id()) && cell.rowIndex() >= table.headerRows().size()) {
                    path.set(index, new CellLocation(cell.tableId(), Math.toIntExact(start + cell.rowIndex()), cell.columnIndex(), cell.blockIndex()));
                    break;
                }
            }
            int row = failure.rowIndex();
            if (failure.blockId().equals(table.id()) && row >= table.headerRows().size()) { row = Math.toIntExact(start + row); }
            return new DocumentLayoutException(failure.code(), failure.blockId(), failure.blockIndex(), page,
                    row, failure.columnIndex(), failure.sourceIndex(), path, failure.getMessage(), failure.getCause());
        }

        private List<TableRow> readGroup(TableRow first) throws IOException {
            groupSources.clear();
            var rows = new ArrayList<TableRow>();
            int[] until = new int[table.columnWidths().size()];
            int required = 1;
            long before = points;
            while (rows.size() < required) {
                if (rows.size() == limits.maxBufferedRows()) { throw fail("streaming buffered row budget exceeded", rowIndex()); }
                TableRow row = rows.isEmpty() ? first : Objects.requireNonNull(source.next(), "row source returned null")
                        .orElseThrow(() -> geometry("streaming source ended within vertical span", rowIndex(), -1));
                int relative = rows.size(); int column = 0;
                for (var cell : row.cells()) {
                    while (column < until.length && until[column] > relative) { column++; }
                    if (cell.rowSpan() > 1 && !table.allowRowSpans()) { throw new DocumentLayoutException(DocumentLayoutException.Code.UNSUPPORTED_COMPONENT,
                            table.id(), 0, page, rowIndex(), column, -1, "streaming vertical spans require allowRowSpans", null); }
                    if (cell.columnSpan() > until.length - column) { throw geometry("streaming group exceeds column grid", rowIndex(), column); }
                    if ((long) relative + cell.rowSpan() > limits.maxBufferedRows()) { throw fail("streaming connected group budget exceeded", rowIndex()); }
                    for (int covered = column; covered < column + cell.columnSpan(); covered++) {
                        if (until[covered] > relative) { throw geometry("streaming vertical spans overlap", rowIndex(), column); }
                        until[covered] = relative + cell.rowSpan();
                    }
                    groupSources.put(new CellKey(rowIndex(), column), cell);
                    required = Math.max(required, relative + cell.rowSpan()); column += cell.columnSpan();
                }
                while (column < until.length && until[column] > relative) { column++; }
                if (column != until.length) { throw geometry("streaming group does not cover all columns", rowIndex(), column); }
                countRow(row, rowIndex()); rows.add(row); bodyRows++;
                if (points - before > limits.maxBufferedCodePoints()) { throw fail("streaming buffered text budget exceeded", rowIndex() - 1); }
            }
            return List.copyOf(rows);
        }

        private int rowIndex() { return Math.toIntExact(table.headerRows().size() + bodyRows); }

        private void countRow(TableRow row, int index) {
            if (index >= limits.document().maxTableRows()) { throw fail("streaming source row budget exceeded", index); }
            long before = points; long[] runs = {0};
            for (var cell : row.cells()) { countCell(cell, index, runs); }
            if (points - before > limits.maxRowCodePoints() || runs[0] > limits.maxRowInlines()) { throw fail("streaming source row budget exceeded", index); }
        }

        private void countCell(TableCell cell, int row, long[] runs) {
            ExecutionContext.checkpoint();
            long coverage = (long) cell.columnSpan() * cell.rowSpan();
            if (coverage > limits.document().maxCells() - slots) { throw fail("streaming source cell budget exceeded", row); }
            slots += coverage; cells++;
            if (cell.hasBlocks()) {
                for (var block : cell.blocks()) { countBlock(block, row, runs); }
            } else { countText(cell.text(), cell.inlines().size(), row, runs); }
        }

        private void countBlock(Block block, int row, long[] runs) {
            ExecutionContext.checkpoint();
            if (++nodes > limits.document().maxParagraphs()) { throw fail("streaming semantic node budget exceeded", row); }
            if (!sourceIds.add(block.id())) { throw new IllegalArgumentException("streaming semantic block IDs must be unique"); }
            if (block instanceof Paragraph paragraph) { countText(paragraph.text(), paragraph.inlines().size(), row, runs); }
            else if (block instanceof Table nested) {
                if (nested.rows().size() > limits.document().maxTableRows() || nested.columns().size() > limits.document().maxTableColumns()) {
                    throw fail("streaming nested grid budget exceeded", row);
                }
                for (var nestedRow : nested.rows()) { for (var cell : nestedRow.cells()) { countCell(cell, row, runs); } }
            } else { throw new IllegalArgumentException("unsupported streaming cell block"); }
        }

        private void countText(String text, int explicitRuns, int row, long[] runs) {
            int count = text.codePointCount(0, text.length());
            if (count > limits.maxCellCodePoints() || count > limits.document().maxCodePoints() - points
                    || Math.max(1, explicitRuns) > limits.maxCellInlines()) { throw fail("streaming leaf text budget exceeded", row); }
            points += count; runs[0] += Math.max(1, explicitRuns);
        }

        private void emit() throws IOException {
            ExecutionContext.checkpoint();
            if (page >= limits.document().maxPages()) { throw fail("streaming page budget exceeded", -1); }
            long added = current.stream().flatMap(row -> row.cells().stream()).mapToLong(CellFragment::lineCount).sum();
            if (added > limits.document().maxLines() - lines) { throw fail("streaming output line budget exceeded", -1); }
            lines += added;
            var elements = current.isEmpty() ? List.<com.mirakyux.export4j.layout.LayoutFragment>of() :
                    List.<com.mirakyux.export4j.layout.LayoutFragment>of(new TableFragment(table.id(), 0,
                            new Point(bounds.x(), bounds.y()), width, used, current));
            consumer.accept(new LayoutPage(page, table.pageSettings().size(), bounds, elements), List.copyOf(currentNotes), Map.copyOf(pageSources));
            ExecutionContext.checkpoint();
        }
        private void nextPage() { page++; current.clear(); currentNotes.clear(); pageSources.clear(); used = 0; notesHeight = 0; pageHasBody = false; }
        private DocumentLayoutException fail(String message, int row) {
            return new DocumentLayoutException(DocumentLayoutException.Code.BUDGET_EXCEEDED, table.id(), 0, page, row, -1, -1, message, null);
        }
        private DocumentLayoutException tooTall(String message, int row) {
            return new DocumentLayoutException(DocumentLayoutException.Code.CONTENT_TOO_TALL, table.id(), 0, page, row, -1, -1, message, null);
        }
        private DocumentLayoutException geometry(String message, int row, int column) {
            return new DocumentLayoutException(DocumentLayoutException.Code.INVALID_GEOMETRY, table.id(), 0, page, row, column, -1, message, null);
        }
    }
}
