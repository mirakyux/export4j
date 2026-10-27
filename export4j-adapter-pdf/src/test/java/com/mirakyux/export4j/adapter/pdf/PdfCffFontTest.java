// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import org.apache.fontbox.ttf.OTFParser;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfCffFontTest {
    @TempDir Path directory;

    @Test void rawAndOpenTypeCffRenderActualEmbeddedOutlinesAndRecoverUnicode() throws Exception {
        for (boolean openType : List.of(false, true)) {
            var adapter = new PdfAdapter(); var input = adapter.input(source(openType, false), "");
            var rendered = adapter.render(input.allPages()).dpi(144).prepare();
            var image = ImageIO.read(new ByteArrayInputStream(rendered.images().getFirst().bytes()));
            int ink = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) { if ((image.getRGB(x, y) & 0xFFFFFF) == 0) { ink++; } }
            }
            assertTrue(ink > 100, "embedded CFF glyphs must be painted: " + ink);
            var recovered = adapter.importDocument(input).policy(PdfSemanticImporterTest.POLICY).prepare();
            assertEquals("SECRET KEEP", ((Paragraph) recovered.document().sections().getFirst().blocks().getFirst()).text());
            assertFalse(recovered.preview().issues().stream().anyMatch(issue -> issue.feature().equals("PDF_STRUCTURE")));
        }
    }

    @Test void cidCffUsesItsOwnCidCharsetAndUnicodeMapping() throws Exception {
        var adapter = new PdfAdapter(); var input = adapter.input(source(false, true), "");
        assertEquals(1, adapter.render(input.allPages()).prepare().images().size());
        var recovered = adapter.importDocument(input).policy(PdfSemanticImporterTest.POLICY).prepare();
        assertEquals("AB", ((Paragraph) recovered.document().sections().getFirst().blocks().getFirst()).text());
    }

    @Test void replacingCffSourceTextPreservesUnmatchedGlyphPositionsAndSourceFont() throws Exception {
        for (boolean openType : List.of(false, true)) {
            var adapter = new PdfAdapter(); byte[] source = source(openType, false);
            var prepared = adapter.editText(adapter.input(source, "")).replace(0, "SECRET", "HI", PdfTextEditorTest.font())
                    .policy(ProcessingPolicy.builder().feature("TEXT_FONT", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE).build()).prepare();
            var output = new ByteArrayOutputStream(); prepared.to(output);
            assertEquals(PdfTextEditorTest.positions(source).subList(6, 11), PdfTextEditorTest.positions(output.toByteArray()).subList(2, 7));
            try (var pdf = Loader.loadPDF(output.toByteArray())) {
                String text = new PDFTextStripper().getText(pdf);
                assertTrue(text.contains("HI")); assertTrue(text.contains("KEEP")); assertFalse(text.contains("SECRET"));
                assertTrue(pdf.getPage(0).getResources().getFont(COSName.getPDFName("Cff")).isEmbedded());
            }
            assertEquals(1, prepared.preview().issues().size());
        }
    }

    @Test void damagedOrMismatchedProgramsCannotTriggerFallbackOrPolicyBypass() throws Exception {
        var adapter = new PdfAdapter();
        var permissive = ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.SKIP_BLOCK).build();
        var importPolicy = ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DISCARD)
                .feature("PDF_LAYOUT", ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DEGRADE)
                .feature("PDF_FONT_MAPPING", ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DEGRADE)
                .feature("PDF_STRUCTURE", ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.SKIP_BLOCK).build();
        for (int variant = 0; variant < 4; variant++) {
            byte[] bytes;
            try (var pdf = Loader.loadPDF(source(false, false))) {
                var font = pdf.getPage(0).getResources().getCOSObject().getCOSDictionary(COSName.FONT).getCOSDictionary(COSName.getPDFName("Cff"));
                var descriptor = font.getCOSDictionary(COSName.FONT_DESC);
                var stream = descriptor.getCOSStream(COSName.FONT_FILE3);
                if (variant == 0) { try (var output = stream.createOutputStream()) { output.write(new byte[] {1, 0, 4, 4, 0}); } }
                else if (variant == 1) { stream.setName(COSName.SUBTYPE, "OpenType"); }
                else if (variant == 2) { descriptor.setItem(COSName.FONT_FILE2, stream); }
                else {
                    try (var output = stream.createOutputStream()) { output.write(cidProgram()); }
                }
                bytes = save(pdf);
            }
            var input = adapter.input(bytes, "");
            assertThrows(java.io.IOException.class, () -> adapter.render(input.allPages()).policy(permissive).prepare());
            assertThrows(java.io.IOException.class, () -> adapter.importDocument(input).policy(importPolicy).prepare());
        }
    }

    @Test void sharedProgramCannotBeReusedUnderAConflictingFontKind() throws Exception {
        try (var pdf = Loader.loadPDF(source(false, false))) {
            var page = pdf.getPage(0); var fonts = page.getResources().getCOSObject().getCOSDictionary(COSName.FONT);
            var original = fonts.getCOSDictionary(COSName.getPDFName("Cff"));
            var duplicate = new COSDictionary(original); duplicate.setName(COSName.SUBTYPE, "TrueType");
            var descriptor = new COSDictionary(original.getCOSDictionary(COSName.FONT_DESC));
            descriptor.setItem(COSName.FONT_FILE2, descriptor.getDictionaryObject(COSName.FONT_FILE3)); descriptor.removeItem(COSName.FONT_FILE3);
            duplicate.setItem(COSName.FONT_DESC, descriptor); fonts.setItem(COSName.getPDFName("Conflicting"), duplicate);
            assertThrows(java.io.IOException.class, () -> new PdfRasterResources().check(page));
        }
    }

    @Test void missingCffGlyphCannotBecomeRecoveredUnicodeOrASubstituteGlyph() throws Exception {
        byte[] bytes;
        try (var pdf = Loader.loadPDF(source(false, false))) {
            var fonts = pdf.getPage(0).getResources().getCOSObject().getCOSDictionary(COSName.FONT);
            var encoding = new COSDictionary(); encoding.setItem(COSName.BASE_ENCODING, COSName.WIN_ANSI_ENCODING);
            var differences = new COSArray(); differences.add(org.apache.pdfbox.cos.COSInteger.get(83)); differences.add(COSName.getPDFName("missing.export4j"));
            encoding.setItem(COSName.DIFFERENCES, differences); fonts.getCOSDictionary(COSName.getPDFName("Cff")).setItem(COSName.ENCODING, encoding);
            bytes = save(pdf);
        }
        var adapter = new PdfAdapter(); var input = adapter.input(bytes, "");
        assertThrows(java.io.IOException.class, () -> adapter.render(input.allPages()).prepare());
        assertThrows(java.io.IOException.class, () -> adapter.importDocument(input).policy(PdfSemanticImporterTest.POLICY).prepare());
        assertThrows(java.io.IOException.class, () -> adapter.editText(input).replace(0, "KEEP", "I", PdfTextEditorTest.font()).prepare());
    }

    @Test void anOpenTypeVariableTableCannotEnterTheStaticCffSubset() throws Exception {
        byte[] bytes;
        try (var pdf = Loader.loadPDF(source(true, false))) {
            var font = pdf.getPage(0).getResources().getCOSObject().getCOSDictionary(COSName.FONT).getCOSDictionary(COSName.getPDFName("Cff"));
            var stream = font.getCOSDictionary(COSName.FONT_DESC).getCOSStream(COSName.FONT_FILE3);
            byte[] program; try (var input = stream.createInputStream()) { program = input.readAllBytes(); }
            var buffer = java.nio.ByteBuffer.wrap(program); int count = buffer.getShort(4) & 0xFFFF; boolean replaced = false;
            for (int index = 0; index < count; index++) {
                int offset = 12 + index * 16;
                if (buffer.getInt(offset) == 0x47535542) { buffer.putInt(offset, 0x66766172); replaced = true; break; }
            }
            assertTrue(replaced, "fixture must have a GSUB table to mark as variable");
            try (var output = stream.createOutputStream()) { output.write(program); } bytes = save(pdf);
        }
        var adapter = new PdfAdapter(); var input = adapter.input(bytes, "");
        assertThrows(java.io.IOException.class, () -> adapter.render(input.allPages()).prepare());
        assertThrows(java.io.IOException.class, () -> adapter.importDocument(input).policy(PdfSemanticImporterTest.POLICY).prepare());
    }

    @Test void cffDecodedAndGlyphBudgetsAndCancelledPublicationRemainEnforced() throws Exception {
        var adapter = new PdfAdapter(); var input = adapter.input(source(true, false), "");
        var streamBound = PdfAdapter.builder().limits(PdfLimits.DEFAULT.toBuilder().maxDecodedStreamBytes(1000).build()).build();
        assertEquals(PdfOperationException.Code.DECODED_STREAM_LIMIT,
                assertThrows(PdfOperationException.class, () -> streamBound.render(input.allPages()).prepare()).code());
        var textBound = PdfAdapter.builder().limits(PdfLimits.DEFAULT.toBuilder().maxTextCharacters(2).build()).build();
        assertEquals(PdfOperationException.Code.TEXT_LIMIT,
                assertThrows(PdfOperationException.class, () -> textBound.render(input.allPages()).prepare()).code());
        var cancelled = new AtomicBoolean();
        var prepared = adapter.render(input.allPages()).execution(ExecutionOptions.builder().cancelled(cancelled::get).build()).prepare();
        Path target = directory.resolve("old.zip"); Files.writeString(target, "OLD"); cancelled.set(true);
        assertThrows(ExecutionException.class, () -> prepared.toZip(target)); assertEquals("OLD", Files.readString(target));
    }

    static byte[] source(boolean openType, boolean cid) throws Exception {
        try (var pdf = new PDDocument(); var data = new RandomAccessReadBuffer(Files.readAllBytes(Path.of("../assets/fonts/SourceSans3-Regular.otf")));
                var otf = new OTFParser().parse(data)) {
            var page = new PDPage(new PDRectangle(240, 120)); pdf.addPage(page); page.setResources(new PDResources());
            var program = new PDStream(pdf);
            try (var output = program.createOutputStream(COSName.FLATE_DECODE)) {
                output.write(cid ? cidProgram() : openType ? Files.readAllBytes(Path.of("../assets/fonts/SourceSans3-Regular.otf")) : otf.getTableBytes(otf.getCFF()));
            }
            program.getCOSObject().setName(COSName.SUBTYPE, cid ? "CIDFontType0C" : openType ? "OpenType" : "Type1C");
            var descriptor = new COSDictionary(); descriptor.setItem(COSName.TYPE, COSName.FONT_DESC);
            descriptor.setName(COSName.FONT_NAME, cid ? "Export4jCID" : "SourceSans3-Regular"); descriptor.setInt(COSName.FLAGS, cid ? 4 : 32);
            descriptor.setItem(COSName.FONT_BBOX, new PDRectangle(-200, -300, 1400, 1300).getCOSArray());
            descriptor.setInt(COSName.ASCENT, 900); descriptor.setInt(COSName.DESCENT, -250); descriptor.setInt(COSName.CAP_HEIGHT, 700);
            descriptor.setInt(COSName.ITALIC_ANGLE, 0); descriptor.setInt(COSName.STEM_V, 80); descriptor.setItem(COSName.FONT_FILE3, program);
            var font = new COSDictionary(); font.setItem(COSName.TYPE, COSName.FONT);
            font.setName(COSName.SUBTYPE, cid ? "Type0" : "Type1"); font.setName(COSName.BASE_FONT, cid ? "Export4jCID" : "SourceSans3-Regular");
            if (cid) {
                font.setItem(COSName.ENCODING, COSName.IDENTITY_H);
                var child = new COSDictionary(); child.setItem(COSName.TYPE, COSName.FONT); child.setName(COSName.SUBTYPE, "CIDFontType0");
                child.setName(COSName.BASE_FONT, "Export4jCID"); child.setItem(COSName.FONT_DESC, descriptor); child.setInt(COSName.DW, 600);
                var system = new COSDictionary(); system.setItem(COSName.REGISTRY, new COSString("Adobe")); system.setItem(COSName.ORDERING, new COSString("Identity")); system.setInt(COSName.SUPPLEMENT, 0);
                child.setItem(COSName.CIDSYSTEMINFO, system); var children = new COSArray(); children.add(child); font.setItem(COSName.DESCENDANT_FONTS, children);
                var unicode = new PDStream(pdf);
                try (var output = unicode.createOutputStream()) {
                    output.write(("/CIDInit /ProcSet findresource begin 12 dict begin begincmap /CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def "
                            + "/CMapName /Export4jUnicode def /CMapType 2 def 1 begincodespacerange <0000> <FFFF> endcodespacerange "
                            + "2 beginbfchar <0001> <0041> <0002> <0042> endbfchar endcmap CMapName currentdict /CMap defineresource pop end end").getBytes(StandardCharsets.US_ASCII));
                }
                font.setItem(COSName.TO_UNICODE, unicode);
            } else {
                font.setItem(COSName.FONT_DESC, descriptor); font.setItem(COSName.ENCODING, COSName.WIN_ANSI_ENCODING);
                font.setInt(COSName.FIRST_CHAR, 32); font.setInt(COSName.LAST_CHAR, 126); var widths = new COSArray();
                for (int code = 32; code <= 126; code++) { widths.add(new COSFloat(otf.getAdvanceWidth(otf.getUnicodeCmapLookup().getGlyphId(code)) * 1000f / otf.getUnitsPerEm())); }
                font.setItem(COSName.WIDTHS, widths);
            }
            var fonts = new COSDictionary(); fonts.setItem(COSName.getPDFName("Cff"), font); page.getResources().getCOSObject().setItem(COSName.FONT, fonts);
            var content = new PDStream(pdf); page.setContents(content);
            try (var output = content.createOutputStream()) { output.write(("BT /Cff 14 Tf 20 80 Td " + (cid ? "<00010002>" : "(SECRET KEEP)") + " Tj ET").getBytes(StandardCharsets.US_ASCII)); }
            return save(pdf);
        }
    }

    // A small original CID-keyed CFF program with two geometric glyphs. No external font asset.
    private static byte[] cidProgram() throws Exception {
        byte[] name = index(List.of("Export4jCID".getBytes(StandardCharsets.US_ASCII)));
        byte[] strings = index(List.of("Adobe".getBytes(StandardCharsets.US_ASCII), "Identity".getBytes(StandardCharsets.US_ASCII)));
        byte[] glyphs = index(List.of(new byte[] {14}, new byte[] {28, 2, 88, (byte) 189, (byte) 139, 21, 28, 1, (byte) 244, (byte) 139, (byte) 139, 28, 2, (byte) 188, 28, (byte) 254, 12, (byte) 139, 5, 14},
                new byte[] {28, 2, 88, (byte) 189, (byte) 139, 21, 28, 0, (byte) 250, 28, 2, (byte) 188, 28, 0, (byte) 250, 28, (byte) 253, 68, 5, 14}));
        int charset = 4 + name.length + 61 + strings.length + 2;
        int charStrings = charset + 5; int fdSelect = charStrings + glyphs.length; int fdArray = fdSelect + 4;
        var dictBytes = new ByteArrayOutputStream(); var dict = new DataOutputStream(dictBytes);
        for (int number : new int[] {391, 392, 0}) { integer(dict, number); } dict.write(new byte[] {12, 30});
        integer(dict, 3); dict.write(new byte[] {12, 34}); integer(dict, charset); dict.writeByte(15);
        integer(dict, charStrings); dict.writeByte(17); integer(dict, fdArray); dict.write(new byte[] {12, 36}); integer(dict, fdSelect); dict.write(new byte[] {12, 37});
        var result = new ByteArrayOutputStream(); result.write(new byte[] {1, 0, 4, 4}); result.write(name); result.write(index(List.of(dictBytes.toByteArray())));
        result.write(strings); result.write(new byte[] {0, 0}); result.write(new byte[] {0, 0, 1, 0, 2}); result.write(glyphs); result.write(new byte[] {0, 0, 0, 0});
        var privateBytes = new ByteArrayOutputStream(); var privateDict = new DataOutputStream(privateBytes);
        integer(privateDict, 0); integer(privateDict, fdArray + 22); privateDict.writeByte(18); result.write(index(List.of(privateBytes.toByteArray())));
        return result.toByteArray();
    }
    private static void integer(DataOutputStream output, int value) throws Exception { output.writeByte(29); output.writeInt(value); }
    private static byte[] index(List<byte[]> items) throws Exception {
        var bytes = new ByteArrayOutputStream(); var output = new DataOutputStream(bytes); output.writeShort(items.size()); output.writeByte(4);
        int offset = 1; output.writeInt(offset); for (var item : items) { offset += item.length; output.writeInt(offset); }
        for (var item : items) { output.write(item); } return bytes.toByteArray();
    }
    private static byte[] save(PDDocument document) throws Exception { var output = new ByteArrayOutputStream(); document.save(output); return output.toByteArray(); }
}
