// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.Objects;
import com.mirakyux.export4j.layout.core.PageBand;
import com.mirakyux.export4j.layout.core.PageTemplate;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;

/** Explicit first/odd/even variants. Odd/even follows displayed section numbering. */
public record SectionTemplate(PageTemplate first, PageTemplate odd, PageTemplate even, PageTemplate firstEven) {
    /** Compatibility constructor: the same first-page override applies to both displayed parities. */
    public SectionTemplate(PageTemplate first, PageTemplate odd, PageTemplate even) { this(first, odd, even, first); }
    public SectionTemplate {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(odd, "odd");
        Objects.requireNonNull(even, "even");
        Objects.requireNonNull(firstEven, "firstEven");
    }

    public static SectionTemplate uniform(PageTemplate template) {
        return new SectionTemplate(template, template, template);
    }

    /** Reserves the largest header/footer extent before flow so variants cannot overlap body content. */
    public PageSettings bodySettings(PageSettings settings) {
        double top = Math.max(extent(firstEven.header()), Math.max(extent(first.header()), Math.max(extent(odd.header()), extent(even.header()))));
        double bottom = Math.max(extent(firstEven.footer()), Math.max(extent(first.footer()), Math.max(extent(odd.footer()), extent(even.footer()))));
        var margins = settings.margins();
        return new PageSettings(settings.size(), new PageMargins(margins.top() + top, margins.right(), margins.bottom() + bottom, margins.left()));
    }

    public PageTemplate page(int localPageIndex, int displayNumber) {
        return localPageIndex == 0 ? displayNumber % 2 == 0 ? firstEven : first : displayNumber % 2 == 0 ? even : odd;
    }

    private static double extent(PageBand band) {
        return band.height() + band.gap();
    }

    /** Task-confined builder. Dimensions use points unless the type specifies another unit.
     * build() validates the complete value and takes an immutable structural snapshot. */
    public static Builder builder() { return new Builder(); }
    /** Starts an independent builder from this value; referenced resources remain borrowed. */
    public Builder toBuilder() { return new Builder().first(first()).odd(odd()).even(even()).firstEven(firstEven()); }

    /** Mutable, task-confined construction state; built values do not retain this builder. */
    public static final class Builder {
        private PageTemplate first = PageTemplate.EMPTY;
        private PageTemplate odd = PageTemplate.EMPTY;
        private PageTemplate even = PageTemplate.EMPTY;
        private PageTemplate firstEven = PageTemplate.EMPTY;

        private Builder() { }
        /** Replaces the first page for both parities. A later firstEven call may specialize even starts. */
        public Builder first(PageTemplate value) { first = Objects.requireNonNull(value, "first"); firstEven = first; return this; }
        /** Explicit first page for an even displayed number; preserves the ordinary first-page variant. */
        public Builder firstEven(PageTemplate value) { firstEven = Objects.requireNonNull(value, "firstEven"); return this; }
        public Builder odd(PageTemplate value) { odd = java.util.Objects.requireNonNull(value, "odd"); return this; }
        public Builder even(PageTemplate value) { even = java.util.Objects.requireNonNull(value, "even"); return this; }

        public SectionTemplate build() { return new SectionTemplate(first, odd, even, firstEven); }
    }
}
