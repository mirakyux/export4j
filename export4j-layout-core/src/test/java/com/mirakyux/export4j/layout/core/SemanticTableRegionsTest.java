// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontData;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.FontMetrics;
import com.mirakyux.export4j.font.GlyphMetrics;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.layout.CellFragment;
import com.mirakyux.export4j.layout.TableRowFragment;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.text.GlyphRun;
import com.mirakyux.export4j.text.ParagraphLayouter;
import com.mirakyux.export4j.text.SimpleTextShaper;
import com.mirakyux.export4j.text.TextLayoutLimits;
import com.mirakyux.export4j.text.TextLine;
import com.mirakyux.export4j.text.TextShaper;

class SemanticTableRegionsTest {
    private static final CellFormat CELL = new CellFormat(10, 10, ParagraphAlignment.LEFT);
    private static final TableFormat WHOLE_ROWS = new TableFormat(PageMargins.uniform(0), 0, 0);
    private static FontFace font;
    private static FontFace physical;

    @BeforeAll
    static void fonts() throws IOException {
        try (InputStream input = SemanticTableRegionsTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            if (input == null) { throw new IOException("missing test font"); }
            physical = TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES);
        }
        font = new FontFace() {
            @Override public FontData data() { return physical.data(); }
            @Override public FontMetrics metrics() { return new FontMetrics(0.7, 0.2, 0); }
            @Override public Optional<GlyphMetrics> glyph(int codePoint) { return Optional.of(new GlyphMetrics(1, 1)); }
        };
    }

    @Test
    void consumesViableShortRegionsAndSkipsOnlyThoseWithoutOneHeaderAndBodyRow() {
        var table = table("regions", List.of(row("H"), row("A"), row("B"), row("C"), row("D"), row("E")), 1, WHOLE_ROWS);
        var regions = new FlowRegions(List.of(0.0, 10.0, 20.0, 0.0, 20.0), 30);
        var result = new DocumentLayouter().layoutTableRegions(table, context(100, 30), regions);

        assertEquals(List.of(2, 4, 5, 6), result.stream().map(DocumentLayouter.TableRegion::regionIndex).toList());
        assertEquals(List.of(20.0, 20.0, 30.0, 20.0), result.stream().map(value -> value.fragment().height()).toList());
        assertEquals(List.of("A", "B", "C", "D", "E"), bodyCells(result).stream().flatMap(cell -> cell.layout().lines().stream()).map(SemanticTableRegionsTest::text).toList());
        assertEquals(List.of(1, 2, 3, 4, 5), result.stream().flatMap(value -> value.fragment().rows().stream())
                .filter(row -> !row.header()).map(TableRowFragment::rowIndex).toList());
        var original = result.getFirst().fragment().rows().getFirst().cells().getFirst().layout();
        assertFalse(result.getFirst().fragment().rows().getFirst().repeatedHeader());
        for (int index = 0; index < result.size(); index++) {
            var fragment = result.get(index).fragment();
            assertEquals(0, fragment.origin().x());
            assertEquals(0, fragment.origin().y());
            assertTrue(fragment.height() <= regions.height(result.get(index).regionIndex()));
            assertSame(original, fragment.rows().getFirst().cells().getFirst().layout());
            assertEquals(index > 0, fragment.rows().getFirst().repeatedHeader());
        }
        assertThrows(UnsupportedOperationException.class, result::clear);
    }

    @Test
    void completeRowsUseTheirActualHeightAndIgnoreOuterTableSpacing() {
        var table = table("spaced", List.of(row("H"), row("A\nB"), row("C")), 1,
                new TableFormat(PageMargins.uniform(0), 500, 500));
        var result = new DocumentLayouter().layoutTableRegions(table, context(100, 40), new FlowRegions(List.of(30.0, 20.0), 40));
        assertEquals(List.of(0, 1), result.stream().map(DocumentLayouter.TableRegion::regionIndex).toList());
        assertEquals(List.of(30.0, 20.0), result.stream().map(value -> value.fragment().height()).toList());
        assertEquals(List.of("A", "B", "C"), bodyCells(result).stream().flatMap(cell -> cell.layout().lines().stream()).map(SemanticTableRegionsTest::text).toList());
    }

    @Test
    void failsAnIndivisibleRowThatCannotFitWithHeadersInAFullContinuation() {
        var table = table("tall", List.of(row("H"), row("A\nB\nC")), 1, WHOLE_ROWS);
        var failure = assertThrows(DocumentLayoutException.class,
                () -> new DocumentLayouter().layoutTableRegions(table, context(100, 30), new FlowRegions(List.of(0.0), 30)));
        assertEquals(DocumentLayoutException.Code.CONTENT_TOO_TALL, failure.code());
        assertEquals("tall", failure.blockId());
        assertEquals(1, failure.rowIndex());
    }

    @Test
    void splitsOnlyCompleteLinesAndRetainsCellLineOffsetsAndSourceRanges() {
        var table = table("split", List.of(row("H"), row("A\nB\nC\nD\nE")), 1,
                new TableFormat(PageMargins.uniform(0), 0, 0, true));
        var result = new DocumentLayouter().layoutTableRegions(table, context(100, 30), new FlowRegions(List.of(20.0), 30));
        assertEquals(List.of(0, 1, 2), result.stream().map(DocumentLayouter.TableRegion::regionIndex).toList());
        var cells = bodyCells(result);
        assertEquals(List.of(0, 1, 3), cells.stream().map(CellFragment::firstLineIndex).toList());
        assertEquals(List.of(false, true, true), result.stream().flatMap(value -> value.fragment().rows().stream())
                .filter(row -> !row.header()).map(TableRowFragment::continuedRow).toList());
        var lines = cells.stream().flatMap(cell -> cell.layout().lines().stream()).toList();
        assertEquals(List.of("A", "B", "C", "D", "E"), lines.stream().map(SemanticTableRegionsTest::text).toList());
        assertEquals(List.of(0, 2, 4, 6, 8), lines.stream().map(TextLine::sourceStart).toList());
        assertEquals(List.of(1, 3, 5, 7, 9), lines.stream().map(TextLine::sourceEnd).toList());
        assertEquals(List.of(7.0, 7.0, 17.0, 7.0, 17.0), lines.stream().map(TextLine::baselineY).toList());
    }

    @Test
    void retainsAtomicFieldsAndImagesWhenTheirLinesContinueToDifferentRegions() throws IOException {
        var image = new InlineImage(image(), 8, 8, 0, "marker", "https://example.com/image");
        var field = new InlineField(InlineField.Kind.PAGE, "", 10, InlineStyle.of(10));
        var format = new CellFormat(10, 20, ParagraphAlignment.LEFT);
        var content = TableCell.mixed(List.of(new InlineText("A\n", InlineStyle.of(10)), field,
                new InlineText("\nB\n", InlineStyle.of(10)), image, new InlineText("\nC", InlineStyle.of(10))), format);
        var table = table("objects", List.of(new TableRow(List.of(new TableCell("H", format))), new TableRow(List.of(content))), 1,
                new TableFormat(PageMargins.uniform(0), 0, 0, true));
        var context = new ComponentContext(100, 60, List.of(physical), 0, 0);
        var result = new DocumentLayouter().layoutTableRegions(table, context, new FlowRegions(List.of(40.0), 60));

        assertEquals(List.of(0, 1, 2), result.stream().map(DocumentLayouter.TableRegion::regionIndex).toList());
        var cells = bodyCells(result);
        assertEquals(List.of(0, 1, 3), cells.stream().map(CellFragment::firstLineIndex).toList());
        var fields = cells.stream().flatMap(cell -> cell.layout().lines().stream()).flatMap(line -> line.fields().stream()).toList();
        var images = cells.stream().flatMap(cell -> cell.layout().lines().stream()).flatMap(line -> line.images().stream()).toList();
        assertEquals(1, fields.size());
        assertEquals(1, images.size());
        assertSame(field, fields.getFirst().field());
        assertSame(image, images.getFirst().image());
        assertEquals(2, fields.getFirst().sourceIndex());
        assertEquals(6, images.getFirst().sourceIndex());
        assertTrue(cells.get(1).layout().lines().getFirst().fields().contains(fields.getFirst()));
        assertTrue(cells.get(2).layout().lines().getFirst().images().contains(images.getFirst()));
        assertEquals(List.of(0, 2, 4, 6, 8), cells.stream().flatMap(cell -> cell.layout().lines().stream()).map(TextLine::sourceStart).toList());
    }

    @Test
    void skipsAllSixteenInitialRegionsWithoutChargingTheOccupiedFragmentBudget() {
        var table = table("skipped", List.of(row("A")), 0, WHOLE_ROWS);
        var limits = new DocumentLayoutLimits(1, 1, 1, 1);
        var result = layouter(limits).layoutTableRegions(table, context(100, 10),
                new FlowRegions(IntStream.range(0, 16).mapToObj(ignored -> 0.0).toList(), 10));
        assertEquals(1, result.size());
        assertEquals(16, result.getFirst().regionIndex());
        assertEquals(10, result.getFirst().fragment().height());
    }

    @Test
    void capsActualFragmentsAfterSkippedRegionsAndCountsRepeatedHeaderLines() {
        var table = table("budget", List.of(row("H"), row("A"), row("B"), row("C"), row("D")), 1, WHOLE_ROWS);
        var regions = new FlowRegions(List.of(0.0, 0.0), 20);
        var pageFailure = assertThrows(DocumentLayoutException.class,
                () -> layouter(new DocumentLayoutLimits(1, 5, 8, 3)).layoutTableRegions(table, context(100, 20), regions));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, pageFailure.code());
        assertEquals(3, pageFailure.pageIndex());
        var lineFailure = assertThrows(DocumentLayoutException.class,
                () -> layouter(new DocumentLayoutLimits(1, 5, 7, 4)).layoutTableRegions(table, context(100, 20), regions));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, lineFailure.code());
        assertEquals(0, lineFailure.rowIndex());
        assertEquals(4, layouter(new DocumentLayoutLimits(1, 5, 8, 4)).layoutTableRegions(table, context(100, 20), regions).size());
    }

    @Test
    void includesEmptyContinuationCellsInTheOutputLineBudget() {
        var table = table("padded", List.of(row("H", "I"), row("A\nB\nC\nD", "X")), 1,
                new TableFormat(PageMargins.uniform(0), 0, 0, true));
        var regions = new FlowRegions(List.of(), 30);
        var failure = assertThrows(DocumentLayoutException.class,
                () -> layouter(new DocumentLayoutLimits(1, 100, 9, 2)).layoutTableRegions(table, context(100, 30), regions));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, failure.code());
        assertEquals(1, failure.rowIndex());
        assertEquals(1, failure.columnIndex());
        var result = layouter(new DocumentLayoutLimits(1, 100, 10, 2)).layoutTableRegions(table, context(100, 30), regions);
        var exhausted = result.getLast().fragment().rows().getLast().cells().get(1);
        assertEquals(1, exhausted.firstLineIndex());
        assertEquals("", text(exhausted.layout().lines().getFirst()));
        assertEquals(1, exhausted.layout().lines().getFirst().sourceStart());
        assertEquals(1, exhausted.layout().lines().getFirst().sourceEnd());
    }

    @Test
    void preservesStructuralAndSourceBudgetFailuresBeforeDistribution() {
        var table = table("source", List.of(row("ABC", "D")), 0, WHOLE_ROWS);
        var context = context(100, 20);
        var regions = new FlowRegions(List.of(), 20);
        var cellsFailure = assertThrows(DocumentLayoutException.class,
                () -> layouter(new DocumentLayoutLimits(1, 10, 10, 10, 1, 2, 1)).layoutTableRegions(table, context, regions));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, cellsFailure.code());
        var sourceFailure = assertThrows(DocumentLayoutException.class,
                () -> layouter(new DocumentLayoutLimits(1, 2, 10, 10)).layoutTableRegions(table, context, regions));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, sourceFailure.code());
        assertEquals("source", sourceFailure.blockId());
        assertEquals(0, sourceFailure.rowIndex());
        assertEquals(0, sourceFailure.columnIndex());
        assertEquals(0, sourceFailure.sourceIndex());
    }

    @Test
    void observesCancellationBetweenMeasurementAndDistributionAndRestoresTheScope() {
        var cancelled = new AtomicBoolean();
        var simple = new SimpleTextShaper();
        TextShaper controlled = (text, fonts, size, source) -> {
            var result = simple.shape(text, fonts, size, source);
            if (text.equals("B")) { cancelled.set(true); }
            return result;
        };
        var layouter = new DocumentLayouter(new ParagraphLayouter(controlled, TextLayoutLimits.DEFAULT), DocumentLayoutLimits.DEFAULT);
        var table = table("cancel", List.of(row("H"), row("B")), 1, WHOLE_ROWS);
        var context = context(100, 20);
        var regions = new FlowRegions(List.of(0.0), 20);
        var options = ExecutionOptions.builder().cancelled(cancelled::get).build();
        var failure = assertThrows(ExecutionException.class,
                () -> ExecutionContext.execute(options, () -> layouter.layoutTableRegions(table, context, regions)));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code());
        ExecutionContext.checkpoint();
        cancelled.set(false);
        assertEquals(1, layouter.layoutTableRegions(table, context, regions).size());
    }

    @Test
    void rejectsContinuationHeightOutsideTheCallerViewport() {
        var table = table("viewport", List.of(row("A")), 0, WHOLE_ROWS);
        assertThrows(IllegalArgumentException.class,
                () -> new DocumentLayouter().layoutTableRegions(table, context(100, 20), new FlowRegions(List.of(), 21)));
        assertThrows(NullPointerException.class,
                () -> new DocumentLayouter().layoutTableRegions(null, context(100, 20), new FlowRegions(List.of(), 20)));
    }

    private static DocumentLayouter layouter(DocumentLayoutLimits limits) {
        return new DocumentLayouter(new ParagraphLayouter(), limits);
    }

    private static ComponentContext context(double width, double height) {
        return new ComponentContext(width, height, FontGroup.strict(List.of(font)), 0, 0, 1);
    }

    private static Table table(String id, List<TableRow> rows, int headers, TableFormat format) {
        return new Table(id, IntStream.range(0, rows.getFirst().cells().size()).<ColumnWidth>mapToObj(ignored -> new ColumnWidth.Fraction(1)).toList(),
                rows, headers, format);
    }

    private static TableRow row(String... values) {
        return new TableRow(java.util.Arrays.stream(values).map(value -> new TableCell(value, CELL)).toList());
    }

    private static List<CellFragment> bodyCells(List<DocumentLayouter.TableRegion> result) {
        return result.stream().flatMap(value -> value.fragment().rows().stream()).filter(row -> !row.header())
                .flatMap(row -> row.cells().stream()).toList();
    }

    private static String text(TextLine line) {
        return line.runs().stream().map(GlyphRun::text).reduce("", String::concat);
    }

    private static ImageResource image() throws IOException {
        var raster = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
        try (var bytes = new ByteArrayOutputStream()) {
            if (!ImageIO.write(raster, "png", bytes)) { throw new IOException("PNG writer unavailable"); }
            return ImageResource.load(bytes.toByteArray());
        } finally { raster.flush(); }
    }
}
