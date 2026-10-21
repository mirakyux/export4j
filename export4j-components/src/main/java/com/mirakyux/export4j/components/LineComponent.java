// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.List;
import java.util.Objects;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.VectorPath;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.model.PageSize;

/** Straight line with endpoints expressed as [0,1] viewport fractions; stroke stays inside the viewport. */
public record LineComponent(Point start, Point end, double strokeWidth, RgbColor color) implements Component {
    public LineComponent {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(color, "color");
        if (!Double.isFinite(strokeWidth) || strokeWidth <= 0 || !unit(start) || !unit(end) || start.equals(end)) {
            throw new IllegalArgumentException("invalid line endpoints or stroke");
        }
    }

    public static LineComponent horizontal(double strokeWidth, RgbColor color) {
        return new LineComponent(new Point(0, 0.5), new Point(1, 0.5), strokeWidth, color);
    }

    @Override
    public ComponentLayout layout(ComponentContext context) {
        if (strokeWidth > Math.min(context.width(), context.height())) {
            throw new IllegalArgumentException("stroke exceeds line viewport");
        }
        var path = new VectorPath(List.of(new VectorPath.MoveTo(point(start, context)), new VectorPath.LineTo(point(end, context))));
        var builder = new DisplayListBuilder(new PageSize(context.width(), context.height())).strokePath(path, color, strokeWidth);
        return new ComponentLayout(context.width(), context.height(), 0, builder.build().commands(), List.of());
    }

    private Point point(Point point, ComponentContext context) {
        return new Point(strokeWidth / 2 + point.x() * (context.width() - strokeWidth),
                strokeWidth / 2 + point.y() * (context.height() - strokeWidth));
    }

    private static boolean unit(Point point) {
        return point.x() >= 0 && point.x() <= 1 && point.y() >= 0 && point.y() <= 1;
    }

    /** Task-confined builder. Dimensions use points unless the type specifies another unit.
     * build() validates the complete value and takes an immutable structural snapshot. */
    public static Builder builder() { return new Builder(); }
    /** Starts an independent builder from this value; referenced resources remain borrowed. */
    public Builder toBuilder() { return new Builder().start(start()).end(end()).strokeWidth(strokeWidth()).color(color()); }

    /** Mutable, task-confined construction state; built values do not retain this builder. */
    public static final class Builder {
        private Point start = new Point(0, 0.5);
        private Point end = new Point(1, 0.5);
        private double strokeWidth = 1;
        private RgbColor color = RgbColor.BLACK;

        private Builder() { }
        public Builder start(Point value) { start = java.util.Objects.requireNonNull(value, "start"); return this; }
        public Builder end(Point value) { end = java.util.Objects.requireNonNull(value, "end"); return this; }
        public Builder strokeWidth(double value) { strokeWidth = value; return this; }
        public Builder color(RgbColor value) { color = java.util.Objects.requireNonNull(value, "color"); return this; }

        public LineComponent build() { return new LineComponent(start, end, strokeWidth, color); }
    }
}
