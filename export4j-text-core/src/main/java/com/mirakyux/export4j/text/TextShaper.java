// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.text;

import java.util.List;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.MissingGlyphPolicy;

/** Horizontal shaping SPI; input is a single line, sourceOffset is an original UTF-16 offset. */
public interface TextShaper {
    ShapedText shape(String text, List<FontFace> fonts, double fontSize, int sourceOffset);

    /** Existing custom shapers remain strict; supporting substitution requires an explicit override. */
    default ShapedText shape(String text, FontGroup group, double fontSize, int sourceOffset) {
        if (group.missingGlyphPolicy() != MissingGlyphPolicy.ERROR) {
            throw new IllegalArgumentException("this shaper does not support missing-glyph substitution");
        }
        return shape(text, group.fonts(), fontSize, sourceOffset);
    }
}
