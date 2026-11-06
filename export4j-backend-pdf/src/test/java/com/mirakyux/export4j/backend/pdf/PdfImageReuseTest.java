// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.mirakyux.export4j.graphics.AffineTransform;
import com.mirakyux.export4j.graphics.BlendMode;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbImage;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSize;

class PdfImageReuseTest {
    private static final PageSize SIZE = new PageSize(120, 100);

    @ParameterizedTest
    @EnumSource(PdfOptions.Storage.class)
    void sharesImageAcrossPagesTransformsAndGroupsWithoutLosingAlpha(PdfOptions.Storage storage) throws IOException {
        var source = new RgbImage(1, 2, new byte[]{(byte) 255, 0, 0, 0, 0, (byte) 255}, new byte[]{(byte) 255, 0});
        var first = new DisplayListBuilder(SIZE).drawImage(source, new Rect(20, 20, 20, 40))
                .save().transform(new AffineTransform(1, 0, 0, 1, 40, 0))
                .drawImage(source, new Rect(20, 20, 20, 40)).restore().build();
        var second = new DisplayListBuilder(SIZE).group(new Rect(0, 0, 120, 100),
                List.of(new DrawCommand.Image(source, new Rect(20, 20, 20, 40))), 1, BlendMode.NORMAL).build();
        for (boolean session : List.of(false, true)) {
            try (var pdf = Loader.loadPDF(write(List.of(first, second), storage, session))) {
                var direct = imageObject(pdf.getPage(0).getResources());
                var objects = pdf.getPage(1).getResources();
                var group = (PDFormXObject) objects.getXObject(objects.getXObjectNames().iterator().next());
                assertSame(direct, imageObject(group.getResources()));
                var renderer = new PDFRenderer(pdf);
                for (int page = 0; page < 2; page++) {
                    var rendered = renderer.renderImageWithDPI(page, 72);
                    assertEquals(0xff0000, rendered.getRGB(30, 25) & 0xffffff);
                    assertEquals(0xffffff, rendered.getRGB(30, 55) & 0xffffff);
                    if (page == 0) {
                        assertEquals(0xff0000, rendered.getRGB(70, 25) & 0xffffff);
                        assertEquals(0xffffff, rendered.getRGB(70, 55) & 0xffffff);
                    }
                }
            }
        }
    }

    @Test
    void cachedDrawsStillConsumeTheCumulativePixelBudget() throws IOException {
        var source = new RgbImage(1, 2, new byte[6]);
        var page = page(source);
        var backend = new PdfBackend(PdfLimits.DEFAULT.toBuilder().maxImagePixels(3).build());
        var output = new ByteArrayOutputStream();
        assertEquals(PdfErrorCode.BUDGET_EXCEEDED, assertThrows(PdfRenderException.class,
                () -> backend.write(new DisplayDocument(DocumentMetadata.EMPTY, List.of(page, page)), output)).code());
        assertEquals(0, output.size());
        try (var writer = backend.open(DocumentMetadata.EMPTY, output)) {
            writer.accept(page);
            assertEquals(PdfErrorCode.BUDGET_EXCEEDED,
                    assertThrows(PdfRenderException.class, () -> writer.accept(page)).code());
            assertThrows(IllegalStateException.class, writer::finish);
        }
        assertEquals(0, output.size());
    }

    @ParameterizedTest
    @EnumSource(PdfOptions.Storage.class)
    void boundsTheContentIndexWhileKeepingRecentReuse(PdfOptions.Storage storage) throws IOException {
        var pages = new ArrayList<DisplayPage>();
        for (int i = 0; i < 257; i++) { pages.add(page(new RgbImage(1, 1, new byte[]{(byte) (i >>> 8), (byte) i, 0}))); }
        pages.add(pages.getLast());
        pages.add(pages.getFirst());
        try (var pdf = Loader.loadPDF(write(pages, storage, true))) {
            assertSame(imageObject(pdf.getPage(256).getResources()), imageObject(pdf.getPage(257).getResources()));
            assertNotSame(imageObject(pdf.getPage(0).getResources()), imageObject(pdf.getPage(258).getResources()),
                    "the content index must evict old keys after 256 distinct resources");
        }
    }

    @Test
    void keepsTwentyFourLargeImagesReusableWithoutRetainingSourcePixels() throws IOException {
        var pages = new ArrayList<DisplayPage>();
        for (int i = 0; i < 24; i++) {
            var pixels = new byte[500 * 400 * 3]; Arrays.fill(pixels, (byte) i);
            pages.add(page(new RgbImage(500, 400, pixels)));
        }
        pages.addAll(List.copyOf(pages));
        try (var pdf = Loader.loadPDF(write(pages, PdfOptions.Storage.TEMP_FILE, true))) {
            for (int i = 0; i < 24; i++) {
                assertSame(imageObject(pdf.getPage(i).getResources()), imageObject(pdf.getPage(i + 24).getResources()));
            }
        }
    }

    @Test
    void contentIdentitySharesDifferentInstancesButPreservesDifferentAlpha() throws IOException {
        byte[] rgb = {(byte) 255, 0, 0};
        var opaque = page(new RgbImage(1, 1, rgb));
        var samePixels = page(new RgbImage(1, 1, rgb.clone()));
        var transparent = page(new RgbImage(1, 1, rgb, new byte[]{0}));
        for (boolean session : List.of(false, true)) {
            try (var pdf = Loader.loadPDF(write(List.of(opaque, samePixels, transparent), PdfOptions.Storage.TEMP_FILE, session))) {
                assertSame(imageObject(pdf.getPage(0).getResources()), imageObject(pdf.getPage(1).getResources()));
                assertNotSame(imageObject(pdf.getPage(0).getResources()), imageObject(pdf.getPage(2).getResources()));
                var renderer = new PDFRenderer(pdf);
                assertEquals(0xff0000, renderer.renderImageWithDPI(0, 72).getRGB(25, 25) & 0xffffff);
                assertEquals(0xffffff, renderer.renderImageWithDPI(2, 72).getRGB(25, 25) & 0xffffff);
            }
        }
    }

    private static DisplayPage page(RgbImage image) {
        return new DisplayListBuilder(SIZE).drawImage(image, new Rect(20, 20, 20, 40)).build();
    }

    private static byte[] write(List<DisplayPage> pages, PdfOptions.Storage storage, boolean session) throws IOException {
        var output = new ByteArrayOutputStream();
        var backend = new PdfBackend(PdfLimits.DEFAULT, PdfOptions.storage(storage));
        if (session) {
            try (var writer = backend.open(DocumentMetadata.EMPTY, output)) {
                for (var page : pages) { writer.accept(page); }
                writer.finish();
            }
        } else { backend.write(new DisplayDocument(DocumentMetadata.EMPTY, pages), output); }
        return output.toByteArray();
    }

    private static COSBase imageObject(PDResources resources) throws IOException {
        var images = new ArrayList<COSBase>();
        for (var name : resources.getXObjectNames()) {
            if (resources.getXObject(name) instanceof PDImageXObject image) { images.add(image.getCOSObject()); }
        }
        assertEquals(1, images.size());
        return images.getFirst();
    }
}
