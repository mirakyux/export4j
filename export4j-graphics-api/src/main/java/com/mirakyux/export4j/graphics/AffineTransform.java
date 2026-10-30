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

    /** Clockwise rotation fitted inside the original positive rectangle, preserving flow geometry. */
    public static AffineTransform fittedRotation(Rect bounds, double degrees) {
        if (!Double.isFinite(degrees) || Math.abs(degrees) > 180) {
            throw new IllegalArgumentException("rotation must be finite and within -180..180 degrees");
        }
        double radians = Math.toRadians(degrees);
        double cosine = Math.cos(radians), sine = Math.sin(radians);
        double width = bounds.width(), height = bounds.height();
        double rotatedWidth = Math.abs(width * cosine) + Math.abs(height * sine);
        double rotatedHeight = Math.abs(width * sine) + Math.abs(height * cosine);
        double scale = Math.min(width / rotatedWidth, height / rotatedHeight);
        double a = scale * cosine, b = scale * sine, c = -b, d = a;
        double cx = bounds.x() + width / 2, cy = bounds.y() + height / 2;
        return new AffineTransform(a, b, c, d, cx - a * cx - c * cy, cy - b * cx - d * cy);
    }
}
