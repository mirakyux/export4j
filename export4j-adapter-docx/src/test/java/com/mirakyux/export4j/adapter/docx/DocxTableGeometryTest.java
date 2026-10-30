// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageRegion;
import com.mirakyux.export4j.model.PageRegions;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
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
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

class DocxTableGeometryTest {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    @Test void adjacentDifferentGridsHaveAStandardParagraphWithoutAddingSemanticBlocks() throws Exception {
        var first = Table.builder("first").columns(new ColumnWidth.Fixed(36), new ColumnWidth.Fixed(72)).row("A", "B").build();
        var second = Table.builder("second").columns(new ColumnWidth.Fraction(1)).row("C").build();
        var source = new Document(DocumentMetadata.EMPTY, PageSettings.A4, List.of(first, second));
        var parts = write(sectioned(source));
        var root = parse(parts.get("word/document.xml"));
        var body = DocxAdapter.child(root, "body");
        assertEquals(List.of("tbl", "p", "tbl", "sectPr"), DocxAdapter.children(body).stream().map(Element::getLocalName).toList());
        assertTrue(DocxTables.isSeparator(DocxAdapter.children(body).get(1)));
        var result = read(parts, DocxAdapter.Policy.STRICT);
        assertEquals(source.blocks(), result.document().sections().getFirst().blocks());
        assertTrue(result.diagnostics().isEmpty());

        String xml = new String(parts.get("word/document.xml"), StandardCharsets.UTF_8);
        parts.put("word/document.xml", xml.replace(" od:tableSeparator=\"true\"", "").getBytes(StandardCharsets.UTF_8));
        assertEquals(3, read(parts, DocxAdapter.Policy.STRICT).document().sections().getFirst().blocks().size());
    }

    @Test void mergedOwnersAndContinuationsDeclareTheSumOfTheirActualGridColumns() throws Exception {
        var merged = TableCell.builder().text("merged").format(CellFormat.of(12)).columnSpan(2).rowSpan(2).build();
        var table = Table.builder("table").columns(new ColumnWidth.Fixed(32.15), new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(2))
                .row(merged, new TableCell("right", CellFormat.of(12))).row("tail").build();
        var source = new Document(DocumentMetadata.EMPTY, PageSettings.A4, List.of(table));
        var parts = write(sectioned(source));
        var root = parse(parts.get("word/document.xml"));
        var wordTable = (Element) root.getElementsByTagNameNS(W, "tbl").item(0);
        var grid = DocxAdapter.children(DocxAdapter.child(wordTable, "tblGrid"));
        long left = Long.parseLong(grid.get(0).getAttributeNS(W, "w")) + Long.parseLong(grid.get(1).getAttributeNS(W, "w"));
        long total = left + Long.parseLong(grid.get(2).getAttributeNS(W, "w"));
        assertEquals(Long.toString(total), DocxAdapter.child(DocxAdapter.child(wordTable, "tblPr"), "tblW").getAttributeNS(W, "w"));
        assertEquals("fixed", DocxAdapter.child(DocxAdapter.child(wordTable, "tblPr"), "tblLayout").getAttributeNS(W, "type"));
        for (var row : DocxAdapter.children(wordTable).stream().filter(element -> element.getLocalName().equals("tr")).toList()) {
            var cell = DocxAdapter.children(row).stream().filter(element -> element.getLocalName().equals("tc")).findFirst().orElseThrow();
            var properties = DocxAdapter.child(cell, "tcPr");
            assertEquals(Long.toString(left), DocxAdapter.child(properties, "tcW").getAttributeNS(W, "w"));
            assertEquals("2", DocxAdapter.child(properties, "gridSpan").getAttributeNS(W, "val"));
        }
        assertEquals(table, read(parts, DocxAdapter.Policy.STRICT).document().sections().getFirst().blocks().getFirst());
    }

    @Test void nestedAndRegionTablesRetainTheirIndependentGridsAndSemanticOrder() throws Exception {
        var a = Table.builder("a").columns(new ColumnWidth.Fixed(30)).row("A").build();
        var b = Table.builder("b").columns(new ColumnWidth.Fixed(40)).row("B").build();
        var outer = Table.builder("outer").columns(new ColumnWidth.Fixed(150))
                .row(TableCell.builder().format(CellFormat.of(12)).blocks(List.of(a, b)).build()).build();
        var h1 = Table.builder("h1").columns(new ColumnWidth.Fixed(80)).row("Header one").build();
        var h2 = Table.builder("h2").columns(new ColumnWidth.Fixed(90)).row("Header two").build();
        var regions = new PageRegions(new PageRegion(60, 0, List.of(h1, h2)), PageRegion.EMPTY);
        var source = new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("section", PageSettings.A4,
                List.of(outer, new Paragraph("end", "End", ParagraphFormat.of(12))), 1, regions)));
        var parts = write(source);
        assertTrue(new String(parts.get("word/header1.xml"), StandardCharsets.UTF_8).contains("od:tableSeparator=\"true\""));
        var result = read(parts, DocxAdapter.Policy.STRICT);
        var section = result.document().sections().getFirst();
        assertEquals(outer, section.blocks().getFirst());
        assertEquals(2, section.blocks().size());
        assertEquals("End", ((Paragraph) section.blocks().getLast()).text());
        assertEquals(regions, section.pageRegions());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test void forgedOrEditedSeparatorsCannotHideContentUnderEitherPolicy() throws Exception {
        var a = Table.builder("a").row("A").build();
        var b = Table.builder("b").row("B").build();
        var parts = write(sectioned(new Document(DocumentMetadata.EMPTY, PageSettings.A4, List.of(a, b))));
        String xml = new String(parts.get("word/document.xml"), StandardCharsets.UTF_8);
        for (String changed : List.of(
                xml.replace("od:tableSeparator=\"true\"", "od:tableSeparator=\"false\""),
                xml.replace("od:tableSeparator=\"true\"", "od:tableSeparator=\"true\" od:id=\"hidden\""),
                xml.replace("<w:p od:tableSeparator=\"true\">", "<w:p od:tableSeparator=\"true\"><w:r><w:t>Do not hide me</w:t></w:r>"),
                xml.replace("w:line=\"1\"", "w:line=\"20\""))) {
            parts.put("word/document.xml", changed.getBytes(StandardCharsets.UTF_8));
            for (var policy : DocxAdapter.Policy.values()) { assertThrows(IOException.class, () -> read(parts, policy)); }
        }
    }

    @Test void conflictingPreferredWidthsAreExplicitLossesRatherThanSilentlyIgnored() throws Exception {
        var table = Table.builder("table").columns(new ColumnWidth.Fixed(80)).row("Value").build();
        var parts = write(sectioned(new Document(DocumentMetadata.EMPTY, PageSettings.A4, List.of(table))));
        String xml = new String(parts.get("word/document.xml"), StandardCharsets.UTF_8);
        parts.put("word/document.xml", xml.replace("<w:tcW w:w=\"1600\"", "<w:tcW w:w=\"1200\"").getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> read(parts, DocxAdapter.Policy.STRICT));
        var result = read(parts, DocxAdapter.Policy.ALLOW_LOSS);
        assertEquals(table, result.document().sections().getFirst().blocks().getFirst());
        assertFalse(result.diagnostics().isEmpty());
        assertTrue(result.diagnostics().stream().anyMatch(issue -> issue.element().equals("tcW")));
    }

    private static Element parse(byte[] bytes) throws Exception {
        var factory = DocumentBuilderFactory.newDefaultNSInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes)).getDocumentElement();
    }

    private static SectionedDocument sectioned(Document source) {
        return new SectionedDocument(source.metadata(), List.of(new Section("document", source.pageSettings(), source.blocks(), 0)));
    }

    private static Map<String, byte[]> write(SectionedDocument source) throws IOException {
        var output = new ByteArrayOutputStream();
        new DocxAdapter().write(source, output);
        var parts = new LinkedHashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(output.toByteArray()))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) { parts.put(entry.getName(), zip.readAllBytes()); }
        }
        return parts;
    }

    private static DocxAdapter.ReadResult read(Map<String, byte[]> parts, DocxAdapter.Policy policy) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            for (var entry : parts.entrySet()) { zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry(); }
        }
        return new DocxAdapter().read(new ByteArrayInputStream(output.toByteArray()), policy);
    }
}
