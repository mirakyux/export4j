// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.awt.image.BufferedImage;
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
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineContent;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.model.TableRow;

class DocxFieldsTest {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final InlineStyle STYLE = new InlineStyle("bold", 12, 0x224488, -2, true, false);
    private static final ParagraphFormat FORMAT = ParagraphFormat.of(12);
    private static InlineField field(InlineField.Kind kind, String target) { return new InlineField(kind, target, 42, STYLE); }
    private static Document source() throws IOException {
        var pixels = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", pixels);
        List<InlineContent> contents = List.of(new InlineText("A😀", InlineStyle.of(12)), field(InlineField.Kind.PAGE, ""),
                field(InlineField.Kind.PAGES, ""), field(InlineField.Kind.REFERENCE_PAGE, "目标<&>"),
                field(InlineField.Kind.REFERENCE_NUMBER, "目标<&>"), field(InlineField.Kind.REFERENCE_PAGE, "table"),
                new InlineImage(ImageResource.load(pixels.toByteArray()), 8, 8, 0, "marker"),
                new InlineText(" LINK", InlineStyle.of(12), "https://example.com/field"));
        var table = new Table("table", List.of(new ColumnWidth.Fraction(1)), List.of(new TableRow(List.of(
                TableCell.mixed(contents, CellFormat.of(12))))), 0, TableFormat.DEFAULT);
        return new Document(DocumentMetadata.EMPTY, PageSettings.A4, List.of(Paragraph.mixed("body", contents, FORMAT), table,
                new NumberedItem("目标<&>", "list", "Target", 4, FORMAT)));
    }
    @Test void nativeFieldsBookmarksStylesAndMediaRoundTripWithoutInventedValues() throws IOException {
        var source = source(); var output = new TrackingOutput(); new DocxAdapter().write(source, output);
        assertFalse(output.closed);
        var parts = parts(output.toByteArray());
        String xml = new String(parts.get("word/document.xml"), StandardCharsets.UTF_8);
        for (String instruction : List.of("PAGE", "NUMPAGES", "PAGEREF od_b_0 \\h", "REF od_b_0 \\w \\h")) {
            assertTrue(xml.contains("w:instr=\"" + instruction + "\""));
        }
        assertEquals(10, xml.split("<w:fldSimple ", -1).length - 1);
        assertEquals(2, xml.split("<w:bookmarkStart ", -1).length - 1);
        assertTrue(xml.contains("w:dirty=\"true\" od:width=\"42.0\""));
        assertFalse(xml.contains(">4</w:t>"));
        assertTrue(new String(parts.get("word/settings.xml"), StandardCharsets.UTF_8).contains("<w:updateFields w:val=\"true\"/>"));
        assertTrue(new String(parts.get("[Content_Types].xml"), StandardCharsets.UTF_8).contains("/word/settings.xml"));
        assertTrue(new String(parts.get("word/_rels/document.xml.rels"), StandardCharsets.UTF_8).contains("Target=\"settings.xml\""));
        var input = new TrackingInput(output.toByteArray()); var imported = new DocxAdapter().read(input, DocxAdapter.Policy.STRICT);
        assertFalse(input.closed); assertTrue(imported.diagnostics().isEmpty());
        assertEquals(source.blocks(), imported.document().sections().getFirst().blocks());
        var paragraph = (Paragraph) imported.document().sections().getFirst().blocks().getFirst();
        assertEquals(List.of(3, 4, 5, 6, 7), paragraph.fields().stream().map(Paragraph.FieldSpan::sourceIndex).toList());
    }
    @Test void complexFieldsJoinSplitInstructionsAndIgnoreStaleCache() throws IOException {
        String complex = control("begin", "od:width=\"33\"") + "<w:r><w:instrText> PA</w:instrText></w:r>"
                + "<w:r><w:instrText>GE \\* ARABIC </w:instrText></w:r>" + control("separate", "") + text("999") + control("end", "");
        var paragraph = paragraph(read("<w:p>" + text("prefix") + complex + text("suffix") + "</w:p>", DocxAdapter.Policy.STRICT));
        assertEquals("prefix\ufffcsuffix", paragraph.text());
        assertEquals(6, paragraph.fields().getFirst().sourceIndex());
        assertEquals(33, paragraph.fields().getFirst().field().width());
    }
    @Test void emptyComplexResultKeepsBeginStyle() throws IOException {
        String complex = "<w:r><w:rPr><w:color w:val=\"224488\"/></w:rPr><w:fldChar w:fldCharType=\"begin\" od:width=\"33\"/></w:r>"
                + "<w:r><w:instrText>NUMPAGES</w:instrText></w:r>" + control("end", "");
        assertEquals(0x224488, paragraph(read("<w:p>" + complex + "</w:p>", DocxAdapter.Policy.STRICT)).fields().getFirst().field().style().rgb());
    }
    @Test void standardWordFieldsRequireExplicitWidthAndNeverInferItFromCache() throws IOException {
        byte[] bytes = fixture("<w:p>" + simple("PAGE", "", "999") + "</w:p>");
        assertThrows(IOException.class, () -> new DocxAdapter().read(new ByteArrayInputStream(bytes), DocxAdapter.Policy.STRICT));
        var imported = new DocxAdapter().read(new ByteArrayInputStream(bytes), DocxAdapter.Policy.STRICT, 35);
        assertEquals(35, paragraph(imported).fields().getFirst().field().width());
        assertEquals("\ufffc", paragraph(imported).text());
        var reduced = new DocxAdapter().read(new ByteArrayInputStream(bytes), DocxAdapter.Policy.ALLOW_LOSS);
        assertEquals("999", paragraph(reduced).text()); assertEquals(1, reduced.diagnostics().size());
        for (double width : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new DocxAdapter().read(new ByteArrayInputStream(bytes), DocxAdapter.Policy.STRICT, width));
        }
    }
    @Test void malformedPresentWidthAlwaysFailsEvenWithFallbackOrLossMode() throws IOException {
        for (String width : List.of("0", "-1", "NaN", "Infinity", "1e100", "bad")) {
            byte[] bytes = fixture("<w:p>" + simple("PAGE", "od:width=\"" + width + "\"", "1") + "</w:p>");
            for (var policy : DocxAdapter.Policy.values()) {
                assertThrows(IOException.class, () -> new DocxAdapter().read(new ByteArrayInputStream(bytes), policy, 35));
            }
        }
    }
    @Test void unsupportedInstructionsSwitchesAndLocksRetainCacheOnlyWithDiagnostics() throws IOException {
        for (String instruction : List.of("DDE server topic item", "INCLUDETEXT file", "PAGE \\* ROMAN", "PAGE \\h", "PAGE \\* ARABIC \\* MERGEFORMAT", "REF target \\h", "PAGE \\x")) {
            String body = "<w:p>" + simple(instruction, "od:width=\"30\"", "kept") + "</w:p>";
            assertThrows(IOException.class, () -> read(body, DocxAdapter.Policy.STRICT));
            var reduced = read(body, DocxAdapter.Policy.ALLOW_LOSS);
            assertEquals("kept", paragraph(reduced).text()); assertEquals(1, reduced.diagnostics().size());
            assertTrue(paragraph(reduced).fields().isEmpty());
        }
        var locked = read("<w:p>" + simple("PAGE", "od:width=\"30\" w:fldLock=\"true\"", "locked") + "</w:p>", DocxAdapter.Policy.ALLOW_LOSS);
        assertEquals("locked", paragraph(locked).text()); assertEquals(1, locked.diagnostics().size());
    }
    @Test void brokenOrNestedComplexFieldsCannotBeBypassedByLossMode() {
        var begin = control("begin", "od:width=\"30\""); var end = control("end", ""); var separate = control("separate", "");
        for (String body : List.of(begin, end, separate, begin + begin + end + end, begin + separate + separate + end,
                begin + text("not instruction") + end, "<w:r><w:instrText>PAGE</w:instrText></w:r>",
                "<w:fldSimple w:instr=\"PAGE\">" + simple("PAGE", "od:width=\"30\"", "1") + "</w:fldSimple>")) {
            for (var policy : DocxAdapter.Policy.values()) { assertThrows(IOException.class, () -> read("<w:p>" + body + "</w:p>", policy)); }
        }
    }
    @Test void bookmarkPairsAndTargetsAreValidatedBeforeUse() throws IOException {
        var reference = simple("PAGEREF target \\h", "od:width=\"30\"", "old");
        for (String target : List.of("", "<w:bookmarkStart w:id=\"1\" w:name=\"target\"/>",
                "<w:bookmarkEnd w:id=\"1\"/>",
                "<w:bookmarkStart w:id=\"1\" w:name=\"target\"/><w:bookmarkStart w:id=\"1\" w:name=\"other\"/><w:bookmarkEnd w:id=\"1\"/>")) {
            assertThrows(IOException.class, () -> read("<w:p>" + reference + target + "</w:p>", DocxAdapter.Policy.STRICT));
        }
        String unnumbered = "<w:p><w:bookmarkStart w:id=\"1\" w:name=\"target\"/>" + text("target") + "<w:bookmarkEnd w:id=\"1\"/></w:p>";
        assertThrows(IOException.class, () -> read("<w:p>" + simple("REF target \\w \\h", "od:width=\"30\"", "old") + "</w:p>" + unnumbered, DocxAdapter.Policy.STRICT));
        var result = read("<w:p>" + reference + "</w:p>" + unnumbered, DocxAdapter.Policy.STRICT);
        assertEquals("p-1", paragraph(result).fields().getFirst().field().targetId());
    }
    @Test void tableParagraphBlocksKeepIndependentUtf16FieldOffsets() throws IOException {
        var body = "<w:tbl><w:tr><w:tc><w:p>" + text("😀") + simple("PAGE", "od:width=\"30\"", "1")
                + "</w:p><w:p>" + text("B") + simple("NUMPAGES", "od:width=\"40\"", "2") + "</w:p></w:tc></w:tr></w:tbl>";
        var table = (Table) read(body, DocxAdapter.Policy.STRICT).document().sections().getFirst().blocks().getFirst();
        var cell = table.rows().getFirst().cells().getFirst();
        var first = (Paragraph) cell.blocks().getFirst();
        var second = (Paragraph) cell.blocks().getLast();
        assertEquals("😀\ufffc", first.text());
        assertEquals("B\ufffc", second.text());
        assertEquals(2, first.fields().getFirst().sourceIndex());
        assertEquals(1, second.fields().getFirst().sourceIndex());
        assertEquals(40, second.fields().getFirst().field().width());
    }
    @Test void notesAndNonNumberedReferencesFailBeforeOutputAndSettingsCountTowardEntryBudget() throws IOException {
        for (var field : List.of(field(InlineField.Kind.NOTE_MARKER, "note"), field(InlineField.Kind.REFERENCE_NUMBER, "target"))) {
            var document = new Document(DocumentMetadata.EMPTY, PageSettings.A4, List.of(Paragraph.mixed("body", List.of(field), FORMAT), new Paragraph("target", "text", FORMAT)));
            var output = new ByteArrayOutputStream(); assertThrows(IOException.class, () -> new DocxAdapter().write(document, output)); assertEquals(0, output.size());
        }
        var limited = new DocxAdapter(new DocxLimits(1_000_000, 1_000_000, 10_000_000, 7, 100));
        var source = source(); var output = new ByteArrayOutputStream();
        assertThrows(IOException.class, () -> limited.write(source, output)); assertEquals(0, output.size());
    }
    @Test void instructionAndPerParagraphFieldBudgetsAreEnforced() {
        for (var policy : DocxAdapter.Policy.values()) {
            assertThrows(IOException.class, () -> read("<w:p>" + simple("PAGE " + "x".repeat(2048), "od:width=\"30\"", "cache") + "</w:p>", policy));
            assertThrows(IOException.class, () -> read("<w:p>" + simple("PAGE", "od:width=\"30\"", "").repeat(1025) + "</w:p>", policy));
        }
    }
    @Test void repeatedHeaderPageFieldsFailBeforeOutputBecauseWordReusesCachedValue() {
        var table = new Table("table", List.of(new ColumnWidth.Fraction(1)), List.of(
                new TableRow(List.of(TableCell.mixed(List.of(field(InlineField.Kind.PAGE, "")), CellFormat.of(12)))),
                new TableRow(List.of(new TableCell("row", CellFormat.of(12))))), 1, TableFormat.DEFAULT);
        var document = new Document(DocumentMetadata.EMPTY, PageSettings.A4, List.of(table));
        var output = new ByteArrayOutputStream();
        assertThrows(IOException.class, () -> new DocxAdapter().write(document, output)); assertEquals(0, output.size());
    }
    @Test void pageReferenceToMidBlockBookmarkIsNeverSilentlyRetargetedToBlockStart() throws IOException {
        String body = "<w:p>" + simple("PAGEREF target", "od:width=\"30\"", "cached") + "</w:p><w:p>"
                + text("earlier text") + "<w:bookmarkStart w:id=\"1\" w:name=\"target\"/>" + text("later text") + "<w:bookmarkEnd w:id=\"1\"/></w:p>";
        assertThrows(IOException.class, () -> read(body, DocxAdapter.Policy.STRICT));
        var reduced = read(body, DocxAdapter.Policy.ALLOW_LOSS);
        assertEquals("cached", paragraph(reduced).text()); assertEquals(1, reduced.diagnostics().size());
    }
    private static Paragraph paragraph(DocxAdapter.ReadResult result) { return (Paragraph) result.document().sections().getFirst().blocks().getFirst(); }
    private static String text(String value) { return "<w:r><w:t>" + value + "</w:t></w:r>"; }
    private static String control(String type, String attrs) { return "<w:r><w:fldChar w:fldCharType=\"" + type + "\" " + attrs + "/></w:r>"; }
    private static String simple(String instruction, String attrs, String cache) { return "<w:fldSimple w:instr=\"" + instruction + "\" " + attrs + ">" + text(cache) + "</w:fldSimple>"; }
    private static DocxAdapter.ReadResult read(String body, DocxAdapter.Policy policy) throws IOException { return new DocxAdapter().read(new ByteArrayInputStream(fixture(body)), policy); }
    private static byte[] fixture(String body) throws IOException {
        String xml = "<w:document xmlns:w=\"" + W + "\" xmlns:od=\"urn:com.mirakyux.export4j:semantic:1\"><w:body>" + body + "</w:body></w:document>";
        return pack(Map.of("[Content_Types].xml", "<Types/>".getBytes(StandardCharsets.UTF_8), "word/document.xml", xml.getBytes(StandardCharsets.UTF_8)));
    }
    private static Map<String, byte[]> parts(byte[] bytes) throws IOException {
        var parts = new LinkedHashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) { parts.put(entry.getName(), zip.readAllBytes()); }
        }
        return parts;
    }
    private static byte[] pack(Map<String, byte[]> parts) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (var part : parts.entrySet()) { zip.putNextEntry(new ZipEntry(part.getKey())); zip.write(part.getValue()); zip.closeEntry(); }
        }
        return bytes.toByteArray();
    }
    private static final class TrackingOutput extends ByteArrayOutputStream { private boolean closed; @Override public void close() { closed = true; } }
    private static final class TrackingInput extends ByteArrayInputStream { private boolean closed; TrackingInput(byte[] bytes) { super(bytes); } @Override public void close() { closed = true; } }
}
