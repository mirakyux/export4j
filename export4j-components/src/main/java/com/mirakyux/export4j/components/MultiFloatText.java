// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import com.mirakyux.export4j.graphics.AffineTransform;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.SemanticPlacement;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.ComponentSemantics;
import com.mirakyux.export4j.layout.core.PageableComponent;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.text.TextDiagnostic;

/** Immutable rectangular edge floats around one explicitly shaped text flow, in points.
 * Floats appear once on the first fragment, at offsets from that fragment's top.
 * Continued fragments use the full width. Children must fit their declared boxes.
 * No component resources are owned; thread safety also depends on the supplied children.
 */
public record MultiFloatText(OpenTypeText text, List<FloatBox> floats) implements PageableComponent {
    public enum Side { LEFT, RIGHT }

    /** An edge-aligned box; gap is horizontal clearance toward the text, top is nonnegative.
     * Actual boxes may touch but must not overlap. A child is clipped to its box after fitting.
     */
    public record FloatBox(Component content, double width, double height, double top, double gap, Side side) {
        /** Task-confined builder; build validates and snapshots the complete value. */
        public static Builder builder() { return new Builder(); }
        public static final class Builder {
            private Component content = null;
            private double width = 0;
            private double height = 0;
            private double top = 0;
            private double gap = 6;
            private Side side = Side.LEFT;
            private Builder() { }
            public Builder content(Component value) { content = java.util.Objects.requireNonNull(value, "content"); return this; }
            public Builder width(double value) { width = value; return this; }
            public Builder height(double value) { height = value; return this; }
            public Builder top(double value) { top = value; return this; }
            public Builder gap(double value) { gap = value; return this; }
            public Builder side(Side value) { side = java.util.Objects.requireNonNull(value, "side"); return this; }

            public FloatBox build() { return new FloatBox(content, width, height, top, gap, side); }
        }

        public FloatBox {
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(side, "side");
            if (!Double.isFinite(width) || width <= 0 || !Double.isFinite(height) || height <= 0
                    || !Double.isFinite(top) || top < 0 || !Double.isFinite(gap) || gap < 0
                    || !Double.isFinite(top + height) || !Double.isFinite(width + gap)) {
                throw new IllegalArgumentException("invalid float geometry");
            }
        }

        private double bottom() { return top + height; }
        private double left(double viewportWidth) { return side == Side.LEFT ? 0 : viewportWidth - width; }
    }

    public MultiFloatText {
        Objects.requireNonNull(text, "text");
        floats = List.copyOf(floats);
        if (floats.size() > 64) { throw new IllegalArgumentException("at most 64 float boxes are supported"); }
    }

    @Override
    public List<ComponentLayout> layoutParts(ComponentContext context) { return layoutParts(context, context.height()); }

    @Override
    public List<ComponentLayout> layoutParts(ComponentContext context, double firstPageHeight) {
        ComponentSupport.firstPageHeight(firstPageHeight, context);
        double extent = validate(context);
        var resolvedText = text.resolved(context);
        double step = resolvedText.style().lineHeight();
        boolean hasBody = !text.text().isEmpty() || floats.isEmpty();
        int capacity = (int) Math.floor(Math.nextUp(context.height() / step));
        if (hasBody && capacity < 1) { throw new IllegalArgumentException("float text line exceeds viewport"); }
        double firstHeight = firstPageHeight < extent || hasBody && firstPageHeight < step ? context.height() : firstPageHeight;
        var slots = new LineSlots(context.width(), firstHeight, step);
        var measured = hasBody ? resolvedText.measure(context, index -> slots.get(index).width(), index -> slots.get(index).x())
                : new OpenTypeText.Measured(List.of(), List.of());
        if (measured.lines().size() > 100_000) { throw new IllegalArgumentException("float text line budget exceeded"); }
        int firstCount = 0;
        while (firstCount < measured.lines().size() && slots.get(firstCount).firstPage()) { firstCount++; }

        // The floats themselves make progress even if every first-page line band is blocked.
        var result = new ArrayList<ComponentLayout>();
        int first = 0;
        do {
            ExecutionContext.checkpoint();
            boolean initial = result.isEmpty();
            int count = initial ? firstCount : Math.min(capacity, measured.lines().size() - first);
            double height = initial ? Math.max(extent, count == 0 ? 0 : slots.get(count - 1).y() + step) : count * step;
            var builder = new DisplayListBuilder(new PageSize(context.width(), height));
            int childLines = 0;
            List<TextDiagnostic> diagnostics = new ArrayList<>();
            List<SemanticPlacement> placements = new ArrayList<>();
            if (initial) {
                for (var box : floats) {
                    ExecutionContext.checkpoint();
                    var viewport = context.viewport(box.width(), box.height());
                    var child = box.content().layout(viewport);
                    ComponentSupport.fits(child.width(), child.height(), viewport);
                    builder.save().transform(AffineTransform.translation(box.left(context.width()), box.top()))
                            .clip(new Rect(0, 0, box.width(), box.height()));
                    ComponentSemantics.append(builder, placements, child);
                    builder.restore();
                    childLines = Math.addExact(childLines, child.textLines());
                    diagnostics.addAll(child.diagnostics());
                }
            }
            for (int line = 0; line < count; line++) {
                ExecutionContext.checkpoint();
                double y = initial ? slots.get(line).y() : line * step;
                builder.save().transform(AffineTransform.translation(0, y))
                        .appendCommands(List.of(measured.lines().get(first + line).command())).restore();
            }
            if (count > 0) {
                int start = measured.lines().get(first).sourceStart();
                int end = measured.lines().get(first + count - 1).sourceEnd();
                diagnostics.addAll(measured.diagnostics().stream().filter(d -> d.sourceStart() >= start && d.sourceStart() < end).toList());
            }
            result.add(new ComponentLayout(context.width(), height, Math.addExact(count, childLines), builder.build().commands(), diagnostics, placements));
            first += count;
        } while (first < measured.lines().size());
        return List.copyOf(result);
    }

    private double validate(ComponentContext context) {
        double extent = 0;
        for (int index = 0; index < floats.size(); index++) {
            var box = floats.get(index);
            ComponentSupport.fits(box.width(), box.bottom(), context);
            extent = Math.max(extent, box.bottom());
            for (int previous = 0; previous < index; previous++) {
                var other = floats.get(previous);
                if (box.top() < other.bottom() && other.top() < box.bottom()
                        && box.left(context.width()) < other.left(context.width()) + other.width()
                        && other.left(context.width()) < box.left(context.width()) + box.width()) {
                    throw new IllegalArgumentException("float boxes overlap at indices " + previous + " and " + index);
                }
            }
        }
        return extent;
    }

    private record LineSlot(double x, double y, double width, boolean firstPage) { }

    /** Lazy fixed-height line bands; blocked bands jump to a float-bottom event without scanning empty rows. */
    private final class LineSlots {
        private final double width;
        private final double height;
        private final double step;
        private final List<LineSlot> first = new ArrayList<>();
        private double nextY;
        private boolean complete;

        private LineSlots(double width, double height, double step) {
            this.width = width;
            this.height = height;
            this.step = step;
        }

        private LineSlot get(int index) {
            while (!complete && first.size() <= index) {
                ExecutionContext.checkpoint();
                if (nextY + step > Math.nextUp(height)) { complete = true; break; }
                double left = 0;
                double right = width;
                double bottom = Double.POSITIVE_INFINITY;
                for (var box : floats) {
                    if (nextY < box.bottom() && box.top() < nextY + step) {
                        if (box.side() == Side.LEFT) { left = Math.max(left, box.width() + box.gap()); }
                        else { right = Math.min(right, width - box.width() - box.gap()); }
                        bottom = Math.min(bottom, box.bottom());
                    }
                }
                if (right > left) {
                    first.add(new LineSlot(left, nextY, right - left, true));
                    double after = nextY + step;
                    if (after <= nextY) { throw new IllegalArgumentException("float line grid cannot advance"); }
                    nextY = after;
                } else {
                    double after = Math.ceil(bottom / step) * step;
                    if (after <= nextY) { throw new IllegalArgumentException("blocked float line grid cannot advance"); }
                    nextY = after;
                }
            }
            return index < first.size() ? first.get(index) : new LineSlot(0, 0, width, false);
        }
    }

    /** Task-confined builder. Dimensions use points unless the type specifies another unit.
     * build() validates the complete value and takes an immutable structural snapshot. */
    public static Builder builder() { return new Builder(); }
    public static Builder builder(OpenTypeText text) { return new Builder().text(text); }
    /** Starts an independent builder from this value; referenced resources remain borrowed. */
    public Builder toBuilder() { return new Builder().text(text()).floats(floats()); }

    /** Mutable, task-confined construction state; built values do not retain this builder. */
    public static final class Builder {
        private OpenTypeText text = null;
        private List<FloatBox> floats = new java.util.ArrayList<>();

        private Builder() { }
        public Builder text(OpenTypeText value) { text = java.util.Objects.requireNonNull(value, "text"); return this; }
        public Builder floats(List<FloatBox> value) { floats = new java.util.ArrayList<>(java.util.List.copyOf(value)); return this; }
        public Builder floating(java.util.function.Consumer<FloatBox.Builder> configure) {
            var value = FloatBox.builder(); configure.accept(value); floats.add(value.build()); return this;
        }

        public MultiFloatText build() { return new MultiFloatText(text, floats); }
    }
}
