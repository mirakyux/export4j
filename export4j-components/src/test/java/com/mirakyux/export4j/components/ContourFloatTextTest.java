// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.VectorPath;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.FlowRegions;
import com.mirakyux.export4j.layout.core.RegionFlow;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.text.ParagraphStyle;

class ContourFloatTextTest {
    private static FontGroup fonts;
    private static final ParagraphStyle STYLE = ParagraphStyle.builder().fontSize(10).lineHeight(20).build();
    private static final Component GRAPHIC = context -> new ComponentLayout(context.width(), context.height(), 0,
            List.of(new DrawCommand.FillRect(new Rect(0, 0, context.width(), context.height()), new RgbColor(.2, .6, .8))), List.of());
    @BeforeAll static void fonts() throws IOException {
        try (var stream = ContourFloatTextTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            fonts = FontGroup.strict(List.of(TrueTypeFontLoader.load(stream, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }
    private static ComponentContext context(double height) { return new ComponentContext(240, height, fonts, 0, 0, 1); }
    private static OpenTypeText text(String source) { return new OpenTypeText(source, STYLE, RgbColor.BLACK); }
    private static VectorPath rectangle(double width, double height) {
        return new VectorPath(List.of(new VectorPath.MoveTo(new Point(0, 0)), new VectorPath.LineTo(new Point(width, 0)),
                new VectorPath.LineTo(new Point(width, height)), new VectorPath.LineTo(new Point(0, height)), new VectorPath.Close()));
    }
    private static String source(List<RegionFlow.Fragment> parts) {
        return parts.stream().flatMap(part -> part.layout().staticCommands().stream()).filter(DrawCommand.OutlineText.class::isInstance)
                .map(DrawCommand.OutlineText.class::cast).map(DrawCommand.OutlineText::logicalText).collect(Collectors.joining());
    }
    private record Placed(String text, double x, double y, double width) { }
    private static List<Placed> lines(ComponentLayout part) {
        var result = new ArrayList<Placed>(); var stack = new ArrayDeque<double[]>(); double x = 0, y = 0;
        for (var command : part.staticCommands()) {
            if (command instanceof DrawCommand.Save) { stack.push(new double[] {x, y}); }
            else if (command instanceof DrawCommand.Restore) { var saved = stack.pop(); x = saved[0]; y = saved[1]; }
            else if (command instanceof DrawCommand.Transform transform) { x += transform.matrix().e(); y += transform.matrix().f(); }
            else if (command instanceof DrawCommand.OutlineText line) {
                result.add(new Placed(line.logicalText(), x + line.logicalBounds().x(), y + line.logicalBounds().y(), line.logicalBounds().width()));
            }
        }
        assertTrue(stack.isEmpty()); return result;
    }

    @Test void triangleUsesActualBandContourAndContinuesAcrossShortFirstRegion() {
        var triangle = new VectorPath(List.of(new VectorPath.MoveTo(new Point(0, 0)), new VectorPath.LineTo(new Point(120, 0)),
                new VectorPath.LineTo(new Point(0, 120)), new VectorPath.Close()));
        String source = "alpha beta gamma delta ".repeat(45);
        var floating = new ContourFloatText.Floating(GRAPHIC, 0, 120, 120, triangle, 0, new ContourFloatText.AtRegion(0, 0));
        var parts = new ContourFloatText(text(source), List.of(floating)).layoutRegions(context(100), new FlowRegions(List.of(60.0), 100));
        assertEquals(source, source(parts)); assertTrue(parts.size() > 2);
        assertEquals(List.of(120.0, 100.0, 80.0), lines(parts.getFirst().layout()).stream().map(Placed::x).toList());
        assertEquals(60, lines(parts.get(1).layout()).getFirst().x(), 1e-6);
        assertEquals(2, parts.stream().flatMap(part -> part.layout().staticCommands().stream()).filter(DrawCommand.FillRect.class::isInstance).count());
        assertTrue(parts.stream().allMatch(part -> part.layout().height() <= (part.regionIndex() == 0 ? 60 : 100)));
    }

    @Test void curvedContourAndInteriorObstacleUseWidestClearIntervalWithStableTies() {
        var curve = new VectorPath(List.of(new VectorPath.MoveTo(new Point(0, 0)), new VectorPath.LineTo(new Point(100, 0)),
                new VectorPath.CurveTo(new Point(100, 40), new Point(10, 60), new Point(0, 100)), new VectorPath.Close()));
        var floating = new ContourFloatText.Floating(GRAPHIC, 0, 100, 100, curve, 0, new ContourFloatText.AtRegion(0, 0));
        var parts = new ContourFloatText(text("one two three four ".repeat(30)), List.of(floating))
                .layoutRegions(context(120), new FlowRegions(List.of(), 120));
        var lines = lines(parts.getFirst().layout());
        assertTrue(lines.get(0).x() > lines.get(2).x()); assertTrue(lines.get(2).x() > lines.get(4).x());
        var middle = new ContourFloatText.Floating(GRAPHIC, 80, 80, 60, rectangle(80, 60), 0, new ContourFloatText.AtRegion(0, 0));
        var centered = new ContourFloatText(text("one two three four ".repeat(10)), List.of(middle))
                .layoutRegions(context(120), new FlowRegions(List.of(), 120));
        for (var line : lines(centered.getFirst().layout())) {
            if (line.y() < 60) { assertEquals(0, line.x()); assertTrue(line.width() <= 80.00001); }
        }
    }

    @Test void afterTextAnchorFollowsTheOriginalLineAndNeverDuplicatesSource() {
        String source = "first words ".repeat(24) + "ANCHOR " + "after words ".repeat(35);
        var floating = new ContourFloatText.Floating(GRAPHIC, 0, 100, 180, rectangle(100, 180), 3,
                new ContourFloatText.AfterText(source.indexOf("ANCHOR"), 0));
        var parts = new ContourFloatText(text(source), List.of(floating)).layoutRegions(context(100), new FlowRegions(List.of(40.0), 100));
        assertEquals(source, source(parts));
        int anchorRegion = -1, floatRegion = -1;
        for (var part : parts) {
            if (lines(part.layout()).stream().anyMatch(line -> line.text().contains("ANCHOR"))) { anchorRegion = part.regionIndex(); }
            if (floatRegion < 0 && part.layout().staticCommands().stream().anyMatch(DrawCommand.FillRect.class::isInstance)) { floatRegion = part.regionIndex(); }
        }
        assertTrue(anchorRegion > 0); assertTrue(floatRegion >= anchorRegion);
        assertTrue(parts.stream().flatMap(part -> part.layout().staticCommands().stream()).filter(DrawCommand.FillRect.class::isInstance).count() >= 2);
    }

    @Test void blockedBandsProgressAndExplicitFutureAnchorReservesIntermediateRegions() {
        var full = new ContourFloatText.Floating(GRAPHIC, 0, 240, 100, rectangle(240, 100), 0, new ContourFloatText.AtRegion(0, 0));
        var parts = new ContourFloatText(text("AFTER"), List.of(full)).layoutRegions(context(100), new FlowRegions(List.of(), 100));
        assertEquals(0, parts.getFirst().layout().textLines()); assertEquals("AFTER", source(parts));
        assertEquals(2, parts.size());
        var future = new ContourFloatText.Floating(GRAPHIC, 0, 40, 40, rectangle(40, 40), 0, new ContourFloatText.AtRegion(3, 10));
        var reserved = new ContourFloatText(text(""), List.of(future)).layoutRegions(context(100), new FlowRegions(List.of(), 100));
        assertEquals(List.of(0, 1, 2, 3), reserved.stream().map(RegionFlow.Fragment::regionIndex).toList());
        assertEquals(50, reserved.getLast().layout().height());
    }

    @Test void positiveButUnusableNarrowBandsAreSkippedWithoutDroppingTheNextGrapheme() {
        var narrow = new ContourFloatText.Floating(GRAPHIC, 0, 239, 40, rectangle(239, 40), 0, new ContourFloatText.AtRegion(0, 0));
        var parts = new ContourFloatText(text("WWWW AFTER"), List.of(narrow)).layoutRegions(context(100), new FlowRegions(List.of(), 100));
        assertEquals("WWWW AFTER", source(parts));
        assertEquals(40, lines(parts.getFirst().layout()).getFirst().y());
    }

    @Test void flowingSemanticFloatKeepsOneOrderedSourceStream() {
        var table = Table.builder("floating-table").format(value -> value.padding(0).allowRowSplit(true))
                .row("floating words ".repeat(35)).build();
        var floating = new ContourFloatText.Floating(new SemanticTable(table), 0, 100, 800, rectangle(100, 800), 0,
                new ContourFloatText.AtRegion(0, 20));
        var parts = new ContourFloatText(text("body words ".repeat(70)), List.of(floating))
                .layoutRegions(context(120), new FlowRegions(List.of(), 120));
        var placements = parts.stream().flatMap(part -> part.layout().semanticPlacements().stream()).toList();
        assertTrue(placements.size() > 2);
        for (int at = 0; at < placements.size(); at++) {
            assertEquals(at, placements.get(at).fragmentIndex()); assertEquals(placements.size(), placements.get(at).fragmentCount());
            assertSame(table, placements.get(at).source());
        }
        assertEquals("body words ".repeat(70), source(parts));
    }

    @Test void atomicTextCannotBeRepeatedAsTilesAndTooSmallFloatSourceFails() {
        var floating = new ContourFloatText.Floating(TextComponent.builder("atomic").style(STYLE).build(), 0, 100, 200,
                rectangle(100, 200), 0, new ContourFloatText.AtRegion(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ContourFloatText(text("body"), List.of(floating))
                .layoutRegions(context(100), new FlowRegions(List.of(), 100)));
        Component rawText = ctx -> new ComponentLayout(ctx.width(), ctx.height(), 0, List.of(new DrawCommand.Text("raw", new Point(0, 10),
                new com.mirakyux.export4j.graphics.TextStyle(com.mirakyux.export4j.graphics.FontFamily.SANS_SERIF, 10, RgbColor.BLACK))), List.of());
        var raw = new ContourFloatText.Floating(rawText, 0, 100, 200, rectangle(100, 200), 0, new ContourFloatText.AtRegion(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ContourFloatText(text("body"), List.of(raw))
                .layoutRegions(context(100), new FlowRegions(List.of(), 100)));
        var tooSmall = new ContourFloatText.Floating(FlowText.builder("long words ".repeat(80)).style(STYLE).build(), 0, 100, 40,
                rectangle(100, 40), 0, new ContourFloatText.AtRegion(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ContourFloatText(text("body"), List.of(tooSmall))
                .layoutRegions(context(100), new FlowRegions(List.of(), 100)));
    }

    @Test void cancellationAndInvalidAnchorsFailBeforeOutputAndDoNotPoisonReuse() {
        var component = new ContourFloatText(text("body ".repeat(30)), List.of());
        assertThrows(com.mirakyux.export4j.model.ExecutionException.class, () -> ExecutionContext.execute(
                ExecutionOptions.builder().cancelled(() -> true).build(),
                () -> component.layoutRegions(context(100), new FlowRegions(List.of(), 100))));
        assertFalse(component.layoutRegions(context(100), new FlowRegions(List.of(), 100)).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new ContourFloatText(text("A😀B"), List.of(new ContourFloatText.Floating(
                GRAPHIC, 0, 20, 20, rectangle(20, 20), 0, new ContourFloatText.AfterText(2, 0)))));
        assertThrows(IllegalArgumentException.class, () -> new ContourFloatText.AtRegion(100_000, 0));
    }
}
