// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DocumentExtras;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.RgbImage;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.ExternalDestination;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.text.GlyphRun;
import com.mirakyux.export4j.text.SimpleTextShaper;

class PdfSpoolDirectPageTest {
    @TempDir Path temporary;

    @Test
    void copiesFinalizedSubsetsImagesAndExternalLinksWithoutAnIntermediatePdf() throws IOException {
        var face = font("LiberationSans-Regular.ttf");
        var red = new RgbImage(1, 1, new byte[]{(byte) 255, 0, 0});
        var transparentRed = new RgbImage(1, 1, new byte[]{(byte) 255, 0, 0}, new byte[]{0});
        var pages = List.of(page(face, "AB", red), page(face, "CD", transparentRed), page(face, "AB", red));
        var output = new ObservingOutput();
        try (var writer = new PdfSpoolBackend(temporary).open(new DocumentMetadata("Direct page", "Author"), output)) {
            for (int index = 0; index < pages.size(); index++) {
                var link = new DocumentExtras.ExternalLink(index, new Rect(20, 10, 30, 15),
                        new ExternalDestination("https://example.com/direct"));
                writer.accept(pages.get(index), List.of(link));
            }
            writer.finish();
        }
        assertFalse(output.closed);
        assertFalse(output.intermediatePdfObserved);
        try (var pdf = Loader.loadPDF(output.bytes.toByteArray())) {
            assertEquals(3, pdf.getNumberOfPages());
            assertEquals("Direct page", pdf.getDocumentInformation().getTitle());
            assertEquals("Author", pdf.getDocumentInformation().getAuthor());
            assertEquals(List.of("AB", "CD", "AB"), new PDFTextStripper().getText(pdf).lines().toList());
            assertSame(firstFont(pdf, 0), firstFont(pdf, 2));
            assertNotSame(firstFont(pdf, 0), firstFont(pdf, 1));
            assertSame(image(pdf, 0).getCOSObject(), image(pdf, 2).getCOSObject());
            assertNotSame(image(pdf, 0).getCOSObject(), image(pdf, 1).getCOSObject());
            assertEquals(0xffff0000, image(pdf, 0).getImage().getRGB(0, 0));
            assertEquals(0, image(pdf, 1).getImage().getRGB(0, 0) >>> 24);
            for (var pdfPage : pdf.getPages()) {
                assertEquals("https://example.com/direct", pdfPage.getAnnotations().getFirst().getCOSObject()
                        .getCOSDictionary(COSName.A).getString(COSName.URI));
                assertEquals(COSName.PAGES, pdfPage.getCOSObject().getCOSDictionary(COSName.PARENT).getCOSName(COSName.TYPE));
            }
        }
        assertClean();
    }

    @Test
    void callbackFailureClosesThePageBeforeUnpinningBorrowedPrograms() throws IOException {
        var sans = font("LiberationSans-Regular.ttf");
        var serif = font("LiberationSerif-Regular.ttf");
        var captured = new AtomicReference<COSStream>();
        var failure = new IOException("direct page consumer failed");
        try (var programs = new PdfFontPrograms(1, 32L * 1024 * 1024)) {
            var thrown = assertThrows(IOException.class, () -> new PdfBackend().withRenderedPage(
                    new DisplayDocument(DocumentMetadata.EMPTY, List.of(page(sans, "AB", red()))),
                    OutputStream.nullOutputStream(), programs, null, rendered -> {
                        assertNull(programs.borrow(serif.data()), "the active page's program must remain pinned");
                        var object = (PDImageXObject) rendered.getResources()
                                .getXObject(rendered.getResources().getXObjectNames().iterator().next());
                        captured.set(object.getCOSObject());
                        try (var raw = captured.get().createRawInputStream()) { assertTrue(raw.read() >= 0); }
                        throw failure;
                    }));
            assertTrue(hasCause(thrown, failure));
            assertNotNull(captured.get());
            assertThrows(IOException.class, () -> captured.get().createRawInputStream());
            assertNotNull(programs.borrow(serif.data()), "page failure must release its pin after document cleanup");
        }
    }

    @Test
    void preservesTheExactSerializedPageByteLimit() throws IOException {
        var page = rectangle();
        var reference = new ByteArrayOutputStream();
        new PdfBackend().write(new DisplayDocument(DocumentMetadata.EMPTY, List.of(page)), reference);
        long exact = reference.size();
        var defaults = PdfSpoolLimits.DEFAULT;
        var tooSmall = limits(exact - 1, defaults.maxTemporaryBytes());
        try (var writer = new PdfSpoolBackend(temporary, PdfLimits.DEFAULT, tooSmall)
                .open(DocumentMetadata.EMPTY, new ByteArrayOutputStream())) {
            assertThrows(IOException.class, () -> writer.accept(page));
            assertThrows(IllegalStateException.class, writer::finish);
        }
        var accepted = new ByteArrayOutputStream();
        try (var writer = new PdfSpoolBackend(temporary, PdfLimits.DEFAULT,
                limits(exact, defaults.maxTemporaryBytes())).open(DocumentMetadata.EMPTY, accepted)) {
            writer.accept(page); writer.finish();
        }
        try (var pdf = Loader.loadPDF(accepted.toByteArray())) { assertEquals(1, pdf.getNumberOfPages()); }
        assertClean();
    }

    @Test
    void temporaryWorkBudgetStillChargesTheCurrentSerializedPage() throws IOException {
        var page = rectangle();
        var reference = new ByteArrayOutputStream();
        new PdfBackend().write(new DisplayDocument(DocumentMetadata.EMPTY, List.of(page)), reference);
        var defaults = PdfSpoolLimits.DEFAULT;
        try (var writer = new PdfSpoolBackend(temporary, PdfLimits.DEFAULT,
                limits(defaults.maxPageBytes(), reference.size() - 1L))
                .open(DocumentMetadata.EMPTY, new ByteArrayOutputStream())) {
            assertThrows(IOException.class, () -> writer.accept(page));
            assertThrows(IllegalStateException.class, writer::finish);
        }
        assertClean();
    }

    @Test
    void copyingOutputFailurePreservesCauseBorrowedOutputAndIndependentRecovery() throws IOException {
        var backend = new PdfSpoolBackend(temporary);
        var output = new ObservingOutput();
        try (var writer = backend.open(DocumentMetadata.EMPTY, output)) {
            output.failing = true;
            var failure = assertThrows(IOException.class, () -> writer.accept(rectangle()));
            assertTrue(hasCause(failure, output.failure));
            assertThrows(IllegalStateException.class, writer::finish);
        }
        assertFalse(output.closed);
        assertClean();
        var recovery = new ByteArrayOutputStream();
        try (var writer = backend.open(DocumentMetadata.EMPTY, recovery)) {
            writer.accept(rectangle()); writer.finish();
        }
        try (var pdf = Loader.loadPDF(recovery.toByteArray())) { assertEquals(1, pdf.getNumberOfPages()); }
        assertClean();
    }

    @Test
    void cancellationDuringCopyTerminatesTheSessionAndRestoresTheExecutionScope() throws IOException {
        var cancelled = new AtomicBoolean();
        var output = new ObservingOutput();
        try (var writer = new PdfSpoolBackend(temporary).open(DocumentMetadata.EMPTY, output)) {
            output.afterWrite = () -> cancelled.set(true);
            var options = ExecutionOptions.builder().cancelled(cancelled::get).build();
            var failure = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(options, () -> {
                writer.accept(rectangle()); return null;
            }));
            assertEquals(ExecutionException.Code.CANCELLED, failure.code());
            assertThrows(IllegalStateException.class, writer::finish);
        }
        ExecutionContext.checkpoint();
        assertFalse(output.closed);
        assertClean();
    }

    private static PdfSpoolLimits limits(long pageBytes, long temporaryBytes) {
        var defaults = PdfSpoolLimits.DEFAULT;
        return new PdfSpoolLimits(defaults.maxPages(), defaults.maxCommands(), defaults.maxObjectsPerPage(),
                defaults.maxObjects(), pageBytes, temporaryBytes, defaults.maxOutputBytes());
    }

    private static boolean hasCause(Throwable actual, Throwable expected) {
        for (var cause = actual; cause != null; cause = cause.getCause()) {
            if (cause == expected) { return true; }
        }
        return false;
    }

    private static DisplayPage rectangle() {
        return new DisplayListBuilder(new PageSize(200, 100))
                .fillRect(new Rect(20, 20, 40, 30), RgbColor.BLACK).build();
    }

    private static DisplayPage page(FontFace face, String text, RgbImage picture) {
        var glyphs = new SimpleTextShaper().shape(text, List.of(face), 18).glyphs().stream()
                .map(glyph -> glyph.glyph()).toList();
        return new DisplayListBuilder(new PageSize(200, 100))
                .drawGlyphRun(new GlyphRun(face.data(), 18, glyphs), new Point(20, 40), RgbColor.BLACK)
                .drawImage(picture, new Rect(100, 20, 20, 20)).build();
    }

    private static RgbImage red() { return new RgbImage(1, 1, new byte[]{(byte) 255, 0, 0}); }

    private static FontFace font(String resource) throws IOException {
        try (var input = PdfSpoolDirectPageTest.class.getResourceAsStream("/fonts/" + resource)) {
            return TrueTypeFontLoader.load(input);
        }
    }

    private static COSBase firstFont(PDDocument pdf, int page) throws IOException {
        var resources = pdf.getPage(page).getResources();
        return resources.getFont(resources.getFontNames().iterator().next()).getCOSObject();
    }

    private static PDImageXObject image(PDDocument pdf, int page) throws IOException {
        var resources = pdf.getPage(page).getResources();
        return (PDImageXObject) resources.getXObject(resources.getXObjectNames().iterator().next());
    }

    private void assertClean() throws IOException {
        try (var files = Files.list(temporary)) { assertEquals(0, files.count()); }
    }

    private final class ObservingOutput extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final IOException failure = new IOException("direct destination failed");
        private Path spoolDirectory;
        private boolean intermediatePdfObserved;
        private boolean failing;
        private boolean closed;
        private Runnable afterWrite = () -> { };

        private void beforeWrite() throws IOException {
            if (spoolDirectory == null) {
                try (var paths = Files.list(temporary)) { spoolDirectory = paths.findFirst().orElseThrow(); }
            }
            if (Files.exists(spoolDirectory.resolve("page.pdf"))) {
                intermediatePdfObserved = true;
                throw new IOException("an intermediate PDF was created during page copy");
            }
            if (failing) { throw failure; }
        }

        @Override public void write(int value) throws IOException {
            beforeWrite(); bytes.write(value); afterWrite.run();
        }

        @Override public void write(byte[] value, int offset, int length) throws IOException {
            beforeWrite(); bytes.write(value, offset, length); afterWrite.run();
        }

        @Override public void close() { closed = true; }
    }
}
