// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
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

class DocxColumnSpanTest {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final CellFormat FORMAT = CellFormat.of(12);

    @Test
    void writerEmitsStandardGridSpanWithRealCellsAndRoundTripsAllCellSemantics() throws IOException {
        var pixels = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", pixels);
        var field = new InlineField(InlineField.Kind.PAGE, "", 24, InlineStyle.of(12));
        var image = new InlineImage(ImageResource.load(pixels.toByteArray()), 8, 8, 0, "marker",
                "https://example.com/image");
        var merged = TableCell.builder().format(FORMAT).columnSpan(2).content(List.of(
                new InlineText("A😀", new InlineStyle("bold", 12, 0x224488, -2, true, false),
                        "https://example.com/text"), field, image,
                new InlineText(" tail", InlineStyle.of(12)))).build();
        var table = Table.builder("table").columns(new ColumnWidth.Fixed(40), new ColumnWidth.Fraction(1),
                new ColumnWidth.Fraction(2)).format(format -> format.allowRowSplit(true))
                .headerCells(cell("all columns", 3)).row(merged, cell("after merge", 1))
                .row("A", "B", "C").build();
        byte[] bytes = write(table);
        var packageParts = parts(bytes);
        String xml = new String(packageParts.get("word/document.xml"), StandardCharsets.UTF_8);
        assertEquals(3, count(xml, "<w:gridCol "));
        assertEquals(6, count(xml, "<w:tc>"));
        assertEquals(2, count(xml, "<w:gridSpan "));
        assertTrue(xml.contains("<w:gridSpan w:val=\"3\"/></w:tcPr><w:p>"));
        assertTrue(xml.contains("<w:gridSpan w:val=\"2\"/></w:tcPr><w:p>"));
        assertFalse(xml.contains("<w:hMerge"));
        assertFalse(xml.contains("<w:vMerge"));
        assertEquals(1, packageParts.keySet().stream().filter(name -> name.startsWith("word/media/")).count());
        assertTrue(xml.contains("w:instr=\"PAGE\""));
        var result = new DocxAdapter().read(new ByteArrayInputStream(bytes), DocxAdapter.Policy.STRICT);
        assertTrue(result.diagnostics().isEmpty());
        assertEquals(table, table(result));
        var row = table(result).rows().get(1);
        assertSame(row.cells().getFirst(), row.cellAtColumn(1));
        assertEquals("after merge", row.cellAtColumn(2).text());
        assertEquals(3, row.cellAtColumn(0).fields().getFirst().sourceIndex());
        assertEquals(4, row.cellAtColumn(0).images().getFirst().sourceIndex());
    }

    @Test
    void nativeGridSpanNeedsNoPrivateMetadataAndRetainsPhysicalAnchorMapping() throws IOException {
        String body = "<w:tbl>" + grid(3) + row(cellXml("2", "merged") + cellXml("", "last"))
                + row(cellXml("", "first") + cellXml("2", "right")) + "</w:tbl>";
        var result = read(body, DocxAdapter.Policy.STRICT);
        assertTrue(result.diagnostics().isEmpty());
        var table = table(result);
        assertEquals(List.of(new ColumnWidth.Fixed(40), new ColumnWidth.Fixed(40), new ColumnWidth.Fixed(40)),
                table.columns());
        assertEquals(List.of(2, 1), table.rows().getFirst().cells().stream().map(TableCell::columnSpan).toList());
        assertEquals(2, table.rows().getFirst().cells().size());
        assertSame(table.rows().getFirst().cells().getFirst(), table.rows().getFirst().cellAtColumn(1));
        assertEquals("last", table.rows().getFirst().cellAtColumn(2).text());
        assertEquals("right", table.rows().getLast().cellAtColumn(2).text());
    }

    @Test
    void absentOrEmptyGridIsInferredFromSpansWithStandardIntegerLexicalForms() throws IOException {
        for (String grid : List.of("", "<w:tblGrid/>")) {
            var table = table(read("<w:tbl>" + grid + row(cellXml(" +002 ", "merged") + cellXml("1", "last"))
                    + row(cellXml("3", "whole")) + "</w:tbl>", DocxAdapter.Policy.STRICT));
            assertEquals(3, table.columns().size());
            assertTrue(table.columns().stream().allMatch(width -> width.equals(new ColumnWidth.Fraction(1))));
            assertEquals(3, table.rows().getLast().cells().getFirst().columnSpan());
        }
    }

    @Test
    void mergedCellParagraphsRetainLeafLocalUtf16OffsetsAndTrailingAnchor() throws IOException {
        String content = "<w:tcPr><w:gridSpan w:val=\"2\"/></w:tcPr><w:p>" + text("😀")
                + nativeField("PAGE", 30) + "</w:p><w:p>" + text("B") + nativeField("NUMPAGES", 40) + "</w:p>";
        var table = table(read("<w:tbl>" + row("<w:tc>" + content + "</w:tc>" + cellXml("", "last"))
                + "</w:tbl>", DocxAdapter.Policy.STRICT));
        var row = table.rows().getFirst();
        assertEquals(3, table.columns().size());
        var cell = row.cellAtColumn(1);
        assertEquals(2, cell.columnSpan());
        var first = (Paragraph) cell.blocks().getFirst();
        var second = (Paragraph) cell.blocks().getLast();
        assertEquals("😀\ufffc", first.text());
        assertEquals("B\ufffc", second.text());
        assertEquals(2, first.fields().getFirst().sourceIndex());
        assertEquals(1, second.fields().getFirst().sourceIndex());
        assertEquals(40, second.fields().getFirst().field().width());
        assertFalse(first.id().equals(second.id()));
        assertEquals("last", row.cellAtColumn(2).text());
    }

    @Test
    void invalidOrOversizedSpanValuesFailUnderBothPolicies() {
        for (String value : List.of("", "0", "-1", "-0", "+0", "1.5", "1e1", "two", "2147483648",
                "99999999999999999999999999999999", "٢", "\u20032")) {
            assertRejected(tableWithProperties("<w:tcPr><w:gridSpan w:val=\"" + value + "\"/></w:tcPr>"));
        }
        assertRejected(tableWithProperties("<w:tcPr><w:gridSpan/></w:tcPr>"));
        assertRejected(tableWithProperties("<w:tcPr><w:gridSpan val=\"2\"/></w:tcPr>"));
    }

    @Test
    void duplicatePropertiesAndAmbiguousGeometryAlwaysFail() {
        for (String properties : List.of(
                "<w:tcPr/><w:tcPr/>",
                "<w:tcPr><w:gridSpan w:val=\"2\"/></w:tcPr><w:tcPr/>",
                "<w:tcPr><w:gridSpan w:val=\"2\"/><w:gridSpan w:val=\"2\"/></w:tcPr>",
                "<w:tcPr><w:gridSpan w:val=\"1\"/><w:gridSpan w:val=\"2\"/></w:tcPr>")) {
            assertRejected(tableWithProperties(properties));
        }
        assertRejected("<w:tbl>" + grid(2) + grid(2) + row(cellXml("2", "cell")) + "</w:tbl>");
    }

    @Test
    void attributesOrContentCannotAlterSupportedSpanGeometryInLossMode() {
        for (String attributes : List.of(" w:other=\"2\"", " val=\"2\"", " od:value=\"2\"")) {
            assertRejected(tableWithProperties("<w:tcPr><w:gridSpan w:val=\"2\"" + attributes + "/></w:tcPr>"));
        }
        for (String attributes : List.of(" w:val=\"2\"", " value=\"2\"", " od:value=\"2\"")) {
            assertRejected(tableWithProperties("<w:tcPr" + attributes + "><w:gridSpan w:val=\"2\"/></w:tcPr>"));
        }
        for (String content : List.of("2", "<w:gridSpan w:val=\"2\"/>", "<od:ignored/>")) {
            assertRejected(tableWithProperties("<w:tcPr><w:gridSpan w:val=\"2\">" + content
                    + "</w:gridSpan></w:tcPr>"));
        }
        assertRejected(tableWithProperties("<w:tcPr>ignored<w:gridSpan w:val=\"2\"/></w:tcPr>"));
    }

    @Test
    void misplacedSpanCannotBeIgnoredOrReinterpretedUnderEitherPolicy() {
        for (String body : List.of(
                "<w:p><w:pPr><w:gridSpan w:val=\"2\"/></w:pPr></w:p>",
                "<w:p><w:tcPr><w:gridSpan w:val=\"2\"/></w:tcPr></w:p>",
                "<w:p><w:r><w:gridSpan w:val=\"2\"/></w:r></w:p>",
                "<w:tbl>" + row("<w:tc><w:gridSpan w:val=\"2\"/><w:p/></w:tc>") + "</w:tbl>",
                "<w:tbl>" + row("<w:tc><od:tcPr><w:gridSpan w:val=\"2\"/></od:tcPr><w:p/></w:tc>") + "</w:tbl>")) {
            assertRejected(body);
        }
    }

    @Test
    void incompleteOverrunAndOverflowCoverageNeverProducesAFalseRectangularTable() {
        for (String body : List.of(
                "<w:tbl>" + grid(2) + row(cellXml("3", "overrun")) + "</w:tbl>",
                "<w:tbl>" + grid(2) + row(cellXml("1", "incomplete")) + "</w:tbl>",
                "<w:tbl>" + grid(2) + row(cellXml("2", "merged") + cellXml("1", "overrun")) + "</w:tbl>",
                "<w:tbl>" + row(cellXml("2", "first")) + row(cellXml("1", "short")) + "</w:tbl>",
                "<w:tbl>" + row(cellXml("2147483647", "huge") + cellXml("1", "overflow")) + "</w:tbl>")) {
            assertRejected(body);
        }
    }

    @Test
    void inferredGridHasABoundedSpanLimitWhileExplicitGridRemainsExplicit() throws IOException {
        assertRejected("<w:tbl>" + row(cellXml(Integer.toString(Table.MAX_INFERRED_COLUMNS + 1), "huge"))
                + "</w:tbl>");
        assertRejected("<w:tbl>" + row(cellXml("2147483647", "huge")) + "</w:tbl>");
        var atLimit = table(read("<w:tbl>" + row(cellXml(Integer.toString(Table.MAX_INFERRED_COLUMNS), "limit"))
                + "</w:tbl>", DocxAdapter.Policy.STRICT));
        assertEquals(Table.MAX_INFERRED_COLUMNS, atLimit.columns().size());
        int explicitCount = Table.MAX_INFERRED_COLUMNS + 1;
        var explicit = table(read("<w:tbl>" + grid(explicitCount) + row(cellXml(Integer.toString(explicitCount), "explicit"))
                + "</w:tbl>", DocxAdapter.Policy.STRICT));
        assertEquals(explicitCount, explicit.columns().size());
    }

    @Test
    void legacyHorizontalMergesRetainLossBoundariesAndNestedGridsRemainIndependent() throws IOException {
        for (String merge : List.of("hMerge")) {
            String body = tableWithProperties("<w:tcPr><w:gridSpan w:val=\"2\"/><w:" + merge
                    + " w:val=\"restart\"/></w:tcPr>");
            assertThrows(IOException.class, () -> read(body, DocxAdapter.Policy.STRICT));
            var result = read(body, DocxAdapter.Policy.ALLOW_LOSS);
            assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.element().equals(merge)));
            assertEquals(2, table(result).rows().getFirst().cells().getFirst().columnSpan());
        }
        String nested = "<w:tbl>" + row(cellXml("2", "nested")) + "</w:tbl>";
        String outer = "<w:tbl>" + row("<w:tc><w:tcPr><w:gridSpan w:val=\"2\"/></w:tcPr><w:p>"
                + text("before") + "</w:p>" + nested + "<w:p>" + text("after") + "</w:p></w:tc>") + "</w:tbl>";
        var result = read(outer, DocxAdapter.Policy.STRICT);
        assertTrue(result.diagnostics().isEmpty());
        var blocks = table(result).rows().getFirst().cells().getFirst().blocks();
        assertEquals("before", ((Paragraph) blocks.getFirst()).text());
        assertEquals("after", ((Paragraph) blocks.getLast()).text());
        assertEquals(2, ((Table) blocks.get(1)).columns().size());
        assertEquals(2, ((Table) blocks.get(1)).rows().getFirst().cells().getFirst().columnSpan());
        assertEquals(2, table(result).columns().size());
    }

    @Test
    void repeatedHeaderPageFieldInMergedCellStillFailsBeforeWritingAnyBytes() {
        var header = TableCell.builder().columnSpan(2).format(FORMAT)
                .content(List.of(new InlineField(InlineField.Kind.PAGE, "", 24, InlineStyle.of(12)))).build();
        var table = Table.builder("table").headerCells(header).row("left", "right").build();
        var output = new ByteArrayOutputStream();
        assertThrows(IOException.class, () -> new DocxAdapter().write(document(table), output));
        assertEquals(0, output.size());
    }

    private static TableCell cell(String text, int span) {
        return TableCell.builder().text(text).format(FORMAT).columnSpan(span).build();
    }

    private static Document document(Table table) {
        return new Document(DocumentMetadata.EMPTY, PageSettings.A4, List.of(table));
    }

    private static byte[] write(Table table) throws IOException {
        var bytes = new ByteArrayOutputStream();
        new DocxAdapter().write(document(table), bytes);
        return bytes.toByteArray();
    }

    private static Table table(DocxAdapter.ReadResult result) {
        return (Table) result.document().sections().getFirst().blocks().getFirst();
    }

    private static String grid(int count) {
        return "<w:tblGrid>" + "<w:gridCol w:w=\"800\"/>".repeat(count) + "</w:tblGrid>";
    }

    private static String row(String cells) { return "<w:tr>" + cells + "</w:tr>"; }

    private static String text(String value) { return "<w:r><w:t>" + value + "</w:t></w:r>"; }

    private static String cellXml(String span, String value) {
        return "<w:tc>" + (span.isEmpty() ? "" : "<w:tcPr><w:gridSpan w:val=\"" + span + "\"/></w:tcPr>")
                + "<w:p>" + text(value) + "</w:p></w:tc>";
    }

    private static String tableWithProperties(String properties) {
        return "<w:tbl>" + row("<w:tc>" + properties + "<w:p>" + text("cell") + "</w:p></w:tc>") + "</w:tbl>";
    }

    private static String nativeField(String instruction, int width) {
        return "<w:fldSimple w:instr=\"" + instruction + "\" od:width=\"" + width + "\">"
                + text("stale") + "</w:fldSimple>";
    }

    private static void assertRejected(String body) {
        for (var policy : DocxAdapter.Policy.values()) {
            assertThrows(IOException.class, () -> read(body, policy), policy + ": " + body);
        }
    }

    private static DocxAdapter.ReadResult read(String body, DocxAdapter.Policy policy) throws IOException {
        String xml = "<w:document xmlns:w=\"" + W
                + "\" xmlns:od=\"urn:com.mirakyux.export4j:semantic:1\"><w:body>" + body + "</w:body></w:document>";
        return new DocxAdapter().read(new ByteArrayInputStream(pack(Map.of(
                "[Content_Types].xml", "<Types/>".getBytes(StandardCharsets.UTF_8),
                "word/document.xml", xml.getBytes(StandardCharsets.UTF_8)))), policy);
    }

    private static int count(String value, String token) { return value.split(java.util.regex.Pattern.quote(token), -1).length - 1; }

    private static Map<String, byte[]> parts(byte[] bytes) throws IOException {
        var parts = new LinkedHashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                parts.put(entry.getName(), zip.readAllBytes());
            }
        }
        return parts;
    }

    private static byte[] pack(Map<String, byte[]> parts) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (var part : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(part.getKey()));
                zip.write(part.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
