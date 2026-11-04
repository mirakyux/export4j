// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.DocumentTemplate;
import com.mirakyux.export4j.model.GridLayout;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XmlGridTest {
    @Test void staticPlacementsPreserveDynamicItemContentAndTableRows() {
        var template = XmlTemplate.builder().build().compile("""
                <document version='1'><p id='before'>Before</p>
                  <grid id='grid' tracks='6' column-gap='8.5' row-gap='11'>
                    <item id='left' row='1' column-start='1' column-span='4'>
                      <each items='names' as='name'><p id='name'>${name}</p></each>
                    </item>
                    <if test='show'><then><item id='right' row='1' column-start='5' column-span='2'>
                      <rows id='data' items='names' as='name'><columns><column weight='1'/></columns>
                        <row><cell>${name}</cell></row></rows>
                    </item></then></if>
                    <item id='last' row='5' column-start='2' column-span='3'><p>Last</p></item>
                  </grid><p id='after'>After</p></document>
                """);
        var result = template.render(Map.of("names", List.of("A", "B"), "show", true));
        assertEquals(List.of("before", "grid", "after"), result.blocks().stream().map(Block::id).toList());
        var grid = (GridLayout) result.blocks().get(1);
        assertEquals(6, grid.tracks()); assertEquals(8.5, grid.columnGap()); assertEquals(11, grid.rowGap());
        assertEquals(List.of(1, 1, 5), grid.items().stream().map(item -> item.row()).toList());
        assertEquals(List.of("A", "B"), grid.items().getFirst().blocks().stream().map(block -> ((Paragraph) block).text()).toList());
        assertEquals(List.of("A", "B"), ((Table) grid.items().get(1).blocks().getFirst()).rows().stream()
                .map(row -> row.cells().getFirst().text()).toList());
        assertEquals(2, ((GridLayout) template.render(Map.of("names", List.of("A"), "show", false)).blocks().get(1)).items().size());
    }

    @Test void includesQualifyGridItemAndDescendantIdsInLexicalCopies() {
        var compiler = XmlTemplate.builder().fragment("grid", """
                <fragment version='1'><grid id='grid'><include ref='item' prefix='nested'/></grid></fragment>
                """).fragment("item", """
                <fragment version='1'><item id='box' row='1' column-start='1' column-span='12'>
                  <include ref='text' prefix='inner'/></item></fragment>
                """).fragment("text", """
                <fragment version='1'><p id='target'>${value}</p>
                <p id='reference'><page-reference target='target' width='24'/></p></fragment>
                """).build();
        var template = compiler.compile("""
                <document version='1'><each items='values' as='value'><include ref='grid' prefix='paper'/></each></document>
                """);
        var grid = (GridLayout) template.render(Map.of("values", List.of("A", "B"))).blocks().getLast();
        assertEquals("paper-grid@1", grid.id()); assertEquals("paper-nested-box@1", grid.items().getFirst().id());
        var paragraphs = grid.items().getFirst().blocks();
        assertEquals("B", ((Paragraph) paragraphs.getFirst()).text());
        assertEquals("paper-nested-inner-target@1", ((Paragraph) paragraphs.getLast()).fields().getFirst().field().targetId());
    }

    @Test void defaultsEmptyDynamicItemsAndWriterZipRoundTripsKeepRealGridStructure() throws Exception {
        var template = XmlTemplate.builder().build().compile("""
                <document version='1'><grid id='grid'><item id='box' row='3' column-start='2' column-span='8'>
                  <if test='show'><then><p id='text'><link href='https://example.com/grid'>Grid😀</link></p></then></if>
                </item></grid><p id='tail'>Tail</p></document>
                """);
        assertEquals(List.of("tail"), template.render(Map.of("show", false)).blocks().stream().map(Block::id).toList());
        var source = template.render(Map.of("show", true)); var grid = (GridLayout) source.blocks().getFirst();
        assertEquals(12, grid.tracks()); assertEquals(12, grid.columnGap()); assertEquals(12, grid.rowGap());
        for (int i = 0; i < 3; i++) {
            var prepared = XmlTemplateWriter.from(source).prepare();
            assertTrue(prepared.preview().issues().isEmpty());
            assertTrue(prepared.bundle().source().contains("<item id=\"box\" row=\"3\" column-start=\"2\" column-span=\"8\""));
            var bytes = new ByteArrayOutputStream(); prepared.to(bytes);
            source = XmlTemplateBundle.read(bytes.toByteArray()).compile().render(Map.of());
            assertEquals(grid, source.blocks().getFirst());
        }
    }

    @Test void expandedIdsRequireExplicitIdentifierDegradationAndReferencesRelocate() throws Exception {
        var template = XmlTemplate.builder().build().compile("""
                <document version='1'><each items='values' as='value'><grid id='grid'>
                  <item id='box' row='1' column-start='1' column-span='12'><p id='target'>${value}</p>
                  <p id='reference'><page-reference target='target' width='24'/></p></item>
                </grid></each></document>
                """);
        var source = template.render(Map.of("values", List.of("A")));
        assertThrows(IllegalArgumentException.class, () -> XmlTemplateWriter.from(source).prepare());
        var policy = ProcessingPolicy.builder().feature("IDENTIFIER", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE).build();
        var prepared = XmlTemplateWriter.from(source).policy(policy).prepare();
        var grid = (GridLayout) prepared.bundle().compile().render(Map.of()).blocks().getFirst();
        assertEquals(grid.items().getFirst().blocks().getFirst().id(),
                ((Paragraph) grid.items().getFirst().blocks().getLast()).fields().getFirst().field().targetId());
        assertEquals(4, prepared.preview().losses().size());
    }

    @Test void invalidGeometryUnsupportedContentAndUnownedItemsFailAtCompileTime() {
        String valid = "<item row='1' column-start='1' column-span='1'><p>Text</p></item>";
        for (String attrs : List.of("tracks='0'", "tracks='13'", "tracks='${n}'", "column-gap='-1'",
                "column-gap='12pt'", "row-gap='NaN'", "row-gap='${gap}'")) {
            assertThrows(TemplateException.class, () -> XmlTemplate.builder().build().compile(
                    "<document version='1'><grid " + attrs + ">" + valid + "</grid></document>"), attrs);
        }
        for (String attrs : List.of("row='1' column-start='1'", "column-start='1' column-span='1'",
                "row='1' column-span='1'", "row='0' column-start='1' column-span='1'",
                "row='1' column-start='12' column-span='2'", "row='${n}' column-start='1' column-span='1'")) {
            assertThrows(TemplateException.class, () -> XmlTemplate.builder().build().compile(
                    "<document version='1'><grid><item " + attrs + "><p>Text</p></item></grid></document>"), attrs);
        }
        for (String bad : List.of("<grid/>", "<grid><if test='show'><then/></if></grid>",
                "<grid><item row='1' column-start='1' column-span='1'><each items='x' as='y'/></item></grid>",
                "<grid><item row='1' column-start='1' column-span='1'/></grid>", valid,
                "<if test='show'><then/> <else>" + valid + "</else></if>", "<columns><grid>" + valid + "</grid></columns>",
                "<grid><if test='show'><then>" + valid + "</then><else><p>Invalid</p></else></if></grid>",
                "<grid><item row='1' column-start='1' column-span='1'><columns><p>Nested</p></columns></item></grid>",
                "<grid tracks='2'><item row='1' column-start='2' column-span='2'><p>Overflow</p></item></grid>",
                "<header height='50'><grid>" + valid + "</grid></header>",
                "<table><columns><column weight='1'/></columns><row><cell><grid>" + valid + "</grid></cell></row></table>")) {
            assertThrows(TemplateException.class, () -> XmlTemplate.builder().build().compile("<document version='1'>" + bad + "</document>"), bad);
        }
    }
}
