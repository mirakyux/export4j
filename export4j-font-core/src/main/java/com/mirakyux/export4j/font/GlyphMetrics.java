// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.font;

/** Glyph ID and advance in em units, quantized to PDF's 1000-unit horizontal metric grid. */
public record GlyphMetrics(int glyphId, double advance) {
    public GlyphMetrics {
        if (glyphId <= 0 || glyphId > 65_535 || !Double.isFinite(advance) || advance < 0) {
            throw new IllegalArgumentException("invalid glyph ID or advance");
        }
    }
}
