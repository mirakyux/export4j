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

    /** Converts explicit positive millimetre dimensions to the engine's point dimensions. */
    public static PageSize ofMillimeters(double width, double height) {
        return new PageSize(Units.mm(width), Units.mm(height));
    }

    /** Converts explicit positive inch dimensions to the engine's point dimensions. */
    public static PageSize ofInches(double width, double height) {
        return new PageSize(Units.inches(width), Units.inches(height));
    }

    /** Returns these dimensions with width at least height; square pages and existing landscape sizes are unchanged. */
    public PageSize landscape() { return width >= height ? this : new PageSize(height, width); }

    /** Returns these dimensions with height at least width; square pages and existing portrait sizes are unchanged. */
    public PageSize portrait() { return height >= width ? this : new PageSize(height, width); }
}
