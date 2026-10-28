// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfExecutionGuardTest {
    @Test void inheritedFormResourcesCannotHideActualRecursionFromReadingOrRendering() throws Exception {
        for (boolean transparency : List.of(false, true)) {
            byte[] source = forms(2, true, transparency, false); var adapter = new PdfAdapter(); var input = adapter.input(source, "");
            assertEquals(PdfOperationException.Code.INVALID_PDF, assertThrows(PdfOperationException.class, () -> adapter.render(input.allPages()).prepare()).code());
            assertEquals(PdfOperationException.Code.INVALID_PDF, assertThrows(PdfOperationException.class, () -> adapter.read(input)).code());
        }
    }

    @Test void actualFormCallsAndGraphicsSavesShareTheDepthBudgetAndRepeatedCallsRemainValid() throws Exception {
        byte[] source = forms(12, false, false, true);
        var bounded = PdfAdapter.builder().limits(PdfLimits.DEFAULT.toBuilder().maxDepth(20).build()).build(); var input = bounded.input(source, "");
        assertEquals(PdfOperationException.Code.DEPTH_LIMIT, assertThrows(PdfOperationException.class, () -> bounded.render(input.allPages()).prepare()).code());
        assertEquals(PdfOperationException.Code.DEPTH_LIMIT, assertThrows(PdfOperationException.class, () -> bounded.read(input)).code());
        var enough = new PdfAdapter(); var repeated = enough.input(forms(2, false, true, false), "");
        assertEquals(1, enough.render(repeated.allPages()).prepare().images().size()); assertEquals(1, enough.read(repeated).pages().size());
    }

    @Test void type3ProceduresCannotRecursivelyShowTheirOwnGlyphThroughInheritedResources() throws Exception {
        try (var document = document()) {
            var font = new COSDictionary(); font.setName(COSName.TYPE, "Font"); font.setName(COSName.SUBTYPE, "Type3");
            font.setItem(COSName.FONT_BBOX, numbers(0, 0, 600, 700)); font.setItem(COSName.FONT_MATRIX, numbers(.001f, 0, 0, .001f, 0, 0));
            font.setInt(COSName.FIRST_CHAR, 65); font.setInt(COSName.LAST_CHAR, 65); font.setItem(COSName.WIDTHS, numbers(600));
            var encoding = new COSDictionary(); encoding.setItem(COSName.DIFFERENCES, array(COSInteger.get(65), key("A"))); font.setItem(COSName.ENCODING, encoding);
            var procedures = new COSDictionary(); procedures.setItem(key("A"), stream(document, "600 0 0 0 600 700 d1 BT /F1 20 Tf <41> Tj ET")); font.setItem(COSName.CHAR_PROCS, procedures);
            var fonts = new COSDictionary(); fonts.setItem(key("F1"), font); document.getPage(0).getResources().getCOSObject().setItem(COSName.FONT, fonts);
            document.getPage(0).setContents(new PDStream(stream(document, "BT /F1 20 Tf 10 50 Td <41> Tj ET")));
            var adapter = new PdfAdapter(); var input = adapter.input(save(document), "");
            assertEquals(PdfOperationException.Code.INVALID_PDF, assertThrows(PdfOperationException.class, () -> adapter.render(input.allPages()).prepare()).code());
        }
    }

    @Test void hiddenTransparencyGroupsDoNotLeakExtractedTextOrHideFollowingPageText() throws Exception {
        try (var document = document()) {
            var page = document.getPage(0); var resources = page.getResources();
            var font = PDType0Font.load(document, new ByteArrayInputStream(Files.readAllBytes(Path.of("../assets/fonts/LiberationSans-Regular.ttf"))), false); resources.put(key("F1"), font);
            var off = new COSDictionary(); off.setName(COSName.TYPE, "OCG"); off.setString(COSName.NAME, "Hidden group");
            var config = new COSDictionary(); config.setName(key("BaseState"), "OFF");
            var layers = new COSDictionary(); layers.setItem(key("OCGs"), array(off)); layers.setItem(COSName.D, config); document.getDocumentCatalog().getCOSObject().setItem(key("OCProperties"), layers);
            var group = form(document, text(font, "HIDDEN", 70), true); group.setItem(COSName.OC, off);
            var objects = new COSDictionary(); objects.setItem(key("Hidden"), group); resources.getCOSObject().setItem(COSName.XOBJECT, objects);
            page.setContents(new PDStream(stream(document, "/Hidden Do " + text(font, "PUBLIC", 40))));
            var adapter = PdfAdapter.builder().allowPassiveStructures(true).build();
            String actual = adapter.read(adapter.input(save(document), "")).pages().getFirst().text();
            assertFalse(actual.contains("HIDDEN")); assertTrue(actual.contains("PUBLIC"));
        }
    }

    @Test void softMasksRequireActualTransparencyGroupDeclarations() throws Exception {
        try (var document = document()) {
            for (String kind : List.of("missing", "stream", "wrong", "valid")) {
                var resources = new PDResources(); var states = new COSDictionary(); resources.getCOSObject().setItem(COSName.EXT_G_STATE, states);
                var state = new COSDictionary(); states.setItem(key("GS"), state); var mask = new COSDictionary(); mask.setName(COSName.S, "Alpha"); state.setItem(COSName.SMASK, mask);
                var group = form(document, "0 0 50 50 re f", false); mask.setItem(COSName.G, group);
                if (!kind.equals("missing")) { var attributes = kind.equals("stream") ? stream(document, "") : new COSDictionary(); attributes.setName(COSName.S, kind.equals("wrong") ? "Other" : "Transparency"); group.setItem(COSName.GROUP, attributes); }
                var check = new PdfSemanticGraphicsState(PdfLimits.DEFAULT);
                if (kind.equals("valid")) { assertTrue(check.renderable(resources, key("GS"))); }
                else { assertEquals(PdfOperationException.Code.INVALID_PDF, assertThrows(PdfOperationException.class, () -> check.renderable(resources, key("GS"))).code(), kind); }
            }
        }
    }

    private static byte[] forms(int count, boolean cycle, boolean transparency, boolean saveGraphics) throws Exception {
        try (var document = document()) {
            var objects = new COSDictionary(); document.getPage(0).getResources().getCOSObject().setItem(COSName.XOBJECT, objects);
            for (int index = 0; index < count; index++) {
                String content = index + 1 < count ? "/F" + (index + 1) + " Do" : cycle ? "/F0 Do" : "0 0 10 10 re f";
                if (saveGraphics) { content = "q " + content + " Q"; } objects.setItem(key("F" + index), form(document, content, transparency));
            }
            document.getPage(0).setContents(new PDStream(stream(document, "/F0 Do /F0 Do"))); return save(document);
        }
    }
    private static PDDocument document() { var document = new PDDocument(); var page = new PDPage(new PDRectangle(100, 100)); page.setResources(new PDResources()); document.addPage(page); return document; }
    private static COSStream form(PDDocument document, String content, boolean transparency) throws Exception {
        var stream = stream(document, content); stream.setName(COSName.TYPE, "XObject"); stream.setName(COSName.SUBTYPE, "Form"); stream.setItem(COSName.BBOX, numbers(0, 0, 100, 100));
        if (transparency) { var attributes = new COSDictionary(); attributes.setName(COSName.S, "Transparency"); stream.setItem(COSName.GROUP, attributes); } return stream;
    }
    private static COSStream stream(PDDocument document, String content) throws Exception { var stream = new PDStream(document); try (var output = stream.createOutputStream()) { output.write(content.getBytes(StandardCharsets.US_ASCII)); } return stream.getCOSObject(); }
    private static COSName key(String value) { return COSName.getPDFName(value); }
    private static COSArray array(COSBase... values) { var result = new COSArray(); for (var value : values) { result.add(value); } return result; }
    private static COSArray numbers(float... values) { var result = new COSArray(); for (float value : values) { result.add(new COSFloat(value)); } return result; }
    private static String text(PDType0Font font, String value, int y) throws Exception { return "BT /F1 10 Tf 10 " + y + " Td <" + HexFormat.of().formatHex(font.encode(value)) + "> Tj ET "; }
    private static byte[] save(PDDocument document) throws Exception { var output = new ByteArrayOutputStream(); document.save(output); return output.toByteArray(); }
}
