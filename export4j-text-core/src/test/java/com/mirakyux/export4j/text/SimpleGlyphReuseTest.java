// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mirakyux.export4j.font.FontData;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.FontMetrics;
import com.mirakyux.export4j.font.GlyphMetrics;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class SimpleGlyphReuseTest {
    private static final int SUPPLEMENTARY = 0x20000;
    private static final String REPEATED = "A\uD840\uDC00A";
    private static final FontMetrics REGULAR_METRICS = new FontMetrics(.8, .2, 0);
    private static final FontMetrics BOLD_METRICS = new FontMetrics(.9, .3, 0);
    private static FontFace regular;
    private static FontFace bold;

    @BeforeAll static void loadFonts() throws IOException {
        regular = load("LiberationSans-Regular.ttf");
        bold = load("LiberationSans-Bold.ttf");
    }

    @Test
    void equalGlyphValuesAreSharedAcrossCallsAndFontsWhileSourcesAndLineMetricsStayIndependent() {
        var shaper = new SimpleTextShaper();
        var firstFace = fixture(regular, REGULAR_METRICS, new GlyphMetrics(7, .5), cp -> true);
        var secondFace = fixture(bold, BOLD_METRICS, new GlyphMetrics(7, .5), cp -> true);
        var first = shaper.shape("AA", FontGroup.strict(firstFace), 10, 7);
        var second = shaper.shape("AA", FontGroup.strict(secondFace), 10, 40);

        assertSame(first.glyphs().getFirst().glyph(), first.glyphs().getLast().glyph());
        assertSame(first.glyphs().getFirst().glyph(), second.glyphs().getLast().glyph());
        assertSame(second.glyphs().getFirst().glyph(), second.glyphs().getLast().glyph());
        assertSource(first, regular.data(), List.of(7, 8), List.of(8, 9), List.of((int) 'A', (int) 'A'));
        assertSource(second, bold.data(), List.of(40, 41), List.of(41, 42), List.of((int) 'A', (int) 'A'));
        var supplementary = shaper.shape("\uD840\uDC00\uD840\uDC00", FontGroup.strict(firstFace), 10, 60);
        var supplementaryOtherFont = shaper.shape("\uD840\uDC00", FontGroup.strict(secondFace), 10, 100);
        assertSame(supplementary.glyphs().getFirst().glyph(), supplementary.glyphs().getLast().glyph());
        assertSame(supplementary.glyphs().getFirst().glyph(), supplementaryOtherFont.glyphs().getFirst().glyph());
        assertSource(supplementary, regular.data(), List.of(60, 62), List.of(62, 64), List.of(SUPPLEMENTARY, SUPPLEMENTARY));
        assertSource(supplementaryOtherFont, bold.data(), List.of(100), List.of(102), List.of(SUPPLEMENTARY));
        assertTrue(first.glyphs().stream().allMatch(glyph -> glyph.ascent() == 8 && glyph.descent() == 2));
        assertTrue(second.glyphs().stream().allMatch(glyph -> glyph.ascent() == 9 && glyph.descent() == 3));
        assertTrue(first.diagnostics().isEmpty());
        assertTrue(second.diagnostics().isEmpty());
        assertTrue(supplementary.diagnostics().isEmpty());
        assertTrue(supplementaryOtherFont.diagnostics().isEmpty());
    }

    @Test
    void theSameScalarKeepsDifferentMappingsAndExactAdvancesAndDifferentScalarsStayDistinct() {
        var shaper = new SimpleTextShaper();
        var baseFace = fixture(regular, REGULAR_METRICS, new GlyphMetrics(7, .5), cp -> true);
        var mappedFace = fixture(regular, REGULAR_METRICS, new GlyphMetrics(8, .5), cp -> true);
        var widerFace = fixture(regular, REGULAR_METRICS, new GlyphMetrics(7, Math.nextUp(.5)), cp -> true);
        var base = shaper.shape("A", FontGroup.strict(baseFace), 1, 0).glyphs().getFirst().glyph();
        assertSame(base, shaper.shape("A", FontGroup.strict(baseFace), 1, 99).glyphs().getFirst().glyph());
        var remapped = shaper.shape("A", FontGroup.strict(mappedFace), 1, 0).glyphs().getFirst().glyph();
        var wider = shaper.shape("A", FontGroup.strict(widerFace), 1, 0).glyphs().getFirst().glyph();
        var scaled = shaper.shape("A", FontGroup.strict(baseFace), 2, 0).glyphs().getFirst().glyph();
        var otherScalar = shaper.shape("B", FontGroup.strict(baseFace), 1, 0).glyphs().getFirst().glyph();

        assertEquals(new PositionedGlyph('A', 7, .5), base);
        assertEquals(new PositionedGlyph('A', 8, .5), remapped);
        assertEquals(new PositionedGlyph('A', 7, Math.nextUp(.5)), wider);
        assertEquals(new PositionedGlyph('A', 7, 1), scaled);
        assertEquals(new PositionedGlyph('B', 7, .5), otherScalar);
        assertNotSame(base, remapped);
        assertNotSame(base, wider);
        assertNotSame(base, scaled);
        assertNotSame(base, otherScalar);
        assertEquals(base, shaper.shape("A", FontGroup.strict(baseFace), 1, 99).glyphs().getFirst().glyph());

        var positiveZeroFace = fixture(regular, REGULAR_METRICS, new GlyphMetrics(7, +0.0), cp -> true);
        var negativeZeroFace = fixture(regular, REGULAR_METRICS, new GlyphMetrics(7, -0.0), cp -> true);
        var positiveZero = shaper.shape("A", FontGroup.strict(positiveZeroFace), 1, 0).glyphs().getFirst().glyph();
        var negativeZero = shaper.shape("A", FontGroup.strict(negativeZeroFace), 1, 0).glyphs().getFirst().glyph();
        assertEquals(0L, Double.doubleToRawLongBits(positiveZero.advance()));
        assertEquals(Long.MIN_VALUE, Double.doubleToRawLongBits(negativeZero.advance()));
        assertNotSame(positiveZero, negativeZero);
        assertEquals(0L, Double.doubleToRawLongBits(shaper.shape("A", FontGroup.strict(positiveZeroFace), 1, 100).glyphs().getFirst().glyph().advance()));
        assertEquals(Long.MIN_VALUE, Double.doubleToRawLongBits(shaper.shape("A", FontGroup.strict(negativeZeroFace), 1, 101).glyphs().getFirst().glyph().advance()));
    }

    @Test
    void sharedReplacementInkDoesNotShareOriginalScalarsRangesOrWarningAndFallbackDiagnostics() {
        var shaper = new SimpleTextShaper();
        var absent = fixture(regular, REGULAR_METRICS, new GlyphMetrics(13, .5), cp -> false);
        var replacement = fixture(bold, BOLD_METRICS, new GlyphMetrics(13, .5), cp -> cp == '?');
        var literal = shaper.shape("?", FontGroup.strict(replacement), 10, 100);
        var group = FontGroup.placeholder(List.of(absent, replacement), '?');
        var replaced = shaper.shape("中\uD840\uDC00?", group, 10, 5);

        assertTrue(replaced.glyphs().stream().allMatch(glyph -> glyph.glyph() == literal.glyphs().getFirst().glyph()));
        assertSource(replaced, bold.data(), List.of(5, 6, 8), List.of(6, 8, 9), List.of((int) '中', SUPPLEMENTARY, (int) '?'));
        assertEquals(List.of((int) '?', (int) '?', (int) '?'), replaced.glyphs().stream().map(glyph -> glyph.glyph().codePoint()).toList());
        var warnings = replaced.diagnostics().stream().filter(diagnostic -> diagnostic.code() == TextDiagnostic.Code.MISSING_GLYPH_REPLACED).toList();
        assertEquals(List.of(new TextDiagnostic(TextDiagnostic.Code.MISSING_GLYPH_REPLACED, 5, 6, bold.data().id(), '中', '?'),
                new TextDiagnostic(TextDiagnostic.Code.MISSING_GLYPH_REPLACED, 6, 8, bold.data().id(), SUPPLEMENTARY, '?')), warnings);
        var fallbacks = replaced.diagnostics().stream().filter(diagnostic -> diagnostic.code() == TextDiagnostic.Code.FONT_FALLBACK).toList();
        assertEquals(List.of(new TextDiagnostic(TextDiagnostic.Code.FONT_FALLBACK, 5, 6, bold.data().id()),
                new TextDiagnostic(TextDiagnostic.Code.FONT_FALLBACK, 6, 8, bold.data().id()),
                new TextDiagnostic(TextDiagnostic.Code.FONT_FALLBACK, 8, 9, bold.data().id())), fallbacks);
        assertTrue(literal.diagnostics().isEmpty());
        assertEquals(100, literal.glyphs().getFirst().sourceStart());
        assertEquals((int) '?', literal.glyphs().getFirst().sourceCodePoint());
    }

    @Test
    @Timeout(30)
    void oneShaperPreservesConcurrentFontsMappingsSizesAndUtf16Sources() throws Exception {
        var shaper = new SimpleTextShaper();
        var faces = List.of(
                fixture(regular, REGULAR_METRICS, new GlyphMetrics(7, .5), cp -> true),
                fixture(regular, REGULAR_METRICS, new GlyphMetrics(8, .5), cp -> true),
                fixture(bold, BOLD_METRICS, new GlyphMetrics(7, .5), cp -> true),
                fixture(bold, BOLD_METRICS, new GlyphMetrics(7, Math.nextUp(.5)), cp -> true));
        var gate = new CyclicBarrier(faces.size());
        var executor = Executors.newFixedThreadPool(faces.size());
        var futures = new ArrayList<Future<List<ShapedText>>>();
        try {
            for (int index = 0; index < faces.size(); index++) {
                var face = faces.get(index);
                int worker = index;
                futures.add(executor.submit(() -> {
                    gate.await(5, TimeUnit.SECONDS);
                    var results = new ArrayList<ShapedText>();
                    for (int round = 0; round < 32; round++) {
                        results.add(shaper.shape(REPEATED, FontGroup.strict(face), 10 + round, worker * 1000 + round * 4));
                    }
                    return List.copyOf(results);
                }));
            }
            var results = new ArrayList<List<ShapedText>>();
            for (var future : futures) { results.add(future.get(10, TimeUnit.SECONDS)); }
            for (int worker = 0; worker < faces.size(); worker++) {
                var face = faces.get(worker);
                for (int round = 0; round < 32; round++) {
                    int start = worker * 1000 + round * 4;
                    var shaped = results.get(worker).get(round);
                    assertSource(shaped, face.data(), List.of(start, start + 1, start + 3),
                            List.of(start + 1, start + 3, start + 4), List.of((int) 'A', SUPPLEMENTARY, (int) 'A'));
                    int id = worker == 1 ? 8 : 7;
                    double advance = (worker == 3 ? Math.nextUp(.5) : .5) * (10 + round);
                    assertEquals(List.of(new PositionedGlyph('A', id, advance), new PositionedGlyph(SUPPLEMENTARY, id, advance),
                            new PositionedGlyph('A', id, advance)), shaped.glyphs().stream().map(ShapedGlyph::glyph).toList());
                    assertTrue(shaped.diagnostics().isEmpty());
                }
            }
        } finally {
            for (var future : futures) { future.cancel(true); }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "shaping workers did not terminate");
        }
    }

    private static void assertSource(ShapedText text, FontData font, List<Integer> starts, List<Integer> ends, List<Integer> codePoints) {
        assertEquals(starts, text.glyphs().stream().map(ShapedGlyph::sourceStart).toList());
        assertEquals(ends, text.glyphs().stream().map(ShapedGlyph::sourceEnd).toList());
        assertEquals(codePoints, text.glyphs().stream().map(ShapedGlyph::sourceCodePoint).toList());
        assertTrue(text.glyphs().stream().allMatch(glyph -> glyph.font() == font));
    }

    private static FontFace fixture(FontFace program, FontMetrics metrics, GlyphMetrics glyph, IntPredicate supports) {
        return new FontFace() {
            @Override public FontData data() { return program.data(); }
            @Override public FontMetrics metrics() { return metrics; }
            @Override public Optional<GlyphMetrics> glyph(int codePoint) { return supports.test(codePoint) ? Optional.of(glyph) : Optional.empty(); }
        };
    }

    private static FontFace load(String name) throws IOException {
        try (var input = SimpleGlyphReuseTest.class.getResourceAsStream("/fonts/" + name)) {
            return TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES);
        }
    }
}
