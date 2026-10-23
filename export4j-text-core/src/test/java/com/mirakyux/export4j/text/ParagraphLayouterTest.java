// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontData;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontMetrics;
import com.mirakyux.export4j.font.GlyphMetrics;
import com.mirakyux.export4j.font.TrueTypeFontLoader;

class ParagraphLayouterTest {
    @Test void variableLineWidthsPreserveOriginalIndicesAndUseEachLinesOwnAlignmentSpace() {
        var face = fixture(latin, cp -> true, new FontMetrics(.8, .2, 0));
        var spans = List.of(new StyledSpan("ABCDEFGHIJKL", com.mirakyux.export4j.font.FontGroup.strict(List.of(face)), 10, -1, 0, false, false));
        var result = new ParagraphLayouter().layout(spans, 40, STYLE, line -> line % 2 == 0 ? 20 : 40);
        assertEquals(List.of("AB", "CDEF", "GH", "IJKL"), texts(result));
        assertEquals(List.of(0, 2, 6, 8), result.lines().stream().map(TextLine::sourceStart).toList());
        assertEquals(List.of(2, 6, 8, 12), result.lines().stream().map(TextLine::sourceEnd).toList());
        for (double invalid : List.of(0.0, -1.0, Double.NaN, 41.0)) {
            assertThrows(IllegalArgumentException.class, () -> new ParagraphLayouter().layout(spans, 40, STYLE, line -> invalid));
        }
    }

    @Test void justificationExpandsSpacesOrCjkGapsOnlyOnSoftWrappedLines() {
        var face = fixture(latin, cp -> true, new FontMetrics(.8, .2, 0));
        var style = STYLE.withAlignment(TextAlignment.JUSTIFY);
        var result = new ParagraphLayouter().layout("A B C D\nE F", List.of(face), 55, style);
        assertEquals(List.of("A B", "C D", "E F"), texts(result));
        assertEquals(55, result.lines().getFirst().width(), 1e-9);
        assertEquals(30, result.lines().get(1).width(), 1e-9);
        assertEquals(30, result.lines().get(2).width(), 1e-9);
        var cjk = new ParagraphLayouter().layout("中文测试换行", List.of(face), 35, style);
        assertEquals(35, cjk.lines().getFirst().width(), 1e-9);
        assertEquals(30, cjk.lines().getLast().width(), 1e-9);
        assertEquals(30, new ParagraphLayouter().layout("ABCDEF", List.of(face), 35, style).lines().getFirst().width());
        assertEquals(List.of(4, 7, 11), result.lines().stream().map(TextLine::sourceEnd).toList());
    }

    @Test void strictChineseEmergencyBreaksPreservePunctuationGroupsAndFailWhenTheyCannotFit() {
        var face = fixture(latin, cp -> true, new FontMetrics(.8, .2, 0));
        var style = STYLE.withLineBreakPolicy(com.mirakyux.export4j.model.LineBreakPolicy.CJK_STRICT);
        for (String source : List.of("中文（测试）完成", "中文……完成", "中文——完成", "AB\u00a0CD", "AB\u202fCD", "AB\u2060CD")) {
            var result = new ParagraphLayouter().layout(source, List.of(face), 50, style);
            for (var line : result.lines()) {
                assertTrue(LineBreakRules.allowed(source, line.sourceEnd(), style.lineBreakPolicy()), source);
            }
        }
        assertThrows(TextLayoutException.class, () -> new ParagraphLayouter().layout("（）", List.of(face), 10, style));
        assertEquals(List.of("（", "）"), texts(new ParagraphLayouter().layout("（\n）", List.of(face), 10, style)));
    }

    @Test
    void fontGroupDefaultsToErrorAndPlaceholderModeEmitsWarningsWithOriginalScalarRanges() {
        var strict = com.mirakyux.export4j.font.FontGroup.strict(List.of(latin));
        var failure = assertThrows(TextLayoutException.class, () -> new SimpleTextShaper().shape("A中B", strict, 10, 7));
        assertEquals(TextLayoutException.Code.MISSING_GLYPH, failure.code());
        assertEquals(8, failure.sourceIndex());
        var group = com.mirakyux.export4j.font.FontGroup.placeholder(List.of(latin), '?');
        var shaped = new SimpleTextShaper().shape("A中\uD840\uDC00B", group, 10, 7);
        assertEquals("A??B", shaped.glyphs().stream().map(g -> new String(Character.toChars(g.glyph().codePoint()))).collect(java.util.stream.Collectors.joining()));
        var warnings = shaped.diagnostics().stream().filter(d -> d.severity() == TextDiagnostic.Severity.WARNING).toList();
        assertEquals(List.of(8, 9), warnings.stream().map(TextDiagnostic::sourceStart).toList());
        assertEquals(List.of(9, 11), warnings.stream().map(TextDiagnostic::sourceEnd).toList());
        assertEquals(List.of((int) '中', 0x20000), warnings.stream().map(TextDiagnostic::sourceCodePoint).toList());
        assertTrue(warnings.stream().allMatch(d -> d.replacementCodePoint() == '?'));
        var paragraph = new ParagraphLayouter().layout("中\n文", group, 50, STYLE);
        assertEquals(List.of("?", "?"), texts(paragraph));
        assertEquals(2, paragraph.diagnostics().size());
    }

    @Test
    void fallbackIsTriedBeforePlaceholderAndInvalidOrComplexTextStillFails() {
        var group = com.mirakyux.export4j.font.FontGroup.placeholder(List.of(latin, chinese), '?');
        var shaped = new SimpleTextShaper().shape("中", group, 10, 0);
        assertEquals('中', shaped.glyphs().getFirst().glyph().codePoint());
        assertTrue(shaped.diagnostics().stream().noneMatch(d -> d.severity() == TextDiagnostic.Severity.WARNING));
        var absentPlaceholder = com.mirakyux.export4j.font.FontGroup.placeholder(List.of(latin), 0x20000);
        assertThrows(TextLayoutException.class, () -> new SimpleTextShaper().shape("中", absentPlaceholder, 10, 0));
        assertEquals(TextLayoutException.Code.INVALID_TEXT, assertThrows(TextLayoutException.class,
                () -> new SimpleTextShaper().shape("\uD800", group, 10, 0)).code());
        assertEquals(TextLayoutException.Code.UNSUPPORTED_SHAPING, assertThrows(TextLayoutException.class,
                () -> new SimpleTextShaper().shape("e\u0301", group, 10, 0)).code());
    }
    private static FontFace latin;
    private static FontFace chinese;
    private static final ParagraphStyle STYLE = new ParagraphStyle(10, 15, 0, 0, TextAlignment.LEFT);

    @BeforeAll
    static void loadFonts() throws IOException {
        latin = load("LiberationSans-Regular.ttf");
        chinese = load("LXGWWenKaiTC-Regular.ttf");
    }

    @Test
    void measuresRealFontsAndUsesFirstSupportingFaceWithSourceDiagnostics() {
        ShapedText shaped = new SimpleTextShaper().shape("A中B", List.of(latin, chinese), 12, 7);
        assertEquals(latin.data().id(), shaped.glyphs().getFirst().font().id());
        assertEquals(chinese.data().id(), shaped.glyphs().get(1).font().id());
        assertEquals(latin.glyph('A').orElseThrow().advance() * 12,
                shaped.glyphs().getFirst().glyph().advance());
        assertEquals(List.of(new TextDiagnostic(TextDiagnostic.Code.FONT_FALLBACK,
                8, 9, chinese.data().id())), shaped.diagnostics());
        ShapedText reversed = new SimpleTextShaper().shape("A中B", List.of(chinese, latin), 12);
        assertTrue(reversed.glyphs().stream().allMatch(g -> g.font().id().equals(chinese.data().id())));
        assertTrue(reversed.diagnostics().isEmpty());
    }

    @Test
    void wrapsAtWordsAndConsumesSeparatorSpacesInSourceRanges() {
        ParagraphLayout layout = layout("one  two three", 60);
        assertEquals(List.of("one", "two", "three"), texts(layout));
        assertEquals(List.of(0, 5, 9), layout.lines().stream().map(TextLine::sourceStart).toList());
        assertEquals(List.of(5, 9, 14), layout.lines().stream().map(TextLine::sourceEnd).toList());
        assertTrue(layout.lines().stream().allMatch(line -> line.width() <= 60));
    }

    @Test
    void respectsOrdinaryChineseClosingAndOpeningPunctuation() {
        assertEquals(List.of("中", "文，", "测试"), texts(layout("中文，测试", 20)));
        assertEquals(List.of("中", "（文）"), texts(layout("中（文）", 30)));
    }

    @Test
    void emergencyWrapsLongWordsAndSupplementaryScalarsWithoutSplittingSurrogates() {
        assertEquals(List.of("ab", "cd", "ef"), texts(layout("abcdef", 20)));
        ParagraphLayout layout = layout("A\uD83D\uDE00B", 10);
        assertEquals(List.of("A", "\uD83D\uDE00", "B"), texts(layout));
        assertEquals(List.of(0, 1, 3), layout.lines().stream().map(TextLine::sourceStart).toList());
        assertEquals(List.of(1, 3, 4), layout.lines().stream().map(TextLine::sourceEnd).toList());
    }

    @Test
    void preservesHardBreaksEmptyLinesAndOriginalUtf16Offsets() {
        ParagraphLayout layout = layout("A\r\n\nB\r", 100);
        assertEquals(List.of("A", "", "B", ""), texts(layout));
        assertEquals(List.of(0, 3, 4, 6), layout.lines().stream().map(TextLine::sourceStart).toList());
        assertEquals(List.of(1, 3, 5, 6), layout.lines().stream().map(TextLine::sourceEnd).toList());
        assertEquals(60, layout.height());
        assertEquals(List.of(""), texts(layout("", 100)));
    }

    @Test
    void appliesIndentsAndAlignmentWithinRemainingWidth() {
        for (TextAlignment alignment : TextAlignment.values()) {
            ParagraphStyle style = new ParagraphStyle(10, 15, 10, 20, alignment);
            ParagraphLayout layout = new ParagraphLayouter().layout("AB\nC",
                    List.of(fixture(latin, cp -> true, new FontMetrics(0.8, 0.2, 0))), 100, style);
            double first = switch (alignment) {
                case LEFT, JUSTIFY -> 10;
                case CENTER -> 45;
                case RIGHT -> 80;
            };
            double second = switch (alignment) {
                case LEFT, JUSTIFY -> 20;
                case CENTER -> 55;
                case RIGHT -> 90;
            };
            assertEquals(first, layout.lines().getFirst().offsetX());
            assertEquals(second, layout.lines().get(1).offsetX());
        }
    }

    @Test
    void usesConstantBaselineStepAcrossDifferentFontsAndEmptyLines() {
        List<FontFace> fonts = List.of(
                fixture(latin, cp -> cp < 128, new FontMetrics(0.7, 0.2, 0)),
                fixture(chinese, cp -> cp >= 128, new FontMetrics(0.9, 0.3, 0)));
        ParagraphLayout layout = new ParagraphLayouter().layout("A\n中\n\nB", fonts, 100, STYLE);
        assertEquals(List.of(9.0, 24.0, 39.0, 54.0),
                layout.lines().stream().map(TextLine::baselineY).toList());
        assertEquals(60, layout.height());
    }

    @Test
    void rejectsUnsupportedShapingAndInvalidScalarsAtOriginalSourceIndex() {
        for (String text : List.of("A\u0301", "A\u200D", "A\u0628", "A\u05D0", "A\u2028",
                "A\u1100", "A\uD83C\uDFFD", "A\uD83C\uDDE8")) {
            TextLayoutException failure = assertThrows(TextLayoutException.class,
                    () -> new SimpleTextShaper().shape(text, List.of(latin), 10, 5));
            assertEquals(TextLayoutException.Code.UNSUPPORTED_SHAPING, failure.code());
            assertEquals(6, failure.sourceIndex());
        }
        for (String text : List.of("A\uD800", "A\uDC00", "A\t")) {
            TextLayoutException failure = assertThrows(TextLayoutException.class,
                    () -> new SimpleTextShaper().shape(text, List.of(latin), 10, 5));
            assertEquals(TextLayoutException.Code.INVALID_TEXT, failure.code());
            assertEquals(6, failure.sourceIndex());
        }
    }

    @Test
    void reportsMissingGlyphAfterHardBreakWithoutLeakingSource() {
        TextLayoutException failure = assertThrows(TextLayoutException.class,
                () -> new ParagraphLayouter().layout("A\r\n秘密", List.of(latin), 100, STYLE));
        assertEquals(TextLayoutException.Code.MISSING_GLYPH, failure.code());
        assertEquals(3, failure.sourceIndex());
        assertFalse(failure.getMessage().contains("秘密"));
    }

    @Test
    void rejectsImpossibleGlyphAndLineBoxRatherThanRetrying() {
        TextLayoutException wide = assertThrows(TextLayoutException.class, () -> layout("A\nB", 9));
        assertEquals(TextLayoutException.Code.GLYPH_TOO_WIDE, wide.code());
        FontFace font = fixture(latin, cp -> true, new FontMetrics(0.9, 0.3, 0));
        TextLayoutException high = assertThrows(TextLayoutException.class,
                () -> new ParagraphLayouter().layout("A", List.of(font), 100,
                        new ParagraphStyle(10, 11, 0, 0, TextAlignment.LEFT)));
        assertEquals(TextLayoutException.Code.LINE_HEIGHT_TOO_SMALL, high.code());
    }

    @Test
    void enforcesCodePointAndLineBudgets() {
        FontFace font = fixture(latin, cp -> true, new FontMetrics(0.8, 0.2, 0));
        ParagraphLayouter bounded = new ParagraphLayouter(new SimpleTextShaper(), new TextLayoutLimits(2, 1));
        assertEquals(List.of("A\uD83D\uDE00"), texts(bounded.layout("A\uD83D\uDE00", List.of(font), 100, STYLE)));
        TextLayoutException codePoints = assertThrows(TextLayoutException.class,
                () -> bounded.layout("ABC", List.of(font), 100, STYLE));
        assertEquals(TextLayoutException.Code.BUDGET_EXCEEDED, codePoints.code());
        TextLayoutException lines = assertThrows(TextLayoutException.class,
                () -> bounded.layout("AB", List.of(font), 10, STYLE));
        assertEquals(TextLayoutException.Code.BUDGET_EXCEEDED, lines.code());
        assertEquals(1, lines.sourceIndex());
    }

    @Test
    void realFontLayoutIsRepeatableAndCollectionsAreImmutableSnapshots() {
        ParagraphLayouter layouter = new ParagraphLayouter();
        List<FontFace> fonts = List.of(latin, chinese);
        String text = "中文与 English 混排。\nNext line.";
        ParagraphLayout first = layouter.layout(text, fonts, 120, ParagraphStyle.of(12));
        assertEquals(first, layouter.layout(text, fonts, 120, ParagraphStyle.of(12)));
        assertThrows(UnsupportedOperationException.class, () -> first.lines().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.diagnostics().clear());
        List<PositionedGlyph> glyphs = new ArrayList<>(first.lines().getFirst().runs().getFirst().glyphs());
        GlyphRun run = new GlyphRun(chinese.data(), 12, glyphs);
        glyphs.clear();
        assertFalse(run.glyphs().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> run.glyphs().clear());
    }

    private static ParagraphLayout layout(String text, double width) {
        return new ParagraphLayouter().layout(text,
                List.of(fixture(latin, cp -> true, new FontMetrics(0.8, 0.2, 0))), width, STYLE);
    }

    private static List<String> texts(ParagraphLayout layout) {
        return layout.lines().stream()
                .map(line -> line.runs().stream().map(GlyphRun::text).reduce("", String::concat)).toList();
    }

    // Synthetic metrics isolate layout geometry; these fake glyph mappings are never rendered.
    private static FontFace fixture(FontFace source, IntPredicate supports, FontMetrics metrics) {
        return new FontFace() {
            @Override
            public FontData data() {
                return source.data();
            }

            @Override
            public FontMetrics metrics() {
                return metrics;
            }

            @Override
            public Optional<GlyphMetrics> glyph(int codePoint) {
                return supports.test(codePoint) ? Optional.of(new GlyphMetrics(1, 1)) : Optional.empty();
            }
        };
    }

    private static FontFace load(String name) throws IOException {
        try (InputStream input = ParagraphLayouterTest.class.getResourceAsStream("/fonts/" + name)) {
            if (input == null) {
                throw new IOException("Missing test font");
            }
            return TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES);
        }
    }
}
