// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.graphics;

import java.util.List;
import java.util.Objects;

/** Immutable paint resources in local point coordinates. Matrices map paint coordinates to the drawing coordinates. */
public sealed interface Paint permits Paint.Solid, Paint.Linear, Paint.Radial, Paint.Tile {
    record Solid(ColorValue color) implements Paint { public Solid { Objects.requireNonNull(color, "color"); } }
    enum Spread { PAD, REPEAT, REFLECT }
    record Stop(double offset, ColorValue color, double opacity) {
        public Stop {
            Objects.requireNonNull(color, "color");
            if (!Double.isFinite(offset) || offset < 0 || offset > 1 || !Double.isFinite(opacity) || opacity < 0 || opacity > 1) { throw new IllegalArgumentException("invalid gradient stop"); }
        }
        public Stop(double offset, ColorValue color) { this(offset, color, 1); }
    }
    record Linear(Point start, Point end, List<Stop> stops, Spread spread, AffineTransform transform) implements Paint {
        public Linear {
            Objects.requireNonNull(start, "start"); Objects.requireNonNull(end, "end"); Objects.requireNonNull(spread, "spread"); Objects.requireNonNull(transform, "transform");
            stops = validate(stops);
            double dx = end.x() - start.x(), dy = end.y() - start.y(); double norm = dx * dx + dy * dy;
            if (!Double.isFinite(norm) || norm <= 0) { throw new IllegalArgumentException("gradient vector must be nonempty and numerically representable"); }
            matrix(transform);
        }
        public Linear(Point start, Point end, List<Stop> stops) { this(start, end, stops, Spread.PAD, AffineTransform.IDENTITY); }
    }
    record Radial(Point center, Point focus, double radius, List<Stop> stops, Spread spread, AffineTransform transform) implements Paint {
        public Radial {
            Objects.requireNonNull(center, "center"); Objects.requireNonNull(focus, "focus"); Objects.requireNonNull(spread, "spread"); Objects.requireNonNull(transform, "transform");
            stops = validate(stops);
            if (!Double.isFinite(radius) || radius <= 0 || Math.hypot(focus.x() - center.x(), focus.y() - center.y()) >= radius) { throw new IllegalArgumentException("radial focus must lie strictly inside positive radius"); }
            if (!Double.isFinite(radius * radius) || radius * radius == 0) { throw new IllegalArgumentException("radial radius precision exceeded"); }
            matrix(transform);
        }
        public Radial(Point center, double radius, List<Stop> stops) { this(center, center, radius, stops, Spread.PAD, AffineTransform.IDENTITY); }
    }
    /** Colored, repeating vector tile. Commands use the same logical coordinate system as bounds. */
    record Tile(Rect bounds, double xStep, double yStep, AffineTransform transform, List<DrawCommand> commands) implements Paint {
        public Tile {
            Objects.requireNonNull(bounds, "bounds"); Objects.requireNonNull(transform, "transform"); commands = List.copyOf(commands);
            if (!Double.isFinite(xStep) || !Double.isFinite(yStep) || xStep <= 0 || yStep <= 0 || xStep > 14_400 || yStep > 14_400 || bounds.width() > xStep || bounds.height() > yStep) { throw new IllegalArgumentException("pattern cells must fit their positive, bounded repeat steps"); }
            matrix(transform);
            validateCommands(commands);
        }
    }
    private static List<Stop> validate(List<Stop> source) {
        var stops = List.copyOf(source);
        if (stops.size() < 2 || stops.size() > 256 || stops.getFirst().offset() != 0 || stops.getLast().offset() != 1) { throw new IllegalArgumentException("gradient requires 2..256 endpoint-inclusive stops"); }
        double prior = -1;
        for (var stop : stops) { if (stop.offset() < prior) { throw new IllegalArgumentException("gradient stops must be ordered"); } prior = stop.offset(); }
        return stops;
    }
    private static void matrix(AffineTransform transform) {
        double determinant = transform.a() * transform.d() - transform.b() * transform.c();
        if (!Double.isFinite(determinant) || determinant == 0) { throw new IllegalArgumentException("paint matrix must be invertible at numeric precision"); }
    }
    static void validateCommands(List<DrawCommand> commands) {
        int depth = 0;
        if (commands.size() > 100_000) { throw new IllegalArgumentException("nested command budget exceeded"); }
        for (var command : commands) {
            Objects.requireNonNull(command, "command");
            if (command instanceof DrawCommand.Save) { depth++; }
            else if (command instanceof DrawCommand.Restore && --depth < 0) { throw new IllegalArgumentException("nested fragment restores outer state"); }
        }
        if (depth != 0) { throw new IllegalArgumentException("unbalanced nested fragment state"); }
    }
}
