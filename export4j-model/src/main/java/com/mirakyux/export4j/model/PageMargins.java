// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

/** Immutable margins in points, ordered top/right/bottom/left; all finite and nonnegative. */
public record PageMargins(double top, double right, double bottom, double left) {
    public PageMargins {
        if (!valid(top) || !valid(right) || !valid(bottom) || !valid(left)) {
            throw new IllegalArgumentException("page margins must be finite and nonnegative");
        }
    }

    public static PageMargins uniform(double points) {
        return new PageMargins(points, points, points, points);
    }

    private static boolean valid(double value) {
        return Double.isFinite(value) && value >= 0;
    }
}
