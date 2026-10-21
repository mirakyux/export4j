// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.graphics.core.TextLayoutPainter;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.FlowRegions;
import com.mirakyux.export4j.layout.core.RegionFlow;
import com.mirakyux.export4j.layout.core.TextInheritance;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.PaginationPolicy;
import com.mirakyux.export4j.text.ParagraphLayout;
import com.mirakyux.export4j.text.ParagraphLayouter;
import com.mirakyux.export4j.model.LineBreakPolicy;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextAlignment;
import com.mirakyux.export4j.text.TextLine;

/** Text for nested flow; wraps once and splits complete lines with explicit orphan/widow counts.
 * Builders inherit unset style and color from the layout context; constructors keep explicit values. */
public record FlowText(String text, ParagraphStyle style, RgbColor color, PaginationPolicy pagination,
        TextInheritance inheritance) implements RegionFlow {
    public FlowText(String text, ParagraphStyle style, RgbColor color, PaginationPolicy pagination) {
        this(text, style, color, pagination, TextInheritance.NONE);
    }

    public FlowText {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(color, "color");
        Objects.requireNonNull(pagination, "pagination");
        Objects.requireNonNull(inheritance, "inheritance");
        if (pagination.keepWithNext() || pagination.pageBreakBefore()) {
            throw new IllegalArgumentException("nested text uses line limits; block adjacency belongs to semantic flow");
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
        if (!inheritance.equals(TextInheritance.NONE)) { return resolved(context).layoutRegions(context, regions); }
        ComponentSupport.fits(context.width(), regions.continuationHeight(), context);
        var shaped = new ParagraphLayouter().layout(text, context.fontGroup(), context.width(), style);
        List<RegionFlow.Fragment> parts = new ArrayList<>();
        for (var slice : LineFlowSlices.split(shaped.lines().size(), style.lineHeight(), regions, pagination)) {
            int first = slice.first();
            int take = slice.count();
            List<TextLine> lines = new ArrayList<>();
            for (int offset = 0; offset < take; offset++) {
                var line = shaped.lines().get(first + offset);
                lines.add(line.atBaseline(line.baselineY() - first * style.lineHeight()));
            }
            int start = lines.getFirst().sourceStart();
            int end = lines.getLast().sourceEnd();
            var diagnostics = shaped.diagnostics().stream().filter(d -> d.sourceStart() >= start && d.sourceStart() < end).toList();
            var layout = new ParagraphLayout(context.width(), take * style.lineHeight(), lines, diagnostics);
            var builder = new DisplayListBuilder(new PageSize(layout.width(), layout.height()));
            TextLayoutPainter.draw(builder, layout, new Point(0, 0), color);
            parts.add(new RegionFlow.Fragment(slice.region(), new ComponentLayout(layout.width(), layout.height(), take, builder.build().commands(), diagnostics)));
        }
        return List.copyOf(parts);
    }

    FlowText resolved(ComponentContext context) {
        return inheritance.equals(TextInheritance.NONE) ? this : new FlowText(text,
                inheritance.resolveStyle(style, context), inheritance.resolveColor(color, context), pagination);
    }

    /** Task-confined builder. Dimensions use points unless the type specifies another unit.
     * build() validates the complete value and takes an immutable structural snapshot. */
    public static Builder builder() { return new Builder(); }
    public static Builder builder(String text) { return new Builder().text(text); }
    /** Starts an independent builder from this value; referenced resources remain borrowed. */
    public Builder toBuilder() { return new Builder(this); }

    /** Mutable, task-confined construction state; built values do not retain this builder. */
    public static final class Builder {
        private String text = "";
        private ComponentTextStyle typography = new ComponentTextStyle();
        private PaginationPolicy pagination = PaginationPolicy.DEFAULT;

        private Builder() { }
        private Builder(FlowText source) {
            text = source.text; typography = new ComponentTextStyle(source.style, source.color, source.inheritance); pagination = source.pagination;
        }
        public Builder text(String value) { text = java.util.Objects.requireNonNull(value, "text"); return this; }
        public Builder style(ParagraphStyle value) { typography.style(value); return this; }
        public Builder style(java.util.function.Consumer<ParagraphStyle.Builder> configure) { typography.style(configure); return this; }
        public Builder color(RgbColor value) { typography.color(value); return this; }
        /** Uses the complete default text style from the layout context. */
        public Builder inheritStyle() { typography.inheritStyle(); return this; }
        /** Uses the default text color from the layout context. */
        public Builder inheritColor() { typography.inheritColor(); return this; }

        /** Overrides only this property unless a complete style was supplied; lengths use points. */
        public Builder fontSize(double value) { typography.fontSize(value); return this; }
        /** Restores only this property to the surrounding text defaults. */
        public Builder inheritFontSize() { typography.inherit(TextInheritance.Property.FONT_SIZE); return this; }
        /** Overrides only this property unless a complete style was supplied; lengths use points. */
        public Builder lineHeight(double value) { typography.lineHeight(value); return this; }
        /** Restores only this property to the surrounding text defaults. */
        public Builder inheritLineHeight() { typography.inherit(TextInheritance.Property.LINE_HEIGHT); return this; }
        /** Overrides only this property unless a complete style was supplied; lengths use points. */
        public Builder firstLineIndent(double value) { typography.firstLineIndent(value); return this; }
        /** Restores only this property to the surrounding text defaults. */
        public Builder inheritFirstLineIndent() { typography.inherit(TextInheritance.Property.FIRST_LINE_INDENT); return this; }
        /** Overrides only this property unless a complete style was supplied; lengths use points. */
        public Builder subsequentLineIndent(double value) { typography.subsequentLineIndent(value); return this; }
        /** Restores only this property to the surrounding text defaults. */
        public Builder inheritSubsequentLineIndent() { typography.inherit(TextInheritance.Property.SUBSEQUENT_LINE_INDENT); return this; }
        /** Overrides only this property unless a complete style was supplied; lengths use points. */
        public Builder alignment(TextAlignment value) { typography.alignment(value); return this; }
        /** Restores only this property to the surrounding text defaults. */
        public Builder inheritAlignment() { typography.inherit(TextInheritance.Property.ALIGNMENT); return this; }
        /** Overrides only this property unless a complete style was supplied; lengths use points. */
        public Builder lineBreakPolicy(LineBreakPolicy value) { typography.lineBreakPolicy(value); return this; }
        /** Restores only this property to the surrounding text defaults. */
        public Builder inheritLineBreakPolicy() { typography.inherit(TextInheritance.Property.LINE_BREAK_POLICY); return this; }
        public Builder pagination(PaginationPolicy value) { pagination = java.util.Objects.requireNonNull(value, "pagination"); return this; }

        public FlowText build() { return new FlowText(text, typography.style(), typography.color(), pagination, typography.inheritance()); }
    }
}
