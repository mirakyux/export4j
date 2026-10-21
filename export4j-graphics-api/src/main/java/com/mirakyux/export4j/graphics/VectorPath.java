// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.graphics;

import java.util.List;
import java.util.Objects;

/**
 * Immutable path in points, painted or clipped using the nonzero winding rule.
 * Each subpath starts with MoveTo; a closed subpath requires a new MoveTo before more segments.
 */
public record VectorPath(List<Segment> segments) {
    public VectorPath {
        segments = List.copyOf(segments);
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("path must contain at least one segment");
        }
        boolean open = false;
        for (Segment segment : segments) {
            switch (segment) {
                case MoveTo ignored -> open = true;
                case LineTo ignored -> requireOpen(open);
                case CurveTo ignored -> requireOpen(open);
                case Close ignored -> {
                    requireOpen(open);
                    open = false;
                }
            }
        }
    }

    private static void requireOpen(boolean open) {
        if (!open) {
            throw new IllegalArgumentException("path segment requires an open subpath");
        }
    }

    /** Closed set of supported path operations; every point uses logical page coordinates. */
    public sealed interface Segment permits MoveTo, LineTo, CurveTo, Close {
    }

    public record MoveTo(Point point) implements Segment {
        public MoveTo {
            Objects.requireNonNull(point, "point");
        }
    }

    public record LineTo(Point point) implements Segment {
        public LineTo {
            Objects.requireNonNull(point, "point");
        }
    }

    /** Cubic Bezier segment ending at end with the two supplied control points. */
    public record CurveTo(Point control1, Point control2, Point end) implements Segment {
        public CurveTo {
            Objects.requireNonNull(control1, "control1");
            Objects.requireNonNull(control2, "control2");
            Objects.requireNonNull(end, "end");
        }
    }

    public record Close() implements Segment {
    }
}
