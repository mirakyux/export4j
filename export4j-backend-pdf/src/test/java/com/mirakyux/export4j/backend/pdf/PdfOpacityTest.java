// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.AccessibilityTree;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DocumentExtras;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSize;

class PdfOpacityTest {
    @Test void nonStrokingAndStrokingAlphaAreExplicitResourcesAndSaveRestoreRestoresVisibility() throws Exception {
        var red = new RgbColor(1, 0, 0);
        var page = new DisplayListBuilder(new PageSize(60, 30))
                .opacity(0.5, 0.25).fillRect(new Rect(0, 0, 10, 10), red)
                .save().opacity(0, 1).fillRect(new Rect(10, 0, 10, 10), red).restore()
                .fillRect(new Rect(20, 0, 10, 10), red).opacity(1, 1).fillRect(new Rect(30, 0, 10, 10), red).build();
        var bytes = new ByteArrayOutputStream(); new PdfBackend().write(new DisplayDocument(DocumentMetadata.EMPTY, List.of(page)), bytes);
        try (var pdf = Loader.loadPDF(bytes.toByteArray())) {
            var states = pdf.getPage(0).getResources();
            boolean found = false;
            for (var name : states.getExtGStateNames()) {
                var alpha = states.getExtGState(name);
                if (Float.valueOf(0.5f).equals(alpha.getNonStrokingAlphaConstant()) && Float.valueOf(0.25f).equals(alpha.getStrokingAlphaConstant())) { found = true; }
            }
            assertTrue(found, "independent fill and stroke alpha are absent from resources");
            var image = new PDFRenderer(pdf).renderImage(0);
            assertHalfTransparentRed(image.getRGB(5, 5));
            assertEquals(0xffffff, image.getRGB(15, 5) & 0xffffff);
            assertHalfTransparentRed(image.getRGB(25, 5));
            assertEquals(0xff0000, image.getRGB(35, 5) & 0xffffff);
        }
    }

    @Test void forbiddenArchivalTransparencyFailsBeforeWritingAnyBytes() throws Exception {
        com.mirakyux.export4j.font.FontData font;
        try (var input = getClass().getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            font = TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES).data();
        }
        var profile = java.awt.color.ICC_Profile.getInstance(java.awt.color.ColorSpace.CS_sRGB).getData();
        var archival = new PdfArchival(PdfArchival.Profile.A1B, profile, font);
        var backend = new PdfBackend(PdfLimits.DEFAULT, PdfOptions.DEFAULT.withArchival(archival));
        var page = new DisplayListBuilder(new PageSize(40, 40)).opacity(1, 0.8).fillRect(new Rect(0, 0, 10, 10), RgbColor.BLACK).build();
        var output = new ByteArrayOutputStream();
        assertThrows(PdfRenderException.class, () -> backend.write(new DisplayDocument(DocumentMetadata.EMPTY, List.of(page)), output));
        assertEquals(0, output.size());
    }

    @Test void alphaStateRequiresNoAccessibilityPaintBinding() {
        var page = new DisplayListBuilder(new PageSize(40, 40)).opacity(0.8, 1).fillRect(new Rect(0, 0, 10, 10), RgbColor.BLACK).build();
        var figure = new AccessibilityTree.Node("box", AccessibilityTree.Role.FIGURE, "Box", "", AccessibilityTree.Cell.NONE,
                List.of(new AccessibilityTree.CommandRange(0, 1, 2)), List.of());
        var display = new DisplayDocument(new DocumentMetadata("Opacity", ""), List.of(page), DocumentExtras.EMPTY,
                new AccessibilityTree("en-US", List.of(figure), List.of()));
        assertEquals(2, display.pages().getFirst().commands().size());
    }

    private static void assertHalfTransparentRed(int pixel) {
        assertEquals(255, pixel >> 16 & 255);
        // 0.5 source-over yields 127.5; 8-bit alpha/color rounding permits either 127 or 128.
        assertTrue(Math.abs((pixel >> 8 & 255) - 127.5) <= 0.5);
        assertTrue(Math.abs((pixel & 255) - 127.5) <= 0.5);
    }

    @Test void deeplyNestedStyleOnlySvgDoesNotSpendBackendGraphicsStateDepth() throws Exception {
        com.mirakyux.export4j.font.FontFace font;
        try (var input = getClass().getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            font = TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES);
        }
        String source = "<svg viewBox='0 0 40 40'>" + "<g fill-opacity='0.5'>".repeat(64)
                + "<rect width='10' height='10'/>" + "</g>".repeat(64) + "</svg>";
        var drawing = com.mirakyux.export4j.components.SvgComponent.parse(source).layout(
                new com.mirakyux.export4j.layout.core.ComponentContext(40, 40, List.of(font), 0, 0));
        var page = new com.mirakyux.export4j.graphics.DisplayPage(new PageSize(40, 40), drawing.commands());
        var bytes = new ByteArrayOutputStream();
        new PdfBackend().write(new DisplayDocument(DocumentMetadata.EMPTY, List.of(page)), bytes);
        assertTrue(bytes.size() > 0, "valid style nesting exhausted the default PDF graphics-state budget");
    }
}
