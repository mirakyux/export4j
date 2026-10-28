// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.mirakyux.export4j.graphics.DocumentExtras;
import com.mirakyux.export4j.graphics.FontFamily;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.TextStyle;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.PageSize;

import static org.junit.jupiter.api.Assertions.*;

class PdfSpoolBackendTest {
    @TempDir Path temporary;
    private static final PageSize SIZE = new PageSize(160, 200);
    private static final TextStyle STYLE = new TextStyle(FontFamily.SANS_SERIF, 12, RgbColor.BLACK);

    @Test void remapsIndependentPageGraphsMetadataLinksAndImageGeometry() throws Exception {
        var output = new TrackingOutput();
        try (var writer = new PdfSpoolBackend(temporary).open(new DocumentMetadata("标题", "Author"), output)) {
            writer.accept(new DisplayListBuilder(SIZE).drawText("Page 1", new Point(12, 30), STYLE)
                    .fillRect(new Rect(20, 50, 30, 40), new RgbColor(1, 0, 0)).build(),
                    List.of(new DocumentExtras.ExternalLink(0, new Rect(12, 20, 40, 12), new com.mirakyux.export4j.model.ExternalDestination("https://example.org"))));
            writer.accept(new DisplayListBuilder(SIZE).drawText("Page 2", new Point(12, 30), STYLE).build());
            writer.finish();
            assertThrows(IllegalStateException.class, writer::finish);
        }
        assertFalse(output.closed);
        try (var pdf = Loader.loadPDF(output.toByteArray())) {
            assertEquals(2, pdf.getNumberOfPages());
            assertEquals("标题", pdf.getDocumentInformation().getTitle());
            assertEquals("Author", pdf.getDocumentInformation().getAuthor());
            assertTrue(new PDFTextStripper().getText(pdf).contains("Page 2"));
            var link = (PDAnnotationLink) pdf.getPage(0).getAnnotations().getFirst();
            assertEquals("https://example.org", ((PDActionURI) link.getAction()).getURI());
            assertEquals(12, link.getRectangle().getLowerLeftX());
            assertEquals(168, link.getRectangle().getLowerLeftY());
            assertEquals(0xff0000, new PDFRenderer(pdf).renderImageWithDPI(0, 72).getRGB(25, 60) & 0xffffff);
        }
        assertClean();
    }

    @Test void pageTreeContinuesPastLeafBoundary() throws Exception {
        var output = new ByteArrayOutputStream();
        try (var writer = new PdfSpoolBackend(temporary).open(DocumentMetadata.EMPTY, output)) {
            for (int page = 0; page < 259; page++) {
                writer.accept(new DisplayListBuilder(SIZE).drawText("Page " + page, new Point(12, 30), STYLE).build());
            }
            writer.finish();
        }
        try (var pdf = Loader.loadPDF(output.toByteArray())) {
            assertEquals(259, pdf.getNumberOfPages());
            assertEquals(160, pdf.getPage(258).getMediaBox().getWidth());
            var stripper = new PDFTextStripper(); stripper.setStartPage(259);
            assertTrue(stripper.getText(pdf).contains("Page 258"));
        }
        assertClean();
    }

    @Test void pageCommandObjectAndDiskBudgetsAreTerminalAndCleaned() throws Exception {
        var defaults = PdfSpoolLimits.DEFAULT;
        var budgets = List.of(
                new PdfSpoolLimits(1, 100, 100, 100, 1_000_000, 1_000_000, 1_000_000),
                new PdfSpoolLimits(10, 1, 100, 100, 1_000_000, 1_000_000, 1_000_000),
                new PdfSpoolLimits(10, 100, 1, 100, 1_000_000, 1_000_000, 1_000_000),
                new PdfSpoolLimits(10, 100, 100, 3, 1_000_000, 1_000_000, 1_000_000),
                new PdfSpoolLimits(10, 100, 100, 100, 100, 1_000_000, 1_000_000),
                new PdfSpoolLimits(10, 100, 100, 100, 1_000_000, 50, 1_000_000),
                new PdfSpoolLimits(10, 100, 100, 100, 1_000_000, 1_000_000, 100));
        for (var budget : budgets) {
            var page = new DisplayListBuilder(SIZE).drawText("budget", new Point(12, 30), STYLE).build();
            try (var writer = new PdfSpoolBackend(temporary, PdfLimits.DEFAULT, budget)
                    .open(DocumentMetadata.EMPTY, new ByteArrayOutputStream())) {
                assertThrows(IOException.class, () -> {
                    for (int i = 0; i < 3; i++) { writer.accept(page); }
                    writer.finish();
                });
                assertThrows(IllegalStateException.class, () -> writer.accept(page));
            }
            assertClean();
        }
        assertTrue(defaults.maxOutputBytes() > 1_000_000);
    }

    @Test void outputFailurePreservesCauseAndDoesNotCloseBorrowedOutput() throws Exception {
        var failure = new IOException("destination");
        var output = new TrackingOutput() {
            @Override public void write(byte[] bytes, int start, int count) {
                if (size() > 100) { throw new java.io.UncheckedIOException(failure); }
                super.write(bytes, start, count);
            }
        };
        assertThrows(java.io.UncheckedIOException.class, () -> {
            try (var writer = new PdfSpoolBackend(temporary).open(DocumentMetadata.EMPTY, output)) {
                writer.accept(new DisplayListBuilder(SIZE).build()); writer.finish();
            }
        });
        assertFalse(output.closed); assertClean();
    }

    @Test void cancellationDuringCopyCleansIndicesWithoutClosingOutput() throws Exception {
        int[] checks = {0};
        var output = new TrackingOutput();
        assertThrows(com.mirakyux.export4j.model.ExecutionException.class, () ->
                ExecutionContext.execute(ExecutionOptions.builder().cancelled(() -> ++checks[0] > 40).build(), () -> {
                    try (var writer = new PdfSpoolBackend(temporary).open(DocumentMetadata.EMPTY, output)) {
                        writer.accept(new DisplayListBuilder(SIZE).drawText("cancel", new Point(12, 30), STYLE).build());
                        writer.finish();
                    }
                    return null;
                }));
        assertFalse(output.closed); assertClean();
    }

    @Test void independentSessionsShareOnlyBorrowedTemporaryParent() throws Exception {
        var backend = new PdfSpoolBackend(temporary);
        try (var pool = Executors.newFixedThreadPool(3)) {
            var results = pool.invokeAll(java.util.stream.IntStream.range(0, 6)
                    .<java.util.concurrent.Callable<byte[]>>mapToObj(index -> () -> {
                        var output = new ByteArrayOutputStream();
                        try (var writer = backend.open(DocumentMetadata.EMPTY, output)) {
                            writer.accept(new DisplayListBuilder(SIZE).drawText("Run " + index, new Point(12, 30), STYLE).build());
                            writer.finish();
                        }
                        return output.toByteArray();
                    }).toList());
            for (int index = 0; index < results.size(); index++) {
                try (var pdf = Loader.loadPDF(results.get(index).get())) {
                    assertTrue(new PDFTextStripper().getText(pdf).contains("Run " + index));
                }
            }
        }
        assertClean();
    }

    @Test void sessionIsConfinedAndCloseIsIdempotent() throws Exception {
        var writer = new PdfSpoolBackend(temporary).open(DocumentMetadata.EMPTY, OutputStream.nullOutputStream());
        try (var pool = Executors.newSingleThreadExecutor()) {
            assertInstanceOf(IllegalStateException.class,
                    pool.submit(() -> { try { writer.finish(); return null; } catch (Exception failure) { return failure; } }).get());
        }
        writer.close(); writer.close(); assertClean();
    }

    private void assertClean() throws IOException { try (var files = Files.list(temporary)) { assertEquals(0, files.count()); } }
    private static class TrackingOutput extends ByteArrayOutputStream {
        protected boolean closed;
        @Override public void close() { closed = true; }
    }
}
