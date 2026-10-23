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
import com.mirakyux.export4j.layout.core.WidthAwareRegionFlow;
import com.mirakyux.export4j.layout.core.DocumentLayoutException;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.text.TextDiagnostic;

/** Sequential columns that start in the current page's remaining height, then use full-height pages.
 * Lengths are in points; columns fill top-to-bottom, then left-to-right. The last fragment reserves its
 * actual maximum column height. Optional balancing uses bounded layout trials. Owns no resources;
 * thread safety depends on content.
 */
public record ContinuousColumns(RegionFlow content, int columns, double gap, boolean balanced,
        List<Double> columnWeights) implements PageableComponent {
    /** Compatibility constructor retaining sequential, unbalanced columns. */
    public ContinuousColumns(RegionFlow content, int columns, double gap) { this(content, columns, gap, false, List.of()); }
    /** Compatibility constructor retaining equal widths. */
    public ContinuousColumns(RegionFlow content, int columns, double gap, boolean balanced) { this(content, columns, gap, balanced, List.of()); }
    public ContinuousColumns {
        Objects.requireNonNull(content, "content");
        columnWeights = List.copyOf(columnWeights);
        if (columns < 2 || columns > 16 || !Double.isFinite(gap) || gap < 0) {
            throw new IllegalArgumentException("invalid columns or gap");
        }
        if (!columnWeights.isEmpty() && columnWeights.size() != columns) {
            throw new IllegalArgumentException("column weights must match column count");
        }
        for (double weight : columnWeights) {
            if (!Double.isFinite(weight) || weight <= 0) { throw new IllegalArgumentException("column weights must be positive and finite"); }
        }
    }

    @Override
    public List<ComponentLayout> layoutParts(ComponentContext context) { return layoutParts(context, context.height()); }

    @Override
    public List<ComponentLayout> layoutParts(ComponentContext context, double firstPageHeight) {
        ComponentSupport.firstPageHeight(firstPageHeight, context);
        ExecutionContext.checkpoint();
        var widths = widths(context.width());
        double width = widths.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
        double firstHeight = Math.min(firstPageHeight, context.height());
        var regions = new FlowRegions(firstHeight < context.height() ? Collections.nCopies(columns, firstHeight) : List.of(), context.height());
        var childContext = context.viewport(width, context.height());
        var fragments = validate(flow(childContext, regions, widths), childContext, regions, widths);
        if (balanced) {
            int firstPage = fragments.getFirst().regionIndex() / columns;
            int lastPage = fragments.getLast().regionIndex() / columns;
            double low = 0;
            double high = context.height();
            for (int attempt = 0; attempt < 24; attempt++) {
                ExecutionContext.checkpoint();
                double candidate = (low + high) / 2;
                double initial = Math.min(firstHeight, candidate);
                var trialRegions = new FlowRegions(initial < candidate ? Collections.nCopies(columns, initial) : List.of(), candidate);
                List<RegionFlow.Fragment> trial;
                try {
                    trial = flow(childContext, trialRegions, widths);
                } catch (DocumentLayoutException failure) {
                    if (failure.code() != DocumentLayoutException.Code.CONTENT_TOO_TALL) { throw failure; }
                    low = candidate; continue;
                } catch (IllegalArgumentException tooShort) {
                    low = candidate; continue;
                }
                trial = validate(trial, childContext, trialRegions, widths);
                if (trial.getFirst().regionIndex() / columns == firstPage && trial.getLast().regionIndex() / columns <= lastPage) {
                    high = candidate; fragments = trial;
                } else { low = candidate; }
            }
        }
        List<ComponentLayout> result = new ArrayList<>();
        List<RegionFlow.Fragment> page = new ArrayList<>();
        int pageIndex = fragments.getFirst().regionIndex() / columns;
        for (var fragment : fragments) {
            int nextPage = fragment.regionIndex() / columns;
            if (nextPage != pageIndex) {
                result.add(pack(context, regions, widths, pageIndex, page, false));
                page.clear();
                pageIndex = nextPage;
            }
            page.add(fragment);
        }
        result.add(pack(context, regions, widths, pageIndex, page, true));
        // The existing flow SPI moves the first fragment based on its height. A skipped first page must
        // therefore represent content that actually exceeds the remaining height, never a hidden page break.
        if (fragments.getFirst().regionIndex() >= columns && result.getFirst().height() <= Math.nextUp(firstHeight)) {
            throw new IllegalArgumentException("skipped first-page regions must exceed the remaining height");
        }
        return List.copyOf(result);
    }

    private List<Double> widths(double viewport) {
        double available = viewport - gap * (columns - 1);
        if (!Double.isFinite(available) || available <= 0) { throw new IllegalArgumentException("column gaps consume the viewport width"); }
        if (columnWeights.isEmpty()) { return Collections.nCopies(columns, available / columns); }
        double maximum = columnWeights.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
        double total = columnWeights.stream().mapToDouble(value -> value / maximum).sum();
        return columnWeights.stream().map(value -> {
            double result = available * (value / maximum / total);
            if (result <= 0 || !Double.isFinite(result)) { throw new IllegalArgumentException("column weight produces an invalid width"); }
            return result;
        }).toList();
    }

    private List<RegionFlow.Fragment> flow(ComponentContext context, FlowRegions regions, List<Double> widths) {
        if (columnWeights.isEmpty()) { return content.layoutRegions(context, regions); }
        if (content instanceof WidthAwareRegionFlow variable) { return variable.layoutVariableRegions(context, regions, widths); }
        throw new IllegalArgumentException("content does not support variable region widths");
    }

    private List<RegionFlow.Fragment> validate(List<RegionFlow.Fragment> values, ComponentContext context,
            FlowRegions regions, List<Double> widths) {
        var fragments = ComponentSupport.regionFragments(values, context, regions);
        for (var fragment : fragments) {
            if (fragment.layout().width() > Math.nextUp(widths.get(fragment.regionIndex() % columns))) {
                throw new IllegalArgumentException("fragment exceeds its assigned column width");
            }
        }
        return fragments;
    }

    private ComponentLayout pack(ComponentContext context, FlowRegions regions, List<Double> widths, int pageIndex,
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
            double x = column * gap;
            for (int preceding = 0; preceding < column; preceding++) { x += widths.get(preceding); }
            builder.save().transform(AffineTransform.translation(x, 0))
                    .clip(new Rect(0, 0, widths.get(column), height));
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
    public Builder toBuilder() { return new Builder().content(content()).columns(columns()).gap(gap()).balanced(balanced()).columnWeights(columnWeights()); }

    /** Mutable, task-confined construction state; built values do not retain this builder. */
    public static final class Builder {
        private RegionFlow content = null;
        private int columns = 2;
        private double gap = 12;
        private boolean balanced;
        private List<Double> columnWeights = List.of();

        private Builder() { }
        public Builder content(RegionFlow value) { content = java.util.Objects.requireNonNull(value, "content"); return this; }
        public Builder columns(int value) { columns = value; columnWeights = List.of(); return this; }
        /** Relative positive column widths, repeated on each page; snapshots the list immediately.
         * An empty list restores equal widths. Nonempty weights set the column count and require width-aware content.
         */
        public Builder columnWeights(List<Double> values) {
            columnWeights = List.copyOf(values);
            if (!columnWeights.isEmpty()) { columns = columnWeights.size(); }
            return this;
        }
        public Builder gap(double value) { gap = value; return this; }
        /** Minimizes a common column height with at most 24 deterministic layout trials.
         * Does not increase physical pages or move content into a previously skipped first page.
         */
        public Builder balanced(boolean value) { balanced = value; return this; }

        public ContinuousColumns build() { return new ContinuousColumns(content, columns, gap, balanced, columnWeights); }
    }
}
