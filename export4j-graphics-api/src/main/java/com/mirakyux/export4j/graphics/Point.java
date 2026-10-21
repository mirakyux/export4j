// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.graphics;

/** Finite point coordinates; page origin is top-left, X rightward and Y downward. */
public record Point(double x, double y) {
    public Point {
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new IllegalArgumentException("point coordinates must be finite");
        }
    }
}
