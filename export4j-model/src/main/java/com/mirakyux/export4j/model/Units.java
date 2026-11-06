// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

/** Explicit finite length conversions to points (1 pt = 1/72 inch).
 * Signed values are accepted for offsets; each destination geometry validates its own positive-size requirements.
 * No unit metadata or machine-dependent resolution is retained.
 */
public final class Units {
    private static final double POINTS_PER_INCH = 72;

    private Units() { }

    /** Returns a finite point value unchanged, including signed offsets. */
    public static double pt(double value) {
        if (!Double.isFinite(value)) { throw new IllegalArgumentException("length must be finite"); }
        return value;
    }

    /** Converts millimetres to points; rejects non-finite inputs and conversion overflow. */
    public static double mm(double value) { return scale(value, POINTS_PER_INCH / 25.4); }

    /** Converts centimetres to points; rejects non-finite inputs and conversion overflow. */
    public static double cm(double value) { return scale(value, POINTS_PER_INCH / 2.54); }

    /** Converts inches to points; rejects non-finite inputs and conversion overflow. */
    public static double inches(double value) { return scale(value, POINTS_PER_INCH); }

    private static double scale(double value, double pointsPerUnit) {
        double points = pt(value) * pointsPerUnit;
        return pt(points);
    }
}
