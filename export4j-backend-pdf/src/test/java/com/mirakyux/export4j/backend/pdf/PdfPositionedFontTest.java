// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.text.GlyphRun;
import com.mirakyux.export4j.text.SimpleTextShaper;

class PdfPositionedFontTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesMeasuredGlyphsEvenWhenTheFontDeclaresGsub(boolean session) throws IOException {
        try (var input = getClass().getResourceAsStream("/fonts/PositionedTest.ttf")) {
            var face = TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES);
            var glyphs = new SimpleTextShaper().shape("AA", List.of(face), 100).glyphs().stream()
                    .map(glyph -> glyph.glyph()).toList();
            assertEquals(2, glyphs.getFirst().glyphId());
            assertEquals(60, glyphs.getFirst().advance());
            var page = new DisplayListBuilder(new PageSize(200, 200))
                    .drawGlyphRun(new GlyphRun(face.data(), 100, glyphs), new Point(20, 100), RgbColor.BLACK).build();
            var output = new ByteArrayOutputStream();
            var backend = new PdfBackend();
            if (session) {
                try (var writer = backend.open(DocumentMetadata.EMPTY, output)) {
                    writer.accept(page); writer.finish();
                }
            } else {
                backend.write(new DisplayDocument(DocumentMetadata.EMPTY, List.of(page)), output);
            }
            try (var pdf = Loader.loadPDF(output.toByteArray())) {
                assertEquals("AA", new PDFTextStripper().getText(pdf).strip());
                var image = new PDFRenderer(pdf).renderImageWithDPI(0, 72);
                // A is 40pt wide; B is 70pt. Measured starts remain 20pt and 80pt.
                assertEquals(0, image.getRGB(30, 50) & 0xffffff);
                assertEquals(0xffffff, image.getRGB(70, 50) & 0xffffff);
                assertEquals(0, image.getRGB(90, 50) & 0xffffff);
                assertEquals(0xffffff, image.getRGB(130, 50) & 0xffffff);
            }
        }
    }
}
