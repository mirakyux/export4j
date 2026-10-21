// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.graphics;

/** Immutable, opaque sRGB color with finite channels in the inclusive range [0, 1]. */
public record RgbColor(double red, double green, double blue) {
    public static final RgbColor BLACK = new RgbColor(0, 0, 0);
    public static final RgbColor WHITE = new RgbColor(1, 1, 1);

    public RgbColor {
        if (!valid(red) || !valid(green) || !valid(blue)) {
            throw new IllegalArgumentException("RGB channels must be finite and between 0 and 1");
        }
    }

    private static boolean valid(double channel) {
        return Double.isFinite(channel) && channel >= 0 && channel <= 1;
    }
}
