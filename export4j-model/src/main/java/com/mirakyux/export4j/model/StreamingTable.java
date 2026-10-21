// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Immutable fixed-column specification for a single-pass table; not a Block and retains no body rows.
 * Widths and padding use points. Columns are left-aligned inside the page content area and may leave
 * unused width. Header rows repeat intact; body rows are supplied separately and never split.
 * The streaming layouter supports plain or pure rich-text cells with explicit font roles and bounded
 * run counts. Hyperlinks, fields, images, block cells and merged cells are not supported.
 * This value is thread-safe; row sources and builders are task-confined.
 */
public record StreamingTable(String id, DocumentMetadata metadata, PageSettings pageSettings,
        List<Double> columnWidths, List<TableRow> headerRows, PageMargins padding) {
    public StreamingTable {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(pageSettings, "pageSettings");
        Objects.requireNonNull(padding, "padding");
        columnWidths = List.copyOf(columnWidths);
        headerRows = List.copyOf(headerRows);
        if (id.isBlank() || columnWidths.isEmpty()) {
            throw new IllegalArgumentException("streaming table requires an ID and fixed columns");
        }
        double width = 0;
        for (double column : columnWidths) {
            if (!Double.isFinite(column) || column <= padding.left() + padding.right()) {
                throw new IllegalArgumentException("streaming column must leave positive text width");
            }
            width += column;
        }
        if (!Double.isFinite(width) || width > pageSettings.contentWidth()) {
            throw new IllegalArgumentException("streaming columns exceed page content width");
        }
        for (var row : headerRows) {
            if (row.cells().stream().anyMatch(TableCell::hasBlocks)) {
                throw new IllegalArgumentException("streaming tables do not support block cells");
            }
            if (row.cells().stream().anyMatch(cell -> cell.rowSpan() != 1)) {
                throw new IllegalArgumentException("streaming tables do not support row spans");
            }
            if (row.cells().stream().anyMatch(cell -> cell.columnSpan() != 1)) {
                throw new IllegalArgumentException("streaming tables do not support column spans");
            }
            if (row.cells().size() != columnWidths.size()) {
                throw new IllegalArgumentException("streaming headers must match column count");
            }
        }
    }

    /** Creates task-confined construction state. No body rows are accepted or retained. */
    public static Builder builder(String id) { return new Builder(id); }
    public Builder toBuilder() {
        return new Builder(id).metadata(metadata).pageSettings(pageSettings).columnWidths(columnWidths)
                .headerRows(headerRows).padding(padding);
    }

    public static final class Builder {
        private final String id;
        private DocumentMetadata metadata = DocumentMetadata.EMPTY;
        private PageSettings pageSettings = PageSettings.A4;
        private List<Double> widths = List.of();
        private final List<TableRow> headers = new ArrayList<>();
        private PageMargins padding = PageMargins.uniform(3);
        private CellFormat cellFormat = CellFormat.of(12);

        private Builder(String id) { this.id = Objects.requireNonNull(id, "id"); }
        public Builder metadata(DocumentMetadata value) { metadata = Objects.requireNonNull(value, "metadata"); return this; }
        public Builder pageSettings(PageSettings value) { pageSettings = Objects.requireNonNull(value, "pageSettings"); return this; }
        public Builder columns(double... values) { widths = Arrays.stream(values).boxed().toList(); return this; }
        public Builder columnWidths(List<Double> values) { widths = List.copyOf(values); return this; }
        /** Replaces all repeating headers, taking an immediate snapshot. */
        public Builder headerRows(List<TableRow> values) { headers.clear(); headers.addAll(List.copyOf(values)); return this; }
        public Builder header(TableRow value) { headers.add(Objects.requireNonNull(value, "header")); return this; }
        public Builder header(String... values) {
            return header(new TableRow(Arrays.stream(values).map(text -> new TableCell(text, cellFormat)).toList()));
        }
        /** Applies only to subsequently added convenience text headers; source cells keep their formats. */
        public Builder cellFormat(CellFormat value) { cellFormat = Objects.requireNonNull(value, "cellFormat"); return this; }
        public Builder padding(PageMargins value) { padding = Objects.requireNonNull(value, "padding"); return this; }
        public Builder padding(double points) { return padding(PageMargins.uniform(points)); }
        public StreamingTable build() { return new StreamingTable(id, metadata, pageSettings, widths, headers, padding); }
    }
}
