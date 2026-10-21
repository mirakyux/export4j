// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.font;

import java.util.List;
import java.util.Objects;

/** Ordered explicit font fallback group with a missing-glyph policy. Immutable if supplied faces are immutable. */
public record FontGroup(List<FontFace> fonts, MissingGlyphPolicy missingGlyphPolicy, int placeholderCodePoint) {
    public FontGroup {
        fonts = List.copyOf(fonts);
        Objects.requireNonNull(missingGlyphPolicy, "missingGlyphPolicy");
        if (fonts.isEmpty() || !Character.isValidCodePoint(placeholderCodePoint)
                || placeholderCodePoint >= 0xd800 && placeholderCodePoint <= 0xdfff
                || Character.isISOControl(placeholderCodePoint) || Character.isWhitespace(placeholderCodePoint)) {
            throw new IllegalArgumentException("font group and placeholder must be valid");
        }
    }

    public static FontGroup strict(List<FontFace> fonts) {
        return new FontGroup(fonts, MissingGlyphPolicy.ERROR, 0x25a1);
    }

    /** Placeholder must itself be supported by at least one group font when substitution is needed. */
    public static FontGroup placeholder(List<FontFace> fonts, int codePoint) {
        return new FontGroup(fonts, MissingGlyphPolicy.PLACEHOLDER_WITH_WARNING, codePoint);
    }

    /** Task-confined builder. Dimensions use points unless the type specifies another unit.
     * build() validates the complete value and takes an immutable structural snapshot. */
    public static Builder builder() { return new Builder(); }
    /** Starts an independent builder from this value; referenced resources remain borrowed. */
    public Builder toBuilder() { return new Builder().fonts(fonts()).missingGlyphPolicy(missingGlyphPolicy()).placeholderCodePoint(placeholderCodePoint()); }

    /** Mutable, task-confined construction state; built values do not retain this builder. */
    public static final class Builder {
        private List<FontFace> fonts = new java.util.ArrayList<>();
        private MissingGlyphPolicy missingGlyphPolicy = MissingGlyphPolicy.ERROR;
        private int placeholderCodePoint = 0x25a1;

        private Builder() { }
        public Builder fonts(List<FontFace> value) { fonts = new java.util.ArrayList<>(java.util.List.copyOf(value)); return this; }
        public Builder missingGlyphPolicy(MissingGlyphPolicy value) { missingGlyphPolicy = java.util.Objects.requireNonNull(value, "missingGlyphPolicy"); return this; }
        public Builder placeholderCodePoint(int value) { placeholderCodePoint = value; return this; }
        /** Appends an explicit face; no system font lookup occurs. */
        public Builder font(FontFace face) { fonts.add(java.util.Objects.requireNonNull(face, "face")); return this; }
        public FontGroup build() { return new FontGroup(fonts, missingGlyphPolicy, placeholderCodePoint); }
    }
}
