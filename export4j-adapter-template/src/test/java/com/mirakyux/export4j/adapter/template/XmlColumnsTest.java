// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.ColumnFlow;
import com.mirakyux.export4j.model.DocumentTemplate;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.Table;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class XmlColumnsTest {
    @Test void compilesStaticGeometryAndExpandsDynamicParagraphsAndTableRowsInOrder() {
        var template = XmlTemplate.builder().build().compile("""
                <document version='1'>
                  <p id='title'>Full width</p>
                  <columns id='article' count='3' gap='14.5' balanced='false' weights='1 2 1'>
                    <if test='show'><then><p id='intro'>${title}</p></then></if>
                    <each items='names' as='name'><p id='item'>${name}</p></each>
                    <rows id='data' items='names' as='name'><columns><column weight='1'/></columns>
                      <header><cell>Names</cell></header><row><cell>${name}</cell></row>
                    </rows>
                  </columns>
                  <p id='tail'>Full width again</p>
                </document>
                """);
        var result = template.render(Map.of("show", true, "title", "Intro", "names", List.of("A", "B")));
        assertEquals(List.of("title", "article", "tail"), result.blocks().stream().map(Block::id).toList());
        var flow = (ColumnFlow) result.blocks().get(1);
        assertEquals(3, flow.columns()); assertEquals(14.5, flow.gap()); assertEquals(false, flow.balanced());
        assertEquals(List.of(1.0, 2.0, 1.0), flow.columnWeights());
        assertEquals(List.of("intro", "item@0", "item@1", "data"), flow.blocks().stream().map(Block::id).toList());
        assertEquals("Intro", ((Paragraph) flow.blocks().getFirst()).text());
        assertEquals(List.of("Names", "A", "B"), ((Table) flow.blocks().getLast()).rows().stream()
                .map(row -> row.cells().getFirst().text()).toList());
        assertEquals(1, ((Table) flow.blocks().getLast()).headerRows());
    }

    @Test void supportsOneToThreeColumnsDefaultsAndEmptyDynamicContent() {
        for (int count = 1; count <= 3; count++) {
            var template = XmlTemplate.builder().build().compile("<document version='1'><columns count='" + count
                    + "' weights='  '><p>Text</p></columns></document>");
            var flow = (ColumnFlow) template.render(Map.of()).blocks().getFirst();
            assertEquals(count, flow.columns()); assertEquals(18, flow.gap()); assertEquals(true, flow.balanced());
            assertEquals(List.of(), flow.columnWeights());
        }
        var template = XmlTemplate.builder().build().compile("""
                <document version='1'><columns><if test='show'><then><p>Conditional</p></then></if></columns>
                <p id='tail'>Tail</p></document>
                """);
        assertEquals(2, ((DocumentTemplate.Columns) template.nodes().getFirst()).count());
        assertEquals(List.of("tail"), template.render(Map.of("show", false)).blocks().stream().map(Block::id).toList());
    }

    @Test void includesInsideAndAroundColumnsKeepQualifiedIdsAndReferenceTargets() {
        var compiler = XmlTemplate.builder().fragment("body", """
                <fragment version='1'><columns id='article'><include ref='paragraphs' prefix='nested'/></columns></fragment>
                """).fragment("paragraphs", """
                <fragment version='1'><p id='target'>Target</p><p id='reference'><page-reference target='target' width='24'/></p></fragment>
                """).build();
        var flow = (ColumnFlow) compiler.compile("<document version='1'><include ref='body' prefix='paper'/></document>")
                .render(Map.of()).blocks().getFirst();
        assertEquals("paper-article", flow.id());
        assertEquals(List.of("paper-nested-target", "paper-nested-reference"), flow.blocks().stream().map(Block::id).toList());
        assertEquals("paper-nested-target", ((Paragraph) flow.blocks().getLast()).fields().getFirst().field().targetId());
    }

    @Test void rejectsInvalidGeometryAndUnsupportedContentEvenInInactiveBranches() {
        for (String attributes : List.of("count='0'", "count='4'", "count='${count}'", "gap='-1'", "gap='NaN'",
                "gap='18pt'", "gap='${gap}'", "balanced='yes'", "balanced='${flag}'", "weights='1'",
                "weights='1 0'", "weights='1 NaN'", "weights='1 Infinity'", "weights='1 -1'", "weights='${weights}'")) {
            assertThrows(TemplateException.class, () -> XmlTemplate.builder().build().compile(
                    "<document version='1'><columns " + attributes + "><p>Text</p></columns></document>"), attributes);
        }
        for (String child : List.of("<heading level='1'>Heading</heading>", "<page-break/>", "<bullet>List</bullet>",
                "<caption kind='Figure'>Caption</caption>", "<columns><p>Nested</p></columns>",
                "<p keep-with-next='true'>Chain</p>", "<p page-break-before='true'>Break</p>")) {
            assertThrows(TemplateException.class, () -> XmlTemplate.builder().build().compile(
                    "<document version='1'><columns><if test='show'><then><p>Valid</p></then><else>" + child
                    + "</else></if></columns></document>"), child);
        }
        assertThrows(TemplateException.class, () -> XmlTemplate.builder().build().compile("<document version='1'><columns/></document>"));
        assertThrows(TemplateException.class, () -> XmlTemplate.builder().build().compile(
                "<document version='1'><header height='50'><columns><p>Text</p></columns></header></document>"));
        assertThrows(TemplateException.class, () -> XmlTemplate.builder().build().compile("""
                <document version='1'><table><columns><column weight='1'/></columns>
                <row><cell><columns><p>Text</p></columns></cell></row></table></document>
                """));
    }
}
