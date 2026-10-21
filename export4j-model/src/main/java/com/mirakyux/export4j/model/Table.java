// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.List;
import java.util.Objects;

/**
 * Immutable rectangular semantic table with at least one body row and positive rectangular cell spans.
 * Source cells are real content anchors covering every grid slot exactly once. Ordered Paragraph/Table
 * block cells are atomic and bounded to two table levels, including this root table.
 * The first headerRows rows repeat together on continuation pages. Zero disables headers.
 * Fixed-only tables are left-aligned; fractional columns fill the remaining content width.
 */
public record Table(String id, List<ColumnWidth> columns, List<TableRow> rows,
        int headerRows, TableFormat format) implements Block {
    /** Upper bound for implicit equal-column inference, preventing allocation from a huge cell span.
     * Explicit column lists are not capped here; layout structural budgets still apply.
     */
    public static final int MAX_INFERRED_COLUMNS = 4096;

    /** Task-confined semantic table builder supporting column spans on explicitly supplied cells. */
    public static Builder builder(String id) { return new Builder(id); }

    public static final class Builder {
        private final String id;
        private List<ColumnWidth> columns = List.of();
        private final java.util.ArrayList<TableRow> rows = new java.util.ArrayList<>();
        private int headerRows;
        private TableFormat format = TableFormat.DEFAULT;
        private CellFormat cellFormat = CellFormat.of(12);

        private Builder(String id) {
            this.id = Objects.requireNonNull(id, "id");
            if (id.isBlank()) { throw new IllegalArgumentException("table ID is blank"); }
        }

        public Builder columns(ColumnWidth... values) { columns = List.of(values); return this; }
        public Builder cellFormat(CellFormat value) { cellFormat = Objects.requireNonNull(value, "cellFormat"); return this; }
        public Builder format(TableFormat value) { format = Objects.requireNonNull(value, "format"); return this; }
        public Builder format(java.util.function.Consumer<TableFormat.Builder> configure) {
            var value = format.toBuilder(); configure.accept(value); return format(value.build());
        }
        /** Adds a repeating header before any body row. */
        public Builder header(String... values) {
            if (headerRows != rows.size()) { throw new IllegalStateException("headers must precede body rows"); }
            row(values); headerRows++; return this;
        }
        /** Adds real header cells, including spans, without overloading the text-header varargs. */
        public Builder headerCells(TableCell... cells) {
            if (headerRows != rows.size()) { throw new IllegalStateException("headers must precede body rows"); }
            if (cells.length == 0) { throw new IllegalArgumentException("use coveredHeaderRow for a covered header"); }
            row(cells); headerRows++; return this;
        }
        /** Adds a header with no new anchors; earlier header anchors must cover every physical column. */
        public Builder coveredHeaderRow() {
            if (headerRows != rows.size()) { throw new IllegalStateException("headers must precede body rows"); }
            rows.add(TableRow.covered()); headerRows++; return this;
        }
        public Builder row(String... values) {
            return row(java.util.Arrays.stream(values).map(text -> new TableCell(text, cellFormat)).toArray(TableCell[]::new));
        }
        public Builder row(TableCell... cells) { rows.add(new TableRow(List.of(cells))); return this; }
        /** Adds a body row with no new anchors; earlier body anchors must cover every physical column. */
        public Builder coveredRow() { rows.add(TableRow.covered()); return this; }
        /** With no explicit columns, infers equal fractions from first-row physical coverage.
         * Inference is capped at MAX_INFERRED_COLUMNS; larger tables require explicit columns.
         */
        public Table build() {
            var widths = columns;
            if (widths.isEmpty() && !rows.isEmpty()) {
                int count = rows.getFirst().columnCount();
                if (count > MAX_INFERRED_COLUMNS) {
                    throw new IllegalArgumentException("implicit table column count exceeds inference limit; supply explicit columns");
                }
                widths = java.util.stream.IntStream.range(0, count).<ColumnWidth>mapToObj(ignored -> new ColumnWidth.Fraction(1)).toList();
            }
            return new Table(id, widths, rows, headerRows, format);
        }
    }
    /** Equal-width text table with one repeating header row. Inputs are snapshotted and must be rectangular.
     * Use the canonical constructor for widths, rich cells, padding or row splitting.
     */
    public static Table text(String id, List<String> headers, List<List<String>> rows, CellFormat cellFormat) {
        headers = List.copyOf(headers);
        var content = new java.util.ArrayList<TableRow>();
        content.add(new TableRow(headers.stream().map(value -> new TableCell(value, cellFormat)).toList()));
        for (var row : List.copyOf(rows)) {
            content.add(new TableRow(row.stream().map(value -> new TableCell(value, cellFormat)).toList()));
        }
        List<ColumnWidth> widths = headers.stream().<ColumnWidth>map(ignored -> new ColumnWidth.Fraction(1)).toList();
        return new Table(id, widths, content, 1, TableFormat.DEFAULT);
    }

    public static Table text(String id, List<String> headers, List<List<String>> rows) {
        return text(id, headers, rows, CellFormat.of(12));
    }

    public Table {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(format, "format");
        columns = List.copyOf(columns);
        rows = List.copyOf(rows);
        if (id.isBlank() || columns.isEmpty() || rows.isEmpty() || headerRows < 0 || headerRows >= rows.size()) {
            throw new IllegalArgumentException("table requires an ID, columns, body rows and valid header count");
        }
        TableGrid.create(columns.size(), rows, headerRows);
    }

    /** Resolves immutable physical owners and source anchors. The result is newly prepared per call;
     * consumers doing several queries should retain it for that operation. Uses O(columns) active state
     * plus O(source anchors) results, with synchronous execution checkpoints.
     */
    public TableGrid grid() { return TableGrid.create(columns.size(), rows, headerRows); }

    /** Whether any real source cell owns ordered semantic blocks. Includes descendant block cells,
     * because every descendant table is reached through a direct block cell. */
    public boolean hasBlockCells() {
        for (var row : rows) {
            ExecutionContext.checkpoint();
            for (var cell : row.cells()) { if (cell.hasBlocks()) { return true; } }
        }
        return false;
    }

    /** Whether any source anchor covers more than one physical row. */
    public boolean hasRowSpans() {
        for (var row : rows) {
            ExecutionContext.checkpoint();
            for (var cell : row.cells()) {
                if (cell.rowSpan() > 1) { return true; }
            }
        }
        return false;
    }
}
