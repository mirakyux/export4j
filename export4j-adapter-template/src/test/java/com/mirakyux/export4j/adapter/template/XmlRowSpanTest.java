// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.model.Table;

class XmlRowSpanTest {
    private static final String COLUMNS = "<columns><column weight='1'/><column weight='1'/><column weight='1'/></columns>";

    @Test void staticRectanglesBindFieldsLinksAndCoveredHeaderAndBodyRows() {
        var template = XmlTemplate.builder().build().compile("""
                <document version="1"><table id="spans">
                  <columns><column width="60"/><column weight="1"/><column weight="2"/></columns>
                  <header><cell column-span="3" row-span="2">${title}</cell></header><header/>
                  <row><cell column-span="2" row-span="2"><span role="bold">${name}</span><page-number width="20"/></cell><cell>R1</cell></row>
                  <row><cell>R2</cell></row>
                  <row><cell column-span="3" row-span="2"><link href="https://example.com/page">${name}</link></cell></row><row/>
                </table></document>
                """);
        var table = (Table) template.render(Map.of("title", "Title", "name", "A😀")).blocks().getFirst();
        var grid = table.grid();
        assertEquals(2, table.headerRows());
        assertEquals(List.of(1, 0, 2, 1, 1, 0), table.rows().stream().map(row -> row.cells().size()).toList());
        assertEquals("Title", grid.ownerAt(1, 2).cell().text());
        assertEquals("A😀\ufffc", grid.ownerAt(3, 1).cell().text());
        assertEquals(3, grid.ownerAt(3, 1).cell().fields().getFirst().sourceIndex());
        assertEquals("bold", grid.ownerAt(3, 1).cell().inlines().getFirst().style().fontRole());
        assertEquals("R2", grid.ownerAt(3, 2).cell().text());
        assertEquals("https://example.com/page", grid.ownerAt(5, 2).cell().inlines().getFirst().hyperlink());
        assertSame(table.rows().get(2).cells().getFirst(), grid.ownerAt(3, 0).cell());
        assertTrue(grid.anchorAt(3, 0).isEmpty());
    }

    @Test void dynamicRowsAllowStaticSpanningHeaderGroupsButNeverCrossDataIterations() {
        var template = XmlTemplate.builder().build().compile("<document version='1'><rows items='items' as='item'>"
                + COLUMNS + "<header><cell column-span='3' row-span='2'>${title}</cell></header><header/>"
                + "<row><cell column-span='3'>${item}</cell></row></rows></document>");
        var table = (Table) template.render(Map.of("title", "Header", "items", List.of("A", "B"))).blocks().getFirst();
        assertEquals(2, table.headerRows());
        assertEquals("Header", table.grid().ownerAt(1, 2).cell().text());
        assertEquals(List.of("A", "B"), table.rows().subList(2, 4).stream()
                .map(row -> row.cells().getFirst().text()).toList());
        assertCode(TemplateException.Code.INVALID_VALUE, "<rows items='items' as='item'>" + COLUMNS
                + "<row><cell column-span='3' row-span='2'>${item}</cell></row></rows>");
    }

    @Test void rowSpanRequiresFixedPositiveAsciiDecimalAtStaticAndDynamicBoundaries() {
        for (String value : List.of("0", "-1", "+1", "1.0", "1e1", "2147483648", "${span}", "", " 2", "٢")) {
            for (String start : List.of("table", "rows items='items' as='item'")) {
                String end = start.startsWith("rows") ? "rows" : "table";
                assertCode(TemplateException.Code.INVALID_VALUE, "<" + start + ">" + COLUMNS
                        + "<row><cell column-span='3' row-span='" + value + "'>X</cell></row></" + end + ">");
            }
        }
    }

    @Test void emptyRowsRequireActualCoverageAndExpiredOrPartialCoverageFails() {
        for (String rows : List.of("<row/>", "<header/><row><cell column-span='3'>B</cell></row>",
                "<row><cell column-span='3'>A</cell></row><row/>",
                "<row><cell row-span='2'>A</cell><cell column-span='2'>B</cell></row><row/>")) {
            assertCode(TemplateException.Code.INVALID_STRUCTURE, "<table>" + COLUMNS + rows + "</table>");
        }
        var table = (Table) XmlTemplate.builder().build().compile("<document version='1'><table>" + COLUMNS
                + "<row><cell column-span='3' row-span='2'>A</cell></row><row/></table></document>")
                .render(Map.of()).blocks().getFirst();
        assertEquals("A", table.grid().ownerAt(1, 2).cell().text());
    }

    @Test void rejectsOverlapsOutOfBoundsAndHeaderBodyCrossingsAsStructuralFailures() {
        for (String rows : List.of(
                "<row><cell>A</cell><cell row-span='2'>B</cell><cell>C</cell></row><row><cell column-span='2'>Over</cell></row>",
                "<row><cell column-span='3' row-span='2147483647'>Over</cell></row><row/>",
                "<header><cell column-span='3' row-span='2'>H</cell></header><row/>",
                "<row><cell column-span='3' row-span='2'>A</cell></row><row><cell>Extra</cell></row>")) {
            assertCode(TemplateException.Code.INVALID_STRUCTURE, "<table>" + COLUMNS + rows + "</table>");
        }
        assertCode(TemplateException.Code.INVALID_STRUCTURE, "<rows items='items' as='item'>" + COLUMNS
                + "<header><cell column-span='3' row-span='2'>H</cell></header>"
                + "<row><cell column-span='3'>B</cell></row></rows>");
    }

    private static void assertCode(TemplateException.Code expected, String content) {
        var failure = assertThrows(TemplateException.class,
                () -> XmlTemplate.builder().build().compile("<document version='1'>" + content + "</document>"));
        assertEquals(expected, failure.code());
    }
}
