// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.awt.Font;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.awt.font.TextMeasurer;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.io.IOException;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.VectorPath;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextAlignment;
import com.mirakyux.export4j.text.TextDiagnostic;

class ComplexOpenTypeTextTest {
    private static final ParagraphStyle STYLE = new ParagraphStyle(12, 30, 0, 0, TextAlignment.LEFT);
    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);

    @Test void replacementKeepsOriginalClusterAndSupplementaryDiagnosticRanges() throws IOException {
        var group = FontGroup.placeholder(List.of(face("LiberationSans-Regular.ttf")), '?');
        String source = "a\u1ab0\udbff\udfff";
        var text = new OpenTypeText(source, STYLE, RgbColor.BLACK);
        assertThrows(IllegalArgumentException.class, () -> text.layoutParts(context(group, 12)));
        var measured = text.measure(context(group, 14), line -> 14, line -> 0);
        assertEquals(List.of(0, 2), measured.lines().stream().map(OpenTypeText.Line::sourceStart).toList());
        assertEquals(List.of(2, 4), measured.lines().stream().map(OpenTypeText.Line::sourceEnd).toList());
        assertEquals(source, measured.lines().stream().map(line -> line.command().logicalText()).collect(Collectors.joining()));
        var warnings = measured.diagnostics().stream().filter(d -> d.severity() == TextDiagnostic.Severity.WARNING).toList();
        assertEquals(List.of(1, 2), warnings.stream().map(TextDiagnostic::sourceStart).toList());
        assertEquals(List.of(2, 4), warnings.stream().map(TextDiagnostic::sourceEnd).toList());
    }

    @Test void cancellationInterruptsOneLargeMissingGlyphClusterBeforeWidthFailureAndRestoresScope() throws IOException {
        var group = FontGroup.placeholder(List.of(face("LiberationSans-Regular.ttf")), '?');
        var text = new OpenTypeText("a" + "\u1ab0".repeat(16_384), STYLE, RgbColor.BLACK);
        var checkpoints = new AtomicInteger();
        var failure = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(
                ExecutionOptions.builder().cancelled(() -> checkpoints.incrementAndGet() > 6).build(),
                () -> text.measure(context(group, 1), line -> 1, line -> 0)));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code());
        ExecutionContext.checkpoint();
        var reused = new OpenTypeText("a\u1ab0", STYLE, RgbColor.BLACK).measure(context(group, 100), line -> 100, line -> 0);
        assertEquals("a\u1ab0", reused.lines().getFirst().command().logicalText());
    }

    @Test void cancellationBetweenSoftLinesStopsBeforeMeasuringAnotherLine() throws IOException {
        var group = FontGroup.strict(List.of(face("LiberationSans-Regular.ttf")));
        var text = new OpenTypeText("a\u0301 ".repeat(20), STYLE, RgbColor.BLACK);
        var cancelled = new AtomicBoolean();
        var measuredWidths = new AtomicInteger();
        var failure = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(
                ExecutionOptions.builder().cancelled(cancelled::get).build(),
                () -> text.measure(context(group, 20), line -> {
                    if (measuredWidths.incrementAndGet() == 2) { cancelled.set(true); }
                    return 20;
                }, line -> 0)));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code());
        assertEquals(2, measuredWidths.get());
        ExecutionContext.checkpoint();
    }

    @Test void explicitEmptyLinesCannotBypassTheOutputLineBudget() throws IOException {
        var group = FontGroup.strict(List.of(face("LiberationSans-Regular.ttf")));
        var text = new OpenTypeText("\n".repeat(100_000), STYLE, RgbColor.BLACK);
        var failure = assertThrows(IllegalArgumentException.class,
                () -> text.measure(context(group, 100), line -> 100, line -> 0));
        assertEquals("OpenType output budget exceeded", failure.getMessage());
    }

    @Test void devanagariThaiAndArabicShapingClustersCannotBeEmergencySplit() throws IOException {
        for (var fixture : List.of(new Cluster("NotoSansDevanagari-Regular.ttf", "\u0915\u094d\u0937", 7, 9),
                new Cluster("NotoSansThai-Regular.ttf", "\u0e01\u0e48\u0e33", 9, 13),
                new Cluster("NotoNaskhArabic-Regular.ttf", "\u0644\u0627", 4, 7))) {
            var group = FontGroup.strict(List.of(face(fixture.font())));
            var text = new OpenTypeText(fixture.source(), STYLE, RgbColor.BLACK);
            assertThrows(IllegalArgumentException.class, () -> text.layoutParts(context(group, fixture.narrow())));
            var measured = text.measure(context(group, fixture.wide()), line -> fixture.wide(), line -> 0);
            assertEquals(1, measured.lines().size());
            assertEquals(fixture.source(), measured.lines().getFirst().command().logicalText());
            assertEquals(fixture.source().length(), measured.lines().getFirst().sourceEnd());
        }
    }

    @Test void justifiedArabicShapesWholeFontRunsAndMatchesJdkWhitespaceExpansion() throws IOException {
        var names = List.of("NotoNaskhArabic-Regular.ttf");
        String source = "لا لا لا لا لا لا لا لا";
        assertMeasuredInk(names, source, 22, OpenTypeText.Direction.AUTO);
    }

    @Test void justifiedContinuationInheritsParagraphBidiInsteadOfRedetectingNeutralLeadingLines() throws IOException {
        var names = List.of("NotoNaskhArabic-Regular.ttf", "LiberationSans-Regular.ttf");
        String source = "العربية (123) 456 789 012";
        assertMeasuredInk(names, source, 50, OpenTypeText.Direction.AUTO);
        assertMeasuredInk(names, source, 50, OpenTypeText.Direction.LEFT_TO_RIGHT);
    }

    private static void assertMeasuredInk(List<String> names, String source, double width, OpenTypeText.Direction direction) throws IOException {
        var faces = new ArrayList<FontFace>();
        var fonts = new ArrayList<Font>();
        for (var name : names) { faces.add(face(name)); fonts.add(physical(name)); }
        var group = FontGroup.strict(faces);
        var text = new OpenTypeText(source, STYLE.withAlignment(TextAlignment.JUSTIFY), RgbColor.BLACK, direction, true, true, false);
        var measured = text.measure(context(group, width), line -> width, line -> 0);
        assertTrue(measured.lines().size() > 1);
        var attributed = new AttributedString(source);
        for (int index = 0; index < source.length(); index++) {
            attributed.addAttribute(TextAttribute.FONT, fonts.get(faces.getFirst().glyph(source.charAt(index)).isPresent() ? 0 : 1), index, index + 1);
        }
        if (direction != OpenTypeText.Direction.AUTO) { attributed.addAttribute(TextAttribute.RUN_DIRECTION, direction == OpenTypeText.Direction.RIGHT_TO_LEFT); }
        var measurer = new TextMeasurer(attributed.getIterator(), FRC);
        for (var line : measured.lines()) {
            int end = line.sourceEnd();
            if (end < source.length()) { while (end > line.sourceStart() && source.charAt(end - 1) == ' ') { end--; } }
            var original = measurer.getLayout(line.sourceStart(), end);
            var expected = line.sourceEnd() < source.length() ? original.getJustifiedLayout((float) width) : original;
            assertEquals(expected.getAdvance(), line.width(), 1e-4);
            var shape = expected.getOutline(AffineTransform.getTranslateInstance(0, original.getAscent()));
            assertGeometry(shape, ink(line.command().paths()));
        }
    }

    private record Cluster(String font, String source, double narrow, double wide) { }
    private static ComponentContext context(FontGroup group, double width) { return new ComponentContext(width, 1000, group, 0, 0, 1); }
    private static FontFace face(String name) throws IOException {
        try (var input = ComplexOpenTypeTextTest.class.getResourceAsStream("/fonts/" + name)) { return TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES); }
    }
    private static Font physical(String name) throws IOException {
        try (var input = ComplexOpenTypeTextTest.class.getResourceAsStream("/fonts/" + name)) {
            try { return Font.createFont(Font.TRUETYPE_FONT, input).deriveFont(12f).deriveFont(Map.of(TextAttribute.KERNING, 1, TextAttribute.LIGATURES, 1)); }
            catch (java.awt.FontFormatException failure) { throw new IOException(failure); }
        }
    }
    private static Shape ink(List<VectorPath> paths) {
        var result = new Path2D.Double();
        for (var path : paths) {
            for (var segment : path.segments()) {
                switch (segment) {
                    case VectorPath.MoveTo move -> result.moveTo(move.point().x(), move.point().y());
                    case VectorPath.LineTo line -> result.lineTo(line.point().x(), line.point().y());
                    case VectorPath.CurveTo curve -> result.curveTo(curve.control1().x(), curve.control1().y(), curve.control2().x(), curve.control2().y(), curve.end().x(), curve.end().y());
                    case VectorPath.Close ignored -> result.closePath();
                }
            }
        }
        return result;
    }
    private static void assertGeometry(Shape expected, Shape actual) {
        var difference = new Area(expected);
        difference.exclusiveOr(new Area(actual));
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
