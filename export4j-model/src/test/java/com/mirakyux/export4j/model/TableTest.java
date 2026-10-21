// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TableTest {
    private static final TableCell CELL = new TableCell("", CellFormat.of(10));

    @Test
    void snapshotsNestedCollectionsAndPreservesMixedBlockOrder() {
        List<TableCell> cells = new ArrayList<>(List.of(CELL));
        TableRow row = new TableRow(cells);
        cells.clear();
        List<TableRow> rows = new ArrayList<>(List.of(row));
        List<ColumnWidth> columns = new ArrayList<>(List.of(new ColumnWidth.Fraction(1)));
        Table table = new Table("t", columns, rows, 0, TableFormat.DEFAULT);
        rows.clear();
        columns.clear();
        Paragraph paragraph = new Paragraph("p", "before", ParagraphFormat.of(10));
        Document document = new Document(DocumentMetadata.EMPTY, PageSettings.A4, List.of(paragraph, table));
        assertEquals(List.of(paragraph, table), document.blocks());
        assertEquals(List.of(paragraph), document.paragraphs());
        assertEquals(List.of(CELL), table.rows().getFirst().cells());
        assertThrows(UnsupportedOperationException.class, () -> document.blocks().clear());
        assertThrows(UnsupportedOperationException.class, () -> table.rows().clear());
        assertThrows(UnsupportedOperationException.class, () -> table.columns().clear());
        assertThrows(UnsupportedOperationException.class, () -> row.cells().clear());
        assertThrows(IllegalArgumentException.class, () -> new Document(DocumentMetadata.EMPTY, PageSettings.A4,
                List.of(table, new Paragraph("t", "duplicate", ParagraphFormat.of(10)))));
    }

    @Test
    void rejectsEmptyRaggedAndHeaderOnlyTables() {
        List<ColumnWidth> columns = List.of(new ColumnWidth.Fixed(100));
        List<TableRow> rows = List.of(new TableRow(List.of(CELL)));
        assertEquals(List.of(), TableRow.covered().cells());
        assertThrows(IllegalArgumentException.class, () -> new Table("t", columns,
                List.of(TableRow.covered()), 0, TableFormat.DEFAULT));
        assertThrows(IllegalArgumentException.class, () -> new Table("", columns, rows, 0, TableFormat.DEFAULT));
        assertThrows(IllegalArgumentException.class, () -> new Table("t", List.of(), rows, 0, TableFormat.DEFAULT));
        assertThrows(IllegalArgumentException.class, () -> new Table("t", columns, List.of(), 0, TableFormat.DEFAULT));
        assertThrows(IllegalArgumentException.class, () -> new Table("t", columns, rows, 1, TableFormat.DEFAULT));
        assertThrows(IllegalArgumentException.class, () -> new Table("t", columns, rows, -1, TableFormat.DEFAULT));
        assertThrows(IllegalArgumentException.class, () -> new Table("t", columns,
                List.of(new TableRow(List.of(CELL, CELL))), 0, TableFormat.DEFAULT));
    }

    @Test
    void rejectsNonfiniteWidthsTextSizesPaddingAndSpacing() {
        for (double bad : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new ColumnWidth.Fixed(bad));
            assertThrows(IllegalArgumentException.class, () -> new ColumnWidth.Fraction(bad));
            assertThrows(IllegalArgumentException.class, () -> CellFormat.of(bad));
        }
        assertThrows(IllegalArgumentException.class, () -> new CellFormat(10, 0, ParagraphAlignment.LEFT));
        assertThrows(IllegalArgumentException.class, () -> new TableFormat(PageMargins.uniform(0), -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new TableFormat(PageMargins.uniform(0), 0, Double.NaN));
        assertThrows(NullPointerException.class, () -> new TableCell(null, CellFormat.of(10)));
    }
}
