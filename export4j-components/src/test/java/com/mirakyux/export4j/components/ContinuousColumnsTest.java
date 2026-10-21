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
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.layout.ComponentFragment;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.ComponentBlock;
import com.mirakyux.export4j.layout.core.FlowRegions;
import com.mirakyux.export4j.layout.core.RegionFlow;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.PaginationPolicy;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextAlignment;

class ContinuousColumnsTest {
    private static FontGroup fonts;
    private static final ParagraphStyle STYLE = new ParagraphStyle(12, 20, 0, 0, TextAlignment.LEFT);
    private static final ShapeComponent PANEL = new ShapeComponent(ShapeComponent.Kind.RECTANGLE,
            new RgbColor(.9, .95, 1), RgbColor.BLACK, 0);
    @BeforeAll static void fonts() throws IOException {
        try (var input = ContinuousColumnsTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            fonts = FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }
    private static ComponentContext context(double height) { return new ComponentContext(240, height, fonts, 0, 0, 1); }
    private static String source(int count) { return IntStream.rangeClosed(1, count).mapToObj(i -> "ROW-" + i).collect(Collectors.joining("\n")); }
    private static FlowText flow(String text) { return new FlowText(text, STYLE, RgbColor.BLACK, PaginationPolicy.DEFAULT); }
    private static String text(List<ComponentLayout> parts) {
        return parts.stream().flatMap(p -> p.commands().stream()).map(command -> switch (command) {
            case DrawCommand.GlyphText glyph -> glyph.run().text();
            case DrawCommand.OutlineText outline -> outline.logicalText();
            default -> "";
        }).collect(Collectors.joining());
    }
    private record Positioned(String source, double x, double y) { }
    private static List<Positioned> positioned(ComponentLayout part) {
        var result = new ArrayList<Positioned>();
        var stack = new ArrayDeque<double[]>();
        double x = 0;
        double y = 0;
        for (var command : part.commands()) {
            if (command instanceof DrawCommand.Save) { stack.push(new double[] {x, y}); }
            else if (command instanceof DrawCommand.Restore) { var saved = stack.pop(); x = saved[0]; y = saved[1]; }
            else if (command instanceof DrawCommand.Transform t) { x += t.matrix().e(); y += t.matrix().f(); }
            else if (command instanceof DrawCommand.GlyphText t) { result.add(new Positioned(t.run().text(), x + t.baseline().x(), y + t.baseline().y())); }
            else if (command instanceof DrawCommand.OutlineText t) { result.add(new Positioned(t.logicalText(), x + t.logicalBounds().x(), y + t.logicalBounds().y())); }
        }
        assertTrue(stack.isEmpty());
        return result;
    }

    @Test void shortFirstColumnsThenFullColumnsConsumeEverySourceLineOnce() {
        var calls = new AtomicInteger();
        RegionFlow child = (ctx, regions) -> { calls.incrementAndGet(); return flow(source(22)).layoutRegions(ctx, regions); };
        var parts = new ContinuousColumns(child, 2, 12).layoutParts(context(120), 60);
        assertEquals(1, calls.get());
        assertEquals(List.of(6, 12, 4), parts.stream().map(ComponentLayout::textLines).toList());
        assertEquals(List.of(60.0, 120.0, 80.0), parts.stream().map(ComponentLayout::height).toList());
        assertEquals(source(22).replace("\n", ""), text(parts));
        assertEquals(List.of(0.0, 0.0, 0.0, 126.0, 126.0, 126.0), positioned(parts.getFirst()).stream().map(Positioned::x).toList());
        assertEquals(20, positioned(parts.getFirst()).get(1).y() - positioned(parts.getFirst()).get(0).y(), 1e-9);
        assertEquals(positioned(parts.getFirst()).get(0).y(), positioned(parts.getFirst()).get(3).y(), 1e-9);
        assertEquals(0, positioned(parts.get(1)).getFirst().x());
    }

    @Test void semanticFlowUsesRemainingHeightAndContinuesAfterTheFinalColumnBlock() {
        var settings = new PageSettings(new PageSize(260, 140), PageMargins.uniform(10));
        var document = Document.builder(DocumentMetadata.EMPTY, settings)
                .add(new ComponentBlock("prefix", new BoxComponent(240, 35, PANEL), 0, 0))
                .add(new ComponentBlock("columns", new ContinuousColumns(flow(source(6)), 2, 12), 0, 0))
                .add(new ComponentBlock("tail", new BoxComponent(240, 5, PANEL), 0, 0)).build();
        var layout = DocumentEngine.builder(fonts).build().layout(document).body();
        assertEquals(1, layout.pages().size());
        var elements = layout.pages().getFirst().elements();
        assertEquals(List.of("prefix", "columns", "tail"), elements.stream().map(e -> e.blockId()).toList());
        assertEquals(45, ((ComponentFragment) elements.get(1)).origin().y());
        assertEquals(80, ((ComponentFragment) elements.get(1)).layout().height());
        assertEquals(125, ((ComponentFragment) elements.get(2)).origin().y());
        var continued = Document.builder(DocumentMetadata.EMPTY, settings)
                .add(new ComponentBlock("prefix", new BoxComponent(240, 35, PANEL), 0, 0))
                .add(new ComponentBlock("columns", new ContinuousColumns(flow(source(22)), 2, 12), 0, 0))
                .add(new ComponentBlock("tail", new BoxComponent(240, 20, PANEL), 0, 0)).build();
        var pages = DocumentEngine.builder(fonts).build().layout(continued).body().pages();
        assertEquals(3, pages.size());
        assertEquals(45, pages.getFirst().elements().get(1).origin().y());
        assertEquals(10, pages.get(1).elements().getFirst().origin().y());
        assertEquals(50, pages.getLast().elements().getLast().origin().y());
    }

    @Test void minimumSplitLinesSkipShortColumnsWithoutEmptyOutputAndKeepTheFinalRemainder() {
        var text = new FlowText(source(5), STYLE, RgbColor.BLACK, PaginationPolicy.BALANCED);
        var regions = text.layoutRegions(context(120), new FlowRegions(List.of(20.0, 20.0), 120));
        assertEquals(1, regions.size()); assertEquals(2, regions.getFirst().regionIndex());
        var parts = new ContinuousColumns(text, 2, 12).layoutParts(context(120), 20);
        assertEquals(1, parts.size()); assertEquals(100, parts.getFirst().height());
        var balanced = new ContinuousColumns(new FlowText(source(7), STYLE, RgbColor.BLACK, PaginationPolicy.BALANCED), 2, 12)
                .layoutParts(context(120), 60);
        assertEquals(List.of(5, 2), balanced.stream().map(ComponentLayout::textLines).toList());
        assertEquals(source(7).replace("\n", ""), text(balanced));
        var impossible = new FlowText(source(7), STYLE, RgbColor.BLACK, new PaginationPolicy(false, false, 4, 4));
        assertThrows(IllegalArgumentException.class, () -> new ContinuousColumns(impossible, 2, 12).layoutParts(context(120), 60));
    }

    @Test void genericRegionPrefixSupportsUnequalAndZeroHeightsAndRetainsLegacyPagination() {
        for (RegionFlow child : List.of(flow(source(8)), new OpenTypeText(source(8), STYLE, RgbColor.BLACK))) {
            var parts = child.layoutRegions(context(80), new FlowRegions(List.of(20.0, 0.0, 60.0), 80));
            assertEquals(List.of(0, 2, 3), parts.stream().map(RegionFlow.Fragment::regionIndex).toList());
            assertEquals(List.of(1, 3, 4), parts.stream().map(p -> p.layout().textLines()).toList());
            assertEquals(source(8).replace("\n", ""), text(parts.stream().map(RegionFlow.Fragment::layout).toList()));
            assertEquals(List.of(1, 4, 3), child.layoutParts(context(80), 20).stream().map(ComponentLayout::textLines).toList());
            assertEquals(List.of(4, 4), child.layoutParts(context(80)).stream().map(ComponentLayout::textLines).toList());
        }
        assertEquals(List.of(4, 4), new ContinuousColumns(flow(source(8)), 2, 12).layoutParts(context(40), 0)
                .stream().map(ComponentLayout::textLines).toList());
    }

    @Test void complexShapingAndSourceOrderSurviveShortAndFullColumns() throws IOException {
        var faces = new ArrayList<FontFace>(fonts.fonts());
        for (String name : List.of("NotoNaskhArabic-Regular.ttf", "LXGWWenKaiTC-Regular.ttf")) {
            try (var input = ContinuousColumnsTest.class.getResourceAsStream("/fonts/" + name)) {
                faces.add(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES));
            }
        }
        String source = "AVATAR العربية لا 中文 a\u0301 ".repeat(30);
        var style = new ParagraphStyle(12, 26, 8, 2, TextAlignment.LEFT);
        var ctx = new ComponentContext(300, 104, FontGroup.strict(faces), 0, 0, 1);
        for (RegionFlow child : List.of(new OpenTypeText(source, style, RgbColor.BLACK), new FlowText(source, style, RgbColor.BLACK, PaginationPolicy.DEFAULT))) {
            var parts = new ContinuousColumns(child, 2, 12).layoutParts(ctx, 52);
            assertTrue(parts.size() > 1);
            // Compare with the same column width before slicing. Adaptive paragraphs consume ASCII
            // spaces at wrap boundaries; region allocation must preserve that existing shaping result.
            var unsliced = child.layoutParts(ctx.viewport(144, 26_000));
            assertEquals(text(unsliced), text(parts));
            assertEquals(source.replace(" ", ""), text(parts).replace(" ", ""));
            if (child instanceof OpenTypeText) { assertEquals(source, text(parts)); }
            assertEquals(8, positioned(parts.getFirst()).getFirst().x(), 1e-5);
            assertEquals(2, positioned(parts.get(1)).getFirst().x(), 1e-5);
            assertTrue(parts.stream().flatMap(p -> p.commands().stream()).filter(DrawCommand.OutlineText.class::isInstance).count() > 20);
        }
    }

    @Test void missingGlyphDiagnosticsPreserveOriginalUtf16PositionsAndFinalPageContext() {
        String source = "A\udbff\udfff\nB\nC\nD\udbff\udfff";
        var ctx = context(60).withFonts(FontGroup.placeholder(fonts.fonts(), '?'));
        for (RegionFlow child : List.of(flow(source), new OpenTypeText(source, STYLE, RgbColor.BLACK))) {
            var parts = new ContinuousColumns(child, 2, 12).layoutParts(ctx, 20);
            assertEquals(2, parts.size()); assertEquals(1, parts.getFirst().diagnostics().size()); assertEquals(1, parts.getLast().diagnostics().size());
            assertEquals(1, parts.getFirst().diagnostics().getFirst().sourceStart()); assertEquals(3, parts.getFirst().diagnostics().getFirst().sourceEnd());
            assertEquals(source.length() - 2, parts.getLast().diagnostics().getFirst().sourceStart());
            assertEquals(source.length(), parts.getLast().diagnostics().getFirst().sourceEnd());
        }
        assertThrows(IllegalArgumentException.class, () -> new ContinuousColumns(flow(source), 2, 12).layoutParts(context(60), 20));
        var settings = new PageSettings(new PageSize(260, 80), PageMargins.uniform(10));
        var document = Document.builder(DocumentMetadata.EMPTY, settings)
                .add(new ComponentBlock("prefix", new BoxComponent(240, 40, PANEL), 0, 0))
                .add(new ComponentBlock("columns", new ContinuousColumns(flow(source), 2, 12), 0, 0)).build();
        var layout = DocumentEngine.builder(ctx.fontGroup()).build().layout(document);
        assertEquals(List.of(0, 1), layout.contextualDiagnostics().stream().filter(d -> d.blockId().equals("columns")).map(ContextualDiagnostic::pageIndex).toList());
    }

    @Test void invalidGeometryRegionBudgetsAndMalformedCustomAssignmentsFail() {
        var unit = new ComponentLayout(10, 10, 0, List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> new FlowRegions(List.of(Double.NaN), 100));
        assertThrows(IllegalArgumentException.class, () -> new FlowRegions(List.of(-1.0), 100));
        assertThrows(IllegalArgumentException.class, () -> new FlowRegions(List.of(101.0), 100));
        assertThrows(IllegalArgumentException.class, () -> new FlowRegions(java.util.Collections.nCopies(17, 10.0), 100));
        assertThrows(IllegalArgumentException.class, () -> new ContinuousColumns(flow("A"), 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ContinuousColumns(flow("A"), 2, 240).layoutParts(context(100)));
        assertThrows(IllegalArgumentException.class, () -> new ContinuousColumns(flow("A"), 2, 12).layoutParts(context(10)));
        assertThrows(IllegalArgumentException.class, () -> new ContinuousColumns(flow("A"), 2, 12).layoutParts(context(100), Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new ContinuousColumns(flow(source(30)), 2, 12).layout(context(100)));
        for (List<RegionFlow.Fragment> bad : List.of(List.<RegionFlow.Fragment>of(),
                List.of(new RegionFlow.Fragment(0, unit), new RegionFlow.Fragment(0, unit)),
                List.of(new RegionFlow.Fragment(0, unit), new RegionFlow.Fragment(3, unit)),
                List.of(new RegionFlow.Fragment(2, unit)),
                List.of(new RegionFlow.Fragment(0, new ComponentLayout(200, 10, 0, List.of(), List.of()))),
                List.of(new RegionFlow.Fragment(0, new ComponentLayout(10, 90, 0, List.of(), List.of()))),
                java.util.Collections.nCopies(100_001, new RegionFlow.Fragment(0, unit)))) {
            RegionFlow custom = (ctx, regions) -> bad;
            assertThrows(IllegalArgumentException.class, () -> new ContinuousColumns(custom, 2, 12).layoutParts(context(100), 20));
        }
    }

    @Test void cancellationAtCustomFlowBoundaryRestoresTheCallingScope() {
        var cancelled = new AtomicBoolean();
        RegionFlow custom = (ctx, regions) -> { cancelled.set(true); return List.of(new RegionFlow.Fragment(0, new ComponentLayout(10, 10, 0, List.of(), List.of()))); };
        var options = new ExecutionOptions(Duration.ZERO, cancelled::get, p -> { }, 1000);
        var failure = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(options,
                () -> new ContinuousColumns(custom, 2, 12).layoutParts(context(100), 20)));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code());
        ExecutionContext.checkpoint();
    }
}
