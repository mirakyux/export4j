// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.Objects;
import java.util.function.Consumer;

/** Immutable model-only repeating header/footer declarations; no font handles, components or backend state.
 * All lengths use points. No regions is the compatibility default. Owns no resources and is thread-safe.
 */
public record PageRegions(PageRegion header, PageRegion footer) {
    public static final PageRegions EMPTY = new PageRegions(PageRegion.EMPTY, PageRegion.EMPTY);
    public PageRegions { Objects.requireNonNull(header, "header"); Objects.requireNonNull(footer, "footer"); }
    public static Builder builder() { return new Builder(); }
    public Builder toBuilder() { return new Builder().header(header).footer(footer); }

    /** Task-confined builder; callbacks are synchronous and never retained. */
    public static final class Builder {
        private PageRegion header = PageRegion.EMPTY;
        private PageRegion footer = PageRegion.EMPTY;
        private Builder() { }
        public Builder header(PageRegion value) { header = Objects.requireNonNull(value, "header"); return this; }
        public Builder footer(PageRegion value) { footer = Objects.requireNonNull(value, "footer"); return this; }
        public Builder header(Consumer<PageRegion.Builder> configure) {
            var value = header.toBuilder(); Objects.requireNonNull(configure, "configure").accept(value); return header(value.build());
        }
        public Builder footer(Consumer<PageRegion.Builder> configure) {
            var value = footer.toBuilder(); Objects.requireNonNull(configure, "configure").accept(value); return footer(value.build());
        }
        public PageRegions build() { return new PageRegions(header, footer); }
    }
}
