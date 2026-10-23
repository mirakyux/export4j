// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.layout.DocumentLayout;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.PaginationPolicy;
import com.mirakyux.export4j.model.StreamingTable;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.TableRowSource;
import com.mirakyux.export4j.text.ParagraphLayouter;

class RowSpanLayouterTest {
    private static final CellFormat CELL = CellFormat.builder().fontSize(8).lineHeight(10).build();
    private static FontGroup fonts;

    @BeforeAll static void fonts() throws IOException {
        try (var input = RowSpanLayouterTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            fonts = FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }

    @Test void incomingSpansRetainPhysicalGapsAndOnePaddingRegion() {
        var source = new Table("spans", List.of(new ColumnWidth.Fixed(40), new ColumnWidth.Fixed(60), new ColumnWidth.Fixed(50)),
                List.of(row(cell("A\nA\nA", 1, 2), cell("B", 1, 1), cell("C", 1, 2)), row(cell("D\nD", 1, 1))),
                0, new TableFormat(PageMargins.uniform(2), 0, 0));
        var result = table(layout(source, 100), 0);
        assertEquals(38, result.height());
        assertEquals(List.of(0, 1, 2), result.rows().getFirst().cells().stream().map(c -> c.columnIndex()).toList());
        assertEquals(List.of(38.0, 14.0, 38.0), result.rows().getFirst().cells().stream().map(c -> c.bounds().height()).toList());
        var lower = result.rows().getLast().cells().getFirst();
        assertEquals(1, lower.columnIndex()); assertEquals(40, lower.bounds().x()); assertEquals(14, lower.bounds().y());
        assertEquals(60, lower.bounds().width()); assertEquals(24, lower.bounds().height());
        assertEquals(56, lower.layout().width()); assertEquals(42, lower.textOrigin().x());
    }

    @Test void coveredRowsAddNoFakeContentOrPaddingAndConsumeMergedHeight() {
        var source = table(List.of(row(cell("A\nB\nC\nD", 2, 3)), TableRow.covered(), TableRow.covered()), 0, 2, false);
        var result = table(layout(source, 100), 0);
        assertEquals(44, result.height(), 1e-10);
        assertEquals(1, result.rows().size()); assertEquals(0, result.rows().getFirst().rowIndex());
        assertEquals(4, result.rows().getFirst().cells().getFirst().layout().lines().size());
        assertEquals(44, result.rows().getFirst().cells().getFirst().bounds().height(), 1e-10);
    }

    @Test void shorterSpanConstraintsAreSatisfiedBeforeLongerOnes() {
        var source = table(List.of(row(cell("a\na\na\na\na\na", 1, 3), cell("b", 1, 1)),
                row(cell("c\nc\nc\nc\nc", 1, 2)), TableRow.covered()), 0, 0, false);
        var result = table(layout(source, 100), 0);
        assertEquals(60, result.height());
        assertEquals(60, result.rows().getFirst().cells().getFirst().bounds().height());
        var second = result.rows().get(1).cells().getFirst();
        assertEquals(10, second.bounds().y()); assertEquals(50, second.bounds().height());
    }

    @Test void transitiveConnectedGroupMovesAsOneAndOverheightFails() {
        var source = connected();
        var lead = new Paragraph("lead", "p", ParagraphFormat.builder().fontSize(8).lineHeight(15).build());
        var result = new DocumentLayouter().layout(document(List.of(lead, source), 40), fonts);
        assertEquals(2, result.pages().size()); assertEquals(1, result.pages().getFirst().elements().size());
        var moved = table(result, 1);
        assertEquals(List.of(0, 1, 2), moved.rows().stream().map(r -> r.rowIndex()).toList());
        assertEquals(30, moved.height()); assertEquals(0, moved.origin().y());
        var failure = assertThrows(DocumentLayoutException.class, () -> layout(source, 29));
        assertEquals(DocumentLayoutException.Code.CONTENT_TOO_TALL, failure.code()); assertEquals(0, failure.rowIndex());
    }

    @Test void cursorSkipIsPureAndSubsequentGroupRemainsAvailable() {
        var cursor = new DocumentLayouter().prepareTable(connected(), context(40));
        assertTrue(cursor.take(29).isEmpty()); assertTrue(cursor.take(29).isEmpty());
        assertEquals(0, cursor.rowIndex()); assertEquals(0, cursor.fragmentIndex()); assertEquals(30, cursor.remainingHeight());
        assertEquals(30, cursor.take(30).orElseThrow().height()); assertTrue(cursor.done());
    }

    @Test void keepWithNextReservesTheWholeFirstConnectedGroup() {
        var normal = ParagraphFormat.builder().fontSize(8).lineHeight(10).spaceBefore(0).spaceAfter(0).build();
        var linked = normal.toBuilder().pagination(PaginationPolicy.builder().keepWithNext(true).build()).build();
        var result = new DocumentLayouter().layout(document(List.of(new Paragraph("lead", "lead", normal),
                new Paragraph("linked", "linked", linked), connected()), 45), fonts);
        assertEquals(2, result.pages().size());
        assertEquals(List.of("lead"), result.pages().getFirst().elements().stream().map(part -> part.blockId()).toList());
        assertEquals(List.of("linked", "spans"), result.pages().getLast().elements().stream().map(part -> part.blockId()).toList());
        assertEquals(10, result.pages().getLast().elements().getLast().origin().y());
    }

    @Test void regionPrefixSkipsWholeConnectedGroup() {
        var regions = new DocumentLayouter().layoutTableRegions(connected(), context(40), new FlowRegions(List.of(9.0, 29.0), 40));
        assertEquals(1, regions.size()); assertEquals(2, regions.getFirst().regionIndex());
        assertEquals(3, regions.getFirst().fragment().rows().size());
    }

    @Test void completeMergedHeadersRepeatAndCountOnlyRealOutputLines() {
        var source = table(List.of(row(cell("H\nH", 2, 2)), TableRow.covered(),
                row(cell("A", 2, 1)), row(cell("B", 2, 1))), 2, 0, false);
        var exact = new DocumentLayoutLimits(1, 100, 6, 2, 4, 2, 8);
        var result = new DocumentLayouter(new ParagraphLayouter(), exact).layout(document(List.of(source), 30), fonts);
        assertEquals(2, result.pages().size());
        for (int page = 0; page < 2; page++) {
            var part = table(result, page);
            assertEquals(2, part.rows().size());
            assertEquals(page > 0, part.rows().getFirst().repeatedHeader());
            assertEquals(20, part.rows().getFirst().cells().getFirst().bounds().height());
        }
        var tooFewLines = new DocumentLayoutLimits(1, 100, 5, 2, 4, 2, 8);
        var failure = assertThrows(DocumentLayoutException.class, () -> new DocumentLayouter(new ParagraphLayouter(), tooFewLines)
                .layout(document(List.of(source), 30), fonts));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, failure.code());
    }

    @Test void headerPlusBodyGroupMustFitTogether() {
        var source = table(List.of(row(cell("H", 2, 1)), row(cell("A", 1, 2), cell("B", 1, 1)),
                row(cell("C", 1, 1))), 1, 0, false);
        var failure = assertThrows(DocumentLayoutException.class, () -> layout(source, 29));
        assertEquals(DocumentLayoutException.Code.CONTENT_TOO_TALL, failure.code()); assertEquals(1, failure.rowIndex());
    }

    @Test void sourceBudgetsUseRealAnchorColumnAndCoveredSlotCount() {
        var source = table(List.of(row(cell("A", 1, 2), cell("B", 1, 1)), row(cell("CC", 1, 1))), 0, 0, false);
        var exact = new DocumentLayoutLimits(1, 4, 3, 1, 2, 2, 4);
        assertEquals(1, new DocumentLayouter(new ParagraphLayouter(), exact).layout(document(List.of(source), 30), fonts).pages().size());
        var points = new DocumentLayoutLimits(1, 3, 3, 1, 2, 2, 4);
        var failure = assertThrows(DocumentLayoutException.class, () -> new DocumentLayouter(new ParagraphLayouter(), points)
                .layout(document(List.of(source), 30), fonts));
        assertEquals(1, failure.rowIndex()); assertEquals(1, failure.columnIndex());
        var slots = new DocumentLayoutLimits(1, 4, 3, 1, 2, 2, 3);
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED,
                assertThrows(DocumentLayoutException.class, () -> new DocumentLayouter(new ParagraphLayouter(), slots)
                        .layout(document(List.of(source), 30), fonts)).code());
    }

    @Test void verticalMergedOwnersUseAutomaticWidthsOnce() {
        var rows = List.of(row(cell("A", 1, 2), cell("B", 1, 1)), row(cell("C", 1, 1)));
        var good = new Table("auto", List.of(new ColumnWidth.Fixed(50), new ColumnWidth.Auto(20, 50)), rows, 0,
                new TableFormat(PageMargins.uniform(0), 0, 0));
        assertEquals(1, layout(good, 30).pages().size());
        var bad = new Table("auto", List.of(new ColumnWidth.Auto(20, 50), new ColumnWidth.Fixed(50)), rows, 0, good.format());
        var result = layout(bad, 30);
        assertEquals(1, result.pages().size());
        var table = (TableFragment) result.pages().getFirst().elements().getFirst();
        assertEquals(20, table.rows().getFirst().cells().getFirst().bounds().width());
        assertEquals(20, table.rows().getFirst().cells().getFirst().bounds().height());
    }

    @Test void explicitRowSplitContinuesVerticalOwnersWithoutDuplicatingContent() {
        var source = connected();
        var split = new Table(source.id(), source.columns(), source.rows(), 0, source.format().toBuilder().allowRowSplit(true).build());
        assertEquals(1, layout(split, 40).pages().size());
        var cursor = new DocumentLayouter().prepareTable(split, context(40));
        assertTrue(cursor.take(9).isEmpty());
        var first = cursor.take(20).orElseThrow();
        assertEquals(0, first.rows().getFirst().rowIndex());
        assertEquals(20, first.rows().getFirst().cells().getFirst().bounds().height());
        assertTrue(cursor.take(20).isPresent()); assertTrue(cursor.done());
    }

    @Test void longVerticalContentSplitsAndCoveredTailMakesProgressWithoutBlankPages() {
        var source = table(List.of(row(cell("A\nB\nC\nD\nE", 1, 2), cell("SIDE", 1, 1)),
                row(cell("TAIL", 1, 1))), 0, 1, true);
        var result = layout(source, 32);
        assertEquals(3, result.pages().size());
        var lines = result.pages().stream().flatMap(page -> page.elements().stream()).map(TableFragment.class::cast)
                .flatMap(table -> table.rows().stream()).filter(row -> row.rowIndex() == 0)
                .flatMap(row -> row.cells().stream()).filter(cell -> cell.columnIndex() == 0 && !cell.hasBlocks())
                .flatMap(cell -> cell.layout().lines().stream()).toList();
        assertEquals(List.of(0, 2, 4, 6, 8), lines.stream().map(line -> line.sourceStart()).toList());
        var covered = Table.builder("covered").columns(new ColumnWidth.Fixed(50))
                .format(format -> format.padding(0).allowRowSplit(true))
                .row(cell("A\nB\nC\nD", 1, 3)).coveredRow().coveredRow().build();
        assertEquals(2, layout(covered, 20).pages().size());
    }

    @Test void cancellationRestoresScopeAndDoesNotConsumeGroup() {
        var cursor = new DocumentLayouter().prepareTable(connected(), context(40));
        var options = ExecutionOptions.builder().cancelled(() -> true).build();
        assertEquals(ExecutionException.Code.CANCELLED,
                assertThrows(ExecutionException.class, () -> ExecutionContext.execute(options, () -> cursor.take(40))).code());
        ExecutionContext.checkpoint(); assertFalse(cursor.done()); assertEquals(0, cursor.rowIndex());
        assertTrue(cursor.take(40).isPresent()); assertTrue(cursor.done());
    }

    @Test void streamingSourceRejectsVerticalSpanEvenWithMatchingAnchorCount() throws IOException {
        var source = StreamingTable.builder("stream").columns(50, 50).build();
        try (var rows = new TableRowSource() {
            @Override public Optional<TableRow> next() { return Optional.of(row(cell("A", 1, 2), cell("B", 1, 1))); }
            @Override public void close() { }
        }) {
            var failure = assertThrows(DocumentLayoutException.class, () -> new StreamingTableLayouter().layout(source, rows, fonts,
                    page -> { throw new AssertionError("must reject before delivery"); }, diagnostic -> { }, StreamingTableLimits.DEFAULT));
            assertEquals(DocumentLayoutException.Code.UNSUPPORTED_COMPONENT, failure.code()); assertEquals(0, failure.columnIndex());
        }
    }

    private static Table connected() {
        return table(List.of(row(cell("A", 1, 2), cell("B", 1, 1)), row(cell("C\nC", 1, 2)), row(cell("D", 1, 1))), 0, 0, false);
    }
    private static TableCell cell(String text, int columns, int rows) {
        return TableCell.builder().text(text).format(CELL).columnSpan(columns).rowSpan(rows).build();
    }
    private static TableRow row(TableCell... cells) { return new TableRow(List.of(cells)); }
    private static Table table(List<TableRow> rows, int headers, double padding, boolean split) {
        return new Table("spans", List.of(new ColumnWidth.Fixed(50), new ColumnWidth.Fixed(50)), rows, headers,
                new TableFormat(PageMargins.uniform(padding), 0, 0, split));
    }
    private static Document document(List<Block> blocks, double height) {
        return new Document(DocumentMetadata.EMPTY, PageSettings.builder().size(150, height).margins(0).build(), blocks);
    }
    private static DocumentLayout layout(Table source, double height) { return new DocumentLayouter().layout(document(List.of(source), height), fonts); }
    private static TableFragment table(DocumentLayout result, int page) { return (TableFragment) result.pages().get(page).elements().getFirst(); }
    private static ComponentContext context(double height) { return new ComponentContext(150, height, fonts, 0, 0, 1); }
}
