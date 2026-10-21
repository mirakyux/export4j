// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.graphics;

/**
 * Finite affine matrix mapping x' = a*x + c*y + e, y' = b*x + d*y + f.
 * Translation values are in points. A canvas concatenates this matrix to its current transform.
 */
public record AffineTransform(double a, double b, double c, double d, double e, double f) {
    public static final AffineTransform IDENTITY = new AffineTransform(1, 0, 0, 1, 0, 0);

    public AffineTransform {
        if (!Double.isFinite(a) || !Double.isFinite(b) || !Double.isFinite(c)
                || !Double.isFinite(d) || !Double.isFinite(e) || !Double.isFinite(f)) {
            throw new IllegalArgumentException("matrix coefficients must be finite");
        }
    }

    /** Creates a translation in points. */
    public static AffineTransform translation(double x, double y) {
        return new AffineTransform(1, 0, 0, 1, x, y);
    }
}
