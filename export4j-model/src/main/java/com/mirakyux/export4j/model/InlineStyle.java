// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.Objects;

/** Explicit font role, point size, 24-bit RGB, baseline shift (positive down), and decorations. No synthetic font variants. */
public record InlineStyle(String fontRole, double fontSize, int rgb, double baselineShift, boolean underline, boolean strikeThrough) {
    public InlineStyle {
        Objects.requireNonNull(fontRole, "fontRole");
        if (!Double.isFinite(fontSize) || fontSize <= 0 || rgb < 0 || rgb > 0xffffff || !Double.isFinite(baselineShift)) {
            throw new IllegalArgumentException("invalid inline style");
        }
    }

    public static InlineStyle of(double size) {
        return new InlineStyle("", size, 0, 0, false, false);
    }

    /** Task-confined builder. Dimensions use points unless the type specifies another unit.
     * build() validates the complete value and takes an immutable structural snapshot. */
    public static Builder builder() { return new Builder(); }
    /** Starts an independent builder from this value; referenced resources remain borrowed. */
    public Builder toBuilder() { return new Builder().fontRole(fontRole()).fontSize(fontSize()).rgb(rgb()).baselineShift(baselineShift()).underline(underline()).strikeThrough(strikeThrough()); }

    /** Mutable, task-confined construction state; built values do not retain this builder. */
    public static final class Builder {
        private String fontRole = "";
        private double fontSize = 12;
        private int rgb = 0;
        private double baselineShift = 0;
        private boolean underline = false;
        private boolean strikeThrough = false;

        private Builder() { }
        public Builder fontRole(String value) { fontRole = java.util.Objects.requireNonNull(value, "fontRole"); return this; }
        public Builder fontSize(double value) { fontSize = value; return this; }
        public Builder rgb(int value) { rgb = value; return this; }
        public Builder baselineShift(double value) { baselineShift = value; return this; }
        public Builder underline(boolean value) { underline = value; return this; }
        public Builder strikeThrough(boolean value) { strikeThrough = value; return this; }

        public InlineStyle build() { return new InlineStyle(fontRole, fontSize, rgb, baselineShift, underline, strikeThrough); }
    }
}
