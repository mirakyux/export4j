// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.graphics;

/** Immutable rectangle in points with a top-left position and positive dimensions. */
public record Rect(double x, double y, double width, double height) {
    public Rect {
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new IllegalArgumentException("rectangle coordinates must be finite");
        }
        if (!Double.isFinite(width) || width <= 0 || !Double.isFinite(height) || height <= 0) {
            throw new IllegalArgumentException("rectangle dimensions must be finite and positive");
        }
    }
}
