// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingIssue;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
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
import org.apache.pdfbox.cos.COSInteger;
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
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfExtendedFontCompatibilityTest {
    @TempDir Path temporary;

    @Test void dictionaryDifferencesPaintNamedGlyphsAndKeepTheOriginalWidthsAndUnicode() throws Exception {
        byte[] source = simpleDifference(false); var adapter = new PdfAdapter();
        byte[] control = control(List.of("€", "A", "€"), List.of(10f, 28f, 46f));
        for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
            var prepared = adapter.render(adapter.input(source, "").allPages()).dpi(144).sourceFont("ExternalSimple", explicit()).policy(mapping(action)).prepare();
            assertArrayEquals(control, prepared.images().getFirst().bytes());
            assertEquals(action == ProcessingPolicy.Action.IGNORE, prepared.preview().warnings().isEmpty());
            assertEquals(1, prepared.preview().issues().size());
        }
        try (var document = Loader.loadPDF(source)) {
            var font = rawFont(document); var encoding = font.getDictionaryObject(COSName.ENCODING);
            var widths = font.getDictionaryObject(COSName.WIDTHS); var unicode = font.getDictionaryObject(COSName.TO_UNICODE);
            var mappings = sourceFonts(document, "ExternalSimple"); mappings.apply(document.getPage(0), 0); mappings.validate();
            assertSame(encoding, font.getDictionaryObject(COSName.ENCODING)); assertSame(widths, font.getDictionaryObject(COSName.WIDTHS));
            assertSame(unicode, font.getDictionaryObject(COSName.TO_UNICODE));
            var loaded = document.getPage(0).getResources().getFont(name("F1"));
            assertEquals("€", loaded.toUnicode(128)); assertEquals(900, loaded.getWidth(128)); mappings.requireGlyph(loaded, 128);
        }
    }

    @Test void identityAndCustomCmapsBindExplicitTrueTypeGlyphsToSourceCidsAndMetrics() throws Exception {
        var adapter = new PdfAdapter(); byte[] control = control(List.of("A", "B", "A"), List.of(10f, 28f, 35f));
        for (boolean custom : List.of(false, true)) {
            byte[] source = composite(custom, false, false); var input = adapter.input(source, "");
            assertThrows(ProcessingException.class, () -> adapter.render(input.allPages()).sourceFont("ExternalCID", explicit()).prepare());
            for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
                var prepared = adapter.render(input.allPages()).dpi(144).sourceFont("ExternalCID", explicit()).policy(mapping(action)).prepare();
                assertArrayEquals(control, prepared.images().getFirst().bytes());
                assertEquals("RENDER_FONT_MAPPING", prepared.preview().issues().getFirst().feature());
                assertEquals(action == ProcessingPolicy.Action.IGNORE, prepared.preview().warnings().isEmpty());
            }
            try (var document = Loader.loadPDF(source)) {
                var font = rawFont(document); var encoding = font.getDictionaryObject(COSName.ENCODING);
                var unicode = font.getDictionaryObject(COSName.TO_UNICODE); var child = (COSDictionary) font.getCOSArray(COSName.DESCENDANT_FONTS).getObject(0);
                var widths = child.getDictionaryObject(COSName.W); byte[] content; try (var stream = document.getPage(0).getContents()) { content = stream.readAllBytes(); }
                var mappings = sourceFonts(document, "ExternalCID"); mappings.apply(document.getPage(0), 0); mappings.validate();
                assertSame(encoding, font.getDictionaryObject(COSName.ENCODING)); assertSame(unicode, font.getDictionaryObject(COSName.TO_UNICODE));
                assertSame(widths, child.getDictionaryObject(COSName.W)); assertEquals(1000, child.getInt(COSName.DW));
                try (var stream = document.getPage(0).getContents()) { assertArrayEquals(content, stream.readAllBytes()); }
                var loaded = document.getPage(0).getResources().getFont(name("F1"));
                assertTrue(loaded.isEmbedded()); assertEquals("A", loaded.toUnicode(1)); assertEquals(900, loaded.getWidth(1));
                mappings.requireGlyph(loaded, 1); assertThrows(PdfOperationException.class, () -> mappings.requireGlyph(loaded, 3));
            }
            assertArrayEquals(source, input.bytes());
        }
    }

    @Test void ambiguousCompositeMappingsAndActualMissingGlyphsCannotBePaintedOrOverwriteTargets() throws Exception {
        var adapter = new PdfAdapter();
        for (boolean missing : List.of(false, true)) {
            byte[] source = composite(false, missing, !missing); var input = adapter.input(source, "");
            Path target = temporary.resolve("old-" + missing + ".png"); byte[] old = "old output".getBytes(StandardCharsets.UTF_8); Files.write(target, old);
            Class<? extends Exception> expected = missing ? PdfOperationException.class : ProcessingException.class;
            assertThrows(expected, () -> adapter.render(input.allPages()).sourceFont("ExternalCID", explicit()).policy(mapping(ProcessingPolicy.Action.IGNORE))
                    .prepare().images().getFirst().to(target));
            assertArrayEquals(old, Files.readAllBytes(target));
        }
    }

    @Test void sharedCidDescendantsRemainPrivateToEachExplicitRootAndItsUnicodeEvidence() throws Exception {
        byte[] source = sharedComposite(); var adapter = new PdfAdapter(); var input = adapter.input(source, "");
        var missing = assertThrows(ProcessingException.class, () -> adapter.render(input.allPages()).sourceFont("ExternalCID", explicit())
                .policy(mapping(ProcessingPolicy.Action.WARN)).prepare());
        assertEquals("RENDER_FONT", missing.issue().feature());
        byte[] expected;
        try (var document = new PDDocument()) {
            var page = new PDPage(new PDRectangle(200, 100)); document.addPage(page);
            var font = PDType0Font.load(document, new ByteArrayInputStream(fontBytes()), false);
            try (var output = new PDPageContentStream(document, page)) {
                output.beginText(); output.setFont(font, 20);
                for (int index = 0; index < 4; index++) { output.setTextMatrix(Matrix.getTranslateInstance(index % 2 == 0 ? 10 : 28, index < 2 ? 60 : 30)); output.showText(List.of("A", "B", "B", "A").get(index)); } output.endText();
            }
            expected = adapter.render(adapter.input(save(document), "").allPages()).dpi(144).prepare().images().getFirst().bytes();
        }
        var actual = adapter.render(input.allPages()).dpi(144).sourceFont("ExternalCID", explicit()).sourceFont("ExternalSecond", explicit())
                .policy(mapping(ProcessingPolicy.Action.WARN)).prepare();
        assertArrayEquals(expected, actual.images().getFirst().bytes()); assertEquals(2, actual.preview().issues().size()); assertArrayEquals(source, input.bytes());
        try (var document = Loader.loadPDF(source)) {
            var fonts = document.getPage(0).getResources().getCOSObject().getCOSDictionary(COSName.FONT); var first = fonts.getCOSDictionary(name("F1")); var second = fonts.getCOSDictionary(name("F2"));
            var shared = (COSDictionary) first.getCOSArray(COSName.DESCENDANT_FONTS).getObject(0); var widths = shared.getDictionaryObject(COSName.W);
            assertSame(shared, second.getCOSArray(COSName.DESCENDANT_FONTS).getObject(0));
            var firstEncoding = first.getDictionaryObject(COSName.ENCODING); var secondEncoding = second.getDictionaryObject(COSName.ENCODING);
            var firstUnicode = first.getDictionaryObject(COSName.TO_UNICODE); var secondUnicode = second.getDictionaryObject(COSName.TO_UNICODE);
            var mappings = new PdfSourceFonts(document, Map.of("ExternalCID", explicit(), "ExternalSecond", explicit()), PdfLimits.DEFAULT, mapping(ProcessingPolicy.Action.WARN), new ArrayList<>(), "PNG", source.length);
            mappings.apply(document.getPage(0), 0); mappings.validate();
            var a = (COSDictionary) first.getCOSArray(COSName.DESCENDANT_FONTS).getObject(0); var b = (COSDictionary) second.getCOSArray(COSName.DESCENDANT_FONTS).getObject(0);
            assertNotSame(shared, a); assertNotSame(shared, b); assertNotSame(a, b);
            assertFalse(shared.getCOSDictionary(COSName.FONT_DESC).containsKey(COSName.FONT_FILE2)); assertFalse(shared.containsKey(COSName.CID_TO_GID_MAP));
            assertSame(widths, a.getDictionaryObject(COSName.W)); assertSame(widths, b.getDictionaryObject(COSName.W)); assertSame(firstEncoding, first.getDictionaryObject(COSName.ENCODING)); assertSame(secondEncoding, second.getDictionaryObject(COSName.ENCODING));
            assertSame(firstUnicode, first.getDictionaryObject(COSName.TO_UNICODE)); assertSame(secondUnicode, second.getDictionaryObject(COSName.TO_UNICODE));
            var loadedA = document.getPage(0).getResources().getFont(name("F1")); var loadedB = document.getPage(0).getResources().getFont(name("F2"));
            assertEquals("A", loadedA.toUnicode(1)); assertEquals("B", loadedB.toUnicode(1)); assertEquals(900, loadedA.getWidth(1)); assertEquals(900, loadedB.getWidth(1));
        }
    }

    @Test void malformedDifferencesAndCidWidthsFailRatherThanUsingSystemFonts() throws Exception {
        var adapter = new PdfAdapter();
        var simple = adapter.input(simpleDifference(true), "");
        assertEquals(PdfOperationException.Code.INVALID_PDF, assertThrows(PdfOperationException.class,
                () -> adapter.render(simple.allPages()).sourceFont("ExternalSimple", explicit()).policy(mapping(ProcessingPolicy.Action.IGNORE)).prepare()).code());
        byte[] broken;
        try (var document = Loader.loadPDF(composite(false, false, false))) {
            var child = (COSDictionary) rawFont(document).getCOSArray(COSName.DESCENDANT_FONTS).getObject(0);
            var widths = new COSArray(); widths.add(COSInteger.ONE); widths.add(numbers(-1)); child.setItem(COSName.W, widths); broken = save(document);
        }
        var input = adapter.input(broken, "");
        assertEquals(PdfOperationException.Code.INVALID_PDF, assertThrows(PdfOperationException.class,
                () -> adapter.render(input.allPages()).sourceFont("ExternalCID", explicit()).policy(mapping(ProcessingPolicy.Action.IGNORE)).prepare()).code());
    }

    @Test void conflictingNumericCodesAcrossByteLengthsCannotSilentlySelectTheWrongGlyph() throws Exception {
        var adapter = new PdfAdapter(); var input = adapter.input(mixedLengthComposite(true), "");
        var failure = assertThrows(ProcessingException.class, () -> adapter.render(input.allPages()).sourceFont("ExternalCID", explicit()).policy(mapping(ProcessingPolicy.Action.WARN)).prepare());
        assertEquals("RENDER_FONT", failure.issue().feature());
        var skip = ProcessingPolicy.builder().feature("RENDER_FONT", ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.SKIP_BLOCK).build();
        var skipped = adapter.render(input.allPages()).sourceFont("ExternalCID", explicit()).policy(skip).prepare();
        assertTrue(skipped.images().isEmpty()); assertEquals(1, skipped.preview().issues().size());
        var valid = adapter.render(adapter.input(mixedLengthComposite(false), "").allPages()).sourceFont("ExternalCID", explicit()).policy(mapping(ProcessingPolicy.Action.WARN)).prepare();
        assertArrayEquals(control(List.of("A", "A"), List.of(10f, 28f)), valid.images().getFirst().bytes());
    }

    @Test void mappedCompositeFontsRemainSubjectToSourceProgramAndCmapWorkBudgets() throws Exception {
        byte[] source = composite(false, false, false); byte[] font = fontBytes();
        var adapter = PdfAdapter.builder().limits(PdfLimits.DEFAULT.toBuilder().maxTotalInputBytes(source.length + font.length - 1L).build()).build();
        assertEquals(PdfOperationException.Code.INPUT_LIMIT, assertThrows(PdfOperationException.class, () -> adapter.render(adapter.input(source, "").allPages())
                .sourceFont("ExternalCID", explicit()).policy(mapping(ProcessingPolicy.Action.IGNORE)).prepare()).code());
        var bounded = PdfAdapter.builder().limits(PdfLimits.DEFAULT.toBuilder().maxContentOperators(1000).build()).build();
        assertEquals(PdfOperationException.Code.OPERATOR_LIMIT, assertThrows(PdfOperationException.class, () -> bounded.render(bounded.input(source, "").allPages())
                .sourceFont("ExternalCID", explicit()).policy(mapping(ProcessingPolicy.Action.IGNORE)).prepare()).code());
    }

    @Test void malformedCmapCoverageAndPrefixAmbiguityCannotUseTheLibraryRepairPath() throws Exception {
        for (boolean prefix : List.of(false, true)) {
            var adapter = new PdfAdapter(); var input = adapter.input(invalidCmapSpace(prefix), "");
            for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
                Path target = temporary.resolve("malformed-codespace-" + prefix + "-" + action + ".png"); byte[] old = "old output".getBytes(StandardCharsets.UTF_8); Files.write(target, old);
                var failure = assertThrows(PdfOperationException.class, () -> adapter.render(input.allPages()).sourceFont("ExternalCID", explicit())
                        .policy(mapping(action)).prepare().images().getFirst().to(target));
                assertEquals(PdfOperationException.Code.INVALID_PDF, failure.code()); assertArrayEquals(old, Files.readAllBytes(target));
            }
        }
    }

    @Test void embeddedTraditionalType1PaintsItsOwnProgramAndDamagedProgramsFail() throws Exception {
        var adapter = new PdfAdapter(); byte[] source = type1();
        assertTrue(ink(adapter.render(adapter.input(source, "").allPages()).dpi(144).prepare().images().getFirst().bytes()) > 200);
        try (var document = Loader.loadPDF(source)) {
            var font = document.getPage(0).getResources().getFont(name("F1")); assertTrue(font.isEmbedded()); PdfRasterResources.requireGlyph(font, 65);
            var program = rawFont(document).getCOSDictionary(COSName.FONT_DESC).getCOSStream(COSName.FONT_FILE);
            program.setInt(COSName.LENGTH1, 1); var input = adapter.input(save(document), "");
            assertThrows(PdfOperationException.class, () -> adapter.render(input.allPages()).policy(mapping(ProcessingPolicy.Action.IGNORE)).prepare());
        }
    }

    @Test void staticType3PaintsItsLocalCharProcAndMissingProgramsFailEvenWhenIgnored() throws Exception {
        var adapter = new PdfAdapter(); byte[] source = type3(false, 1);
        assertTrue(ink(adapter.render(adapter.input(source, "").allPages()).dpi(144).prepare().images().getFirst().bytes()) > 200);
        try (var document = Loader.loadPDF(source)) {
            var font = document.getPage(0).getResources().getFont(name("F1")); PdfRasterResources.requireGlyph(font, 65);
            assertEquals("A", font.toUnicode(65)); assertThrows(PdfOperationException.class, () -> PdfRasterResources.requireGlyph(font, 66));
        }
        var broken = adapter.input(type3(true, 1), "");
        assertThrows(PdfOperationException.class, () -> adapter.render(broken.allPages()).policy(mapping(ProcessingPolicy.Action.IGNORE)).prepare());
    }

    @Test void repeatedType3ProgramsAreChargedForActualExecutedOperators() throws Exception {
        var adapter = PdfAdapter.builder().limits(PdfLimits.DEFAULT.toBuilder().maxContentOperators(1000).build()).build();
        var input = adapter.input(type3(false, 30), "");
        assertEquals(PdfOperationException.Code.OPERATOR_LIMIT, assertThrows(PdfOperationException.class, () -> adapter.render(input.allPages()).prepare()).code());
    }

    @Test void type3EncodingDeclarationsAreValidatedBeforeTheFontLibraryCanRepairThem() throws Exception {
        for (String kind : List.of("float", "range", "name-first", "overflow", "wrong-value", "stream", "type", "base-type", "unknown-name", "unknown-base")) {
            try (var document = Loader.loadPDF(type3(false, 1))) {
                var font = rawFont(document); var encoding = font.getCOSDictionary(COSName.ENCODING); var differences = encoding.getCOSArray(COSName.DIFFERENCES);
                switch (kind) {
                    case "float" -> differences.set(0, new COSFloat(65.5f));
                    case "range" -> differences.set(0, COSInteger.get(256));
                    case "name-first" -> differences.remove(0);
                    case "overflow" -> { differences.set(0, COSInteger.get(255)); differences.add(name("B")); }
                    case "wrong-value" -> differences.set(0, new COSString("65"));
                    case "stream" -> font.setItem(COSName.ENCODING, stream(document, ""));
                    case "type" -> encoding.setItem(COSName.TYPE, COSName.FONT);
                    case "base-type" -> encoding.setInt(COSName.BASE_ENCODING, 1);
                    case "unknown-name" -> font.setName(COSName.ENCODING, "PrivateEncoding");
                    case "unknown-base" -> encoding.setName(COSName.BASE_ENCODING, "PrivateEncoding");
                    default -> throw new AssertionError(kind);
                }
                var adapter = new PdfAdapter(); var input = adapter.input(save(document), "");
                var failure = assertThrows(PdfOperationException.class, () -> adapter.render(input.allPages()).policy(mapping(ProcessingPolicy.Action.IGNORE)).prepare(), kind);
                assertEquals(kind.startsWith("unknown") ? PdfOperationException.Code.UNSUPPORTED_FEATURE : PdfOperationException.Code.INVALID_PDF, failure.code(), kind);
            }
        }
    }

    private static PdfSourceFonts sourceFonts(PDDocument document, String name) throws Exception {
        return new PdfSourceFonts(document, Map.of(name, explicit()), PdfLimits.DEFAULT, mapping(ProcessingPolicy.Action.WARN), new ArrayList<ProcessingIssue>(), "PNG", 1);
    }
    private static ProcessingPolicy mapping(ProcessingPolicy.Action action) {
        return ProcessingPolicy.builder().feature("RENDER_FONT_MAPPING", action, ProcessingPolicy.Treatment.DEGRADE).build();
    }
    private static PdfFont explicit() throws Exception { return PdfFont.of(fontBytes()); }
    private static byte[] fontBytes() throws Exception { return Files.readAllBytes(Path.of("../assets/fonts/LiberationSans-Regular.ttf")); }
    private static byte[] control(List<String> text, List<Float> x) throws Exception {
        try (var document = new PDDocument()) {
            var page = new PDPage(new PDRectangle(200, 100)); document.addPage(page);
            var font = PDType0Font.load(document, new ByteArrayInputStream(fontBytes()), false);
            try (var output = new PDPageContentStream(document, page)) {
                output.beginText(); output.setFont(font, 20);
                for (int index = 0; index < text.size(); index++) { output.setTextMatrix(Matrix.getTranslateInstance(x.get(index), 60)); output.showText(text.get(index)); } output.endText();
            }
            var adapter = new PdfAdapter(); return adapter.render(adapter.input(save(document), "").allPages()).dpi(144).prepare().images().getFirst().bytes();
        }
    }
    private static byte[] simpleDifference(boolean malformed) throws Exception {
        try (var document = new PDDocument()) {
            var font = baseFont("TrueType", "ExternalSimple"); font.setItem(COSName.FONT_DESC, descriptor("ExternalSimple"));
            font.setInt(COSName.FIRST_CHAR, 0); font.setInt(COSName.LAST_CHAR, 255); var widths = new COSArray();
            for (int i = 0; i < 256; i++) { widths.add(new COSFloat(900)); } font.setItem(COSName.WIDTHS, widths);
            var encoding = new COSDictionary(); encoding.setItem(COSName.BASE_ENCODING, COSName.WIN_ANSI_ENCODING);
            var differences = new COSArray(); if (!malformed) { differences.add(COSInteger.get(128)); } differences.add(name("Euro")); encoding.setItem(COSName.DIFFERENCES, differences);
            font.setItem(COSName.ENCODING, encoding); font.setItem(COSName.TO_UNICODE, cmap(document, "<00> <FF>", "2 beginbfchar <80> <20AC> <41> <0041> endbfchar", true));
            page(document, font, "BT /F1 20 Tf 10 60 Td <804180> Tj ET"); return save(document);
        }
    }
    private static byte[] composite(boolean custom, boolean missing, boolean ambiguous) throws Exception {
        try (var document = new PDDocument()) {
            var font = baseFont("Type0", "ExternalCID"); var child = baseFont("CIDFontType2", "ExternalCID"); child.setItem(COSName.FONT_DESC, descriptor("ExternalCID"));
            var system = new COSDictionary(); system.setString(COSName.REGISTRY, "Adobe"); system.setString(COSName.ORDERING, "Identity"); system.setInt(COSName.SUPPLEMENT, 0); child.setItem(COSName.CIDSYSTEMINFO, system);
            child.setInt(COSName.DW, 1000); var widths = new COSArray();
            if (custom) { widths.add(COSInteger.get(7)); widths.add(numbers(900)); widths.add(COSInteger.get(9)); widths.add(numbers(350)); }
            else { widths.add(COSInteger.ONE); widths.add(numbers(900, 350)); } child.setItem(COSName.W, widths);
            var children = new COSArray(); children.add(child); font.setItem(COSName.DESCENDANT_FONTS, children);
            if (custom) { font.setItem(COSName.ENCODING, cmap(document, "<00> <FF>", "2 begincidchar <01> 7 <02> 9 endcidchar", false)); }
            else { font.setItem(COSName.ENCODING, name("Identity-H")); }
            String first = ambiguous ? "00410042" : missing ? "DBFFDFFF" : "0041";
            font.setItem(COSName.TO_UNICODE, cmap(document, custom ? "<00> <FF>" : "<0000> <FFFF>", custom
                    ? "2 beginbfchar <01> <" + first + "> <02> <0042> endbfchar" : "2 beginbfchar <0001> <" + first + "> <0002> <0042> endbfchar", true));
            page(document, font, "BT /F1 20 Tf 10 60 Td <" + (custom ? "010201" : "000100020001") + "> Tj ET"); return save(document);
        }
    }
    private static byte[] sharedComposite() throws Exception {
        try (var document = Loader.loadPDF(composite(false, false, false))) {
            var original = rawFont(document); var second = new COSDictionary(original); second.setName(COSName.BASE_FONT, "ExternalSecond");
            second.setItem(COSName.ENCODING, cmap(document, "<00> <FF>", "2 begincidchar <01> 1 <02> 2 endcidchar", false));
            second.setItem(COSName.TO_UNICODE, cmap(document, "<00> <FF>", "2 beginbfchar <01> <0042> <02> <0041> endbfchar", true));
            document.getPage(0).getResources().getCOSObject().getCOSDictionary(COSName.FONT).setItem(name("F2"), second);
            document.getPage(0).setContents(stream(document, "BT /F1 20 Tf 10 60 Td <00010002> Tj ET BT /F2 20 Tf 10 30 Td <0102> Tj ET")); return save(document);
        }
    }
    private static byte[] mixedLengthComposite(boolean conflicting) throws Exception {
        try (var document = Loader.loadPDF(composite(true, false, false))) {
            var font = rawFont(document); String spaces = "<01> <01> <0001> <0001>";
            font.setItem(COSName.ENCODING, cmap(document, spaces, "2 begincidchar <01> 7 <0001> " + (conflicting ? "9" : "7") + " endcidchar", false, 2));
            font.setItem(COSName.TO_UNICODE, cmap(document, spaces, "2 beginbfchar <01> <0041> <0001> <" + (conflicting ? "0042" : "0041") + "> endbfchar", true, 2));
            document.getPage(0).setContents(stream(document, "BT /F1 20 Tf 10 60 Td <010001> Tj ET")); return save(document);
        }
    }
    private static byte[] invalidCmapSpace(boolean prefix) throws Exception {
        try (var document = Loader.loadPDF(composite(true, false, false))) {
            var font = rawFont(document); String spaces = prefix ? "<01> <01> <0101> <0101>" : "<01> <01>";
            font.setItem(COSName.ENCODING, cmap(document, spaces, prefix ? "2 begincidchar <01> 7 <0101> 9 endcidchar" : "1 begincidchar <02> 9 endcidchar", false, prefix ? 2 : 1));
            font.setItem(COSName.TO_UNICODE, cmap(document, spaces, prefix ? "2 beginbfchar <01> <0041> <0101> <0042> endbfchar" : "1 beginbfchar <02> <0042> endbfchar", true, prefix ? 2 : 1));
            document.getPage(0).setContents(stream(document, "BT /F1 20 Tf 10 60 Td <" + (prefix ? "010101" : "02") + "> Tj ET")); return save(document);
        }
    }
    private static byte[] type3(boolean missing, int repetitions) throws Exception {
        try (var document = new PDDocument()) {
            var font = baseFont("Type3", "Export4jStaticFixture"); font.setInt(COSName.FIRST_CHAR, 65); font.setInt(COSName.LAST_CHAR, 65);
            font.setItem(COSName.WIDTHS, numbers(600)); font.setItem(COSName.FONT_MATRIX, numbers(.001f, 0, 0, .001f, 0, 0)); font.setItem(COSName.FONT_BBOX, numbers(0, 0, 600, 700));
            var differences = new COSArray(); differences.add(COSInteger.get(65)); differences.add(name("A")); var encoding = new COSDictionary(); encoding.setItem(COSName.DIFFERENCES, differences); font.setItem(COSName.ENCODING, encoding);
            var procs = new COSDictionary(); procs.setItem(name(missing ? "Other" : "A"), stream(document, "600 0 0 0 600 700 d1\n" + (repetitions > 1 ? "q Q\n".repeat(50) : "") + "50 0 500 700 re f\n")); font.setItem(COSName.CHAR_PROCS, procs);
            font.setItem(COSName.RESOURCES, new COSDictionary()); font.setItem(COSName.TO_UNICODE, cmap(document, "<00> <FF>", "1 beginbfchar <41> <0041> endbfchar", true));
            page(document, font, "BT /F1 20 Tf 10 60 Td <" + "41".repeat(repetitions) + "> Tj ET"); return save(document);
        }
    }
    private static byte[] type1() throws Exception {
        try (var document = new PDDocument()) {
            String clear = "%!PS-AdobeFont-1.0: Export4jFixture 1.0\n8 dict begin\n/FontName /Export4jFixture def\n/FontType 1 def\n/PaintType 0 def\n/FontMatrix [0.001 0 0 0.001 0 0] def\n/FontBBox [0 0 600 700] def\n/Encoding StandardEncoding def\ncurrentdict end\ncurrentfile eexec\n";
            var privateData = new ByteArrayOutputStream();
            privateData.write(new byte[4]);
            privateData.write("dup /Private 8 dict dup begin /lenIV -1 def /RD {string currentfile exch readstring pop} executeonly def /ND {noaccess def} executeonly def /NP {noaccess put} executeonly def /Subrs 0 array def end readonly put dup /CharStrings 2 dict dup begin\n".getBytes(StandardCharsets.US_ASCII));
            byte[] notdef = charstring(0, 600, "hsbw", "endchar"); byte[] a = charstring(0, 600, "hsbw", 50, 0, "rmoveto", 0, 700, "rlineto", 500, 0, "rlineto", 0, -700, "rlineto", -500, 0, "rlineto", "closepath", "endchar");
            privateData.write(("/.notdef " + notdef.length + " RD ").getBytes(StandardCharsets.US_ASCII)); privateData.write(notdef); privateData.write(" ND\n".getBytes(StandardCharsets.US_ASCII));
            privateData.write(("/A " + a.length + " RD ").getBytes(StandardCharsets.US_ASCII)); privateData.write(a); privateData.write(" ND\nend readonly put dup /FontName get exch definefont pop mark currentfile closefile\n".getBytes(StandardCharsets.US_ASCII));
            byte[] encrypted = privateData.toByteArray(); int state = 55665;
            for (int index = 0; index < encrypted.length; index++) { int cipher = (encrypted[index] & 255) ^ state >>> 8; encrypted[index] = (byte) cipher; state = (cipher + state) * 52845 + 22719 & 65535; }
            var program = new PDStream(document); try (var output = program.createOutputStream()) { output.write(clear.getBytes(StandardCharsets.US_ASCII)); output.write(encrypted); }
            program.getCOSObject().setInt(COSName.LENGTH1, clear.length()); program.getCOSObject().setInt(COSName.LENGTH2, encrypted.length); program.getCOSObject().setInt(COSName.LENGTH3, 0);
            var font = baseFont("Type1", "Export4jFixture"); var descriptor = descriptor("Export4jFixture"); descriptor.setItem(COSName.FONT_FILE, program); font.setItem(COSName.FONT_DESC, descriptor);
            font.setItem(COSName.ENCODING, COSName.WIN_ANSI_ENCODING); font.setInt(COSName.FIRST_CHAR, 65); font.setInt(COSName.LAST_CHAR, 65); font.setItem(COSName.WIDTHS, numbers(600));
            page(document, font, "BT /F1 20 Tf 10 60 Td <41> Tj ET"); return save(document);
        }
    }
    private static byte[] charstring(Object... values) {
        var output = new ByteArrayOutputStream();
        for (Object value : values) {
            if (value instanceof Integer number) {
                if (number >= -107 && number <= 107) { output.write(number + 139); }
                else if (number >= 108 && number <= 1131) { output.write((number - 108) / 256 + 247); output.write((number - 108) % 256); }
                else if (number <= -108 && number >= -1131) { output.write((-number - 108) / 256 + 251); output.write((-number - 108) % 256); }
                else { throw new AssertionError("fixture number outside compact Type1 range"); }
            } else { output.write(switch ((String) value) { case "hsbw" -> 13; case "rmoveto" -> 21; case "rlineto" -> 5; case "closepath" -> 9; case "endchar" -> 14; default -> throw new AssertionError("unknown fixture operator"); }); }
        }
        return output.toByteArray();
    }
    private static PDStream cmap(PDDocument document, String codespace, String mapping, boolean unicode) throws Exception {
        return cmap(document, codespace, mapping, unicode, 1);
    }
    private static PDStream cmap(PDDocument document, String codespace, String mapping, boolean unicode, int ranges) throws Exception {
        return stream(document, "/CIDInit /ProcSet findresource begin 12 dict begin begincmap /CMapName /Export4jFixture def /CMapType " + (unicode ? 2 : 1)
                + " def /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> def /WMode 0 def " + ranges + " begincodespacerange " + codespace + " endcodespacerange " + mapping
                + " endcmap CMapName currentdict /CMap defineresource pop end end");
    }
    private static COSDictionary baseFont(String type, String fontName) { var font = new COSDictionary(); font.setItem(COSName.TYPE, COSName.FONT); font.setName(COSName.SUBTYPE, type); font.setName(COSName.BASE_FONT, fontName); return font; }
    private static COSDictionary descriptor(String name) {
        var value = new COSDictionary(); value.setName(COSName.TYPE, "FontDescriptor"); value.setName(COSName.FONT_NAME, name); value.setInt(COSName.FLAGS, 32);
        value.setItem(COSName.FONT_BBOX, numbers(0, 0, 600, 700)); value.setInt(COSName.ASCENT, 700); value.setInt(COSName.DESCENT, 0); value.setInt(COSName.CAP_HEIGHT, 700); value.setInt(COSName.ITALIC_ANGLE, 0); value.setInt(COSName.STEM_V, 80); return value;
    }
    private static void page(PDDocument document, COSDictionary font, String commands) throws Exception {
        var page = new PDPage(new PDRectangle(200, 100)); document.addPage(page); var resources = new PDResources(); page.setResources(resources);
        var fonts = new COSDictionary(); fonts.setItem(name("F1"), font); resources.getCOSObject().setItem(COSName.FONT, fonts); page.setContents(stream(document, commands));
    }
    private static COSDictionary rawFont(PDDocument document) { return document.getPage(0).getResources().getCOSObject().getCOSDictionary(COSName.FONT).getCOSDictionary(name("F1")); }
    private static PDStream stream(PDDocument document, String commands) throws Exception { var stream = new PDStream(document); try (var output = stream.createOutputStream()) { output.write(commands.getBytes(StandardCharsets.US_ASCII)); } return stream; }
    private static COSArray numbers(float... values) { var result = new COSArray(); for (float value : values) { result.add(new COSFloat(value)); } return result; }
    private static COSName name(String name) { return COSName.getPDFName(name); }
    private static byte[] save(PDDocument document) throws Exception { var output = new ByteArrayOutputStream(); document.save(output); return output.toByteArray(); }
    private static int ink(byte[] png) throws Exception {
        var image = ImageIO.read(new ByteArrayInputStream(png)); int result = 0;
        try { for (int y = 0; y < image.getHeight(); y++) { for (int x = 0; x < image.getWidth(); x++) { if ((image.getRGB(x, y) & 0xFFFFFF) < 0x808080) { result++; } } } }
        finally { image.flush(); } return result;
    }
}
