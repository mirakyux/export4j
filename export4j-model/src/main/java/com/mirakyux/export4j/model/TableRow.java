// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.List;

/** Immutable sequence of real content anchors. A cell can cover consecutive columns and rows;
 * cells() indexes anchors, not physical grid columns. Full coverage is checked by its table.
 * An empty row is valid only when earlier anchors cover the complete row in that table.
 * Default pagination keeps the row whole; explicit table splitting may continue complete text lines.
 */
public record TableRow(List<TableCell> cells) {
    public TableRow {
        cells = List.copyOf(cells);
        count(cells);
    }

    /** Creates a row with no new content anchors, requiring complete coverage from earlier table rows. */
    public static TableRow covered() { return new TableRow(List.of()); }

    /** Sum of this row's anchor column spans, with overflow checked before addition.
     * This is local coverage and excludes earlier row-spanning owners; use Table.grid() for physical coordinates.
     */
    public int columnCount() { return count(cells); }

    /** Returns an anchor using only this row's local, consecutive column coverage.
     * Earlier row-spanning owners are excluded; this method cannot locate physical columns of a two-dimensional table.
     * Use Table.grid().ownerAt(row, column) for that purpose.
     * @throws IndexOutOfBoundsException for a negative column or one outside the local anchor coverage
     */
    public TableCell cellAtColumn(int column) {
        if (column < 0) { throw new IndexOutOfBoundsException("negative table column"); }
        int end = 0;
        for (var cell : cells) {
            end += cell.columnSpan();
            if (column < end) { return cell; }
        }
        throw new IndexOutOfBoundsException("table column outside row");
    }

    private static int count(List<TableCell> cells) {
        int count = 0;
        for (var cell : cells) {
            if (cell.columnSpan() > Integer.MAX_VALUE - count) {
                throw new IllegalArgumentException("table row column coverage overflows integer range");
            }
            count += cell.columnSpan();
        }
        return count;
    }
}
