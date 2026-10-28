// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.VectorPath;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextDiagnostic;
import com.mirakyux.export4j.text.TextLayoutException;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerticalTextTest {
    private static FontFace cjk;
    private static FontFace latin;

    @BeforeAll static void fonts() throws IOException {
        cjk = load("LXGWWenKaiTC-Regular.ttf");
        latin = load("LiberationSans-Regular.ttf");
    }

    @Test void cjkUsesUprightCellsAndRightToLeftColumnOrder() {
        var result = sample("天地玄黃宇宙").build().layoutDetails(context(FontGroup.strict(List.of(cjk))));
        assertEquals(72, result.layout().width());
        assertEquals(96, result.layout().height());
        assertEquals(2, result.layout().textLines());
        assertEquals(List.of(36.0, 36.0, 36.0, 0.0, 0.0, 0.0),
                result.placements().stream().map(value -> value.bounds().x()).toList());
        assertEquals(List.of(0.0, 32.0, 64.0, 0.0, 32.0, 64.0),
                result.placements().stream().map(value -> value.bounds().y()).toList());
        assertTrue(result.placements().stream().noneMatch(VerticalText.Placement::sideways));
        assertEquals("天地玄黃宇宙", logical(result));
        assertTrue(outlines(result).stream().allMatch(value -> !value.paths().isEmpty()));
    }

    @Test void explicitLeftToRightColumnsPreserveLogicalOrder() {
        var result = sample("天地玄黃").columnOrder(VerticalText.ColumnOrder.LEFT_TO_RIGHT).build()
                .layoutDetails(context(FontGroup.strict(List.of(cjk))));
        assertEquals(0, result.placements().getFirst().bounds().x());
        assertEquals(36, result.placements().getLast().bounds().x());
        assertEquals("天地玄黃", logical(result));
    }

    @Test void asciiScalarsRotateIndividuallyAndKeepOriginalSource() {
        var result = sample("AB12").build().layoutDetails(context(FontGroup.strict(List.of(latin))));
        assertTrue(result.placements().stream().allMatch(VerticalText.Placement::sideways));
        assertEquals(List.of(0, 1, 2, 3), result.placements().stream().map(VerticalText.Placement::sourceStart).toList());
        assertEquals("AB12", logical(result));
        assertEquals(2, result.layout().textLines());
    }

    @Test void actualOutlinesKeepCjkHorizontalStrokesAndTurnLatinStems() {
        var result = sample("一I").build().layoutDetails(context(FontGroup.strict(List.of(latin, cjk))));
        for (var outline : outlines(result)) {
            var points = outline.paths().stream().flatMap(path -> path.segments().stream()).flatMap(segment ->
                    switch (segment) {
                        case VectorPath.MoveTo move -> List.of(move.point()).stream();
                        case VectorPath.LineTo line -> List.of(line.point()).stream();
                        case VectorPath.CurveTo curve -> List.of(curve.control1(), curve.control2(), curve.end()).stream();
                        case VectorPath.Close ignored -> List.<Point>of().stream();
                    }).toList();
            double width = points.stream().mapToDouble(Point::x).max().orElseThrow()
                    - points.stream().mapToDouble(Point::x).min().orElseThrow();
            double height = points.stream().mapToDouble(Point::y).max().orElseThrow()
                    - points.stream().mapToDouble(Point::y).min().orElseThrow();
            assertTrue(width > height * 2, "CJK one must keep its horizontal stroke; Latin I's stem must turn horizontal");
        }
    }

    @Test void punctuationUsesExplicitVerticalFormWithoutChangingLogicalText() {
        var result = sample("「天地」。").build().layoutDetails(context(FontGroup.strict(List.of(cjk))));
        assertEquals(0xfe41, result.placements().getFirst().renderedCodePoint());
        assertEquals(0xfe42, result.placements().get(3).renderedCodePoint());
        assertEquals(0xfe12, result.placements().getLast().renderedCodePoint());
        assertEquals("「天地」。", logical(result));
        assertFalse(result.placements().getFirst().sideways());
    }

    @Test void crlfIsOneExplicitColumnBreakWithItsOriginalRange() {
        var result = sample("天\r\n地\n玄").build().layoutDetails(context(FontGroup.strict(List.of(cjk))));
        assertEquals(3, result.layout().textLines());
        assertEquals(1, result.placements().get(1).sourceStart());
        assertEquals(3, result.placements().get(1).sourceEnd());
        assertEquals(-1, result.placements().get(1).renderedCodePoint());
        assertEquals(1, result.placements().get(2).columnIndex());
        assertEquals("天\r\n地\n玄", logical(result));
    }

    @Test void fallbackAndSupplementaryPlaceholderRetainOriginalUtf16Ranges() {
        var fallback = sample("天A").build().layoutDetails(context(FontGroup.strict(List.of(latin, cjk))));
        var diagnostic = fallback.layout().diagnostics().getFirst();
        assertEquals(TextDiagnostic.Code.FONT_FALLBACK, diagnostic.code());
        assertEquals(cjk.data().id(), diagnostic.fontId());
        assertEquals(0, diagnostic.sourceStart());
        var replacement = sample("A😀B").build().layoutDetails(context(FontGroup.placeholder(List.of(latin), '?')));
        var warning = replacement.layout().diagnostics().getFirst();
        assertEquals(TextDiagnostic.Code.MISSING_GLYPH_REPLACED, warning.code());
        assertEquals(1, warning.sourceStart());
        assertEquals(3, warning.sourceEnd());
        assertEquals(0x1f600, warning.sourceCodePoint());
        assertEquals('?', warning.replacementCodePoint());
        assertEquals(1, replacement.placements().get(1).sourceStart());
        assertEquals(3, replacement.placements().get(1).sourceEnd());
        assertEquals("A😀B", logical(replacement));
    }

    @Test void missingVerticalPresentationGlyphDoesNotSilentlyUseHorizontalPunctuation() {
        var failure = assertThrows(TextLayoutException.class,
                () -> sample("(").build().layout(context(FontGroup.strict(List.of(latin)))));
        assertEquals(TextLayoutException.Code.MISSING_GLYPH, failure.code());
        assertEquals(0, failure.sourceIndex());
    }

    @Test void missingGlyphsFailUnderStrictPolicy() {
        var failure = assertThrows(TextLayoutException.class,
                () -> sample("A😀").build().layout(context(FontGroup.strict(List.of(latin)))));
        assertEquals(TextLayoutException.Code.MISSING_GLYPH, failure.code());
        assertEquals(1, failure.sourceIndex());
    }

    @Test void combiningJoiningAndMalformedTextFailBeforeLayout() {
        assertEquals(TextLayoutException.Code.UNSUPPORTED_SHAPING,
                assertThrows(TextLayoutException.class, () -> VerticalText.builder("a\u0301").build()).code());
        assertEquals(TextLayoutException.Code.UNSUPPORTED_SHAPING,
                assertThrows(TextLayoutException.class, () -> VerticalText.builder("ภาษา").build()).code());
        assertEquals(TextLayoutException.Code.UNSUPPORTED_SHAPING,
                assertThrows(TextLayoutException.class, () -> VerticalText.builder("ا").build()).code());
        assertEquals(TextLayoutException.Code.INVALID_TEXT,
                assertThrows(TextLayoutException.class, () -> VerticalText.builder("A\ud800").build()).code());
        assertThrows(TextLayoutException.class, () -> VerticalText.builder("\t").build());
    }

    @Test void columnAndSourceBudgetsAreExplicitAndCannotDiscardTail() {
        var columns = assertThrows(TextLayoutException.class,
                () -> sample("天地玄黃").maxColumns(1).build().layout(context(FontGroup.strict(List.of(cjk)))));
        assertEquals(TextLayoutException.Code.BUDGET_EXCEEDED, columns.code());
        assertEquals(3, columns.sourceIndex());
        assertEquals(TextLayoutException.Code.BUDGET_EXCEEDED,
                assertThrows(TextLayoutException.class,
                        () -> VerticalText.builder("天地").maxCodePoints(1).build()).code());
    }

    @Test void oversizedGlyphOrColumnHeightFailsWithoutClippingOrShrinking() {
        assertThrows(TextLayoutException.class,
                () -> sample("天").fontSize(80).build().layout(context(FontGroup.strict(List.of(cjk)))));
        assertThrows(TextLayoutException.class,
                () -> sample("天").height(161).build().layout(context(FontGroup.strict(List.of(cjk)))));
        assertThrows(TextLayoutException.class,
                () -> sample("天").cellAdvance(100).build().layout(context(FontGroup.strict(List.of(cjk)))));
    }

    @Test void contextDefaultsAreInheritedAndRepeatedLayoutIsDeterministic() {
        var context = context(FontGroup.strict(List.of(cjk))).withTextDefaults(
                ParagraphStyle.builder().fontSize(18).lineHeight(32).build(), new RgbColor(0, 0, 1));
        var component = VerticalText.builder("天地").height(96).build();
        var first = component.layoutDetails(context);
        var second = component.layoutDetails(context);
        assertEquals(first, second);
        assertEquals(32, first.layout().width());
        assertTrue(outlines(first).stream().allMatch(value -> value.color().equals(new RgbColor(0, 0, 1))));
        assertThrows(UnsupportedOperationException.class, () -> first.placements().clear());
    }

    @Test void emptyTextStillProducesAFiniteAtomicComponent() {
        var result = sample("").build().layoutDetails(context(FontGroup.strict(List.of(cjk))));
        assertEquals(36, result.layout().width());
        assertEquals(96, result.layout().height());
        assertTrue(result.placements().isEmpty());
    }

    @Test void spacesAndBlankColumnsRetainSourceWithoutVisibleInk() {
        var result = sample(" \n\n").build().layoutDetails(context(FontGroup.strict(List.of(latin))));
        assertEquals(3, result.layout().textLines());
        assertEquals(" \n\n", logical(result));
        assertEquals(3, result.placements().size());
        assertTrue(outlines(result).stream().allMatch(outline -> outline.paths().isEmpty()));
    }

    @Test void tinyFontOrCellCannotSilentlyEraseInkOrLetItEscapeItsCell() {
        var context = context(FontGroup.strict(List.of(latin)));
        assertThrows(TextLayoutException.class, () -> sample("A").fontSize(Double.MIN_VALUE).build().layout(context));
        assertThrows(TextLayoutException.class, () -> sample("A").fontSize(0.0001).cellAdvance(0.0000001)
                .columnAdvance(0.0000001).build().layout(context));
    }

    @Test void cancellationDuringLayoutDoesNotConsumeTheReusableComponent() throws IOException {
        var component = sample("天地玄黃").build();
        var context = context(FontGroup.strict(List.of(cjk)));
        var count = new AtomicInteger();
        var failure = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(
                ExecutionOptions.builder().cancelled(() -> count.incrementAndGet() > 3).build(),
                () -> component.layoutDetails(context)));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code());
        assertEquals("天地玄黃", logical(component.layoutDetails(context)));
    }

    @Test void builderRejectsNonFiniteDimensions() {
        assertThrows(IllegalArgumentException.class, () -> VerticalText.builder("天").fontSize(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> VerticalText.builder("天").cellAdvance(0));
        assertThrows(IllegalArgumentException.class, () -> VerticalText.builder("天").columnAdvance(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> VerticalText.builder("天").maxColumns(0));
    }

    private static VerticalText.Builder sample(String value) {
        return VerticalText.builder(value).fontSize(18).cellAdvance(32).columnAdvance(36).height(96);
    }
    private static ComponentContext context(FontGroup group) { return new ComponentContext(240, 160, group, 0, 0, 1); }
    private static FontFace load(String name) throws IOException {
        try (var input = VerticalTextTest.class.getResourceAsStream("/fonts/" + name)) {
            if (input == null) { throw new IOException("missing test font"); }
            return TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES);
        }
    }
    private static List<DrawCommand.OutlineText> outlines(VerticalText.Result result) {
        return result.layout().commands().stream().map(DrawCommand.OutlineText.class::cast).toList();
    }
    private static String logical(VerticalText.Result result) {
        return outlines(result).stream().map(DrawCommand.OutlineText::logicalText).collect(java.util.stream.Collectors.joining());
    }
}
