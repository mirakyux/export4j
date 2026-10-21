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
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.StreamingTable;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.TableRowSource;
import com.mirakyux.export4j.text.ParagraphLayouter;

class ColumnSpanLayouterTest {
    private static final CellFormat CELL = new CellFormat(8, 10, ParagraphAlignment.LEFT);
    private static final TableFormat PLAIN = new TableFormat(PageMargins.uniform(0), 0, 0);
    private static FontGroup fonts;

    @BeforeAll static void fonts() throws IOException {
        try (var input = ColumnSpanLayouterTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            fonts = FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }

    @Test void fixedFractionGeometryUsesGridStartAndOnePaddingRegion() {
        var source = new Table("merged", List.of(new ColumnWidth.Fixed(30), new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1)),
                List.of(row(cell("A", 2), cell("B", 1)), row(cell("C", 1), cell("D", 2))), 0,
                new TableFormat(PageMargins.uniform(2), 0, 0));
        var table = fragment(new DocumentLayouter().layout(document(source, 150, 100), fonts), 0);
        var first = table.rows().getFirst().cells();
        assertEquals(List.of(0, 2), first.stream().map(c -> c.columnIndex()).toList());
        assertEquals(List.of(90.0, 60.0), first.stream().map(c -> c.bounds().width()).toList());
        assertEquals(List.of(0.0, 90.0), first.stream().map(c -> c.bounds().x()).toList());
        assertEquals(List.of(86.0, 56.0), first.stream().map(c -> c.layout().width()).toList());
        var second = table.rows().getLast().cells();
        assertEquals(List.of(0, 1), second.stream().map(c -> c.columnIndex()).toList());
        assertEquals(List.of(30.0, 120.0), second.stream().map(c -> c.bounds().width()).toList());
        assertEquals(28, table.height());
    }

    @Test void narrowCoveredColumnsNeedNoIndependentPadding() {
        var source = new Table("narrow", List.of(new ColumnWidth.Fixed(3), new ColumnWidth.Fixed(30)),
                List.of(row(cell("A", 2))), 0, new TableFormat(PageMargins.uniform(4), 0, 0));
        var result = fragment(new DocumentLayouter().layout(document(source, 100, 100), fonts), 0).rows().getFirst().cells().getFirst();
        assertEquals(33, result.bounds().width());
        assertEquals(25, result.layout().width());
    }

    @Test void mergedAutomaticColumnsFailAtAnchorButUnrelatedAutomaticColumnWorks() {
        var bad = new Table("auto", List.of(new ColumnWidth.Fixed(40), new ColumnWidth.Auto(20, 80), new ColumnWidth.Fixed(40)),
                List.of(row(cell("A", 2), cell("B", 1))), 0, PLAIN);
        var failure = assertThrows(DocumentLayoutException.class, () -> new DocumentLayouter().layout(document(bad, 150, 100), fonts));
        assertEquals(DocumentLayoutException.Code.INVALID_GEOMETRY, failure.code());
        assertEquals(0, failure.rowIndex()); assertEquals(0, failure.columnIndex());
        var good = new Table("auto", List.of(new ColumnWidth.Fixed(40), new ColumnWidth.Fixed(40), new ColumnWidth.Auto(20, 80)),
                bad.rows(), 0, PLAIN);
        var cells = fragment(new DocumentLayouter().layout(document(good, 150, 100), fonts), 0).rows().getFirst().cells();
        assertEquals(List.of(0, 2), cells.stream().map(c -> c.columnIndex()).toList());
        assertEquals(80, cells.getFirst().bounds().width());
        assertTrue(cells.getLast().bounds().width() >= 20);
    }

    @Test void sourceCountsEachAnchorOnceButStructuralBudgetCountsCoveredSlots() {
        var source = table(List.of(row(cell("AB", 2), cell("C", 1)), row(cell("D", 3))), 0, false);
        var exact = new DocumentLayoutLimits(1, 4, 3, 1, 2, 3, 6);
        assertEquals(1, new DocumentLayouter(new ParagraphLayouter(), exact).layout(document(source, 150, 100), fonts).pages().size());
        var tooFewSlots = new DocumentLayoutLimits(1, 4, 3, 1, 2, 3, 5);
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED,
                assertThrows(DocumentLayoutException.class, () -> new DocumentLayouter(new ParagraphLayouter(), tooFewSlots)
                        .layout(document(source, 150, 100), fonts)).code());
        var tooFewPoints = new DocumentLayoutLimits(1, 2, 3, 1, 2, 3, 6);
        var failure = assertThrows(DocumentLayoutException.class, () -> new DocumentLayouter(new ParagraphLayouter(), tooFewPoints)
                .layout(document(source, 150, 100), fonts));
        assertEquals(0, failure.rowIndex()); assertEquals(2, failure.columnIndex());
    }

    @Test void adjacentAutoReservesPaddingPerMergedAnchorAndRespectsFractionWeights() {
        for (var fractions : List.of(List.of(new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1)),
                List.of(new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(3)))) {
            var source = new Table("fraction-auto", List.of(fractions.getFirst(), fractions.getLast(), new ColumnWidth.Auto(84, 84)),
                    List.of(row(cell("A", 2), cell("B", 1))), 0, new TableFormat(PageMargins.uniform(4), 0, 0));
            var cells = fragment(new DocumentLayouter().layout(document(source, 100, 100), fonts), 0).rows().getFirst().cells();
            assertEquals(List.of(16.0, 84.0), cells.stream().map(c -> c.bounds().width()).toList());
            assertEquals(8, cells.getFirst().layout().width());
            assertEquals(76, cells.getLast().layout().width());
        }
    }

    @Test void wholeRowsRepeatWideHeaderWithoutExtraTailPage() {
        var source = table(List.of(row(cell("HEAD", 3)), row(cell("A", 2), cell("B", 1)),
                row(cell("C", 1), cell("D", 2)), row(cell("E", 3))), 1, false);
        var result = new DocumentLayouter().layout(document(source, 150, 30), fonts);
        assertEquals(2, result.pages().size());
        assertEquals(List.of(0, 1, 2), fragment(result, 0).rows().stream().map(r -> r.rowIndex()).toList());
        assertEquals(List.of(0, 3), fragment(result, 1).rows().stream().map(r -> r.rowIndex()).toList());
        assertTrue(fragment(result, 1).rows().getFirst().repeatedHeader());
        assertEquals(150, fragment(result, 1).rows().getFirst().cells().getFirst().bounds().width());
    }

    @Test void splitRowsResetOffsetsWhenNextRowHasDifferentAnchorCount() {
        var source = splitSource();
        var result = new DocumentLayouter().layout(document(source, 150, 40), fonts);
        assertEquals(3, result.pages().size());
        var pieces = result.pages().stream().flatMap(p -> p.elements().stream()).map(TableFragment.class::cast)
                .flatMap(t -> t.rows().stream()).filter(r -> !r.header()).toList();
        assertEquals(List.of(1, 1, 2, 2, 3), pieces.stream().map(r -> r.rowIndex()).toList());
        assertEquals(List.of(0, 3, 0, 1, 0), pieces.stream().map(r -> r.cells().getFirst().firstLineIndex()).toList());
        assertEquals(List.of(2, 2, 1, 1, 3), pieces.stream().map(r -> r.cells().size()).toList());
        assertEquals(List.of(0, 2), pieces.get(1).cells().stream().map(c -> c.columnIndex()).toList());
        assertEquals(12, pieces.stream().flatMap(r -> r.cells().stream()).flatMap(c -> c.layout().lines().stream())
                .filter(l -> l.sourceEnd() > l.sourceStart()).count());
    }

    @Test void cursorSkipsShortRegionsAndRetainsMergedLineSourcesAcrossVaryingTakes() {
        var cursor = new DocumentLayouter().prepareTable(splitSource(), new ComponentContext(150, 50, fonts, 0, 0, 1, java.util.Map.of()));
        assertTrue(cursor.take(19).isEmpty()); assertEquals(1, cursor.rowIndex());
        var first = cursor.take(30).orElseThrow();
        assertEquals(0, first.rows().getLast().cells().getFirst().firstLineIndex());
        var second = cursor.take(40).orElseThrow();
        assertEquals(2, second.rows().getLast().cells().getFirst().firstLineIndex());
        assertFalse(cursor.done());
        var third = cursor.take(50).orElseThrow();
        assertEquals(List.of(0, 2, 3), third.rows().stream().map(r -> r.rowIndex()).toList());
        assertEquals(List.of(1, 3), third.rows().stream().filter(r -> !r.header()).map(r -> r.cells().size()).toList());
        assertTrue(cursor.done()); assertEquals(0, cursor.remainingHeight());
    }

    @Test void strictMissingGlyphReportsPhysicalStartAndCellUtf16() {
        var source = table(List.of(row(cell("OK", 2), cell("a 😀", 1))), 0, false);
        var error = assertThrows(DocumentLayoutException.class, () -> new DocumentLayouter().layout(document(source, 150, 100), fonts));
        assertEquals(0, error.rowIndex()); assertEquals(2, error.columnIndex()); assertEquals(2, error.sourceIndex());
    }

    @Test void streamingRowsExplicitlyRejectSpansEvenWithMatchingAnchorCount() {
        var specification = StreamingTable.builder("source").columns(50, 50).padding(PageMargins.uniform(0)).build();
        TableRowSource source = () -> Optional.of(row(cell("A", 2), cell("B", 1)));
        var error = assertThrows(DocumentLayoutException.class, () -> new StreamingTableLayouter().layout(specification,
                source, fonts, page -> { throw new AssertionError("must reject before delivery"); }, diagnostic -> { }, StreamingTableLimits.DEFAULT));
        assertEquals(DocumentLayoutException.Code.UNSUPPORTED_COMPONENT, error.code());
        assertEquals(0, error.columnIndex());
    }

    private static Table splitSource() {
        return table(List.of(row(cell("HEAD", 3)), row(cell("A\nB\nC\nD\nE", 2), cell("S", 1)),
                row(cell("F\nG\nH", 3)), row(cell("I", 1), cell("J", 1), cell("K", 1))), 1, true);
    }

    private static Table table(List<TableRow> rows, int headers, boolean split) {
        return new Table("merged", List.of(new ColumnWidth.Fixed(50), new ColumnWidth.Fixed(50), new ColumnWidth.Fixed(50)),
                rows, headers, new TableFormat(PageMargins.uniform(0), 0, 0, split));
    }

    private static TableCell cell(String value, int span) { return TableCell.builder().text(value).format(CELL).columnSpan(span).build(); }
    private static TableRow row(TableCell... cells) { return new TableRow(List.of(cells)); }
    private static Document document(Table table, double width, double height) {
        return new Document(DocumentMetadata.EMPTY, PageSettings.builder().size(width, height).margins(0).build(), List.of(table));
    }
    private static TableFragment fragment(com.mirakyux.export4j.layout.DocumentLayout result, int page) {
        return (TableFragment) result.pages().get(page).elements().getFirst();
    }
}
