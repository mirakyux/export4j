// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.RandomAccess;

/** Immutable physical interpretation of a semantic table. Coordinates use zero-based physical rows
 * and columns; positive spans count covered slots, not local source-list positions. Only real anchors are retained;
 * construction uses O(columns) active state and O(anchors) output, never a row-by-column matrix.
 * Instances are thread-safe. Preparation and queries observe the caller's synchronous execution scope.
 */
public final class TableGrid {
    /** A real source cell and its zero-based physical origin. The cell and its content are immutable. */
    public record Anchor(int rowIndex, int columnIndex, TableCell cell) {
        public Anchor {
            Objects.requireNonNull(cell, "cell");
            if (rowIndex < 0 || columnIndex < 0) { throw new IllegalArgumentException("negative table anchor"); }
        }
    }

    private final int columnCount;
    private final List<List<Anchor>> anchorRows;

    private TableGrid(int columnCount, int rowCount, Map<Integer, List<Anchor>> anchorRows) {
        this.columnCount = columnCount;
        this.anchorRows = new AnchorRows(rowCount, anchorRows);
    }

    /** Sparse immutable view: a long vertical rectangle retains one anchor row, not one entry per covered row. */
    private static final class AnchorRows extends AbstractList<List<Anchor>> implements RandomAccess {
        private final int rowCount;
        private final Map<Integer, List<Anchor>> rows;
        private AnchorRows(int rowCount, Map<Integer, List<Anchor>> rows) {
            this.rowCount = rowCount;
            this.rows = Map.copyOf(rows);
        }
        @Override public int size() { return rowCount; }
        @Override public List<Anchor> get(int index) {
            Objects.checkIndex(index, rowCount);
            return rows.getOrDefault(index, List.of());
        }
    }

    static TableGrid create(int columnCount, List<TableRow> rows, int headerRows) {
        ExecutionContext.checkpoint();
        var active = new Anchor[columnCount];
        Map<Integer, List<Anchor>> result = new HashMap<>();
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            ExecutionContext.checkpoint();
            for (int column = 0; column < columnCount; column++) {
                if ((column & 1023) == 0) { ExecutionContext.checkpoint(); }
                var previous = active[column];
                if (previous != null && previous.cell().rowSpan() <= rowIndex - previous.rowIndex()) {
                    active[column] = null;
                }
            }
            List<Anchor> anchors = new ArrayList<>(rows.get(rowIndex).cells().size());
            int cursor = 0;
            for (var cell : rows.get(rowIndex).cells()) {
                ExecutionContext.checkpoint();
                while (cursor < columnCount && active[cursor] != null) {
                    if ((cursor & 1023) == 0) { ExecutionContext.checkpoint(); }
                    cursor++;
                }
                if (cell.columnSpan() > columnCount - cursor || cell.rowSpan() > rows.size() - rowIndex) {
                    throw new IllegalArgumentException("table cell span exceeds physical grid bounds");
                }
                if (rowIndex < headerRows && cell.rowSpan() > headerRows - rowIndex) {
                    throw new IllegalArgumentException("table cell span crosses header boundary");
                }
                var anchor = new Anchor(rowIndex, cursor, cell);
                int end = cursor + cell.columnSpan();
                for (int column = cursor; column < end; column++) {
                    if ((column & 1023) == 0) { ExecutionContext.checkpoint(); }
                    if (active[column] != null) { throw new IllegalArgumentException("table cell spans overlap"); }
                    active[column] = anchor;
                }
                anchors.add(anchor);
                cursor = end;
            }
            for (int column = 0; column < columnCount; column++) {
                if ((column & 1023) == 0) { ExecutionContext.checkpoint(); }
                if (active[column] == null) { throw new IllegalArgumentException("table grid contains an uncovered slot"); }
            }
            if (!anchors.isEmpty()) { result.put(rowIndex, List.copyOf(anchors)); }
        }
        return new TableGrid(columnCount, rows.size(), result);
    }

    /** Ordered immutable source anchors for every physical row; completely covered rows have an empty list. */
    public List<List<Anchor>> anchorRows() { return anchorRows; }

    /** Returns the unique source owner of a physical slot, including horizontal or vertical coverage.
     * Queries scan earlier anchor rows and take O(source anchors + rows) in the worst case.
     * @throws IndexOutOfBoundsException when either coordinate is outside this grid
     */
    public Anchor ownerAt(int row, int column) {
        checkCoordinates(row, column);
        for (int sourceRow = row; sourceRow >= 0; sourceRow--) {
            ExecutionContext.checkpoint();
            for (var anchor : anchorRows.get(sourceRow)) {
                ExecutionContext.checkpoint();
                if (anchor.columnIndex() > column) { break; }
                if (column - anchor.columnIndex() < anchor.cell().columnSpan()
                        && row - sourceRow < anchor.cell().rowSpan()) {
                    return anchor;
                }
            }
        }
        throw new IllegalStateException("validated table grid lost its owner");
    }

    /** Returns an anchor only when this slot is its exact physical origin; covered slots return empty.
     * Uses binary search within the requested source row.
     * @throws IndexOutOfBoundsException when either coordinate is outside this grid
     */
    public Optional<Anchor> anchorAt(int row, int column) {
        checkCoordinates(row, column);
        var anchors = anchorRows.get(row);
        int low = 0, high = anchors.size() - 1;
        while (low <= high) {
            ExecutionContext.checkpoint();
            int middle = low + (high - low) / 2;
            var candidate = anchors.get(middle);
            if (candidate.columnIndex() < column) { low = middle + 1; }
            else if (candidate.columnIndex() > column) { high = middle - 1; }
            else { return Optional.of(candidate); }
        }
        return Optional.empty();
    }

    private void checkCoordinates(int row, int column) {
        ExecutionContext.checkpoint();
        Objects.checkIndex(row, anchorRows.size());
        Objects.checkIndex(column, columnCount);
    }
}
