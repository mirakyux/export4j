// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.PageWriter;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbImage;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.PageSize;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class PdfResourceSafetyTest {
    private static final PageSize SIZE = new PageSize(96, 64);
    private static final int RED = 0xffff0000;
    private static final int BLUE = 0xff0000ff;
    @TempDir Path temporary;

    private enum Mode { COMPLETE, PAGES, SPOOL }
    private enum Budget { COMMANDS, PIXELS }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void equalSampleBytesCannotMergeDifferentDimensionsOrMasks(Mode mode) throws IOException {
        var target = new Target(mode, PdfLimits.DEFAULT, PdfSpoolLimits.DEFAULT.maxCommands());
        var wide = new RgbImage(2, 1, twoPixels());
        var tall = new RgbImage(1, 2, twoPixels());
        var maskedWide = new RgbImage(2, 1, twoPixels(), new byte[]{(byte) 255, 0});
        var maskedTall = new RgbImage(1, 2, twoPixels(), new byte[]{(byte) 255, 0});
        var output = new TrackingOutput();
        target.write(List.of(page(wide), page(tall), page(new RgbImage(2, 1, twoPixels())),
                page(maskedWide), page(maskedTall)), output);
        try (var pdf = Loader.loadPDF(output.bytes())) {
            assertEquals(5, pdf.getNumberOfPages());
            assertSame(image(pdf, 0).getCOSObject(), image(pdf, 2).getCOSObject());
            assertNotSame(image(pdf, 0).getCOSObject(), image(pdf, 1).getCOSObject());
            assertNotSame(image(pdf, 0).getCOSObject(), image(pdf, 3).getCOSObject());
            assertNotSame(image(pdf, 3).getCOSObject(), image(pdf, 4).getCOSObject());
            assertSamples(image(pdf, 0), 2, 1, RED, BLUE);
            assertSamples(image(pdf, 1), 1, 2, RED, BLUE);
            assertSamples(image(pdf, 3), 2, 1, RED, 0);
            assertSamples(image(pdf, 4), 1, 2, RED, 0);
        }
        assertFalse(output.closed);
        assertClean();
    }

    @ParameterizedTest
    @MethodSource("repeatedResourceBudgets")
    void repeatedResourcesDoNotDiscountCommandsOrPixels(Mode mode, Budget budget) throws IOException {
        var image = new RgbImage(1, 2, twoPixels());
        var pages = List.of(page(image), new DisplayListBuilder(SIZE)
                .drawImage(image, new Rect(10, 10, 20, 20))
                .drawImage(new RgbImage(1, 2, twoPixels()), new Rect(40, 10, 20, 20)).build());
        // PdfLimits is cumulative in ordinary output, but explicitly per page in spool output.
        int exactPixels = mode == Mode.SPOOL ? 4 : 6;
        var limits = budget == Budget.PIXELS
                ? PdfLimits.DEFAULT.toBuilder().maxImagePixels(3).build()
                : PdfLimits.DEFAULT.toBuilder().maxCommands(2).build();
        var target = new Target(mode, limits, budget == Budget.COMMANDS ? 2 : PdfSpoolLimits.DEFAULT.maxCommands());
        var output = new TrackingOutput();
        var failure = assertThrows(IOException.class, () -> target.write(pages, output));
        if (failure instanceof PdfRenderException pdfFailure) {
            assertEquals(PdfErrorCode.BUDGET_EXCEEDED, pdfFailure.code());
        }
        assertFalse(output.closed);
        assertClean();

        var exact = budget == Budget.PIXELS
                ? PdfLimits.DEFAULT.toBuilder().maxImagePixels(exactPixels).build()
                : PdfLimits.DEFAULT.toBuilder().maxCommands(3).build();
        var accepted = new TrackingOutput();
        new Target(mode, exact, budget == Budget.COMMANDS ? 3 : PdfSpoolLimits.DEFAULT.maxCommands())
                .write(pages, accepted);
        try (var pdf = Loader.loadPDF(accepted.bytes())) {
            assertEquals(2, pdf.getNumberOfPages());
            assertSame(image(pdf, 0).getCOSObject(), image(pdf, 1).getCOSObject());
        }
        assertFalse(accepted.closed);
        assertClean();
    }

    static Stream<Arguments> repeatedResourceBudgets() {
        return Stream.of(Mode.values()).flatMap(mode ->
                Stream.of(Budget.values()).map(budget -> Arguments.of(mode, budget)));
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void cancellationAfterAResourceWasUsedRestoresScopeAndAllowsIndependentOutput(Mode mode) throws IOException {
        var target = new Target(mode, PdfLimits.DEFAULT, PdfSpoolLimits.DEFAULT.maxCommands());
        var source = solid(0x9b2051);
        var cancelled = new AtomicBoolean();
        var pagesReported = new AtomicInteger();
        var output = new TrackingOutput();
        var options = ExecutionOptions.builder().cancelled(cancelled::get).progress(progress -> {
            if (progress.stage().equals("PDF_PAGE") && pagesReported.incrementAndGet() == 2) {
                cancelled.set(true);
            }
        }).build();
        var failure = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(options, () -> {
            target.write(List.of(page(source), page(source)), output);
            return null;
        }));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code());
        assertEquals(2, pagesReported.get());
        assertFalse(output.closed);
        assertClean();

        var recovered = new TrackingOutput();
        target.write(List.of(page(source)), recovered);
        assertSolidPage(recovered.bytes(), 0x9b2051);
        assertFalse(recovered.closed);
        assertClean();
    }

    @ParameterizedTest
    @EnumSource(value = Mode.class, names = {"PAGES", "SPOOL"})
    void cancelledWarmSessionIsTerminalButCloseAndBorrowedOutputRemainUsable(Mode mode) throws IOException {
        var target = new Target(mode, PdfLimits.DEFAULT, PdfSpoolLimits.DEFAULT.maxCommands());
        var source = page(solid(0x245f86));
        var output = new TrackingOutput();
        var cancelled = new AtomicBoolean();
        try (var writer = target.open(output)) {
            writer.accept(source);
            var options = ExecutionOptions.builder().cancelled(cancelled::get).build();
            var failure = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(options, () -> {
                cancelled.set(true);
                writer.accept(source);
                return null;
            }));
            assertEquals(ExecutionException.Code.CANCELLED, failure.code());
            assertThrows(IllegalStateException.class, () -> writer.accept(source));
            assertThrows(IllegalStateException.class, writer::finish);
        }
        assertFalse(output.closed);
        output.write(0x5a);
        assertClean();
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void outputFailureAfterWarmResourcesKeepsCauseOwnershipAndSubsequentDocumentIsolation(Mode mode)
            throws IOException {
        var target = new Target(mode, PdfLimits.DEFAULT, PdfSpoolLimits.DEFAULT.maxCommands());
        var source = solid(0xa14f26);
        var output = new TrackingOutput();
        var pagesReported = new AtomicInteger();
        var options = ExecutionOptions.builder().progress(progress -> {
            if (progress.stage().equals("PDF_PAGE") && pagesReported.incrementAndGet() == 2) {
                output.failing = true;
            }
        }).build();
        var failure = assertThrows(IOException.class, () -> ExecutionContext.execute(options, () -> {
            target.write(List.of(page(source), page(source)), output);
            return null;
        }));
        assertTrue(hasCause(failure, output.failure), "the original destination failure must remain reachable");
        assertFalse(output.closed);
        assertClean();
        output.failing = false;
        output.write(0x5a);

        var recovered = new TrackingOutput();
        target.write(List.of(page(source), page(new RgbImage(1, 1, new byte[]{(byte) 0xa1, 0x4f, 0x26}))), recovered);
        try (var pdf = Loader.loadPDF(recovered.bytes())) {
            assertEquals(2, pdf.getNumberOfPages());
            assertSame(image(pdf, 0).getCOSObject(), image(pdf, 1).getCOSObject());
            assertSamples(image(pdf, 0), 1, 1, 0xffa14f26);
        }
        assertFalse(recovered.closed);
        assertClean();
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    @Timeout(30)
    void concurrentDocumentsShareImmutableInputsWithoutSharingOwnedPdfResources(Mode mode) throws Exception {
        var target = new Target(mode, PdfLimits.DEFAULT, PdfSpoolLimits.DEFAULT.maxCommands());
        var common = new RgbImage(1, 2, twoPixels());
        int workers = 4;
        var barrier = new CyclicBarrier(workers);
        var futures = new ArrayList<Future<byte[]>>();
        var pool = Executors.newFixedThreadPool(workers);
        try {
            for (int index = 0; index < workers; index++) {
                int color = (index + 1) * 0x302010;
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    var output = new TrackingOutput();
                    target.write(List.of(page(common), page(solid(color)),
                            page(new RgbImage(1, 2, twoPixels())), page(solid(color))), output);
                    assertFalse(output.closed);
                    return output.bytes();
                }));
            }
            for (int index = 0; index < workers; index++) {
                try (var pdf = Loader.loadPDF(futures.get(index).get(20, TimeUnit.SECONDS))) {
                    assertEquals(4, pdf.getNumberOfPages());
                    assertSame(image(pdf, 0).getCOSObject(), image(pdf, 2).getCOSObject());
                    assertSame(image(pdf, 1).getCOSObject(), image(pdf, 3).getCOSObject());
                    assertNotSame(image(pdf, 0).getCOSObject(), image(pdf, 1).getCOSObject());
                    assertSamples(image(pdf, 0), 1, 2, RED, BLUE);
                    assertSamples(image(pdf, 1), 1, 1, 0xff000000 | (index + 1) * 0x302010);
                }
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "PDF resource workers must stop after the test");
        }
        assertClean();
    }

    private final class Target {
        private final Mode mode;
        private final PdfBackend ordinary;
        private final PdfSpoolBackend spool;

        private Target(Mode mode, PdfLimits limits, long maxCommands) {
            this.mode = mode;
            ordinary = new PdfBackend(limits);
            var defaults = PdfSpoolLimits.DEFAULT;
            var spoolLimits = new PdfSpoolLimits(defaults.maxPages(), maxCommands, defaults.maxObjectsPerPage(),
                    defaults.maxObjects(), defaults.maxPageBytes(), defaults.maxTemporaryBytes(), defaults.maxOutputBytes());
            spool = new PdfSpoolBackend(temporary, limits, spoolLimits);
        }

        private PageWriter open(OutputStream output) throws IOException {
            return mode == Mode.SPOOL ? spool.open(DocumentMetadata.EMPTY, output)
                    : ordinary.open(DocumentMetadata.EMPTY, output);
        }

        private void write(List<DisplayPage> pages, OutputStream output) throws IOException {
            if (mode == Mode.COMPLETE) {
                ordinary.write(new DisplayDocument(DocumentMetadata.EMPTY, pages), output);
                return;
            }
            try (var writer = open(output)) {
                for (var page : pages) { writer.accept(page); }
                writer.finish();
            }
        }
    }

    private static byte[] twoPixels() { return new byte[]{(byte) 255, 0, 0, 0, 0, (byte) 255}; }

    private static RgbImage solid(int rgb) {
        return new RgbImage(1, 1, new byte[]{(byte) (rgb >>> 16), (byte) (rgb >>> 8), (byte) rgb});
    }

    private static DisplayPage page(RgbImage image) {
        return new DisplayListBuilder(SIZE).drawImage(image, new Rect(10, 10, 20, 20)).build();
    }

    private static PDImageXObject image(PDDocument pdf, int page) throws IOException {
        var resources = pdf.getPage(page).getResources();
        var names = resources.getXObjectNames().iterator();
        assertTrue(names.hasNext());
        var image = (PDImageXObject) resources.getXObject(names.next());
        assertFalse(names.hasNext());
        return image;
    }

    private static void assertSamples(PDImageXObject image, int width, int height, int... expected) throws IOException {
        assertEquals(width, image.getWidth());
        assertEquals(height, image.getHeight());
        assertEquals(width * height, expected.length);
        var pixels = image.getImage();
        try {
            for (int index = 0; index < expected.length; index++) {
                int actual = pixels.getRGB(index % width, index / width);
                if (expected[index] == 0) { assertEquals(0, actual >>> 24); }
                else { assertEquals(expected[index], actual); }
            }
        } finally { pixels.flush(); }
    }

    private static void assertSolidPage(byte[] document, int rgb) throws IOException {
        try (var pdf = Loader.loadPDF(document)) {
            assertEquals(1, pdf.getNumberOfPages());
            assertSamples(image(pdf, 0), 1, 1, 0xff000000 | rgb);
        }
    }

    private static boolean hasCause(Throwable actual, Throwable expected) {
        for (int depth = 0; actual != null && depth < 16; depth++, actual = actual.getCause()) {
            if (actual == expected) { return true; }
        }
        return false;
    }

    private void assertClean() throws IOException {
        try (var files = Files.list(temporary)) { assertEquals(0, files.count()); }
    }

    private static final class TrackingOutput extends OutputStream {
        private final ByteArrayOutputStream contents = new ByteArrayOutputStream();
        private final IOException failure = new IOException("destination failure after resource warmup");
        private boolean failing;
        private boolean closed;

        @Override public void write(int value) throws IOException {
            if (failing) { throw failure; }
            contents.write(value);
        }

        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            if (failing) { throw failure; }
            contents.write(bytes, offset, length);
        }

        @Override public void close() { closed = true; }

        private byte[] bytes() { return contents.toByteArray(); }
    }
}
