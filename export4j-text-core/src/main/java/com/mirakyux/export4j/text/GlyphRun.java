// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.text;

import java.util.List;
import java.util.Objects;
import com.mirakyux.export4j.font.FontData;

/** Immutable same-font glyph run. Size and individual advances are in points; no open handles. */
public record GlyphRun(FontData font, double fontSize, List<PositionedGlyph> glyphs,
        int rgb, double baselineShift, boolean underline, boolean strikeThrough) {
    public GlyphRun {
        Objects.requireNonNull(font, "font");
        glyphs = List.copyOf(glyphs);
        if (!Double.isFinite(fontSize) || fontSize <= 0 || rgb < -1 || rgb > 0xffffff || !Double.isFinite(baselineShift)) {
            throw new IllegalArgumentException("fontSize must be finite and positive");
        }
        double width = 0;
        for (PositionedGlyph glyph : glyphs) {
            width += glyph.advance();
        }
        if (!Double.isFinite(width)) {
            throw new IllegalArgumentException("glyph advances must have a finite sum");
        }
    }

    public GlyphRun(FontData font, double fontSize, List<PositionedGlyph> glyphs) {
        this(font, fontSize, glyphs, -1, 0, false, false);
    }

    public double width() {
        double width = 0;
        for (PositionedGlyph glyph : glyphs) {
            width += glyph.advance();
        }
        return width;
    }

    public String text() {
        StringBuilder text = new StringBuilder();
        glyphs.forEach(glyph -> text.appendCodePoint(glyph.codePoint()));
        return text.toString();
    }
}
