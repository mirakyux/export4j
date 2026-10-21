// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.text;

import java.util.List;
import java.util.Objects;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;

/** Resolved text span with explicit fonts, point size, RGB (-1 inherits painter color), and baseline shift. */
public record StyledSpan(String text, FontGroup fontGroup, double fontSize, int rgb,
        double baselineShift, boolean underline, boolean strikeThrough) {
    public StyledSpan {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(fontGroup, "fontGroup");
        if (!Double.isFinite(fontSize) || fontSize <= 0 || rgb < -1 || rgb > 0xffffff
                || !Double.isFinite(baselineShift)) {
            throw new IllegalArgumentException("invalid styled span");
        }
    }

    public StyledSpan(String text, List<FontFace> fonts, double fontSize, int rgb,
            double baselineShift, boolean underline, boolean strikeThrough) {
        this(text, FontGroup.strict(fonts), fontSize, rgb, baselineShift, underline, strikeThrough);
    }

    public List<FontFace> fonts() {
        return fontGroup.fonts();
    }
}
