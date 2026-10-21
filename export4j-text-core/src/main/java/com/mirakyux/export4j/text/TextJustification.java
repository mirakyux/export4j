// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.text;

import java.awt.font.TextHitInfo;
import java.awt.font.TextLayout;
import java.util.List;

/** Precomputed horizontal expansion for physical-font outlines. Immutable and thread-safe.
 * Positions and available width are points relative to a shaped line; glyph shapes are never scaled.
 */
public final class TextJustification {
    private final List<Double> gaps;
    private final double step;

    private TextJustification(List<Double> gaps, double step) { this.gaps = gaps; this.step = step; }

    /** Computes visual gap positions from logical UTF-16 text. Only call for soft-wrapped lines.
     * Lines without eligible gaps retain their original advance. Text must match the supplied JDK layout.
     */
    public static TextJustification of(String text, TextLayout layout, double available) {
        if (text.length() != layout.getCharacterCount() || !Double.isFinite(available) || available < 0) {
            throw new IllegalArgumentException("invalid justification source or width");
        }
        var gaps = LineBreakRules.expansionBoundaries(text).stream()
                .map(boundary -> (double) layout.getCaretInfo(TextHitInfo.trailing(boundary - 1))[0])
                .filter(x -> x > 0 && x < layout.getAdvance()).sorted().distinct().toList();
        return new TextJustification(gaps, gaps.isEmpty() ? 0 : Math.max(0, available - layout.getAdvance()) / gaps.size());
    }

    /** Extra displacement at a visual glyph/object origin, in points. */
    public double shift(double x) {
        int low = 0; int high = gaps.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (gaps.get(middle) <= x + 0.00001) { low = middle + 1; } else { high = middle; }
        }
        return low * step;
    }

    public double expansion() { return gaps.size() * step; }
}
