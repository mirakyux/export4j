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

/** Static writing line spanning the supplied viewport width, with explicit height, bottom inset
 * and stroke width in points. Includes half-stroke horizontal insets so ink stays inside.
 * Immutable, thread-safe, no resources or editable field. Invalid or unfit geometry fails.
 */
public record FillLine(double height, double bottomInset, double strokeWidth, RgbColor color) implements Component {
    public FillLine {
        Objects.requireNonNull(color, "color");
        if (!Double.isFinite(height) || height <= 0 || !Double.isFinite(bottomInset)
                || !Double.isFinite(strokeWidth) || strokeWidth <= 0
                || bottomInset < strokeWidth / 2 || bottomInset > height - strokeWidth / 2) {
            throw new IllegalArgumentException("invalid writing line geometry");
        }
    }
    @Override public ComponentLayout layout(ComponentContext context) {
        ComponentSupport.fits(context.width(), height, context);
        if (strokeWidth >= context.width()) { throw new IllegalArgumentException("stroke consumes writing line width"); }
        var builder = new DisplayListBuilder(new PageSize(context.width(), height));
        builder.strokePath(new VectorPath(List.of(new VectorPath.MoveTo(new Point(strokeWidth / 2, height - bottomInset)),
                new VectorPath.LineTo(new Point(context.width() - strokeWidth / 2, height - bottomInset)))), color, strokeWidth);
        return new ComponentLayout(context.width(), height, 0, builder.build().commands(), List.of());
    }
    public static Builder builder() { return new Builder(); }
    public Builder toBuilder() { return builder().height(height).bottomInset(bottomInset).strokeWidth(strokeWidth).color(color); }
    /** Mutable task-confined construction state. */
    public static final class Builder {
        private double height = 18;
        private double bottomInset = 3;
        private double strokeWidth = .5;
        private RgbColor color = RgbColor.BLACK;
        private Builder() { }
        public Builder height(double value) { height = value; return this; }
        public Builder bottomInset(double value) { bottomInset = value; return this; }
        public Builder strokeWidth(double value) { strokeWidth = value; return this; }
        public Builder color(RgbColor value) { color = Objects.requireNonNull(value, "color"); return this; }
        public FillLine build() { return new FillLine(height, bottomInset, strokeWidth, color); }
    }
}
