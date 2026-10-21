// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

/**
 * Immutable page dimensions in points (1 pt = 1/72 inch).
 * Both dimensions must be finite and strictly positive.
 */
public record PageSize(double width, double height) {
    public static final PageSize A4 = new PageSize(595.2755905511812, 841.8897637795277);
    public static final PageSize LETTER = new PageSize(612, 792);

    public PageSize {
        if (!Double.isFinite(width) || width <= 0) {
            throw new IllegalArgumentException("width must be finite and positive");
        }
        if (!Double.isFinite(height) || height <= 0) {
            throw new IllegalArgumentException("height must be finite and positive");
        }
    }
}
