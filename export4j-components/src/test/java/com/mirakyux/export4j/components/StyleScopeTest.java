// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.MissingGlyphPolicy;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentBlock;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.FlowRegions;
import com.mirakyux.export4j.layout.core.PageableComponent;
import com.mirakyux.export4j.layout.core.RegionFlow;
import com.mirakyux.export4j.model.PaginationPolicy;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextAlignment;
import com.mirakyux.export4j.text.TextDiagnostic;
import com.mirakyux.export4j.text.TextLayoutException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StyleScopeTest {
    private static FontGroup fonts;
    private static final ParagraphStyle STYLE = ParagraphStyle.builder().fontSize(10).lineHeight(20).build();
    private static final ParagraphStyle LARGE = ParagraphStyle.builder().fontSize(16).lineHeight(24).build();
    private static final RgbColor BLUE = new RgbColor(0, 0, 1);
    private static final RgbColor RED = new RgbColor(1, 0, 0);

    @BeforeAll static void fonts() throws IOException {
        try (var input = StyleScopeTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            fonts = FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }

    private static ComponentContext context(double height) {
        return new ComponentContext(240, height, fonts, 0, 0, 1);
    }

    private static String logical(ComponentLayout part) {
        return part.commands().stream().map(command -> {
            if (command instanceof DrawCommand.GlyphText text) { return text.run().text(); }
            if (command instanceof DrawCommand.OutlineText text) { return text.logicalText(); }
            return "";
        }).collect(Collectors.joining());
    }

    private static List<DrawCommand.GlyphText> glyphs(ComponentLayout part) {
        return part.commands().stream().filter(DrawCommand.GlyphText.class::isInstance)
                .map(DrawCommand.GlyphText.class::cast).toList();
    }

    private static List<RgbColor> textColors(ComponentLayout part) {
        return part.commands().stream().filter(command -> command instanceof DrawCommand.GlyphText
                || command instanceof DrawCommand.OutlineText).map(command -> {
                    if (command instanceof DrawCommand.GlyphText text) { return text.color(); }
                    return ((DrawCommand.OutlineText) command).color();
                }).toList();
    }

    @Test void defaultsReachNestedTextGridAndStaticFormLabels() {
        var content = FlowColumn.builder().text("TOP").fixedText("FIXED")
                .column(inner -> inner.formText("☑ FORM").choices(choices -> choices.option("OPTION", true))
                        .labelValue("LABEL", "VALUE"))
                .grid(grid -> grid.defaults(cell -> cell.padding(0)).row(row -> row.cell("CELL"))).build();
        var part = StyleScope.builder(content).style(STYLE).color(BLUE).build().layout(context(200));
        assertEquals(120, part.height());
        assertEquals("TOPFIXED☑ FORM☑ OPTIONLABELVALUECELL", logical(part));
        assertFalse(glyphs(part).isEmpty());
        assertTrue(glyphs(part).stream().allMatch(text -> text.run().fontSize() == 10));
        assertTrue(textColors(part).stream().allMatch(BLUE::equals));
        assertTrue(part.commands().stream().filter(DrawCommand.FillPath.class::isInstance)
                .map(DrawCommand.FillPath.class::cast).allMatch(path -> path.color().equals(BLUE)));
    }

    @Test void constructorsAndExplicitTwelvePointStylesDoNotBecomeDefaults() {
        var content = FlowColumn.builder()
                .add(new FlowText("CTOR", ParagraphStyle.of(12), RgbColor.BLACK, PaginationPolicy.DEFAULT))
                .text("BUILDER", text -> text.style(ParagraphStyle.of(12))).build();
        var part = StyleScope.builder(content).style(STYLE).color(BLUE).build().layout(context(100));
        assertEquals(36, part.height());
        assertEquals(List.of(12.0, 12.0), glyphs(part).stream().map(text -> text.run().fontSize()).toList());
        assertEquals(List.of(RgbColor.BLACK, BLUE), textColors(part));
    }

    @Test void explicitColorStillInheritsTypography() {
        var part = StyleScope.builder(TextComponent.builder("COLOR").color(RED).build())
                .style(STYLE).color(BLUE).build().layout(context(100));
        assertEquals(20, part.height());
        assertEquals(10, glyphs(part).getFirst().run().fontSize());
        assertEquals(List.of(RED), textColors(part));
    }

    @Test void toBuilderPreservesUnsetPropertiesAndCanRestoreInheritance() {
        var flow = FlowText.builder("FLOW").color(RED).build();
        var fixed = TextComponent.builder("FIXED").build();
        var openType = OpenTypeText.builder("OPEN").build();
        var form = FormText.builder("☑ FORM").build();
        var choices = ChoiceGroup.builder().option("OPTION", false).build();
        var label = LabelValue.builder("LABEL", "VALUE").build();
        var page = PageNumberComponent.builder("{page}/{pages}").build();
        List<Component> original = List.of(flow, fixed, openType, form, choices, label, page);
        List<Component> copies = List.of(flow.toBuilder().build(), fixed.toBuilder().build(), openType.toBuilder().build(),
                form.toBuilder().build(), choices.toBuilder().build(), label.toBuilder().build(), page.toBuilder().build());
        var finalContext = new ComponentContext(240, 100, fonts, 0, 1, 7).withTextDefaults(LARGE, BLUE);
        for (int index = 0; index < original.size(); index++) {
            var expected = original.get(index).layout(finalContext);
            assertEquals(24, expected.height());
            assertEquals(expected, copies.get(index).layout(finalContext));
        }
        var restored = FlowText.builder("RESTORED").style(ParagraphStyle.of(12)).color(RgbColor.BLACK)
                .inheritStyle().inheritColor().build().toBuilder().build().layout(finalContext);
        assertEquals(24, restored.height());
        assertEquals(List.of(BLUE), textColors(restored));
        assertEquals(16, glyphs(restored).getFirst().run().fontSize());
    }

    @Test void nestedColorOnlyScopePreservesOuterTypographyAndSiblingColor() {
        var inner = StyleScope.builder(FlowText.builder("INNER").build()).color(RED).build();
        var content = FlowColumn.builder().add(inner).text("SIBLING").build();
        var part = StyleScope.builder(content).style(STYLE).color(BLUE).build().layout(context(100));
        assertEquals(40, part.height());
        assertEquals(List.of(RED, BLUE), textColors(part));
        assertTrue(glyphs(part).stream().allMatch(text -> text.run().fontSize() == 10));
    }

    @Test void documentBodyDefaultsSnapshotAtAdditionAndDoNotRewriteRawBlocks() {
        var retained = new AtomicReference<ParagraphFormat.Builder>();
        var builder = Documents.builder().bodyFormat(format -> {
            retained.set(format); format.fontSize(10).lineHeight(20).spaceAfter(0);
        }).column(column -> column.text("EARLY"));
        retained.get().fontSize(22).lineHeight(33);
        var document = builder.bodyFormat(format -> format.fontSize(16).lineHeight(24))
                .grid(grid -> grid.defaults(cell -> cell.padding(0)).row(row -> row.cell("LATE")))
                .paragraph("BODY").add(new ComponentBlock("raw", FlowText.builder("RAW").build(), 0, 0)).build();
        var commands = DocumentEngine.builder(fonts).build().layout(document).display().pages().getFirst().commands();
        var runs = commands.stream().filter(DrawCommand.GlyphText.class::isInstance).map(DrawCommand.GlyphText.class::cast)
                .collect(Collectors.toMap(text -> text.run().text(), text -> text.run().fontSize()));
        assertEquals(Map.of("EARLY", 10.0, "LATE", 16.0, "BODY", 16.0, "RAW", 12.0), runs);
    }

    @Test void splitGridInheritsLocalDefaultsAndRepeatsStyledHeadersWithoutLosingBody() {
        String source = IntStream.rangeClosed(1, 19).mapToObj(index -> "☑ LINE" + index).collect(Collectors.joining("\n"));
        var grid = GridTable.builder().headerRows(1).allowRowSplit(true)
                .defaults(cell -> cell.padding(0).textStyle(STYLE).textColor(BLUE))
                .row(row -> row.cell("HEAD"))
                .row(row -> row.cell(FormText.builder(source).build(), cell -> cell.rowSpan(2)))
                .row(row -> { }).build();
        var parts = StyleScope.builder(grid).style(LARGE).color(RED).build()
                .layoutRegions(context(100), new FlowRegions(List.of(15.0, 60.0), 100));
        assertEquals(1, parts.getFirst().regionIndex());
        assertTrue(parts.size() > 3);
        assertEquals(source.replace("\n", ""), parts.stream().map(part -> logical(part.layout()))
                .collect(Collectors.joining()).replace("HEAD", ""));
        for (var fragment : parts) {
            assertTrue(logical(fragment.layout()).startsWith("HEAD"));
            assertTrue(fragment.layout().height() <= (fragment.regionIndex() == 1 ? 60 : 100));
            assertTrue(glyphs(fragment.layout()).stream().allMatch(text -> text.run().fontSize() == 10));
            assertTrue(textColors(fragment.layout()).stream().allMatch(BLUE::equals));
        }
    }

    @Test void cellFactoryDefaultsReachUnsetLeavesAndRespectExplicitLeavesAndSnapshots() {
        var builder = GridTable.builder().defaults(cell -> cell.padding(0).textStyle(STYLE).textColor(BLUE)
                .textFactory(text -> FlowColumn.builder().text(text).build()))
                .row(row -> row.cell("FACTORY"));
        var grid = builder.defaults(cell -> cell.textStyle(LARGE).textColor(RED))
                .row(row -> row.cell("NEXT"))
                .row(row -> row.cell(FlowText.builder("EXPLICIT").style(ParagraphStyle.of(12)).color(RgbColor.BLACK).build()))
                .build();
        var part = StyleScope.builder(grid).style(ParagraphStyle.of(8)).color(RgbColor.WHITE).build().layout(context(100));
        assertEquals(62, part.height());
        assertEquals(List.of(10.0, 16.0, 12.0), glyphs(part).stream().map(text -> text.run().fontSize()).toList());
        assertEquals(List.of(BLUE, RED, RgbColor.BLACK), textColors(part));
    }

    @Test void changingViewportAndFontsPreservesDefaultsRolesAndFinalPageContext() {
        var role = FontGroup.builder().fonts(fonts.fonts()).missingGlyphPolicy(MissingGlyphPolicy.PLACEHOLDER_WITH_WARNING).build();
        var original = new ComponentContext(240, 100, fonts, 1, 3, 7, Map.of("body", fonts))
                .withTextDefaults(STYLE, BLUE);
        var changed = original.viewport(120, 80).withFonts(role);
        var part = TextComponent.builder("CONTEXT").build().layout(changed);
        assertEquals(20, part.height());
        assertEquals(10, glyphs(part).getFirst().run().fontSize());
        assertEquals(List.of(BLUE), textColors(part));
        assertEquals(Map.of("body", fonts), changed.fontRoles());
        assertEquals(role, changed.fontGroup());
        assertEquals(1, changed.pageIndex());
        assertEquals(3, changed.pageCount());
        assertEquals(7, changed.displayPageNumber());
        assertEquals(240, original.width());
    }

    @Test void styleAndFontScopesPreserveEveryShortInitialRegion() {
        String source = IntStream.rangeClosed(1, 7).mapToObj(index -> "LINE" + index).collect(Collectors.joining("\n"));
        var content = new FontScope(StyleScope.builder(FlowText.builder(source).build()).style(STYLE).color(BLUE).build(), fonts);
        var parts = content.layoutRegions(context(80), new FlowRegions(List.of(5.0, 40.0), 80));
        assertEquals(List.of(1, 2, 3), parts.stream().map(RegionFlow.Fragment::regionIndex).toList());
        assertEquals(List.of(40.0, 80.0, 20.0), parts.stream().map(part -> part.layout().height()).toList());
        assertEquals(source.replace("\n", ""), parts.stream().map(part -> logical(part.layout())).collect(Collectors.joining()));
        assertTrue(parts.stream().flatMap(part -> textColors(part.layout()).stream()).allMatch(BLUE::equals));
    }

    @Test void inheritedFormStyleKeepsOriginalMissingGlyphOffsetsAcrossScopeOrders() {
        var replacement = FontGroup.builder().fonts(fonts.fonts()).missingGlyphPolicy(MissingGlyphPolicy.PLACEHOLDER_WITH_WARNING)
                .placeholderCodePoint('?').build();
        var text = FormText.builder("☑ A\uD83D\uDE00\n□ B\uD83D\uDE00").build();
        var first = new FontScope(StyleScope.builder(text).style(STYLE).color(BLUE).build(), replacement);
        var second = StyleScope.builder(new FontScope(text, replacement)).style(STYLE).color(BLUE).build();
        var regions = new FlowRegions(List.of(5.0, 20.0), 20);
        var a = first.layoutRegions(context(20), regions);
        assertEquals(a, second.layoutRegions(context(20), regions));
        var diagnostics = a.stream().flatMap(part -> part.layout().diagnostics().stream())
                .filter(diagnostic -> diagnostic.code() == TextDiagnostic.Code.MISSING_GLYPH_REPLACED).toList();
        assertEquals(List.of(3, 9), diagnostics.stream().map(TextDiagnostic::sourceStart).toList());
        assertEquals(List.of(5, 11), diagnostics.stream().map(TextDiagnostic::sourceEnd).toList());
        assertEquals(2, diagnostics.size());
    }

    @Test void inheritedLineFrameStillFailsInsteadOfShrinkingOrClipping() {
        var invalid = ParagraphStyle.builder().fontSize(10).lineHeight(2).build();
        var form = StyleScope.builder(FormText.builder("☑ LABEL").build()).style(invalid).build();
        var failure = assertThrows(TextLayoutException.class, () -> form.layout(context(100)));
        assertEquals(TextLayoutException.Code.LINE_HEIGHT_TOO_SMALL, failure.code());
        var grid = GridTable.builder().allowRowSplit(true).defaults(cell -> cell.padding(0))
                .row(row -> row.cell(FormText.builder("☑ CELL").build())).build();
        assertThrows(TextLayoutException.class, () -> StyleScope.builder(grid).style(invalid).build().layoutParts(context(100)));
    }

    @Test void styleCallbackDeclaresACompleteStyleAndInheritanceCanBeRestored() {
        var declared = FlowText.builder("RIGHT").style(style -> style.alignment(TextAlignment.RIGHT)).build();
        var scoped = StyleScope.builder(declared).style(LARGE).color(BLUE).build().layout(context(100));
        assertEquals(18, scoped.height());
        assertEquals(12, glyphs(scoped).getFirst().run().fontSize());
        assertTrue(glyphs(scoped).getFirst().baseline().x() > 100);
        var restored = StyleScope.builder(declared.toBuilder().inheritStyle().build()).style(LARGE).color(BLUE).build()
                .layout(context(100));
        assertEquals(24, restored.height());
        assertEquals(16, glyphs(restored).getFirst().run().fontSize());
        assertEquals(0, glyphs(restored).getFirst().baseline().x());
    }

    @Test void floatingOpenTypeTextUsesInheritedLineStepBeforeAllocatingSlots() {
        var floating = MultiFloatText.builder(OpenTypeText.builder("FLOAT-A\nFLOAT-B\nFLOAT-C").build())
                .floating(box -> box.content(new Spacer(20)).width(40).height(20).gap(4)).build();
        var parts = StyleScope.builder(floating).style(STYLE).color(BLUE).build().layoutParts(context(40), 20);
        assertEquals(List.of(20.0, 40.0), parts.stream().map(ComponentLayout::height).toList());
        assertEquals("FLOAT-AFLOAT-BFLOAT-C", parts.stream().map(StyleScopeTest::logical).collect(Collectors.joining()));
        assertTrue(parts.stream().flatMap(part -> textColors(part).stream()).allMatch(BLUE::equals));
    }

    @Test void scopedNarrowAtomicChildrenKeepTheirWidthAndColumnAlignment() {
        var mark = CheckMark.builder().selected(true).size(9).build();
        List<RegionFlow> scopes = List.of(StyleScope.builder(mark).style(STYLE).color(BLUE).build(),
                new FontScope(mark, fonts), StyleScope.builder(new FontScope(mark, fonts)).style(STYLE).build());
        for (var scope : scopes) {
            assertEquals(9, scope.layout(context(100)).width());
            assertEquals(List.of(9.0), scope.layoutParts(context(100), 4).stream().map(ComponentLayout::width).toList());
            var regions = scope.layoutRegions(context(20), new FlowRegions(List.of(4.0, 10.0), 20));
            assertEquals(List.of(1), regions.stream().map(RegionFlow.Fragment::regionIndex).toList());
            assertEquals(9, regions.getFirst().layout().width());
            for (var alignment : List.of(FlowColumn.Alignment.CENTER, FlowColumn.Alignment.END)) {
                var column = FlowColumn.builder().alignment(alignment).add(scope).build().layout(context(100));
                var placement = column.commands().stream().filter(DrawCommand.Transform.class::isInstance)
                        .map(DrawCommand.Transform.class::cast).findFirst().orElseThrow();
                assertEquals(alignment == FlowColumn.Alignment.CENTER ? 115.5 : 231, placement.matrix().e());
                assertEquals(9, column.height());
            }
        }
    }

    @Test void scopedLegacyPageablePartsKeepNarrowWidthsAcrossAllShortRegions() {
        var measurements = new AtomicInteger();
        PageableComponent legacy = viewport -> {
            measurements.incrementAndGet();
            return List.of(CheckMark.builder().size(9).build().layout(viewport),
                    CheckMark.builder().size(12).build().layout(viewport));
        };
        List<RegionFlow> scopes = List.of(StyleScope.builder(legacy).style(STYLE).build(),
                new FontScope(legacy, fonts), new FontScope(StyleScope.builder(legacy).color(BLUE).build(), fonts));
        for (var scope : scopes) {
            measurements.set(0);
            assertEquals(List.of(9.0, 12.0), scope.layoutParts(context(20)).stream().map(ComponentLayout::width).toList());
            assertEquals(1, measurements.get());
            measurements.set(0);
            var parts = scope.layoutRegions(context(20), new FlowRegions(List.of(4.0, 10.0, 5.0), 20));
            assertEquals(List.of(1, 3), parts.stream().map(RegionFlow.Fragment::regionIndex).toList());
            assertEquals(List.of(9.0, 12.0), parts.stream().map(part -> part.layout().width()).toList());
            assertEquals(List.of(9.0, 12.0), parts.stream().map(part -> part.layout().height()).toList());
            assertEquals(1, measurements.get());
            assertThrows(IllegalArgumentException.class, () -> scope.layout(context(20)));
        }
    }
}
