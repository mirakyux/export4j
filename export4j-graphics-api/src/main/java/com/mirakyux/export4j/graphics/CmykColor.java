// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.graphics;

/** Device CMYK ink coverage in [0,1]. It carries no implicit printer profile. */
public record CmykColor(double cyan, double magenta, double yellow, double black) implements ColorValue {
    public CmykColor {
        for (double value : new double[] {cyan, magenta, yellow, black}) {
            if (!Double.isFinite(value) || value < 0 || value > 1) { throw new IllegalArgumentException("CMYK components must be finite in [0,1]"); }
        }
    }
}
