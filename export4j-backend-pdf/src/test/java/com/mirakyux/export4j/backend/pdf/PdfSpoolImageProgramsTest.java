// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbImage;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;

class PdfSpoolImageProgramsTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void hitsRebuildIndependentStreamsAndPreserveEveryEncodingPropertyAndPixel(boolean alpha) throws IOException {
        var expected = raster(64, 64, alpha, 17);
        try (var programs = new PdfImagePrograms(); var originalDocument = new PDDocument();
                var firstDocument = new PDDocument(); var secondDocument = new PDDocument()) {
            var original = LosslessFactory.createFromImage(originalDocument, expected);
            assertNotNull(original.getCOSObject().getDictionaryObject(COSName.DECODE_PARMS));
            programs.remember("image", original);
            var first = programs.create(firstDocument, "image");
            var second = programs.create(secondDocument, "image");
            assertNotNull(first); assertNotNull(second);
            assertNotSame(first, second);
            assertEncoding(original.getCOSObject(), first.getCOSObject());
            assertEncoding(original.getCOSObject(), second.getCOSObject());
            assertRaster(expected, first.getImage());
            assertRaster(expected, second.getImage());
        } finally { expected.flush(); }
    }

    @Test
    void cachedProgramsOutliveTheirSourceDocumentWithoutRetainingItsResources() throws IOException {
        var expected = raster(16, 16, true, 23);
        try (var programs = new PdfImagePrograms()) {
            try (var source = new PDDocument()) {
                programs.remember("image", LosslessFactory.createFromImage(source, expected));
            }
            try (var current = new PDDocument()) {
                var rebuilt = programs.create(current, "image");
                assertNotNull(rebuilt);
                assertRaster(expected, rebuilt.getImage());
            }
        } finally { expected.flush(); }
    }

    @Test
    void sourceIdentityKeepsEqualSampleBytesWithDifferentDimensionsAndAlphaSeparate() throws IOException {
        byte[] rgb = {(byte) 255, 0, 0, 0, (byte) 255, 0};
        var wide = new RgbImage(2, 1, rgb);
        var tall = new RgbImage(1, 2, rgb);
        var alpha = new RgbImage(2, 1, rgb, new byte[] {64, (byte) 192});
        try (var programs = new PdfImagePrograms()) {
            for (var source : List.of(wide, tall, alpha, wide, alpha, tall)) {
                var actual = render(programs, source);
                try {
                    assertEquals(source.width(), actual.getWidth());
                    assertEquals(source.height(), actual.getHeight());
                    assertEquals(source == alpha ? 0x40ff0000 : 0xffff0000, actual.getRGB(0, 0));
                    assertEquals(source == alpha ? 0xc000ff00 : 0xff00ff00,
                            actual.getRGB(source.width() - 1, source.height() - 1));
                } finally { actual.flush(); }
            }
        }
    }

    @Test
    void entryBoundEvictsTheLeastRecentlyUsedProgram() throws IOException {
        var source = raster(8, 8, false, 29);
        try (var pdf = new PDDocument(); var programs = new PdfImagePrograms(2, 32L * 1024 * 1024)) {
            var image = LosslessFactory.createFromImage(pdf, source);
            programs.remember("first", image); programs.remember("second", image);
            assertNotNull(programs.create(pdf, "first"));
            programs.remember("third", image);
            assertNull(programs.create(pdf, "second"));
            assertNotNull(programs.create(pdf, "first"));
            assertNotNull(programs.create(pdf, "third"));
        } finally { source.flush(); }
    }

    @Test
    void defaultEntryBoundRetainsOnlyTheLatest64Programs() throws IOException {
        var source = raster(1, 1, false, 30);
        try (var pdf = new PDDocument(); var programs = new PdfImagePrograms()) {
            var image = LosslessFactory.createFromImage(pdf, source);
            for (int entry = 0; entry < 65; entry++) { programs.remember("image-" + entry, image); }
            assertNull(programs.create(pdf, "image-0"));
            for (int entry = 1; entry < 65; entry++) { assertNotNull(programs.create(pdf, "image-" + entry)); }
        } finally { source.flush(); }
    }

    @Test
    void byteBoundIncludesTheSoftMaskAndAdmitsTheExactBoundary() throws IOException {
        var source = raster(16, 16, true, 31);
        try (var pdf = new PDDocument()) {
            var image = LosslessFactory.createFromImage(pdf, source);
            var mask = (COSStream) image.getCOSObject().getDictionaryObject(COSName.SMASK);
            long total = image.getCOSObject().getLength() + mask.getLength();
            try (var exact = new PdfImagePrograms(64, total); var below = new PdfImagePrograms(64, total - 1);
                    var mainOnly = new PdfImagePrograms(64, image.getCOSObject().getLength())) {
                exact.remember("first", image);
                assertNotNull(exact.create(pdf, "first"));
                exact.remember("second", image);
                assertNull(exact.create(pdf, "first"), "byte capacity must evict even while entry capacity remains");
                assertNotNull(exact.create(pdf, "second"));
                below.remember("image", image); mainOnly.remember("image", image);
                assertNull(below.create(pdf, "image"));
                assertNull(mainOnly.create(pdf, "image"));
            }
        } finally { source.flush(); }
    }

    @Test
    void oversizedProgramsUseTheOriginalRendererOnEveryPage() throws IOException {
        var image = new RgbImage(1, 1, new byte[] {7, 31, (byte) 193});
        try (var programs = new PdfImagePrograms(64, 1)) {
            for (int page = 0; page < 2; page++) {
                var rendered = render(programs, image);
                try { assertEquals(0xff071fc1, rendered.getRGB(0, 0)); }
                finally { rendered.flush(); }
                try (var pdf = new PDDocument()) { assertNull(programs.create(pdf, image.id())); }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"image", "mask", "predictor"})
    void unknownPropertiesRejectTheWholeProgram(String location) throws IOException {
        var source = raster(64, 64, true, 37);
        try (var pdf = new PDDocument(); var programs = new PdfImagePrograms()) {
            var image = LosslessFactory.createFromImage(pdf, source);
            COSDictionary changed = switch (location) {
                case "image" -> image.getCOSObject();
                case "mask" -> (COSStream) image.getCOSObject().getDictionaryObject(COSName.SMASK);
                default -> (COSDictionary) image.getCOSObject().getDictionaryObject(COSName.DECODE_PARMS);
            };
            changed.setInt(COSName.getPDFName("UnknownImageProperty"), 1);
            programs.remember("image", image);
            assertNull(programs.create(pdf, "image"));
            assertEquals(1, changed.getInt(COSName.getPDFName("UnknownImageProperty")),
                    "declining reuse must not normalize or strip the original resource");
        } finally { source.flush(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Predictor", "Columns", "Colors", "BitsPerComponent"})
    void unsupportedPredictorValuesAreNeverReplacedWithDefaults(String parameter) throws IOException {
        var source = raster(64, 64, false, 39);
        try (var pdf = new PDDocument(); var programs = new PdfImagePrograms()) {
            var image = LosslessFactory.createFromImage(pdf, source);
            var parameters = (COSDictionary) image.getCOSObject().getDictionaryObject(COSName.DECODE_PARMS);
            parameters.setInt(COSName.getPDFName(parameter), 0);
            programs.remember("image", image);
            assertNull(programs.create(pdf, "image"));
        } finally { source.flush(); }
    }

    @Test
    void mismatchedAndNestedSoftMasksRejectTheWholeProgram() throws IOException {
        var source = raster(8, 8, true, 40);
        try (var pdf = new PDDocument(); var programs = new PdfImagePrograms()) {
            var image = LosslessFactory.createFromImage(pdf, source);
            var mask = (COSStream) image.getCOSObject().getDictionaryObject(COSName.SMASK);
            mask.setInt(COSName.WIDTH, 7);
            programs.remember("mismatched", image);
            assertNull(programs.create(pdf, "mismatched"));
            mask.setInt(COSName.WIDTH, 8);
            mask.setItem(COSName.SMASK, image);
            programs.remember("nested", image);
            assertNull(programs.create(pdf, "nested"));
        } finally { source.flush(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Decode", "ColorSpace", "Filter", "BitsPerComponent"})
    void otherColorAndEncodingStructuresStayOnTheOrdinaryPath(String property) throws IOException {
        var source = raster(8, 8, false, 41);
        try (var pdf = new PDDocument(); var programs = new PdfImagePrograms()) {
            var image = LosslessFactory.createFromImage(pdf, source);
            switch (property) {
                case "Decode" -> image.getCOSObject().setItem(COSName.DECODE, new org.apache.pdfbox.cos.COSArray());
                case "ColorSpace" -> image.getCOSObject().setItem(COSName.COLORSPACE, COSName.DEVICECMYK);
                case "Filter" -> image.getCOSObject().setItem(COSName.FILTER, COSName.DCT_DECODE);
                default -> image.getCOSObject().setInt(COSName.BITS_PER_COMPONENT, 16);
            }
            programs.remember("image", image);
            assertNull(programs.create(pdf, "image"));
        } finally { source.flush(); }
    }

    @Test
    void failedSnapshotNeverCreatesAPartialEntry() throws IOException {
        var source = raster(8, 8, true, 43);
        try (var pdf = new PDDocument(); var programs = new PdfImagePrograms()) {
            var image = LosslessFactory.createFromImage(pdf, source);
            image.getCOSObject().close();
            assertThrows(IOException.class, () -> programs.remember("image", image));
            assertNull(programs.create(pdf, "image"));
        } finally { source.flush(); }
    }

    @Test
    void cacheHitsAndCaptureObserveCancellationAndLeaveCompleteEntriesUsable() throws IOException {
        var source = raster(8, 8, true, 47);
        var cancelled = new AtomicBoolean();
        try (var pdf = new PDDocument(); var programs = new PdfImagePrograms()) {
            var image = LosslessFactory.createFromImage(pdf, source);
            programs.remember("existing", image);
            ExecutionContext.execute(ExecutionOptions.builder().cancelled(cancelled::get).build(), () -> {
                cancelled.set(true);
                try {
                    assertThrows(ExecutionException.class, () -> programs.create(pdf, "existing"));
                    assertThrows(ExecutionException.class, () -> programs.remember("new", image));
                } finally { cancelled.set(false); }
                return null;
            });
            assertNull(programs.create(pdf, "new"));
            assertNotNull(programs.create(pdf, "existing"));
        } finally { source.flush(); }
    }

    @Test
    void cacheHitsStillChargeEveryImageOccurrenceToThePageBudget() throws IOException {
        var image = new RgbImage(1, 1, new byte[] {3, 5, 7});
        try (var programs = new PdfImagePrograms()) {
            render(programs, image).flush();
            var commands = List.<DrawCommand>of(new DrawCommand.Image(image, new Rect(10, 10, 1, 1)),
                    new DrawCommand.Image(image, new Rect(20, 20, 1, 1)));
            var document = new DisplayDocument(DocumentMetadata.EMPTY,
                    List.of(new DisplayPage(new PageSize(100, 100), commands)));
            var backend = new PdfBackend(PdfLimits.DEFAULT.toBuilder().maxImagePixels(1).build());
            var failure = assertThrows(PdfRenderException.class,
                    () -> backend.write(document, new ByteArrayOutputStream(), ProcessingPolicy.STRICT, null, programs));
            assertEquals(PdfErrorCode.BUDGET_EXCEEDED, failure.code());
        }
    }

    @Test
    void failedSpoolSessionIsTerminalAndCleansTemporaryFilesBeforeAnotherSession() throws IOException {
        var fail = new AtomicBoolean();
        var target = new OutputStream() {
            @Override public void write(int value) throws IOException { if (fail.get()) { throw new IOException("expected output failure"); } }
            @Override public void write(byte[] bytes, int offset, int length) throws IOException { if (fail.get()) { throw new IOException("expected output failure"); } }
        };
        var image = new RgbImage(1, 1, new byte[] {11, 13, 17}, new byte[] {(byte) 193});
        try (var writer = new PdfSpoolBackend(directory).open(DocumentMetadata.EMPTY, target)) {
            writer.accept(page(image));
            fail.set(true);
            assertThrows(IOException.class, () -> writer.accept(page(image)));
            assertThrows(IllegalStateException.class, writer::finish);
        }
        try (var paths = Files.list(directory)) { assertEquals(0, paths.count()); }
        try (var writer = new PdfSpoolBackend(directory).open(DocumentMetadata.EMPTY, new ByteArrayOutputStream())) {
            writer.accept(page(image)); writer.finish();
        }
        try (var paths = Files.list(directory)) { assertEquals(0, paths.count()); }
    }

    @Test
    void closingClearsOwnedProgramsAndRejectsFurtherUse() throws IOException {
        var source = raster(8, 8, false, 53);
        var programs = new PdfImagePrograms();
        try (var pdf = new PDDocument()) {
            var image = LosslessFactory.createFromImage(pdf, source);
            try { programs.remember("image", image); }
            finally { programs.close(); }
            programs.close();
            assertThrows(IllegalStateException.class, () -> programs.create(pdf, "image"));
            assertThrows(IllegalStateException.class, () -> programs.remember("image", image));
        } finally { source.flush(); }
    }

    private static BufferedImage render(PdfImagePrograms programs, RgbImage source) throws IOException {
        var output = new ByteArrayOutputStream();
        new PdfBackend().write(new DisplayDocument(DocumentMetadata.EMPTY, List.of(page(source))), output,
                ProcessingPolicy.STRICT, null, programs);
        try (var pdf = Loader.loadPDF(output.toByteArray())) {
            var resources = pdf.getPage(0).getResources();
            var image = (PDImageXObject) resources.getXObject(resources.getXObjectNames().iterator().next());
            return image.getImage();
        }
    }

    private static DisplayPage page(RgbImage image) {
        return new DisplayPage(new PageSize(100, 100),
                List.of(new DrawCommand.Image(image, new Rect(10, 10, image.width(), image.height()))));
    }

    private static BufferedImage raster(int width, int height, boolean alpha, int seed) {
        var image = new BufferedImage(width, height, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        var random = new Random(seed);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, (alpha ? 32 + random.nextInt(224) : 255) << 24 | random.nextInt(1 << 24));
            }
        }
        return image;
    }

    private static void assertRaster(BufferedImage expected, BufferedImage actual) {
        assertEquals(expected.getWidth(), actual.getWidth());
        assertEquals(expected.getHeight(), actual.getHeight());
        assertArrayEquals(expected.getRGB(0, 0, expected.getWidth(), expected.getHeight(), null, 0, expected.getWidth()),
                actual.getRGB(0, 0, actual.getWidth(), actual.getHeight(), null, 0, actual.getWidth()));
    }

    private static void assertEncoding(COSStream expected, COSStream actual) throws IOException {
        assertNotSame(expected, actual);
        assertEquals(expected.keySet(), actual.keySet());
        try (var expectedBytes = expected.createRawInputStream(); var actualBytes = actual.createRawInputStream()) {
            assertArrayEquals(expectedBytes.readAllBytes(), actualBytes.readAllBytes());
        }
        for (var key : expected.keySet()) {
            if (key.equals(COSName.SMASK)) {
                assertEncoding((COSStream) expected.getDictionaryObject(key), (COSStream) actual.getDictionaryObject(key));
            } else if (key.equals(COSName.DECODE_PARMS)) {
                var original = (COSDictionary) expected.getDictionaryObject(key);
                var copied = (COSDictionary) actual.getDictionaryObject(key);
                assertNotSame(original, copied);
                assertEquals(original.keySet(), copied.keySet());
                for (var parameter : original.keySet()) {
                    assertEquals(original.getDictionaryObject(parameter), copied.getDictionaryObject(parameter));
                }
            } else { assertEquals(expected.getDictionaryObject(key), actual.getDictionaryObject(key)); }
        }
    }
}
