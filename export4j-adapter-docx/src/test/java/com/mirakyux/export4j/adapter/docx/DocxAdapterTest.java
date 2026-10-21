// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.BulletItem;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.PageBreak;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.model.TableRow;

class DocxAdapterTest {
    @Test void richHeadingsListsAndJustifiedStrictCellsRoundTripWithNativeAlignment() throws IOException {
        var format = ParagraphFormat.of(12).withAlignment(ParagraphAlignment.JUSTIFY)
                .withLineBreakPolicy(com.mirakyux.export4j.model.LineBreakPolicy.CJK_STRICT);
        var run = new InlineText("Rich text", new InlineStyle("bold", 12, 0x224488, 0, true, false), "https://example.com/rich");
        var document = Document.builder().add(com.mirakyux.export4j.model.Heading.rich("h", List.of(run), 1, format))
                .add(com.mirakyux.export4j.model.BulletItem.rich("b", List.of(run), "•", 1, format))
                .add(com.mirakyux.export4j.model.NumberedItem.rich("n", "list", List.of(run), 0, format, 1))
                .add(Table.text("t", List.of("Header"), List.of(List.of("Body")),
                        CellFormat.of(12).withAlignment(ParagraphAlignment.JUSTIFY).withLineBreakPolicy(format.lineBreakPolicy()))).build();
        var output = new ByteArrayOutputStream(); new DocxAdapter().write(document, output);
        var imported = new DocxAdapter().read(new ByteArrayInputStream(output.toByteArray()), DocxAdapter.Policy.STRICT);
        assertTrue(imported.diagnostics().isEmpty());
        assertEquals(document.blocks(), imported.document().sections().getFirst().blocks());
        var nativeBoth = new DocxAdapter().read(new ByteArrayInputStream(packageXml(document(
                "<w:p><w:pPr><w:jc w:val=\"both\"/></w:pPr><w:r><w:t>text</w:t></w:r></w:p>"))), DocxAdapter.Policy.STRICT);
        assertEquals(ParagraphAlignment.JUSTIFY, ((Paragraph) nativeBoth.document().sections().getFirst().blocks().getFirst()).format().alignment());
    }

    @Test
    void unresolvedInlineFieldTargetsAreRejectedBeforeWritingDocxBytes() {
        var paragraph = Paragraph.mixed("p", List.of(new InlineField(InlineField.Kind.REFERENCE_PAGE, "missing", 30, InlineStyle.of(12))), ParagraphFormat.of(12));
        var cell = TableCell.mixed(List.of(new InlineField(InlineField.Kind.REFERENCE_NUMBER, "missing", 30, InlineStyle.of(12))), CellFormat.of(12));
        var table = new Table("t", List.of(new ColumnWidth.Fraction(1)), List.of(new TableRow(List.of(cell))), 0, TableFormat.DEFAULT);
        for (var block : List.of(paragraph, table)) {
            var document = new Document(DocumentMetadata.EMPTY, PageSettings.A4,
                    List.of(new Paragraph("before", "Earlier block", ParagraphFormat.of(12)), block));
            var output = new ByteArrayOutputStream();
            assertThrows(IOException.class, () -> new DocxAdapter().write(document, output));
            assertEquals(0, output.size());
        }
    }

    @Test void semanticRoundTripPreservesSectionsListsRichRunsAndTables() throws IOException {
        var f = ParagraphFormat.of(12);
        var rich = new Paragraph("rich", List.of(new InlineText("中文 & < >\n", InlineStyle.of(12)), new InlineText("Bold", new InlineStyle("bold", 12, 0x224488, -2, true, true))), f);
        var table = new Table("table", List.of(new ColumnWidth.Fixed(80), new ColumnWidth.Fraction(1)), List.of(
                new TableRow(List.of(new TableCell("H1", CellFormat.of(10)), new TableCell("H2", CellFormat.of(10)))),
                new TableRow(List.of(new TableCell("A", CellFormat.of(10)), new TableCell("B\nC", CellFormat.of(10))))), 1,
                new TableFormat(PageMargins.uniform(3), 0, 6, true));
        var blocks = List.<Block>of(new Heading("h", "标题", 1, f), rich, new NumberedItem("n1", "items", "Parent", 3, f),
                new NumberedItem("n2", "items", "Child", 2, f, 2), new BulletItem("b", "Bullet", "•", 2, f), table, new PageBreak("break"));
        var source = new SectionedDocument(new DocumentMetadata("Metadata <&>", "Author"), List.of(new Section("first", PageSettings.A4, blocks, 1),
                new Section("second", new PageSettings(new PageSize(600, 400), PageMargins.uniform(20)), List.of(new Paragraph("end", "End", f)), 10)));
        var output = new TrackingOutput();
        new DocxAdapter().write(source, output);
        assertFalse(output.closed);
        var input = new TrackingInput(output.toByteArray());
        var read = new DocxAdapter().read(input, DocxAdapter.Policy.STRICT);
        assertFalse(input.closed); assertTrue(read.diagnostics().isEmpty());
        assertEquals(source.metadata(), read.document().metadata()); assertEquals(2, read.document().sections().size());
        var imported = read.document().sections().getFirst().blocks();
        assertEquals(blocks.stream().map(Block::id).toList(), imported.stream().map(Block::id).toList());
        assertEquals(rich, imported.get(1)); assertEquals(blocks.get(2), imported.get(2)); assertEquals(blocks.get(3), imported.get(3));
        assertEquals(blocks.get(4), imported.get(4)); assertEquals(table, imported.get(5));
        assertEquals(10, read.document().sections().get(1).firstPageNumber());
        assertEquals(600, read.document().sections().get(1).pageSettings().size().width());
    }

    @Test void strictRejectsUnsupportedMarkupAndLossPolicyReturnsDiagnostics() throws IOException {
        String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>kept</w:t><w:drawing/></w:r></w:p></w:body></w:document>";
        byte[] bytes = packageXml(xml);
        assertThrows(IOException.class, () -> new DocxAdapter().read(new ByteArrayInputStream(bytes), DocxAdapter.Policy.STRICT));
        var imported = new DocxAdapter().read(new ByteArrayInputStream(bytes), DocxAdapter.Policy.ALLOW_LOSS);
        assertTrue(imported.diagnostics().stream().anyMatch(d -> d.element().equals("drawing")));
        assertEquals("kept", ((Paragraph) imported.document().sections().getFirst().blocks().getFirst()).text());
    }

    @Test void supportedNestedTablesPreserveOrderedBlocksWithoutLoss() throws IOException {
        String nested = table("<w:p><w:r><w:t>nested-only</w:t></w:r></w:p>");
        byte[] bytes = packageXml(document(table("<w:p><w:r><w:t>before</w:t></w:r></w:p>" + nested
                + "<w:p><w:r><w:t>after</w:t></w:r></w:p>")));
        var input = new TrackingInput(bytes);
        var imported = new DocxAdapter().read(input, DocxAdapter.Policy.STRICT);
        assertFalse(input.closed);
        assertTrue(imported.diagnostics().isEmpty());
        var outer = (Table) imported.document().sections().getFirst().blocks().getFirst();
        var content = outer.rows().getFirst().cells().getFirst().blocks();
        assertEquals("before", ((Paragraph) content.getFirst()).text());
        assertEquals("nested-only", ((Table) content.get(1)).rows().getFirst().cells().getFirst().text());
        assertEquals("after", ((Paragraph) content.getLast()).text());
    }

    @Test void knownSemanticNamesInUnreadContainersAreRejectedOrDiagnosed() throws IOException {
        var misplaced = Map.of(
                "document", "<w:document><w:body><w:p/></w:body></w:document>",
                "tbl", "<w:p>" + table("<w:p/>") + "</w:p>",
                "tr", "<w:tr><w:tc><w:p/></w:tc></w:tr>",
                "tc", "<w:tc><w:p/></w:tc>",
                "p", "<w:p><w:p><w:r><w:t>omitted</w:t></w:r></w:p></w:p>",
                "r", table("<w:r><w:t>omitted</w:t></w:r><w:p/>"),
                "t", "<w:p><w:t>omitted</w:t></w:p>",
                "drawing", "<w:p><w:drawing/></w:p>",
                "sectPr", table("<w:p><w:pPr><w:sectPr/></w:pPr></w:p>"));
        for (var entry : misplaced.entrySet()) {
            byte[] bytes = packageXml(document("<w:p><w:r><w:t>kept</w:t></w:r></w:p>" + entry.getValue()));
            assertThrows(IOException.class, () -> new DocxAdapter().read(new ByteArrayInputStream(bytes), DocxAdapter.Policy.STRICT), entry.getKey());
            var imported = new DocxAdapter().read(new ByteArrayInputStream(bytes), DocxAdapter.Policy.ALLOW_LOSS);
            assertTrue(imported.diagnostics().stream().anyMatch(d -> d.part().equals("word/document.xml")
                    && d.element().equals(entry.getKey())), entry.getKey());
            assertEquals("kept", ((Paragraph) imported.document().sections().getFirst().blocks().getFirst()).text());
        }
    }

    @Test void rejectsEntitiesTraversalAndExpandedBudget() throws IOException {
        String xml = "<!DOCTYPE x [<!ENTITY test SYSTEM 'file:///no-such-file'>]><x>&test;</x>";
        assertThrows(IOException.class, () -> new DocxAdapter().read(new ByteArrayInputStream(packageXml(xml)), DocxAdapter.Policy.ALLOW_LOSS));
        var data = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(data)) { zip.putNextEntry(new ZipEntry("../escape.xml")); zip.write(1); zip.closeEntry(); }
        assertThrows(IOException.class, () -> new DocxAdapter().read(new ByteArrayInputStream(data.toByteArray()), DocxAdapter.Policy.ALLOW_LOSS));
        var adapter = new DocxAdapter(new DocxLimits(100_000, 100, 150, 10, 10));
        assertThrows(IOException.class, () -> adapter.read(new ByteArrayInputStream(packageXml("<x>" + "x".repeat(1000) + "</x>")), DocxAdapter.Policy.ALLOW_LOSS));
    }

    @Test void unsupportedExportFailsBeforeOutput() {
        var output = new ByteArrayOutputStream();
        var doc = new Document(DocumentMetadata.EMPTY, PageSettings.A4, List.of(new com.mirakyux.export4j.model.Caption("c", "Figure", "caption", ParagraphFormat.of(12))));
        assertThrows(IOException.class, () -> new DocxAdapter().write(doc, output));
        assertEquals(0, output.size());
    }

    @Test void macroContentTypesAndVbaPartsFailEvenWithLossAllowed() throws IOException {
        String main = document("<w:p><w:r><w:t>text</w:t></w:r></w:p>");
        for (var extra : List.of(Map.of("[Content_Types].xml", "<Types><Override ContentType=\"application/vnd.ms-word.document.macroEnabled.main+xml\"/></Types>"),
                Map.of("word/vbaProject.bin", "code"))) {
            byte[] bytes = packageParts(main, extra);
            assertThrows(IOException.class, () -> new DocxAdapter().read(new ByteArrayInputStream(bytes), DocxAdapter.Policy.ALLOW_LOSS));
        }
    }

    @Test void unsupportedStylesAlignmentAndNumberingOverridesAreDiagnosed() throws IOException {
        String numbering = "<w:numbering xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:abstractNum w:abstractNumId=\"1\"><w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/><w:numFmt w:val=\"decimal\"/><w:lvlText w:val=\"%1.\"/></w:lvl></w:abstractNum><w:num w:numId=\"1\"><w:abstractNumId w:val=\"1\"/><w:lvlOverride w:ilvl=\"0\"><w:startOverride w:val=\"7\"/></w:lvlOverride></w:num></w:numbering>";
        for (var testCase : List.of(
                packageXml(document("<w:p><w:pPr><w:pStyle w:val=\"Custom\"/></w:pPr><w:r><w:t>text</w:t></w:r></w:p>")),
                packageXml(document("<w:p><w:pPr><w:jc w:val=\"distribute\"/></w:pPr><w:r><w:t>text</w:t></w:r></w:p>")),
                packageXml(document("<w:p><w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr><w:r><w:rPr><w:shadow/></w:rPr><w:t>text</w:t></w:r></w:p>")),
                packageParts(document("<w:p><w:pPr><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr></w:pPr><w:r><w:t>text</w:t></w:r></w:p>"), Map.of("word/numbering.xml", numbering)),
                packageParts(document("<w:p><w:r><w:t>text</w:t></w:r></w:p>"), Map.of("word/styles.xml", "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:docDefaults/></w:styles>")))) {
            assertThrows(IOException.class, () -> new DocxAdapter().read(new ByteArrayInputStream(testCase), DocxAdapter.Policy.STRICT));
            assertFalse(new DocxAdapter().read(new ByteArrayInputStream(testCase), DocxAdapter.Policy.ALLOW_LOSS).diagnostics().isEmpty());
        }
    }

    @Test void richTableCellsAndPassiveHyperlinksRoundTripStrictly() throws IOException {
        var inline = new InlineText("External link", new InlineStyle("", 12, 0x224488, 0, true, false), "https://example.com/docs?a=1&b=2");
        var table = new Table("t", List.of(new ColumnWidth.Auto(30, 180)), List.of(new TableRow(List.of(TableCell.rich(List.of(inline), CellFormat.of(12))))), 0, TableFormat.DEFAULT);
        var document = new Document(DocumentMetadata.EMPTY, PageSettings.A4, List.of(new Paragraph("p", List.of(inline), ParagraphFormat.of(12)), table));
        var output = new ByteArrayOutputStream(); new DocxAdapter().write(document, output);
        var result = new DocxAdapter().read(new ByteArrayInputStream(output.toByteArray()), DocxAdapter.Policy.STRICT);
        assertTrue(result.diagnostics().isEmpty());
        assertEquals(document.blocks(), result.document().sections().getFirst().blocks());
        assertThrows(IllegalArgumentException.class, () -> new InlineText("bad", InlineStyle.of(12), "javascript:alert(1)"));
    }

    @Test void invalidImportedValuesAreIoErrorsAndOversizedExportDoesNotWrite() throws IOException {
        byte[] bad = packageXml(document("<w:p><w:pPr><w:rPr><w:sz w:val=\"0\"/></w:rPr></w:pPr><w:r><w:t>text</w:t></w:r></w:p>"));
        assertThrows(IOException.class, () -> new DocxAdapter().read(new ByteArrayInputStream(bad), DocxAdapter.Policy.STRICT));
        var output = new ByteArrayOutputStream();
        var adapter = new DocxAdapter(new DocxLimits(100_000, 100, 1000, 20, 20));
        assertThrows(IOException.class, () -> adapter.write(new Document(DocumentMetadata.EMPTY, PageSettings.A4,
                List.of(new Paragraph("p", "&".repeat(30), ParagraphFormat.of(12)))), output));
        assertEquals(0, output.size());
    }

    private static String document(String body) { return "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>" + body + "</w:body></w:document>"; }

    private static String table(String cellContents) {
        return "<w:tbl><w:tblGrid><w:gridCol w:w=\"1440\"/></w:tblGrid><w:tr><w:tc>"
                + cellContents + "</w:tc></w:tr></w:tbl>";
    }

    private static byte[] packageXml(String xml) throws IOException {
        return packageParts(xml, Map.of());
    }

    private static byte[] packageParts(String xml, Map<String, String> extras) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml")); zip.write(extras.getOrDefault("[Content_Types].xml", "<Types/>").getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml")); zip.write(xml.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            for (var part : extras.entrySet()) {
                if (part.getKey().equals("[Content_Types].xml")) { continue; }
                zip.putNextEntry(new ZipEntry(part.getKey())); zip.write(part.getValue().getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
    private static final class TrackingOutput extends ByteArrayOutputStream { private boolean closed; @Override public void close() { closed = true; } }
    private static final class TrackingInput extends ByteArrayInputStream { private boolean closed; TrackingInput(byte[] bytes) { super(bytes); } @Override public void close() { closed = true; } }
}
