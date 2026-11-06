// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import com.mirakyux.export4j.text.GlyphRun;
import com.mirakyux.export4j.text.SimpleTextShaper;

class PdfSpoolRenderingResourcesTest {
    @Test
    void successiveDocumentsBorrowOneProgramButKeepTheirOwnSubsets() throws IOException {
        var face = font("LiberationSans-Regular.ttf");
        try (var programs = new PdfFontPrograms()) {
            var original = programs.borrow(face.data());
            for (var text : List.of("AB", "CD", "éÅ", "AB")) {
                assertEquals(List.of(text), render(programs, page(List.of(face), List.of(text))));
                assertSame(original, programs.borrow(face.data()),
                        "closing a page document must not close or replace the borrowed font program");
            }
        }
    }

    @Test
    void pinsActiveProgramsAndUsesPageOwnedFontsWhenEveryEntryIsPinned() throws IOException {
        var sans = font("LiberationSans-Regular.ttf");
        var serif = font("LiberationSerif-Regular.ttf");
        try (var programs = new PdfFontPrograms(1, 32L * 1024 * 1024)) {
            var original = programs.borrow(sans.data());
            assertNull(programs.borrow(serif.data()));
            assertSame(original, programs.borrow(sans.data()));
            assertEquals(List.of("Sans AB", "Serif CD"),
                    render(programs, page(List.of(sans, serif), List.of("Sans AB", "Serif CD"))));
            assertSame(original, programs.borrow(sans.data()));
        }
    }

    @Test
    void evictsOnlyAfterThePreviousPageHasClosedAndCanReloadTheEarlierFont() throws IOException {
        var sans = font("LiberationSans-Regular.ttf");
        var serif = font("LiberationSerif-Regular.ttf");
        try (var programs = new PdfFontPrograms(1, 32L * 1024 * 1024)) {
            var original = programs.borrow(sans.data());
            assertEquals(List.of("AB"), render(programs, page(List.of(sans), List.of("AB"))));
            assertEquals(List.of("CD"), render(programs, page(List.of(serif), List.of("CD"))));
            var reloaded = programs.borrow(sans.data());
            assertNotSame(original, reloaded);
            assertEquals(List.of("éÅ"), render(programs, page(List.of(sans), List.of("éÅ"))));
        }
    }

    @Test
    void encodedByteBoundAlsoAppliesWhenTheEntryCountHasRoom() throws IOException {
        var sans = font("LiberationSans-Regular.ttf");
        var serif = font("LiberationSerif-Regular.ttf");
        long oneProgram = Math.max(sans.data().byteSize(), serif.data().byteSize());
        try (var programs = new PdfFontPrograms(8, oneProgram)) {
            var original = programs.borrow(sans.data());
            assertNull(programs.borrow(serif.data()), "pinned programs cannot exceed the encoded byte bound");
            programs.releasePage();
            assertNotNull(programs.borrow(serif.data()));
            programs.releasePage();
            assertNotSame(original, programs.borrow(sans.data()));
            assertEquals(List.of("AB", "CD"),
                    render(programs, page(List.of(sans, serif), List.of("AB", "CD"))));
        }
    }

    @Test
    void oversizedProgramsRenderWithPageOwnershipWithoutEnteringTheCache() throws IOException {
        var face = font("LiberationSans-Regular.ttf");
        try (var programs = new PdfFontPrograms(8, face.data().byteSize() - 1L)) {
            for (var text : List.of("AB", "CD")) {
                assertNull(programs.borrow(face.data()));
                assertEquals(List.of(text), render(programs, page(List.of(face), List.of(text))));
            }
        }
    }

    @Test
    void failedOutputReleasesPinsAfterClosingThePageDocument() throws IOException {
        var sans = font("LiberationSans-Regular.ttf");
        var serif = font("LiberationSerif-Regular.ttf");
        var failed = new java.io.OutputStream() {
            @Override public void write(int value) throws IOException { throw new IOException("expected output failure"); }
            @Override public void write(byte[] bytes, int offset, int length) throws IOException { throw new IOException("expected output failure"); }
        };
        try (var programs = new PdfFontPrograms(1, 32L * 1024 * 1024)) {
            var source = new DisplayDocument(DocumentMetadata.EMPTY,
                    List.of(page(List.of(sans), List.of("AB"))));
            assertThrows(IOException.class, () -> new PdfBackend().write(source, failed, ProcessingPolicy.STRICT, programs));
            assertNotNull(programs.borrow(serif.data()), "failed output must release the previous page's font pins");
            assertEquals(List.of("CD"), render(programs, page(List.of(serif), List.of("CD"))));
        }
    }

    @Test
    void closedProgramsCannotBeBorrowedAndOtherSessionsRemainIndependent() throws IOException {
        var face = font("LiberationSans-Regular.ttf");
        var closed = new PdfFontPrograms();
        try (var independent = new PdfFontPrograms()) {
            try {
                assertNotSame(closed.borrow(face.data()), independent.borrow(face.data()));
            } finally { closed.close(); }
            assertThrows(IllegalStateException.class, () -> closed.borrow(face.data()));
            closed.close();
            assertEquals(List.of("AB"), render(independent, page(List.of(face), List.of("AB"))));
        }
    }

    private static List<String> render(PdfFontPrograms programs, DisplayPage page) throws IOException {
        var output = new ByteArrayOutputStream();
        new PdfBackend().write(new DisplayDocument(DocumentMetadata.EMPTY, List.of(page)),
                output, ProcessingPolicy.STRICT, programs);
        try (var pdf = Loader.loadPDF(output.toByteArray())) {
            return new PDFTextStripper().getText(pdf).lines().toList();
        }
    }

    private static DisplayPage page(List<FontFace> faces, List<String> texts) {
        var commands = new ArrayList<DrawCommand>();
        for (int index = 0; index < faces.size(); index++) {
            var face = faces.get(index);
            var glyphs = new SimpleTextShaper().shape(texts.get(index), List.of(face), 18)
                    .glyphs().stream().map(glyph -> glyph.glyph()).toList();
            commands.add(new DrawCommand.GlyphText(new GlyphRun(face.data(), 18, glyphs),
                    new Point(20, 35 + index * 30), RgbColor.BLACK));
        }
        return new DisplayPage(new PageSize(200, 120), commands);
    }

    private static FontFace font(String resource) throws IOException {
        try (var input = PdfSpoolRenderingResourcesTest.class.getResourceAsStream("/fonts/" + resource)) {
            return TrueTypeFontLoader.load(input);
        }
    }
}
