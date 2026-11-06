// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import com.mirakyux.export4j.text.GlyphRun;
import com.mirakyux.export4j.text.PositionedGlyph;
import com.mirakyux.export4j.text.SimpleTextShaper;

class PdfFontSubsetProgramsTest {
    @TempDir Path temporary;

    @Test void finalizedGraphsSurviveClosingTheOriginalDocumentAndKeepOrderAndAdvancesIndependent() throws IOException {
        var face = font("PositionedTest.ttf");
        var initial = page(face, "AB", 1);
        var reordered = page(face, "BA", 1.25);
        try (var programs = new PdfFontPrograms()) {
            render(initial, programs);
            programs.subsets().begin(List.of(reordered));
            try (var document = new PDDocument()) {
                var cached = programs.subsets().create(document, face.data());
                assertNotNull(cached, "the finalized subset must survive closing its original page document");
                int a = face.glyph('A').orElseThrow().glyphId();
                assertArrayEquals(new byte[] {(byte) (a >>> 8), (byte) a}, cached.encode("A"));
            } finally { programs.releasePage(); }
            assertAppearance(render(reordered, null), render(reordered, programs));
            assertAppearance(render(initial, null), render(initial, programs));
        }
    }

    @Test void differentUnicodeDeclarationsAndFontProgramsDoNotShareSubsetKeys() throws IOException {
        var sans = font("PositionedTest.ttf");
        var serif = font("LiberationSerif-Regular.ttf");
        try (var programs = new PdfFontPrograms()) {
            render(page(sans, "A", 1), programs);
            int a = sans.glyph('A').orElseThrow().glyphId();
            // Identical declared GIDs do not establish identical Unicode semantics. This deliberately
            // invalid B->A declaration tests lookup isolation; the ordinary renderer must reject it.
            var changed = new DisplayListBuilder(new PageSize(200, 100)).drawGlyphRun(
                    new GlyphRun(sans.data(), 18, List.of(new PositionedGlyph('B', a, 18))),
                    new Point(20, 50), RgbColor.BLACK).build();
            programs.subsets().begin(List.of(changed));
            try (var document = new PDDocument()) { assertNull(programs.subsets().create(document, sans.data())); }
            finally { programs.releasePage(); }
            var failure = assertThrows(PdfRenderException.class, () -> render(changed, programs));
            assertEquals(PdfErrorCode.INVALID_FONT, failure.code());
            render(page(sans, "AB", 1), programs);
            assertAppearance(render(page(sans, "BA", 1), null), render(page(sans, "BA", 1), programs));
            programs.subsets().begin(List.of(page(serif, "AB", 1)));
            try (var document = new PDDocument()) { assertNull(programs.subsets().create(document, serif.data())); }
            finally { programs.releasePage(); }
            assertAppearance(render(page(serif, "AB", 1), null), render(page(serif, "AB", 1), programs));
        }
    }

    @Test void warmingAValidSubsetNeverAdmitsAnInvalidMeasuredGlyphMapping() throws IOException {
        var face = font("PositionedTest.ttf");
        try (var programs = new PdfFontPrograms()) {
            render(page(face, "AB", 1), programs);
            var wrong = new GlyphRun(face.data(), 18, List.of(new PositionedGlyph('A', 3, 18), new PositionedGlyph('B', 2, 18)));
            var page = new DisplayListBuilder(new PageSize(200, 100)).drawGlyphRun(wrong, new Point(20, 50), RgbColor.BLACK).build();
            var failure = assertThrows(PdfRenderException.class, () -> render(page, programs));
            assertEquals(PdfErrorCode.INVALID_FONT, failure.code());
            assertAppearance(render(page(face, "AB", 1), null), render(page(face, "AB", 1), programs));
        }
    }

    @Test void cacheHitsRetainExactSerializedPageAdmissionAndDoNotAdmitALargerPage() throws IOException {
        var face = font("PositionedTest.ttf");
        var page = page(face, "AB", 1);
        long exact = render(page, null).length;
        var limits = PdfSpoolLimits.DEFAULT;
        var bounded = new PdfSpoolLimits(limits.maxPages(), limits.maxCommands(), limits.maxObjectsPerPage(),
                limits.maxObjects(), exact, limits.maxTemporaryBytes(), limits.maxOutputBytes());
        var larger = new java.util.ArrayList<>(page.commands());
        for (int index = 0; index < 100; index++) {
            larger.add(new DrawCommand.FillRect(new Rect(index, 60, 1, 1), RgbColor.BLACK));
        }
        try (var writer = new PdfSpoolBackend(temporary, PdfLimits.DEFAULT, bounded)
                .open(DocumentMetadata.EMPTY, new ByteArrayOutputStream())) {
            writer.accept(page);
            writer.accept(page);
            assertThrows(IOException.class, () -> writer.accept(new DisplayPage(page.size(), larger)));
            assertThrows(IllegalStateException.class, writer::finish);
        }
        assertClean();
    }

    @Test void cachedPageOutputFailureAndCancellationLeaveTheBorrowedDestinationOpenAndCleanTheSession() throws IOException {
        var face = font("PositionedTest.ttf");
        var page = page(face, "AB", 1);
        for (boolean cancel : List.of(false, true)) {
            var closed = new AtomicBoolean();
            var failing = new AtomicBoolean();
            var cancelled = new AtomicBoolean();
            var target = new OutputStream() {
                @Override public void write(int value) throws IOException {
                    if (failing.get()) {
                        if (cancel) { cancelled.set(true); }
                        else { throw new IOException("cached page destination failure"); }
                    }
                }
                @Override public void write(byte[] bytes, int offset, int length) throws IOException { write(0); }
                @Override public void close() { closed.set(true); }
            };
            try (var writer = new PdfSpoolBackend(temporary).open(DocumentMetadata.EMPTY, target)) {
                writer.accept(page);
                failing.set(true);
                if (cancel) {
                    var failure = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(
                            ExecutionOptions.builder().cancelled(cancelled::get).build(), () -> { writer.accept(page); return null; }));
                    assertEquals(ExecutionException.Code.CANCELLED, failure.code());
                } else { assertThrows(IOException.class, () -> writer.accept(page)); }
                assertThrows(IllegalStateException.class, writer::finish);
            }
            assertFalse(closed.get());
            assertClean();
        }
    }

    private static byte[] render(DisplayPage page, PdfFontPrograms programs) throws IOException {
        var output = new ByteArrayOutputStream();
        new PdfBackend().write(new DisplayDocument(DocumentMetadata.EMPTY, List.of(page)), output, ProcessingPolicy.STRICT, programs);
        return output.toByteArray();
    }

    private static void assertAppearance(byte[] expected, byte[] actual) throws IOException {
        try (var first = Loader.loadPDF(expected); var second = Loader.loadPDF(actual)) {
            assertEquals(new PDFTextStripper().getText(first), new PDFTextStripper().getText(second));
            var reference = new PDFRenderer(first).renderImageWithDPI(0, 72);
            var output = new PDFRenderer(second).renderImageWithDPI(0, 72);
            try {
                assertEquals(reference.getWidth(), output.getWidth()); assertEquals(reference.getHeight(), output.getHeight());
                assertArrayEquals(reference.getRGB(0, 0, reference.getWidth(), reference.getHeight(), null, 0, reference.getWidth()),
                        output.getRGB(0, 0, output.getWidth(), output.getHeight(), null, 0, output.getWidth()));
            } finally { reference.flush(); output.flush(); }
        }
    }

    private static DisplayPage page(FontFace face, String text, double advanceScale) {
        var glyphs = new SimpleTextShaper().shape(text, List.of(face), 18).glyphs().stream().map(value -> value.glyph())
                .map(value -> new PositionedGlyph(value.codePoint(), value.glyphId(), value.advance() * advanceScale)).toList();
        return new DisplayListBuilder(new PageSize(200, 100)).drawGlyphRun(new GlyphRun(face.data(), 18, glyphs),
                new Point(20, 50), RgbColor.BLACK).build();
    }

    private static FontFace font(String name) throws IOException {
        try (var input = PdfFontSubsetProgramsTest.class.getResourceAsStream("/fonts/" + name)) {
            return TrueTypeFontLoader.load(input);
        }
    }

    private void assertClean() throws IOException {
        try (var paths = Files.list(temporary)) { assertEquals(0, paths.count()); }
    }
}
