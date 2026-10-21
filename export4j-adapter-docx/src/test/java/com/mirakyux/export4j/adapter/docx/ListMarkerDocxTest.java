// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.model.BulletItem;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.ParagraphFormat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListMarkerDocxTest {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String START = "<w:numbering xmlns:w=\"" + W + "\">";
    private static final String END = "</w:numbering>";
    private static final String LEVEL = "<w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/><w:numFmt w:val=\"decimal\"/>"
            + "<w:lvlText w:val=\"%%1.\"/>%s</w:lvl>";
    private static final String INSTANCE = "<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num>";
    private static final String BODY = "<w:p><w:pPr><w:rPr><w:sz w:val=\"20\"/></w:rPr>"
            + "<w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr></w:pPr>"
            + "<w:r><w:rPr><w:i/><w:sz w:val=\"18\"/><w:color w:val=\"AA3311\"/></w:rPr><w:t>Body</w:t></w:r></w:p>";

    @Test void completeMarkerStylesRoundTripIndependentlyFromConflictingBodyRuns() throws IOException {
        var format = ParagraphFormat.of(12);
        var marker = new InlineStyle("bold", 10.5, 0x224488, -1.5, true, true);
        var body = new InlineText("Styled body", new InlineStyle("italic", 11, 0x883366, 0, false, false));
        var numbered = NumberedItem.rich("number", "list", List.of(body), 3, format, 1, marker);
        var bullet = BulletItem.rich("bullet", List.of(body), "*", 2, format, marker);
        var source = Document.builder().add(numbered).add(bullet).build();
        byte[] bytes = write(source);
        var result = read(bytes, DocxAdapter.Policy.STRICT);
        assertTrue(result.diagnostics().isEmpty());
        assertEquals(source.blocks(), result.document().sections().getFirst().blocks());
        String xml = parts(bytes).get("word/numbering.xml");
        assertEquals(18, occurrences(xml, "<w:rPr>"));
        assertEquals(18, occurrences(xml, "<w:rStyle w:val=\"bold\"/>"));
        assertEquals(18, occurrences(xml, "<w:sz w:val=\"21\"/>"));
        assertEquals(18, occurrences(xml, "<w:color w:val=\"224488\"/>"));
        assertEquals(18, occurrences(xml, "<w:position w:val=\"3\"/>"));
        assertEquals(18, occurrences(xml, "<w:u w:val=\"single\"/>"));
        assertEquals(18, occurrences(xml, "<w:strike/>"));
        assertFalse(xml.contains("883366"));
        assertTrue(parts(bytes).get("word/styles.xml").contains("w:styleId=\"bold\""));
    }

    @Test void roleUsedOnlyByMarkerIsDeclaredAndPreserved() throws IOException {
        var item = NumberedItem.builder().id("n").listId("l").text("Plain body")
                .markerStyle(style -> style.fontRole("marker-brand").rgb(0x224488)).build();
        byte[] bytes = write(Document.builder().add(item).build());
        assertTrue(parts(bytes).get("word/styles.xml").contains("w:styleId=\"marker-brand\""));
        assertFalse(parts(bytes).get("word/document.xml").contains("marker-brand"));
        var imported = (NumberedItem) read(bytes, DocxAdapter.Policy.STRICT).document().sections().getFirst().blocks().getFirst();
        assertEquals(item.markerStyle(), imported.markerStyle());
    }

    @Test void oldListConstructorsKeepDefaultMarkerAndDoNotCopyFirstRun() throws IOException {
        var f = ParagraphFormat.of(10);
        var body = new InlineText("Body", new InlineStyle("italic", 9, 0xAA3311, -1, true, true));
        var source = Document.builder().add(NumberedItem.rich("n", "l", List.of(body), 0, f, 1))
                .add(BulletItem.rich("b", List.of(body), "*", 1, f)).build();
        var result = read(write(source), DocxAdapter.Policy.STRICT);
        assertEquals(InlineStyle.of(10), ((NumberedItem) result.document().sections().getFirst().blocks().getFirst()).markerStyle());
        assertEquals(InlineStyle.of(10), ((BulletItem) result.document().sections().getFirst().blocks().get(1)).markerStyle());
        assertEquals(source.blocks(), result.document().sections().getFirst().blocks());
    }

    @Test void standardNativeNumberingReadsSelectedLevelStyleWithoutMetadata() throws IOException {
        String first = LEVEL.formatted("<w:rPr><w:b/><w:sz w:val=\"21\"/><w:color w:val=\"224488\"/>"
                + "<w:position w:val=\"3\"/><w:u w:val=\"single\"/><w:strike/></w:rPr>");
        String second = "<w:lvl w:ilvl=\"1\"><w:start w:val=\"1\"/><w:numFmt w:val=\"decimal\"/>"
                + "<w:lvlText w:val=\"%1.%2.\"/><w:rPr><w:i/><w:sz w:val=\"16\"/>"
                + "<w:color w:val=\"336633\"/></w:rPr></w:lvl>";
        byte[] bytes = fixture(BODY + BODY.replace("w:ilvl w:val=\"0\"", "w:ilvl w:val=\"1\""), numbering(first + second));
        var result = read(bytes, DocxAdapter.Policy.STRICT);
        assertTrue(result.diagnostics().isEmpty());
        var blocks = result.document().sections().getFirst().blocks();
        var parent = (NumberedItem) blocks.getFirst();
        var child = (NumberedItem) blocks.get(1);
        assertEquals(new InlineStyle("bold", 10.5, 0x224488, -1.5, true, true), parent.markerStyle());
        assertEquals(new InlineStyle("italic", 8, 0x336633, 0, false, false), child.markerStyle());
        assertEquals("italic", parent.inlines().getFirst().style().fontRole());
        assertEquals(0xAA3311, parent.inlines().getFirst().style().rgb());
        assertEquals(2, child.level());
    }

    @Test void absentNativeMarkerPropertiesUseParagraphDefaultInsteadOfFirstBodyRun() throws IOException {
        var imported = (NumberedItem) read(fixture(BODY, numbering(LEVEL.formatted(""))), DocxAdapter.Policy.STRICT)
                .document().sections().getFirst().blocks().getFirst();
        assertEquals(InlineStyle.of(10), imported.markerStyle());
        assertEquals(new InlineStyle("italic", 9, 0xAA3311, 0, false, false), imported.inlines().getFirst().style());
    }

    @Test void standardNativeBulletStyleSurvivesWithoutPrivateAttributes() throws IOException {
        String level = LEVEL.formatted("<w:rPr><w:rStyle w:val=\"custom-marker\"/><w:sz w:val=\"18\"/>"
                + "<w:color w:val=\"224488\"/></w:rPr>")
                .replace("w:val=\"decimal\"", "w:val=\"bullet\"").replace("w:val=\"%1.\"", "w:val=\"*\"");
        var result = read(fixture(BODY, numbering(level)), DocxAdapter.Policy.STRICT);
        var item = (BulletItem) result.document().sections().getFirst().blocks().getFirst();
        assertEquals("*", item.marker());
        assertEquals(new InlineStyle("custom-marker", 9, 0x224488, 0, false, false), item.markerStyle());
    }

    @Test void numberingStyleUsesExistingHalfPointQuantization() throws IOException {
        var style = new InlineStyle("bold", 10.26, 0x224488, -1.26, true, false);
        var item = NumberedItem.builder().id("n").listId("l").text("Body").markerStyle(style).build();
        var imported = (NumberedItem) read(write(Document.builder().add(item).build()), DocxAdapter.Policy.STRICT)
                .document().sections().getFirst().blocks().getFirst();
        assertEquals(new InlineStyle("bold", 10.5, 0x224488, -1.5, true, false), imported.markerStyle());
    }

    @Test void unknownMarkerPropertiesOnUnusedLevelsAreRejectedOrDiagnosedInNumberingPart() throws IOException {
        for (String extra : List.of("<w:highlight w:val=\"yellow\"/>", "<w:rFonts w:ascii=\"Unknown\"/>",
                "<w:vanish/>", "<x:color xmlns:x=\"urn:foreign\" w:val=\"FFFFFF\"/>")) {
            String unused = LEVEL.formatted("<w:rPr>" + extra + "</w:rPr>").replace("w:ilvl=\"0\"", "w:ilvl=\"8\"");
            byte[] bytes = fixture(BODY, numbering(LEVEL.formatted("") + unused));
            assertThrows(IOException.class, () -> read(bytes, DocxAdapter.Policy.STRICT));
            var result = read(bytes, DocxAdapter.Policy.ALLOW_LOSS);
            assertFalse(result.diagnostics().isEmpty());
            assertTrue(result.diagnostics().stream().allMatch(value -> value.part().equals("word/numbering.xml")));
            assertEquals(InlineStyle.of(10), ((NumberedItem) result.document().sections().getFirst().blocks().getFirst()).markerStyle());
        }
    }

    @Test void unsupportedNumberingAttributesAndContainersCannotDisappearSilently() throws IOException {
        for (String level : List.of(
                LEVEL.formatted("<w:rPr><w:color w:val=\"224488\" w:themeColor=\"accent1\"/></w:rPr>"),
                LEVEL.formatted("<w:rPr><w:u w:val=\"double\"/></w:rPr>"),
                LEVEL.formatted("<w:pPr><w:ind w:left=\"100\"/></w:pPr>"),
                LEVEL.formatted("<w:rPr><w:sz w:val=\"20\"><w:b/></w:sz></w:rPr>"),
                LEVEL.formatted("<w:rPr arbitrary=\"bad\"><w:b/></w:rPr>"))) {
            byte[] bytes = fixture(BODY, numbering(level));
            assertThrows(IOException.class, () -> read(bytes, DocxAdapter.Policy.STRICT));
            assertTrue(read(bytes, DocxAdapter.Policy.ALLOW_LOSS).diagnostics().stream()
                    .anyMatch(value -> value.part().equals("word/numbering.xml")));
        }
    }

    @Test void malformedAndAmbiguousNumberingFailsUnderBothPolicies() throws IOException {
        String good = numbering(LEVEL.formatted(""));
        for (String bad : List.of(
                good.replace("</w:abstractNum>", LEVEL.formatted("") + "</w:abstractNum>"),
                good.replace(INSTANCE, INSTANCE + INSTANCE),
                good.replace("<w:lvlText", "<w:start w:val=\"1\"/><w:lvlText"),
                good.replace("w:ilvl=\"0\"", "w:ilvl=\"9\""),
                numbering(LEVEL.formatted("<w:rPr><w:sz w:val=\"NaN\"/></w:rPr>")),
                numbering(LEVEL.formatted("<w:rPr><w:sz w:val=\"0\"/></w:rPr>")),
                numbering(LEVEL.formatted("<w:rPr><w:color w:val=\"FFFFFFF\"/></w:rPr>")),
                numbering(LEVEL.formatted("<w:rPr><w:position w:val=\"1.25\"/></w:rPr>")),
                numbering(LEVEL.formatted("<w:rPr><w:b w:val=\"maybe\"/></w:rPr>")),
                numbering(LEVEL.formatted("<w:rPr/><w:rPr/>")))) {
            byte[] bytes = fixture(BODY, bad);
            for (var policy : DocxAdapter.Policy.values()) { assertThrows(IOException.class, () -> read(bytes, policy)); }
        }
    }

    @Test void externalRestartOverridesRemainAnExplicitLoss() throws IOException {
        String xml = numbering(LEVEL.formatted("<w:rPr><w:b/></w:rPr>"))
                .replace("</w:num>", "<w:lvlOverride w:ilvl=\"0\"><w:startOverride w:val=\"7\"/></w:lvlOverride></w:num>");
        byte[] bytes = fixture(BODY, xml);
        assertThrows(IOException.class, () -> read(bytes, DocxAdapter.Policy.STRICT));
        var result = read(bytes, DocxAdapter.Policy.ALLOW_LOSS);
        assertTrue(result.diagnostics().stream().anyMatch(value -> value.element().equals("lvlOverride")));
        assertEquals("bold", ((NumberedItem) result.document().sections().getFirst().blocks().getFirst()).markerStyle().fontRole());
    }

    @Test void numberingValidationBudgetIncludesUnusedDefinitions() throws IOException {
        var xml = new StringBuilder(START);
        for (int index = 0; index < 40; index++) {
            xml.append("<w:abstractNum w:abstractNumId=\"").append(index).append("\">")
                    .append(LEVEL.formatted("<w:rPr><w:b/><w:sz w:val=\"20\"/></w:rPr>"))
                    .append("</w:abstractNum>");
        }
        xml.append(INSTANCE).append(END);
        var limits = DocxLimits.DEFAULT.toBuilder().maxBlocks(1).build();
        byte[] bytes = fixture(BODY, xml.toString());
        for (var policy : DocxAdapter.Policy.values()) {
            var failure = assertThrows(IOException.class, () -> new DocxAdapter(limits).read(new ByteArrayInputStream(bytes), policy));
            assertTrue(failure.getMessage().contains("numbering node budget"));
        }
    }

    @Test void externalBulletOverridesAreRejectedOrDiagnosedBeforeReturningTheBullet() throws IOException {
        String level = LEVEL.formatted("<w:rPr><w:b/></w:rPr>")
                .replace("w:val=\"decimal\"", "w:val=\"bullet\"").replace("w:val=\"%1.\"", "w:val=\"*\"");
        String xml = numbering(level).replace("</w:num>",
                "<w:lvlOverride w:ilvl=\"0\"><w:startOverride w:val=\"7\"/></w:lvlOverride></w:num>");
        byte[] bytes = fixture(BODY, xml);
        assertThrows(IOException.class, () -> read(bytes, DocxAdapter.Policy.STRICT));
        var result = read(bytes, DocxAdapter.Policy.ALLOW_LOSS);
        assertEquals(1, result.diagnostics().size());
        assertEquals("word/numbering.xml", result.diagnostics().getFirst().part());
        assertEquals("lvlOverride", result.diagnostics().getFirst().element());
        var item = (BulletItem) result.document().sections().getFirst().blocks().getFirst();
        assertEquals("*", item.marker());
        assertEquals("bold", item.markerStyle().fontRole());
    }

    private static String numbering(String levels) {
        return START + "<w:abstractNum w:abstractNumId=\"0\">" + levels + "</w:abstractNum>" + INSTANCE + END;
    }

    private static int occurrences(String text, String value) { return text.split(java.util.regex.Pattern.quote(value), -1).length - 1; }

    private static byte[] write(Document document) throws IOException {
        var out = new ByteArrayOutputStream(); new DocxAdapter().write(document, out); return out.toByteArray();
    }

    private static DocxAdapter.ReadResult read(byte[] bytes, DocxAdapter.Policy policy) throws IOException {
        return new DocxAdapter().read(new ByteArrayInputStream(bytes), policy);
    }

    private static byte[] fixture(String body, String numbering) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            for (var entry : Map.of("[Content_Types].xml", "<Types/>", "word/document.xml",
                    "<w:document xmlns:w=\"" + W + "\"><w:body>" + body + "</w:body></w:document>",
                    "word/numbering.xml", numbering).entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static Map<String, String> parts(byte[] bytes) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                result.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return result;
    }
}
