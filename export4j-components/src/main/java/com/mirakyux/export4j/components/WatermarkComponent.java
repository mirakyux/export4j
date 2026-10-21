// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.Objects;
import com.mirakyux.export4j.graphics.AffineTransform;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.graphics.core.TextLayoutPainter;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.text.ParagraphLayouter;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextAlignment;

/** Centered rotated text, explicitly clipped to its viewport; use pale RGB in the background layer. No transparency. */
public record WatermarkComponent(String text, double fontSize, double angleDegrees, RgbColor color) implements Component {
    public WatermarkComponent {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(color, "color");
        ParagraphStyle.of(fontSize);
        if (!Double.isFinite(angleDegrees)) {
            throw new IllegalArgumentException("watermark angle must be finite");
        }
    }

    @Override
    public ComponentLayout layout(ComponentContext context) {
        var textLayout = new ParagraphLayouter().layout(text, context.fontGroup(), context.width(),
                new ParagraphStyle(fontSize, fontSize * 1.5, 0, 0, TextAlignment.CENTER));
        ComponentSupport.fits(textLayout.width(), textLayout.height(), context);
        double angle = Math.toRadians(angleDegrees % 360);
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        double cx = context.width() / 2;
        double cy = context.height() / 2;
        var builder = new DisplayListBuilder(new PageSize(context.width(), context.height()));
        builder.save().clip(new Rect(0, 0, context.width(), context.height()))
                .transform(new AffineTransform(cos, sin, -sin, cos, cx - cos * cx + sin * cy, cy - sin * cx - cos * cy));
        TextLayoutPainter.draw(builder, textLayout, new Point(0, (context.height() - textLayout.height()) / 2), color);
        builder.restore();
        return new ComponentLayout(context.width(), context.height(), textLayout.lines().size(),
                builder.build().commands(), textLayout.diagnostics());
    }

    /** Task-confined builder. Dimensions use points unless the type specifies another unit.
     * build() validates the complete value and takes an immutable structural snapshot. */
    public static Builder builder() { return new Builder(); }
    public static Builder builder(String text) { return new Builder().text(text); }
    /** Starts an independent builder from this value; referenced resources remain borrowed. */
    public Builder toBuilder() { return new Builder().text(text()).fontSize(fontSize()).angleDegrees(angleDegrees()).color(color()); }

    /** Mutable, task-confined construction state; built values do not retain this builder. */
    public static final class Builder {
        private String text = "";
        private double fontSize = 36;
        private double angleDegrees = -30;
        private RgbColor color = new RgbColor(0.85, 0.85, 0.85);

        private Builder() { }
        public Builder text(String value) { text = java.util.Objects.requireNonNull(value, "text"); return this; }
        public Builder fontSize(double value) { fontSize = value; return this; }
        public Builder angleDegrees(double value) { angleDegrees = value; return this; }
        public Builder color(RgbColor value) { color = java.util.Objects.requireNonNull(value, "color"); return this; }

        public WatermarkComponent build() { return new WatermarkComponent(text, fontSize, angleDegrees, color); }
    }
}
