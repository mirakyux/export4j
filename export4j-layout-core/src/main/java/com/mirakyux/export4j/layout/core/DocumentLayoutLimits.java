// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

/**
 * Positive structural budgets, additional to text-layer limits. maxParagraphs counts all semantic tree nodes
 * plus distinct component semantic source trees; static component children do not add semantic blocks.
 * Row/column caps apply per table; cells/code points/lines/pages are shared by the document.
 * maxCells counts covered grid slots, including slots owned by a merged cell; source text counts each anchor once.
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
}
