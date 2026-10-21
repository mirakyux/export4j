// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.graphics;

import java.util.Objects;

/** Immutable text style; font size is finite and strictly positive, measured in points. */
public record TextStyle(FontFamily family, double fontSize, RgbColor color) {
    public TextStyle {
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(color, "color");
        if (!Double.isFinite(fontSize) || fontSize <= 0) {
            throw new IllegalArgumentException("fontSize must be finite and positive");
        }
    }
}
