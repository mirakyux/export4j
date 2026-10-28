// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingIssue;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.util.Matrix;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfSourceFontMappingTest {
    @Test void standardFontRequiresExplicitMappingAndActualLossPolicy() throws Exception {
        var adapter = new PdfAdapter(); var input = adapter.input(source("Helvetica", false, "SECRET KEEP"), "");
        assertThrows(ProcessingException.class, () -> adapter.render(input.allPages()).prepare());
        assertThrows(ProcessingException.class, () -> adapter.render(input.allPages()).sourceFont("Helvetica", font()).prepare());
        var warn = adapter.render(input.allPages()).dpi(72).sourceFont("Helvetica", font()).policy(mapping(ProcessingPolicy.Action.WARN)).prepare();
        var ignore = adapter.render(input.allPages()).dpi(72).sourceFont("Helvetica", font()).policy(mapping(ProcessingPolicy.Action.IGNORE)).prepare();
        assertEquals(1, warn.images().size()); assertEquals(1, warn.preview().losses().size());
        assertEquals("RENDER_FONT_MAPPING", warn.preview().issues().getFirst().feature());
        assertEquals(List.of("EXPLICIT_SOURCE_FONT_SUBSTITUTION"), warn.preview().issues().getFirst().effects());
        assertFalse(warn.preview().warnings().isEmpty()); assertTrue(ignore.preview().warnings().isEmpty());
        assertArrayEquals(warn.images().getFirst().bytes(), ignore.images().getFirst().bytes());
        assertTrue(black(image(warn.images().getFirst().bytes()), 0, 0, 200, 100) > 100);
        assertThrows(ProcessingException.class, () -> adapter.render(input.allPages()).sourceFont("helvetica", font()).policy(mapping(ProcessingPolicy.Action.WARN)).prepare());
    }

    @Test void originalEncodedWidthsToUnicodeAndContentBytesArePreserved() throws Exception {
        try (var document = Loader.loadPDF(source("DeclaredFont", true, "AAA"))) {
            var page = document.getPage(0); var source = dictionary(page); var widths = source.getDictionaryObject(COSName.WIDTHS);
            byte[] content;
            try (var stream = page.getContents()) { content = stream.readAllBytes(); }
            var unicode = new PDStream(document);
            try (var stream = unicode.createOutputStream()) { stream.write("/CIDInit /ProcSet findresource begin 12 dict begin begincmap /CMapName /Test def /CMapType 2 def 1 begincodespacerange <00> <FF> endcodespacerange 1 beginbfchar <41> <0041> endbfchar endcmap CMapName currentdict /CMap defineresource pop end end".getBytes(StandardCharsets.US_ASCII)); }
            source.setItem(COSName.TO_UNICODE, unicode);
            var issues = new ArrayList<ProcessingIssue>();
            var mappings = new PdfSourceFonts(document, Map.of("DeclaredFont", font()), PdfLimits.DEFAULT,
                    mapping(ProcessingPolicy.Action.WARN), issues, "PNG", 1);
            mappings.apply(page, 0); mappings.validate();
            assertEquals("TrueType", source.getNameAsString(COSName.SUBTYPE));
            assertEquals("WinAnsiEncoding", source.getNameAsString(COSName.ENCODING));
            assertEquals(32, source.getInt(COSName.FIRST_CHAR)); assertEquals(126, source.getInt(COSName.LAST_CHAR));
            assertSame(widths, source.getDictionaryObject(COSName.WIDTHS));
            assertSame(unicode.getCOSObject(), source.getDictionaryObject(COSName.TO_UNICODE));
            try (var stream = page.getContents()) { assertArrayEquals(content, stream.readAllBytes()); }
            var loaded = page.getResources().getFont(COSName.getPDFName("F1"));
            assertTrue(loaded.isEmbedded()); assertEquals(900, loaded.getWidth(65));
            mappings.requireGlyph(loaded, 65); assertThrows(PdfOperationException.class, () -> mappings.requireGlyph(loaded, 200));
        }
        try (var document = Loader.loadPDF(source("Helvetica", false, "WI"))) {
            var mappings = new PdfSourceFonts(document, Map.of("Helvetica", font()), PdfLimits.DEFAULT,
                    mapping(ProcessingPolicy.Action.WARN), new ArrayList<>(), "PNG", 1);
            mappings.apply(document.getPage(0), 0);
            var loaded = document.getPage(0).getResources().getFont(COSName.getPDFName("F1"));
            assertEquals(944, loaded.getWidth('W')); assertEquals(278, loaded.getWidth('I'));
        }
    }

    @Test void allThreeEncodingsRenderHighByteGlyphsWithSourceWidthsAndUnicodeUnchanged() throws Exception {
        record Case(String encoding, int code, String unicode) { }
        var adapter = new PdfAdapter();
        for (var value : List.of(new Case("StandardEncoding", 0xE1, "Æ"), new Case("WinAnsiEncoding", 0x80, "€"), new Case("MacRomanEncoding", 0x80, "Ä"))) {
            byte[] encoded;
            try (var document = Loader.loadPDF(source("DeclaredFont", true, "A"))) {
                var page = document.getPage(0); var font = dictionary(page);
                font.setName(COSName.ENCODING, value.encoding()); font.setInt(COSName.FIRST_CHAR, 0); font.setInt(COSName.LAST_CHAR, 255);
                var widths = new COSArray(); for (int code = 0; code <= 255; code++) { widths.add(new COSFloat(900)); } font.setItem(COSName.WIDTHS, widths);
                var unicode = new PDStream(document);
                try (var stream = unicode.createOutputStream()) {
                    stream.write(("/CIDInit /ProcSet findresource begin 12 dict begin begincmap /CMapName /Test def /CMapType 2 def "
                            + "1 begincodespacerange <00> <FF> endcodespacerange 2 beginbfchar <%02X> <%04X> <41> <0041> endbfchar "
                            + "endcmap CMapName currentdict /CMap defineresource pop end end").formatted(value.code(), value.unicode().codePointAt(0)).getBytes(StandardCharsets.US_ASCII));
                }
                font.setItem(COSName.TO_UNICODE, unicode);
                var content = new PDStream(document); page.setContents(content);
                byte[] shown = "BT /F1 20 Tf 10 60 Td <%02X41> Tj ET\n".formatted(value.code()).getBytes(StandardCharsets.US_ASCII);
                try (var stream = content.createOutputStream()) { stream.write(shown); }
                encoded = save(document);
                var mapping = new PdfSourceFonts(document, Map.of("DeclaredFont", font()), PdfLimits.DEFAULT,
                        mapping(ProcessingPolicy.Action.WARN), new ArrayList<>(), "PNG", encoded.length);
                mapping.apply(page, 0); mapping.validate();
                assertEquals(value.encoding(), font.getNameAsString(COSName.ENCODING)); assertSame(widths, font.getDictionaryObject(COSName.WIDTHS));
                assertSame(unicode.getCOSObject(), font.getDictionaryObject(COSName.TO_UNICODE));
                try (var stream = page.getContents()) { assertArrayEquals(shown, stream.readAllBytes()); }
                var loaded = page.getResources().getFont(COSName.getPDFName("F1"));
                assertEquals(value.unicode(), loaded.toUnicode(value.code())); assertEquals(900, loaded.getWidth(value.code()));
                mapping.requireGlyph(loaded, value.code()); mapping.requireGlyph(loaded, 'A');
            }
            var actual = adapter.render(adapter.input(encoded, "").allPages()).dpi(144).sourceFont("DeclaredFont", font())
                    .policy(mapping(ProcessingPolicy.Action.WARN)).prepare().images().getFirst().bytes();
            byte[] control;
            try (var document = new PDDocument()) {
                var page = new PDPage(new PDRectangle(200, 100)); document.addPage(page);
                var font = PDType0Font.load(document, new ByteArrayInputStream(fontBytes()), false);
                try (var stream = new PDPageContentStream(document, page)) {
                    stream.beginText(); stream.setFont(font, 20); stream.newLineAtOffset(10, 60); stream.showText(value.unicode());
                    // The next glyph starts after the declared 900/1000 * 20 pt advance, independent of the font's native width.
                    stream.setTextMatrix(Matrix.getTranslateInstance(28, 60)); stream.showText("A"); stream.endText();
                }
                control = adapter.render(adapter.input(save(document), "").allPages()).dpi(144).prepare().images().getFirst().bytes();
            }
            assertArrayEquals(control, actual, value.encoding());
            assertTrue(black(image(actual), 0, 0, 400, 200) > 100, value.encoding());
        }
    }

    @Test void unusedMappingsAndEmbeddedProgramsDoNotCreateSubstitutionLosses() throws Exception {
        var adapter = new PdfAdapter(); byte[] bytes;
        try (var document = new PDDocument()) {
            var page = new PDPage(new PDRectangle(200, 100)); document.addPage(page); page.setResources(new PDResources());
            var embedded = PDType0Font.load(document, new ByteArrayInputStream(fontBytes()), false);
            page.getResources().put(COSName.getPDFName("F1"), embedded); bytes = save(document);
        }
        var input = adapter.input(bytes, ""); var original = adapter.render(input.allPages()).prepare();
        var mapped = adapter.render(input.allPages()).sourceFont("unused", font()).sourceFont("LiberationSans", font()).prepare();
        assertTrue(mapped.preview().issues().isEmpty()); assertArrayEquals(original.images().getFirst().bytes(), mapped.images().getFirst().bytes());
        assertThrows(IllegalArgumentException.class, () -> adapter.render(input.allPages()).sourceFont(" ", font()));
        assertThrows(NullPointerException.class, () -> adapter.render(input.allPages()).sourceFont("Helvetica", null));
    }

    @Test void mappingSnapshotAndIndependentRequestsCannotMutateInputOrOtherCandidates() throws Exception {
        var adapter = new PdfAdapter(); byte[] bytes = source("Helvetica", false, "WIDE KEEP"); var input = adapter.input(bytes, "");
        byte[] supplied = fontBytes(); var explicit = PdfFont.of(supplied); supplied[0] = 0;
        var request = adapter.render(input.allPages()).sourceFont("Helvetica", explicit).policy(mapping(ProcessingPolicy.Action.WARN));
        var candidate = request.prepare(); byte[] expected = candidate.images().getFirst().bytes();
        request.sourceFont("Helvetica", PdfFont.of(Files.readAllBytes(Path.of("../assets/fonts/LiberationSans-Bold.ttf"))));
        assertArrayEquals(expected, candidate.images().getFirst().bytes());
        assertFalse(java.util.Arrays.equals(expected, request.prepare().images().getFirst().bytes()));
        assertThrows(ProcessingException.class, () -> adapter.render(input.allPages()).prepare());
        assertArrayEquals(bytes, input.bytes());
        assertThrows(ProcessingException.class, () -> adapter.importDocument(input).policy(ProcessingPolicy.builder()
                .feature("PDF_LAYOUT", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE).build()).prepare());
        assertThrows(ProcessingException.class, () -> adapter.editText(input).replace(0, "KEEP", "EDIT", explicit).prepare());
    }

    @Test void sourceAndEveryLoadedProgramShareTotalInputAndDecodedBudgets() throws Exception {
        byte[] source = source("Helvetica", false, "KEEP"), font = fontBytes();
        var limits = PdfLimits.DEFAULT.toBuilder().maxTotalInputBytes((long) source.length + font.length - 1).build();
        var adapter = PdfAdapter.builder().limits(limits).build();
        assertEquals(PdfOperationException.Code.INPUT_LIMIT, assertThrows(PdfOperationException.class, () -> adapter.render(adapter.input(source, "").allPages())
                .sourceFont("Helvetica", PdfFont.of(font)).policy(mapping(ProcessingPolicy.Action.WARN)).prepare()).code());
        limits = PdfLimits.DEFAULT.toBuilder().maxDecodedBytes(font.length).build();
        var decoded = PdfAdapter.builder().limits(limits).build();
        assertEquals(PdfOperationException.Code.DECODED_STREAM_LIMIT, assertThrows(PdfOperationException.class, () -> decoded.render(decoded.input(source, "").allPages())
                .sourceFont("Helvetica", PdfFont.of(font)).policy(mapping(ProcessingPolicy.Action.WARN)).prepare()).code());
        byte[] twoPages;
        try (var document = Loader.loadPDF(source); var other = Loader.loadPDF(source("Times-Roman", false, "KEEP"))) {
            document.importPage(other.getPage(0)); twoPages = save(document);
        }
        byte[] bold = Files.readAllBytes(Path.of("../assets/fonts/LiberationSans-Bold.ttf"));
        var combined = PdfAdapter.builder().limits(PdfLimits.DEFAULT.toBuilder()
                .maxTotalInputBytes((long) twoPages.length + font.length + bold.length - 1).build()).build();
        var combinedInput = combined.input(twoPages, "");
        assertEquals(PdfOperationException.Code.INPUT_LIMIT, assertThrows(PdfOperationException.class, () -> combined.render(combinedInput.allPages())
                .sourceFont("Helvetica", PdfFont.of(font)).sourceFont("Times-Roman", PdfFont.of(bold))
                .policy(mapping(ProcessingPolicy.Action.WARN)).prepare()).code());
    }

    @Test void symbolicOrUnclassifiedSourcesRemainUnsupportedAndInvalidFlagsFail() throws Exception {
        var adapter = new PdfAdapter();
        var skip = ProcessingPolicy.builder().feature("RENDER_FONT_MAPPING", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
                .feature("RENDER_FONT", ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.SKIP_BLOCK).build();
        for (int flags : List.of(0, 4)) {
            byte[] source;
            try (var document = Loader.loadPDF(source("DeclaredFont", true, "A"))) {
                dictionary(document.getPage(0)).getCOSDictionary(COSName.FONT_DESC).setInt(COSName.FLAGS, flags); source = save(document);
            }
            var result = adapter.render(adapter.input(source, "").allPages()).sourceFont("DeclaredFont", font()).policy(skip).prepare();
            assertTrue(result.images().isEmpty()); assertEquals("RENDER_FONT", result.preview().issues().getFirst().feature());
            assertEquals(1, result.preview().issues().size());
        }
        for (var flags : List.of(new COSString("not flags"), org.apache.pdfbox.cos.COSInteger.get(36))) {
            byte[] source;
            try (var document = Loader.loadPDF(source("DeclaredFont", true, "A"))) {
                dictionary(document.getPage(0)).getCOSDictionary(COSName.FONT_DESC).setItem(COSName.FLAGS, flags); source = save(document);
            }
            var input = adapter.input(source, "");
            assertEquals(PdfOperationException.Code.INVALID_PDF, assertThrows(PdfOperationException.class, () -> adapter.render(input.allPages())
                    .sourceFont("DeclaredFont", font()).policy(skip).prepare()).code());
        }
        var symbol = adapter.render(adapter.input(source("Symbol", false, "A"), "").allPages()).sourceFont("Symbol", font()).policy(skip).prepare();
        assertTrue(symbol.images().isEmpty()); assertEquals("RENDER_FONT", symbol.preview().issues().getFirst().feature());
    }

    @Test void standardFontAliasesRequireExplicitSourceEncodingDescriptorAndWidths() throws Exception {
        var adapter = new PdfAdapter();
        var noDescriptor = adapter.input(source("Arial", false, "ALIAS"), "");
        assertEquals(PdfOperationException.Code.INVALID_PDF, assertThrows(PdfOperationException.class, () -> adapter.render(noDescriptor.allPages())
                .sourceFont("Arial", font()).policy(mapping(ProcessingPolicy.Action.IGNORE)).prepare()).code());
        byte[] explicit;
        try (var document = Loader.loadPDF(source("Arial", true, "ALIAS"))) {
            dictionary(document.getPage(0)).setName(COSName.SUBTYPE, "Type1"); explicit = save(document);
        }
        var mapped = adapter.render(adapter.input(explicit, "").allPages()).dpi(72).sourceFont("Arial", font())
                .policy(mapping(ProcessingPolicy.Action.WARN)).prepare();
        assertEquals(1, mapped.images().size()); assertEquals("RENDER_FONT_MAPPING", mapped.preview().issues().getFirst().feature());
        assertTrue(black(image(mapped.images().getFirst().bytes()), 0, 0, 200, 100) > 100);
        var skip = ProcessingPolicy.builder().feature("RENDER_FONT_MAPPING", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
                .feature("RENDER_FONT", ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.SKIP_BLOCK).build();
        for (boolean removeWidths : List.of(true, false)) {
            byte[] incomplete;
            try (var document = Loader.loadPDF(explicit)) {
                var dictionary = dictionary(document.getPage(0));
                if (removeWidths) {
                    dictionary.removeItem(COSName.WIDTHS); dictionary.removeItem(COSName.FIRST_CHAR); dictionary.removeItem(COSName.LAST_CHAR);
                } else { dictionary.removeItem(COSName.ENCODING); }
                incomplete = save(document);
            }
            var unsupported = adapter.render(adapter.input(incomplete, "").allPages()).sourceFont("Arial", font()).policy(skip).prepare();
            assertTrue(unsupported.images().isEmpty()); assertEquals(1, unsupported.preview().issues().size());
            assertEquals("RENDER_FONT", unsupported.preview().issues().getFirst().feature());
        }
    }

    @Test void malformedSourceAndActualMissingGlyphCannotBeIgnored() throws Exception {
        var adapter = new PdfAdapter(); byte[] broken;
        try (var document = Loader.loadPDF(source("DeclaredFont", true, "A"))) {
            dictionary(document.getPage(0)).setItem(COSName.WIDTHS, new COSString("not an array")); broken = save(document);
        }
        var input = adapter.input(broken, "");
        assertEquals(PdfOperationException.Code.INVALID_PDF, assertThrows(PdfOperationException.class, () -> adapter.render(input.allPages())
                .sourceFont("DeclaredFont", font()).policy(mapping(ProcessingPolicy.Action.IGNORE)).prepare()).code());
        byte[] missing = source("Helvetica", false, "\\000");
        assertThrows(PdfOperationException.class, () -> adapter.render(adapter.input(missing, "").allPages()).sourceFont("Helvetica", font())
                .policy(mapping(ProcessingPolicy.Action.IGNORE)).prepare());
        try (var document = Loader.loadPDF(source("DeclaredFont", true, "A"))) {
            dictionary(document.getPage(0)).getCOSDictionary(COSName.FONT_DESC).setItem(COSName.FONT_FILE2, new COSString("not a program")); broken = save(document);
        }
        var damaged = adapter.input(broken, "");
        assertEquals(PdfOperationException.Code.INVALID_PDF, assertThrows(PdfOperationException.class, () -> adapter.render(damaged.allPages())
                .sourceFont("DeclaredFont", font()).policy(mapping(ProcessingPolicy.Action.IGNORE)).prepare()).code());
    }

    @Test void mappedRedactionDeletesPixelsAndCarriesNoSourceFontOrText() throws Exception {
        var adapter = new PdfAdapter(); var input = adapter.input(source("Helvetica", false, "SECRET"), "");
        var policy = ProcessingPolicy.builder().feature("RENDER_FONT_MAPPING", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
                .feature("RASTERIZATION", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE).build();
        var prepared = adapter.redact(input).sourceFont("Helvetica", font()).dpi(72).fill(0xFFFFFF).area(0, 0, 10, 200, 45).policy(policy).prepare();
        var output = new ByteArrayOutputStream(); prepared.to(output);
        try (var document = Loader.loadPDF(output.toByteArray())) {
            assertFalse(document.getPage(0).getResources().getFontNames().iterator().hasNext());
            assertEquals("", new org.apache.pdfbox.text.PDFTextStripper().getText(document).strip());
        }
        var rendered = adapter.render(adapter.input(output.toByteArray(), "").allPages()).dpi(72).prepare(); var image = image(rendered.images().getFirst().bytes());
        assertEquals(0, black(image, 0, 0, 200, 55)); assertTrue(black(image, 0, 60, 200, 40) > 20);
        assertTrue(prepared.preview().issues().stream().anyMatch(issue -> issue.feature().equals("RENDER_FONT_MAPPING")));
    }

    private static ProcessingPolicy mapping(ProcessingPolicy.Action action) {
        return ProcessingPolicy.builder().feature("RENDER_FONT_MAPPING", action, ProcessingPolicy.Treatment.DEGRADE).build();
    }
    private static PdfFont font() throws Exception { return PdfFont.of(fontBytes()); }
    private static byte[] fontBytes() throws Exception { return Files.readAllBytes(Path.of("../assets/fonts/LiberationSans-Regular.ttf")); }
    private static BufferedImage image(byte[] bytes) throws Exception { return ImageIO.read(new ByteArrayInputStream(bytes)); }
    private static long black(BufferedImage image, int x, int y, int width, int height) {
        long count = 0;
        for (int row = y; row < y + height; row++) { for (int column = x; column < x + width; column++) {
            if ((image.getRGB(column, row) & 0xFFFFFF) < 0x808080) { count++; }
        } }
        return count;
    }
    private static byte[] source(String name, boolean declared, String text) throws Exception {
        try (var document = new PDDocument()) {
            var page = new PDPage(new PDRectangle(200, 100)); document.addPage(page); page.setResources(new PDResources());
            var font = new COSDictionary(); font.setItem(COSName.TYPE, COSName.FONT); font.setName(COSName.SUBTYPE, declared ? "TrueType" : "Type1"); font.setName(COSName.BASE_FONT, name);
            if (declared) {
                font.setItem(COSName.ENCODING, COSName.WIN_ANSI_ENCODING); font.setInt(COSName.FIRST_CHAR, 32); font.setInt(COSName.LAST_CHAR, 126);
                var widths = new COSArray(); for (int code = 32; code <= 126; code++) { widths.add(new COSFloat(900)); } font.setItem(COSName.WIDTHS, widths);
                var descriptor = new COSDictionary(); descriptor.setName(COSName.TYPE, "FontDescriptor"); descriptor.setName(COSName.FONT_NAME, name); descriptor.setInt(COSName.FLAGS, 32);
                font.setItem(COSName.FONT_DESC, descriptor);
            }
            var fonts = new COSDictionary(); fonts.setItem(COSName.getPDFName("F1"), font); page.getResources().getCOSObject().setItem(COSName.FONT, fonts);
            var content = new PDStream(document); page.setContents(content);
            try (var stream = content.createOutputStream()) { stream.write(("BT /F1 20 Tf 10 60 Td (" + text + ") Tj ET\nBT /F1 15 Tf 10 20 Td (OUTSIDE) Tj ET\n").getBytes(StandardCharsets.US_ASCII)); }
            return save(document);
        }
    }
    private static COSDictionary dictionary(PDPage page) { return page.getResources().getCOSObject().getCOSDictionary(COSName.FONT).getCOSDictionary(COSName.getPDFName("F1")); }
    private static byte[] save(PDDocument document) throws Exception { var output = new ByteArrayOutputStream(); document.save(output); return output.toByteArray(); }
}
