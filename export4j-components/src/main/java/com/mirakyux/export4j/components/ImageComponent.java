// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.List;
import java.util.Objects;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbImage;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.model.PageSize;

/** Explicit immutable RGB resource. Fills the viewport; no decoding, file/URL access, or implicit resource ownership. */
public record ImageComponent(RgbImage image, Fit fit) implements Component {
    public enum Fit { CONTAIN, COVER, STRETCH }

    public ImageComponent {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(fit, "fit");
    }

    @Override
    public ComponentLayout layout(ComponentContext context) {
        double width = context.width();
        double height = context.height();
        if (fit != Fit.STRETCH) {
            double scale = fit == Fit.CONTAIN ? Math.min(width / image.width(), height / image.height())
                    : Math.max(width / image.width(), height / image.height());
            width = image.width() * scale;
            height = image.height() * scale;
        }
        var builder = new DisplayListBuilder(new PageSize(context.width(), context.height()));
        builder.save().clip(new Rect(0, 0, context.width(), context.height()))
                .drawImage(image, new Rect((context.width() - width) / 2, (context.height() - height) / 2, width, height)).restore();
        return new ComponentLayout(context.width(), context.height(), 0, builder.build().commands(), List.of());
    }

    /** Task-confined builder. Dimensions use points unless the type specifies another unit.
     * build() validates the complete value and takes an immutable structural snapshot. */
    public static Builder builder() { return new Builder(); }
    public static Builder builder(RgbImage image) { return new Builder().image(image); }
    /** Starts an independent builder from this value; referenced resources remain borrowed. */
    public Builder toBuilder() { return new Builder().image(image()).fit(fit()); }

    /** Mutable, task-confined construction state; built values do not retain this builder. */
    public static final class Builder {
        private RgbImage image = null;
        private Fit fit = Fit.CONTAIN;

        private Builder() { }
        public Builder image(RgbImage value) { image = java.util.Objects.requireNonNull(value, "image"); return this; }
        public Builder fit(Fit value) { fit = java.util.Objects.requireNonNull(value, "fit"); return this; }

        public ImageComponent build() { return new ImageComponent(image, fit); }
    }
}
