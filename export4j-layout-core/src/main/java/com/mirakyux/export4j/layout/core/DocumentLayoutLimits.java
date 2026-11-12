// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

/**
 * Positive structural budgets, additional to text-layer limits. maxParagraphs counts all semantic tree nodes
 * plus distinct component semantic source trees; static component children do not add semantic blocks.
 * Row/column caps apply per table; source header rows count toward maxTableRows.
 * Cells/code points/lines/pages are shared by the document.
 * maxCells counts table grid slots, including slots owned by a merged cell, and tracks in occupied
 * GridLayout rows (skipped row numbers add no slots); source text counts each anchor once.
 * maxLines includes repeated header output; source code points are counted once.
 * Components add emitted text code points and reported lines after measurement; custom SPI execution is trusted.
 */
public record DocumentLayoutLimits(int maxParagraphs, long maxCodePoints, long maxLines, int maxPages,
        int maxTableRows, int maxTableColumns, long maxCells) {
    public static final DocumentLayoutLimits DEFAULT = new DocumentLayoutLimits(10_000, 1_000_000, 100_000, 1_000);

    /** Legacy four-argument configuration; maxParagraphs limits all semantic block sources. */
    public DocumentLayoutLimits(int maxParagraphs, long maxCodePoints, long maxLines, int maxPages) {
        this(maxParagraphs, maxCodePoints, maxLines, maxPages, 10_000, 256, 100_000);
    }

    public DocumentLayoutLimits {
        if (maxParagraphs <= 0 || maxCodePoints <= 0 || maxLines <= 0 || maxPages <= 0
                || maxTableRows <= 0 || maxTableColumns <= 0 || maxCells <= 0) {
            throw new IllegalArgumentException("document layout budgets must be positive");
        }
    }

    /** Returns a mutable builder initialized with {@link #DEFAULT}. */
    public static Builder builder() {
        return new Builder(DEFAULT);
    }

    /** Returns an independent mutable builder initialized with these limits. */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /** Named configuration of positive budgets; {@link #build()} creates an immutable snapshot. */
    public static final class Builder {
        private int maxParagraphs;
        private long maxCodePoints;
        private long maxLines;
        private int maxPages;
        private int maxTableRows;
        private int maxTableColumns;
        private long maxCells;

        private Builder(DocumentLayoutLimits limits) {
            maxParagraphs = limits.maxParagraphs();
            maxCodePoints = limits.maxCodePoints();
            maxLines = limits.maxLines();
            maxPages = limits.maxPages();
            maxTableRows = limits.maxTableRows();
            maxTableColumns = limits.maxTableColumns();
            maxCells = limits.maxCells();
        }

        public Builder maxParagraphs(int value) {
            maxParagraphs = value;
            return this;
        }

        public Builder maxCodePoints(long value) {
            maxCodePoints = value;
            return this;
        }

        public Builder maxLines(long value) {
            maxLines = value;
            return this;
        }

        public Builder maxPages(int value) {
            maxPages = value;
            return this;
        }

        /** Sets the per-table source row cap, including source header rows. */
        public Builder maxTableRows(int value) {
            maxTableRows = value;
            return this;
        }

        public Builder maxTableColumns(int value) {
            maxTableColumns = value;
            return this;
        }

        public Builder maxCells(long value) {
            maxCells = value;
            return this;
        }

        /** @throws IllegalArgumentException if any budget is not positive */
        public DocumentLayoutLimits build() {
            return new DocumentLayoutLimits(maxParagraphs, maxCodePoints, maxLines, maxPages,
                    maxTableRows, maxTableColumns, maxCells);
        }
    }
}
