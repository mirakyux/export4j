// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.math;

/** Immutable, finite per-formula limits. Sizes are points; source lengths are UTF-16 units.
 * Native Word equations inherit paragraph size. Vector formulas use the explicit font size.
 * Limits may be tightened; the supported hard ceilings cannot be raised by callers.
 */
public record MathOptions(double fontSize, boolean display, int maxSourceLength, int maxDepth,
        int maxCommands, int maxPaths, int maxSegments, double maxExtent) {
    public static final MathOptions DEFAULT = builder().build();

    public MathOptions {
        if (!Double.isFinite(fontSize) || fontSize < 6 || fontSize > 72
                || maxSourceLength < 1 || maxSourceLength > 1024 || maxDepth < 1 || maxDepth > 16
                || maxCommands < 1 || maxCommands > 256 || maxPaths < 1 || maxPaths > 2048
                || maxSegments < 1 || maxSegments > 10_000
                || !Double.isFinite(maxExtent) || maxExtent <= 0 || maxExtent > 1024) {
            throw new IllegalArgumentException("invalid formula options or hard ceiling exceeded");
        }
    }

    public static Builder builder() { return new Builder(); }

    /** Returns a new value; does not mutate this snapshot. */
    public MathOptions withDisplay(boolean value) {
        return new MathOptions(fontSize, value, maxSourceLength, maxDepth, maxCommands,
                maxPaths, maxSegments, maxExtent);
    }

    /** Mutable caller-confined configuration, copied by build(). */
    public static final class Builder {
        private double fontSize = 12;
        private boolean display;
        private int maxSourceLength = 1024;
        private int maxDepth = 16;
        private int maxCommands = 256;
        private int maxPaths = 2048;
        private int maxSegments = 10_000;
        private double maxExtent = 1024;

        private Builder() { }
        public Builder fontSize(double value) { fontSize = value; return this; }
        public Builder display(boolean value) { display = value; return this; }
        public Builder maxSourceLength(int value) { maxSourceLength = value; return this; }
        public Builder maxDepth(int value) { maxDepth = value; return this; }
        public Builder maxCommands(int value) { maxCommands = value; return this; }
        public Builder maxPaths(int value) { maxPaths = value; return this; }
        public Builder maxSegments(int value) { maxSegments = value; return this; }
        public Builder maxExtent(double value) { maxExtent = value; return this; }
        public MathOptions build() {
            return new MathOptions(fontSize, display, maxSourceLength, maxDepth, maxCommands,
                    maxPaths, maxSegments, maxExtent);
        }
    }
}
