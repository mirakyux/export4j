// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.Objects;

/** Immutable single-section page settings in points; margins must leave positive content dimensions. */
public record PageSettings(PageSize size, PageMargins margins) {
    public static final PageSettings A4 = new PageSettings(PageSize.A4, PageMargins.uniform(54));

    public PageSettings {
        Objects.requireNonNull(size, "size");
        Objects.requireNonNull(margins, "margins");
        if (margins.left() + margins.right() >= size.width()
                || margins.top() + margins.bottom() >= size.height()) {
            throw new IllegalArgumentException("page margins must leave a positive content area");
        }
    }

    public double contentWidth() {
        return size.width() - (margins.left() + margins.right());
    }

    public double contentHeight() {
        return size.height() - (margins.top() + margins.bottom());
    }

    /** Task-confined builder. Dimensions use points unless the type specifies another unit.
     * build() validates the complete value and takes an immutable structural snapshot. */
    public static Builder builder() { return new Builder(); }
    /** Starts an independent builder from this value; referenced resources remain borrowed. */
    public Builder toBuilder() { return new Builder().size(size()).margins(margins()); }

    /** Mutable, task-confined construction state; built values do not retain this builder. */
    public static final class Builder {
        private PageSize size = PageSize.A4;
        private PageMargins margins = PageMargins.uniform(54);

        private Builder() { }
        public Builder size(PageSize value) { size = java.util.Objects.requireNonNull(value, "size"); return this; }
        public Builder margins(PageMargins value) { margins = java.util.Objects.requireNonNull(value, "margins"); return this; }
        public Builder margins(double points) { return margins(PageMargins.uniform(points)); }
        public Builder size(double width, double height) { return size(new PageSize(width, height)); }
        public Builder margins(double top, double right, double bottom, double left) { return margins(new PageMargins(top, right, bottom, left)); }
        public PageSettings build() { return new PageSettings(size, margins); }
    }
}
