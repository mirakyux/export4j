// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import java.util.Objects;

/** Positive streaming budgets. Document table rows include source headers and body rows once;
 * output lines and diagnostics include every repeated header presentation. Per-cell and per-row
 * code point and inline-run caps bound a single source row before shaping. Empty runs count, and a
 * plain cell without explicit runs counts as one. The specification is one semantic source,
 * so the positive maxParagraphs budget always admits it; there are no separate paragraph blocks.
 * Text-layer per-cell limits still apply. No source text is included in failures.
 */
public record StreamingTableLimits(DocumentLayoutLimits document, int maxCellCodePoints,
        int maxRowCodePoints, long maxDiagnostics, int maxCellInlines, int maxRowInlines) {
    private static final int DEFAULT_MAX_CELL_INLINES = 4_096;
    private static final int DEFAULT_MAX_ROW_INLINES = 16_384;
    public static final StreamingTableLimits DEFAULT = new StreamingTableLimits(DocumentLayoutLimits.DEFAULT,
            100_000, 1_000_000, 10_000, DEFAULT_MAX_CELL_INLINES, DEFAULT_MAX_ROW_INLINES);

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
                || maxCellInlines <= 0 || maxRowInlines <= 0) {
            throw new IllegalArgumentException("streaming budgets must be positive");
        }
    }
    public static Builder builder() { return new Builder(); }
    public Builder toBuilder() {
        return new Builder().document(document).maxCellCodePoints(maxCellCodePoints)
                .maxRowCodePoints(maxRowCodePoints).maxDiagnostics(maxDiagnostics)
                .maxCellInlines(maxCellInlines).maxRowInlines(maxRowInlines);
    }
    /** Task-confined builder; build() takes an immutable snapshot. */
    public static final class Builder {
        private DocumentLayoutLimits document = DEFAULT.document;
        private int cell = DEFAULT.maxCellCodePoints;
        private int row = DEFAULT.maxRowCodePoints;
        private long diagnostics = DEFAULT.maxDiagnostics;
        private int cellInlines = DEFAULT.maxCellInlines;
        private int rowInlines = DEFAULT.maxRowInlines;
        private Builder() { }
        public Builder document(DocumentLayoutLimits value) { document = Objects.requireNonNull(value, "document"); return this; }
        public Builder maxCellCodePoints(int value) { cell = value; return this; }
        public Builder maxRowCodePoints(int value) { row = value; return this; }
        public Builder maxDiagnostics(long value) { diagnostics = value; return this; }
        /** Positive run count, including empty runs; ordinary text cells count as one run. */
        public Builder maxCellInlines(int value) { cellInlines = value; return this; }
        /** Positive total run count for one source row, including headers and empty runs. */
        public Builder maxRowInlines(int value) { rowInlines = value; return this; }
        public StreamingTableLimits build() {
            return new StreamingTableLimits(document, cell, row, diagnostics, cellInlines, rowInlines);
        }
    }
}
