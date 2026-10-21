// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.text;

import java.util.Objects;
import com.mirakyux.export4j.model.LineBreakPolicy;

/** Point-based paragraph style; indents are nonnegative, lineHeight is the baseline step. */
public record ParagraphStyle(double fontSize, double lineHeight, double firstLineIndent,
        double subsequentLineIndent, TextAlignment alignment, LineBreakPolicy lineBreakPolicy) {
    public ParagraphStyle {
        Objects.requireNonNull(alignment, "alignment");
        Objects.requireNonNull(lineBreakPolicy, "lineBreakPolicy");
        if (!Double.isFinite(fontSize) || fontSize <= 0 || !Double.isFinite(lineHeight) || lineHeight <= 0
                || !Double.isFinite(firstLineIndent) || firstLineIndent < 0
                || !Double.isFinite(subsequentLineIndent) || subsequentLineIndent < 0) {
            throw new IllegalArgumentException("paragraph sizes and indents must be finite and valid");
        }
    }

    public ParagraphStyle(double fontSize, double lineHeight, double firstLineIndent,
            double subsequentLineIndent, TextAlignment alignment) {
        this(fontSize, lineHeight, firstLineIndent, subsequentLineIndent, alignment, LineBreakPolicy.STANDARD);
    }

    public ParagraphStyle withAlignment(TextAlignment value) {
        return new ParagraphStyle(fontSize, lineHeight, firstLineIndent, subsequentLineIndent, value, lineBreakPolicy);
    }

    public ParagraphStyle withLineBreakPolicy(LineBreakPolicy value) {
        return new ParagraphStyle(fontSize, lineHeight, firstLineIndent, subsequentLineIndent, alignment, value);
    }

    public static ParagraphStyle of(double fontSize) {
        return new ParagraphStyle(fontSize, fontSize * 1.5, 0, 0, TextAlignment.LEFT);
    }

    /** Task-confined builder. Dimensions use points unless the type specifies another unit.
     * build() validates the complete value and takes an immutable structural snapshot. */
    public static Builder builder() { return new Builder(); }
    /** Starts an independent builder from this value; referenced resources remain borrowed. */
    public Builder toBuilder() { return new Builder().fontSize(fontSize()).lineHeight(lineHeight()).firstLineIndent(firstLineIndent()).subsequentLineIndent(subsequentLineIndent()).alignment(alignment()).lineBreakPolicy(lineBreakPolicy()); }

    /** Mutable, task-confined construction state; built values do not retain this builder. */
    public static final class Builder {
        private double fontSize = 12;
        private double lineHeight = 18;
        private double firstLineIndent = 0;
        private double subsequentLineIndent = 0;
        private TextAlignment alignment = TextAlignment.LEFT;
        private LineBreakPolicy lineBreakPolicy = LineBreakPolicy.STANDARD;

        private Builder() { }
        public Builder fontSize(double value) { fontSize = value; if (!explicitLineHeight) { lineHeight = value * 1.5; } return this; }
        public Builder lineHeight(double value) { lineHeight = value; explicitLineHeight = true; return this; }
        public Builder firstLineIndent(double value) { firstLineIndent = value; return this; }
        public Builder subsequentLineIndent(double value) { subsequentLineIndent = value; return this; }
        public Builder alignment(TextAlignment value) { alignment = java.util.Objects.requireNonNull(value, "alignment"); return this; }
        public Builder lineBreakPolicy(LineBreakPolicy value) { lineBreakPolicy = java.util.Objects.requireNonNull(value, "lineBreakPolicy"); return this; }
        private boolean explicitLineHeight;

        public ParagraphStyle build() { return new ParagraphStyle(fontSize, lineHeight, firstLineIndent, subsequentLineIndent, alignment, lineBreakPolicy); }
    }
}
