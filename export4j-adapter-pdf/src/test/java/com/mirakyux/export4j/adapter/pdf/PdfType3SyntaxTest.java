// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PdfType3SyntaxTest {
    @Test void validWidthDeclarationsRemainRenderable() throws Exception {
        var adapter = new PdfAdapter();
        for (String width : List.of("600 0 d0", "600 0 0 0 600 700 d1")) {
            var input = adapter.input(source(width + " 0 0 600 700 re f", "", false), "");
            assertEquals("A", adapter.read(input).pages().getFirst().text().strip());
            assertEquals(1, adapter.render(input.allPages()).prepare().images().size());
        }
    }

    @Test void malformedWidthProgramsFailBeforeAnyFontInstanceCanRepairThem() throws Exception {
        var adapter = new PdfAdapter();
        for (String program : List.of("", "0 0 600 700 re f", "600 0 d1", "600 0 (0) 0 600 700 d1",
                "600 0 100 0 0 700 d1", "600 0 d0 600 0 d0", "600 0 d0 600 0 0 0 600 700 d1")) {
            var input = adapter.input(source(program, "", false), "");
            assertEquals(PdfOperationException.Code.INVALID_PDF, assertThrows(PdfOperationException.class, () -> adapter.read(input)).code(), program);
            assertEquals(PdfOperationException.Code.INVALID_PDF, assertThrows(PdfOperationException.class, () -> adapter.render(input.allPages()).prepare()).code(), program);
        }
    }

    @Test void streamObjectsCannotMasqueradeAsCharProcsOrResourcesDictionaries() throws Exception {
        var adapter = new PdfAdapter();
        for (String dictionary : List.of("CharProcs", "Resources")) {
            var input = adapter.input(source("600 0 d0 0 0 600 700 re f", dictionary, false), "");
            assertEquals(PdfOperationException.Code.INVALID_PDF, assertThrows(PdfOperationException.class, () -> adapter.read(input)).code());
        }
    }

    @Test void glyphWidthOperatorsAreInvalidInOrdinaryPageContent() throws Exception {
        var adapter = new PdfAdapter(); var input = adapter.input(source("600 0 d0", "", true), "");
        assertEquals(PdfOperationException.Code.INVALID_PDF, assertThrows(PdfOperationException.class, () -> adapter.read(input)).code());
    }

    private static byte[] source(String program, String fake, boolean pageWidth) throws Exception {
        try (var document = new PDDocument()) {
            var page = new PDPage(new PDRectangle(100, 100)); document.addPage(page); var resources = new PDResources(); page.setResources(resources);
            var font = new COSDictionary(); font.setName(COSName.TYPE, "Font"); font.setName(COSName.SUBTYPE, "Type3");
            font.setItem(COSName.FONT_BBOX, numbers(0, 0, 600, 700)); font.setItem(COSName.FONT_MATRIX, numbers(.001f, 0, 0, .001f, 0, 0));
            font.setInt(COSName.FIRST_CHAR, 65); font.setInt(COSName.LAST_CHAR, 65); font.setItem(COSName.WIDTHS, numbers(600));
            var encoding = new COSDictionary(); var differences = new COSArray(); differences.add(COSInteger.get(65)); differences.add(COSName.getPDFName("A"));
            encoding.setItem(COSName.DIFFERENCES, differences); font.setItem(COSName.ENCODING, encoding);
            COSDictionary procedures = fake.equals("CharProcs") ? stream(document, "").getCOSObject() : new COSDictionary();
            procedures.setItem(COSName.getPDFName("A"), stream(document, program)); font.setItem(COSName.CHAR_PROCS, procedures);
            if (fake.equals("Resources")) { font.setItem(COSName.RESOURCES, stream(document, "")); }
            var fonts = new COSDictionary(); fonts.setItem(COSName.getPDFName("F1"), font); resources.getCOSObject().setItem(COSName.FONT, fonts);
            page.setContents(stream(document, (pageWidth ? "600 0 d0 " : "") + "BT /F1 20 Tf 10 50 Td <41> Tj ET"));
            var output = new ByteArrayOutputStream(); document.save(output); return output.toByteArray();
        }
    }
    private static PDStream stream(PDDocument document, String content) throws Exception { var value = new PDStream(document); try (var output = value.createOutputStream()) { output.write(content.getBytes(StandardCharsets.US_ASCII)); } return value; }
    private static COSArray numbers(float... values) { var result = new COSArray(); for (float value : values) { result.add(new COSFloat(value)); } return result; }
}
