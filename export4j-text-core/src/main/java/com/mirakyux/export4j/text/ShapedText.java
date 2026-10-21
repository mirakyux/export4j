// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.text;

import java.util.List;

/** Immutable shaping result preserving the selected font and explicit advance for every glyph. */
public record ShapedText(List<ShapedGlyph> glyphs, List<TextDiagnostic> diagnostics) {
    public ShapedText {
        glyphs = List.copyOf(glyphs);
        diagnostics = List.copyOf(diagnostics);
    }

    public double width() {
        return glyphs.stream().mapToDouble(glyph -> glyph.glyph().advance()).sum();
    }
}
