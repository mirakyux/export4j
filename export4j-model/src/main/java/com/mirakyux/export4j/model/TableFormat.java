// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.Objects;

/** Cell padding and block spacing in points. Spacing is suppressed at page edges, as for paragraphs.
 * drawBorders controls the cell outlines only; it does not change geometry or header backgrounds. */
public record TableFormat(PageMargins padding, double spaceBefore, double spaceAfter, boolean allowRowSplit,
        boolean drawBorders) {
    public static final TableFormat DEFAULT = new TableFormat(PageMargins.uniform(6), 0, 6);

    public TableFormat {
        Objects.requireNonNull(padding, "padding");
        if (!Double.isFinite(spaceBefore) || spaceBefore < 0 || !Double.isFinite(spaceAfter) || spaceAfter < 0) {
            throw new IllegalArgumentException("table spacing must be finite and nonnegative");
        }
    }

    public TableFormat(PageMargins padding, double spaceBefore, double spaceAfter) {
        this(padding, spaceBefore, spaceAfter, false, true);
    }

    /** Preserves the original bordered table behavior. */
    public TableFormat(PageMargins padding, double spaceBefore, double spaceAfter, boolean allowRowSplit) {
        this(padding, spaceBefore, spaceAfter, allowRowSplit, true);
    }

    /** Task-confined builder. Dimensions use points unless the type specifies another unit.
     * build() validates the complete value and takes an immutable structural snapshot. */
    public static Builder builder() { return new Builder(); }
    /** Starts an independent builder from this value; referenced resources remain borrowed. */
    public Builder toBuilder() { return new Builder().padding(padding()).spaceBefore(spaceBefore()).spaceAfter(spaceAfter()).allowRowSplit(allowRowSplit()).drawBorders(drawBorders()); }

    /** Mutable, task-confined construction state; built values do not retain this builder. */
    public static final class Builder {
        private PageMargins padding = PageMargins.uniform(6);
        private double spaceBefore = 0;
        private double spaceAfter = 6;
        private boolean allowRowSplit = false;
        private boolean drawBorders = true;

        private Builder() { }
        public Builder padding(PageMargins value) { padding = java.util.Objects.requireNonNull(value, "padding"); return this; }
        public Builder spaceBefore(double value) { spaceBefore = value; return this; }
        public Builder spaceAfter(double value) { spaceAfter = value; return this; }
        public Builder allowRowSplit(boolean value) { allowRowSplit = value; return this; }
        /** Enables or disables outlines without changing cell geometry or content. */
        public Builder drawBorders(boolean value) { drawBorders = value; return this; }
        public Builder padding(double points) { return padding(PageMargins.uniform(points)); }
        public TableFormat build() { return new TableFormat(padding, spaceBefore, spaceAfter, allowRowSplit, drawBorders); }
    }
}
