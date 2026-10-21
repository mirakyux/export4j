// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.Objects;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.graphics.core.TextLayoutPainter;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.TextInheritance;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.text.ParagraphLayouter;
import com.mirakyux.export4j.model.LineBreakPolicy;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextAlignment;

/** Immutable reusable text component; wraps to viewport width, consumes measured height, never splits across pages.
 * Builders inherit unset style and color from the layout context; constructors keep explicit values. */
public record TextComponent(String text, ParagraphStyle style, RgbColor color, double minimumHeight,
        TextInheritance inheritance) implements Component {
    public TextComponent(String text, ParagraphStyle style, RgbColor color) { this(text, style, color, 0); }
    public TextComponent(String text, ParagraphStyle style, RgbColor color, double minimumHeight) {
        this(text, style, color, minimumHeight, TextInheritance.NONE);
    }
    public TextComponent {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(color, "color");
        Objects.requireNonNull(inheritance, "inheritance");
        if (!Double.isFinite(minimumHeight) || minimumHeight < 0) { throw new IllegalArgumentException("invalid minimum text height"); }
    }

    @Override
    public ComponentLayout layout(ComponentContext context) {
        if (!inheritance.equals(TextInheritance.NONE)) { return resolved(context).layout(context); }
        var textLayout = new ParagraphLayouter().layout(text, context.fontGroup(), context.width(), style);
        double height = Math.max(minimumHeight, textLayout.height());
        ComponentSupport.fits(textLayout.width(), height, context);
        var builder = new DisplayListBuilder(new PageSize(textLayout.width(), height));
        TextLayoutPainter.draw(builder, textLayout, new Point(0, 0), color);
        return new ComponentLayout(textLayout.width(), height, textLayout.lines().size(),
                builder.build().commands(), textLayout.diagnostics());
    }

    TextComponent resolved(ComponentContext context) {
        return inheritance.equals(TextInheritance.NONE) ? this : new TextComponent(text,
                inheritance.resolveStyle(style, context), inheritance.resolveColor(color, context), minimumHeight);
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
        private double minimumHeight;

        private Builder() { }
        private Builder(TextComponent source) {
            text = source.text; typography = new ComponentTextStyle(source.style, source.color, source.inheritance); minimumHeight = source.minimumHeight;
        }
        public Builder text(String value) { text = java.util.Objects.requireNonNull(value, "text"); return this; }
        public Builder style(ParagraphStyle value) { typography.style(value); return this; }
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
        /** Lower bound in points. Text stays top aligned; never shrinks or clips. */
        public Builder minHeight(double points) { minimumHeight = points; return this; }
        public Builder style(java.util.function.Consumer<ParagraphStyle.Builder> configure) { typography.style(configure); return this; }

        public TextComponent build() { return new TextComponent(text, typography.style(), typography.color(), minimumHeight, typography.inheritance()); }
    }
}
