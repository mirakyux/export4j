// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.RgbImage;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.text.GlyphRun;
import com.mirakyux.export4j.text.SimpleTextShaper;

class PdfSpoolImageReuseTest {
    @TempDir Path directory;

    @Test
    void sharesCompleteImageGraphsButNeverConfusesDifferentAlpha() throws IOException {
        byte[] rgb = {(byte) 255, 0, 0};
        var output = new ByteArrayOutputStream();
        try (var writer = new PdfSpoolBackend(directory).open(DocumentMetadata.EMPTY, output)) {
            writer.accept(page(new RgbImage(1, 1, rgb)));
            writer.accept(page(new RgbImage(1, 1, rgb.clone())));
            writer.accept(page(new RgbImage(1, 1, rgb, new byte[]{0})));
            writer.accept(page(new RgbImage(1, 1, rgb, new byte[]{0})));
            writer.finish();
        }
        try (var pdf = Loader.loadPDF(output.toByteArray())) {
            assertSame(image(pdf, 0).getCOSObject(), image(pdf, 1).getCOSObject());
            assertNotSame(image(pdf, 0).getCOSObject(), image(pdf, 2).getCOSObject());
            assertSame(image(pdf, 2).getCOSObject(), image(pdf, 3).getCOSObject());
            assertEquals(0, image(pdf, 2).getImage().getRGB(0, 0) >>> 24);
        }
        assertClean();
    }

    @Test
    void keepsOriginalJpegBytesAndSharesTheirWrittenObjectAcrossPages() throws IOException {
        byte[] encoded = PdfJpegSourceTest.jpeg(false, false);
        var output = new ByteArrayOutputStream();
        try (var writer = new PdfSpoolBackend(directory).open(DocumentMetadata.EMPTY, output)) {
            writer.accept(page(RgbImage.from(ImageResource.load(encoded))));
            writer.accept(page(RgbImage.from(ImageResource.load(encoded.clone()))));
            writer.finish();
        }
        try (var pdf = Loader.loadPDF(output.toByteArray())) {
            var first = image(pdf, 0);
            assertSame(first.getCOSObject(), image(pdf, 1).getCOSObject());
            assertEquals(COSName.DCT_DECODE, first.getCOSObject().getDictionaryObject(COSName.FILTER));
            try (var raw = first.getCOSObject().createRawInputStream()) { assertArrayEquals(encoded, raw.readAllBytes()); }
        }
        assertClean();
    }

    @Test
    void imageHitsStillChargeTheEntireSourceGraphIncludingTheMask() throws IOException {
        var picture = new RgbImage(1, 1, new byte[]{(byte) 255, 0, 0}, new byte[]{0});
        try (var input = PdfSpoolImageReuseTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            var face = TrueTypeFontLoader.load(input);
            var glyphs = new SimpleTextShaper().shape("AB", List.of(face), 18).glyphs().stream().map(g -> g.glyph()).toList();
            var textAndImage = new DisplayListBuilder(new PageSize(200, 100))
                    .drawGlyphRun(new GlyphRun(face.data(), 18, glyphs), new Point(20, 50), RgbColor.BLACK)
                    .drawImage(picture, new Rect(100, 20, 10, 10)).build();
            var defaults = PdfSpoolLimits.DEFAULT;
            // Existing font-only fixture fits this bound; the image's complete graph makes this page exceed it.
            var limits = new PdfSpoolLimits(defaults.maxPages(), defaults.maxCommands(), 19, defaults.maxObjects(),
                    defaults.maxPageBytes(), defaults.maxTemporaryBytes(), defaults.maxOutputBytes());
            try (var writer = new PdfSpoolBackend(directory, PdfLimits.DEFAULT, limits)
                    .open(DocumentMetadata.EMPTY, new ByteArrayOutputStream())) {
                writer.accept(page(picture));
                assertThrows(IOException.class, () -> writer.accept(textAndImage));
                assertThrows(IllegalStateException.class, writer::finish);
            }
        }
        assertClean();
    }

    @Test
    void imageHistoryEvictsOldGraphsAndKeepsRecentReuse() throws IOException {
        var output = new ByteArrayOutputStream();
        try (var writer = new PdfSpoolBackend(directory).open(DocumentMetadata.EMPTY, output)) {
            for (int value = 0; value < 257; value++) {
                writer.accept(page(new RgbImage(1, 1, new byte[]{(byte) (value >>> 8), (byte) value, 0})));
            }
            writer.accept(page(new RgbImage(1, 1, new byte[]{1, 0, 0})));
            writer.accept(page(new RgbImage(1, 1, new byte[3])));
            writer.finish();
        }
        try (var pdf = Loader.loadPDF(output.toByteArray())) {
            assertSame(image(pdf, 256).getCOSObject(), image(pdf, 257).getCOSObject());
            assertNotSame(image(pdf, 0).getCOSObject(), image(pdf, 258).getCOSObject());
        }
        assertClean();
    }

    private static DisplayPage page(RgbImage image) {
        return new DisplayListBuilder(new PageSize(200, 100)).drawImage(image, new Rect(20, 20, 40, 40)).build();
    }

    private static PDImageXObject image(PDDocument pdf, int page) throws IOException {
        var resources = pdf.getPage(page).getResources();
        return (PDImageXObject) resources.getXObject(resources.getXObjectNames().iterator().next());
    }

    private void assertClean() throws IOException {
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }
}
