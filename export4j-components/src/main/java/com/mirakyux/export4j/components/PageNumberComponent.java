// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.Objects;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.TextInheritance;
import com.mirakyux.export4j.model.LineBreakPolicy;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextAlignment;

/** Final-page text for headers/footers/overlays. Replaces {page}/{pages}; flow use before final pagination fails. */
public record PageNumberComponent(String pattern, ParagraphStyle style, RgbColor color, TextInheritance inheritance) implements Component {
    /** Compatibility constructor: both text properties are explicit. */
    public PageNumberComponent(String pattern, ParagraphStyle style, RgbColor color) {
        this(pattern, style, color, TextInheritance.NONE);
    }
    public PageNumberComponent {
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(color, "color");
        Objects.requireNonNull(inheritance, "inheritance");
    }

    @Override
    public ComponentLayout layout(ComponentContext context) {
        if (context.pageCount() == 0) {
            throw new IllegalArgumentException("page fields require final pagination");
        }
        return new TextComponent(pattern.replace("{page}", Integer.toString(context.displayPageNumber()))
                .replace("{pages}", Integer.toString(context.pageCount())), inheritance.resolveStyle(style, context),
                inheritance.resolveColor(color, context)).layout(context);
    }

    /** Task-confined builder. Dimensions use points unless the type specifies another unit.
     * build() validates the complete value and takes an immutable structural snapshot. */
    public static Builder builder() { return new Builder(); }
    public static Builder builder(String pattern) { return new Builder().pattern(pattern); }
    /** Starts an independent builder from this value; referenced resources remain borrowed. */
    public Builder toBuilder() {
        var value = new Builder().pattern(pattern());
        value.typography = new ComponentTextStyle(style, color, inheritance); return value;
    }

    /** Mutable, task-confined construction state; built values do not retain this builder. */
    public static final class Builder {
        private String pattern = "{page} / {pages}";
        private ComponentTextStyle typography = new ComponentTextStyle();

        private Builder() { }
        public Builder pattern(String value) { pattern = java.util.Objects.requireNonNull(value, "pattern"); return this; }
        public Builder style(ParagraphStyle value) { typography.style(value); return this; }
        public Builder color(RgbColor value) { typography.color(value); return this; }
        public Builder inheritStyle() { typography.inheritStyle(); return this; }
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

        public PageNumberComponent build() { return new PageNumberComponent(pattern, typography.style(), typography.color(), typography.inheritance()); }
    }
}
