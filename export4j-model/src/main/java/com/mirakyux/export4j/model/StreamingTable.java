// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Immutable fixed-column specification for a single-pass table; not a Block and retains no body rows.
 * Widths and padding use points. Columns are left-aligned inside the page content area and may leave
 * unused width. Header rows repeat intact; body rows are supplied separately and remain atomic unless allowRowSplit is enabled.
 * The streaming layouter supports text and explicit inline PNG/JPEG with font roles and bounded
 * run counts. Horizontal spans cover consecutive fixed columns and deduct padding once.
 * Passive text/image hyperlinks require the link-aware page consumer. PAGE resolves at each actual
 * presentation; PAGES requires a positive expectedPages total, checked before successful completion.
 * Zero means an unknown total. Explicit allowRowSpans enables bounded atomic body-row groups;
 * repeating headers never contain vertical spans, and row splitting cannot be combined with groups.
 * Block cells and future references are not supported.
 * This value is thread-safe; row sources and builders are task-confined.
 */
public record StreamingTable(String id, DocumentMetadata metadata, PageSettings pageSettings,
        List<Double> columnWidths, List<TableRow> headerRows, PageMargins padding, boolean allowRowSplit,
        int expectedPages, boolean allowRowSpans) {
    /** Compatibility constructor preserving the original one-row lookahead and no vertical spans. */
    public StreamingTable(String id, DocumentMetadata metadata, PageSettings pageSettings,
            List<Double> columnWidths, List<TableRow> headerRows, PageMargins padding, boolean allowRowSplit, int expectedPages) {
        this(id, metadata, pageSettings, columnWidths, headerRows, padding, allowRowSplit, expectedPages, false);
    }
    /** Compatibility constructor without a caller-declared total page count. */
    public StreamingTable(String id, DocumentMetadata metadata, PageSettings pageSettings,
            List<Double> columnWidths, List<TableRow> headerRows, PageMargins padding, boolean allowRowSplit) {
        this(id, metadata, pageSettings, columnWidths, headerRows, padding, allowRowSplit, 0);
    }
    /** Compatibility constructor preserving atomic body rows. */
    public StreamingTable(String id, DocumentMetadata metadata, PageSettings pageSettings,
            List<Double> columnWidths, List<TableRow> headerRows, PageMargins padding) {
        this(id, metadata, pageSettings, columnWidths, headerRows, padding, false);
    }
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
        if (expectedPages < 0) { throw new IllegalArgumentException("expected page count must be nonnegative"); }
        if (allowRowSpans && allowRowSplit) {
            throw new IllegalArgumentException("streaming row groups are atomic and cannot enable row splitting");
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
            int covered = 0;
            for (var cell : row.cells()) {
                if (cell.columnSpan() > columnWidths.size() - covered) {
                    throw new IllegalArgumentException("streaming header spans exceed column count");
                }
                covered += cell.columnSpan();
            }
            if (covered != columnWidths.size()) {
                throw new IllegalArgumentException("streaming headers must cover every column");
            }
        }
    }

    /** Creates task-confined construction state. No body rows are accepted or retained. */
    public static Builder builder(String id) { return new Builder(id); }
    public Builder toBuilder() {
        return new Builder(id).metadata(metadata).pageSettings(pageSettings).columnWidths(columnWidths)
                .headerRows(headerRows).padding(padding).allowRowSplit(allowRowSplit).expectedPages(expectedPages)
                .allowRowSpans(allowRowSpans);
    }

    public static final class Builder {
        private final String id;
        private DocumentMetadata metadata = DocumentMetadata.EMPTY;
        private PageSettings pageSettings = PageSettings.A4;
        private List<Double> widths = List.of();
        private final List<TableRow> headers = new ArrayList<>();
        private PageMargins padding = PageMargins.uniform(3);
        private CellFormat cellFormat = CellFormat.of(12);
        private boolean allowRowSplit;
        private int expectedPages;
        private boolean allowRowSpans;

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
        /** Allows complete-line continuation of body rows. Headers always remain atomic. */
        public Builder allowRowSplit(boolean value) { allowRowSplit = value; return this; }
        /** Enables finite connected body-row groups with vertical spans. Groups stay atomic;
         * headers still reject vertical spans. Streaming layout limits cap buffered rows and text.
         */
        public Builder allowRowSpans(boolean value) { allowRowSpans = value; return this; }
        /** Declares an exact total for PAGES fields; zero means unknown and forbids PAGES.
         * The actual total is checked before successful completion, even without PAGES fields.
         */
        public Builder expectedPages(int value) { expectedPages = value; return this; }
        public StreamingTable build() { return new StreamingTable(id, metadata, pageSettings, widths, headers, padding,
                allowRowSplit, expectedPages, allowRowSpans); }
    }
}
