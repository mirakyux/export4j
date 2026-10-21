// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class DocxNestedCellsTest {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String OD = "urn:com.mirakyux.export4j:semantic:1";
    private static final CellFormat CELL = CellFormat.of(12);
    private static final ParagraphFormat PARAGRAPH = ParagraphFormat.of(12);

    @Test
    void orderedBlockCellsRoundTripIndependentMergesStylesSharedMediaAndFourFields() throws IOException {
        var pixels = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", pixels);
        var resource = ImageResource.load(pixels.toByteArray());
        var image = new InlineImage(resource, 8, 8, 0, "marker", "https://example.com/image");
        var ink = new InlineStyle("bold", 12, 0x224488, 0, true, false);
        var first = Paragraph.mixed("first", List.of(new InlineText("P😀", ink, "https://example.com/text"),
                field(InlineField.Kind.PAGE, ""), field(InlineField.Kind.PAGES, ""),
                field(InlineField.Kind.REFERENCE_PAGE, "inner"), field(InlineField.Kind.REFERENCE_NUMBER, "number"), image),
                new ParagraphFormat(12, 18, 2, 3, ParagraphAlignment.RIGHT, 2, 1, true));
        var inner = new Table("inner", List.of(new ColumnWidth.Fixed(70), new ColumnWidth.Fraction(1)),
                List.of(new TableRow(List.of(TableCell.builder().rowSpan(2).content(List.of(image,
                        new InlineText("anchor", InlineStyle.of(12)))).build(), new TableCell("B", CELL))),
                        new TableRow(List.of(new TableCell("C", CELL)))), 0, TableFormat.DEFAULT);
        var last = Paragraph.mixed("last", List.of(new InlineText("Last", InlineStyle.of(10)),
                new InlineField(InlineField.Kind.REFERENCE_PAGE, "first", 24, InlineStyle.of(10)),
                new InlineField(InlineField.Kind.REFERENCE_PAGE, "outer", 24, InlineStyle.of(10))), ParagraphFormat.of(10));
        var outer = new Table("outer", List.of(new ColumnWidth.Fixed(100), new ColumnWidth.Fraction(1)),
                List.of(new TableRow(List.of(TableCell.builder().format(new CellFormat(16, 24, ParagraphAlignment.CENTER))
                        .columnSpan(2).rowSpan(2).blocks(List.of(first, inner, last)).build())), TableRow.covered()), 0, TableFormat.DEFAULT);
        var source = document(List.of(outer, new NumberedItem("number", "numbers", "Target", 1, PARAGRAPH)));
        var bytes = write(new DocxAdapter(), source);
        var parts = parts(bytes);
        String xml = new String(parts.get("word/document.xml"), StandardCharsets.UTF_8);
        assertEquals(2, count(xml, "<w:tbl "));
        assertEquals(4, count(xml, "<w:gridCol "));
        assertEquals(2, count(xml, "<w:vMerge w:val=\"restart\"/>"));
        assertEquals(2, count(xml, "<w:vMerge w:val=\"continue\"/>"));
        assertEquals(6, count(xml, "<w:fldSimple "));
        assertEquals(1, parts.keySet().stream().filter(name -> name.startsWith("word/media/")).count());
        assertEquals(2, count(xml, "<wp:inline"));
        var result = read(new DocxAdapter(), bytes, DocxAdapter.Policy.STRICT);
        assertTrue(result.diagnostics().isEmpty());
        assertEquals(source.blocks(), result.document().sections().getFirst().blocks());
        assertEquals(List.of(3, 4, 5, 6), ((Paragraph) ((Table) result.document().sections().getFirst().blocks().getFirst())
                .rows().getFirst().cells().getFirst().blocks().getFirst()).fields().stream().map(Paragraph.FieldSpan::sourceIndex).toList());
        // Native child tblGrid uses its containing merged cell's content width, not the page width.
        double nestedFraction = PageSettings.A4.contentWidth() - 12 - 70;
        assertTrue(xml.contains("<w:gridCol w:w=\"" + Math.round(nestedFraction * 20) + "\" od:weight=\"1.0\"/>"));
    }

    @Test
    void tableAndParagraphTargetsRemainDistinctEvenWhenTheyShareTheFirstPhysicalParagraph() throws IOException {
        var target = new Paragraph("target-p", "Target", PARAGRAPH);
        var inner = single("target-table", TableCell.builder().blocks(List.of(target)).build());
        var outer = single("outer", TableCell.builder().blocks(List.of(inner)).build());
        var references = Paragraph.mixed("refs", List.of(field(InlineField.Kind.REFERENCE_PAGE, "outer"),
                field(InlineField.Kind.REFERENCE_PAGE, "target-table"), field(InlineField.Kind.REFERENCE_PAGE, "target-p")), PARAGRAPH);
        var source = document(List.of(outer, references));
        var bytes = write(new DocxAdapter(), source);
        var result = read(new DocxAdapter(), bytes, DocxAdapter.Policy.STRICT);
        var actualOuter = (Table) result.document().sections().getFirst().blocks().getFirst();
        var actualInner = (Table) actualOuter.rows().getFirst().cells().getFirst().blocks().getFirst();
        var actualTarget = (Paragraph) actualInner.rows().getFirst().cells().getFirst().blocks().getFirst();
        assertEquals("outer", actualOuter.id());
        assertEquals("target-table", actualInner.id());
        assertEquals(target.id(), actualTarget.id());
        assertEquals(target.text(), actualTarget.text());
        assertEquals(target.format(), actualTarget.format());
        assertEquals(references, result.document().sections().getFirst().blocks().getLast());
        String xml = new String(parts(bytes).get("word/document.xml"), StandardCharsets.UTF_8);
        assertEquals(1, count(xml, "od:cellTail=\"true\""));
        assertEquals(3, count(xml, "<w:bookmarkStart "));
    }

    @Test
    void singleParagraphBranchAndRealEmptyParagraphsSurviveWithoutGuessing() throws IOException {
        var single = single("single", TableCell.builder().blocks(List.of(new Paragraph("empty", "", PARAGRAPH))).build());
        assertEquals(List.of(single), read(new DocxAdapter(), write(new DocxAdapter(), document(List.of(single))),
                DocxAdapter.Policy.STRICT).document().sections().getFirst().blocks());
        String nativeXml = table(p("before") + table(p("nested")) + "<w:p/>");
        var result = read(new DocxAdapter(), packageXml(nativeXml), DocxAdapter.Policy.STRICT);
        var cell = ((Table) result.document().sections().getFirst().blocks().getFirst()).rows().getFirst().cells().getFirst();
        assertEquals(3, cell.blocks().size());
        assertEquals("before", ((Paragraph) cell.blocks().getFirst()).text());
        assertEquals("", ((Paragraph) cell.blocks().getLast()).text());
        assertFalse(cell.blocks().getFirst().id().equals(cell.blocks().getLast().id()));
        var paragraphOnly = read(new DocxAdapter(), packageXml(table(p("one") + "<w:p/>" + p("three"))), DocxAdapter.Policy.STRICT);
        assertEquals(3, ((Table) paragraphOnly.document().sections().getFirst().blocks().getFirst()).rows().getFirst().cells().getFirst().blocks().size());
    }

    @Test
    void nativeBookmarksResolveToNearestModeledParagraphAndNestedTable() throws IOException {
        String start = "<w:bookmarkStart w:id=\"1\" w:name=\"native\"/>";
        String end = "<w:bookmarkEnd w:id=\"1\"/>";
        String target = "<w:p>" + start + "<w:r><w:t>target</w:t></w:r>" + end + "</w:p>";
        String reference = "<w:p><w:fldSimple w:instr=\"PAGEREF native\"><w:r><w:t>1</w:t></w:r></w:fldSimple></w:p>";
        byte[] bytes = packageXml(table(target + table(p("inner"))) + reference);
        var result = new DocxAdapter().read(new ByteArrayInputStream(bytes), DocxAdapter.Policy.STRICT, 24);
        var outer = (Table) result.document().sections().getFirst().blocks().getFirst();
        var refs = (Paragraph) result.document().sections().getFirst().blocks().getLast();
        assertEquals(outer.rows().getFirst().cells().getFirst().blocks().getFirst().id(), refs.fields().getFirst().field().targetId());
        bytes = packageXml(table(p("before") + table(target)) + reference);
        result = new DocxAdapter().read(new ByteArrayInputStream(bytes), DocxAdapter.Policy.STRICT, 24);
        outer = (Table) result.document().sections().getFirst().blocks().getFirst();
        var inner = (Table) outer.rows().getFirst().cells().getFirst().blocks().getLast();
        refs = (Paragraph) result.document().sections().getFirst().blocks().getLast();
        assertEquals(inner.id(), refs.fields().getFirst().field().targetId());
    }

    @Test
    void malformedStructuralTailsAndOwnerHintsFailInBothPolicies() throws IOException {
        for (String tail : List.of("<w:p od:cellTail=\"true\"><w:r><w:t>lost</w:t></w:r></w:p>",
                "<w:p od:cellTail=\"true\" od:id=\"lost\"/>", "<w:p od:cellTail=\"true\"><w:pPr/></w:p>",
                "<w:p od:cellTail=\"false\"/>", "<w:p od:cellTail=\"true\"/>" + p("after"))) {
            rejectBoth(table(table(p("inner")) + tail));
        }
        rejectBoth("<w:p od:cellTail=\"true\"/>");
        rejectBoth(table("<w:p od:cellTail=\"true\"/>"));
        String bookmark = "<w:p od:id=\"p\"><w:bookmarkStart w:id=\"1\" w:name=\"target\" od:owner=\"other\"/>"
                + "<w:bookmarkEnd w:id=\"1\" od:owner=\"other\"/></w:p>";
        rejectBoth(table(bookmark + table(p("inner"))));
        rejectBoth(table("<w:p od:id=\"p\"><w:r><w:t>first</w:t></w:r>"
                + "<w:bookmarkStart w:id=\"1\" w:name=\"target\" od:owner=\"p\"/>"
                + "<w:bookmarkEnd w:id=\"1\" od:owner=\"p\"/></w:p>" + table(p("inner"))));
    }

    @Test
    void depthAndCellParagraphPaginationCannotBeSilentlyReduced() throws IOException {
        rejectBoth(table(table(table(p("too deep")))));
        for (String property : List.of("<w:keepNext/>", "<w:pageBreakBefore/>", "<w:pStyle w:val=\"Heading1\"/>",
                "<w:sectPr/>")) {
            rejectBoth(table("<w:p><w:pPr>" + property + "</w:pPr><w:r><w:t>content</w:t></w:r></w:p>" + p("after")));
        }
        rejectBoth(table("<w:p od:id=\"same\"/>" + "<w:p od:id=\"same\"/>"));
        for (String attribute : List.of("id=\"hidden\"", "boundary=\"true\"", "kind=\"paragraph\"", "cellTail=\"true\"")) {
            rejectBoth("<w:tbl><w:tr><w:tc><w:tcPr><w:vMerge w:val=\"restart\"/></w:tcPr>" + p("root")
                    + "</w:tc></w:tr><w:tr><w:tc><w:tcPr><w:vMerge/></w:tcPr><w:p od:" + attribute + "/></w:tc></w:tr></w:tbl>");
        }
    }

    @Test
    void fullTreeBudgetsAreCumulativeForImportAndExport() throws IOException {
        var outer = single("outer", TableCell.builder().blocks(List.of(single("a", new TableCell("😀", CELL)),
                single("b", new TableCell("B", CELL)))).build());
        var source = document(List.of(outer));
        var bytes = write(new DocxAdapter(), source);
        for (var limits : List.of(DocxLimits.DEFAULT.toBuilder().maxBlocks(2).build(),
                DocxLimits.DEFAULT.toBuilder().maxTableSlots(2).build(),
                DocxLimits.DEFAULT.toBuilder().maxSourceCodePoints(1).build())) {
            var adapter = new DocxAdapter(limits);
            var output = new ByteArrayOutputStream();
            assertThrows(IOException.class, () -> adapter.write(source, output));
            assertEquals(0, output.size());
            assertThrows(IOException.class, () -> read(adapter, bytes, DocxAdapter.Policy.STRICT));
        }
        var bounded = new DocxAdapter(DocxLimits.DEFAULT.toBuilder().maxBlocks(3).maxTableSlots(3).maxSourceCodePoints(2).build());
        assertEquals(source.blocks(), read(bounded, write(bounded, source), DocxAdapter.Policy.STRICT).document().sections().getFirst().blocks());
        var one = new DocxAdapter(DocxLimits.DEFAULT.toBuilder().maxSourceCodePoints(1).build());
        var cached = packageXml("<w:p><w:fldSimple w:instr=\"PAGE\" od:width=\"10\"><w:r><w:t>123456789</w:t></w:r></w:fldSimple></w:p>");
        var field = (Paragraph) read(one, cached, DocxAdapter.Policy.STRICT).document().sections().getFirst().blocks().getFirst();
        assertEquals("\uFFFC", field.text());
    }

    @Test
    void nestedWidthsSplitModesAutoColumnsAndRepeatedParentHeadersFailBeforeOutput() throws IOException {
        var wide = new Table("wide", List.of(new ColumnWidth.Fixed(PageSettings.A4.contentWidth())),
                List.of(new TableRow(List.of(new TableCell("wide", CELL)))), 0, TableFormat.DEFAULT);
        failWrite(single("outer-wide", TableCell.builder().blocks(List.of(wide)).build()));
        var inner = single("inner", new TableCell("inner", CELL));
        var block = TableCell.builder().blocks(List.of(inner)).build();
        failWrite(new Table("split", List.of(new ColumnWidth.Fraction(1)), List.of(new TableRow(List.of(block))), 0,
                TableFormat.DEFAULT.toBuilder().allowRowSplit(true).build()));
        failWrite(new Table("auto", List.of(new ColumnWidth.Auto(10, 200)), List.of(new TableRow(List.of(block))), 0, TableFormat.DEFAULT));
        failWrite(new Table("header", List.of(new ColumnWidth.Fraction(1)),
                List.of(new TableRow(List.of(block)), new TableRow(List.of(new TableCell("body", CELL)))), 1, TableFormat.DEFAULT));
        var ordinaryHeader = new Table("ordinary-header", List.of(new ColumnWidth.Fraction(1)),
                List.of(new TableRow(List.of(new TableCell("header", CELL))), new TableRow(List.of(block))), 1, TableFormat.DEFAULT);
        assertEquals(List.of(ordinaryHeader), read(new DocxAdapter(), write(new DocxAdapter(), document(List.of(ordinaryHeader))),
                DocxAdapter.Policy.STRICT).document().sections().getFirst().blocks());
    }

    private static void failWrite(Table table) {
        var output = new ByteArrayOutputStream();
        assertThrows(IOException.class, () -> new DocxAdapter().write(document(List.of(table)), output));
        assertEquals(0, output.size());
    }

    private static void rejectBoth(String body) throws IOException {
        byte[] bytes = packageXml(body);
        for (var policy : DocxAdapter.Policy.values()) { assertThrows(IOException.class, () -> read(new DocxAdapter(), bytes, policy)); }
    }

    private static InlineField field(InlineField.Kind kind, String target) { return new InlineField(kind, target, 24, InlineStyle.of(12)); }
    private static Table single(String id, TableCell cell) {
        return new Table(id, List.of(new ColumnWidth.Fraction(1)), List.of(new TableRow(List.of(cell))), 0, TableFormat.DEFAULT);
    }
    private static Document document(List<Block> blocks) { return new Document(DocumentMetadata.EMPTY, PageSettings.A4, blocks); }
    private static byte[] write(DocxAdapter adapter, Document document) throws IOException {
        var output = new ByteArrayOutputStream(); adapter.write(document, output); return output.toByteArray();
    }
    private static DocxAdapter.ReadResult read(DocxAdapter adapter, byte[] bytes, DocxAdapter.Policy policy) throws IOException {
        return adapter.read(new ByteArrayInputStream(bytes), policy);
    }
    private static String p(String text) { return "<w:p><w:r><w:t>" + text + "</w:t></w:r></w:p>"; }
    private static String table(String content) { return "<w:tbl><w:tr><w:tc>" + content + "</w:tc></w:tr></w:tbl>"; }
    private static byte[] packageXml(String body) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml")); zip.write("<Types/>".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(("<w:document xmlns:w=\"" + W + "\" xmlns:od=\"" + OD + "\"><w:body>" + body + "</w:body></w:document>")
                    .getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        return output.toByteArray();
    }
    private static Map<String, byte[]> parts(byte[] bytes) throws IOException {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) { result.put(entry.getName(), zip.readAllBytes()); }
        }
        return result;
    }
    private static int count(String source, String token) { return source.split(java.util.regex.Pattern.quote(token), -1).length - 1; }
}
