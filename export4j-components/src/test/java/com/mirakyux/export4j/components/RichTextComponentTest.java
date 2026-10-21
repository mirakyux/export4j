// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.ComponentLimits;
import com.mirakyux.export4j.layout.core.DocumentLayoutException;
import com.mirakyux.export4j.layout.core.DocumentLayoutLimits;
import com.mirakyux.export4j.layout.core.FlowRegions;
import com.mirakyux.export4j.layout.core.PageTemplate;
import com.mirakyux.export4j.layout.core.TextInheritance;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.LineBreakPolicy;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextAlignment;
import com.mirakyux.export4j.text.TextDiagnostic;
import com.mirakyux.export4j.text.TextLayoutException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RichTextComponentTest {
    private static final RgbColor RED = new RgbColor(1, 0, 0);
    private static final RgbColor BLUE = new RgbColor(0, 0, 1);
    private static FontGroup regular;
    private static FontGroup bold;
    private static FontGroup italic;
    private static FontGroup arabic;

    @BeforeAll static void loadFonts() throws IOException {
        regular = font("LiberationSans-Regular.ttf");
        bold = font("LiberationSans-Bold.ttf");
        italic = font("LiberationSans-Italic.ttf");
        arabic = font("NotoNaskhArabic-Regular.ttf");
    }

    @Test void realFontVariantsColorsDecorationsAndBaselineShiftsReachDrawingCommands() {
        var rich = RichTextComponent.builder()
                .add("A", new InlineStyle("bold", 12, 0xff0000, 0, true, false))
                .add("B", new InlineStyle("italic", 10, 0x0000ff, -2, false, true))
                .add("C", new InlineStyle("", 8, 0, 3, false, false)).lineHeight(32).build();
        var part = rich.layout(context(240, 100));
        var glyphs = glyphs(part);
        assertEquals("ABC", logical(part));
        assertEquals(32, part.height());
        assertEquals(List.of(bold.fonts().getFirst().data().id(), italic.fonts().getFirst().data().id(), regular.fonts().getFirst().data().id()),
                glyphs.stream().map(run -> run.run().font().id()).toList());
        assertEquals(List.of(12.0, 10.0, 8.0), glyphs.stream().map(run -> run.run().fontSize()).toList());
        assertEquals(List.of(RED, BLUE, RgbColor.BLACK), glyphs.stream().map(DrawCommand.GlyphText::color).toList());
        assertEquals(-2, glyphs.get(1).baseline().y() - glyphs.getFirst().baseline().y(), 1e-10);
        assertEquals(3, glyphs.get(2).baseline().y() - glyphs.getFirst().baseline().y(), 1e-10);
        assertTrue(glyphs.getFirst().run().underline());
        assertTrue(glyphs.get(1).run().strikeThrough());
        var strokes = part.commands().stream().filter(DrawCommand.StrokePath.class::isInstance)
                .map(DrawCommand.StrokePath.class::cast).toList();
        assertEquals(List.of(RED, BLUE), strokes.stream().map(DrawCommand.StrokePath::color).toList());
        assertTrue(strokes.stream().allMatch(stroke -> stroke.width() > 0));
        assertTrue(glyphs.get(1).baseline().x() >= glyphs.getFirst().run().width());
    }

    @Test void inheritedParagraphGeometryDoesNotRewriteCompleteInlineSizesColorsOrRoles() {
        var rich = RichTextComponent.builder().add("FIXED\nSTYLE", new InlineStyle("bold", 10, 0xff0000, 0, false, false)).build();
        var surrounding = ParagraphStyle.builder().fontSize(32).lineHeight(28).firstLineIndent(13).subsequentLineIndent(17).build();
        var context = context(240, 100).withTextDefaults(surrounding, BLUE);
        var part = rich.layout(context);
        assertEquals(56, part.height());
        assertEquals(List.of(13.0, 17.0), glyphs(part).stream().map(run -> run.baseline().x()).toList());
        assertTrue(glyphs(part).stream().allMatch(run -> run.run().fontSize() == 10 && run.color().equals(RED)
                && run.run().font().id().equals(bold.fonts().getFirst().data().id())));
        var unusualDeclaration = new RichTextComponent(rich.inlines(), rich.style(), TextInheritance.ALL);
        assertEquals(part, unusualDeclaration.layout(context));
    }

    @Test void namedParagraphOverridesAndTheirRestorationPreserveOtherDeclarations() {
        var rich = RichTextComponent.builder().add("FIRST\nNEXT", InlineStyle.of(10))
                .lineHeight(24).firstLineIndent(7).subsequentLineIndent(9).alignment(TextAlignment.LEFT)
                .lineBreakPolicy(LineBreakPolicy.STANDARD).build();
        var parent = ParagraphStyle.builder().fontSize(30).lineHeight(30).firstLineIndent(13).subsequentLineIndent(17)
                .alignment(TextAlignment.RIGHT).lineBreakPolicy(LineBreakPolicy.CJK_STRICT).build();
        var context = context(240, 100).withTextDefaults(parent, BLUE);
        var local = rich.layout(context);
        assertEquals(48, local.height());
        assertEquals(List.of(7.0, 9.0), glyphs(local).stream().map(run -> run.baseline().x()).toList());
        var restored = rich.toBuilder().inheritLineHeight().inheritFirstLineIndent().inheritSubsequentLineIndent()
                .inheritAlignment().inheritLineBreakPolicy().build();
        assertEquals(60, restored.layout(context).height());
        assertTrue(glyphs(restored.layout(context)).stream().allMatch(run -> run.baseline().x() > 100));
        assertTrue(glyphs(restored.layout(context)).stream().allMatch(run -> run.run().fontSize() == 10 && run.color().equals(RgbColor.BLACK)));
        assertEquals(rich.layout(context), rich.toBuilder().build().layout(context));
    }

    @Test void completeStyleConstructorsAndCallbacksRemainExplicitAndCanRestoreInheritance() {
        var run = new InlineText("VALUE", InlineStyle.of(10));
        var complete = new RichTextComponent(List.of(run), ParagraphStyle.of(12));
        var captured = new AtomicReference<ParagraphStyle.Builder>();
        var callback = RichTextComponent.builder().add(run).style(style -> {
            captured.set(style); style.alignment(TextAlignment.RIGHT);
        }).build();
        captured.get().lineHeight(100).alignment(TextAlignment.LEFT);
        var context = context(240, 100).withTextDefaults(ParagraphStyle.builder().fontSize(24).lineHeight(32).build(), BLUE);
        assertEquals(18, complete.layout(context).height());
        assertEquals(18, callback.layout(context).height());
        assertTrue(glyphs(callback.layout(context)).getFirst().baseline().x() > 100);
        assertEquals(32, callback.toBuilder().inheritStyle().build().layout(context).height());
        var oneRestored = callback.toBuilder().inheritLineHeight().build().layout(context);
        assertEquals(32, oneRestored.height());
        assertTrue(glyphs(oneRestored).getFirst().baseline().x() > 100);
    }

    @Test void fontAndStyleScopeOrderRetainsTheExplicitRunAndDefaultRoleBehavior() {
        var rich = RichTextComponent.builder().add("DEFAULT", InlineStyle.of(10))
                .add("ROLE", new InlineStyle("italic", 10, 0x0000ff, 0, false, false)).build();
        var style = ParagraphStyle.builder().lineHeight(28).build();
        var first = new FontRoleScope(new StyleScope(rich, style, RED), "bold").layout(context(240, 100));
        var second = new StyleScope(new FontRoleScope(rich, "bold"), style, RED).layout(context(240, 100));
        assertEquals(first, second);
        assertEquals(List.of(bold.fonts().getFirst().data().id(), italic.fonts().getFirst().data().id()),
                glyphs(first).stream().map(run -> run.run().font().id()).toList());
        assertEquals(List.of(RgbColor.BLACK, BLUE), glyphs(first).stream().map(DrawCommand.GlyphText::color).toList());
        assertEquals(28, first.height());
    }

    @Test void complexBidirectionalTextPreservesLogicalSourceAndColoredOutlines() {
        String first = "English ";
        String middle = "العربية لا";
        String last = " 123 a\u0301 ffi";
        var rich = RichTextComponent.builder().add(first, new InlineStyle("", 12, 0x0000ff, 0, false, false))
                .add(middle, new InlineStyle("arabic", 12, 0xff0000, 0, true, false))
                .add(last, new InlineStyle("", 12, 0x0000ff, 0, false, true)).lineHeight(36).build();
        var part = rich.layout(context(480, 100));
        assertEquals(first + middle + last, logical(part));
        assertEquals(1, part.textLines());
        assertFalse(part.commands().stream().anyMatch(DrawCommand.GlyphText.class::isInstance));
        var paths = part.commands().stream().filter(DrawCommand.FillPath.class::isInstance).map(DrawCommand.FillPath.class::cast).toList();
        assertTrue(paths.stream().anyMatch(path -> path.color().equals(RED)));
        assertTrue(paths.stream().anyMatch(path -> path.color().equals(BLUE)));
        assertTrue(part.diagnostics().isEmpty());
    }

    @Test void wrappingPreservesStylesAcrossRunBoundariesAndUsesTheDeclaredLineStep() {
        var rich = RichTextComponent.builder().add("A\n", new InlineStyle("bold", 10, 0xff0000, 0, true, false))
                .add("B\nC", new InlineStyle("italic", 10, 0x0000ff, 0, false, true)).lineHeight(24).build();
        var part = rich.layout(context(80, 100));
        assertEquals(72, part.height());
        assertEquals(3, part.textLines());
        assertEquals("ABC", logical(part));
        var glyphs = glyphs(part);
        assertEquals(24, glyphs.get(1).baseline().y() - glyphs.getFirst().baseline().y(), 1e-10);
        assertEquals(24, glyphs.get(2).baseline().y() - glyphs.get(1).baseline().y(), 1e-10);
        assertEquals(List.of(RED, BLUE, BLUE), glyphs.stream().map(DrawCommand.GlyphText::color).toList());
        var wrapped = RichTextComponent.builder().add("ABCDEFGHIJ", InlineStyle.of(10)).lineHeight(20).build().layout(context(18, 200));
        assertTrue(wrapped.textLines() > 1);
        assertEquals("ABCDEFGHIJ", logical(wrapped));
        assertEquals(wrapped.textLines() * 20.0, wrapped.height());
    }

    @Test void fixedLineFramesAndViewportsFailInsteadOfShrinkingClippingOrSplitting() {
        var oversized = RichTextComponent.builder().add("LARGE", InlineStyle.of(40)).build();
        assertEquals(TextLayoutException.Code.LINE_HEIGHT_TOO_SMALL,
                assertThrows(TextLayoutException.class, () -> oversized.layout(context(240, 100))).code());
        var shifted = RichTextComponent.builder().add("SHIFT", new InlineStyle("", 12, 0, -30, false, false)).lineHeight(20).build();
        assertEquals(TextLayoutException.Code.LINE_HEIGHT_TOO_SMALL,
                assertThrows(TextLayoutException.class, () -> shifted.layout(context(240, 100))).code());
        var threeLines = RichTextComponent.builder().add("A\nB\nC", InlineStyle.of(10)).lineHeight(20).build();
        assertThrows(IllegalArgumentException.class, () -> threeLines.layout(context(240, 40)));
        assertEquals(TextLayoutException.Code.GLYPH_TOO_WIDE, assertThrows(TextLayoutException.class,
                () -> RichTextComponent.builder().add("W", InlineStyle.of(12)).build().layout(context(1, 100))).code());
        var scoped = new FontRoleScope(threeLines, "bold");
        var parts = scoped.layoutRegions(context(240, 80), new FlowRegions(List.of(5.0, 40.0), 80));
        assertEquals(1, parts.size());
        assertEquals(2, parts.getFirst().regionIndex());
        assertEquals(60, parts.getFirst().layout().height());
    }

    @Test void namedMissingGlyphPoliciesKeepConcatenatedOriginalUtf16Offsets() {
        var replacement = FontGroup.placeholder(bold.fonts(), '?');
        var context = new ComponentContext(240, 100, regular, 0, 0, 1, Map.of("warn", replacement));
        var rich = RichTextComponent.builder().add("AB", InlineStyle.of(10))
                .add("C😀D", new InlineStyle("warn", 10, 0, 0, false, false)).build();
        var warning = rich.layout(context).diagnostics().stream().filter(item -> item.code() == TextDiagnostic.Code.MISSING_GLYPH_REPLACED)
                .findFirst().orElseThrow();
        assertEquals(3, warning.sourceStart());
        assertEquals(5, warning.sourceEnd());
        assertEquals(0x1f600, warning.sourceCodePoint());
        assertEquals(bold.fonts().getFirst().data().id(), warning.fontId());
        var strict = new ComponentContext(240, 100, FontGroup.placeholder(regular.fonts(), '?'), 0, 0, 1, Map.of("warn", bold));
        var failure = assertThrows(TextLayoutException.class, () -> rich.layout(strict));
        assertEquals(TextLayoutException.Code.MISSING_GLYPH, failure.code());
        assertEquals(3, failure.sourceIndex());
    }

    @Test void unknownRolesIncludingEmptyRunsFailAtTheirConcatenatedSourcePosition() {
        for (String text : List.of("X", "")) {
            var rich = RichTextComponent.builder().add("A😀", InlineStyle.of(10))
                    .add(text, new InlineStyle("unknown", 10, 0, 0, false, false)).build();
            var failure = assertThrows(TextLayoutException.class, () -> rich.layout(context(240, 100)));
            assertEquals(TextLayoutException.Code.INVALID_TEXT, failure.code());
            assertEquals(3, failure.sourceIndex());
            assertFalse(failure.getMessage().contains("A😀"));
        }
    }

    @Test void snapshotsAndToBuilderDoNotRetainMutableListsOrBuilderState() {
        var values = new ArrayList<>(List.of(new InlineText("EARLY", InlineStyle.of(10))));
        var constructor = new RichTextComponent(values, ParagraphStyle.of(12));
        var builder = RichTextComponent.builder().inlines(values).lineHeight(24);
        var built = builder.build();
        values.clear();
        builder.add("LATE", InlineStyle.of(10)).lineHeight(30);
        assertEquals("EARLY", logical(constructor.layout(context(240, 100))));
        assertEquals("EARLY", logical(built.layout(context(240, 100))));
        assertEquals(24, built.layout(context(240, 100)).height());
        assertEquals(built, built.toBuilder().build());
        assertThrows(UnsupportedOperationException.class, () -> built.inlines().clear());
        assertEquals("EARLYCOPY", logical(built.toBuilder().add("COPY", InlineStyle.of(10)).build().layout(context(240, 100))));
        assertEquals(1, built.inlines().size());
    }

    @Test void emptyRunsCountTowardTheRunBudgetAndFailedMutationsLeaveAUsableBuilder() {
        var empty = new InlineText("", InlineStyle.of(10));
        assertThrows(IllegalArgumentException.class, () -> RichTextComponent.builder().build());
        assertThrows(IllegalArgumentException.class, () -> new RichTextComponent(List.of(), ParagraphStyle.of(12)));
        assertEquals(1, RichTextComponent.builder().add(empty).build().layout(context(240, 100)).textLines());
        var limit = Collections.nCopies(4096, empty);
        var builder = RichTextComponent.builder().inlines(limit);
        assertEquals(4096, builder.build().inlines().size());
        assertEquals(TextLayoutException.Code.BUDGET_EXCEEDED, assertThrows(TextLayoutException.class, () -> builder.add(empty)).code());
        assertEquals(4096, builder.build().inlines().size());
        var tooMany = Collections.nCopies(4097, empty);
        assertThrows(TextLayoutException.class, () -> new RichTextComponent(tooMany, ParagraphStyle.of(12)));
        assertThrows(TextLayoutException.class, () -> builder.inlines(tooMany));
        assertEquals(4096, builder.build().inlines().size());
    }

    @Test void sourceBudgetCountsUnicodeCodePointsAcrossRunsInsteadOfUtf16Units() {
        String supplementary = "😀".repeat(50_000);
        var builder = RichTextComponent.builder().add(supplementary, InlineStyle.of(10)).add("A".repeat(50_000), InlineStyle.of(10));
        var value = builder.build();
        assertEquals(150_000, value.inlines().stream().mapToInt(run -> run.text().length()).sum());
        var failure = assertThrows(TextLayoutException.class, () -> builder.add("B", InlineStyle.of(10)));
        assertEquals(TextLayoutException.Code.BUDGET_EXCEEDED, failure.code());
        assertEquals(150_000, failure.sourceIndex());
        assertEquals(value, builder.build());
        assertThrows(TextLayoutException.class, () -> new RichTextComponent(List.of(new InlineText("A".repeat(100_001), InlineStyle.of(10))), ParagraphStyle.of(12)));
        assertThrows(TextLayoutException.class, () -> builder.inlines(List.of(new InlineText("😀".repeat(100_001), InlineStyle.of(10)))));
        assertEquals(value, builder.build());
    }

    @Test void hyperlinksAndObjectMarkersCannotSilentlyBecomeStaticText() {
        var linked = new InlineText("LINK", InlineStyle.of(10), "https://example.com");
        assertThrows(IllegalArgumentException.class, () -> RichTextComponent.builder().add(linked));
        assertThrows(IllegalArgumentException.class, () -> new RichTextComponent(List.of(linked), ParagraphStyle.of(12)));
        var builder = RichTextComponent.builder().add("A😀", InlineStyle.of(10));
        var failure = assertThrows(TextLayoutException.class, () -> builder.add("B\ufffcC", InlineStyle.of(10)));
        assertEquals(TextLayoutException.Code.INVALID_TEXT, failure.code());
        assertEquals(4, failure.sourceIndex());
        assertEquals(1, builder.build().inlines().size());
        var object = new InlineText("\ufffc", InlineStyle.of(10));
        assertThrows(TextLayoutException.class, () -> new RichTextComponent(List.of(object), ParagraphStyle.of(12)));
        assertThrows(TextLayoutException.class, () -> builder.inlines(List.of(object)));
        assertEquals(1, builder.build().inlines().size());
    }

    @Test void componentCommandAndOutputLineBudgetsRemainEnforced() {
        var rich = RichTextComponent.builder().add("A\nB", InlineStyle.of(10)).lineHeight(20).build();
        var document = Documents.builder().component("rich", rich).build();
        var fewLines = new DocumentLayoutLimits(10, 1_000, 1, 10);
        var lineFailure = assertThrows(DocumentLayoutException.class, () -> new ComponentDocumentRenderer(fewLines, ComponentLimits.DEFAULT)
                .layout(document, PageTemplate.EMPTY, Navigation.EMPTY, regular));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, lineFailure.code());
        var commandFailure = assertThrows(DocumentLayoutException.class, () -> new ComponentDocumentRenderer(DocumentLayoutLimits.DEFAULT,
                new ComponentLimits(10, 1, 10)).layout(document, PageTemplate.EMPTY, Navigation.EMPTY, regular));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, commandFailure.code());
    }

    @Test void cancellationDuringRunPreparationRestoresTheScopeAndLeavesTheValueReusable() throws IOException {
        var values = Collections.nCopies(200, new InlineText("A", InlineStyle.of(10)));
        var rich = RichTextComponent.builder().inlines(values).build();
        var checks = new AtomicInteger();
        var options = ExecutionOptions.builder().cancelled(() -> checks.incrementAndGet() > 6).build();
        var failure = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(options, () -> rich.layout(context(2_000, 100))));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code());
        assertTrue(checks.get() > 6);
        ExecutionContext.checkpoint();
        assertEquals("A".repeat(200), logical(rich.layout(context(2_000, 100))));
        assertEquals(200, rich.inlines().size());
        var cancelledBuild = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(
                ExecutionOptions.builder().cancelled(() -> true).build(), () -> rich.toBuilder().build()));
        assertEquals(ExecutionException.Code.CANCELLED, cancelledBuild.code());
        ExecutionContext.checkpoint();
        assertEquals(rich, rich.toBuilder().build());
    }

    private static ComponentContext context(double width, double height) {
        return new ComponentContext(width, height, regular, 0, 0, 1, Map.of("bold", bold, "italic", italic, "arabic", arabic));
    }

    private static FontGroup font(String name) throws IOException {
        try (var input = RichTextComponentTest.class.getResourceAsStream("/fonts/" + name)) {
            if (input == null) { throw new IOException("missing test font"); }
            return FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }

    private static List<DrawCommand.GlyphText> glyphs(ComponentLayout part) {
        return part.commands().stream().filter(DrawCommand.GlyphText.class::isInstance).map(DrawCommand.GlyphText.class::cast).toList();
    }

    private static String logical(ComponentLayout part) {
        return part.commands().stream().map(command -> {
            if (command instanceof DrawCommand.GlyphText text) { return text.run().text(); }
            if (command instanceof DrawCommand.OutlineText text) { return text.logicalText(); }
            return "";
        }).collect(Collectors.joining());
    }
}
