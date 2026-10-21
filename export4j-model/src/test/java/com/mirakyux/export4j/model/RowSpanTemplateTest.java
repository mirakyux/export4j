// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RowSpanTemplateTest {
    @Test void staticValueBlocksBindSpanningSourcesOnceAndPreserveCoveredRowsAndFieldOffsets() {
        var merged = TableCell.builder().columnSpan(2).rowSpan(2).content(List.of(
                new InlineText("${value}", InlineStyle.of(12), "https://example.com/page"),
                new InlineField(InlineField.Kind.REFERENCE_PAGE, "${target}", 20, InlineStyle.of(12)))).build();
        var source = Table.builder("source").row(merged).coveredRow().build();
        var template = DocumentTemplate.builder().block(source).build();
        var bound = (Table) template.render(Map.of("value", "A😀", "target", "destination")).blocks().getFirst();
        var actual = bound.grid().ownerAt(1, 1).cell();
        assertEquals("A😀\ufffc", actual.text());
        assertEquals(2, actual.rowSpan());
        assertEquals(2, actual.columnSpan());
        assertEquals(3, actual.fields().getFirst().sourceIndex());
        assertEquals("destination", actual.fields().getFirst().field().targetId());
        assertEquals("https://example.com/page", actual.inlines().getFirst().hyperlink());
        assertTrue(bound.rows().getLast().cells().isEmpty());
        assertSame(actual, bound.rows().getFirst().cells().getFirst());
        assertEquals("${value}\ufffc", merged.text());
    }

    @Test void dynamicRowsBindStaticVerticalHeadersAndKeepEachBodyRowIndependent() {
        var headers = List.of(new TableRow(List.of(TableCell.builder().text("${caption}").columnSpan(2).rowSpan(2).build())),
                TableRow.covered());
        var prototype = new TableRow(List.of(TableCell.builder().text("${item.name}").columnSpan(2).build()));
        var rows = DocumentTemplate.Rows.builder().id("rows").path("items").variable("item")
                .columns(List.of(new ColumnWidth.Fixed(50), new ColumnWidth.Fixed(50)))
                .headers(headers).row(prototype).build();
        var bound = (Table) DocumentTemplate.builder().node(rows).build()
                .render(Map.of("caption", "Title", "items", List.of(Map.of("name", "A"), Map.of("name", "B"))))
                .blocks().getFirst();
        assertEquals(2, bound.headerRows());
        assertEquals(4, bound.rows().size());
        assertEquals("Title", bound.grid().ownerAt(1, 1).cell().text());
        assertEquals(2, bound.grid().ownerAt(1, 1).cell().rowSpan());
        assertEquals(List.of("A", "B"), bound.rows().subList(2, 4).stream()
                .map(row -> row.cells().getFirst().text()).toList());
        assertTrue(bound.rows().subList(2, 4).stream().allMatch(row -> row.cells().getFirst().rowSpan() == 1));
    }

    @Test void dynamicBodyPrototypesRejectVerticalSpansAndHeadersCannotExtendIntoGeneratedData() {
        var columns = List.<ColumnWidth>of(new ColumnWidth.Fixed(50));
        var vertical = new TableRow(List.of(TableCell.builder().text("${item}").rowSpan(2).build()));
        var plain = new TableRow(List.of(TableCell.builder().text("${item}").build()));
        assertThrows(IllegalArgumentException.class, () -> new DocumentTemplate.Rows("rows", "items", "item",
                columns, List.of(), vertical, TableFormat.DEFAULT));
        assertThrows(IllegalArgumentException.class, () -> new DocumentTemplate.Rows("rows", "items", "item",
                columns, List.of(vertical), plain, TableFormat.DEFAULT));
        assertThrows(IllegalArgumentException.class, () -> new DocumentTemplate.Rows("rows", "items", "item",
                columns, List.of(), TableRow.covered(), TableFormat.DEFAULT));
    }
}
