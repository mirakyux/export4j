// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentTemplate;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.SemanticTree;
import com.mirakyux.export4j.model.Table;

class XmlTemplateBlockCellsTest {
    private static final String COLUMNS = "<columns><column weight='1'/></columns>";

    @Test void staticBlocksKeepIndependentParagraphFormatsAndOrderedNestedSources() {
        var compiler = XmlTemplate.builder().cellFormat(CellFormat.builder().fontSize(10).lineHeight(16)
                .alignment(ParagraphAlignment.RIGHT).build()).build();
        var document = compiler.compile(xml(table("outer", "<p id='before'>Before</p>"
                + table("inner", "<p id='nested'>Nested</p>")
                + "<p id='after' size='11' line-height='18' align='CENTER' space-before='2' space-after='3'>After</p>")))
                .render(Map.of());
        assertEquals(List.of("outer", "before", "inner", "nested", "after"), ids(document));
        var outer = assertInstanceOf(Table.class, document.blocks().getFirst());
        var cell = outer.rows().getFirst().cells().getFirst();
        assertTrue(cell.hasBlocks());
        var before = assertInstanceOf(Paragraph.class, cell.blocks().getFirst());
        assertEquals("Before", before.text());
        assertEquals(10, before.format().fontSize());
        assertEquals(16, before.format().lineHeight());
        assertEquals(ParagraphAlignment.RIGHT, before.format().alignment());
        var after = assertInstanceOf(Paragraph.class, cell.blocks().getLast());
        assertEquals(11, after.format().fontSize());
        assertEquals(18, after.format().lineHeight());
        assertEquals(ParagraphAlignment.CENTER, after.format().alignment());
        assertEquals(2, after.format().spaceBefore());
        assertEquals(3, after.format().spaceAfter());
        assertThrows(IllegalStateException.class, cell::text);
    }

    @Test void parentAndChildStaticSpansKeepTheirOwnTrueAnchors() {
        String three = "<columns><column weight='1'/><column weight='1'/><column weight='1'/></columns>";
        String inner = "<table id='inner'>" + three + "<row><cell column-span='3' row-span='2'><p id='nested'>Nested</p></cell></row><row/></table>";
        var outer = assertInstanceOf(Table.class, compiler().compile(xml("<table id='outer'>" + three
                + "<header><cell column-span='3'>Header</cell></header>"
                + "<row><cell column-span='2' row-span='2'><p id='before'>Before</p>" + inner
                + "<p id='after'>After</p></cell><cell>Side</cell></row><row><cell>Tail</cell></row></table>"))
                .render(Map.of()).blocks().getFirst());
        assertEquals(1, outer.headerRows());
        var cell = outer.rows().get(1).cells().getFirst();
        assertSame(cell, outer.grid().ownerAt(2, 1).cell());
        var child = assertInstanceOf(Table.class, cell.blocks().get(1));
        assertSame(child.rows().getFirst().cells().getFirst(), child.grid().ownerAt(1, 2).cell());
        assertEquals(List.of("outer", "before", "inner", "nested", "after"),
                ids(Document.builder().add(outer).build()));
    }

    @Test void mixedParagraphResourcesFieldsAndLeafUtf16SurviveBinding() throws IOException {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
        var image = ImageResource.load(bytes.toByteArray());
        var document = XmlTemplate.builder().image("logo", image).build().compile(xml(table("outer",
                "<p id='before'>${prefix}<link href='https://example.com/text'><span role='bold'>Link</span>"
                + "<image ref='logo' width='10' height='11' alt='Logo ${name}'/></link>"
                + "<page-reference target='after' width='20'/></p>"
                + table("inner", "<p id='nested'><page-number width='21'/></p>")
                + "<p id='after'>After</p>"))).render(Map.of("prefix", "A😀", "name", "Test"));
        var before = assertInstanceOf(Paragraph.class, assertInstanceOf(Table.class, document.blocks().getFirst())
                .rows().getFirst().cells().getFirst().blocks().getFirst());
        assertEquals("A😀Link\ufffc\ufffc", before.text());
        assertEquals(7, before.images().getFirst().sourceIndex());
        assertEquals(8, before.fields().getFirst().sourceIndex());
        assertSame(image, before.images().getFirst().image().resource());
        assertEquals("Logo Test", before.images().getFirst().image().altText());
        assertEquals("https://example.com/text", before.images().getFirst().image().hyperlink());
        assertEquals("after", before.fields().getFirst().field().targetId());
        assertEquals(20, before.fields().getFirst().field().width());
        assertTrue(before.inlines().stream().anyMatch(run -> run.style().fontRole().equals("bold")));
    }

    @Test void legacyInlineWhitespaceAndEmptyCellsRemainDistinctFromRealEmptyBlockParagraphs() {
        var document = compiler().compile(xml("<table>" + COLUMNS
                + "<row><cell>  A<span> B </span>  </cell></row><row><cell/></row>"
                + "<row><cell>\n<p id='empty'/>\n</cell></row></table>")).render(Map.of());
        var rows = assertInstanceOf(Table.class, document.blocks().getFirst()).rows();
        assertEquals("  A B   ", rows.getFirst().cells().getFirst().text());
        assertFalse(rows.get(1).cells().getFirst().hasBlocks());
        assertEquals("", rows.get(1).cells().getFirst().text());
        assertTrue(rows.getLast().cells().getFirst().hasBlocks());
        assertEquals("", assertInstanceOf(Paragraph.class, rows.getLast().cells().getFirst().blocks().getFirst()).text());
    }

    @Test void outerIterationsDeepCopyIdsAndRelocateLocalTargetsButKeepExternalTargets() {
        var document = compiler().compile(xml("<p id='external'>External</p><each items='items' as='item'>"
                + table("outer", "<p id='before'>${item}<page-reference target='nested' width='20'/></p>"
                + table("inner", "<p id='nested'><page-reference target='after' width='20'/></p>")
                + "<p id='after'><page-reference target='external' width='20'/></p>") + "</each>"))
                .render(Map.of("items", List.of("A", "B")));
        assertEquals(List.of("external", "outer@0", "before@0", "inner@0", "nested@0", "after@0",
                "outer@1", "before@1", "inner@1", "nested@1", "after@1"), ids(document));
        for (int index = 0; index < 2; index++) {
            var children = assertInstanceOf(Table.class, document.blocks().get(index + 1)).rows().getFirst().cells().getFirst().blocks();
            assertEquals("nested@" + index, assertInstanceOf(Paragraph.class, children.getFirst()).fields().getFirst().field().targetId());
            var inner = assertInstanceOf(Table.class, children.get(1));
            assertEquals("after@" + index, assertInstanceOf(Paragraph.class, inner.rows().getFirst().cells().getFirst()
                    .blocks().getFirst()).fields().getFirst().field().targetId());
            assertEquals("external", assertInstanceOf(Paragraph.class, children.getLast()).fields().getFirst().field().targetId());
        }
    }

    @Test void dynamicOuterRowsKeepIndependentDescendantNamespacesAndStaticInnerSpans() {
        String source = "<rows id='dynamic' items='items' as='item'>" + COLUMNS
                + "<header><cell>Header</cell></header><row><cell><p id='before'>${item}<page-reference target='after' width='20'/></p>"
                + "<table id='inner'>" + COLUMNS + "<row><cell row-span='2'><p id='nested'>Nested</p></cell></row><row/></table>"
                + "<p id='after'>After</p></cell></row></rows>";
        var document = compiler().compile(xml(source)).render(Map.of("items", List.of("A", "B")));
        var table = assertInstanceOf(Table.class, document.blocks().getFirst());
        assertFalse(table.rows().getFirst().cells().getFirst().hasBlocks());
        for (int index = 0; index < 2; index++) {
            String suffix = "@row:dynamic:" + index;
            var children = table.rows().get(index + 1).cells().getFirst().blocks();
            assertEquals(List.of("before" + suffix, "inner" + suffix, "after" + suffix), children.stream().map(Block::id).toList());
            assertEquals("after" + suffix, assertInstanceOf(Paragraph.class, children.getFirst()).fields().getFirst().field().targetId());
            assertEquals(2, assertInstanceOf(Table.class, children.get(1)).rows().getFirst().cells().getFirst().rowSpan());
        }
        assertEquals(ids(document).size(), ids(document).stream().distinct().count());
    }

    @Test void automaticIdsReserveDeepExplicitIdsBeforeCompilingAndRejectAllBranchCollisions() {
        var document = compiler().compile(xml(table("", "<p id='xml-1'>Before</p>" + table("", "<p>Nested</p>") + "<p>After</p>")))
                .render(Map.of());
        assertEquals(List.of("xml-2", "xml-1", "xml-3", "xml-4", "xml-5"), ids(document));
        reject(TemplateException.Code.INVALID_VALUE, table("outer", "<p id='same'>Before</p>"
                + table("inner", "<p id='same'>Nested</p>")));
    }

    @Test void mixedUnknownDynamicAndUnsupportedCellBlocksFailInEveryBranch() {
        for (String content : List.of("Before<p>Block</p>", "<p>Block</p>After", "<p>Block</p><span>Inline</span>",
                "<p>Block</p><page-number width='20'/>", "<p>Block</p><image ref='logo' width='10' height='10'/>",
                "<p>Block</p><if test='visible'><then><p>Conditional</p></then></if>",
                "<p>Block</p><each items='items' as='item'><p>Dynamic</p></each>",
                "<p>Block</p><rows items='items' as='item'>" + COLUMNS + "<row><cell>Dynamic</cell></row></rows>",
                "<p>Block</p><heading level='1'>Heading</heading>", "<blocks><p>Unknown wrapper</p></blocks>")) {
            reject(content.startsWith("Before") || content.endsWith("After") ? TemplateException.Code.INVALID_STRUCTURE
                    : TemplateException.Code.UNSUPPORTED_ELEMENT, table("outer", content));
        }
        reject(TemplateException.Code.UNSUPPORTED_ATTRIBUTE, table("outer", "<p unknown='x'>Block</p>"));
        reject(TemplateException.Code.INVALID_VALUE, table("outer", "<p><note-marker target='n' width='20'/></p>"));
    }

    @Test void deeperTablesAndRowSplittingCompileWhileUnsupportedHeadersAndDynamicSpansFail() {
        assertEquals(3, ids(XmlTemplate.builder().build().compile(xml(table("outer", table("inner", table("deep", "Deep")))))
                .render(Map.of())).size());
        reject(TemplateException.Code.INVALID_STRUCTURE, "<table>" + COLUMNS
                + "<header><cell><p>Block header</p></cell></header><row><cell>Body</cell></row></table>");
        assertEquals(2, ids(XmlTemplate.builder().build().compile(xml("<table row-split='true'>" + COLUMNS
                + "<row><cell><p>Block</p></cell></row></table>")).render(Map.of())).size());
        assertEquals(2, ids(XmlTemplate.builder().build().compile(xml(table("outer", "<table row-split='true'>" + COLUMNS
                + "<row><cell>Nested</cell></row></table>"))).render(Map.of())).size());
        reject(TemplateException.Code.INVALID_STRUCTURE, table("outer", "<table>" + COLUMNS
                + "<row><cell column-span='2'>Too wide</cell></row></table>"));
        reject(TemplateException.Code.INVALID_VALUE, "<rows items='items' as='item'>" + COLUMNS
                + "<row><cell row-span='2'><p>Dynamic span</p></cell></row></rows>");
    }

    @Test void recursiveSourceAndBindingBudgetsHaveExactBoundaries() {
        String source = xml(table("outer", "<p id='before'>Before</p>" + table("inner", "<p id='nested'>Nested</p>")
                + "<p id='after'>After</p>"));
        assertEquals(5, ids(XmlTemplate.builder().limits(new XmlTemplate.Limits(source.length(), 14, 8, 17)).build()
                .compile(source).render(Map.of())).size());
        for (var limits : List.of(new XmlTemplate.Limits(source.length() - 1, 14, 8, 17),
                new XmlTemplate.Limits(source.length(), 13, 8, 17), new XmlTemplate.Limits(source.length(), 14, 7, 17),
                new XmlTemplate.Limits(source.length(), 14, 8, 16))) {
            assertEquals(TemplateException.Code.BUDGET_EXCEEDED,
                    assertThrows(TemplateException.class, () -> XmlTemplate.builder().limits(limits).build().compile(source)).code());
        }
        assertEquals(5, ids(XmlTemplate.builder().bindingLimits(new DocumentTemplate.Limits(1000, 5, 2, 17, 16)).build()
                .compile(source).render(Map.of())).size());
        for (var limits : List.of(new DocumentTemplate.Limits(1000, 4, 2, 17, 16),
                new DocumentTemplate.Limits(1000, 5, 1, 17, 16), new DocumentTemplate.Limits(1000, 5, 2, 16, 16))) {
            assertEquals(DocumentTemplate.BindingException.Code.BUDGET_EXCEEDED,
                    assertThrows(DocumentTemplate.BindingException.class,
                            () -> XmlTemplate.builder().bindingLimits(limits).build().compile(source).render(Map.of())).code());
        }
    }

    private static XmlTemplate compiler() { return XmlTemplate.builder().build(); }

    private static String table(String id, String content) {
        return "<table" + (id.isEmpty() ? "" : " id='" + id + "'") + ">" + COLUMNS
                + "<row><cell>" + content + "</cell></row></table>";
    }

    private static String xml(String content) { return "<document version='1'>" + content + "</document>"; }

    private static void reject(TemplateException.Code code, String content) {
        for (String source : List.of(xml(content), xml("<if test='visible'><then><p>Safe</p></then><else>"
                + content + "</else></if>"))) {
            assertEquals(code, assertThrows(TemplateException.class, () -> compiler().compile(source)).code());
        }
    }

    private static List<String> ids(Document document) {
        var result = new ArrayList<String>();
        SemanticTree.walk(document.blocks(), visit -> result.add(visit.block().id()));
        return result;
    }
}
