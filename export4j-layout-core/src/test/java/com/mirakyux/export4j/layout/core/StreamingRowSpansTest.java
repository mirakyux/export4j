// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.MissingGlyphPolicy;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.layout.LayoutPage;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.layout.TableRowFragment;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.StreamingTable;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.TableRowSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingRowSpansTest {
    private static final CellFormat FORMAT = CellFormat.of(10);
    private static FontGroup fonts;

    @BeforeAll static void fonts() throws IOException {
        try (var input = StreamingRowSpansTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            fonts = FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }

    private static StreamingTable table() {
        return StreamingTable.builder("groups").columns(50, 50).padding(0)
                .pageSettings(new PageSettings(new PageSize(120, 80), PageMargins.uniform(10)))
                .cellFormat(FORMAT).header("LEFT", "RIGHT").allowRowSpans(true).build();
    }
    private static TableCell cell(String value, int rows, int columns) {
        return TableCell.builder().text(value).format(FORMAT).rowSpan(rows).columnSpan(columns).build();
    }
    private static TableRow row(TableCell... cells) { return new TableRow(List.of(cells)); }
    private static TableRow row(String left, String right) { return row(cell(left, 1, 1), cell(right, 1, 1)); }
    private static List<TableRowFragment> rows(LayoutPage page) { return ((TableFragment) page.elements().getFirst()).rows(); }
    private static final class Source implements TableRowSource {
        private final List<TableRow> values;
        private int reads;
        private int closes;
        Source(TableRow... values) { this.values = List.of(values); }
        @Override public Optional<TableRow> next() {
            int index = reads++;
            return index < values.size() ? Optional.of(values.get(index)) : Optional.empty();
        }
        @Override public void close() { closes++; }
    }
    private static StreamingTableLayouter.Summary layout(StreamingTable table, Source source,
            List<LayoutPage> pages, StreamingTableLimits limits) throws IOException {
        return new StreamingTableLayouter().layout(table, source, fonts, pages::add, ignored -> { }, limits);
    }

    @Test void connectedGroupsMoveWholeRetainAnchorsAndNeverReadFollowingIndependentRow() throws IOException {
        var source = new Source(row("PRE-A", "A"), row("PRE-B", "B"),
                row(cell("OWNER", 2, 1), cell("B3", 1, 1)), row(cell("B4", 1, 1)),
                row("A5", "B5"), row(cell("FULL", 2, 2)), TableRow.covered());
        var pages = new ArrayList<LayoutPage>();
        var readCounts = new ArrayList<Integer>();
        var summary = new StreamingTableLayouter().layout(table(), source, fonts, page -> {
            readCounts.add(source.reads); pages.add(page);
        }, ignored -> { }, StreamingTableLimits.DEFAULT);
        assertEquals(3, summary.pages()); assertEquals(7, summary.bodyRows());
        assertEquals(12, summary.sourceCells()); assertEquals(16, summary.outputLines());
        assertEquals(List.of(4, 7, 8), readCounts); assertEquals(0, source.closes);
        assertEquals(List.of(1, 2), rows(pages.get(0)).stream().filter(value -> !value.header()).map(TableRowFragment::rowIndex).toList());
        assertEquals(List.of(3, 4, 5), rows(pages.get(1)).stream().filter(value -> !value.header()).map(TableRowFragment::rowIndex).toList());
        assertEquals(List.of(6), rows(pages.get(2)).stream().filter(value -> !value.header()).map(TableRowFragment::rowIndex).toList());
        var owner = rows(pages.get(1)).get(1).cells().getFirst();
        var covered = rows(pages.get(1)).get(2).cells().getFirst();
        assertEquals(30, owner.bounds().height()); assertEquals(1, covered.columnIndex());
        assertEquals(50, covered.bounds().x()); assertEquals(15, covered.bounds().height());
        var full = rows(pages.get(2)).get(1).cells().getFirst();
        assertEquals(100, full.bounds().width()); assertEquals(15, full.bounds().height());
        for (var page : pages) {
            assertEquals(page.pageIndex() > 0, rows(page).getFirst().repeatedHeader());
            assertSame(rows(pages.getFirst()).getFirst().cells().getFirst().layout(), rows(page).getFirst().cells().getFirst().layout());
        }
    }

    @Test void transitiveVerticalOwnersKeepThreeRowsConnectedAndChargeEveryPhysicalSlot() throws IOException {
        var source = new Source(row(cell("L", 2, 1), cell("R1", 1, 1)),
                row(cell("R2", 2, 1)), row(cell("L3", 1, 1)));
        var pages = new ArrayList<LayoutPage>();
        var summary = layout(table(), source, pages, StreamingTableLimits.DEFAULT);
        assertEquals(1, summary.pages()); assertEquals(3, summary.bodyRows()); assertEquals(6, summary.sourceCells());
        assertEquals(15, rows(pages.getFirst()).get(1).cells().getFirst().bounds().height());
        assertEquals(15, rows(pages.getFirst()).get(2).cells().getFirst().bounds().height());
        var limits = StreamingTableLimits.DEFAULT.toBuilder().document(new DocumentLayoutLimits(1, 1000, 100, 10, 10, 2, 7)).build();
        var failure = assertThrows(DocumentLayoutException.class, () -> layout(table(), new Source(
                row(cell("L", 2, 1), cell("R1", 1, 1)), row(cell("R2", 2, 1)), row(cell("L3", 1, 1))), new ArrayList<>(), limits));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, failure.code());
        assertEquals(3, failure.rowIndex()); assertEquals(0, failure.columnIndex());
    }

    @Test void unequalHeaderCellLineCountsStillUseUniformRepeatedHeaderRowHeight() throws IOException {
        var spec = table().toBuilder().headerRows(List.of(row("HEAD\nTWO", "R"))).build();
        var pages = new ArrayList<LayoutPage>();
        var summary = layout(spec, new Source(row("A", "B"), row("C", "D"), row("E", "F")),
                pages, StreamingTableLimits.DEFAULT);
        assertEquals(2, summary.pages()); assertEquals(12, summary.outputLines());
        for (var page : pages) {
            var header = rows(page).getFirst();
            assertEquals(List.of(30.0, 30.0), header.cells().stream().map(cell -> cell.bounds().height()).toList());
            assertEquals(page.pageIndex() > 0, header.repeatedHeader());
        }
    }

    @Test void verticalGroupsAreOptInAndDoNotAlterOldConstructors() {
        var ordinary = table().toBuilder().allowRowSpans(false).build();
        var failure = assertThrows(DocumentLayoutException.class, () -> layout(ordinary,
                new Source(row(cell("L", 2, 2)), TableRow.covered()), new ArrayList<>(), StreamingTableLimits.DEFAULT));
        assertEquals(DocumentLayoutException.Code.UNSUPPORTED_COMPONENT, failure.code());
        assertFalse(new StreamingTable("old", ordinary.metadata(), ordinary.pageSettings(), ordinary.columnWidths(),
                ordinary.headerRows(), ordinary.padding(), false, 0).allowRowSpans());
        assertTrue(table().toBuilder().build().allowRowSpans());
        assertThrows(IllegalArgumentException.class, () -> table().toBuilder().allowRowSplit(true).build());
        assertThrows(IllegalArgumentException.class, () -> table().toBuilder().headerRows(List.of(row(cell("H", 2, 2)))).build());
    }

    @Test void groupRowBudgetFailsBeforeReadingRowsBeyondDeclaredSpan() {
        var source = new Source(row(cell("L", 3, 2)), TableRow.covered(), TableRow.covered());
        var pages = new ArrayList<LayoutPage>();
        var failure = assertThrows(DocumentLayoutException.class, () -> layout(table(), source, pages,
                StreamingTableLimits.DEFAULT.toBuilder().maxBufferedRows(2).build()));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, failure.code());
        assertEquals(1, source.reads); assertTrue(pages.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> StreamingTableLimits.builder().maxBufferedRows(0).build());
        assertThrows(IllegalArgumentException.class, () -> StreamingTableLimits.builder().maxBufferedCodePoints(0).build());
    }

    @Test void columnBudgetIsPreflightedBeforeColumnCachesOrSourceAndProgressCallbacks() {
        var source = new Source(row("A", "B"));
        var progress = new ArrayList<ExecutionOptions.Progress>();
        var pages = new ArrayList<LayoutPage>();
        var limits = StreamingTableLimits.DEFAULT.toBuilder()
                .document(new DocumentLayoutLimits(1, 1000, 100, 10, 10, 1, 100)).build();
        var failure = assertThrows(DocumentLayoutException.class, () ->
                ExecutionContext.execute(ExecutionOptions.builder().progress(progress::add).build(), () ->
                        layout(table(), source, pages, limits)));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, failure.code());
        assertEquals(0, source.reads); assertTrue(progress.isEmpty()); assertTrue(pages.isEmpty());
    }

    @Test void transitiveGroupCannotExtendPastBufferBudget() {
        var source = new Source(row(cell("L", 2, 1), cell("R", 1, 1)), row(cell("R", 2, 1)), row(cell("L", 1, 1)));
        var failure = assertThrows(DocumentLayoutException.class, () -> layout(table(), source, new ArrayList<>(),
                StreamingTableLimits.DEFAULT.toBuilder().maxBufferedRows(2).build()));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, failure.code()); assertEquals(2, source.reads);
    }

    @Test void groupCodePointBudgetIncludesAllAnchorsAndStopsBeforeFurtherReads() {
        var source = new Source(row(cell("AB", 2, 1), cell("CD", 1, 1)), row(cell("EF", 1, 1)));
        var failure = assertThrows(DocumentLayoutException.class, () -> layout(table(), source, new ArrayList<>(),
                StreamingTableLimits.DEFAULT.toBuilder().maxBufferedCodePoints(5).build()));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, failure.code());
        assertEquals(2, source.reads); assertEquals(2, failure.rowIndex()); assertEquals(1, failure.columnIndex());
        assertEquals(1, failure.sourceIndex());
    }

    @Test void missingCoveredRowsUncoveredSlotsAndOverlappingRectanglesFail() {
        for (var source : List.of(
                new Source(row(cell("L", 2, 2))),
                new Source(row(cell("L", 2, 1), cell("R", 1, 1)), TableRow.covered()),
                new Source(row(cell("L", 2, 1), cell("R", 1, 1)), row(cell("BAD", 1, 2))))) {
            var pages = new ArrayList<LayoutPage>();
            var failure = assertThrows(DocumentLayoutException.class, () -> layout(table(), source, pages, StreamingTableLimits.DEFAULT));
            assertEquals(DocumentLayoutException.Code.INVALID_GEOMETRY, failure.code()); assertTrue(pages.isEmpty());
        }
        var spec = table().toBuilder().columns(30, 30, 30).headerRows(List.of()).build();
        var overlap = new Source(row(cell("A", 1, 1), cell("B", 2, 1), cell("C", 1, 1)), row(cell("BAD", 1, 2)));
        assertEquals(DocumentLayoutException.Code.INVALID_GEOMETRY, assertThrows(DocumentLayoutException.class,
                () -> layout(spec, overlap, new ArrayList<>(), StreamingTableLimits.DEFAULT)).code());
    }

    @Test void tooTallGroupFailsBeforeDeliveringHeaderOnlyPage() {
        var source = new Source(row(cell("A", 4, 1), cell("B1", 1, 1)),
                row(cell("B2", 1, 1)), row(cell("B3", 1, 1)), row(cell("B4", 1, 1)));
        var pages = new ArrayList<LayoutPage>();
        assertEquals(DocumentLayoutException.Code.CONTENT_TOO_TALL, assertThrows(DocumentLayoutException.class,
                () -> layout(table(), source, pages, StreamingTableLimits.DEFAULT)).code());
        assertEquals(4, source.reads); assertTrue(pages.isEmpty());
    }

    @Test void utf16DiagnosticsKeepOriginalOwnerRowAndPhysicalColumnAfterCoverage() throws IOException {
        var group = FontGroup.builder().fonts(fonts.fonts()).missingGlyphPolicy(MissingGlyphPolicy.PLACEHOLDER_WITH_WARNING)
                .placeholderCodePoint('?').build();
        var warnings = new ArrayList<StreamingTableDiagnostic>();
        var source = new Source(row(cell("L", 2, 1), cell("R", 1, 1)), row(cell("A😀", 1, 1)));
        new StreamingTableLayouter().layout(table(), source, group, ignored -> { }, warnings::add, StreamingTableLimits.DEFAULT);
        var warning = warnings.stream().filter(value -> value.diagnostic().code().name().equals("MISSING_GLYPH_REPLACED")).findFirst().orElseThrow();
        assertEquals(2, warning.rowIndex()); assertEquals(1, warning.columnIndex());
        assertEquals(1, warning.diagnostic().sourceStart()); assertEquals(3, warning.diagnostic().sourceEnd());
    }

    @Test void pageFailureStopsBeforeFollowingGroupAndBorrowedSourceRemainsOpen() {
        var source = new Source(row("A", "B"), row("C", "D"),
                row(cell("L", 2, 1), cell("R1", 1, 1)), row(cell("R2", 1, 1)), row("UNREAD", "UNREAD"));
        var expected = new IOException("page failure");
        assertSame(expected, assertThrows(IOException.class, () -> new StreamingTableLayouter().layout(table(), source, fonts,
                ignored -> { throw expected; }, ignored -> { }, StreamingTableLimits.DEFAULT)));
        assertEquals(4, source.reads); assertEquals(0, source.closes);
    }

    @Test void cancellationDuringGroupReadDoesNotPublishPartialGroup() {
        var cancel = new AtomicBoolean();
        var reads = new int[1];
        TableRowSource source = () -> {
            reads[0]++;
            if (reads[0] == 2) { cancel.set(true); }
            return Optional.of(reads[0] == 1 ? row(cell("L", 2, 2)) : TableRow.covered());
        };
        var pages = new ArrayList<LayoutPage>();
        var options = ExecutionOptions.builder().cancelled(cancel::get).build();
        assertThrows(ExecutionException.class, () -> ExecutionContext.execute(options, () ->
                new StreamingTableLayouter().layout(table(), source, fonts, pages::add, ignored -> { }, StreamingTableLimits.DEFAULT)));
        assertEquals(2, reads[0]); assertTrue(pages.isEmpty());
    }
}
