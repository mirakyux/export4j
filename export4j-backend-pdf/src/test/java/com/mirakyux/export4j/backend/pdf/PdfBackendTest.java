// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import com.mirakyux.export4j.graphics.AffineTransform;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.FontFamily;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.RgbImage;
import com.mirakyux.export4j.graphics.TextStyle;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.graphics.core.PathBuilder;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfBackendTest {
    private static final PageSize SIZE = new PageSize(200, 200);
    private static final RgbColor RED = new RgbColor(1, 0, 0);
    private static final RgbColor BLUE = new RgbColor(0, 0, 1);
    private static final RgbColor GREEN = new RgbColor(0, 1, 0);
    private static final TextStyle STYLE = new TextStyle(FontFamily.SANS_SERIF, 14, RgbColor.BLACK);

    @Test
    void writesMultiplePagesMetadataTextAndImageResourcesWithoutClosingOutput() throws IOException {
        DisplayPage first = new DisplayListBuilder(SIZE)
                .drawText("Hello Export4j", new Point(10, 30), STYLE)
                .drawImage(twoRowImage(), new Rect(20, 50, 40, 40)).build();
        DisplayPage second = new DisplayListBuilder(PageSize.LETTER)
                .drawText("Page two", new Point(10, 30), STYLE).build();
        TrackingOutput output = new TrackingOutput();
        new PdfBackend().write(new DisplayDocument(new DocumentMetadata("Title", "Author"),
                List.of(first, second)), output);
        assertFalse(output.closed);
        try (PDDocument pdf = Loader.loadPDF(output.toByteArray())) {
            assertEquals(2, pdf.getNumberOfPages());
            assertEquals("Title", pdf.getDocumentInformation().getTitle());
            assertEquals("Author", pdf.getDocumentInformation().getAuthor());
            assertEquals(612, pdf.getPage(1).getMediaBox().getWidth());
            String extracted = new PDFTextStripper().getText(pdf);
            assertTrue(extracted.contains("Hello Export4j"));
            assertTrue(extracted.contains("Page two"));
            var names = pdf.getPage(0).getResources().getXObjectNames().iterator();
            assertTrue(names.hasNext());
            assertTrue(pdf.getPage(0).getResources().getXObject(names.next()) instanceof PDImageXObject);
        }
    }

    @Test
    void preservesTopLeftCoordinatesUprightImagesTextAndScopedClipping() throws IOException {
        DisplayPage page = new DisplayListBuilder(SIZE)
                .fillRect(new Rect(0, 0, 200, 200), RgbColor.WHITE)
                .fillRect(new Rect(10, 10, 20, 20), RED)
                .save().transform(AffineTransform.translation(50, 0))
                .clip(new Rect(0, 0, 10, 10))
                .fillRect(new Rect(0, 0, 20, 20), BLUE).restore()
                .fillRect(new Rect(70, 10, 10, 10), GREEN)
                .drawImage(twoRowImage(), new Rect(100, 20, 40, 40))
                .drawText("Hello", new Point(10, 100), STYLE)
                .fillPath(new PathBuilder().moveTo(100, 100).lineTo(120, 100)
                        .lineTo(100, 120).close().build(), GREEN)
                .strokePath(new PathBuilder().moveTo(10, 140)
                        .curveTo(10, 150, 30, 150, 30, 140).build(), RgbColor.BLACK, 2)
                .build();
        try (PDDocument pdf = Loader.loadPDF(render(new PdfBackend(), page))) {
            BufferedImage image = new PDFRenderer(pdf).renderImageWithDPI(0, 72);
            assertPixel(image, 15, 15, 0xFF0000);
            assertPixel(image, 15, 185, 0xFFFFFF);
            assertPixel(image, 55, 5, 0x0000FF);
            assertPixel(image, 65, 5, 0xFFFFFF);
            assertPixel(image, 75, 15, 0x00FF00);
            assertPixel(image, 120, 25, 0xFF0000);
            assertPixel(image, 120, 55, 0x0000FF);
            assertPixel(image, 105, 105, 0x00FF00);
            assertTrue(hasDarkPixel(image, 10, 85, 45, 100), "text must rise above baseline");
            assertFalse(hasDarkPixel(image, 10, 103, 45, 120), "text must not be inverted");
            assertTrue(hasDarkPixel(image, 17, 145, 23, 150), "cubic stroke must be present");
        }
    }

    @Test
    void concatenatesMatricesInDocumentedOrderAndRestoresNestedState() throws IOException {
        DisplayPage page = new DisplayListBuilder(SIZE)
                .save().transform(AffineTransform.translation(100, 0))
                .save().transform(new AffineTransform(2, 0, 0, 2, 0, 0))
                .fillRect(new Rect(0, 0, 10, 10), BLUE).restore()
                .fillRect(new Rect(25, 0, 5, 5), GREEN).restore()
                .save().transform(new AffineTransform(0, 1, -1, 0, 150, 10))
                .fillRect(new Rect(0, 0, 20, 10), RED).restore()
                .build();
        try (PDDocument pdf = Loader.loadPDF(render(new PdfBackend(), page))) {
            BufferedImage image = new PDFRenderer(pdf).renderImageWithDPI(0, 72);
            assertPixel(image, 115, 15, 0x0000FF);
            assertPixel(image, 127, 2, 0x00FF00);
            assertPixel(image, 145, 20, 0xFF0000);
            assertPixel(image, 50, 2, 0xFFFFFF);
        }
    }

    @Test
    void reportsUnsupportedTextWithoutLeakingContentOrWritingOutput() {
        DisplayPage page = new DisplayListBuilder(SIZE)
                .drawText("机密😀", new Point(10, 30), STYLE).build();
        TrackingOutput output = new TrackingOutput();
        PdfRenderException failure = assertThrows(PdfRenderException.class,
                () -> new PdfBackend().write(document(page), output));
        assertEquals(PdfErrorCode.UNSUPPORTED_TEXT, failure.code());
        assertEquals(0, failure.pageIndex());
        assertEquals(0, failure.commandIndex());
        assertFalse(failure.getMessage().contains("机密"));
        assertEquals(0, output.size());
        assertFalse(output.closed);
    }

    @ParameterizedTest
    @MethodSource("budgetCases")
    void rejectsOverBudgetDocumentsBeforeWriting(PdfLimits limits, DisplayDocument document) {
        TrackingOutput output = new TrackingOutput();
        PdfRenderException failure = assertThrows(PdfRenderException.class,
                () -> new PdfBackend(limits).write(document, output));
        assertEquals(PdfErrorCode.BUDGET_EXCEEDED, failure.code());
        assertEquals(0, output.size());
        assertFalse(output.closed);
    }

    static Stream<Arguments> budgetCases() {
        DisplayPage blank = new DisplayListBuilder(SIZE).build();
        DisplayPage twoCommands = new DisplayListBuilder(SIZE)
                .fillRect(new Rect(0, 0, 10, 10), RED)
                .fillRect(new Rect(10, 0, 10, 10), BLUE).build();
        DisplayPage nested = new DisplayListBuilder(SIZE).save().save().restore().restore().build();
        DisplayPage image = new DisplayListBuilder(SIZE)
                .drawImage(twoRowImage(), new Rect(0, 0, 10, 10))
                .drawImage(twoRowImage(), new Rect(10, 0, 10, 10)).build();
        DisplayPage text = new DisplayListBuilder(SIZE)
                .drawText("abcd", new Point(0, 10), STYLE).build();
        DisplayPage path = new DisplayListBuilder(SIZE)
                .fillPath(new PathBuilder().moveTo(0, 0).lineTo(10, 0)
                        .lineTo(0, 10).close().build(), RED).build();
        return Stream.of(
                Arguments.of(new PdfLimits(1, 100, 10, 100, 100, 100),
                        new DisplayDocument(DocumentMetadata.EMPTY, List.of(blank, blank))),
                Arguments.of(new PdfLimits(10, 1, 10, 100, 100, 100), document(twoCommands)),
                Arguments.of(new PdfLimits(10, 100, 1, 100, 100, 100), document(nested)),
                Arguments.of(new PdfLimits(10, 100, 10, 3, 100, 100), document(image)),
                Arguments.of(new PdfLimits(10, 100, 10, 100, 3, 100), document(text)),
                Arguments.of(new PdfLimits(10, 100, 10, 100, 100, 3), document(path)));
    }

    @Test
    void rejectsGeometryThatPdfCannotRepresent() {
        DisplayPage oversized = new DisplayListBuilder(new PageSize(20_000, 100)).build();
        DisplayPage overflow = new DisplayListBuilder(SIZE)
                .fillRect(new Rect(Double.MAX_VALUE, 0, 10, 10), RED).build();
        DisplayPage underflow = new DisplayListBuilder(new PageSize(Double.MIN_VALUE, 100)).build();
        for (DisplayPage page : List.of(oversized, overflow, underflow)) {
            PdfRenderException failure = assertThrows(PdfRenderException.class,
                    () -> render(new PdfBackend(), page));
            assertEquals(PdfErrorCode.INVALID_GEOMETRY, failure.code());
        }
    }

    @Test
    void preservesOutputFailureCauseAndCallerOwnership() {
        FailingOutput output = new FailingOutput();
        DisplayPage page = new DisplayListBuilder(SIZE).build();
        PdfRenderException failure = assertThrows(PdfRenderException.class,
                () -> new PdfBackend().write(document(page), output));
        assertEquals(PdfErrorCode.OUTPUT_FAILED, failure.code());
        assertSame(output.failure, failure.getCause());
        assertFalse(output.closed);
        assertNotNull(failure.getCause());
    }

    @Test
    void sameBackendSupportsConcurrentIndependentDocuments() throws Exception {
        PdfBackend backend = new PdfBackend();
        DisplayPage page = new DisplayListBuilder(SIZE)
                .drawText("Concurrent", new Point(10, 30), STYLE).build();
        List<Callable<byte[]>> tasks = IntStream.range(0, 8)
                .<Callable<byte[]>>mapToObj(index -> () -> render(backend, page)).toList();
        try (var executor = Executors.newFixedThreadPool(4)) {
            for (var result : executor.invokeAll(tasks)) {
                try (PDDocument pdf = Loader.loadPDF(result.get())) {
                    assertEquals(1, pdf.getNumberOfPages());
                    assertTrue(new PDFTextStripper().getText(pdf).contains("Concurrent"));
                }
            }
        }
    }

    private static RgbImage twoRowImage() {
        return new RgbImage(1, 2, new byte[]{(byte) 255, 0, 0, 0, 0, (byte) 255});
    }

    private static DisplayDocument document(DisplayPage page) {
        return new DisplayDocument(DocumentMetadata.EMPTY, List.of(page));
    }

    private static byte[] render(PdfBackend backend, DisplayPage page) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        backend.write(document(page), output);
        return output.toByteArray();
    }

    private static void assertPixel(BufferedImage image, int x, int y, int rgb) {
        assertEquals(rgb, image.getRGB(x, y) & 0xFFFFFF, "pixel at " + x + "," + y);
    }

    private static boolean hasDarkPixel(BufferedImage image, int left, int top, int right, int bottom) {
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                if ((image.getRGB(x, y) & 0xFFFFFF) == 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final class TrackingOutput extends ByteArrayOutputStream {
        private boolean closed;

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class FailingOutput extends OutputStream {
        private final IOException failure = new IOException("simulated output failure");
        private boolean closed;

        @Override
        public void write(int value) throws IOException {
            throw failure;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
