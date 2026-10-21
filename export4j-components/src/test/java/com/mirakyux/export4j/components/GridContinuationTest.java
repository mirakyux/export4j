// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.MissingGlyphPolicy;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.RgbImage;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.FlowRegions;
import com.mirakyux.export4j.layout.core.RegionFlow;
import com.mirakyux.export4j.model.PaginationPolicy;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.text.ParagraphStyle;

class GridContinuationTest {
    private static FontGroup fonts;
    private static final ParagraphStyle STYLE = ParagraphStyle.builder().fontSize(10).lineHeight(16).build();

    @BeforeAll static void fonts() throws IOException {
        try (var stream = GridContinuationTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            fonts = FontGroup.strict(List.of(TrueTypeFontLoader.load(stream, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }
    private static ComponentContext context(double height) { return new ComponentContext(240, height, fonts, 0, 0, 1); }
    private static String source(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> String.format(java.util.Locale.ROOT, "ROW%03d", i)).collect(Collectors.joining("\n"));
    }
    private static String text(ComponentLayout layout) {
        return layout.commands().stream().map(command -> {
            if (command instanceof DrawCommand.GlyphText glyph) {
                return glyph.run().glyphs().stream().map(com.mirakyux.export4j.text.PositionedGlyph::text).collect(Collectors.joining());
            }
            if (command instanceof DrawCommand.OutlineText outline) { return outline.logicalText(); }
            return "";
        }).collect(Collectors.joining());
    }
    private static GridTable.Builder merged(boolean split, Component content) {
        return GridTable.builder().columns(1, 1).headerRows(1).allowRowSplit(split)
                .defaults(cell -> cell.textStyle(STYLE).padding(2))
                .row(row -> row.cells("HEAD-A", "HEAD-B"))
                .row(row -> row.cell(content, cell -> cell.rowSpan(2)).cell("SIDE-A"))
                .row(row -> row.cell("SIDE-B"));
    }
    private static FlowText flow(String value) { return FlowText.builder(value).style(STYLE).build(); }

    @Test void ordinaryNestedTextContinuesButFixedTextFailsWhenOverheight() {
        var column = FlowColumn.builder().text(source(16), value -> value.style(STYLE)).build();
        var parts = column.layoutRegions(context(100), new FlowRegions(List.of(40.0), 100));
        assertEquals(List.of(0, 1, 2, 3), parts.stream().map(RegionFlow.Fragment::regionIndex).toList());
        assertEquals(source(16).replace("\n", ""), parts.stream().map(p -> text(p.layout())).collect(Collectors.joining()));
        assertThrows(IllegalArgumentException.class, () -> FlowColumn.builder().fixedText(source(16), t -> t.style(STYLE)).build().layoutParts(context(100)));
    }

    @Test void mergedLongCellContinuesOnceWithHeadersAndAsymmetricNeighbors() {
        var table = merged(true, flow(source(120))).build();
        var parts = table.layoutRegions(context(200), new FlowRegions(List.of(65.0), 200));
        String combined = parts.stream().map(p -> text(p.layout())).collect(Collectors.joining());
        assertTrue(parts.size() > 9);
        assertEquals(source(120).replace("\n", ""), combined.replaceAll("HEAD-A|HEAD-B|SIDE-A|SIDE-B", ""));
        assertEquals(1, combined.split("SIDE-A", -1).length - 1);
        assertEquals(1, combined.split("SIDE-B", -1).length - 1);
        for (var part : parts) {
            assertTrue(text(part.layout()).startsWith("HEAD-AHEAD-B"));
            assertTrue(part.layout().height() <= (part.regionIndex() == 0 ? 65 : 200));
        }
        assertEquals(parts, table.layoutRegions(context(200), new FlowRegions(List.of(65.0), 200)));
    }

    @Test void legacyMergedGroupRemainsAtomicAndFixedChildDoesNotBecomeSplittable() {
        assertFalse(merged(false, flow(source(30))).build().allowRowSplit());
        assertThrows(IllegalArgumentException.class, () -> merged(false, flow(source(30))).build().layoutParts(context(100)));
        assertThrows(IllegalArgumentException.class, () -> merged(true, TextComponent.builder(source(30)).style(STYLE).build()).build().layoutParts(context(100)));
    }

    @Test void repeatedPaddingDoesNotLoseTheFinalLinesAtFractionalCuts() {
        var table = merged(true, flow(source(11))).build();
        var parts = table.layoutRegions(context(75.7), new FlowRegions(List.of(29.5, 44.9), 75.7));
        assertEquals(source(11).replace("\n", ""), parts.stream().map(p -> text(p.layout()))
                .collect(Collectors.joining()).replaceAll("HEAD-A|HEAD-B|SIDE-A|SIDE-B", ""));
        assertTrue(parts.getFirst().regionIndex() >= 1);
    }

    @Test void mixedCellKeepsImageAtomicAndMeasuresCustomChildOnce() {
        AtomicInteger measurements = new AtomicInteger();
        Component picture = ctx -> {
            measurements.incrementAndGet();
            return new BoxComponent(30, 25, new ImageComponent(new RgbImage(1, 1, new byte[]{10, 20, 30}), ImageComponent.Fit.STRETCH)).layout(ctx);
        };
        var content = FlowColumn.builder().padding(2).gap(3).text(source(7), t -> t.style(STYLE))
                .add(picture).text("AFTER-IMAGE", t -> t.style(STYLE)).build();
        var parts = merged(true, content).build().layoutParts(context(100));
        assertEquals(1, measurements.get());
        assertEquals(1, parts.stream().flatMap(p -> p.commands().stream()).filter(DrawCommand.Image.class::isInstance).count());
        assertEquals(source(7).replace("\n", "") + "AFTER-IMAGE", parts.stream().map(GridContinuationTest::text)
                .collect(Collectors.joining()).replaceAll("HEAD-A|HEAD-B|SIDE-A|SIDE-B", ""));
    }

    @Test void impossibleHeadersAtomicContentsAndLineConstraintsFail() {
        assertThrows(IllegalArgumentException.class, () -> merged(true, new Spacer(90)).build().layoutParts(context(100)));
        assertThrows(IllegalArgumentException.class, () -> merged(true, flow("A")).build().layoutParts(context(20)));
        var balanced = flow(source(6)).toBuilder().pagination(new PaginationPolicy(false, false, 3, 3)).build();
        assertThrows(IllegalArgumentException.class, () -> merged(true, balanced).build().layoutParts(context(60)));
    }

    @Test void diagnosticsKeepOriginalUtf16PositionExactlyOnceAcrossContinuations() {
        var group = FontGroup.builder().fonts(fonts.fonts()).missingGlyphPolicy(MissingGlyphPolicy.PLACEHOLDER_WITH_WARNING).build();
        String original = source(9) + "\n\uD83E\uDD80 END";
        var parts = merged(true, new FontScope(flow(original), group)).build().layoutParts(context(100));
        var diagnostics = parts.stream().flatMap(p -> p.diagnostics().stream()).toList();
        assertEquals(1, diagnostics.size());
        assertEquals(original.indexOf("\uD83E\uDD80"), diagnostics.getFirst().sourceStart());
    }

    @Test void simultaneousSpansAndCellDeclarationOrderPreserveAllContent() {
        var table = GridTable.builder().columns(1, 1, 1).allowRowSplit(true).defaults(c -> c.textStyle(STYLE).padding(3))
                .row(r -> r.cell(source(17), c -> c.rowSpan(2)).cell("B1").cell(source(13), c -> c.rowSpan(3)))
                .row(r -> r.cell("B2"))
                .row(r -> r.cell("A3").cell("B3")).build();
        var first = table.layoutParts(context(100));
        var reversed = new java.util.ArrayList<>(table.cells()); Collections.reverse(reversed);
        var second = new GridTable(3, table.columnWeights(), 0, reversed, table.minimumRowHeights(), true).layoutParts(context(100));
        assertEquals(first.stream().map(ComponentLayout::height).toList(), second.stream().map(ComponentLayout::height).toList());
        String combined = first.stream().map(GridContinuationTest::text).collect(Collectors.joining());
        for (int index = 1; index <= 17; index++) {
            String marker = String.format(java.util.Locale.ROOT, "ROW%03d", index);
            assertEquals(index <= 13 ? 2 : 1, combined.split(marker, -1).length - 1);
        }
        for (String marker : List.of("A3", "B1", "B2", "B3")) { assertEquals(1, combined.split(marker, -1).length - 1); }
    }

    @Test void variedSpansAndFractionalRegionsNeverConsumeUnpaintedLines() {
        var random = new java.util.Random(81623);
        for (int scenario = 0; scenario < 40; scenario++) {
            int a = random.nextInt(1, 28);
            int b = random.nextInt(1, 20);
            int c = random.nextInt(1, 18);
            double padding = random.nextDouble(0, 5);
            double height = random.nextDouble(75, 180);
            var table = GridTable.builder().columns(1, 1).headerRows(1).allowRowSplit(true)
                    .defaults(cell -> cell.textStyle(STYLE).padding(padding))
                    .row(row -> row.cells("HEAD-A", "HEAD-B"))
                    .row(row -> row.cell(source(a).replace("ROW", "A"), cell -> cell.rowSpan(2))
                            .cell(source(b).replace("ROW", "B")))
                    .row(row -> row.cell(source(c).replace("ROW", "C"))).build();
            var parts = table.layoutRegions(context(height), new FlowRegions(List.of(random.nextDouble(0, 50)), height));
            String combined = parts.stream().map(part -> text(part.layout())).collect(Collectors.joining());
            for (String prefix : List.of("A", "B", "C")) {
                int count = prefix.equals("A") ? a : prefix.equals("B") ? b : c;
                var matcher = java.util.regex.Pattern.compile(prefix + "(\\d{3})").matcher(combined);
                assertEquals(IntStream.rangeClosed(1, count).boxed().toList(),
                        matcher.results().map(match -> Integer.valueOf(match.group(1))).toList());
            }
            assertTrue(parts.size() < 100);
        }
    }

    @Test void cancellationDuringPreparationRestoresOuterScope() {
        var cancelled = new AtomicBoolean();
        Component child = ctx -> { cancelled.set(true); return TextComponent.builder("STOP").style(STYLE).build().layout(ctx); };
        var options = new ExecutionOptions(Duration.ZERO, cancelled::get, progress -> { }, 1000);
        var failure = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(options,
                () -> merged(true, child).build().layoutParts(context(100))));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code());
        ExecutionContext.checkpoint();
    }
}
