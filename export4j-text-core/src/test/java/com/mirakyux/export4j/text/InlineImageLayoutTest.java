// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineImage;

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
