// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.text;

/** Positive per-layout limits, measured in Unicode code points and produced lines. */
public record TextLayoutLimits(int maxCodePoints, int maxLines) {
    public static final TextLayoutLimits DEFAULT = new TextLayoutLimits(100_000, 10_000);

    public TextLayoutLimits {
        if (maxCodePoints <= 0 || maxLines <= 0) {
            throw new IllegalArgumentException("text layout limits must be positive");
        }
    }
}
