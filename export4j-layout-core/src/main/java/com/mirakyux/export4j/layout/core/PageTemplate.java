// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import java.util.List;
import java.util.Objects;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;

/** Repeating page composition: background, body, header/footer, foreground, in that order. */
public record PageTemplate(PageBand header, PageBand footer, List<Placement> background, List<Placement> foreground) {
    public static final PageTemplate EMPTY = new PageTemplate(PageBand.EMPTY, PageBand.EMPTY, List.of(), List.of());

    public PageTemplate {
        Objects.requireNonNull(header, "header");
        Objects.requireNonNull(footer, "footer");
        background = List.copyOf(background);
        foreground = List.copyOf(foreground);
    }

    /** Creates the body region before pagination; no clipping or implicit resizing when bands exhaust the page. */
    public PageSettings bodySettings(PageSettings settings) {
        var margins = settings.margins();
        return new PageSettings(settings.size(), new PageMargins(margins.top() + header.height() + header.gap(),
                margins.right(), margins.bottom() + footer.height() + footer.gap(), margins.left()));
    }

    /** Task-confined builder. Dimensions use points unless the type specifies another unit.
     * build() validates the complete value and takes an immutable structural snapshot. */
    public static Builder builder() { return new Builder(); }
    /** Starts an independent builder from this value; referenced resources remain borrowed. */
    public Builder toBuilder() { return new Builder().header(header()).footer(footer()).background(background()).foreground(foreground()); }

    /** Mutable, task-confined construction state; built values do not retain this builder. */
    public static final class Builder {
        private PageBand header = PageBand.EMPTY;
        private PageBand footer = PageBand.EMPTY;
        private List<Placement> background = new java.util.ArrayList<>();
        private List<Placement> foreground = new java.util.ArrayList<>();

        private Builder() { }
        public Builder header(PageBand value) { header = java.util.Objects.requireNonNull(value, "header"); return this; }
        public Builder footer(PageBand value) { footer = java.util.Objects.requireNonNull(value, "footer"); return this; }
        public Builder background(List<Placement> value) { background = new java.util.ArrayList<>(java.util.List.copyOf(value)); return this; }
        public Builder foreground(List<Placement> value) { foreground = new java.util.ArrayList<>(java.util.List.copyOf(value)); return this; }
        public Builder header(java.util.function.Consumer<PageBand.Builder> configure) { var value = PageBand.builder(); configure.accept(value); return header(value.build()); }
        public Builder footer(java.util.function.Consumer<PageBand.Builder> configure) { var value = PageBand.builder(); configure.accept(value); return footer(value.build()); }
        public Builder background(Placement placement) { background.add(java.util.Objects.requireNonNull(placement, "placement")); return this; }
        public Builder foreground(Placement placement) { foreground.add(java.util.Objects.requireNonNull(placement, "placement")); return this; }
        public PageTemplate build() { return new PageTemplate(header, footer, background, foreground); }
    }
}
