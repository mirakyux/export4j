// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.MissingGlyphPolicy;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.FlowRegions;
import com.mirakyux.export4j.layout.core.TextInheritance;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.LineBreakPolicy;
import com.mirakyux.export4j.model.PaginationPolicy;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextAlignment;
import com.mirakyux.export4j.text.TextDiagnostic;
import com.mirakyux.export4j.text.TextLayoutException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextCascadeTest {
    private static final RgbColor BLUE = new RgbColor(0, 0, 1);
    private static final RgbColor RED = new RgbColor(1, 0, 0);
    private static final ParagraphStyle PARENT = new ParagraphStyle(10, 24, 7, 3, TextAlignment.RIGHT, LineBreakPolicy.CJK_STRICT);
    private static FontGroup fonts;

    @BeforeAll static void fonts() throws IOException {
        try (var input = TextCascadeTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            fonts = FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }

    private static ComponentContext context(double height) {
        return new ComponentContext(240, height, fonts, 0, 1, 7).withTextDefaults(PARENT, BLUE);
    }

    private static List<DrawCommand.GlyphText> glyphs(ComponentLayout part) {
        return part.commands().stream().filter(DrawCommand.GlyphText.class::isInstance).map(DrawCommand.GlyphText.class::cast).toList();
    }

    private static String logical(ComponentLayout part) {
        return part.commands().stream().map(command -> {
            if (command instanceof DrawCommand.GlyphText glyph) { return glyph.run().text(); }
            if (command instanceof DrawCommand.OutlineText outline) { return outline.logicalText(); }
            return "";
        }).collect(Collectors.joining());
    }

    private record OutlineLine(DrawCommand.OutlineText command, Rect bounds) { }

    private static List<OutlineLine> outlineLines(ComponentLayout part) {
        var result = new ArrayList<OutlineLine>();
        var stack = new ArrayDeque<Point>();
        var offset = new Point(0, 0);
        for (var command : part.commands()) {
            if (command instanceof DrawCommand.Save) { stack.push(offset); }
            else if (command instanceof DrawCommand.Restore) { offset = stack.pop(); }
            else if (command instanceof DrawCommand.Transform transform) {
                var matrix = transform.matrix();
                // This fixture translates cells only; reject a new transform instead of ignoring it.
                assertEquals(1, matrix.a()); assertEquals(0, matrix.b());
                assertEquals(0, matrix.c()); assertEquals(1, matrix.d());
                offset = new Point(offset.x() + matrix.e(), offset.y() + matrix.f());
            } else if (command instanceof DrawCommand.OutlineText outline) {
                var bounds = outline.logicalBounds();
                result.add(new OutlineLine(outline, new Rect(offset.x() + bounds.x(), offset.y() + bounds.y(),
                        bounds.width(), bounds.height())));
            }
        }
        assertTrue(stack.isEmpty());
        return result;
    }

    @Test void fontSizeOnlyInheritsEveryOtherParentPropertyAndActualGeometry() {
        var source = FlowText.builder("FIRST\nSECOND").fontSize(12).build();
        var resolved = source.resolved(context(100));
        assertEquals(new ParagraphStyle(12, 24, 7, 3, TextAlignment.RIGHT, LineBreakPolicy.CJK_STRICT), resolved.style());
        var part = source.layout(context(100));
        assertEquals(48, part.height());
        var text = glyphs(part);
        assertEquals(List.of(12.0, 12.0), text.stream().map(glyph -> glyph.run().fontSize()).toList());
        assertEquals(24, text.get(1).baseline().y() - text.get(0).baseline().y());
        assertTrue(text.stream().allMatch(glyph -> glyph.baseline().x() > 100 && glyph.color().equals(BLUE)));
    }

    @Test void explicitDefaultNumbersAndEnumsDoNotBecomeInherited() {
        var text = FlowText.builder("ONE\nTWO").fontSize(12).lineHeight(18).firstLineIndent(0).subsequentLineIndent(0)
                .alignment(TextAlignment.LEFT).lineBreakPolicy(LineBreakPolicy.STANDARD).color(RgbColor.BLACK).build();
        assertEquals(ParagraphStyle.of(12), text.resolved(context(100)).style());
        var part = text.layout(context(100));
        assertEquals(36, part.height());
        assertTrue(glyphs(part).stream().allMatch(glyph -> glyph.baseline().x() == 0 && glyph.color().equals(RgbColor.BLACK)));
    }

    @Test void lineHeightOnlyKeepsParentFontIndentsAndAlignment() {
        var source = FlowText.builder("ONE\nTWO").lineHeight(30).build();
        assertEquals(new ParagraphStyle(10, 30, 7, 3, TextAlignment.RIGHT, LineBreakPolicy.CJK_STRICT), source.resolved(context(100)).style());
        assertEquals(60, source.layout(context(100)).height());
        assertTrue(glyphs(source.layout(context(100))).stream().allMatch(glyph -> glyph.run().fontSize() == 10));
    }

    @Test void nestedPartialScopesMergeAndDoNotMutateSiblings() {
        var nested = StyleScope.builder(StyleScope.builder(FlowText.builder("INNER").build()).alignment(TextAlignment.LEFT).build())
                .fontSize(14).lineHeight(30).color(RED).build();
        var outer = FlowColumn.builder().add(nested).text("SIBLING").build().layout(context(100));
        assertEquals(54, outer.height());
        var runs = glyphs(outer);
        assertEquals(List.of(14.0, 10.0), runs.stream().map(glyph -> glyph.run().fontSize()).toList());
        assertEquals(List.of(RED, BLUE), runs.stream().map(DrawCommand.GlyphText::color).toList());
        assertEquals(7, runs.getFirst().baseline().x());
        assertTrue(runs.getLast().baseline().x() > 100);
    }

    @Test void allExistingLeafBuildersAndScopesRetainPartialDeclarationsThroughToBuilder() {
        var flow = FlowText.builder("FLOW").fontSize(12).build();
        var fixed = TextComponent.builder("FIXED").fontSize(12).build();
        var open = OpenTypeText.builder("OPEN").fontSize(12).build();
        var form = FormText.builder("☑ FORM").fontSize(12).build();
        var choices = ChoiceGroup.builder().option("OPTION", true).fontSize(12).build();
        var label = LabelValue.builder("LABEL", "VALUE").fontSize(12).build();
        var page = PageNumberComponent.builder("{page}/{pages}").fontSize(12).build();
        var scope = StyleScope.builder(FlowText.builder("SCOPED").build()).fontSize(12).build();
        List<Component> original = List.of(flow, fixed, open, form, choices, label, page, scope);
        List<Component> copies = List.of(flow.toBuilder().build(), fixed.toBuilder().build(), open.toBuilder().build(),
                form.toBuilder().build(), choices.toBuilder().build(), label.toBuilder().build(), page.toBuilder().build(), scope.toBuilder().build());
        for (int index = 0; index < original.size(); index++) {
            assertEquals(24, original.get(index).layout(context(100)).height());
            assertEquals(original.get(index), copies.get(index));
            assertEquals(original.get(index).layout(context(100)), copies.get(index).layout(context(100)));
        }
    }

    @Test void eachInheritanceRecoveryPreservesOtherCompleteStyleDeclarations() {
        var complete = new ParagraphStyle(12, 18, 0, 0, TextAlignment.LEFT, LineBreakPolicy.STANDARD);
        List<FlowText> restored = List.of(
                FlowText.builder("A").style(complete).inheritFontSize().build(),
                FlowText.builder("A").style(complete).inheritLineHeight().build(),
                FlowText.builder("A").style(complete).inheritFirstLineIndent().build(),
                FlowText.builder("A").style(complete).inheritSubsequentLineIndent().build(),
                FlowText.builder("A").style(complete).inheritAlignment().build(),
                FlowText.builder("A").style(complete).inheritLineBreakPolicy().build());
        List<ParagraphStyle> expected = List.of(
                new ParagraphStyle(10, 18, 0, 0, TextAlignment.LEFT, LineBreakPolicy.STANDARD),
                new ParagraphStyle(12, 24, 0, 0, TextAlignment.LEFT, LineBreakPolicy.STANDARD),
                new ParagraphStyle(12, 18, 7, 0, TextAlignment.LEFT, LineBreakPolicy.STANDARD),
                new ParagraphStyle(12, 18, 0, 3, TextAlignment.LEFT, LineBreakPolicy.STANDARD),
                new ParagraphStyle(12, 18, 0, 0, TextAlignment.RIGHT, LineBreakPolicy.STANDARD),
                new ParagraphStyle(12, 18, 0, 0, TextAlignment.LEFT, LineBreakPolicy.CJK_STRICT));
        for (int index = 0; index < restored.size(); index++) {
            assertEquals(expected.get(index), restored.get(index).resolved(context(100)).style());
        }
    }

    @Test void wholeStyleSetterAndLegacyCallbackRemainCompleteAfterNamedEdits() {
        var declared = FlowText.builder("COMPLETE").style(ParagraphStyle.of(12)).fontSize(13).build();
        assertEquals(new ParagraphStyle(13, 18, 0, 0, TextAlignment.LEFT), declared.resolved(context(100)).style());
        var callback = FlowText.builder("CALLBACK").fontSize(13).style(style -> style.alignment(TextAlignment.CENTER)).build();
        assertEquals(new ParagraphStyle(13, 18, 0, 0, TextAlignment.CENTER), callback.resolved(context(100)).style());
        var restored = declared.toBuilder().inheritStyle().fontSize(12).build().resolved(context(100));
        assertEquals(new ParagraphStyle(12, 24, 7, 3, TextAlignment.RIGHT, LineBreakPolicy.CJK_STRICT), restored.style());
    }

    @Test void retainedCallbacksAndBuilderMutationsCannotAlterBuiltValues() {
        var retained = new AtomicReference<ParagraphStyle.Builder>();
        var builder = FlowText.builder("SNAPSHOT").style(style -> {
            retained.set(style); style.fontSize(12).lineHeight(20);
        }).inheritAlignment();
        var first = builder.build();
        retained.get().fontSize(22).lineHeight(33);
        builder.fontSize(14).lineHeight(30).alignment(TextAlignment.LEFT);
        assertEquals(new ParagraphStyle(12, 20, 0, 0, TextAlignment.RIGHT), first.resolved(context(100)).style());
        assertEquals(new ParagraphStyle(14, 30, 0, 0, TextAlignment.LEFT), builder.build().resolved(context(100)).style());
    }

    @Test void propertySetAndCompatibilityDeclarationsAreImmutable() {
        var properties = EnumSet.of(TextInheritance.Property.FONT_SIZE);
        var declaration = new TextInheritance(true, true, properties);
        properties.add(TextInheritance.Property.LINE_HEIGHT);
        assertEquals(Set.of(TextInheritance.Property.FONT_SIZE), declaration.overrides());
        assertThrows(UnsupportedOperationException.class, () -> declaration.overrides().clear());
        assertEquals(TextInheritance.NONE, new TextInheritance(false, false));
        assertEquals(TextInheritance.ALL, new TextInheritance(true, true));
        assertThrows(IllegalArgumentException.class, () -> new TextInheritance(false, true, properties));
    }

    @Test void partialValueCanBeLaidOutAgainstDifferentContextsWithoutCachingParentDefaults() {
        var source = FlowText.builder("REUSE").fontSize(12).build();
        var a = source.layout(context(100));
        var b = source.layout(context(100).withTextDefaults(ParagraphStyle.builder().fontSize(16).lineHeight(30).build(), RED));
        assertEquals(24, a.height()); assertEquals(30, b.height());
        assertEquals(12, glyphs(a).getFirst().run().fontSize()); assertEquals(12, glyphs(b).getFirst().run().fontSize());
        assertEquals(BLUE, glyphs(a).getFirst().color()); assertEquals(RED, glyphs(b).getFirst().color());
    }

    @Test void directConstructorsNeverAcquireParentDefaults() {
        var text = new FlowText("CONSTRUCTOR", ParagraphStyle.of(12), RgbColor.BLACK, PaginationPolicy.DEFAULT);
        assertSame(text, text.resolved(context(100)));
        assertEquals(18, text.layout(context(100)).height());
        assertEquals(RgbColor.BLACK, glyphs(text.layout(context(100))).getFirst().color());
    }

    @Test void gridPartialDefaultsLocalRecoveryFactoriesAndSnapshotsCompose() {
        var retained = new AtomicReference<GridTable.CellBuilder>();
        var builder = GridTable.builder().defaults(cell -> {
            retained.set(cell); cell.padding(0).fontSize(12);
        }).row(row -> row.cell("EARLY"));
        retained.get().fontSize(20).lineHeight(36);
        var grid = builder.defaults(cell -> cell.fontSize(14).textColor(RED))
                .row(row -> row.cell("LOCAL", cell -> cell.inheritFontSize()))
                .row(row -> row.cell("FACTORY", cell -> cell.textFactory(text -> FlowText.builder(text).fontSize(8).build())))
                .build();
        var part = grid.layout(context(100));
        assertEquals(72, part.height());
        assertEquals(List.of(12.0, 10.0, 8.0), glyphs(part).stream().map(glyph -> glyph.run().fontSize()).toList());
        assertEquals(List.of(BLUE, RED, RED), glyphs(part).stream().map(DrawCommand.GlyphText::color).toList());
    }

    @Test void splitMergedGridUsesInheritedLineStepAndPreservesBodyInShortRegions() {
        String text = IntStream.rangeClosed(1, 9).mapToObj(index -> "☑ LINE" + index).collect(Collectors.joining("\n"));
        var grid = GridTable.builder().headerRows(1).allowRowSplit(true).defaults(cell -> cell.padding(0).fontSize(12))
                .row(row -> row.cell("HEAD", cell -> cell.fontSize(14)))
                .row(row -> row.cell(FormText.builder(text).build(), cell -> cell.rowSpan(2))).row(row -> { }).build();
        var parts = grid.layoutRegions(context(100), new FlowRegions(List.of(5.0, 60.0), 100));
        assertEquals(List.of(1, 2, 3, 4), parts.stream().map(part -> part.regionIndex()).toList());
        // Continuing merged groups fill the region, retaining unused space below their complete lines.
        assertEquals(List.of(60.0, 100.0, 100.0, 72.0), parts.stream().map(part -> part.layout().height()).toList());
        assertEquals(List.of(1, 3, 3, 2), parts.stream().map(part -> outlineLines(part.layout()).size()).toList());
        assertEquals(text.replace("\n", ""), parts.stream().map(part -> logical(part.layout())).collect(Collectors.joining()).replace("HEAD", ""));
        for (var part : parts) {
            var body = outlineLines(part.layout());
            double slack = part.layout().height() - 24 - body.size() * 24;
            assertTrue(slack >= 0 && slack < 24);
            for (int index = 0; index < body.size(); index++) {
                var line = body.get(index);
                assertTrue(line.command().logicalText().startsWith("☑ LINE"));
                assertEquals(BLUE, line.command().color());
                assertEquals(24 + index * 24, line.bounds().y());
                assertEquals(24, line.bounds().height());
                assertTrue(line.bounds().x() >= 0 && line.bounds().x() + line.bounds().width() <= part.layout().width());
                assertTrue(line.bounds().y() + line.bounds().height() <= part.layout().height());
            }
            assertTrue(logical(part.layout()).startsWith("HEAD"));
            assertEquals(List.of("HEAD"), glyphs(part.layout()).stream().map(glyph -> glyph.run().text()).toList());
            assertEquals(14, glyphs(part.layout()).getFirst().run().fontSize());
        }
    }

    @Test void floatingOpenTypeUsesInheritedLineStepBeforeAllocatingSlots() {
        var source = OpenTypeText.builder("FLOAT-A\nFLOAT-B\nFLOAT-C").fontSize(12).build();
        var floating = MultiFloatText.builder(source).floating(box -> box.content(new Spacer(24)).width(40).height(24).gap(4)).build();
        var parts = floating.layoutParts(context(48), 24);
        assertEquals(List.of(24.0, 48.0), parts.stream().map(ComponentLayout::height).toList());
        assertEquals("FLOAT-AFLOAT-BFLOAT-C", parts.stream().map(TextCascadeTest::logical).collect(Collectors.joining()));
        assertTrue(parts.stream().flatMap(part -> part.commands().stream()).filter(DrawCommand.OutlineText.class::isInstance)
                .map(DrawCommand.OutlineText.class::cast).allMatch(outline -> outline.color().equals(BLUE)));
    }

    @Test void fontAndPartialStyleScopeOrdersPreserveMissingGlyphSourceOffsets() {
        var replacement = FontGroup.builder().fonts(fonts.fonts()).missingGlyphPolicy(MissingGlyphPolicy.PLACEHOLDER_WITH_WARNING)
                .placeholderCodePoint('?').build();
        var text = FormText.builder("☑ A\uD83D\uDE00\n□ B\uD83D\uDE00").fontSize(12).build();
        var a = new FontScope(StyleScope.builder(text).lineHeight(24).build(), replacement);
        var b = StyleScope.builder(new FontScope(text, replacement)).lineHeight(24).build();
        var regions = new FlowRegions(List.of(5.0, 24.0), 24);
        var first = a.layoutRegions(context(24), regions);
        assertEquals(first, b.layoutRegions(context(24), regions));
        var diagnostics = first.stream().flatMap(part -> part.layout().diagnostics().stream())
                .filter(diagnostic -> diagnostic.code() == TextDiagnostic.Code.MISSING_GLYPH_REPLACED).toList();
        assertEquals(List.of(3, 9), diagnostics.stream().map(TextDiagnostic::sourceStart).toList());
        assertEquals(List.of(5, 11), diagnostics.stream().map(TextDiagnostic::sourceEnd).toList());
    }

    @Test void oversizedLocalFontStillFailsInheritedFixedLineFrame() {
        var text = FlowText.builder("TOO LARGE").fontSize(40).build();
        var failure = assertThrows(TextLayoutException.class, () -> text.layout(context(100)));
        assertEquals(TextLayoutException.Code.LINE_HEIGHT_TOO_SMALL, failure.code());
        assertThrows(TextLayoutException.class, () -> FormText.builder("☑ TOO LARGE").fontSize(40).build().layout(context(100)));
    }

    @Test void partialScopesKeepAtomicChildWidthAndSemanticTableSource() {
        var mark = StyleScope.builder(CheckMark.builder().size(9).build()).fontSize(14).build();
        var part = FlowColumn.builder().alignment(FlowColumn.Alignment.CENTER).add(mark).build().layout(context(100));
        assertEquals(9, part.height());
        assertEquals(9, mark.layout(context(100)).width());
        var transform = part.commands().stream().filter(DrawCommand.Transform.class::isInstance).map(DrawCommand.Transform.class::cast).findFirst().orElseThrow();
        assertEquals(115.5, transform.matrix().e());
        var table = Table.builder("semantic").row("SOURCE").build();
        var semantic = new SemanticTable(table);
        var scoped = StyleScope.builder(semantic).fontSize(8).color(RED).build().layout(context(100));
        assertEquals(semantic.layout(context(100)), scoped);
        assertSame(table, scoped.semanticPlacements().getFirst().source());
        assertThrows(IllegalStateException.class, scoped::commands);
    }

    @Test void cooperativeCancellationInsidePartialNestedFlowRestoresExecutionState() throws IOException {
        var checks = new AtomicInteger();
        var text = FlowText.builder("CANCEL ".repeat(2_000)).fontSize(12).build();
        var grid = GridTable.builder().allowRowSplit(true).defaults(cell -> cell.padding(0).lineHeight(24))
                .row(row -> row.cell(text)).build();
        var options = ExecutionOptions.builder().cancelled(() -> checks.incrementAndGet() > 6).build();
        var failure = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(options,
                () -> grid.layoutRegions(context(100), new FlowRegions(List.of(5.0, 60.0), 100))));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code());
        assertTrue(checks.get() > 6);
        ExecutionContext.checkpoint();
        assertEquals(24, FlowText.builder("RECOVERED").fontSize(12).build().layout(context(100)).height());
    }
}
