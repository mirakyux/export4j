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

/** Rectangle or cubic-Bezier ellipse within the viewport, opaque fill and optional internal stroke, point units. */
public record ShapeComponent(Kind kind, RgbColor fill, RgbColor stroke, double strokeWidth) implements Component {
    public enum Kind { RECTANGLE, ELLIPSE }

    public ShapeComponent {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(fill, "fill");
        Objects.requireNonNull(stroke, "stroke");
        if (!Double.isFinite(strokeWidth) || strokeWidth < 0) {
            throw new IllegalArgumentException("stroke width must be finite and nonnegative");
        }
    }

    @Override
    public ComponentLayout layout(ComponentContext context) {
        if (strokeWidth >= Math.min(context.width(), context.height())) {
            throw new IllegalArgumentException("stroke consumes shape viewport");
        }
        double left = strokeWidth / 2;
        double top = strokeWidth / 2;
        double right = context.width() - left;
        double bottom = context.height() - top;
        VectorPath path;
        if (kind == Kind.RECTANGLE) {
            path = new VectorPath(List.of(new VectorPath.MoveTo(new Point(left, top)),
                    new VectorPath.LineTo(new Point(right, top)), new VectorPath.LineTo(new Point(right, bottom)),
                    new VectorPath.LineTo(new Point(left, bottom)), new VectorPath.Close()));
        } else {
            double cx = (left + right) / 2;
            double cy = (top + bottom) / 2;
            double rx = (right - left) / 2;
            double ry = (bottom - top) / 2;
            double k = 0.5522847498307936;
            path = new VectorPath(List.of(new VectorPath.MoveTo(new Point(right, cy)),
                    new VectorPath.CurveTo(new Point(right, cy + k * ry), new Point(cx + k * rx, bottom), new Point(cx, bottom)),
                    new VectorPath.CurveTo(new Point(cx - k * rx, bottom), new Point(left, cy + k * ry), new Point(left, cy)),
                    new VectorPath.CurveTo(new Point(left, cy - k * ry), new Point(cx - k * rx, top), new Point(cx, top)),
                    new VectorPath.CurveTo(new Point(cx + k * rx, top), new Point(right, cy - k * ry), new Point(right, cy)),
                    new VectorPath.Close()));
        }
        var builder = new DisplayListBuilder(new PageSize(context.width(), context.height()));
        builder.fillPath(path, fill);
        if (strokeWidth > 0) {
            builder.strokePath(path, stroke, strokeWidth);
        }
        return new ComponentLayout(context.width(), context.height(), 0, builder.build().commands(), List.of());
    }

    /** Task-confined builder. Dimensions use points unless the type specifies another unit.
     * build() validates the complete value and takes an immutable structural snapshot. */
    public static Builder builder() { return new Builder(); }
    /** Starts an independent builder from this value; referenced resources remain borrowed. */
    public Builder toBuilder() { return new Builder().kind(kind()).fill(fill()).stroke(stroke()).strokeWidth(strokeWidth()); }

    /** Mutable, task-confined construction state; built values do not retain this builder. */
    public static final class Builder {
        private Kind kind = Kind.RECTANGLE;
        private RgbColor fill = RgbColor.WHITE;
        private RgbColor stroke = RgbColor.BLACK;
        private double strokeWidth = 1;

        private Builder() { }
        public Builder kind(Kind value) { kind = java.util.Objects.requireNonNull(value, "kind"); return this; }
        public Builder fill(RgbColor value) { fill = java.util.Objects.requireNonNull(value, "fill"); return this; }
        public Builder stroke(RgbColor value) { stroke = java.util.Objects.requireNonNull(value, "stroke"); return this; }
        public Builder strokeWidth(double value) { strokeWidth = value; return this; }

        public ShapeComponent build() { return new ShapeComponent(kind, fill, stroke, strokeWidth); }
    }
}
