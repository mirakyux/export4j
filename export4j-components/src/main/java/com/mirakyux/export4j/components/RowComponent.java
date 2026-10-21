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
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.text.TextDiagnostic;

/** Equal-width horizontal composition with padding and vertical alignment. Rows stay together. */
public record RowComponent(List<Component> children, PageMargins padding, double gap, FlowColumn.Alignment alignment) implements Component {
    public RowComponent {
        children = List.copyOf(children);
        java.util.Objects.requireNonNull(padding, "padding");
        java.util.Objects.requireNonNull(alignment, "alignment");
        if (children.isEmpty() || children.size() > 10_000 || !Double.isFinite(gap) || gap < 0) {
            throw new IllegalArgumentException("invalid row");
        }
    }

    @Override
    public ComponentLayout layout(ComponentContext context) {
        double width = (context.width() - padding.left() - padding.right() - gap * (children.size() - 1)) / children.size();
        var childContext = context.viewport(width, context.height() - padding.top() - padding.bottom());
        var layouts = children.stream().map(child -> child.layout(childContext)).toList();
        double contentHeight = layouts.stream().mapToDouble(ComponentLayout::height).max().orElseThrow();
        double height = padding.top() + contentHeight + padding.bottom();
        ComponentSupport.fits(context.width(), height, context);
        var builder = new DisplayListBuilder(new PageSize(context.width(), height));
        int lines = 0;
        List<TextDiagnostic> diagnostics = new ArrayList<>();
        List<SemanticPlacement> placements = new ArrayList<>();
        for (int index = 0; index < layouts.size(); index++) {
            var child = layouts.get(index);
            ComponentSupport.fits(child.width(), child.height(), childContext);
            double free = contentHeight - child.height();
            double y = padding.top() + switch (alignment) { case START -> 0; case CENTER -> free / 2; case END -> free; };
            builder.save().transform(AffineTransform.translation(padding.left() + index * (width + gap), y));
            ComponentSemantics.append(builder, placements, child);
            builder.restore();
            lines = Math.addExact(lines, child.textLines());
            diagnostics.addAll(child.diagnostics());
        }
        return new ComponentLayout(context.width(), height, lines, builder.build().commands(), diagnostics, placements);
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
        private FlowColumn.Alignment alignment = FlowColumn.Alignment.START;

        private Builder() { }
        public Builder children(List<Component> value) { children = new java.util.ArrayList<>(java.util.List.copyOf(value)); return this; }
        public Builder padding(PageMargins value) { padding = java.util.Objects.requireNonNull(value, "padding"); return this; }
        public Builder gap(double value) { gap = value; return this; }
        public Builder alignment(FlowColumn.Alignment value) { alignment = java.util.Objects.requireNonNull(value, "alignment"); return this; }
        /** Appends one child in source order. */
        public Builder add(Component child) { children.add(java.util.Objects.requireNonNull(child, "child")); return this; }
        public Builder padding(double points) { return padding(PageMargins.uniform(points)); }
        public Builder text(String text) { return add(TextComponent.builder(text).build()); }
        public Builder text(String text, java.util.function.Consumer<TextComponent.Builder> configure) { var child = TextComponent.builder(text); configure.accept(child); return add(child.build()); }
        public Builder table(java.util.function.Consumer<GridTable.Builder> configure) { var child = GridTable.builder(); configure.accept(child); return add(child.build()); }
        public Builder column(java.util.function.Consumer<FlowColumn.Builder> configure) { var child = FlowColumn.builder(); configure.accept(child); return add(child.build()); }
        public Builder row(java.util.function.Consumer<RowComponent.Builder> configure) { var child = RowComponent.builder(); configure.accept(child); return add(child.build()); }
        public Builder space(double points) { return add(new Spacer(points)); }
        public RowComponent build() { return new RowComponent(children, padding, gap, alignment); }
    }
}
