// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

/** Paragraph pagination constraints. Minimum lines apply to fragments when a paragraph actually splits. */
public record PaginationPolicy(boolean keepWithNext, boolean pageBreakBefore,
        int minLinesBeforeBreak, int minLinesAfterBreak) {
    public static final PaginationPolicy DEFAULT = new PaginationPolicy(false, false, 1, 1);
    public static final PaginationPolicy BALANCED = new PaginationPolicy(false, false, 2, 2);

    public PaginationPolicy {
        if (minLinesBeforeBreak < 1 || minLinesAfterBreak < 1) {
            throw new IllegalArgumentException("minimum fragment lines must be positive");
        }
    }

    /** Task-confined builder. Dimensions use points unless the type specifies another unit.
     * build() validates the complete value and takes an immutable structural snapshot. */
    public static Builder builder() { return new Builder(); }
    /** Starts an independent builder from this value; referenced resources remain borrowed. */
    public Builder toBuilder() { return new Builder().keepWithNext(keepWithNext()).pageBreakBefore(pageBreakBefore()).minLinesBeforeBreak(minLinesBeforeBreak()).minLinesAfterBreak(minLinesAfterBreak()); }

    /** Mutable, task-confined construction state; built values do not retain this builder. */
    public static final class Builder {
        private boolean keepWithNext = false;
        private boolean pageBreakBefore = false;
        private int minLinesBeforeBreak = 1;
        private int minLinesAfterBreak = 1;

        private Builder() { }
        public Builder keepWithNext(boolean value) { keepWithNext = value; return this; }
        public Builder pageBreakBefore(boolean value) { pageBreakBefore = value; return this; }
        public Builder minLinesBeforeBreak(int value) { minLinesBeforeBreak = value; return this; }
        public Builder minLinesAfterBreak(int value) { minLinesAfterBreak = value; return this; }

        public PaginationPolicy build() { return new PaginationPolicy(keepWithNext, pageBreakBefore, minLinesBeforeBreak, minLinesAfterBreak); }
    }
}
