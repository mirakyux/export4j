// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineStyle;

class InlineImageLayoutTest {
    private static FontGroup fonts(boolean placeholder) throws IOException {
        try (var input = InlineImageLayoutTest.class.getResourceAsStream("/fonts/NotoNaskhArabic-Regular.ttf")) {
            var faces = List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES));
            return placeholder ? FontGroup.placeholder(faces, '?') : FontGroup.strict(faces);
        }
    }
    private static InlineImage image(double width, double height, double shift) throws IOException {
        var data = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", data);
        return new InlineImage(ImageResource.load(data.toByteArray()), width, height, shift, "Test");
    }

    private static FontFace face(String name) throws IOException {
        try (var input = InlineImageLayoutTest.class.getResourceAsStream("/fonts/" + name)) {
            return TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES);
        }
    }

    @Test void directlyMappedMixedTextKeepsOriginalRangesAndExplicitImageGaps() throws IOException {
        var latin = face("LiberationSans-Regular.ttf"); var chinese = face("LXGWWenKaiTC-Regular.ttf");
        var group = FontGroup.strict(List.of(latin, chinese));
        String source = "A\ufffc中B\ufffc文\r\n\ufffc中A";
        var images = Map.of(1, image(23, 18, 0), 4, image(31, 18, 0), 8, image(17, 18, 0));
        var layout = new ParagraphLayouter().layout(List.of(new StyledSpan(source, group, 12, -1, 0, false, false)),
                300, new ParagraphStyle(12, 40, 0, 0, TextAlignment.LEFT), images);
        assertEquals(2, layout.lines().size());
        for (var line : layout.lines()) {
            assertFalse(line.glyphRunBoxes().isEmpty());
            assertTrue(line.runs().isEmpty());
            assertEquals(line.glyphRunBoxes(), line.atBaseline(200).glyphRunBoxes());
            assertEquals(line.outlines(), line.atBaseline(200).outlines());
            assertEquals(line.images(), line.atBaseline(200).images());
            for (var box : line.glyphRunBoxes()) {
                assertEquals(source.substring(box.sourceStart(), box.sourceEnd()), box.run().text());
                var selected = group.fonts().stream().filter(font -> font.data().id().equals(box.run().font().id())).findFirst().orElseThrow();
                for (var glyph : box.run().glyphs()) {
                    assertEquals(selected.glyph(glyph.codePoint()).orElseThrow().glyphId(), glyph.glyphId());
                }
            }
            for (var box : line.images()) {
                var next = line.glyphRunBoxes().stream().filter(text -> text.sourceStart() == box.sourceIndex() + 1).findFirst().orElseThrow();
                // Image advances are explicit placement gaps; they never enlarge a preceding glyph.
                assertEquals(box.x() + box.image().width(), next.x(), 1e-5);
                line.glyphRunBoxes().stream().filter(text -> text.sourceEnd() == box.sourceIndex()).findFirst().ifPresent(previous ->
                        assertEquals(box.x(), previous.x() + previous.run().width(), 1e-5));
            }
        }
        assertEquals(List.of(0, 2, 3, 5, 9, 10), layout.lines().stream().flatMap(line -> line.glyphRunBoxes().stream()).map(GlyphRunBox::sourceStart).toList());
        assertEquals("A中B文中A", layout.lines().stream().flatMap(line -> line.glyphRunBoxes().stream()).map(box -> box.run().text()).collect(java.util.stream.Collectors.joining()));
        assertThrows(UnsupportedOperationException.class, () -> layout.lines().getFirst().glyphRunBoxes().clear());
    }

    @Test void positionedMixedTextRetainsShapedWrappingJustificationAndStyle() throws IOException {
        var group = FontGroup.strict(List.of(face("LiberationSans-Regular.ttf"), face("LXGWWenKaiTC-Regular.ttf")));
        String source = "中文\ufffc排版测试中文两端对齐布局检查结束";
        var spans = List.of(new StyledSpan(source, group, 12, 0x224488, -2, false, false));
        var style = new ParagraphStyle(12, 40, 0, 0, TextAlignment.JUSTIFY);
        var images = Map.of(2, image(19, 18, 0));
        var layout = new ParagraphLayouter().layout(spans, 83, style, images);
        double ascent = group.fonts().stream().mapToDouble(font -> font.metrics().ascent() * 12 + 2).max().orElseThrow();
        var outlinesOnly = new OpenTypeParagraphLayouter(TextLayoutLimits.DEFAULT).layout(spans, 83, style, ascent,
                images, Map.of(), Map.of(), line -> 83, false);
        assertTrue(layout.lines().size() > 1);
        assertEquals(outlinesOnly.height(), layout.height());
        assertEquals(outlinesOnly.diagnostics(), layout.diagnostics());
        for (int index = 0; index < layout.lines().size(); index++) {
            var line = layout.lines().get(index); var original = outlinesOnly.lines().get(index);
            assertFalse(line.glyphRunBoxes().isEmpty());
            assertEquals(original.sourceStart(), line.sourceStart()); assertEquals(original.sourceEnd(), line.sourceEnd());
            assertEquals(original.offsetX(), line.offsetX()); assertEquals(original.baselineY(), line.baselineY());
            assertEquals(original.width(), line.width()); assertEquals(original.outlines(), line.outlines());
            assertEquals(original.images(), line.images());
            assertTrue(line.glyphRunBoxes().stream().allMatch(box -> box.run().rgb() == 0x224488 && box.run().baselineShift() == -2));
        }
        assertTrue(outlinesOnly.lines().stream().allMatch(line -> line.glyphRunBoxes().isEmpty()));
        assertTrue(new ParagraphLayouter().layoutOpenType(List.of(new StyledSpan("AVATAR中文", group, 12, -1, 0, false, false)),
                83, style).lines().stream().allMatch(line -> line.glyphRunBoxes().isEmpty()));
    }

    @Test void unsafeMixedLinesKeepCompleteOutlineInkAndOriginalText() throws IOException {
        var latin = face("LiberationSans-Regular.ttf");
        var group = FontGroup.strict(List.of(latin, face("NotoNaskhArabic-Regular.ttf")));
        var style = new ParagraphStyle(12, 40, 0, 0, TextAlignment.LEFT);
        for (String source : List.of("A\ufffca\u0301", "A\ufffcالعربية")) {
            var layout = new ParagraphLayouter().layout(List.of(new StyledSpan(source, group, 12, -1, 0, false, false)),
                    300, style, Map.of(1, image(17, 18, 0)));
            assertTrue(layout.lines().getFirst().glyphRunBoxes().isEmpty());
            assertEquals(source, layout.lines().getFirst().outlines().stream().map(TextOutline::logicalText).collect(java.util.stream.Collectors.joining()));
            assertTrue(layout.lines().getFirst().outlines().stream().anyMatch(outline -> !outline.segments().isEmpty()));
        }
        var replacement = new ParagraphLayouter().layout(List.of(new StyledSpan("A\ufffc\uD840\uDC00", FontGroup.placeholder(List.of(latin), '?'), 12, -1, 0, false, false)),
                300, style, Map.of(1, image(17, 18, 0)));
        assertTrue(replacement.lines().getFirst().glyphRunBoxes().isEmpty());
        assertEquals(2, replacement.diagnostics().getFirst().sourceStart());
        assertEquals(4, replacement.diagnostics().getFirst().sourceEnd());
        var cff = new ParagraphLayouter().layout(List.of(new StyledSpan("A\ufffcB", FontGroup.strict(List.of(face("SourceSans3-Regular.otf"))), 12, -1, 0, false, false)),
                300, style, Map.of(1, image(17, 18, 0)));
        assertTrue(cff.lines().getFirst().glyphRunBoxes().isEmpty());
        for (boolean underline : List.of(false, true)) {
            var decorated = new ParagraphLayouter().layout(List.of(new StyledSpan("A\ufffcB", group, 12, -1, 0, underline, !underline)),
                    300, style, Map.of(1, image(17, 18, 0)));
            assertTrue(decorated.lines().getFirst().glyphRunBoxes().isEmpty());
        }
    }

    @Test void inlineFieldsAndGraphicsRetainTheirOutlineResolutionContract() throws IOException {
        var group = FontGroup.strict(List.of(face("LiberationSans-Regular.ttf")));
        var spans = List.of(new StyledSpan("A\ufffcB\ufffcC", group, 12, 0, 0, false, false));
        var style = new ParagraphStyle(12, 40, 0, 0, TextAlignment.LEFT);
        var images = Map.of(1, image(17, 18, 0));
        var field = new InlineField(InlineField.Kind.PAGE, "", 20, InlineStyle.of(12));
        var fields = new ParagraphLayouter().layout(spans, 300, style, images, Map.of(3, field));
        assertTrue(fields.lines().getFirst().glyphRunBoxes().isEmpty());
        assertEquals(1, fields.lines().getFirst().fields().size());
        var resolved = InlineFieldInk.resolve(fields.lines().getFirst().fields().getFirst(), "12", 40, group, new ParagraphLayouter()).outline();
        assertEquals("12", resolved.logicalText());
        assertEquals(1, resolved.sourceLength());
        var graphic = new InlineGraphic(10, 10, 0, new TextOutline("mark", List.of(), -1));
        var graphics = new ParagraphLayouter().layout(spans, 300, style, images, Map.of(), Map.of(3, graphic));
        assertTrue(graphics.lines().getFirst().glyphRunBoxes().isEmpty());
        assertEquals("A\ufffcBmarkC", graphics.lines().getFirst().outlines().stream().map(TextOutline::logicalText).collect(java.util.stream.Collectors.joining()));
        assertEquals(1, graphic.ink().sourceLength());
        assertEquals(5, graphics.lines().getFirst().outlines().stream().mapToInt(TextOutline::sourceLength).sum());
    }
    @Test void adjacentObjectsWrapAtomicallyAndSurviveBaselineMovesWithSourceIndices() throws IOException {
        var group = fonts(false); var image = image(30, 20, 0);
        String source = "\ufffc\ufffc\ufffc\r\n\ufffc";
        var layout = new ParagraphLayouter().layout(List.of(new StyledSpan(source, group, 12, 0, 0, false, false)),
                65, new ParagraphStyle(12, 40, 0, 0, TextAlignment.LEFT), Map.of(0, image, 1, image, 2, image, 5, image));
        assertEquals(3, layout.lines().size());
        assertEquals(List.of(0, 1, 2, 5), layout.lines().stream().flatMap(l -> l.images().stream()).map(ImageBox::sourceIndex).toList());
        assertEquals(2, layout.lines().getFirst().images().size());
        assertEquals(30, layout.lines().getFirst().images().get(1).x(), 1e-6);
        for (var line : layout.lines()) {
            assertEquals(line.images(), line.atBaseline(200).images());
            assertTrue(line.width() <= 65);
            assertTrue(line.images().stream().allMatch(b -> b.x() + b.image().width() <= line.width() + 1e-5));
        }
        assertTrue(layout.diagnostics().isEmpty());
    }
    @Test void bidiPlacesObjectsWithinMeasuredLineAndPreservesLogicalText() throws IOException {
        var group = fonts(false); String text = "العربية \ufffc لا \ufffc";
        var result = new ParagraphLayouter().layout(List.of(new StyledSpan(text, group, 14, 0, 0, false, false)),
                300, new ParagraphStyle(14, 45, 0, 0, TextAlignment.RIGHT), Map.of(text.indexOf('\ufffc'), image(22, 24, -2), text.lastIndexOf('\ufffc'), image(16, 20, 3)));
        var line = result.lines().getFirst();
        assertEquals(text, line.outlines().stream().map(TextOutline::logicalText).collect(java.util.stream.Collectors.joining()));
        assertTrue(line.images().getFirst().x() > line.images().getLast().x());
        assertTrue(line.images().stream().allMatch(b -> b.x() + b.image().width() <= line.width() + 1e-5));
        assertEquals(300, line.offsetX() + line.width(), 1e-6);
    }
    @Test void imagesBypassMissingGlyphReplacementButStillEnforceFixedHeightAndWidth() throws IOException {
        var group = fonts(true); var small = image(12, 15, 0); var tall = image(12, 80, 0);
        var spans = List.of(new StyledSpan("\ufffc", group, 12, 0, 0, false, false));
        var style = new ParagraphStyle(12, 35, 0, 0, TextAlignment.LEFT);
        assertTrue(new ParagraphLayouter().layout(spans, 100, style, Map.of(0, small)).diagnostics().isEmpty());
        assertEquals(TextLayoutException.Code.LINE_HEIGHT_TOO_SMALL, assertThrows(TextLayoutException.class,
                () -> new ParagraphLayouter().layout(spans, 100, style, Map.of(0, tall))).code());
        assertEquals(TextLayoutException.Code.GLYPH_TOO_WIDE, assertThrows(TextLayoutException.class,
                () -> new ParagraphLayouter().layout(spans, 5, style, Map.of(0, small))).code());
        assertThrows(IllegalArgumentException.class, () -> new ParagraphLayouter(new SimpleTextShaper(), TextLayoutLimits.DEFAULT)
                .layout(spans, 100, style, Map.of(0, small)));
    }
}
