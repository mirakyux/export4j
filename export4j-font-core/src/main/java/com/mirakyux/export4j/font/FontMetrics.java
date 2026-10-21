// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.font;

/** Immutable font metrics in em units; ascent, descent and line gap are nonnegative. */
public record FontMetrics(double ascent, double descent, double lineGap) {
    public FontMetrics {
        if (!Double.isFinite(ascent) || ascent < 0 || !Double.isFinite(descent) || descent < 0
                || !Double.isFinite(lineGap) || lineGap < 0 || !Double.isFinite(ascent + descent)
                || ascent + descent <= 0) {
            throw new IllegalArgumentException("font metrics must describe a finite positive line box");
        }
    }
}
