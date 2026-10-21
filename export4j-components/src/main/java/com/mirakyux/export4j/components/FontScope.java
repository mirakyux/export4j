// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.List;
import java.util.Objects;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.FlowRegions;
import com.mirakyux.export4j.layout.core.PageableComponent;
import com.mirakyux.export4j.layout.core.RegionFlow;

/** Applies an explicit font group to a component and all of its nested flow, preserving missing-glyph policy. */
public record FontScope(Component content, FontGroup fonts) implements RegionFlow {
    public FontScope { Objects.requireNonNull(content, "content"); Objects.requireNonNull(fonts, "fonts"); }
    @Override public List<ComponentLayout> layoutParts(ComponentContext context) { return layoutParts(context, context.height()); }
    @Override public List<ComponentLayout> layoutParts(ComponentContext context, double firstPageHeight) {
        ComponentSupport.firstPageHeight(firstPageHeight, context);
        var scoped = context.withFonts(fonts);
        return content instanceof PageableComponent pageable ? pageable.layoutParts(scoped, firstPageHeight) : List.of(content.layout(scoped));
    }

    /** Preserves every initial short region while applying the explicit font policy to the whole flow.
     * Legacy pageable children retain atomic fragments; ordinary children still move as one block.
     */
    @Override public List<RegionFlow.Fragment> layoutRegions(ComponentContext context, FlowRegions regions) {
        var scoped = context.withFonts(fonts);
        return content instanceof RegionFlow flow ? flow.layoutRegions(scoped, regions)
                : ComponentSupport.atomicRegions(content, scoped, regions);
    }

    /** Task-confined builder. Dimensions use points unless the type specifies another unit.
     * build() validates the complete value and takes an immutable structural snapshot. */
    public static Builder builder() { return new Builder(); }
    public static Builder builder(Component content) { return new Builder().content(content); }
    /** Starts an independent builder from this value; referenced resources remain borrowed. */
    public Builder toBuilder() { return new Builder().content(content()).fonts(fonts()); }

    /** Mutable, task-confined construction state; built values do not retain this builder. */
    public static final class Builder {
        private Component content = null;
        private FontGroup fonts = null;

        private Builder() { }
        public Builder content(Component value) { content = java.util.Objects.requireNonNull(value, "content"); return this; }
        public Builder fonts(FontGroup value) { fonts = java.util.Objects.requireNonNull(value, "fonts"); return this; }

        public FontScope build() { return new FontScope(content, fonts); }
    }
}
