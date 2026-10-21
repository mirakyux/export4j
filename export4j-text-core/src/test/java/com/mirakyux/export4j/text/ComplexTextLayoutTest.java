// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.awt.Font;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.awt.font.TextMeasurer;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.io.IOException;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;

class ComplexTextLayoutTest {
    private static final ParagraphStyle STYLE = new ParagraphStyle(12, 30, 0, 0, TextAlignment.LEFT);
    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);

    @Test void sourceBoundariesRetainDevanagariViramaAndZwjChainsWithoutNormalizingText() {
        assertEquals(List.of(0, 3), List.copyOf(TextClusters.boundaries("\u0915\u094d\u0937")));
        assertEquals(List.of(0, 4), List.copyOf(TextClusters.boundaries("\u0915\u094d\u200d\u0937")));
        assertEquals(List.of(0, 5), List.copyOf(TextClusters.boundaries("\u0915\u094d\u0937\u094d\u0923")));
        assertEquals(List.of(0, 2, 3), List.copyOf(TextClusters.boundaries("a\u0301b")));
        assertEquals(List.of(0, 3, 4), List.copyOf(TextClusters.boundaries("\u0915\u094d\u200c\u0937")));
        assertThrows(UnsupportedOperationException.class, () -> TextClusters.boundaries("ab").remove(1));
    }

    @Test void placeholderReplacementCannotIntroduceBreaksInsideAnOriginalCluster() throws IOException {
        var group = FontGroup.placeholder(List.of(face("LiberationSans-Regular.ttf")), '?');
        String source = "a\u1ab0\udbff\udfff";
        var failure = assertThrows(TextLayoutException.class, () -> new ParagraphLayouter().layout(source, group, 12, STYLE));
        assertEquals(TextLayoutException.Code.GLYPH_TOO_WIDE, failure.code());
        assertEquals(0, failure.sourceIndex());
        var layout = new ParagraphLayouter().layout(source, group, 14, STYLE);
        assertEquals(List.of(0, 2), layout.lines().stream().map(TextLine::sourceStart).toList());
        assertEquals(List.of(2, 4), layout.lines().stream().map(TextLine::sourceEnd).toList());
        assertEquals(source, logical(layout));
        var warnings = layout.diagnostics().stream().filter(d -> d.severity() == TextDiagnostic.Severity.WARNING).toList();
        assertEquals(List.of(1, 2), warnings.stream().map(TextDiagnostic::sourceStart).toList());
        assertEquals(List.of(2, 4), warnings.stream().map(TextDiagnostic::sourceEnd).toList());
        assertEquals(List.of(0x1ab0, 0x10ffff), warnings.stream().map(TextDiagnostic::sourceCodePoint).toList());
    }

    @Test void cancellationInterruptsOneLargeMissingGlyphClusterBeforeWidthFailureAndRestoresScope() throws IOException {
        var group = FontGroup.placeholder(List.of(face("LiberationSans-Regular.ttf")), '?');
        String source = "a" + "\u1ab0".repeat(16_384);
        var checkpoints = new AtomicInteger();
        var failure = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(
                ExecutionOptions.builder().cancelled(() -> checkpoints.incrementAndGet() > 6).build(),
                () -> new ParagraphLayouter().layout(source, group, 1, STYLE)));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code());
        ExecutionContext.checkpoint();
        assertEquals("a\u1ab0", logical(new ParagraphLayouter().layout("a\u1ab0", group, 100, STYLE)));
    }

    @Test void devanagariConjunctUsesOneFallbackFontAndCannotBeEmergencySplit() throws IOException {
        var group = FontGroup.strict(List.of(face("LiberationSans-Regular.ttf"), face("NotoSansDevanagari-Regular.ttf")));
        String source = "\u0915\u094d\u0937";
        assertEquals(TextLayoutException.Code.GLYPH_TOO_WIDE, assertThrows(TextLayoutException.class,
                () -> new ParagraphLayouter().layout(source, group, 7, STYLE)).code());
        var layout = new ParagraphLayouter().layout(source, group, 9, STYLE);
        assertEquals(1, layout.lines().size());
        assertEquals(source, logical(layout));
        assertEquals(List.of(0, 1, 2), layout.diagnostics().stream().map(TextDiagnostic::sourceStart).toList());
        assertTrue(layout.diagnostics().stream().allMatch(d -> d.code() == TextDiagnostic.Code.FONT_FALLBACK
                && d.fontId().equals(group.fonts().get(1).data().id())));
    }

    @Test void sameResolvedStyleMayCrossNodesButDifferentStylesCannotSplitConjuncts() throws IOException {
        var group = FontGroup.strict(List.of(face("NotoSansDevanagari-Regular.ttf")));
        String source = "\u0915\u094d\u0937";
        var joined = new ParagraphLayouter().layout(source, group, 100, STYLE);
        var split = new ParagraphLayouter().layout(List.of(span(source.substring(0, 2), group, 0), span(source.substring(2), group, 0)), 100, STYLE);
        assertEquals(source, logical(split));
        assertGeometry(ink(joined.lines().getFirst()), ink(split.lines().getFirst()));
        var failure = assertThrows(TextLayoutException.class, () -> new ParagraphLayouter().layout(
                List.of(span(source.substring(0, 2), group, 0), span(source.substring(2), group, 0xff0000)), 100, STYLE));
        assertEquals(TextLayoutException.Code.INVALID_TEXT, failure.code());
        assertEquals(0, failure.sourceIndex());
    }

    @Test void actualArabicLigatureClustersStayAtomicAndRejectMixedStyleBoundaries() throws IOException {
        var group = FontGroup.strict(List.of(face("NotoNaskhArabic-Regular.ttf")));
        String source = "\u0644\u0627";
        assertEquals(TextLayoutException.Code.GLYPH_TOO_WIDE, assertThrows(TextLayoutException.class,
                () -> new ParagraphLayouter().layout(source, group, 4, STYLE)).code());
        assertEquals(source, logical(new ParagraphLayouter().layout(source, group, 7, STYLE)));
        var failure = assertThrows(TextLayoutException.class, () -> new ParagraphLayouter().layout(
                List.of(span(source.substring(0, 1), group, 0xff0000), span(source.substring(1), group, 0x0000ff)), 100, STYLE));
        assertEquals(TextLayoutException.Code.INVALID_TEXT, failure.code());
        assertEquals(source, logical(new ParagraphLayouter().layout(
                List.of(span(source.substring(0, 1), group, 0), span(source.substring(1), group, 0)), 100, STYLE)));
    }

    @Test void colorOnlyChangesDoNotReshapeKernedFontRuns() throws IOException {
        var group = FontGroup.strict(List.of(face("LiberationSans-Regular.ttf")));
        String source = "AVATAR a\u0301";
        var joined = new ParagraphLayouter().layoutOpenType(List.of(span(source, group, 0)), 200, STYLE);
        var split = new ParagraphLayouter().layoutOpenType(List.of(span("A", group, 0xff0000), span(source.substring(1), group, 0x0000ff)), 200, STYLE);
        assertEquals(joined.lines().getFirst().width(), split.lines().getFirst().width(), 1e-6);
        assertEquals(source, logical(split));
        assertEquals(List.of(0xff0000, 0x0000ff), split.lines().getFirst().outlines().stream().map(TextOutline::rgb).toList());
        assertGeometry(ink(joined.lines().getFirst()), ink(split.lines().getFirst()));
    }

    @Test void softLinesRetainParagraphBidiAndShapingContextInTheirActualInk() throws IOException {
        var names = List.of("NotoNaskhArabic-Regular.ttf", "LiberationSans-Regular.ttf");
        var faces = new ArrayList<FontFace>();
        var fonts = new ArrayList<Font>();
        for (var name : names) { faces.add(face(name)); fonts.add(physical(name)); }
        var group = FontGroup.strict(faces);
        for (var source : List.of("العربية (123) 456", "العربيةالعربية")) {
            var attributed = new AttributedString(source);
            for (int index = 0; index < source.length(); index++) {
                attributed.addAttribute(TextAttribute.FONT, fonts.get(faces.getFirst().glyph(source.charAt(index)).isPresent() ? 0 : 1), index, index + 1);
            }
            var measurer = new TextMeasurer(attributed.getIterator(), FRC);
            for (double width : List.of(22.0, 32.0)) {
                var layout = new ParagraphLayouter().layout(source, group, width, STYLE);
                assertTrue(layout.lines().size() > 1);
                for (var line : layout.lines()) {
                    int drawnEnd = line.sourceStart() + line.outlines().stream().mapToInt(outline -> outline.logicalText().length()).sum();
                    var measured = measurer.getLayout(line.sourceStart(), drawnEnd);
                    assertEquals(measured.getAdvance(), line.width(), 1e-5);
                    assertGeometry(measured.getOutline(null), ink(line));
                }
            }
        }
    }

    @Test void thaiSaraAmAndToneMarksRemainWithTheirConsonant() throws IOException {
        var group = FontGroup.strict(List.of(face("NotoSansThai-Regular.ttf")));
        String source = "\u0e01\u0e48\u0e33";
        assertEquals(List.of(0, 3), List.copyOf(TextClusters.boundaries(source)));
        assertEquals(TextLayoutException.Code.GLYPH_TOO_WIDE, assertThrows(TextLayoutException.class,
                () -> new ParagraphLayouter().layout(source, group, 9, STYLE)).code());
        assertEquals(source, logical(new ParagraphLayouter().layout(source, group, 13, STYLE)));
    }

    private static StyledSpan span(String text, FontGroup group, int rgb) { return new StyledSpan(text, group, 12, rgb, 0, false, false); }
    private static String logical(ParagraphLayout layout) { return layout.lines().stream().flatMap(line -> line.outlines().stream()).map(TextOutline::logicalText).collect(Collectors.joining()); }
    private static FontFace face(String name) throws IOException {
        try (var input = ComplexTextLayoutTest.class.getResourceAsStream("/fonts/" + name)) { return TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES); }
    }
    private static Font physical(String name) throws IOException {
        try (var input = ComplexTextLayoutTest.class.getResourceAsStream("/fonts/" + name)) {
            try { return Font.createFont(Font.TRUETYPE_FONT, input).deriveFont(12f).deriveFont(Map.of(TextAttribute.KERNING, 1, TextAttribute.LIGATURES, 1)); }
            catch (java.awt.FontFormatException failure) { throw new IOException(failure); }
        }
    }
    private static Shape ink(TextLine line) {
        var result = new Path2D.Double();
        for (var outline : line.outlines()) {
            for (var segment : outline.segments()) {
                var c = segment.coordinates();
                switch (segment.operation()) {
                    case MOVE -> result.moveTo(c.get(0), c.get(1));
                    case LINE -> result.lineTo(c.get(0), c.get(1));
                    case CURVE -> result.curveTo(c.get(0), c.get(1), c.get(2), c.get(3), c.get(4), c.get(5));
                    case CLOSE -> result.closePath();
                }
            }
        }
        return result;
    }
    private static void assertGeometry(Shape expected, Shape actual) {
        var difference = new Area(expected);
        difference.exclusiveOr(new Area(actual));
        // Float JDK glyph positions and the IR's quadratic-to-cubic conversion differ by tiny
        // fractions of a point. Measure actual filled area, not antialias-sensitive image pixels.
        assertTrue(area(difference) < .02, () -> "shaped ink differs by " + area(difference) + " square points");
    }
    private static double area(Shape shape) {
        double total = 0; double x = 0; double y = 0; double startX = 0; double startY = 0;
        double[] c = new double[6];
        for (var it = shape.getPathIterator(null, .0001); !it.isDone(); it.next()) {
            switch (it.currentSegment(c)) {
                case PathIterator.SEG_MOVETO -> { x = startX = c[0]; y = startY = c[1]; }
                case PathIterator.SEG_LINETO -> { total += x * c[1] - y * c[0]; x = c[0]; y = c[1]; }
                case PathIterator.SEG_CLOSE -> total += x * startY - y * startX;
                default -> throw new AssertionError("flattened path contains a curve");
            }
        }
        return Math.abs(total) / 2;
    }
}
