// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.Objects;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.model.PageSize;

/** Reserves explicit point dimensions for a child, useful for images/shapes in flow. Child remains top-left aligned. */
public record BoxComponent(double width, double height, Component child) implements Component {
    public BoxComponent {
        new PageSize(width, height);
        Objects.requireNonNull(child, "child");
    }

    @Override
    public ComponentLayout layout(ComponentContext context) {
        ComponentSupport.fits(width, height, context);
        var result = child.layout(context.viewport(width, height));
        if (result.width() > Math.nextUp(width) || result.height() > Math.nextUp(height)) {
            throw new IllegalArgumentException("child exceeds box");
        }
        return new ComponentLayout(width, height, result.textLines(), result.staticCommands(), result.diagnostics(), result.semanticPlacements());
    }

    /** Task-confined builder. Dimensions use points unless the type specifies another unit.
     * build() validates the complete value and takes an immutable structural snapshot. */
    public static Builder builder() { return new Builder(); }
    /** Starts an independent builder from this value; referenced resources remain borrowed. */
    public Builder toBuilder() { return new Builder().width(width()).height(height()).child(child()); }

    /** Mutable, task-confined construction state; built values do not retain this builder. */
    public static final class Builder {
        private double width = 0;
        private double height = 0;
        private Component child = null;

        private Builder() { }
        public Builder width(double value) { width = value; return this; }
        public Builder height(double value) { height = value; return this; }
        public Builder child(Component value) { child = java.util.Objects.requireNonNull(value, "child"); return this; }

        public BoxComponent build() { return new BoxComponent(width, height, child); }
    }
}
