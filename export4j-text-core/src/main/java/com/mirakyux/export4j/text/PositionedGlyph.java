// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.text;

/** One Unicode scalar mapped to a glyph, with an explicit nonnegative advance in points. */
public record PositionedGlyph(int codePoint, int glyphId, double advance) {
    public PositionedGlyph {
        if (!Character.isValidCodePoint(codePoint) || (codePoint >= 0xD800 && codePoint <= 0xDFFF)
                || Character.isISOControl(codePoint) || glyphId <= 0 || glyphId > 65_535
                || !Double.isFinite(advance) || advance < 0) {
            throw new IllegalArgumentException("invalid positioned glyph");
        }
    }

    public String text() {
        return new String(Character.toChars(codePoint));
    }
}
