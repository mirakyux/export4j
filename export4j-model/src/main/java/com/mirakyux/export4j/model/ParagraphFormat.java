// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.Objects;

/**
 * Immutable semantic paragraph formatting in points, independent of fonts and backends.
 * Line height is a baseline step. Spacing is additive within a page and suppressed at page edges.
 * Keep-together paragraphs must fit a fresh page; no shrinking or clipping is implied.
 */
public record ParagraphFormat(double fontSize, double lineHeight, double firstLineIndent,
        double subsequentLineIndent, ParagraphAlignment alignment, double spaceBefore,
        double spaceAfter, boolean keepTogether, PaginationPolicy pagination, LineBreakPolicy lineBreakPolicy,
        double rotation) {
    public ParagraphFormat {
        Objects.requireNonNull(alignment, "alignment");
        Objects.requireNonNull(pagination, "pagination");
        Objects.requireNonNull(lineBreakPolicy, "lineBreakPolicy");
        if (!Double.isFinite(fontSize) || fontSize <= 0 || !Double.isFinite(lineHeight) || lineHeight <= 0
                || !nonnegative(firstLineIndent) || !nonnegative(subsequentLineIndent)
                || !nonnegative(spaceBefore) || !nonnegative(spaceAfter)
                || !Double.isFinite(rotation) || Math.abs(rotation) > 180) {
            throw new IllegalArgumentException("paragraph dimensions must be finite and valid");
        }
    }

    public ParagraphFormat(double fontSize, double lineHeight, double firstLineIndent,
            double subsequentLineIndent, ParagraphAlignment alignment, double spaceBefore,
            double spaceAfter, boolean keepTogether, PaginationPolicy pagination, LineBreakPolicy lineBreakPolicy) {
        this(fontSize, lineHeight, firstLineIndent, subsequentLineIndent, alignment,
                spaceBefore, spaceAfter, keepTogether, pagination, lineBreakPolicy, 0);
    }

    /** Compatibility constructor preserving existing line boundaries. */
    public ParagraphFormat(double fontSize, double lineHeight, double firstLineIndent,
            double subsequentLineIndent, ParagraphAlignment alignment, double spaceBefore,
            double spaceAfter, boolean keepTogether, PaginationPolicy pagination) {
        this(fontSize, lineHeight, firstLineIndent, subsequentLineIndent, alignment,
                spaceBefore, spaceAfter, keepTogether, pagination, LineBreakPolicy.STANDARD);
    }

    /** Compatibility constructor preserving unrestricted complete-line pagination. */
    public ParagraphFormat(double fontSize, double lineHeight, double firstLineIndent,
            double subsequentLineIndent, ParagraphAlignment alignment, double spaceBefore,
            double spaceAfter, boolean keepTogether) {
        this(fontSize, lineHeight, firstLineIndent, subsequentLineIndent, alignment,
                spaceBefore, spaceAfter, keepTogether, PaginationPolicy.DEFAULT);
    }

    public ParagraphFormat withPagination(PaginationPolicy policy) {
        return new ParagraphFormat(fontSize, lineHeight, firstLineIndent, subsequentLineIndent,
                alignment, spaceBefore, spaceAfter, keepTogether, policy, lineBreakPolicy, rotation);
    }

    public ParagraphFormat withAlignment(ParagraphAlignment value) {
        return new ParagraphFormat(fontSize, lineHeight, firstLineIndent, subsequentLineIndent,
                value, spaceBefore, spaceAfter, keepTogether, pagination, lineBreakPolicy, rotation);
    }

    public ParagraphFormat withLineBreakPolicy(LineBreakPolicy value) {
        return new ParagraphFormat(fontSize, lineHeight, firstLineIndent, subsequentLineIndent,
                alignment, spaceBefore, spaceAfter, keepTogether, pagination, value, rotation);
    }

    public static ParagraphFormat of(double fontSize) {
        return new ParagraphFormat(fontSize, fontSize * 1.5, 0, 0,
                ParagraphAlignment.LEFT, 0, 6, false);
    }

    private static boolean nonnegative(double value) {
        return Double.isFinite(value) && value >= 0;
    }

    /** Task-confined builder. Dimensions use points unless the type specifies another unit.
     * build() validates the complete value and takes an immutable structural snapshot. */
    public static Builder builder() { return new Builder(); }
    /** Starts an independent builder from this value; referenced resources remain borrowed. */
    public Builder toBuilder() { return new Builder().fontSize(fontSize()).lineHeight(lineHeight()).firstLineIndent(firstLineIndent()).subsequentLineIndent(subsequentLineIndent()).alignment(alignment()).spaceBefore(spaceBefore()).spaceAfter(spaceAfter()).keepTogether(keepTogether()).pagination(pagination()).lineBreakPolicy(lineBreakPolicy()).rotation(rotation()); }

    /** Mutable, task-confined construction state; built values do not retain this builder. */
    public static final class Builder {
        private double fontSize = 12;
        private double lineHeight = 18;
        private double firstLineIndent = 0;
        private double subsequentLineIndent = 0;
        private ParagraphAlignment alignment = ParagraphAlignment.LEFT;
        private double spaceBefore = 0;
        private double spaceAfter = 6;
        private boolean keepTogether = false;
        private PaginationPolicy pagination = PaginationPolicy.DEFAULT;
        private LineBreakPolicy lineBreakPolicy = LineBreakPolicy.STANDARD;
        private double rotation;

        private Builder() { }
        public Builder fontSize(double value) { fontSize = value; if (!explicitLineHeight) { lineHeight = value * 1.5; } return this; }
        public Builder lineHeight(double value) { lineHeight = value; explicitLineHeight = true; return this; }
        public Builder firstLineIndent(double value) { firstLineIndent = value; return this; }
        public Builder subsequentLineIndent(double value) { subsequentLineIndent = value; return this; }
        public Builder alignment(ParagraphAlignment value) { alignment = java.util.Objects.requireNonNull(value, "alignment"); return this; }
        public Builder spaceBefore(double value) { spaceBefore = value; return this; }
        public Builder spaceAfter(double value) { spaceAfter = value; return this; }
        public Builder keepTogether(boolean value) { keepTogether = value; return this; }
        public Builder pagination(PaginationPolicy value) { pagination = java.util.Objects.requireNonNull(value, "pagination"); return this; }
        public Builder lineBreakPolicy(LineBreakPolicy value) { lineBreakPolicy = java.util.Objects.requireNonNull(value, "lineBreakPolicy"); return this; }
        /** Clockwise degrees, fitted inside the original layout box. */
        public Builder rotation(double value) { rotation = value; return this; }
        private boolean explicitLineHeight;

        public ParagraphFormat build() { return new ParagraphFormat(fontSize, lineHeight, firstLineIndent, subsequentLineIndent, alignment, spaceBefore, spaceAfter, keepTogether, pagination, lineBreakPolicy, rotation); }
    }
}
