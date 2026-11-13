// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbImage;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.text.GlyphRun;
import com.mirakyux.export4j.text.SimpleTextShaper;

class PdfSpoolFontReuseTest {
    @TempDir Path directory;

    @ParameterizedTest
    @CsvSource({"LiberationSans-Regular.ttf,AB", "SourceSans3-Regular.otf,AB", "Export4j-CID-Test.otf,AB"})
    void sharesCompleteEquivalentFontGraphsAcrossPages(String resource, String text) throws IOException {
        var face = font(resource);
        var output = new ByteArrayOutputStream();
        try (var writer = new PdfSpoolBackend(directory).open(DocumentMetadata.EMPTY, output)) {
            writer.accept(page(face, text)); writer.accept(page(face, text)); writer.finish();
        }
        try (var pdf = Loader.loadPDF(output.toByteArray())) {
            assertEquals(List.of(text, text), new PDFTextStripper().getText(pdf).lines().toList());
            assertSame(firstFont(pdf, 0), firstFont(pdf, 1), "equivalent font resources must reuse one output object");
        }
        assertClean();
    }

    @Test
    void preservesDifferentSubsetsAndReusesTheEarlierSubsetWhenItReturns() throws IOException {
        var face = font("LiberationSans-Regular.ttf");
        var output = new ByteArrayOutputStream();
        try (var writer = new PdfSpoolBackend(directory).open(DocumentMetadata.EMPTY, output)) {
            for (var text : List.of("AB", "CD", "AB")) { writer.accept(page(face, text)); }
            writer.finish();
        }
        try (var pdf = Loader.loadPDF(output.toByteArray())) {
            assertEquals(List.of("AB", "CD", "AB"), new PDFTextStripper().getText(pdf).lines().toList());
            assertNotSame(firstFont(pdf, 0), firstFont(pdf, 1));
            assertSame(firstFont(pdf, 0), firstFont(pdf, 2));
        }
        assertClean();
    }

    @Test
    void keepsUnicodeAliasesDistinctWhenFinalizedSubsetProgramsAreReused() throws IOException {
        var face = font("LXGWWenKaiTC-Regular.ttf");
        assertEquals(face.glyph('丟').orElseThrow().glyphId(), face.glyph('丢').orElseThrow().glyphId());
        var texts = List.of("丟", "丢", "丟", "丢丟\uD840\uDC03", "丟丢\uD840\uDC03", "丢丟\uD840\uDC03");
        var output = new ByteArrayOutputStream();
        try (var writer = new PdfSpoolBackend(directory).open(DocumentMetadata.EMPTY, output)) {
            for (var text : texts) { writer.accept(page(face, text)); }
            writer.finish();
        }
        try (var pdf = Loader.loadPDF(output.toByteArray())) {
            assertEquals(texts, new PDFTextStripper().getText(pdf).lines().toList());
            assertNotSame(firstFont(pdf, 0), firstFont(pdf, 1), "shared outlines must retain different Unicode mappings");
            assertSame(firstFont(pdf, 0), firstFont(pdf, 2));
            assertSame(firstFont(pdf, 3), firstFont(pdf, 4), "the same Unicode set keeps stable CID assignments across source orderings");
            assertSame(firstFont(pdf, 3), firstFont(pdf, 5), "an earlier finalized subset must be reusable");
        }
        assertClean();
    }

    @Test
    void sharesTheFontAcrossDifferentChineseUnicodeOrderings() throws IOException {
        var face = font("LXGWWenKaiTC-Regular.ttf");
        var texts = List.of("产品丢丟文档AB", "AB文档丟丢产品", "产品丢丟文档AB");
        var output = new ByteArrayOutputStream();
        try (var writer = new PdfSpoolBackend(directory).open(DocumentMetadata.EMPTY, output)) {
            for (var text : texts) { writer.accept(page(face, text)); }
            writer.finish();
        }
        try (var pdf = Loader.loadPDF(output.toByteArray())) {
            assertEquals(texts, new PDFTextStripper().getText(pdf).lines().toList());
            assertSame(firstFont(pdf, 0), firstFont(pdf, 1), "the same Unicode set must share its stable encoding");
            assertSame(fontProgram(pdf, 0), fontProgram(pdf, 1), "equivalent glyph programs must share one output stream");
            assertSame(firstFont(pdf, 0), firstFont(pdf, 2), "returning to the earlier encoding still reuses its font root");
        }
        assertClean();
    }

    @Test
    void sharesTheCompleteProgramWhileKeepingDifferentUnicodeEncodings() throws IOException {
        byte[] bytes;
        try (var input = PdfSpoolFontReuseTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            bytes = input.readAllBytes();
        }
        var tables = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        boolean patched = false;
        for (int index = 0; index < Short.toUnsignedInt(tables.getShort(4)); index++) {
            int entry = 12 + 16 * index;
            if (new String(bytes, entry, 4, StandardCharsets.US_ASCII).equals("OS/2")) {
                tables.putShort(tables.getInt(entry + 8) + 8, (short) 0x0100);
                patched = true;
                break;
            }
        }
        assertEquals(true, patched, "the real fixture must have an OS/2 embedding policy");
        var face = TrueTypeFontLoader.load(new java.io.ByteArrayInputStream(bytes));
        assertEquals(false, face.data().subsetAllowed());
        var texts = List.of("AB", "BA", "AB");
        var output = new ByteArrayOutputStream();
        try (var writer = new PdfSpoolBackend(directory).open(DocumentMetadata.EMPTY, output)) {
            for (var text : texts) { writer.accept(page(face, text)); }
            writer.finish();
        }
        try (var pdf = Loader.loadPDF(output.toByteArray())) {
            assertEquals(texts, new PDFTextStripper().getText(pdf).lines().toList());
            assertNotSame(firstFont(pdf, 0), firstFont(pdf, 1), "unplanned complete fonts retain their distinct CID assignments");
            assertSame(fontProgram(pdf, 0), fontProgram(pdf, 1), "complete programs are shared independently of Unicode encodings");
            assertSame(firstFont(pdf, 0), firstFont(pdf, 2));
        }
        assertClean();
    }

    @Test
    void fontHitsStillChargeTheEntireSourceObjectGraph() throws IOException {
        var face = font("LiberationSans-Regular.ttf");
        var first = page(face, "AB");
        var commands = new java.util.ArrayList<>(first.commands());
        commands.add(new DrawCommand.Image(new RgbImage(1, 1, new byte[3]), new Rect(100, 20, 10, 10)));
        var second = new DisplayPage(first.size(), commands);
        // This bound admits the text page, but not the same page plus an image.
        var defaults = PdfSpoolLimits.DEFAULT;
        var bounded = new PdfSpoolLimits(defaults.maxPages(), defaults.maxCommands(), 17, defaults.maxObjects(),
                defaults.maxPageBytes(), defaults.maxTemporaryBytes(), defaults.maxOutputBytes());
        try (var writer = new PdfSpoolBackend(directory, PdfLimits.DEFAULT, bounded)
                .open(DocumentMetadata.EMPTY, new ByteArrayOutputStream())) {
            writer.accept(first);
            assertThrows(IOException.class, () -> writer.accept(second));
            assertThrows(IllegalStateException.class, writer::finish);
        }
        assertClean();
    }

    @Test
    void boundedFontHistoryEvictsOldGraphsAndKeepsRecentReuse() throws IOException {
        var face = font("LiberationSans-Regular.ttf");
        var output = new ByteArrayOutputStream();
        try (var writer = new PdfSpoolBackend(directory).open(DocumentMetadata.EMPTY, output)) {
            for (int mask = 0; mask < 257; mask++) { writer.accept(page(face, subset(mask))); }
            writer.accept(page(face, subset(256))); writer.accept(page(face, subset(0))); writer.finish();
        }
        try (var pdf = Loader.loadPDF(output.toByteArray())) {
            assertSame(firstFont(pdf, 256), firstFont(pdf, 257));
            assertNotSame(firstFont(pdf, 0), firstFont(pdf, 258), "old source identities must leave the bounded history");
            assertSame(fontProgram(pdf, 256), fontProgram(pdf, 257));
            assertNotSame(fontProgram(pdf, 0), fontProgram(pdf, 258), "old programs must leave their bounded independent history");
            var text = new PDFTextStripper().getText(pdf).lines().toList();
            assertEquals(259, text.size());
            for (int mask = 0; mask < 257; mask++) { assertEquals(subset(mask), text.get(mask)); }
        }
        assertClean();
    }

    private static String subset(int mask) {
        var text = new StringBuilder("A");
        for (int bit = 0; bit < 9; bit++) { if ((mask & (1 << bit)) != 0) { text.append((char) ('B' + bit)); } }
        return text.toString();
    }

    private static COSBase firstFont(PDDocument pdf, int page) throws IOException {
        var fonts = pdf.getPage(page).getResources().getCOSObject().getCOSDictionary(COSName.FONT);
        return fonts.getDictionaryObject(fonts.keySet().iterator().next());
    }

    private static COSBase fontProgram(PDDocument pdf, int page) throws IOException {
        var font = (COSDictionary) firstFont(pdf, page);
        var descendant = (COSDictionary) font.getCOSArray(COSName.DESCENDANT_FONTS).getObject(0);
        return descendant.getCOSDictionary(COSName.FONT_DESC).getDictionaryObject(COSName.FONT_FILE2);
    }

    private static DisplayPage page(FontFace face, String text) {
        var glyphs = new SimpleTextShaper().shape(text, List.of(face), 18).glyphs().stream().map(g -> g.glyph()).toList();
        return new DisplayListBuilder(new PageSize(200, 100))
                .drawGlyphRun(new GlyphRun(face.data(), 18, glyphs), new Point(20, 50), RgbColor.BLACK).build();
    }

    private static FontFace font(String resource) throws IOException {
        try (var input = PdfSpoolFontReuseTest.class.getResourceAsStream("/fonts/" + resource)) {
            return TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES);
        }
    }

    private void assertClean() throws IOException {
        try (var paths = Files.list(directory)) { assertEquals(0, paths.count()); }
    }
}
