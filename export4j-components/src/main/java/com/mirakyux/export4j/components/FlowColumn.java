// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.ArrayList;
import java.util.List;
import com.mirakyux.export4j.graphics.AffineTransform;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.SemanticPlacement;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.ComponentSemantics;
import com.mirakyux.export4j.layout.core.FlowRegions;
import com.mirakyux.export4j.layout.core.PageableComponent;
import com.mirakyux.export4j.layout.core.RegionFlow;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.text.TextDiagnostic;

/** Vertical mixed region flow. Padding repeats per occupied region; gaps apply within a region only.
 * Region-aware children consume the remaining and following regions once. Other pageable children
 * retain atomic measured fragments. Lengths are points; owns no resources, child thread safety applies.
 */
public record FlowColumn(List<Component> children, PageMargins padding, double gap, Alignment alignment) implements RegionFlow {
    public enum Alignment { START, CENTER, END }

    public FlowColumn {
        children = List.copyOf(children);
        java.util.Objects.requireNonNull(padding, "padding");
        java.util.Objects.requireNonNull(alignment, "alignment");
        if (children.isEmpty() || children.size() > 100_000 || !Double.isFinite(gap) || gap < 0) {
            throw new IllegalArgumentException("invalid column");
        }
    }

    @Override
    public List<ComponentLayout> layoutParts(ComponentContext context) {
        return RegionFlow.super.layoutParts(context);
    }

    @Override
    public List<ComponentLayout> layoutParts(ComponentContext context, double firstPageHeight) {
        return RegionFlow.super.layoutParts(context, firstPageHeight);
    }

    @Override
    public List<RegionFlow.Fragment> layoutRegions(ComponentContext context, FlowRegions regions) {
        ComponentSupport.fits(context.width(), regions.continuationHeight(), context);
        double width = context.width() - padding.left() - padding.right();
        double height = regions.continuationHeight() - padding.top() - padding.bottom();
        var childContext = context.viewport(width, height);
        List<RegionFlow.Fragment> parts = new ArrayList<>();
        List<ComponentLayout> current = new ArrayList<>();
        double used = 0;
        int region = 0;
        int placed = 0;
        for (var child : children) {
            ExecutionContext.checkpoint();
            double remaining = Math.max(0, innerHeight(regions.height(region)) - used - (current.isEmpty() ? 0 : gap));
            if (child instanceof RegionFlow flow) {
                List<Double> initial = new ArrayList<>();
                initial.add(remaining);
                for (int next = region + 1; next < regions.initialHeights().size(); next++) {
                    initial.add(innerHeight(regions.height(next)));
                }
                var childRegions = new FlowRegions(initial, height);
                var measured = ComponentSupport.regionFragments(flow.layoutRegions(childContext, childRegions), childContext, childRegions);
                int start = region;
                for (var fragment : measured) {
                    ExecutionContext.checkpoint();
                    if (++placed > 100_000) { throw new IllegalArgumentException("nested fragment budget exceeded"); }
                    int assigned = Math.addExact(start, fragment.regionIndex());
                    if (assigned != region) {
                        if (!current.isEmpty()) { parts.add(new RegionFlow.Fragment(region, pack(context.width(), current))); }
                        current.clear(); used = 0; region = assigned;
                    }
                    var part = fragment.layout();
                    used += (current.isEmpty() ? 0 : gap) + part.height();
                    current.add(part);
                }
            } else {
                // A legacy pageable child supplies atomic fragments; only RegionFlow can adapt every short region.
                var measured = List.copyOf(child instanceof PageableComponent pageable
                        ? pageable.layoutParts(childContext, remaining) : List.of(child.layout(childContext)));
                if (measured.isEmpty() || measured.size() > 100_000) { throw new IllegalArgumentException("invalid child fragment count"); }
                for (int index = 0; index < measured.size(); index++) {
                    ExecutionContext.checkpoint();
                    if (++placed > 100_000) { throw new IllegalArgumentException("nested fragment budget exceeded"); }
                    var part = measured.get(index);
                    ComponentSupport.fits(part.width(), part.height(), childContext);
                    if (index > 0) {
                        parts.add(new RegionFlow.Fragment(region, pack(context.width(), current)));
                        current.clear(); used = 0; region++;
                    }
                    while (used + (current.isEmpty() ? 0 : gap) + part.height() > Math.nextUp(innerHeight(regions.height(region)))) {
                        if (!current.isEmpty()) { parts.add(new RegionFlow.Fragment(region, pack(context.width(), current))); }
                        else if (region >= regions.initialHeights().size()) {
                            throw new IllegalArgumentException("atomic child exceeds continuation region");
                        }
                        current.clear(); used = 0; region++;
                    }
                    used += (current.isEmpty() ? 0 : gap) + part.height();
                    current.add(part);
                }
            }
        }
        parts.add(new RegionFlow.Fragment(region, pack(context.width(), current)));
        return ComponentSupport.regionFragments(parts, context, regions);
    }

    private double innerHeight(double outerHeight) {
        return Math.max(0, outerHeight - padding.top() - padding.bottom());
    }

    private ComponentLayout pack(double width, List<ComponentLayout> layouts) {
        double height = padding.top() + padding.bottom() + layouts.stream().mapToDouble(ComponentLayout::height).sum()
                + (layouts.size() - 1) * gap;
        var builder = new DisplayListBuilder(new PageSize(width, height));
        double y = padding.top();
        int lines = 0;
        List<TextDiagnostic> diagnostics = new ArrayList<>();
        List<SemanticPlacement> placements = new ArrayList<>();
        for (var child : layouts) {
            ExecutionContext.checkpoint();
            double free = width - padding.left() - padding.right() - child.width();
            double x = padding.left() + switch (alignment) { case START -> 0; case CENTER -> free / 2; case END -> free; };
            builder.save().transform(AffineTransform.translation(x, y));
            ComponentSemantics.append(builder, placements, child);
            builder.restore();
            y += child.height() + gap;
            lines = Math.addExact(lines, child.textLines());
            diagnostics.addAll(child.diagnostics());
        }
        return new ComponentLayout(width, height, lines, builder.build().commands(), diagnostics, placements);
    }

    /** Task-confined builder. Dimensions use points unless the type specifies another unit.
     * build() validates the complete value and takes an immutable structural snapshot. */
    public static Builder builder() { return new Builder(); }
    /** Starts an independent builder from this value; referenced resources remain borrowed. */
    public Builder toBuilder() { return new Builder().children(children()).padding(padding()).gap(gap()).alignment(alignment()); }

    /** Mutable, task-confined construction state; built values do not retain this builder. */
    public static final class Builder {
        private List<Component> children = new java.util.ArrayList<>();
        private PageMargins padding = PageMargins.uniform(0);
        private double gap = 0;
        private Alignment alignment = Alignment.START;

        private Builder() { }
        public Builder children(List<Component> value) { children = new java.util.ArrayList<>(java.util.List.copyOf(value)); return this; }
        public Builder padding(PageMargins value) { padding = java.util.Objects.requireNonNull(value, "padding"); return this; }
        public Builder gap(double value) { gap = value; return this; }
        public Builder alignment(Alignment value) { alignment = java.util.Objects.requireNonNull(value, "alignment"); return this; }
        /** Appends one child in source order. */
        public Builder add(Component child) { children.add(java.util.Objects.requireNonNull(child, "child")); return this; }
        public Builder padding(double points) { return padding(PageMargins.uniform(points)); }
        /** Appends ordinary text that can continue by complete lines across regions. */
        public Builder text(String text) { return add(FlowText.builder(text).build()); }
        public Builder text(String text, java.util.function.Consumer<FlowText.Builder> configure) { var child = FlowText.builder(text); configure.accept(child); return add(child.build()); }
        /** Appends a fixed text box that moves as one block; never splits. */
        public Builder fixedText(String text) { return add(TextComponent.builder(text).build()); }
        public Builder fixedText(String text, java.util.function.Consumer<TextComponent.Builder> configure) { var child = TextComponent.builder(text); configure.accept(child); return add(child.build()); }
        public Builder formText(String text) { return add(FormText.builder(text).build()); }
        public Builder formText(String text, java.util.function.Consumer<FormText.Builder> configure) { var child = FormText.builder(text); configure.accept(child); return add(child.build()); }
        public Builder choices(java.util.function.Consumer<ChoiceGroup.Builder> configure) { var child = ChoiceGroup.builder(); configure.accept(child); return add(child.build()); }
        public Builder labelValue(String label, String value, java.util.function.Consumer<LabelValue.Builder> configure) { var child = LabelValue.builder(label, value); configure.accept(child); return add(child.build()); }
        public Builder labelValue(String label, String value) { return add(LabelValue.builder(label, value).build()); }
        public Builder fillLine(java.util.function.Consumer<FillLine.Builder> configure) { var child = FillLine.builder(); configure.accept(child); return add(child.build()); }
        public Builder fillLine() { return add(FillLine.builder().build()); }
        public Builder grid(java.util.function.Consumer<GridTable.Builder> configure) { var child = GridTable.builder(); configure.accept(child); return add(child.build()); }
        /** Appends a semantic table that continues across regions, retaining fields, links and cell sources.
         * Complete rows are the default; the table's explicit row-splitting policy consumes complete cell lines.
         */
        public Builder semanticTable(Table table) { return add(new SemanticTable(table)); }
        /** Compatibility name for a component grid; semantic tables belong to Documents.table(). */
        public Builder table(java.util.function.Consumer<GridTable.Builder> configure) { return grid(configure); }
        public Builder column(java.util.function.Consumer<FlowColumn.Builder> configure) { var child = FlowColumn.builder(); configure.accept(child); return add(child.build()); }
        public Builder row(java.util.function.Consumer<RowComponent.Builder> configure) { var child = RowComponent.builder(); configure.accept(child); return add(child.build()); }
        public Builder space(double points) { return add(new Spacer(points)); }
        public FlowColumn build() { return new FlowColumn(children, padding, gap, alignment); }
    }
}
