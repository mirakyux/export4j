// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.font;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
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

    /** Ordered explicit faces, snapshotted with the same strict policy as the list overload. */
    public static FontGroup strict(FontFace... fonts) { return strict(List.of(fonts)); }

    /** Placeholder must itself be supported by at least one group font when substitution is needed. */
    public static FontGroup placeholder(List<FontFace> fonts, int codePoint) {
        return new FontGroup(fonts, MissingGlyphPolicy.PLACEHOLDER_WITH_WARNING, codePoint);
    }

    /** Explicit replacement code point and ordered faces; the supplied array is not retained. */
    public static FontGroup placeholder(int codePoint, FontFace... fonts) {
        return placeholder(List.of(fonts), codePoint);
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
        /** Replaces the ordered faces with an immutable snapshot of the supplied values. */
        public Builder fonts(FontFace... value) { return fonts(List.of(value)); }
        public Builder missingGlyphPolicy(MissingGlyphPolicy value) { missingGlyphPolicy = java.util.Objects.requireNonNull(value, "missingGlyphPolicy"); return this; }
        public Builder placeholderCodePoint(int value) { placeholderCodePoint = value; return this; }
        /** Appends an explicit face; no system font lookup occurs. */
        public Builder font(FontFace face) { fonts.add(java.util.Objects.requireNonNull(face, "face")); return this; }

        /** Loads and appends an explicit local font now; the loader closes its own bounded file stream. */
        public Builder fontFile(Path source) throws IOException { return font(TrueTypeFontLoader.load(source)); }
        /** Explicit local font with an encoded byte limit; failed loading leaves this builder unchanged. */
        public Builder fontFile(Path source, int maxBytes) throws IOException { return font(TrueTypeFontLoader.load(source, maxBytes)); }
        /** Local filesystem path, never a URL or a system-font lookup. */
        public Builder fontFile(String source) throws IOException { return font(TrueTypeFontLoader.load(source)); }
        /** Local filesystem path and an explicit encoded byte limit. */
        public Builder fontFile(String source, int maxBytes) throws IOException { return font(TrueTypeFontLoader.load(source, maxBytes)); }
        /** Explicit local font; the loader closes the stream it opens. */
        public Builder fontFile(File source) throws IOException { return font(TrueTypeFontLoader.load(source)); }
        /** Explicit local font and an encoded byte limit. */
        public Builder fontFile(File source, int maxBytes) throws IOException { return font(TrueTypeFontLoader.load(source, maxBytes)); }
        public FontGroup build() { return new FontGroup(fonts, missingGlyphPolicy, placeholderCodePoint); }
    }
}
