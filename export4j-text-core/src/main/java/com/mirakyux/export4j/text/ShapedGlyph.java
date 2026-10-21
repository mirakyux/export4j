// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.text;

import java.util.Objects;
import com.mirakyux.export4j.font.FontData;

/** Source-associated glyph; metrics are in points, indexes are original UTF-16 offsets. */
public record ShapedGlyph(FontData font, PositionedGlyph glyph, int sourceStart, int sourceEnd,
        double ascent, double descent, int sourceCodePoint) {
    public ShapedGlyph {
        Objects.requireNonNull(font, "font");
        Objects.requireNonNull(glyph, "glyph");
        if (sourceStart < 0 || !Character.isValidCodePoint(sourceCodePoint) || sourceCodePoint >= 0xd800 && sourceCodePoint <= 0xdfff
                || sourceEnd - sourceStart != Character.charCount(sourceCodePoint)
                || !Double.isFinite(ascent) || ascent < 0 || !Double.isFinite(descent) || descent < 0) {
            throw new IllegalArgumentException("invalid shaped glyph range or metrics");
        }
    }

    public ShapedGlyph(FontData font, PositionedGlyph glyph, int sourceStart, int sourceEnd, double ascent, double descent) {
        this(font, glyph, sourceStart, sourceEnd, ascent, descent, glyph.codePoint());
    }
}
