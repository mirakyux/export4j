// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.Objects;

/** Immutable cell text format in points. Text is top-aligned; line height is a fixed baseline step. */
public record CellFormat(double fontSize, double lineHeight, ParagraphAlignment alignment, LineBreakPolicy lineBreakPolicy) {
    public CellFormat {
        Objects.requireNonNull(alignment, "alignment");
        Objects.requireNonNull(lineBreakPolicy, "lineBreakPolicy");
        if (!Double.isFinite(fontSize) || fontSize <= 0 || !Double.isFinite(lineHeight) || lineHeight <= 0) {
            throw new IllegalArgumentException("cell text dimensions must be finite and positive");
        }
    }

    public CellFormat(double fontSize, double lineHeight, ParagraphAlignment alignment) {
        this(fontSize, lineHeight, alignment, LineBreakPolicy.STANDARD);
    }

    public CellFormat withAlignment(ParagraphAlignment value) {
        return new CellFormat(fontSize, lineHeight, value, lineBreakPolicy);
    }

    public CellFormat withLineBreakPolicy(LineBreakPolicy value) {
        return new CellFormat(fontSize, lineHeight, alignment, value);
    }

    public static CellFormat of(double fontSize) {
        return new CellFormat(fontSize, fontSize * 1.5, ParagraphAlignment.LEFT);
    }

    /** Task-confined builder. Dimensions use points unless the type specifies another unit.
     * build() validates the complete value and takes an immutable structural snapshot. */
    public static Builder builder() { return new Builder(); }
    /** Starts an independent builder from this value; referenced resources remain borrowed. */
    public Builder toBuilder() { return new Builder().fontSize(fontSize()).lineHeight(lineHeight()).alignment(alignment()).lineBreakPolicy(lineBreakPolicy()); }

    /** Mutable, task-confined construction state; built values do not retain this builder. */
    public static final class Builder {
        private double fontSize = 12;
        private double lineHeight = 18;
        private ParagraphAlignment alignment = ParagraphAlignment.LEFT;
        private LineBreakPolicy lineBreakPolicy = LineBreakPolicy.STANDARD;

        private Builder() { }
        public Builder fontSize(double value) { fontSize = value; if (!explicitLineHeight) { lineHeight = value * 1.5; } return this; }
        public Builder lineHeight(double value) { lineHeight = value; explicitLineHeight = true; return this; }
        public Builder alignment(ParagraphAlignment value) { alignment = java.util.Objects.requireNonNull(value, "alignment"); return this; }
        public Builder lineBreakPolicy(LineBreakPolicy value) { lineBreakPolicy = java.util.Objects.requireNonNull(value, "lineBreakPolicy"); return this; }
        private boolean explicitLineHeight;

        public CellFormat build() { return new CellFormat(fontSize, lineHeight, alignment, lineBreakPolicy); }
    }
}
