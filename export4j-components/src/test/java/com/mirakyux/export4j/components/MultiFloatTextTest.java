// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.ComponentFragment;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentBlock;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextAlignment;

class MultiFloatTextTest {
    private static FontGroup fonts;
    private static final ParagraphStyle STYLE = new ParagraphStyle(12, 20, 0, 0, TextAlignment.LEFT);
    private static final Component BOX = context -> new ComponentLayout(context.width(), context.height(), 0,
            List.of(new DrawCommand.FillRect(new Rect(0, 0, context.width(), context.height()), new RgbColor(.2, .6, .8))), List.of());

    @BeforeAll static void fonts() throws IOException {
        try (var input = MultiFloatTextTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            fonts = FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }
    private static ComponentContext context(double height) { return new ComponentContext(240, height, fonts, 0, 0, 1); }
    private static OpenTypeText text(String source) { return new OpenTypeText(source, STYLE, RgbColor.BLACK); }
    private static MultiFloatText.FloatBox box(double width, double height, double top, MultiFloatText.Side side) {
        return new MultiFloatText.FloatBox(BOX, width, height, top, 10, side);
    }
    private static String source(List<ComponentLayout> parts) {
        return parts.stream().flatMap(p -> p.commands().stream()).filter(DrawCommand.OutlineText.class::isInstance)
                .map(DrawCommand.OutlineText.class::cast).map(DrawCommand.OutlineText::logicalText).collect(Collectors.joining());
    }
    private record Placed(String source, Rect bounds) { }
    private static List<Placed> lines(ComponentLayout part) {
        var result = new ArrayList<Placed>();
        var stack = new ArrayDeque<double[]>();
        double x = 0;
        double y = 0;
        for (var command : part.commands()) {
            if (command instanceof DrawCommand.Save) { stack.push(new double[] {x, y}); }
            else if (command instanceof DrawCommand.Restore) { var saved = stack.pop(); x = saved[0]; y = saved[1]; }
            else if (command instanceof DrawCommand.Transform transform) { x += transform.matrix().e(); y += transform.matrix().f(); }
            else if (command instanceof DrawCommand.OutlineText line) {
                var bounds = line.logicalBounds();
                result.add(new Placed(line.logicalText(), new Rect(x + bounds.x(), y + bounds.y(), bounds.width(), bounds.height())));
            }
        }
        assertTrue(stack.isEmpty());
        return result;
    }

    @Test void staggeredOpposingFloatsPreserveSourceAndExcludeEveryIntersectingLineBand() {
        String source = "AV words a\u0301 ".repeat(100);
        var boxes = List.of(box(60, 31, 0, MultiFloatText.Side.LEFT), box(70, 45, 19, MultiFloatText.Side.RIGHT),
                box(90, 30, 75, MultiFloatText.Side.LEFT));
        var component = new MultiFloatText(text(source), boxes);
        var parts = component.layoutParts(context(140), 120);
        assertTrue(parts.size() > 1);
        assertEquals(source, source(parts));
        assertEquals(3, parts.stream().flatMap(p -> p.commands().stream()).filter(DrawCommand.FillRect.class::isInstance).count());
        assertTrue(parts.getFirst().height() <= 120);
        for (var line : lines(parts.getFirst())) {
            double left = 0;
            double right = 240;
            for (var box : boxes) {
                if (line.bounds().y() < box.top() + box.height() && box.top() < line.bounds().y() + STYLE.lineHeight()) {
                    if (box.side() == MultiFloatText.Side.LEFT) { left = Math.max(left, box.width() + box.gap()); }
                    else { right = Math.min(right, 240 - box.width() - box.gap()); }
                }
            }
            assertTrue(line.bounds().x() >= left - 1e-6);
            assertTrue(line.bounds().x() + line.bounds().width() <= right + 1e-5);
        }
        for (var part : parts.subList(1, parts.size())) {
            assertTrue(lines(part).stream().allMatch(line -> line.bounds().x() == 0));
        }
        var reverse = new ArrayList<>(boxes); java.util.Collections.reverse(reverse);
        assertEquals(lines(parts.getFirst()), lines(new MultiFloatText(text(source), reverse).layoutParts(context(140), 120).getFirst()));
    }

    @Test void closedBandsAreSkippedAndAFullHeightObstacleMakesOneFloatOnlyFragment() {
        var closed = new MultiFloatText(text("A\nB"), List.of(box(120, 40, 0, MultiFloatText.Side.LEFT), box(120, 40, 0, MultiFloatText.Side.RIGHT)));
        var parts = closed.layoutParts(context(100));
        assertEquals(1, parts.size()); assertEquals(80, parts.getFirst().height());
        assertEquals(List.of(40.0, 60.0), lines(parts.getFirst()).stream().map(line -> line.bounds().y()).toList());
        var full = new MultiFloatText(text("A\nB"), List.of(box(240, 100, 0, MultiFloatText.Side.LEFT))).layoutParts(context(100));
        assertEquals(2, full.size()); assertEquals(0, full.getFirst().textLines());
        assertEquals(100, full.getFirst().height()); assertEquals("AB", source(full));
        assertEquals(40, full.getLast().height());
    }

    @Test void remainingHeightMovesTheWholeFloatGroupAndLeavesRoomAfterShortContent() {
        var calls = new AtomicInteger();
        Component child = ctx -> { calls.incrementAndGet(); return BOX.layout(ctx); };
        var component = new MultiFloatText(text("A"), List.of(new MultiFloatText.FloatBox(child, 50, 40, 40, 10, MultiFloatText.Side.RIGHT)));
        assertEquals(80, component.layoutParts(context(100), 60).getFirst().height());
        assertEquals(1, calls.get());
        var settings = new PageSettings(new PageSize(260, 120), PageMargins.uniform(10));
        var document = Document.builder(DocumentMetadata.EMPTY, settings)
                .add(new ComponentBlock("prefix", new BoxComponent(240, 40, BOX), 0, 0))
                .add(new ComponentBlock("float", component, 0, 0))
                .add(new ComponentBlock("tail", new BoxComponent(240, 20, BOX), 0, 0)).build();
        var layout = DocumentEngine.builder(fonts).build().layout(document);
        assertEquals(2, layout.body().pages().size());
        var second = layout.body().pages().get(1).elements();
        assertEquals(List.of("float", "tail"), second.stream().map(e -> e.blockId()).toList());
        assertEquals(90, ((ComponentFragment) second.get(1)).origin().y());
    }

    @Test void emptyTextStillEmitsAllFloatsAndNoFloatListBehavesAsNormalText() {
        var empty = new MultiFloatText(text(""), List.of(box(60, 30, 70, MultiFloatText.Side.RIGHT))).layoutParts(context(100));
        assertEquals(1, empty.size()); assertEquals(100, empty.getFirst().height());
        assertEquals(1, empty.getFirst().commands().stream().filter(DrawCommand.FillRect.class::isInstance).count());
        var emptyFull = new MultiFloatText(text(""), List.of(box(240, 100, 0, MultiFloatText.Side.LEFT))).layoutParts(context(100));
        assertEquals(1, emptyFull.size()); assertEquals(0, emptyFull.getFirst().textLines());
        var emptySmall = new MultiFloatText(text(""), List.of(box(60, 10, 0, MultiFloatText.Side.LEFT))).layoutParts(context(100), 10);
        assertEquals(10, emptySmall.getFirst().height());
        var plain = new MultiFloatText(text("A\nB\nC"), List.of()).layoutParts(context(40), 20);
        assertEquals(List.of(1, 2), plain.stream().map(ComponentLayout::textLines).toList());
        assertEquals("ABC", source(plain));
    }

    @Test void diagnosticsKeepUtf16RangesAcrossContinuationAndChildWarnings() {
        var tolerant = context(60).withFonts(FontGroup.placeholder(fonts.fonts(), '?'));
        String source = "A\udbff\udfff\nB\nC\nD\udbff\udfff";
        var child = new TextComponent("\udbff\udfff", STYLE, RgbColor.BLACK);
        var parts = new MultiFloatText(text(source), List.of(new MultiFloatText.FloatBox(child, 40, 20, 0, 10, MultiFloatText.Side.RIGHT)))
                .layoutParts(tolerant, 40);
        assertEquals(2, parts.size()); assertEquals(2, parts.getFirst().diagnostics().size());
        assertTrue(parts.getFirst().diagnostics().stream().anyMatch(d -> d.sourceStart() == 1 && d.sourceEnd() == 3));
        assertEquals(1, parts.getLast().diagnostics().size());
        assertEquals(source.length() - 2, parts.getLast().diagnostics().getFirst().sourceStart());
        assertEquals(source.length(), parts.getLast().diagnostics().getFirst().sourceEnd());
        assertThrows(IllegalArgumentException.class, () -> new MultiFloatText(text(source), List.of()).layoutParts(context(60)));
    }

    @Test void overlappingOutOfBoundsOversizedAndPathologicalInputsFail() {
        assertThrows(IllegalArgumentException.class, () -> new MultiFloatText.FloatBox(BOX, 10, 10, Double.NaN, 0, MultiFloatText.Side.LEFT));
        assertThrows(IllegalArgumentException.class, () -> new MultiFloatText.FloatBox(BOX, 10, Double.MAX_VALUE, Double.MAX_VALUE, 0, MultiFloatText.Side.LEFT));
        assertThrows(IllegalArgumentException.class, () -> new MultiFloatText(text("A"), java.util.Collections.nCopies(65, box(10, 10, 0, MultiFloatText.Side.LEFT))));
        assertThrows(IllegalArgumentException.class, () -> new MultiFloatText(text("A"), List.of(box(60, 40, 0, MultiFloatText.Side.LEFT), box(60, 40, 20, MultiFloatText.Side.LEFT))).layoutParts(context(100)));
        assertThrows(IllegalArgumentException.class, () -> new MultiFloatText(text("A"), List.of(box(140, 40, 0, MultiFloatText.Side.LEFT), box(140, 40, 0, MultiFloatText.Side.RIGHT))).layoutParts(context(100)));
        assertThrows(IllegalArgumentException.class, () -> new MultiFloatText(text("A"), List.of(box(30, 90, 20, MultiFloatText.Side.LEFT))).layoutParts(context(100)));
        assertThrows(IllegalArgumentException.class, () -> new MultiFloatText(text("A"), List.of(box(250, 20, 0, MultiFloatText.Side.LEFT))).layoutParts(context(100)));
        assertThrows(IllegalArgumentException.class, () -> new MultiFloatText(text("W"), List.of(box(229, 20, 0, MultiFloatText.Side.LEFT))).layoutParts(context(100)));
        assertThrows(IllegalArgumentException.class, () -> new MultiFloatText(text("A"), List.of()).layoutParts(context(10)));
        Component oversize = ctx -> new ComponentLayout(ctx.width() + 1, ctx.height(), 0, List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> new MultiFloatText(text("A"), List.of(new MultiFloatText.FloatBox(oversize, 20, 20, 0, 0, MultiFloatText.Side.LEFT))).layoutParts(context(100)));
        assertThrows(IllegalArgumentException.class, () -> new MultiFloatText(text("A"), List.of()).layoutParts(context(100), -1));
    }

    @Test void borrowedChildrenRemainCooperativeWithCancellation() throws IOException {
        var cancelled = new AtomicBoolean();
        var calls = new AtomicInteger();
        Component child = ctx -> { calls.incrementAndGet(); cancelled.set(true); return BOX.layout(ctx); };
        var boxes = List.of(new MultiFloatText.FloatBox(child, 20, 20, 0, 0, MultiFloatText.Side.LEFT), box(20, 20, 40, MultiFloatText.Side.LEFT));
        var options = new ExecutionOptions(Duration.ZERO, cancelled::get, p -> { }, 1000);
        var failure = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(options,
                () -> new MultiFloatText(text("A"), boxes).layoutParts(context(100))));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code()); assertEquals(1, calls.get());
        ExecutionContext.checkpoint();
    }
}
