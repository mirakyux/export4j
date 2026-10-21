// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.text;

import java.util.List;
import java.util.Objects;

/** Backend-neutral, pre-shaped line ink. Coordinates are relative to the line baseline, in points. */
public record TextOutline(String logicalText, List<Segment> segments, int rgb) {
    public TextOutline {
        Objects.requireNonNull(logicalText, "logicalText");
        segments = List.copyOf(segments);
        if (rgb < -1 || rgb > 0xffffff) { throw new IllegalArgumentException("invalid ink"); }
    }

    public enum Operation { MOVE, LINE, CURVE, CLOSE }

    /** Cubic curves use six coordinates, move/line two, close none. */
    public record Segment(Operation operation, List<Double> coordinates) {
        public Segment {
            Objects.requireNonNull(operation, "operation");
            coordinates = List.copyOf(coordinates);
            int expected = switch (operation) { case MOVE, LINE -> 2; case CURVE -> 6; case CLOSE -> 0; };
            if (coordinates.size() != expected || coordinates.stream().anyMatch(v -> !Double.isFinite(v))) {
                throw new IllegalArgumentException("invalid outline coordinates");
            }
        }
    }
}
