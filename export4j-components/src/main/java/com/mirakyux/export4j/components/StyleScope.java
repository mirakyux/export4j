// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.List;
import java.util.Objects;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.FlowRegions;
import com.mirakyux.export4j.layout.core.RegionFlow;
import com.mirakyux.export4j.layout.core.PageableComponent;
import com.mirakyux.export4j.layout.core.TextInheritance;
import com.mirakyux.export4j.model.LineBreakPolicy;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextAlignment;

/** Regional defaults for unset component text properties. Nested scopes override only declared
 * style/color properties; explicit child declarations always win. Named setters override individual
 * properties; style(value/callback) declares a complete style. Preserves region flow, semantics and diagnostics.
 * Point units; immutable structure, child thread-safety applies, owns no resources.
 */
public record StyleScope(Component content, ParagraphStyle style, RgbColor color,
        TextInheritance inheritance) implements RegionFlow {
    public StyleScope {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(color, "color");
        Objects.requireNonNull(inheritance, "inheritance");
    }

    /** Both supplied defaults are explicit; child declarations still take precedence. */
    public StyleScope(Component content, ParagraphStyle style, RgbColor color) {
        this(content, style, color, TextInheritance.NONE);
    }

    ComponentContext scoped(ComponentContext context) {
        return context.withTextDefaults(inheritance.resolveStyle(style, context), inheritance.resolveColor(color, context));
    }

    @Override public List<Fragment> layoutRegions(ComponentContext context, FlowRegions regions) {
        var scoped = scoped(context);
        return content instanceof RegionFlow flow ? flow.layoutRegions(scoped, regions)
                : ComponentSupport.atomicRegions(content, scoped, regions);
    }

    @Override public ComponentLayout layout(ComponentContext context) { return content.layout(scoped(context)); }

    @Override public List<ComponentLayout> layoutParts(ComponentContext context) {
        var scoped = scoped(context);
        return content instanceof PageableComponent pageable ? pageable.layoutParts(scoped) : List.of(content.layout(scoped));
    }

    @Override public List<ComponentLayout> layoutParts(ComponentContext context, double firstPageHeight) {
        ComponentSupport.firstPageHeight(firstPageHeight, context);
        var scoped = scoped(context);
        return content instanceof PageableComponent pageable ? pageable.layoutParts(scoped, firstPageHeight) : List.of(content.layout(scoped));
    }

    public static Builder builder(Component content) { return new Builder(content); }
    public Builder toBuilder() {
        var value = new Builder(content);
        value.typography = new ComponentTextStyle(style, color, inheritance);
        return value;
    }

    /** Task-confined construction state; build snapshots values and retains no callbacks. */
    public static final class Builder {
        private final Component content;
        private ComponentTextStyle typography = new ComponentTextStyle();
        private Builder(Component content) { this.content = Objects.requireNonNull(content, "content"); }
        public Builder style(ParagraphStyle value) { typography.style(value); return this; }
        /** Builds one explicit complete style using current local values, not future inherited values. */
        public Builder style(java.util.function.Consumer<ParagraphStyle.Builder> configure) { typography.style(configure); return this; }
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
        public StyleScope build() { return new StyleScope(content, typography.style(), typography.color(), typography.inheritance()); }
    }
}
