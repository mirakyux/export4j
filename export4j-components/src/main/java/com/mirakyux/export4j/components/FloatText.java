// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.List;
import java.util.Objects;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.PageableComponent;

/** Rectangular float on the first page; text expands to full width below it and on continued pages. */
public record FloatText(OpenTypeText text, Component floating, double floatWidth, double floatHeight, double gap, Side side) implements PageableComponent {
    public enum Side { LEFT, RIGHT }
    public FloatText {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(floating, "floating");
        Objects.requireNonNull(side, "side");
        if (!Double.isFinite(floatWidth) || floatWidth <= 0 || !Double.isFinite(floatHeight) || floatHeight <= 0
                || !Double.isFinite(gap) || gap < 0) { throw new IllegalArgumentException("invalid float geometry"); }
    }

    @Override
    public List<ComponentLayout> layoutParts(ComponentContext context) { return layoutParts(context, context.height()); }

    @Override
    public List<ComponentLayout> layoutParts(ComponentContext context, double firstPageHeight) {
        ComponentSupport.fits(floatWidth + gap, floatHeight, context);
        var box = new MultiFloatText.FloatBox(floating, floatWidth, floatHeight, 0, gap,
                side == Side.LEFT ? MultiFloatText.Side.LEFT : MultiFloatText.Side.RIGHT);
        return new MultiFloatText(text, List.of(box)).layoutParts(context, firstPageHeight);
    }

    /** Task-confined builder. Dimensions use points unless the type specifies another unit.
     * build() validates the complete value and takes an immutable structural snapshot. */
    public static Builder builder() { return new Builder(); }
    public static Builder builder(OpenTypeText text) { return new Builder().text(text); }
    /** Starts an independent builder from this value; referenced resources remain borrowed. */
    public Builder toBuilder() { return new Builder().text(text()).floating(floating()).floatWidth(floatWidth()).floatHeight(floatHeight()).gap(gap()).side(side()); }

    /** Mutable, task-confined construction state; built values do not retain this builder. */
    public static final class Builder {
        private OpenTypeText text = null;
        private Component floating = null;
        private double floatWidth = 0;
        private double floatHeight = 0;
        private double gap = 6;
        private Side side = Side.LEFT;

        private Builder() { }
        public Builder text(OpenTypeText value) { text = java.util.Objects.requireNonNull(value, "text"); return this; }
        public Builder floating(Component value) { floating = java.util.Objects.requireNonNull(value, "floating"); return this; }
        public Builder floatWidth(double value) { floatWidth = value; return this; }
        public Builder floatHeight(double value) { floatHeight = value; return this; }
        public Builder gap(double value) { gap = value; return this; }
        public Builder side(Side value) { side = java.util.Objects.requireNonNull(value, "side"); return this; }

        public FloatText build() { return new FloatText(text, floating, floatWidth, floatHeight, gap, side); }
    }
}
