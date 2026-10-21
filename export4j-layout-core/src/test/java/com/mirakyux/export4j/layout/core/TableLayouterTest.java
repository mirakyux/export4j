// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontData;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontMetrics;
import com.mirakyux.export4j.font.GlyphMetrics;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.layout.DocumentLayout;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.layout.TableRowFragment;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.text.GlyphRun;
import com.mirakyux.export4j.text.ParagraphLayouter;
import com.mirakyux.export4j.text.TextLayoutException;

class TableLayouterTest {
    private static FontFace font;
    private static final CellFormat CELL = new CellFormat(10, 10, ParagraphAlignment.LEFT);
    private static final TableFormat PLAIN = new TableFormat(PageMargins.uniform(0), 0, 0);
    private static final ParagraphFormat PARAGRAPH = new ParagraphFormat(10, 10, 0, 0,
            ParagraphAlignment.LEFT, 0, 0, false);

    @BeforeAll
    static void loadFont() throws IOException {
        FontData data;
        try (InputStream input = TableLayouterTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            if (input == null) {
                throw new IOException("Missing test font");
            }
            data = TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES).data();
        }
        font = new FontFace() {
            @Override
            public FontData data() {
                return data;
            }

            @Override
            public FontMetrics metrics() {
                return new FontMetrics(0.7, 0.2, 0);
            }

            @Override
            public Optional<GlyphMetrics> glyph(int codePoint) {
                return Optional.of(new GlyphMetrics(1, 1));
            }
        };
    }

    @Test
    void resolvesFixedAndWeightedColumnsAndUsesTallestPaddedCell() {
        Table table = new Table("t", List.of(new ColumnWidth.Fixed(30), new ColumnWidth.Fraction(1),
                new ColumnWidth.Fraction(2)), List.of(row("A", "BCDEFG", "H")), 0,
                new TableFormat(new PageMargins(2, 5, 3, 5), 0, 0));
        TableFragment fragment = tables(layout(180, 100, table)).getFirst();
        assertEquals(180, fragment.width());
        assertEquals(25, fragment.height());
        var cells = fragment.rows().getFirst().cells();
        assertEquals(List.of(30.0, 50.0, 100.0), cells.stream().map(c -> c.bounds().width()).toList());
        assertEquals(List.of(0.0, 30.0, 80.0), cells.stream().map(c -> c.bounds().x()).toList());
        assertEquals(List.of("BCDE", "FG"), cells.get(1).layout().lines().stream().map(line ->
                line.runs().stream().map(GlyphRun::text).reduce("", String::concat)).toList());
        assertEquals(35, cells.get(1).textOrigin().x());
        assertEquals(2, cells.get(1).textOrigin().y());
        assertTrue(cells.stream().allMatch(c -> c.bounds().height() == 25));
    }

    @Test
    void fixedOnlyTableKeepsItsWidthAndLargeFractionWeightsStayFinite() {
        Table fixed = new Table("fixed", List.of(new ColumnWidth.Fixed(30)), List.of(row("A")), 0, PLAIN);
        Table large = new Table("large", List.of(new ColumnWidth.Fraction(Double.MAX_VALUE),
                new ColumnWidth.Fraction(Double.MAX_VALUE)), List.of(row("B", "C")), 0, PLAIN);
        var result = tables(layout(100, 100, fixed, large));
        assertEquals(30, result.getFirst().width());
        assertEquals(List.of(50.0, 50.0), result.getLast().rows().getFirst().cells().stream()
                .map(cell -> cell.bounds().width()).toList());
    }

    @Test
    void paginatesWholeRowsAndRepeatsHeadersWithStableSourceIdentity() {
        DocumentLayout result = layout(100, 30, numbered(7, 1));
        assertEquals(3, result.pages().size());
        var fragments = tables(result);
        assertEquals(List.of(0, 1, 2, 0, 3, 4, 0, 5, 6), fragments.stream()
                .flatMap(t -> t.rows().stream()).map(TableRowFragment::rowIndex).toList());
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6), fragments.stream().flatMap(t -> t.rows().stream())
                .filter(r -> !r.repeatedHeader()).map(TableRowFragment::rowIndex).toList());
        assertFalse(fragments.getFirst().rows().getFirst().repeatedHeader());
        assertTrue(fragments.subList(1, 3).stream().allMatch(t -> t.rows().getFirst().repeatedHeader()));
        assertTrue(fragments.stream().allMatch(t -> t.blockId().equals("t") && t.blockIndex() == 0));
        assertContained(result);
    }

    @Test
    void keepsMultipleHeaderRowsWithNextBodyRowAndMovesStartOffPartialPage() {
        var result = layout(100, 40, new Paragraph("p", "A\nB", PARAGRAPH), numbered(6, 2));
        assertEquals(List.of("p"), result.pages().getFirst().elements().stream().map(f -> f.blockId()).toList());
        assertEquals(3, result.pages().size());
        assertEquals(List.of(0, 1, 2, 3), tables(result).getFirst().rows().stream().map(TableRowFragment::rowIndex).toList());
        assertEquals(List.of(0, 1, 4, 5), tables(result).getLast().rows().stream().map(TableRowFragment::rowIndex).toList());
        assertTrue(tables(result).getLast().rows().subList(0, 2).stream().allMatch(TableRowFragment::repeatedHeader));
        assertEquals(10, tables(result).getFirst().origin().y());
    }

    @Test
    void preservesParagraphTableParagraphOrderAndDoesNotLeaveTrailingEmptyPages() {
        var result = layout(100, 40, new Paragraph("before", "A", PARAGRAPH), numbered(2, 0),
                new Paragraph("after", "Z", PARAGRAPH));
        assertEquals(1, result.pages().size());
        assertEquals(List.of("before", "t", "after"), result.pages().getFirst().elements().stream()
                .map(f -> f.blockId()).toList());
        assertEquals(2, result.pages().getFirst().fragments().getLast().paragraphIndex());
        assertEquals(20, tables(result).getFirst().origin().y());
        assertEquals(40, result.pages().getFirst().fragments().getLast().origin().y());
        assertContained(result);
    }

    @Test
    void headerlessTablesContinueWithoutDuplicatingAnyRow() {
        var result = layout(100, 20, numbered(5, 0));
        assertEquals(3, result.pages().size());
        assertEquals(List.of(0, 1, 2, 3, 4), tables(result).stream().flatMap(t -> t.rows().stream())
                .map(TableRowFragment::rowIndex).toList());
        assertTrue(tables(result).stream().flatMap(t -> t.rows().stream()).noneMatch(TableRowFragment::header));
        assertContained(result);
    }

    @Test
    void appliesBlockSpacingOnceAndSuppressesItAtPageEdges() {
        Table table = new Table("t", List.of(new ColumnWidth.Fraction(1)), List.of(row("A"), row("B")), 0,
                new TableFormat(PageMargins.uniform(0), 5, 7));
        var result = layout(100, 60, new Paragraph("before", "X", new ParagraphFormat(10, 10, 0, 0,
                ParagraphAlignment.LEFT, 0, 3, false)), table, new Paragraph("after", "Y", PARAGRAPH));
        assertEquals(28, tables(result).getFirst().origin().y());
        assertEquals(55, result.pages().getFirst().fragments().getLast().origin().y());
        Table longer = new Table("t", table.columns(), List.of(row("A"), row("B"), row("C")), 0, table.format());
        var moved = layout(100, 20, new Paragraph("before", "X", PARAGRAPH), longer);
        assertEquals(3, moved.pages().size());
        assertEquals(List.of(10.0, 10.0), tables(moved).stream().map(t -> t.origin().y()).toList());
    }

    @Test
    void refusesTooTallRowOrHeaderAndBodyPairWithoutRetryLoops() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            Table tall = new Table("t", List.of(new ColumnWidth.Fraction(1)), List.of(row("A\nB\nC\nD")), 0, PLAIN);
            var rowFailure = fail(100, 30, tall, DocumentLayoutLimits.DEFAULT);
            assertEquals(DocumentLayoutException.Code.CONTENT_TOO_TALL, rowFailure.code());
            assertEquals(0, rowFailure.rowIndex());
            Table pair = new Table("t", List.of(new ColumnWidth.Fraction(1)),
                    List.of(row("A\nB"), row("C\nD")), 1, PLAIN);
            var pairFailure = fail(100, 30, pair, DocumentLayoutLimits.DEFAULT);
            assertEquals(DocumentLayoutException.Code.CONTENT_TOO_TALL, pairFailure.code());
            assertEquals(1, pairFailure.rowIndex());
            assertEquals(0, pairFailure.pageIndex());
        });
    }

    @Test
    void rejectsExhaustedColumnsPaddingAndNonfiniteHeightWithContext() {
        for (Table table : List.of(
                new Table("t", List.of(new ColumnWidth.Fixed(101)), List.of(row("A")), 0, PLAIN),
                new Table("t", List.of(new ColumnWidth.Fixed(100), new ColumnWidth.Fraction(1)),
                        List.of(row("A", "B")), 0, PLAIN),
                new Table("t", List.of(new ColumnWidth.Fixed(10)), List.of(row("A")), 0,
                        new TableFormat(PageMargins.uniform(5), 0, 0)))) {
            assertEquals(DocumentLayoutException.Code.INVALID_GEOMETRY,
                    fail(100, 30, table, DocumentLayoutLimits.DEFAULT).code());
        }
        Table height = new Table("t", List.of(new ColumnWidth.Fraction(1)), List.of(row("A")), 0,
                new TableFormat(new PageMargins(Double.MAX_VALUE, 0, Double.MAX_VALUE, 0), 0, 0));
        assertEquals(DocumentLayoutException.Code.CONTENT_TOO_TALL,
                fail(100, 30, height, DocumentLayoutLimits.DEFAULT).code());
    }

    @Test
    void enforcesRowColumnCellAndAggregateCodePointBudgetsBeforeLayout() {
        Table table = new Table("t", List.of(new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1)),
                List.of(row("A", "B"), row("C", "D")), 1, PLAIN);
        for (DocumentLayoutLimits limits : List.of(
                new DocumentLayoutLimits(10, 100, 100, 10, 1, 2, 4),
                new DocumentLayoutLimits(10, 100, 100, 10, 2, 1, 4),
                new DocumentLayoutLimits(10, 100, 100, 10, 2, 2, 3),
                new DocumentLayoutLimits(10, 3, 100, 10, 2, 2, 4))) {
            var failure = fail(100, 30, table, limits);
            assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, failure.code());
            assertEquals("t", failure.blockId());
            assertEquals(-1, failure.pageIndex());
        }
        var failure = fail(100, 30, table, new DocumentLayoutLimits(10, 3, 100, 10));
        assertEquals(1, failure.rowIndex());
        assertEquals(1, failure.columnIndex());
    }

    @Test
    void accountsForRepeatedHeaderLinesAndReportsPageBudgetAtNextBodyRow() {
        Table table = numbered(4, 1);
        var lines = fail(100, 20, table, new DocumentLayoutLimits(10, 100, 5, 10));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, lines.code());
        assertEquals(2, lines.pageIndex());
        assertEquals(0, lines.rowIndex());
        assertEquals(3, layout(100, 20, table).pages().size());
        var pages = fail(100, 20, table, new DocumentLayoutLimits(10, 100, 100, 1));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, pages.code());
        assertEquals(1, pages.pageIndex());
        assertEquals(2, pages.rowIndex());
    }

    @Test
    void cellFailureKeepsTableRowColumnAndUtf16PositionWithoutTextInMessage() {
        Table table = new Table("secret-id", List.of(new ColumnWidth.Fraction(1)),
                List.of(row("A"), row("B\n\u0628")), 1, PLAIN);
        var failure = assertThrows(DocumentLayoutException.class, () -> new DocumentLayouter(new ParagraphLayouter(
                new com.mirakyux.export4j.text.SimpleTextShaper(), com.mirakyux.export4j.text.TextLayoutLimits.DEFAULT), DocumentLayoutLimits.DEFAULT)
                .layout(document(100, 50, table), List.of(font)));
        assertEquals(DocumentLayoutException.Code.TEXT_LAYOUT_FAILED, failure.code());
        assertEquals("secret-id", failure.blockId());
        assertEquals(1, failure.rowIndex());
        assertEquals(0, failure.columnIndex());
        assertEquals(2, failure.sourceIndex());
        assertEquals(TextLayoutException.Code.UNSUPPORTED_SHAPING,
                assertInstanceOf(TextLayoutException.class, failure.getCause()).code());
        assertFalse(failure.getMessage().contains("\u0628"));
    }

    @Test
    void emptyCellsRetainLinesAndAlignmentAndSourceRangesAreCellLocal() {
        Table table = new Table("t", List.of(new ColumnWidth.Fixed(40), new ColumnWidth.Fixed(40)), List.of(
                new TableRow(List.of(new TableCell("", CELL), new TableCell("A\nB\n", new CellFormat(10, 10,
                        ParagraphAlignment.RIGHT))))), 0, PLAIN);
        var cells = tables(layout(100, 40, table)).getFirst().rows().getFirst().cells();
        assertEquals(1, cells.getFirst().layout().lines().size());
        assertEquals(List.of(0, 2, 4), cells.getLast().layout().lines().stream().map(l -> l.sourceStart()).toList());
        assertEquals(List.of(30.0, 30.0, 40.0), cells.getLast().layout().lines().stream().map(l -> l.offsetX()).toList());
    }

    @Test
    void decimalRowHeightsTolerateRoundingButStillMoveRealOverflowToNextPage() {
        CellFormat tiny = new CellFormat(0.01, 0.1, ParagraphAlignment.LEFT);
        TableRow row = new TableRow(List.of(new TableCell("A", tiny)));
        Table table = new Table("t", List.of(new ColumnWidth.Fraction(1)), List.of(row, row, row), 0, PLAIN);
        for (double height : new double[] {0.3, 0.3 - 1e-8}) {
            var source = new Document(DocumentMetadata.EMPTY,
                    new PageSettings(new PageSize(80, height), PageMargins.uniform(0)), List.of(table));
            var result = new DocumentLayouter().layout(source, List.of(font));
            assertEquals(height == 0.3 ? 1 : 2, result.pages().size());
            assertContained(result);
        }
    }

    @Test
    void cellBudgetIsSharedByTablesAndSourceCodePointsCountSupplementaryScalarsOnce() {
        Table first = new Table("first", List.of(new ColumnWidth.Fraction(1)), List.of(row("A"), row("B")), 0, PLAIN);
        Table second = new Table("second", first.columns(), List.of(row("C"), row("D")), 0, PLAIN);
        var failure = assertThrows(DocumentLayoutException.class,
                () -> new DocumentLayouter(new ParagraphLayouter(), new DocumentLayoutLimits(10, 100, 100, 10, 10, 10, 3))
                        .layout(document(100, 40, first, second), List.of(font)));
        assertEquals("second", failure.blockId());
        assertEquals(1, failure.blockIndex());
        Table unicode = new Table("unicode", first.columns(), List.of(row("A\uD83D\uDE00")), 0, PLAIN);
        var result = new DocumentLayouter(new ParagraphLayouter(), new DocumentLayoutLimits(1, 2, 10, 1))
                .layout(document(100, 30, unicode), List.of(font));
        assertEquals("A\uD83D\uDE00", tables(result).getFirst().rows().getFirst().cells().getFirst().layout().lines()
                .getFirst().runs().getFirst().text());
    }

    @Test
    void painterUsesCellOriginsAndKeepsMixedContentDrawingOrder() {
        var layout = layout(100, 40, new Paragraph("before", "X", PARAGRAPH), numbered(2, 1),
                new Paragraph("after", "Z", PARAGRAPH));
        var display = DocumentPainter.paint(layout);
        var glyphs = display.pages().getFirst().commands().stream().filter(DrawCommand.GlyphText.class::isInstance)
                .map(DrawCommand.GlyphText.class::cast).toList();
        assertEquals(List.of("X", "0", "1", "Z"), glyphs.stream().map(g -> g.run().text()).toList());
        var table = tables(layout).getFirst();
        var cell = table.rows().getFirst().cells().getFirst();
        assertEquals(table.origin().x() + cell.textOrigin().x(), glyphs.get(1).baseline().x());
        assertEquals(table.origin().y() + cell.textOrigin().y() + cell.layout().lines().getFirst().baselineY(),
                glyphs.get(1).baseline().y());
        assertTrue(display.pages().getFirst().commands().stream().anyMatch(DrawCommand.FillRect.class::isInstance));
        assertTrue(display.pages().getFirst().commands().stream().anyMatch(DrawCommand.StrokePath.class::isInstance));
        assertEquals(layout, layout(100, 40, new Paragraph("before", "X", PARAGRAPH), numbered(2, 1),
                new Paragraph("after", "Z", PARAGRAPH)));
        assertThrows(UnsupportedOperationException.class, () -> table.rows().clear());
        assertThrows(UnsupportedOperationException.class, () -> cell.layout().lines().clear());
    }

    private static void assertContained(DocumentLayout layout) {
        for (var page : layout.pages()) {
            for (var table : page.elements().stream().filter(TableFragment.class::isInstance).map(TableFragment.class::cast).toList()) {
                assertTrue(table.origin().y() >= page.contentBounds().y());
                assertTrue(table.origin().y() + table.height() <= page.contentBounds().y() + page.contentBounds().height() + 1e-9);
                for (var row : table.rows()) {
                    for (var cell : row.cells()) {
                        assertTrue(cell.bounds().x() + cell.bounds().width() <= table.width() + 1e-9);
                        assertTrue(cell.bounds().y() + cell.bounds().height() <= table.height() + 1e-9);
                        assertTrue(cell.textOrigin().y() + cell.layout().height() <= cell.bounds().y() + cell.bounds().height() + 1e-9);
                    }
                }
            }
        }
    }

    private static Table numbered(int count, int headers) {
        return new Table("t", List.of(new ColumnWidth.Fraction(1)),
                IntStream.range(0, count).mapToObj(index -> row(Integer.toString(index))).toList(), headers, PLAIN);
    }

    private static TableRow row(String... values) {
        return new TableRow(List.of(values).stream().map(text -> new TableCell(text, CELL)).toList());
    }

    private static Document document(double width, double height, Block... blocks) {
        return new Document(DocumentMetadata.EMPTY,
                new PageSettings(new PageSize(width + 20, height + 20), PageMargins.uniform(10)), List.of(blocks));
    }

    private static DocumentLayout layout(double width, double height, Block... blocks) {
        return new DocumentLayouter().layout(document(width, height, blocks), List.of(font));
    }

    private static DocumentLayoutException fail(double width, double height, Table table, DocumentLayoutLimits limits) {
        return assertThrows(DocumentLayoutException.class, () -> new DocumentLayouter(new ParagraphLayouter(), limits)
                .layout(document(width, height, table), List.of(font)));
    }

    private static List<TableFragment> tables(DocumentLayout layout) {
        return layout.pages().stream().flatMap(p -> p.elements().stream()).filter(TableFragment.class::isInstance)
                .map(TableFragment.class::cast).toList();
    }
}
