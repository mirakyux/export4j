// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

/** Explicit point width or a positive weight sharing space left after fixed columns. */
public sealed interface ColumnWidth permits ColumnWidth.Fixed, ColumnWidth.Fraction, ColumnWidth.Auto {
    /** Content-preferred width clamped to explicit bounds; may shrink toward minimum to fit the page. */
    record Auto(double minPoints, double maxPoints) implements ColumnWidth {
        public Auto {
            positive(minPoints); positive(maxPoints);
            if (minPoints > maxPoints) { throw new IllegalArgumentException("automatic column bounds are reversed"); }
        }
    }
    record Fixed(double points) implements ColumnWidth {
        public Fixed {
            positive(points);
        }
    }

    record Fraction(double weight) implements ColumnWidth {
        public Fraction {
            positive(weight);
        }
    }

    private static void positive(double value) {
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException("column width or weight must be finite and positive");
        }
    }
}
