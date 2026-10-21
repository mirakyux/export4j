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
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.ImageResource;
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
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class DocxRowSpanTest {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final CellFormat FORMAT = CellFormat.of(12);

    @Test
    void standardRestartContinuationsRoundTripRealAnchorsFieldsAndMediaExactlyOnce() throws IOException {
        var pixels = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", pixels);
        var a = TableCell.builder().format(FORMAT).rowSpan(3).content(List.of(
                new InlineText("A😀", new InlineStyle("bold", 12, 0x224488, -2, true, false),
                        "https://example.com/text"),
                field(InlineField.Kind.PAGE, ""), field(InlineField.Kind.PAGES, ""),
                field(InlineField.Kind.REFERENCE_PAGE, "table"), field(InlineField.Kind.REFERENCE_NUMBER, "target"),
                new InlineImage(ImageResource.load(pixels.toByteArray()), 8, 8, 0, "marker",
                        "https://example.com/image"), new InlineText(" unique-tail", InlineStyle.of(12)))).build();
        var source = new Table("table", List.of(new ColumnWidth.Fixed(40), new ColumnWidth.Fixed(50),
                new ColumnWidth.Fraction(1)), List.of(rowModel(cell("HEADER", 3, 2)), TableRow.covered(),
                rowModel(a, cell("B", 1, 1), cell("C", 1, 2)), rowModel(cell("D", 1, 2)),
                rowModel(cell("E", 1, 1)), rowModel(cell("F", 1, 1), cell("G", 1, 1), cell("H", 1, 1))),
                2, TableFormat.DEFAULT);
        var document = new Document(DocumentMetadata.EMPTY, PageSettings.A4, List.of(source,
                new NumberedItem("target", "numbers", "Target", 4, ParagraphFormat.of(12))));
        byte[] bytes = write(document);
        var packageParts = parts(bytes);
        String xml = new String(packageParts.get("word/document.xml"), StandardCharsets.UTF_8);
        assertEquals(3, count(xml, "<w:gridCol "));
        assertEquals(14, count(xml, "<w:tc>"));
        assertEquals(4, count(xml, "<w:vMerge w:val=\"restart\"/>"));
        assertEquals(5, count(xml, "<w:vMerge w:val=\"continue\"/>"));
        assertEquals(2, count(xml, "<w:gridSpan w:val=\"3\"/>"));
        assertEquals(1, count(xml, "HEADER"));
        assertEquals(1, count(xml, "A😀"));
        assertEquals(1, count(xml, "unique-tail"));
        assertEquals(4, count(xml, "<w:fldSimple "));
        assertEquals(1, count(xml, "<wp:inline"));
        assertEquals(1, packageParts.keySet().stream().filter(name -> name.startsWith("word/media/")).count());
        assertTrue(xml.contains("<w:gridSpan w:val=\"3\"/><w:vMerge w:val=\"continue\"/></w:tcPr><w:p/></w:tc>"));
        assertFalse(xml.contains("<w:hMerge"));
        var result = new DocxAdapter().read(new ByteArrayInputStream(bytes), DocxAdapter.Policy.STRICT);
        assertTrue(result.diagnostics().isEmpty());
        assertEquals(document.blocks(), result.document().sections().getFirst().blocks());
        var table = table(result);
        assertTrue(table.rows().get(1).cells().isEmpty());
        assertEquals(List.of(1, 0, 3, 1, 1, 3), table.rows().stream().map(row -> row.cells().size()).toList());
        assertSame(table.rows().get(2).cells().getFirst(), table.grid().ownerAt(4, 0).cell());
        assertEquals(3, table.grid().ownerAt(4, 0).cell().rowSpan());
        assertEquals(List.of(3, 4, 5, 6), table.rows().get(2).cells().getFirst().fields().stream()
                .map(Paragraph.FieldSpan::sourceIndex).toList());
        assertEquals(7, table.rows().get(2).cells().getFirst().images().getFirst().sourceIndex());
    }

    @Test
    void metadataFreeImportNormalizesIncomingIntervalsAndNewAnchorsBetweenThem() throws IOException {
        String body = "<w:tbl>" + grid(3)
                + row(tc(1, "restart", text("A")) + tc(1, "", text("B")) + tc(1, "restart", text("C")))
                + row(tc(1, "default", "") + tc(1, "restart", text("D")) + tc(1, "continue", ""))
                + row(tc(1, "continue", "") + tc(1, "default", "") + tc(1, "", text("E"))) + "</w:tbl>";
        var result = read(body, DocxAdapter.Policy.STRICT);
        assertTrue(result.diagnostics().isEmpty());
        var table = table(result);
        assertEquals(List.of(3, 1, 1), table.rows().stream().map(row -> row.cells().size()).toList());
        assertEquals(List.of(3, 1, 2), table.rows().getFirst().cells().stream().map(TableCell::rowSpan).toList());
        assertEquals(1, table.grid().anchorRows().get(1).getFirst().columnIndex());
        assertEquals(2, table.grid().anchorRows().get(2).getFirst().columnIndex());
        assertEquals("A", table.grid().ownerAt(2, 0).cell().text());
        assertEquals("D", table.grid().ownerAt(2, 1).cell().text());
        assertEquals(2, table.grid().ownerAt(2, 1).cell().rowSpan());
        assertTrue(table.grid().anchorAt(2, 0).isEmpty());
    }

    @Test
    void fullyCoveredRowsAndRectangularSpansNeedNoModelPlaceholdersOrPrivateMetadata() throws IOException {
        for (String columns : List.of("", "<w:tblGrid/>", grid(3))) {
            var result = read("<w:tbl>" + columns + row(tc(3, "restart", text("only-content")))
                    + row(tc(3, "default", "")) + row(tc(3, "continue", "")) + "</w:tbl>", DocxAdapter.Policy.STRICT);
            assertTrue(result.diagnostics().isEmpty());
            var table = table(result);
            assertEquals(3, table.columns().size());
            assertEquals(List.of(1, 0, 0), table.rows().stream().map(row -> row.cells().size()).toList());
            var anchor = table.rows().getFirst().cells().getFirst();
            assertEquals(3, anchor.columnSpan());
            assertEquals(3, anchor.rowSpan());
            assertSame(anchor, table.grid().ownerAt(2, 2).cell());
            assertTrue(table.grid().anchorAt(2, 2).isEmpty());
        }
    }

    @Test
    void singleRestartNormalizesToOneRowAndEndingARunNeedsNoContinuation() throws IOException {
        var table = table(read("<w:tbl>" + row(tc(1, "restart", text("first")))
                + row(tc(1, "", text("last"))) + "</w:tbl>", DocxAdapter.Policy.STRICT));
        assertEquals(List.of(1, 1), table.rows().stream().map(row -> row.cells().getFirst().rowSpan()).toList());
        String xml = new String(parts(write(document(table))).get("word/document.xml"), StandardCharsets.UTF_8);
        assertFalse(xml.contains("<w:vMerge"));
    }

    @Test
    void whitespaceAroundNativeMergeValueAndFormattedEmptyContinuationAreAccepted() throws IOException {
        String root = "<w:tc><w:tcPr><w:vMerge w:val=\" restart \"/></w:tcPr><w:p>" + text("content") + "</w:p></w:tc>";
        String continued = "<w:tc><w:tcPr><w:vMerge w:val=\" continue \"/></w:tcPr><w:p><w:pPr>"
                + "<w:spacing w:line=\"240\"/></w:pPr><w:r><w:rPr><w:b/></w:rPr><w:t></w:t></w:r></w:p><w:p/></w:tc>";
        var result = read("<w:tbl>" + row(root) + row(continued) + "</w:tbl>", DocxAdapter.Policy.STRICT);
        assertTrue(result.diagnostics().isEmpty());
        assertEquals(2, table(result).rows().getFirst().cells().getFirst().rowSpan());
    }

    @Test
    void emptyContinuationRetainsOrdinaryStyleLossAndInvalidFormatBoundaries() throws IOException {
        String body = "<w:tbl>" + row(tc(1, "restart", text("A")))
                + row(tc(1, "continue", "<w:pPr><w:pStyle w:val=\"Custom\"/></w:pPr>")) + "</w:tbl>";
        assertThrows(IOException.class, () -> read(body, DocxAdapter.Policy.STRICT));
        var result = read(body, DocxAdapter.Policy.ALLOW_LOSS);
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.element().equals("pStyle")));
        assertEquals(2, table(result).rows().getFirst().cells().getFirst().rowSpan());
        assertTrue(table(result).rows().get(1).cells().isEmpty());
        assertRejected("<w:tbl>" + row(tc(1, "restart", text("A")))
                + row(tc(1, "continue", "<w:r><w:rPr><w:sz w:val=\"-2\"/></w:rPr><w:t/></w:r>")) + "</w:tbl>");
    }

    @Test
    void orphanAfterGapShiftedPartialAndJoinedIntervalsCannotBeGuessedInEitherPolicy() {
        for (String rows : List.of(
                row(tc(1, "continue", "")),
                row(tc(1, "restart", text("A"))) + row(tc(1, "", text("B"))) + row(tc(1, "default", "")),
                row(tc(2, "restart", text("A")) + tc(1, "", text("B")))
                        + row(tc(1, "continue", "") + tc(2, "", text("C"))),
                row(tc(1, "", text("A")) + tc(2, "restart", text("B")))
                        + row(tc(2, "continue", "") + tc(1, "", text("C"))),
                row(tc(1, "restart", text("A")) + tc(1, "restart", text("B")))
                        + row(tc(2, "continue", "")),
                row(tc(2, "restart", text("A"))) + row(tc(1, "", text("B")) + tc(1, "continue", "")))) {
            assertRejected("<w:tbl>" + rows + "</w:tbl>");
        }
    }

    @Test
    void missingOverrunAndOmittedGridSlotsAlwaysFail() {
        for (String rows : List.of(
                row(tc(2, "restart", text("A"))) + row(tc(1, "continue", "")),
                row(tc(2, "restart", text("A"))) + row(tc(2, "continue", "") + tc(1, "", text("extra"))),
                row(tc(2, "restart", text("A"))) + "<w:tr><w:trPr><w:gridBefore w:val=\"1\"/></w:trPr>"
                        + tc(1, "continue", "") + "</w:tr>",
                row(tc(2, "restart", text("A"))) + "<w:tr><w:trPr><w:gridAfter w:val=\"1\"/></w:trPr>"
                        + tc(2, "continue", "") + "</w:tr>")) {
            assertRejected("<w:tbl>" + grid(2) + rows + "</w:tbl>");
        }
    }

    @Test
    void duplicateAndMalformedMergePropertiesNeverDegradeToOrdinaryCells() {
        for (String properties : List.of(
                "<w:tcPr><w:vMerge w:val=\"restart\"/><w:vMerge/></w:tcPr>",
                "<w:tcPr><w:vMerge w:val=\"restart\"/></w:tcPr><w:tcPr><w:vMerge/></w:tcPr>",
                "<w:tcPr><w:gridSpan w:val=\"1\"/><w:gridSpan w:val=\"1\"/><w:vMerge w:val=\"restart\"/></w:tcPr>",
                "<w:tcPr><w:vMerge w:val=\"restart\" val=\"continue\"/></w:tcPr>",
                "<w:tcPr><w:vMerge val=\"restart\"/></w:tcPr>",
                "<w:tcPr><w:vMerge w:val=\"restart\" od:value=\"continue\"/></w:tcPr>",
                "<w:tcPr><w:vMerge w:val=\"restart\" w:other=\"1\"/></w:tcPr>",
                "<w:tcPr><w:vMerge w:val=\"restart\">content</w:vMerge></w:tcPr>",
                "<w:tcPr><w:vMerge w:val=\"restart\">\u2003</w:vMerge></w:tcPr>",
                "<w:tcPr><w:vMerge w:val=\"restart\"><w:b/></w:vMerge></w:tcPr>")) {
            assertRejected("<w:tbl>" + row("<w:tc>" + properties + "<w:p/></w:tc>") + "</w:tbl>");
        }
        for (String value : List.of("", "RESTART", "Continue", "1", "none", "\u2003restart")) {
            assertRejected("<w:tbl>" + row("<w:tc><w:tcPr><w:vMerge w:val=\"" + value
                    + "\"/></w:tcPr><w:p/></w:tc>") + "</w:tbl>");
        }
    }

    @Test
    void misplacedMergeCannotHideUnderKnownOrForeignContainers() {
        for (String body : List.of(
                "<w:p><w:pPr><w:vMerge w:val=\"restart\"/></w:pPr></w:p>",
                "<w:p><w:tcPr><w:vMerge w:val=\"restart\"/></w:tcPr></w:p>",
                "<w:tbl>" + row("<w:tc><w:vMerge w:val=\"restart\"/><w:p/></w:tc>") + "</w:tbl>",
                "<w:tbl>" + row("<w:tc><od:tcPr><w:vMerge w:val=\"restart\"/></od:tcPr><w:p/></w:tc>") + "</w:tbl>")) {
            assertRejected(body);
        }
        for (String geometry : List.of("<w:vMerge w:val=\"restart\"/>", "<w:gridSpan w:val=\"2\"/>")) {
            String cell = "<w:tc><w:tcPr>" + geometry + "</w:tcPr><w:p/></w:tc>";
            assertRejected("<w:p>" + cell + "</w:p>");
            assertRejected("<w:p><w:tr>" + cell + "</w:tr></w:p>");
            assertRejected("<w:tbl><od:tr>" + cell + "</od:tr></w:tbl>");
        }
    }

    @Test
    void anyContinuationTextImageFieldLinkOrNestedContentIsRejectedEvenWithLossAllowed() {
        for (String content : List.of(text("content"), text(" "), text("\u2003"),
                "<w:r><w:tab/></w:r>", "<w:r><w:br/></w:r>", "<w:r><w:drawing/></w:r>",
                "<w:fldSimple w:instr=\"PAGE\" od:width=\"24\">" + text("1") + "</w:fldSimple>",
                "<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r>",
                "<w:hyperlink><w:r><w:t></w:t></w:r></w:hyperlink>",
                "<w:bookmarkStart w:id=\"1\" w:name=\"hidden\"/><w:bookmarkEnd w:id=\"1\"/>",
                "<w:pPr><w:numPr><w:numId w:val=\"1\"/></w:numPr></w:pPr>", "<od:unknown/>")) {
            assertRejected("<w:tbl>" + row(tc(1, "restart", text("A"))) + row(tc(1, "continue", content)) + "</w:tbl>");
        }
        String nested = "<w:tbl>" + row(tc(1, "", text("nested"))) + "</w:tbl>";
        assertRejected("<w:tbl>" + row(tc(1, "restart", text("A")))
                + row("<w:tc><w:tcPr><w:vMerge/></w:tcPr><w:p/>" + nested + "</w:tc>") + "</w:tbl>");
        assertRejected("<w:tbl>" + row(tc(1, "restart", text("A")))
                + row("<w:tc>hidden<w:tcPr><w:vMerge/></w:tcPr><w:p/></w:tc>") + "</w:tbl>");
        assertRejected("<w:tbl>" + row(tc(1, "restart", text("A")))
                + row("<w:tc><w:tcPr><w:vMerge/></w:tcPr></w:tc>") + "</w:tbl>");
    }

    @Test
    void mergedHeadersStayInHeaderRegionAndCannotCrossIntoBody() throws IOException {
        String start = headerRow(tc(2, "restart", text("HEADER")));
        var valid = table(read("<w:tbl>" + start + headerRow(tc(2, "continue", ""))
                + row(tc(2, "", text("BODY"))) + "</w:tbl>", DocxAdapter.Policy.STRICT));
        assertEquals(2, valid.headerRows());
        assertTrue(valid.rows().get(1).cells().isEmpty());
        assertEquals(2, valid.rows().getFirst().cells().getFirst().rowSpan());
        assertRejected("<w:tbl>" + start + row(tc(2, "continue", "")) + "</w:tbl>");
        assertRejected("<w:tbl>" + row(tc(1, "", text("BODY")))
                + headerRow(tc(1, "restart", text("HEADER"))) + "</w:tbl>");
    }

    @Test
    void repeatedMergedHeaderPageFieldStillFailsBeforeOutput() {
        var header = TableCell.builder().format(FORMAT).columnSpan(2).rowSpan(2)
                .content(List.of(field(InlineField.Kind.PAGE, ""))).build();
        var table = new Table("table", List.of(new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1)),
                List.of(rowModel(header), TableRow.covered(), rowModel(cell("BODY", 2, 1))), 2, TableFormat.DEFAULT);
        var output = new ByteArrayOutputStream();
        assertThrows(IOException.class, () -> new DocxAdapter().write(document(table), output));
        assertEquals(0, output.size());
    }

    @Test
    void absentGridInferenceRemainsBoundedAndContinuationCoverageCannotOverflow() {
        assertRejected("<w:tbl>" + row(tc(Table.MAX_INFERRED_COLUMNS + 1, "restart", text("A")))
                + row(tc(Table.MAX_INFERRED_COLUMNS + 1, "continue", "")) + "</w:tbl>");
        assertRejected("<w:tbl>" + row(tc(Integer.MAX_VALUE, "restart", text("A")) + tc(1, "", text("B"))) + "</w:tbl>");
        assertRejected("<w:tbl>" + grid(2) + row(tc(2, "restart", text("A")))
                + row(tc(Integer.MAX_VALUE, "continue", "")) + "</w:tbl>");
    }

    @Test
    void longCoveredSpanExportsWithLinearCheckpointBudgetAndCancellationRestoresScope() throws IOException {
        int rowCount = 2048;
        var rows = new ArrayList<TableRow>(rowCount);
        rows.add(rowModel(cell("only-source", 1, rowCount)));
        for (int row = 1; row < rowCount; row++) { rows.add(TableRow.covered()); }
        var document = document(new Table("long-merge", List.of(new ColumnWidth.Fraction(1)),
                rows, 0, TableFormat.DEFAULT));
        var checkpoints = new AtomicInteger();
        var bounded = ExecutionOptions.builder()
                .cancelled(() -> checkpoints.incrementAndGet() > rowCount * 16).build();
        byte[] bytes = ExecutionContext.execute(bounded, () -> write(document));
        String xml = new String(parts(bytes).get("word/document.xml"), StandardCharsets.UTF_8);
        assertEquals(rowCount, count(xml, "<w:tr>"));
        assertEquals(1, count(xml, "only-source"));
        assertEquals(1, count(xml, "<w:vMerge w:val=\"restart\"/>"));
        assertEquals(rowCount - 1, count(xml, "<w:vMerge w:val=\"continue\"/>"));
        assertTrue(checkpoints.get() < rowCount * 16);

        checkpoints.set(0);
        var cancelled = ExecutionOptions.builder()
                .cancelled(() -> checkpoints.incrementAndGet() >= rowCount * 4).build();
        var output = new ByteArrayOutputStream();
        var failure = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(cancelled, () -> {
            new DocxAdapter().write(document, output);
            return null;
        }));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code());
        assertEquals(rowCount * 4, checkpoints.get());
        assertEquals(0, output.size());
        ExecutionContext.checkpoint();
        assertTrue(write(document(new Table("recovered", List.of(new ColumnWidth.Fraction(1)),
                List.of(rowModel(cell("after-cancel", 1, 1))), 0, TableFormat.DEFAULT))).length > 0);
    }

    private static InlineField field(InlineField.Kind kind, String target) {
        return new InlineField(kind, target, 24, InlineStyle.of(12));
    }

    private static TableCell cell(String value, int columns, int rows) {
        return TableCell.builder().text(value).format(FORMAT).columnSpan(columns).rowSpan(rows).build();
    }

    private static TableRow rowModel(TableCell... cells) { return new TableRow(List.of(cells)); }
    private static Document document(Table table) { return new Document(DocumentMetadata.EMPTY, PageSettings.A4, List.of(table)); }
    private static String grid(int count) { return "<w:tblGrid>" + "<w:gridCol w:w=\"800\"/>".repeat(count) + "</w:tblGrid>"; }
    private static String row(String cells) { return "<w:tr>" + cells + "</w:tr>"; }
    private static String headerRow(String cells) { return "<w:tr><w:trPr><w:tblHeader/></w:trPr>" + cells + "</w:tr>"; }
    private static String text(String value) { return "<w:r><w:t xml:space=\"preserve\">" + value + "</w:t></w:r>"; }

    private static String tc(int span, String merge, String content) {
        return "<w:tc><w:tcPr>" + (span == 1 ? "" : "<w:gridSpan w:val=\"" + span + "\"/>")
                + (merge.isEmpty() ? "" : merge.equals("default") ? "<w:vMerge/>" : "<w:vMerge w:val=\"" + merge + "\"/>")
                + "</w:tcPr><w:p>" + content + "</w:p></w:tc>";
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

    private static byte[] write(Document document) throws IOException {
        var bytes = new ByteArrayOutputStream();
        new DocxAdapter().write(document, bytes);
        return bytes.toByteArray();
    }

    private static Table table(DocxAdapter.ReadResult result) {
        return (Table) result.document().sections().getFirst().blocks().getFirst();
    }

    private static int count(String value, String token) { return value.split(java.util.regex.Pattern.quote(token), -1).length - 1; }

    private static Map<String, byte[]> parts(byte[] bytes) throws IOException {
        var result = new LinkedHashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                result.put(entry.getName(), zip.readAllBytes());
            }
        }
        return result;
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
