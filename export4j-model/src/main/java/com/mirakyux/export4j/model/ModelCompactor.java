// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Builder-local sharing of bounded, immutable inline values; never traverses custom blocks. */
final class ModelCompactor {
    private static final int MAX_CELLS = 4096;
    private static final int MAX_STYLES = 512;
    private static final int MAX_SOURCE_UNITS = 1024;
    private static final int MAX_RUNS = 16;
    private static final int MAX_ROLE_UNITS = 128;
    private static final int MAX_CELL_UNITS = 4096;
    private static final int MAX_PARAGRAPH_UNITS = 4096;

    private final Values<TableCell> cells = new Values<>(MAX_CELLS);
    private final Values<InlineStyle> styles = new Values<>(MAX_STYLES);

    Table compact(Table table) {
        ArrayList<TableRow> replacementRows = null;
        for (int rowIndex = 0; rowIndex < table.rows().size(); rowIndex++) {
            ExecutionContext.checkpoint();
            var row = table.rows().get(rowIndex);
            ArrayList<TableCell> replacementCells = null;
            for (int cellIndex = 0; cellIndex < row.cells().size(); cellIndex++) {
                ExecutionContext.checkpoint();
                var original = row.cells().get(cellIndex);
                var compacted = compact(original);
                if (compacted != original) {
                    if (replacementCells == null) { replacementCells = new ArrayList<>(row.cells()); }
                    replacementCells.set(cellIndex, compacted);
                }
            }
            if (replacementCells != null) {
                if (replacementRows == null) { replacementRows = new ArrayList<>(table.rows()); }
                replacementRows.set(rowIndex, new TableRow(replacementCells));
            }
        }
        return replacementRows == null ? table
                : new Table(table.id(), table.columns(), replacementRows, table.headerRows(), table.format());
    }

    Paragraph compact(Paragraph paragraph) {
        if (paragraph.text().length() > MAX_PARAGRAPH_UNITS || paragraph.inlines().size() > MAX_RUNS) {
            return paragraph;
        }
        long units = paragraph.text().length();
        for (var run : paragraph.inlines()) {
            if (run.style().fontRole().length() > MAX_ROLE_UNITS) { return paragraph; }
            units += (long) run.text().length() + run.style().fontRole().length() + run.hyperlink().length();
            if (units > MAX_PARAGRAPH_UNITS) { return paragraph; }
        }
        ArrayList<InlineText> replacementRuns = null;
        for (int runIndex = 0; runIndex < paragraph.inlines().size(); runIndex++) {
            var run = paragraph.inlines().get(runIndex);
            var style = styles.share(run.style());
            if (style != run.style()) {
                if (replacementRuns == null) { replacementRuns = new ArrayList<>(paragraph.inlines()); }
                replacementRuns.set(runIndex, new InlineText(run.text(), style, run.hyperlink()));
            }
        }
        return replacementRuns == null ? paragraph
                : new Paragraph(paragraph.id(), paragraph.text(), paragraph.format(), replacementRuns,
                        paragraph.images(), paragraph.fields(), paragraph.vectors());
    }

    void clear() { cells.clear(); styles.clear(); }

    private TableCell compact(TableCell cell) {
        if (!eligible(cell)) { return cell; }
        var shared = cells.get(cell);
        if (shared != null) { return shared; }
        ArrayList<InlineText> replacementRuns = null;
        for (int runIndex = 0; runIndex < cell.inlines().size(); runIndex++) {
            var run = cell.inlines().get(runIndex);
            var style = styles.share(run.style());
            if (style != run.style()) {
                if (replacementRuns == null) { replacementRuns = new ArrayList<>(cell.inlines()); }
                replacementRuns.set(runIndex, new InlineText(run.text(), style, run.hyperlink()));
            }
        }
        var result = replacementRuns == null ? cell
                : new TableCell(cell.text(), cell.format(), replacementRuns, cell.images(), cell.fields(), cell.vectors(),
                        cell.columnSpan(), cell.rowSpan(), cell.blocks());
        cells.put(result, result);
        return result;
    }

    private static boolean eligible(TableCell cell) {
        if (cell.hasBlocks() || !cell.images().isEmpty() || !cell.fields().isEmpty() || !cell.vectors().isEmpty()
                || cell.text().length() > MAX_SOURCE_UNITS || cell.inlines().size() > MAX_RUNS) {
            return false;
        }
        long units = cell.text().length();
        for (var run : cell.inlines()) {
            if (run.style().fontRole().length() > MAX_ROLE_UNITS) { return false; }
            units += (long) run.text().length() + run.style().fontRole().length() + run.hyperlink().length();
            if (units > MAX_CELL_UNITS) { return false; }
        }
        return true;
    }

    private static final class Values<T> extends LinkedHashMap<T, T> {
        private static final long serialVersionUID = 1L;
        private final int maximum;

        private Values(int maximum) {
            super(16, 0.75f, true);
            this.maximum = maximum;
        }

        private T share(T value) {
            var existing = get(value);
            if (existing != null) { return existing; }
            put(value, value);
            return value;
        }

        @Override protected boolean removeEldestEntry(Map.Entry<T, T> eldest) { return size() > maximum; }
    }
}
