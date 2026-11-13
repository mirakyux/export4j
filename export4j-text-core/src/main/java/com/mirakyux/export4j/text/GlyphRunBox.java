// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.text;

import java.util.Objects;

/** A directly mapped logical text fragment at an explicit line-local x, in points.
 * Source offsets are original UTF-16 positions. The run supplies its baseline shift;
 * image advances and preceding fragments never need to be reconstructed by painters.
 */
public record GlyphRunBox(int sourceStart, int sourceEnd, double x, GlyphRun run) {
    public GlyphRunBox {
        Objects.requireNonNull(run, "run");
        if (sourceStart < 0 || sourceEnd <= sourceStart || !Double.isFinite(x) || x < 0
                || run.glyphs().isEmpty() || run.text().length() != sourceEnd - sourceStart) {
            throw new IllegalArgumentException("invalid positioned text fragment");
        }
    }
}
