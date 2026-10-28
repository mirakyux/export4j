// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import java.util.Objects;

/** Positive streaming budgets. Document table rows include source headers and body rows once;
 * output lines and diagnostics include every repeated header presentation. Per-cell and per-row
 * code point and inline-run caps bound a single source row before shaping. Empty runs count, and a
 * plain cell without explicit runs counts as one. maxCells counts covered fixed-column slots;
 * summary sourceCells counts real anchors once. The specification is one semantic source, so the
 * positive maxParagraphs budget always admits its legacy inline path. The semantic streaming
 * path instead counts all original Paragraph/Table descendants against that cumulative budget.
 * maxFields and maxGeneratedCodePoints count actual field presentations and generated values,
 * including repeated headers, separately from source counts. Text-layer per-cell limits still apply.
 * maxBufferedRows and maxBufferedCodePoints bound the optional connected vertical-span group;
 * ordinary non-grouped streams still retain at most one pending row. No source text is included in failures.
 */
public record StreamingTableLimits(DocumentLayoutLimits document, int maxCellCodePoints,
        int maxRowCodePoints, long maxDiagnostics, int maxCellInlines, int maxRowInlines, long maxLinks,
        long maxFields, long maxGeneratedCodePoints, int maxBufferedRows, long maxBufferedCodePoints) {
    /** Compatibility constructor with bounded group defaults of 64 rows and 1,000,000 code points. */
    public StreamingTableLimits(DocumentLayoutLimits document, int maxCellCodePoints,
            int maxRowCodePoints, long maxDiagnostics, int maxCellInlines, int maxRowInlines, long maxLinks,
            long maxFields, long maxGeneratedCodePoints) {
        this(document, maxCellCodePoints, maxRowCodePoints, maxDiagnostics, maxCellInlines, maxRowInlines,
                maxLinks, maxFields, maxGeneratedCodePoints, 64, 1_000_000);
    }
    /** Compatibility constructor with default field presentation/value budgets. */
    public StreamingTableLimits(DocumentLayoutLimits document, int maxCellCodePoints,
            int maxRowCodePoints, long maxDiagnostics, int maxCellInlines, int maxRowInlines, long maxLinks) {
        this(document, maxCellCodePoints, maxRowCodePoints, maxDiagnostics, maxCellInlines, maxRowInlines, maxLinks, 100_000, 1_000_000);
    }
    private static final int DEFAULT_MAX_CELL_INLINES = 4_096;
    private static final int DEFAULT_MAX_ROW_INLINES = 16_384;
    public static final StreamingTableLimits DEFAULT = new StreamingTableLimits(DocumentLayoutLimits.DEFAULT,
            100_000, 1_000_000, 10_000, DEFAULT_MAX_CELL_INLINES, DEFAULT_MAX_ROW_INLINES, 10_000);

    /** Compatibility constructor with the default cumulative 10,000 output-link budget. */
    public StreamingTableLimits(DocumentLayoutLimits document, int maxCellCodePoints,
            int maxRowCodePoints, long maxDiagnostics, int maxCellInlines, int maxRowInlines) {
        this(document, maxCellCodePoints, maxRowCodePoints, maxDiagnostics, maxCellInlines, maxRowInlines, 10_000);
    }

    /** Preserves the plain-table construction API, with default inline-run caps of 4,096 per cell
     * and 16,384 per source row, including headers. */
    public StreamingTableLimits(DocumentLayoutLimits document, int maxCellCodePoints,
            int maxRowCodePoints, long maxDiagnostics) {
        this(document, maxCellCodePoints, maxRowCodePoints, maxDiagnostics,
                DEFAULT_MAX_CELL_INLINES, DEFAULT_MAX_ROW_INLINES);
    }

    public StreamingTableLimits {
        Objects.requireNonNull(document, "document");
        if (maxCellCodePoints <= 0 || maxRowCodePoints <= 0 || maxDiagnostics <= 0
                || maxCellInlines <= 0 || maxRowInlines <= 0 || maxLinks <= 0 || maxFields <= 0 || maxGeneratedCodePoints <= 0
                || maxBufferedRows <= 0 || maxBufferedCodePoints <= 0) {
            throw new IllegalArgumentException("streaming budgets must be positive");
        }
    }
    public static Builder builder() { return new Builder(); }
    public Builder toBuilder() {
        return new Builder().document(document).maxCellCodePoints(maxCellCodePoints)
                .maxRowCodePoints(maxRowCodePoints).maxDiagnostics(maxDiagnostics)
                .maxCellInlines(maxCellInlines).maxRowInlines(maxRowInlines).maxLinks(maxLinks)
                .maxFields(maxFields).maxGeneratedCodePoints(maxGeneratedCodePoints)
                .maxBufferedRows(maxBufferedRows).maxBufferedCodePoints(maxBufferedCodePoints);
    }
    /** Task-confined builder; build() takes an immutable snapshot. */
    public static final class Builder {
        private DocumentLayoutLimits document = DEFAULT.document;
        private int cell = DEFAULT.maxCellCodePoints;
        private int row = DEFAULT.maxRowCodePoints;
        private long diagnostics = DEFAULT.maxDiagnostics;
        private int cellInlines = DEFAULT.maxCellInlines;
        private int rowInlines = DEFAULT.maxRowInlines;
        private long links = DEFAULT.maxLinks;
        private long fields = DEFAULT.maxFields;
        private long generated = DEFAULT.maxGeneratedCodePoints;
        private int bufferedRows = DEFAULT.maxBufferedRows;
        private long bufferedCodePoints = DEFAULT.maxBufferedCodePoints;
        private Builder() { }
        public Builder document(DocumentLayoutLimits value) { document = Objects.requireNonNull(value, "document"); return this; }
        public Builder maxCellCodePoints(int value) { cell = value; return this; }
        public Builder maxRowCodePoints(int value) { row = value; return this; }
        public Builder maxDiagnostics(long value) { diagnostics = value; return this; }
        /** Positive run count, including empty runs; ordinary text cells count as one run. */
        public Builder maxCellInlines(int value) { cellInlines = value; return this; }
        /** Positive total run count for one source row, including headers and empty runs. */
        public Builder maxRowInlines(int value) { rowInlines = value; return this; }
        /** Positive cumulative count of output link rectangles, including repeated headers. */
        public Builder maxLinks(long value) { links = value; return this; }
        /** Positive cumulative field presentation count, including every repeated header. */
        public Builder maxFields(long value) { fields = value; return this; }
        /** Positive cumulative generated-value code points, separate from original source text. */
        public Builder maxGeneratedCodePoints(long value) { generated = value; return this; }
        /** Maximum physical rows in one buffered connected body group, checked before reading further rows. */
        public Builder maxBufferedRows(int value) { bufferedRows = value; return this; }
        /** Maximum source code points retained for one optional atomic body group. */
        public Builder maxBufferedCodePoints(long value) { bufferedCodePoints = value; return this; }
        public StreamingTableLimits build() {
            return new StreamingTableLimits(document, cell, row, diagnostics, cellInlines, rowInlines, links,
                    fields, generated, bufferedRows, bufferedCodePoints);
        }
    }
}
