// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.ComponentSemantics;
import com.mirakyux.export4j.layout.core.DocumentLayoutException;
import com.mirakyux.export4j.layout.core.DocumentLayoutLimits;
import com.mirakyux.export4j.layout.core.DocumentLayouter;
import com.mirakyux.export4j.layout.core.FlowRegions;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.text.ParagraphLayouter;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Detects lost/duplicated source and fixed-width reuse when a partially consumed cell changes columns. */
class VariableSplitTableTest {
    private static final CellFormat CELL = CellFormat.builder().fontSize(10).lineHeight(12).build();
    private static FontGroup fonts;
    @BeforeAll static void fonts() throws IOException {
        try (var input = VariableSplitTableTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            fonts = FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }

    @Test void completeSourceWrapsAtEachActualWidthAndKeepsOffsetsAndContinuationIndices() {
        String source = "alpha beta gamma delta ".repeat(15);
        var table = table("source").header("H").row(source).row("END").build();
        var parts = new DocumentLayouter().layoutTableRegions(table, context(150, 60),
                new FlowRegions(List.of(1.0, 1.0), 60), List.of(60.0, 150.0));
        assertEquals(2, parts.getFirst().regionIndex());
        StringBuilder actual = new StringBuilder();
        int consumed = 0, previousEnd = 0;
        boolean narrow = false, wide = false;
        for (var part : parts) {
            double width = part.regionIndex() % 2 == 0 ? 60 : 150;
            for (var row : part.fragment().rows()) {
                if (row.header() || row.rowIndex() != 1) { continue; }
                var cell = row.cells().getFirst();
                assertEquals(consumed, cell.firstLineIndex());
                assertEquals(consumed > 0, row.continuedRow());
                assertEquals(width, cell.layout().width());
                for (var line : cell.layout().lines()) {
                    assertTrue(line.sourceStart() >= previousEnd);
                    assertTrue(line.width() <= Math.nextUp(width));
                    actual.append(source, line.sourceStart(), line.sourceEnd());
                    previousEnd = line.sourceEnd();
                    narrow |= width == 60;
                    wide |= width == 150 && line.sourceEnd() - line.sourceStart() > 15;
                }
                consumed += cell.layout().lines().size();
            }
        }
        assertEquals(source.replace(" ", ""), actual.toString().replace(" ", ""));
        assertTrue(narrow && wide);
        assertEquals("END", table.rows().get(parts.getLast().fragment().rows().getLast().rowIndex()).cells().getFirst().text());
    }

    @Test void differentLineHeightsMergedColumnsAndEmptyContinuationCellsShareExactRegions() {
        var slower = CELL.toBuilder().lineHeight(18).build();
        var table = table("mixed").columns(new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1))
                .headerCells(TableCell.builder().text("HEADER varies with width").format(CELL).columnSpan(3).build())
                .row(TableCell.builder().text("short").format(slower).build(),
                        TableCell.builder().text("one two three four five ".repeat(18)).format(CELL).columnSpan(2).build())
                .row("AFTER", "B", "C").build();
        var parts = new DocumentLayouter().layoutTableRegions(table, context(240, 100),
                new FlowRegions(List.of(10.0), 100), List.of(120.0, 240.0));
        var seen = new HashMap<Integer, Integer>();
        boolean empty = false;
        for (var part : parts) {
            assertTrue(part.fragment().height() <= 100);
            for (var row : part.fragment().rows()) {
                if (row.header() || row.rowIndex() != 1) { continue; }
                assertEquals(List.of(0, 1), row.cells().stream().map(cell -> cell.columnIndex()).toList());
                for (var cell : row.cells()) {
                    assertEquals(seen.getOrDefault(cell.columnIndex(), 0).intValue(), cell.firstLineIndex());
                    boolean blank = cell.layout().lines().stream().allMatch(line -> line.sourceStart() == line.sourceEnd());
                    empty |= blank;
                    if (!blank) { seen.merge(cell.columnIndex(), cell.layout().lines().size(), Integer::sum); }
                }
            }
        }
        assertTrue(empty);
        assertEquals(1, seen.get(0));
        assertTrue(seen.get(1) > 10);
    }

    @Test void physicalPageFieldsLinksAndSourceIdentitySurviveBalancedPaddedScopes() {
        var style = InlineStyle.of(10);
        var header = TableCell.mixed(List.of(new InlineText("P", style), new InlineField(InlineField.Kind.PAGE, "", 12, style),
                new InlineText("/", style), new InlineField(InlineField.Kind.PAGES, "", 12, style)), CELL.toBuilder().lineHeight(18).build());
        var table = table("semantic").headerCells(header).row(TableCell.rich(List.of(
                new InlineText("alpha beta gamma delta ".repeat(45), style, "https://example.com/continued")), CELL)).build();
        var flow = FlowColumn.builder().padding(2).add(new FontScope(new SemanticTable(table), fonts)).build();
        var columns = ContinuousColumns.builder(flow).columnWeights(List.of(1.0, 2.0)).gap(12).balanced(true).build();
        var document = Documents.builder().pageSettings(PageSettings.builder().size(350, 150).margins(15).build())
                .component("host", columns).build();
        var layout = DocumentEngine.builder(fonts).build().layout(document);
        assertTrue(layout.body().pages().size() > 1);
        int index = 0;
        for (var page : layout.body().pages()) {
            for (var element : page.elements()) {
                var component = (com.mirakyux.export4j.layout.ComponentFragment) element;
                for (var located : ComponentSemantics.locate(component.layout(), component.origin())) {
                    var placement = located.placement();
                    assertEquals(table, placement.source());
                    assertEquals(index++, placement.fragmentIndex());
                    var cell = placement.fragment().rows().getFirst().cells().getFirst();
                    String value = cell.layout().lines().stream().flatMap(line -> line.outlines().stream())
                            .map(outline -> outline.logicalText()).collect(java.util.stream.Collectors.joining());
                    assertEquals("P" + (page.pageIndex() + 1) + "/" + layout.body().pages().size(), value);
                }
            }
        }
        assertTrue(layout.display().extras().externalLinks().size() > index);
        assertTrue(layout.display().extras().externalLinks().stream().allMatch(link -> link.destination().uri().equals("https://example.com/continued")));
    }

    @Test void sourceAndActualOutputBudgetsIncludeRepeatedHeadersAndDoNotChargeWidthProbesTwice() {
        var table = table("budget").header("H").row("AA\nBB\nCC\nDD\nEE\nFF").build();
        var exact = new DocumentLayoutLimits(1, 18, 9, 3, 2, 1, 2);
        var regions = new FlowRegions(List.of(), 36);
        var parts = new DocumentLayouter(new ParagraphLayouter(), exact).layoutTableRegions(table, context(100, 36), regions, List.of(50.0, 100.0));
        assertEquals(3, parts.size());
        for (var limits : List.of(new DocumentLayoutLimits(1, 18, 8, 3, 2, 1, 2),
                new DocumentLayoutLimits(1, 18, 9, 2, 2, 1, 2), new DocumentLayoutLimits(1, 17, 9, 3, 2, 1, 2))) {
            assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, assertThrows(DocumentLayoutException.class,
                    () -> new DocumentLayouter(new ParagraphLayouter(), limits).layoutTableRegions(table, context(100, 36), regions, List.of(50.0, 100.0))).code());
        }
    }

    @Test void continuationSkipsInterveningShortRegionsForAllCellsWithoutConsumingSourceOrPageBudget() {
        var tall = CELL.toBuilder().lineHeight(24).build();
        var table = table("short-continuation").columns(new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1))
                .row(TableCell.builder().text("SHORT").format(tall).columnSpan(2).build(),
                        TableCell.builder().text("A\nB\nC\nD\nE\nF").format(CELL).build()).build();
        var limits = new DocumentLayoutLimits(1, 100, 8, 2, 1, 3, 3);
        var parts = new DocumentLayouter(new ParagraphLayouter(), limits).layoutTableRegions(table, context(180, 36),
                new FlowRegions(List.of(36.0, 0.0, 12.0), 36), List.of(90.0, 180.0));
        assertEquals(List.of(0, 3), parts.stream().map(part -> part.regionIndex()).toList());
        var first = parts.getFirst().fragment().rows().getFirst();
        var continued = parts.getLast().fragment().rows().getFirst();
        assertEquals(List.of(0, 2), continued.cells().stream().map(cell -> cell.columnIndex()).toList());
        assertEquals(3, first.cells().getLast().layout().lines().size());
        assertEquals(3, continued.cells().getLast().firstLineIndex());
        assertTrue(continued.continuedRow());
        assertTrue(continued.cells().getFirst().layout().lines().getFirst().runs().isEmpty());
        assertTrue(continued.cells().getFirst().layout().lines().getFirst().outlines().isEmpty());
        String actual = parts.stream().flatMap(part -> part.fragment().rows().stream())
                .flatMap(row -> row.cells().getLast().layout().lines().stream())
                .flatMap(line -> line.runs().stream()).map(run -> run.text())
                .collect(java.util.stream.Collectors.joining());
        assertEquals("ABCDEF", actual);
        assertEquals(60, continued.cells().getLast().layout().width());
    }

    @Test void supplementaryWarningOccursOnceAtOriginalUtf16LocationAfterContinuation() {
        var policy = FontGroup.placeholder(fonts.fonts(), '?');
        var table = table("warning").header("H").row("AA\nBB\n😀\nDD\nEE").build();
        var parts = new DocumentLayouter().layoutTableRegions(table, context(100, 36).withFonts(policy),
                new FlowRegions(List.of(), 36), List.of(50.0, 100.0));
        var warnings = parts.stream().flatMap(part -> part.fragment().rows().stream()).filter(row -> !row.header())
                .flatMap(row -> row.cells().stream()).flatMap(cell -> cell.diagnostics().stream()).toList();
        assertEquals(1, warnings.size());
        assertEquals(6, warnings.getFirst().sourceStart());
        assertEquals(8, warnings.getFirst().sourceEnd());
        assertTrue(parts.get(1).fragment().rows().getLast().continuedRow());
    }

    @Test void unsupportedRecursiveSplitAndImpossibleLineFailWithoutAffectingLaterLayout() {
        var merged = table("merged").row(TableCell.builder().text("A").format(CELL).rowSpan(2).build()).coveredRow().build();
        var nested = Table.builder("inner").cellFormat(CELL).row("A").build();
        var blocks = table("blocks").row(TableCell.builder().blocks(List.of(nested)).build()).build();
        for (var table : List.of(merged, blocks)) {
            assertEquals(DocumentLayoutException.Code.INVALID_GEOMETRY, assertThrows(DocumentLayoutException.class,
                    () -> new DocumentLayouter().layoutTableRegions(table, context(100, 36), new FlowRegions(List.of(), 36), List.of(50.0, 100.0))).code());
        }
        var ordinary = table("ordinary").header("H").row("A").build();
        assertEquals(DocumentLayoutException.Code.CONTENT_TOO_TALL, assertThrows(DocumentLayoutException.class,
                () -> new DocumentLayouter().layoutTableRegions(ordinary, context(100, 12), new FlowRegions(List.of(), 12), List.of(50.0, 100.0))).code());
        assertFalse(new DocumentLayouter().layoutTableRegions(ordinary, context(100, 36), new FlowRegions(List.of(), 36), List.of(50.0, 100.0)).isEmpty());
    }

    @Test void atomicPictureIsCheckedAtItsActualContinuationWidthRatherThanUnusedNarrowWidth() throws IOException {
        var bitmap = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var bytes = new java.io.ByteArrayOutputStream();
        try { assertTrue(javax.imageio.ImageIO.write(bitmap, "png", bytes)); } finally { bitmap.flush(); }
        var image = new com.mirakyux.export4j.model.InlineImage(com.mirakyux.export4j.model.ImageResource.load(bytes.toByteArray()),
                80, 8, 0, "picture", "https://example.com/picture");
        var format = CELL.toBuilder().lineHeight(18).build();
        var cell = TableCell.mixed(List.of(new InlineText("A\nB\n", InlineStyle.of(10)), image,
                new InlineText("\nEND", InlineStyle.of(10))), format);
        var table = table("image").row(cell).build();
        var parts = new DocumentLayouter().layoutTableRegions(table, context(100, 36), new FlowRegions(List.of(), 36), List.of(50.0, 100.0));
        assertEquals(2, parts.size());
        assertEquals(2, parts.getLast().fragment().rows().getFirst().cells().getFirst().firstLineIndex());
        assertEquals(1, parts.getLast().fragment().rows().getFirst().cells().getFirst().layout().lines().stream().flatMap(line -> line.images().stream()).count());
        var bad = table("too-narrow").row(TableCell.mixed(List.of(new InlineText("A\n", InlineStyle.of(10)), image), format)).build();
        assertEquals(DocumentLayoutException.Code.TEXT_LAYOUT_FAILED, assertThrows(DocumentLayoutException.class,
                () -> new DocumentLayouter().layoutTableRegions(bad, context(100, 36), new FlowRegions(List.of(), 36), List.of(50.0, 100.0))).code());
    }

    @Test void cancellationDuringActualSourceMeasurementRestoresScopeAndDoesNotPoisonReuse() throws IOException {
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        var simple = new com.mirakyux.export4j.text.SimpleTextShaper();
        com.mirakyux.export4j.text.TextShaper controlled = (text, stack, size, offset) -> {
            var result = simple.shape(text, stack, size, offset);
            if (text.equals("CANCEL")) { cancelled.set(true); }
            return result;
        };
        var layouter = new DocumentLayouter(new ParagraphLayouter(controlled, com.mirakyux.export4j.text.TextLayoutLimits.DEFAULT),
                DocumentLayoutLimits.DEFAULT);
        var table = table("cancel").row("CANCEL\nAFTER").build();
        var options = ExecutionOptions.builder().cancelled(cancelled::get).build();
        assertEquals(ExecutionException.Code.CANCELLED, assertThrows(ExecutionException.class, () -> ExecutionContext.execute(options,
                () -> layouter.layoutTableRegions(table, context(100, 24), new FlowRegions(List.of(), 24), List.of(50.0, 100.0)))).code());
        ExecutionContext.checkpoint();
        assertEquals(1, layouter.layoutTableRegions(table, context(100, 24), new FlowRegions(List.of(), 24), List.of(50.0, 100.0)).size());
    }

    private static Table.Builder table(String id) { return Table.builder(id).cellFormat(CELL).format(format -> format.padding(0).allowRowSplit(true)); }
    private static ComponentContext context(double width, double height) { return new ComponentContext(width, height, fonts, 0, 0, 1); }
}
