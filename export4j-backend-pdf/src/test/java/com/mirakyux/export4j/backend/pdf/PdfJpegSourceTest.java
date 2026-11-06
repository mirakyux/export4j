// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbImage;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.PageSize;

class PdfJpegSourceTest {
    @Test
    void sessionRecognitionRebuildsIndependentRawJpegObjectsAndKeepsNegativeDecisionsSeparate() throws IOException {
        var encoded = jpeg(false, true);
        var source = ImageResource.load(encoded);
        var recognition = new PdfJpegSource.Recognition();
        for (int index = 0; index < 3; index++) {
            try (var document = new PDDocument()) {
                var image = recognition.create(document, source);
                assertTrue(image != null);
                assertEquals(COSName.DCT_DECODE, image.getCOSObject().getCOSName(COSName.FILTER));
                try (var raw = image.getCOSObject().createRawInputStream()) { assertArrayEquals(encoded, raw.readAllBytes()); }
            }
        }
        var adobe = ImageResource.load(insert(jpeg(false, false), 0xee,
                new byte[]{'A', 'd', 'o', 'b', 'e', 0, 100, 0, 0, 0, 0, 1}));
        try (var document = new PDDocument()) {
            assertEquals(null, recognition.create(document, adobe));
            assertEquals(null, recognition.create(document, adobe));
            assertTrue(recognition.create(document, source) != null);
        }
        recognition.clear();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void embedsColorJfifWithoutReencoding(boolean progressive) throws IOException {
        byte[] encoded = jpeg(false, progressive);
        assertTrue(PdfJpegSource.supports(encoded, 32, 16));
        var resource = ImageResource.load(encoded);
        var first = new DisplayListBuilder(new PageSize(100, 100))
                .drawImage(RgbImage.from(resource), new Rect(10, 10, 64, 32)).build();
        var second = new DisplayListBuilder(new PageSize(100, 100))
                .drawImage(RgbImage.from(ImageResource.load(encoded.clone())), new Rect(10, 10, 64, 32)).build();
        var output = new ByteArrayOutputStream();
        new PdfBackend().write(new DisplayDocument(DocumentMetadata.EMPTY, List.of(first, second)), output);
        try (var pdf = Loader.loadPDF(output.toByteArray())) {
            var resources = pdf.getPage(0).getResources();
            var image = (PDImageXObject) resources.getXObject(resources.getXObjectNames().iterator().next());
            var later = pdf.getPage(1).getResources();
            assertSame(image.getCOSObject(), later.getXObject(later.getXObjectNames().iterator().next()).getCOSObject());
            assertEquals(COSName.DCT_DECODE, image.getCOSObject().getDictionaryObject(COSName.FILTER));
            assertEquals(COSName.DEVICERGB, image.getCOSObject().getDictionaryObject(COSName.COLORSPACE));
            assertNull(image.getCOSObject().getDictionaryObject(COSName.DECODE_PARMS));
            assertEquals(8, image.getBitsPerComponent());
            assertEquals(32, image.getWidth()); assertEquals(16, image.getHeight());
            try (var raw = image.getCOSObject().createRawInputStream()) { assertArrayEquals(encoded, raw.readAllBytes()); }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void directJfifPassthroughPreservesThePreviousDecoderPixels(boolean progressive) throws IOException {
        byte[] encoded = jpeg(false, progressive);
        var resource = ImageResource.load(encoded);
        var recognition = new PdfJpegSource.Recognition();
        try (var referenceDocument = new PDDocument(); var firstDocument = new PDDocument();
                var laterDocument = new PDDocument()) {
            var reference = JPEGFactory.createFromByteArray(referenceDocument, encoded);
            var first = recognition.create(firstDocument, resource);
            var later = recognition.create(laterDocument, resource);
            assertNotNull(first); assertNotNull(later);
            assertEquals(reference.getCOSObject().getDictionaryObject(COSName.COLORSPACE),
                    first.getCOSObject().getDictionaryObject(COSName.COLORSPACE));
            assertEquals(reference.getCOSObject().getDictionaryObject(COSName.DECODE_PARMS),
                    first.getCOSObject().getDictionaryObject(COSName.DECODE_PARMS));
            var expected = reference.getImage();
            try {
                assertPixels(expected, first.getImage());
                assertPixels(expected, later.getImage());
            } finally { expected.flush(); }
        } finally { recognition.clear(); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void grayscaleJfifUsesLosslessDecodedPixels(boolean progressive) throws IOException {
        byte[] encoded = jpeg(true, progressive);
        assertFalse(PdfJpegSource.supports(encoded, 32, 16));
        assertLosslessDecodedPixels(encoded);
    }

    @Test
    void rejectsIccAdobeUnknownAndUncertainHeadersForThePixelFallback() throws IOException {
        byte[] encoded = jpeg(false, false);
        assertFalse(PdfJpegSource.supports(insert(encoded, 0xe2, new byte[]{'I', 'C', 'C', '_', 'P', 'R', 'O', 'F', 'I', 'L', 'E', 0, 1, 1}), 32, 16));
        assertFalse(PdfJpegSource.supports(insert(encoded, 0xee, new byte[]{'A', 'd', 'o', 'b', 'e', 0, 100, 0, 0, 0, 0, 1}), 32, 16));
        assertFalse(PdfJpegSource.supports(insert(encoded, 0xe1, new byte[]{1, 2, 3}), 32, 16));
        assertFalse(PdfJpegSource.supports(encoded, 33, 16));
        for (int length = 0; length < encoded.length; length++) {
            assertFalse(PdfJpegSource.supports(Arrays.copyOf(encoded, length), 32, 16));
        }
        byte[] trailing = Arrays.copyOf(encoded, encoded.length + 1);
        assertFalse(PdfJpegSource.supports(trailing, 32, 16));
    }

    @Test
    void adobeResourceUsesLosslessDecodedPixelsInsteadOfDctPassthrough() throws IOException {
        byte[] encoded = insert(jpeg(false, false), 0xee, new byte[]{'A', 'd', 'o', 'b', 'e', 0, 100, 0, 0, 0, 0, 1});
        assertLosslessDecodedPixels(encoded);
    }

    private static void assertLosslessDecodedPixels(byte[] encoded) throws IOException {
        var resource = ImageResource.load(encoded);
        var source = RgbImage.from(resource);
        byte[] expected = source.pixels();
        var page = new DisplayListBuilder(new PageSize(100, 100)).drawImage(source, new Rect(10, 10, 64, 32)).build();
        var output = new ByteArrayOutputStream();
        new PdfBackend().write(new DisplayDocument(DocumentMetadata.EMPTY, List.of(page)), output);
        try (var pdf = Loader.loadPDF(output.toByteArray())) {
            var resources = pdf.getPage(0).getResources();
            var image = (PDImageXObject) resources.getXObject(resources.getXObjectNames().iterator().next());
            assertFalse(COSName.DCT_DECODE.equals(image.getCOSObject().getDictionaryObject(COSName.FILTER)));
            var pixels = image.getImage();
            try {
                for (int y = 0, at = 0; y < pixels.getHeight(); y++) {
                    for (int x = 0; x < pixels.getWidth(); x++) {
                        int rgb = (Byte.toUnsignedInt(expected[at++]) << 16) | (Byte.toUnsignedInt(expected[at++]) << 8)
                                | Byte.toUnsignedInt(expected[at++]);
                        assertEquals(rgb, pixels.getRGB(x, y) & 0xffffff);
                    }
                }
            } finally { pixels.flush(); }
        }
    }

    private static void assertPixels(BufferedImage expected, BufferedImage actual) {
        try {
            assertEquals(expected.getWidth(), actual.getWidth());
            assertEquals(expected.getHeight(), actual.getHeight());
            for (int y = 0; y < expected.getHeight(); y++) {
                for (int x = 0; x < expected.getWidth(); x++) {
                    assertEquals(expected.getRGB(x, y), actual.getRGB(x, y));
                }
            }
        } finally { actual.flush(); }
    }

    static byte[] jpeg(boolean gray, boolean progressive) throws IOException {
        var image = new BufferedImage(32, 16, gray ? BufferedImage.TYPE_BYTE_GRAY : BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int level = 32 + 191 * x / (image.getWidth() - 1);
                int grayRgb = level << 16 | level << 8 | level;
                image.setRGB(x, y, gray ? grayRgb : (x < 16 ? 0xff0000 : 0x0000ff));
            }
        }
        var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        var bytes = new ByteArrayOutputStream();
        try (var stream = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(stream);
            var parameters = writer.getDefaultWriteParam();
            parameters.setProgressiveMode(progressive ? ImageWriteParam.MODE_DEFAULT : ImageWriteParam.MODE_DISABLED);
            writer.write(null, new IIOImage(image, null, null), parameters);
        } finally { writer.dispose(); image.flush(); }
        return bytes.toByteArray();
    }

    private static byte[] insert(byte[] jpeg, int marker, byte[] payload) {
        int length = payload.length + 2;
        var bytes = new ByteArrayOutputStream();
        bytes.write(jpeg, 0, 2); bytes.write(0xff); bytes.write(marker); bytes.write(length >>> 8); bytes.write(length);
        bytes.writeBytes(payload); bytes.write(jpeg, 2, jpeg.length - 2);
        return bytes.toByteArray();
    }
}
