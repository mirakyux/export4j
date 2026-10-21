// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import java.util.Objects;
import com.mirakyux.export4j.graphics.Rect;

/** Resolves a viewport on a page. Absolute uses page coordinates; other modes use the supplied parent region. */
public sealed interface Position permits Position.Absolute, Position.Relative, Position.Fractional {
    Rect resolve(Rect parent);

    record Absolute(Rect bounds) implements Position {
        public Absolute {
            Objects.requireNonNull(bounds, "bounds");
        }

        @Override
        public Rect resolve(Rect parent) {
            Objects.requireNonNull(parent, "parent");
            return bounds;
        }
    }

    enum Anchor {
        TOP_LEFT(0, 0), TOP_CENTER(0.5, 0), TOP_RIGHT(1, 0),
        CENTER_LEFT(0, 0.5), CENTER(0.5, 0.5), CENTER_RIGHT(1, 0.5),
        BOTTOM_LEFT(0, 1), BOTTOM_CENTER(0.5, 1), BOTTOM_RIGHT(1, 1);

        private final double x;
        private final double y;

        Anchor(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }

    /** Aligns the same anchor of a fixed-size viewport and its parent, then adds point offsets. */
    record Relative(Anchor anchor, double width, double height, double offsetX, double offsetY) implements Position {
        public Relative {
            Objects.requireNonNull(anchor, "anchor");
            new Rect(offsetX, offsetY, width, height);
        }

        @Override
        public Rect resolve(Rect parent) {
            return new Rect(parent.x() + (parent.width() - width) * anchor.x + offsetX,
                    parent.y() + (parent.height() - height) * anchor.y + offsetY, width, height);
        }
    }

    /** Fractions of parent width/height; x/y nonnegative, width/height positive, rectangle must stay within parent. */
    record Fractional(double x, double y, double width, double height) implements Position {
        public Fractional {
            new Rect(x, y, width, height);
            if (x < 0 || y < 0 || x + width > 1 || y + height > 1) {
                throw new IllegalArgumentException("fractional viewport must lie within its parent");
            }
        }

        @Override
        public Rect resolve(Rect parent) {
            return new Rect(parent.x() + x * parent.width(), parent.y() + y * parent.height(),
                    width * parent.width(), height * parent.height());
        }
    }
}
