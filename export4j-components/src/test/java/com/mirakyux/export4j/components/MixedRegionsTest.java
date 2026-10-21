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
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.RgbImage;
import com.mirakyux.export4j.layout.ComponentFragment;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentBlock;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.FlowRegions;
import com.mirakyux.export4j.layout.core.PageableComponent;
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

class MixedRegionsTest {
    private static FontGroup fonts;
    private static final ParagraphStyle STYLE = new ParagraphStyle(12, 20, 0, 0, TextAlignment.LEFT);
    @BeforeAll static void fonts() throws IOException {
        try (var input = MixedRegionsTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            fonts = FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }
    private static ComponentContext context(double height) { return new ComponentContext(240, height, fonts, 0, 0, 1); }
    private static TextComponent text(String value) { return new TextComponent(value, STYLE, RgbColor.BLACK); }
    private static FlowText flow(String value) { return new FlowText(value, STYLE, RgbColor.BLACK, PaginationPolicy.DEFAULT); }
    private static BoxComponent box(double height) {
        return new BoxComponent(40, height, new ShapeComponent(ShapeComponent.Kind.RECTANGLE, RgbColor.WHITE, RgbColor.BLACK, 0));
    }
    private static FlowColumn column(List<Component> children, double padding, double gap) {
        return new FlowColumn(children, PageMargins.uniform(padding), gap, FlowColumn.Alignment.START);
    }
    private static GridTable.Cell cell(int row, int col, int span, Component content) {
        return new GridTable.Cell(row, col, span, 1, content, PageMargins.uniform(0), FlowColumn.Alignment.START,
                RgbColor.WHITE, RgbColor.BLACK, .5);
    }
    private static GridTable table(int rows, AtomicInteger measured) {
        List<GridTable.Cell> cells = new ArrayList<>();
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < 2; col++) {
                String value = (row == 0 ? "HEAD" : "ROW-" + row) + (col == 0 ? "-L" : "-R");
                cells.add(cell(row, col, 1, ctx -> { measured.incrementAndGet(); return text(value).layout(ctx); }));
            }
        }
        return new GridTable(rows, List.of(1.0, 1.0), 1, cells);
    }
    private record Positioned(String text, double x, double y) { }
    private static List<Positioned> positions(ComponentLayout part) {
        var result = new ArrayList<Positioned>();
        var stack = new ArrayDeque<double[]>();
        double x = 0, y = 0;
        for (var command : part.commands()) {
            if (command instanceof DrawCommand.Save) { stack.push(new double[] {x, y}); }
            else if (command instanceof DrawCommand.Restore) { var old = stack.pop(); x = old[0]; y = old[1]; }
            else if (command instanceof DrawCommand.Transform t) { x += t.matrix().e(); y += t.matrix().f(); }
            else if (command instanceof DrawCommand.GlyphText t) { result.add(new Positioned(t.run().text(), x + t.baseline().x(), y + t.baseline().y())); }
        }
        assertTrue(stack.isEmpty());
        return result;
    }
    private static List<String> values(List<ComponentLayout> parts) {
        return parts.stream().flatMap(p -> positions(p).stream()).map(Positioned::text).toList();
    }

    @Test void imageTextAndFollowingComponentUseEveryShortColumnAndMeasureOnlyOnce() {
        var imageCalls = new AtomicInteger();
        var image = new ImageComponent(new RgbImage(1, 1, new byte[] {20, 80, (byte) 200}), ImageComponent.Fit.STRETCH);
        Component picture = ctx -> { imageCalls.incrementAndGet(); return new BoxComponent(40, 30, image).layout(ctx); };
        var mixed = column(List.of(picture, flow("A\nB\nC\nD\nE\nF"), box(10)), 2, 4);
        var parts = new ContinuousColumns(mixed, 2, 12).layoutParts(context(120), 60);
        assertEquals(List.of(60.0, 78.0), parts.stream().map(ComponentLayout::height).toList());
        assertEquals(List.of("A", "B", "C", "D", "E", "F"), values(parts));
        assertEquals(1, imageCalls.get());
        assertEquals(1, parts.stream().flatMap(p -> p.commands().stream()).filter(DrawCommand.Image.class::isInstance).count());
        var first = positions(parts.getFirst());
        assertEquals(List.of(2.0, 128.0, 128.0), first.stream().map(Positioned::x).toList());
        double ascent = fonts.fonts().getFirst().metrics().ascent() * STYLE.fontSize();
        assertEquals(36 + ascent, first.getFirst().y(), 1e-9);
        assertEquals(2 + ascent, first.get(1).y(), 1e-9);
    }

    @Test void tableRepeatsHeadersPerOccupiedColumnWithoutRemeasuringOrRepeatingBodyRows() {
        var calls = new AtomicInteger();
        var parts = new ContinuousColumns(table(8, calls), 2, 12).layoutParts(context(120), 60);
        assertEquals(List.of(60.0, 80.0), parts.stream().map(ComponentLayout::height).toList());
        assertEquals(16, calls.get());
        var actual = values(parts);
        assertEquals(3, actual.stream().filter("HEAD-L"::equals).count());
        assertEquals(3, actual.stream().filter("HEAD-R"::equals).count());
        var body = IntStream.rangeClosed(1, 7).boxed().flatMap(row -> List.of("ROW-" + row + "-L", "ROW-" + row + "-R").stream()).toList();
        assertEquals(body, actual.stream().filter(v -> v.startsWith("ROW")).toList());
        assertEquals(3, parts.stream().flatMap(p -> p.commands().stream()).filter(DrawCommand.Clip.class::isInstance).count());
    }

    @Test void mergedGroupsSkipTooShortRegionsAndNeverProduceHeaderOnlyFragments() {
        var table = new GridTable(4, List.of(1.0, 1.0), 1, List.of(
                cell(0, 0, 1, text("H-L")), cell(0, 1, 1, text("H-R")),
                cell(1, 0, 2, new BoxComponent(80, 40, text("MERGED"))),
                cell(1, 1, 1, text("A")), cell(2, 1, 1, text("B")),
                cell(3, 0, 1, text("C")), cell(3, 1, 1, text("D"))));
        var regions = table.layoutRegions(context(120), new FlowRegions(List.of(40.0, 40.0), 120));
        assertEquals(List.of(2), regions.stream().map(RegionFlow.Fragment::regionIndex).toList());
        assertEquals(List.of("H-L", "H-R", "MERGED", "A", "B", "C", "D"), values(regions.stream().map(RegionFlow.Fragment::layout).toList()));
        assertEquals(80, regions.getFirst().layout().height());
        assertThrows(IllegalArgumentException.class, () -> table.layoutRegions(context(50), new FlowRegions(List.of(0.0), 50)));
    }

    @Test void nestedPaddingAndAlignmentPreserveRegionIndicesAndLineOrder() {
        var body = IntStream.rangeClosed(1, 9).mapToObj(i -> "T" + i).collect(Collectors.joining("\n"));
        var inner = column(List.of(flow(body)), 1, 3);
        var outer = new FlowColumn(List.of(inner), PageMargins.uniform(1), 5, FlowColumn.Alignment.CENTER);
        var regions = outer.layoutRegions(context(120), new FlowRegions(List.of(65.0, 65.0), 120));
        assertEquals(List.of(0, 1, 2), regions.stream().map(RegionFlow.Fragment::regionIndex).toList());
        assertEquals(List.of(64.0, 64.0, 64.0), regions.stream().map(f -> f.layout().height()).toList());
        assertEquals(IntStream.rangeClosed(1, 9).mapToObj(i -> "T" + i).toList(), values(regions.stream().map(RegionFlow.Fragment::layout).toList()));
        assertEquals(2, positions(regions.getFirst().layout()).getFirst().x(), 1e-9);
    }

    @Test void legacyPageablePartsRemainAtomicAndGapsResetAtRegionBoundaries() {
        var calls = new AtomicInteger();
        PageableComponent legacy = ctx -> { calls.incrementAndGet(); return List.of(box(30).layout(ctx), box(70).layout(ctx)); };
        var mixed = column(List.of(legacy, text("TAIL")), 0, 3);
        var regions = mixed.layoutRegions(context(100), new FlowRegions(List.of(50.0, 50.0), 100));
        assertEquals(1, calls.get());
        assertEquals(List.of(0, 2), regions.stream().map(RegionFlow.Fragment::regionIndex).toList());
        assertEquals(List.of(30.0, 93.0), regions.stream().map(f -> f.layout().height()).toList());
        var gaps = column(List.of(box(30), box(50)), 0, 25).layoutRegions(context(100), new FlowRegions(List.of(60.0, 60.0), 100));
        assertEquals(List.of(30.0, 50.0), gaps.stream().map(f -> f.layout().height()).toList());
        var zero = column(List.of(box(20)), 2, 0).layoutRegions(context(100), new FlowRegions(List.of(0.0, 4.0, 30.0), 100));
        assertEquals(2, zero.getFirst().regionIndex()); assertEquals(24, zero.getFirst().layout().height());
    }

    @Test void semanticFlowPlacesTheFollowingBlockAtTheActualLastMixedHeight() {
        var mixed = column(List.of(box(30), flow("A\nB\nC\nD\nE\nF"), box(10)), 2, 4);
        var settings = new PageSettings(new PageSize(260, 140), PageMargins.uniform(10));
        var document = Document.builder(DocumentMetadata.EMPTY, settings)
                .add(new ComponentBlock("prefix", box(60), 0, 0))
                .add(new ComponentBlock("columns", new ContinuousColumns(mixed, 2, 12), 0, 0))
                .add(new ComponentBlock("tail", text("AFTER"), 0, 0)).build();
        var layout = DocumentEngine.builder(fonts).build().layout(document).body();
        assertEquals(2, layout.pages().size());
        assertEquals(70, ((ComponentFragment) layout.pages().getFirst().elements().get(1)).origin().y());
        assertEquals(88, ((ComponentFragment) layout.pages().getLast().elements().getLast()).origin().y());
    }

    @Test void repeatedHeaderWarningsAndFinalPageContextRetainOriginalUtf16Ranges() {
        var placeholder = FontGroup.placeholder(fonts.fonts(), '?');
        var cells = new ArrayList<GridTable.Cell>();
        cells.add(cell(0, 0, 1, text("H\udbff\udfff")));
        for (int row = 1; row <= 7; row++) { cells.add(cell(row, 0, 1, text("R" + row))); }
        var table = new GridTable(8, List.of(1.0), 1, cells);
        var mixed = column(List.of(table), 0, 0);
        var parts = new ContinuousColumns(mixed, 2, 12).layoutParts(context(120).withFonts(placeholder), 60);
        assertEquals(List.of(2, 1), parts.stream().map(p -> p.diagnostics().size()).toList());
        assertTrue(parts.stream().flatMap(p -> p.diagnostics().stream()).allMatch(d -> d.sourceStart() == 1 && d.sourceEnd() == 3));
        assertThrows(IllegalArgumentException.class, () -> new ContinuousColumns(mixed, 2, 12).layoutParts(context(120), 60));
        var settings = new PageSettings(new PageSize(260, 140), PageMargins.uniform(10));
        var document = Document.builder(DocumentMetadata.EMPTY, settings).add(new ComponentBlock("prefix", box(60), 0, 0))
                .add(new ComponentBlock("columns", new ContinuousColumns(mixed, 2, 12), 0, 0)).build();
        var layout = DocumentEngine.builder(placeholder).build().layout(document);
        assertEquals(List.of(0, 0, 1), layout.contextualDiagnostics().stream().map(d -> d.pageIndex()).toList());
        assertTrue(layout.contextualDiagnostics().stream().allMatch(d -> d.blockId().equals("columns")));
    }

    @Test void malformedChildrenInvalidPaddingAndUnfitAtomicContentFailWithoutRetry() {
        var unit = new ComponentLayout(10, 10, 0, List.of(), List.of());
        for (List<RegionFlow.Fragment> bad : List.of(List.<RegionFlow.Fragment>of(),
                List.of(new RegionFlow.Fragment(0, unit), new RegionFlow.Fragment(0, unit)),
                List.of(new RegionFlow.Fragment(3, unit)),
                List.of(new RegionFlow.Fragment(0, new ComponentLayout(241, 10, 0, List.of(), List.of()))),
                java.util.Collections.nCopies(100_001, new RegionFlow.Fragment(0, unit)))) {
            RegionFlow custom = (ctx, regions) -> bad;
            assertThrows(IllegalArgumentException.class, () -> column(List.of(custom), 0, 0).layoutParts(context(100), 30));
        }
        assertThrows(IllegalArgumentException.class, () -> column(List.of(box(20)), 50, 0).layoutParts(context(100)));
        assertThrows(IllegalArgumentException.class, () -> column(List.of(box(101)), 0, 0).layoutParts(context(100), 0));
        PageableComponent empty = ctx -> List.of();
        assertThrows(IllegalArgumentException.class, () -> column(List.of(empty), 0, 0).layoutParts(context(100)));
        assertThrows(IllegalArgumentException.class, () -> new ContinuousColumns(table(8, new AtomicInteger()), 2, 12).layout(context(40)));
    }

    @Test void cancellationAtCellAndChildBoundariesRestoresTheCallingScope() {
        for (boolean grid : List.of(false, true)) {
            var cancelled = new AtomicBoolean();
            Component child = ctx -> { cancelled.set(true); return text("STOP").layout(ctx); };
            RegionFlow content = grid ? new GridTable(2, List.of(1.0), 0, List.of(cell(0, 0, 1, child), cell(1, 0, 1, text("NEXT"))))
                    : column(List.of(child), 0, 0);
            var options = new ExecutionOptions(Duration.ZERO, cancelled::get, p -> { }, 1000);
            var failure = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(options,
                    () -> content.layoutRegions(context(100), new FlowRegions(List.of(30.0, 30.0), 100))));
            assertEquals(ExecutionException.Code.CANCELLED, failure.code());
            ExecutionContext.checkpoint();
        }
    }
}
