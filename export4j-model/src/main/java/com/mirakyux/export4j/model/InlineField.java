// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.Objects;

/** Immutable atomic field occupying one U+FFFC source position and a fixed width in points.
 * Resolved by component orchestration after pagination. Values must fit one reserved line;
 * no shrinking or reflow occurs. Explicit style uses the same font-role and missing-glyph policies as text.
 */
public record InlineField(Kind kind, String targetId, double width, InlineStyle style) implements InlineContent {
    public enum Kind { PAGE, PAGES, REFERENCE_PAGE, REFERENCE_NUMBER, NOTE_MARKER }

    public InlineField {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(style, "style");
        boolean targeted = kind != Kind.PAGE && kind != Kind.PAGES;
        if (!Double.isFinite(width) || width <= 0 || width > Float.MAX_VALUE
                || targeted && targetId.isBlank() || !targeted && !targetId.isEmpty()) {
            throw new IllegalArgumentException("invalid inline field target or reserved width");
        }
    }

    /** Task-confined builder. Dimensions use points unless the type specifies another unit.
     * build() validates the complete value and takes an immutable structural snapshot. */
    public static Builder builder() { return new Builder(); }
    /** Starts an independent builder from this value; referenced resources remain borrowed. */
    public Builder toBuilder() { return new Builder().kind(kind()).targetId(targetId()).width(width()).style(style()); }

    /** Mutable, task-confined construction state; built values do not retain this builder. */
    public static final class Builder {
        private Kind kind = Kind.PAGE;
        private String targetId = "";
        private double width = 0;
        private InlineStyle style = InlineStyle.of(12);

        private Builder() { }
        public Builder kind(Kind value) { kind = java.util.Objects.requireNonNull(value, "kind"); return this; }
        public Builder targetId(String value) { targetId = java.util.Objects.requireNonNull(value, "targetId"); return this; }
        public Builder width(double value) { width = value; return this; }
        public Builder style(InlineStyle value) { style = java.util.Objects.requireNonNull(value, "style"); return this; }

        public InlineField build() { return new InlineField(kind, targetId, width, style); }
    }
}
