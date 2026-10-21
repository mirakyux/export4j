// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.text;

import java.util.Objects;

/** Immutable atomic vector ink at a U+FFFC UTF-16 source position. Dimensions are points;
 * coordinates in ink are local to the baseline, inside [0,width] x [-ascent,descent].
 * No glyph is requested for the marker. The default paragraph layouter places the ink once;
 * no callbacks, resources or mutable drawing state are retained. Thread-safe.
 */
public record InlineGraphic(double width, double ascent, double descent, TextOutline ink) {
    public InlineGraphic {
        Objects.requireNonNull(ink, "ink");
        if (!Double.isFinite(width) || width <= 0 || width > Float.MAX_VALUE
                || !Double.isFinite(ascent) || ascent < 0 || ascent > Float.MAX_VALUE
                || !Double.isFinite(descent) || descent < 0 || descent > Float.MAX_VALUE
                || ascent + descent <= 0 || ink.segments().size() > 10_000
                || ink.logicalText().length() > 1024 || ink.logicalText().codePoints().anyMatch(cp -> Character.isISOControl(cp)
                || cp >= 0xd800 && cp <= 0xdfff || cp == 0xfffe || cp == 0xffff
                || Character.getType(cp) == Character.LINE_SEPARATOR || Character.getType(cp) == Character.PARAGRAPH_SEPARATOR)) {
            throw new IllegalArgumentException("invalid inline graphic dimensions or budget");
        }
        for (var segment : ink.segments()) {
            var c = segment.coordinates();
            for (int index = 0; index < c.size(); index += 2) {
                if (c.get(index) < 0 || c.get(index) > width
                        || c.get(index + 1) < -ascent || c.get(index + 1) > descent) {
                    throw new IllegalArgumentException("inline graphic ink exceeds its reserved box");
                }
            }
        }
    }
}
