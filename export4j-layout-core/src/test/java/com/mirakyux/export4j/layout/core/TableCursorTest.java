// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontData;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontMetrics;
import com.mirakyux.export4j.font.GlyphMetrics;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
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
import com.mirakyux.export4j.text.ParagraphLayouter;
import com.mirakyux.export4j.text.SimpleTextShaper;
import com.mirakyux.export4j.text.TextLayoutLimits;
import com.mirakyux.export4j.text.TextLine;

class TableCursorTest {
    private static final CellFormat FORMAT = new CellFormat(10, 10, ParagraphAlignment.LEFT);
    private static FontFace font;
    private static FontFace physical;

    @BeforeAll static void fonts() throws IOException {
        try (var input = TableCursorTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            if (input == null) { throw new IOException("missing test font"); }
            physical = TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES);
        }
        font = new FontFace() {
            @Override public FontData data() { return physical.data(); }
            @Override public FontMetrics metrics() { return new FontMetrics(0.7, 0.2, 0); }
            @Override public Optional<GlyphMetrics> glyph(int codePoint) { return Optional.of(new GlyphMetrics(1, 1)); }
        };
    }

    @Test void emptyTakesDoNotConsumeRowsHeadersOrFragmentBudget() {
        var table = table("atomic", List.of(row("H"), row("A\nB"), row("C")), 1, false);
        var cursor = new DocumentLayouter(new ParagraphLayouter(), new DocumentLayoutLimits(1, 100, 5, 2))
                .prepareTable(table, context(100, 40));
        assertEquals(40, cursor.remainingHeight());
        assertTrue(cursor.take(0).isEmpty());
        assertTrue(cursor.take(29).isEmpty());
        assertEquals(40, cursor.remainingHeight());
        var first = cursor.take(30).orElseThrow();
        assertEquals(30, first.height());
        assertFalse(first.rows().getFirst().repeatedHeader());
        assertEquals(20, cursor.remainingHeight());
        var last = cursor.take(20).orElseThrow();
        assertTrue(last.rows().getFirst().repeatedHeader());
        assertSame(first.rows().getFirst().cells().getFirst().layout(), last.rows().getFirst().cells().getFirst().layout());
        assertTrue(cursor.done());
        assertEquals(0, cursor.remainingHeight());
        assertTrue(cursor.take(0).isEmpty());
    }

    @Test void variableHeightsPreserveCompleteLinesAndCellLocalOffsets() {
        var cursor = new DocumentLayouter().prepareTable(
                table("split", List.of(row("H"), row("A\nB\nC\nD")), 1, true), context(100, 40));
        var first = cursor.take(20).orElseThrow();
        assertTrue(cursor.take(19).isEmpty());
        var middle = cursor.take(30).orElseThrow();
        var last = cursor.take(20).orElseThrow();
        var cells = List.of(first, middle, last).stream().map(value -> value.rows().getLast().cells().getFirst()).toList();
        assertEquals(List.of(0, 1, 3), cells.stream().map(value -> value.firstLineIndex()).toList());
        var lines = cells.stream().flatMap(value -> value.layout().lines().stream()).toList();
        assertEquals(List.of(0, 2, 4, 6), lines.stream().map(TextLine::sourceStart).toList());
        assertEquals(List.of(1, 3, 5, 7), lines.stream().map(TextLine::sourceEnd).toList());
        assertEquals(List.of(false, true, true), List.of(first, middle, last).stream().map(value -> value.rows().getLast().continuedRow()).toList());
        assertTrue(cursor.done());
    }

    @Test void cachedFinalLayoutsAreNotShapedAgainWhenHeightsChange() {
        var shaped = new AtomicInteger();
        var simple = new SimpleTextShaper();
        var layouter = new DocumentLayouter(new ParagraphLayouter((text, fonts, size, source) -> {
            shaped.incrementAndGet();
            return simple.shape(text, fonts, size, source);
        }, TextLayoutLimits.DEFAULT), DocumentLayoutLimits.DEFAULT);
        var cursor = layouter.prepareTable(table("cached", List.of(row("H"), row("A\nB\nC")), 1, true), context(100, 40));
        int preparationCalls = shaped.get();
        assertTrue(preparationCalls > 0);
        cursor.take(19);
        cursor.take(20);
        cursor.take(30);
        assertTrue(cursor.done());
        assertEquals(preparationCalls, shaped.get());
    }

    @Test void fieldsImagesAndOriginalStylesSurviveIndependentTakes() throws IOException {
        var pixels = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
        byte[] encoded;
        try (var output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(pixels, "png", output)) { throw new IOException("missing PNG writer"); }
            encoded = output.toByteArray();
        }
        var image = new InlineImage(ImageResource.load(encoded), 8, 8, 0, "image", "https://example.com/image");
        var field = new InlineField(InlineField.Kind.PAGE, "", 10, InlineStyle.of(10));
        var format = new CellFormat(10, 20, ParagraphAlignment.LEFT);
        var cell = TableCell.mixed(List.of(new InlineText("A\n", InlineStyle.of(10)), field,
                new InlineText("\n", InlineStyle.of(10)), image), format);
        var source = new Table("objects", List.of(new ColumnWidth.Fraction(1)), List.of(new TableRow(List.of(cell))), 0,
                new TableFormat(PageMargins.uniform(0), 0, 0, true));
        var cursor = new DocumentLayouter().prepareTable(source, new ComponentContext(100, 40, List.of(physical), 0, 0));
        var first = cursor.take(20).orElseThrow();
        var second = cursor.take(20).orElseThrow();
        var last = cursor.take(20).orElseThrow();
        assertTrue(first.rows().getFirst().cells().getFirst().layout().lines().getFirst().fields().isEmpty());
        var fieldBox = second.rows().getFirst().cells().getFirst().layout().lines().getFirst().fields().getFirst();
        assertSame(field, fieldBox.field()); assertEquals(2, fieldBox.sourceIndex());
        var imageBox = last.rows().getFirst().cells().getFirst().layout().lines().getFirst().images().getFirst();
        assertSame(image, imageBox.image()); assertEquals(4, imageBox.sourceIndex());
        assertTrue(cursor.done());
    }

    @Test void explicitWidthResolutionRemainsStableAcrossTakes() {
        var source = new Table("widths", List.of(new ColumnWidth.Fixed(25), new ColumnWidth.Fraction(1)),
                List.of(row("A", "B"), row("C", "D")), 0, new TableFormat(PageMargins.uniform(0), 0, 0));
        var cursor = new DocumentLayouter().prepareTable(source, context(100, 20));
        for (int index = 0; index < 2; index++) {
            var cells = cursor.take(10).orElseThrow().rows().getFirst().cells();
            assertEquals(List.of(25.0, 75.0), cells.stream().map(value -> value.bounds().width()).toList());
            assertEquals(List.of(0.0, 25.0), cells.stream().map(value -> value.bounds().x()).toList());
        }
        assertTrue(cursor.done());
    }

    @Test void occupiedPageAndRepeatedHeaderBudgetsKeepSourceContext() {
        var source = table("limits", List.of(row("H"), row("A"), row("B"), row("C")), 1, false);
        var pages = new DocumentLayouter(new ParagraphLayouter(), new DocumentLayoutLimits(1, 100, 100, 1))
                .prepareTable(source, context(100, 20));
        pages.take(19); pages.take(20);
        var pageFailure = assertThrows(DocumentLayoutException.class, () -> pages.take(20));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, pageFailure.code());
        assertEquals(1, pageFailure.pageIndex()); assertEquals(2, pageFailure.rowIndex());
        var lines = new DocumentLayouter(new ParagraphLayouter(), new DocumentLayoutLimits(1, 100, 5, 3))
                .prepareTable(source, context(100, 20));
        lines.take(20); lines.take(20);
        var lineFailure = assertThrows(DocumentLayoutException.class, () -> lines.take(20));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, lineFailure.code());
        assertEquals("limits", lineFailure.blockId()); assertEquals(0, lineFailure.rowIndex());
        assertThrows(DocumentLayoutException.class, () -> new DocumentLayouter(new ParagraphLayouter(),
                new DocumentLayoutLimits(1, 2, 100, 10)).prepareTable(source, context(100, 20)));
    }

    @Test void indivisibleOversizeSourceReturnsEmptyWithoutShrinkingOrConsuming() {
        var cursor = new DocumentLayouter().prepareTable(table("tall", List.of(row("A\nB\nC")), 0, false), context(100, 20));
        assertEquals(30, cursor.remainingHeight());
        assertTrue(cursor.take(20).isEmpty()); assertTrue(cursor.take(20).isEmpty());
        assertFalse(cursor.done()); assertEquals(30, cursor.remainingHeight());
        for (double invalid : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY, 21}) {
            assertThrows(IllegalArgumentException.class, () -> cursor.take(invalid));
        }
    }

    @Test void cancellationBeforeTakeLeavesTheSourceAvailableAndRestoresExecutionScope() {
        var cursor = new DocumentLayouter().prepareTable(table("cancel", List.of(row("A")), 0, false), context(100, 20));
        var cancelled = new AtomicBoolean(true);
        var options = ExecutionOptions.builder().cancelled(cancelled::get).build();
        var failure = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(options, () -> cursor.take(10)));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code());
        ExecutionContext.checkpoint();
        assertEquals(10, cursor.remainingHeight());
        assertTrue(cursor.take(10).isPresent()); assertTrue(cursor.done());
    }

    private static ComponentContext context(double width, double height) {
        return new ComponentContext(width, height, List.of(font), 0, 0);
    }
    private static TableRow row(String... text) {
        return new TableRow(java.util.Arrays.stream(text).map(value -> new TableCell(value, FORMAT)).toList());
    }
    private static Table table(String id, List<TableRow> rows, int headers, boolean split) {
        return new Table(id, rows.getFirst().cells().stream().<ColumnWidth>map(value -> new ColumnWidth.Fraction(1)).toList(),
                rows, headers, new TableFormat(PageMargins.uniform(0), 0, 0, split));
    }
}
