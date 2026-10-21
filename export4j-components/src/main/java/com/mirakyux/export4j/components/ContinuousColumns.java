// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import com.mirakyux.export4j.graphics.AffineTransform;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.SemanticPlacement;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.ComponentSemantics;
import com.mirakyux.export4j.layout.core.FlowRegions;
import com.mirakyux.export4j.layout.core.PageableComponent;
import com.mirakyux.export4j.layout.core.RegionFlow;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.text.TextDiagnostic;

/** Sequential columns that start in the current page's remaining height, then use full-height pages.
 * Lengths are in points; columns fill top-to-bottom, then left-to-right. The last fragment reserves its
 * actual maximum column height, without balancing. Owns no resources; thread safety depends on content.
 */
public record ContinuousColumns(RegionFlow content, int columns, double gap) implements PageableComponent {
    public ContinuousColumns {
        Objects.requireNonNull(content, "content");
        if (columns < 2 || columns > 16 || !Double.isFinite(gap) || gap < 0) {
            throw new IllegalArgumentException("invalid columns or gap");
        }
    }

    @Override
    public List<ComponentLayout> layoutParts(ComponentContext context) { return layoutParts(context, context.height()); }

    @Override
    public List<ComponentLayout> layoutParts(ComponentContext context, double firstPageHeight) {
        ComponentSupport.firstPageHeight(firstPageHeight, context);
        ExecutionContext.checkpoint();
        double width = (context.width() - gap * (columns - 1)) / columns;
        if (!Double.isFinite(width) || width <= 0) { throw new IllegalArgumentException("column gaps consume the viewport width"); }
        double firstHeight = Math.min(firstPageHeight, context.height());
        var regions = new FlowRegions(firstHeight < context.height() ? Collections.nCopies(columns, firstHeight) : List.of(), context.height());
        var childContext = context.viewport(width, context.height());
        var fragments = ComponentSupport.regionFragments(content.layoutRegions(childContext, regions), childContext, regions);
        List<ComponentLayout> result = new ArrayList<>();
        List<RegionFlow.Fragment> page = new ArrayList<>();
        int pageIndex = fragments.getFirst().regionIndex() / columns;
        for (var fragment : fragments) {
            int nextPage = fragment.regionIndex() / columns;
            if (nextPage != pageIndex) {
                result.add(pack(context, regions, width, pageIndex, page, false));
                page.clear();
                pageIndex = nextPage;
            }
            page.add(fragment);
        }
        result.add(pack(context, regions, width, pageIndex, page, true));
        // The existing flow SPI moves the first fragment based on its height. A skipped first page must
        // therefore represent content that actually exceeds the remaining height, never a hidden page break.
        if (fragments.getFirst().regionIndex() >= columns && result.getFirst().height() <= Math.nextUp(firstHeight)) {
            throw new IllegalArgumentException("skipped first-page regions must exceed the remaining height");
        }
        return List.copyOf(result);
    }

    private ComponentLayout pack(ComponentContext context, FlowRegions regions, double width, int pageIndex,
            List<RegionFlow.Fragment> fragments, boolean last) {
        ExecutionContext.checkpoint();
        double capacity = regions.height(pageIndex * columns);
        double height = last ? fragments.stream().mapToDouble(f -> f.layout().height()).max().orElseThrow() : capacity;
        var builder = new DisplayListBuilder(new PageSize(context.width(), height));
        int lines = 0;
        List<TextDiagnostic> diagnostics = new ArrayList<>();
        List<SemanticPlacement> placements = new ArrayList<>();
        for (var fragment : fragments) {
            ExecutionContext.checkpoint();
            var layout = fragment.layout();
            int column = fragment.regionIndex() % columns;
            builder.save().transform(AffineTransform.translation(column * (width + gap), 0))
                    .clip(new Rect(0, 0, width, height));
            ComponentSemantics.append(builder, placements, layout);
            builder.restore();
            lines = Math.addExact(lines, layout.textLines());
            diagnostics.addAll(layout.diagnostics());
        }
        return new ComponentLayout(context.width(), height, lines, builder.build().commands(), diagnostics, placements);
    }

    /** Task-confined builder. Dimensions use points unless the type specifies another unit.
     * build() validates the complete value and takes an immutable structural snapshot. */
    public static Builder builder() { return new Builder(); }
    public static Builder builder(RegionFlow content) { return new Builder().content(content); }
    /** Starts an independent builder from this value; referenced resources remain borrowed. */
    public Builder toBuilder() { return new Builder().content(content()).columns(columns()).gap(gap()); }

    /** Mutable, task-confined construction state; built values do not retain this builder. */
    public static final class Builder {
        private RegionFlow content = null;
        private int columns = 2;
        private double gap = 12;

        private Builder() { }
        public Builder content(RegionFlow value) { content = java.util.Objects.requireNonNull(value, "content"); return this; }
        public Builder columns(int value) { columns = value; return this; }
        public Builder gap(double value) { gap = value; return this; }

        public ContinuousColumns build() { return new ContinuousColumns(content, columns, gap); }
    }
}
