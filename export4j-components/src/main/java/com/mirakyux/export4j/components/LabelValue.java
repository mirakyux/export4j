// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import com.mirakyux.export4j.graphics.AffineTransform;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.FlowRegions;
import com.mirakyux.export4j.layout.core.RegionFlow;
import com.mirakyux.export4j.layout.core.TextInheritance;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.LineBreakPolicy;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextAlignment;
import com.mirakyux.export4j.text.TextDiagnostic;

/** Label with a fixed-width left column and a naturally continuing text value. Width, gap and style
 * dimensions are points. A label is printed once; complete text lines continue without borders.
 * Immutable, thread-safe, owns no resources. Does not create fields or semantic DOCX cells.
 * Builders inherit unset style and color from the layout context; constructors keep explicit values.
 */
public record LabelValue(String label, String value, double labelWidth, double gap,
        ParagraphStyle style, RgbColor color, TextInheritance inheritance) implements RegionFlow {
    public LabelValue(String label, String value, double labelWidth, double gap, ParagraphStyle style, RgbColor color) {
        this(label, value, labelWidth, gap, style, color, TextInheritance.NONE);
    }
    public LabelValue {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(color, "color");
        Objects.requireNonNull(inheritance, "inheritance");
        if (!Double.isFinite(labelWidth) || labelWidth <= 0 || !Double.isFinite(gap) || gap < 0) {
            throw new IllegalArgumentException("invalid label width or gap");
        }
    }
    @Override public List<Fragment> layoutRegions(ComponentContext context, FlowRegions regions) {
        if (!inheritance.equals(TextInheritance.NONE)) { return resolved(context).layoutRegions(context, regions); }
        ComponentSupport.fits(context.width(), regions.continuationHeight(), context);
        double valueWidth = context.width() - labelWidth - gap;
        if (valueWidth <= 0) { throw new IllegalArgumentException("label and gap consume value width"); }
        var left = PreparedCellFlow.prepare(FlowText.builder(label).style(style).color(color).build(), context.viewport(labelWidth, context.height()));
        var right = PreparedCellFlow.prepare(FlowText.builder(value).style(style).color(color).build(), context.viewport(valueWidth, context.height()));
        var fragments = new ArrayList<Fragment>();
        for (int region = 0; !left.done() || !right.done(); region++) {
            ExecutionContext.checkpoint();
            if (region >= 100_000) { throw new IllegalArgumentException("label value fragment budget exceeded"); }
            double available = regions.height(region);
            var a = left.take(available);
            var b = right.take(available);
            if (a.isEmpty() && b.isEmpty()) {
                if (region < regions.initialHeights().size()) { continue; }
                throw new IllegalArgumentException("label value line exceeds continuation region");
            }
            double height = Math.max(a.map(ComponentLayout::height).orElse(0.0), b.map(ComponentLayout::height).orElse(0.0));
            var builder = new DisplayListBuilder(new PageSize(context.width(), height));
            var diagnostics = new ArrayList<TextDiagnostic>();
            int lines = 0;
            if (a.isPresent()) {
                var part = a.orElseThrow();
                builder.appendCommands(part.commands()); diagnostics.addAll(part.diagnostics()); lines += part.textLines();
            }
            if (b.isPresent()) {
                var part = b.orElseThrow();
                builder.save().transform(AffineTransform.translation(labelWidth + gap, 0)).appendCommands(part.commands()).restore();
                diagnostics.addAll(part.diagnostics()); lines = Math.addExact(lines, part.textLines());
            }
            fragments.add(new Fragment(region, new ComponentLayout(context.width(), height, lines, builder.build().commands(), diagnostics)));
        }
        return ComponentSupport.regionFragments(fragments, context, regions);
    }
    LabelValue resolved(ComponentContext context) {
        return inheritance.equals(TextInheritance.NONE) ? this : new LabelValue(label, value, labelWidth, gap,
                inheritance.resolveStyle(style, context), inheritance.resolveColor(color, context));
    }
    public static Builder builder() { return new Builder(); }
    public static Builder builder(String label, String value) { return builder().label(label).value(value); }
    public Builder toBuilder() { return new Builder(this); }
    /** Mutable task-confined construction state. */
    public static final class Builder {
        private String label = "";
        private String value = "";
        private double labelWidth = 72;
        private double gap = 8;
        private ComponentTextStyle typography = new ComponentTextStyle();
        private Builder() { }
        private Builder(LabelValue source) {
            label = source.label; value = source.value; labelWidth = source.labelWidth; gap = source.gap;
            typography = new ComponentTextStyle(source.style, source.color, source.inheritance);
        }
        public Builder label(String text) { label = Objects.requireNonNull(text, "label"); return this; }
        public Builder value(String text) { value = Objects.requireNonNull(text, "value"); return this; }
        public Builder labelWidth(double points) { labelWidth = points; return this; }
        public Builder gap(double points) { gap = points; return this; }
        public Builder style(ParagraphStyle textStyle) { typography.style(textStyle); return this; }
        public Builder style(java.util.function.Consumer<ParagraphStyle.Builder> configure) { typography.style(configure); return this; }
        public Builder color(RgbColor ink) { typography.color(ink); return this; }
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
        public LabelValue build() { return new LabelValue(label, value, labelWidth, gap, typography.style(), typography.color(), typography.inheritance()); }
    }
}
