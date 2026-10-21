// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.FlowRegions;
import com.mirakyux.export4j.layout.core.RegionFlow;
import com.mirakyux.export4j.layout.core.TextInheritance;
import com.mirakyux.export4j.model.LineBreakPolicy;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextAlignment;

/** Static options in source order, using vector marks before labels. Radio groups permit at most
 * one selected option. Horizontal groups wrap as ordinary inline text, including within a long
 * label; vertical groups add a line break between options. Not an editable field or PDF/UA control.
 * Immutable, thread-safe, owns no resources; dimensions in points. Builders inherit unset style and
 * color from the layout context; constructors keep explicit values.
 */
public record ChoiceGroup(List<Option> options, Kind kind, boolean vertical, ParagraphStyle style, RgbColor color,
        TextInheritance inheritance) implements RegionFlow {
    public enum Kind { CHECKBOX, RADIO }
    public record Option(String label, boolean selected) {
        public Option {
            Objects.requireNonNull(label, "label");
            if (label.isBlank() || label.chars().anyMatch(c -> FormMarks.isMark((char) c))) {
                throw new IllegalArgumentException("option requires a label without reserved form marks");
            }
        }
    }
    public ChoiceGroup(List<Option> options, Kind kind, boolean vertical, ParagraphStyle style, RgbColor color) {
        this(options, kind, vertical, style, color, TextInheritance.NONE);
    }
    public ChoiceGroup {
        options = List.copyOf(options);
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(color, "color");
        Objects.requireNonNull(inheritance, "inheritance");
        if (options.isEmpty() || options.size() > 1024 || kind == Kind.RADIO && options.stream().filter(Option::selected).count() > 1) {
            throw new IllegalArgumentException("invalid option count or multiple selected radio options");
        }
    }
    @Override public List<Fragment> layoutRegions(ComponentContext context, FlowRegions regions) {
        if (!inheritance.equals(TextInheritance.NONE)) { return resolved(context).layoutRegions(context, regions); }
        return text().layoutRegions(context, regions);
    }
    ChoiceGroup resolved(ComponentContext context) {
        return inheritance.equals(TextInheritance.NONE) ? this : new ChoiceGroup(options, kind, vertical,
                inheritance.resolveStyle(style, context), inheritance.resolveColor(color, context));
    }
    FormText text() {
        var value = FormText.builder().style(style).color(color);
        for (int index = 0; index < options.size(); index++) {
            if (index > 0) { value.append(vertical ? "\n" : "  "); }
            var option = options.get(index);
            if (kind == Kind.RADIO) { value.radio(option.selected()); } else { value.checkbox(option.selected()); }
            value.append(" " + option.label());
        }
        return value.build();
    }
    public static Builder builder() { return new Builder(); }
    public Builder toBuilder() { return new Builder(this); }
    /** Mutable task-confined construction state; options are snapshotted by build(). */
    public static final class Builder {
        private final List<Option> options = new ArrayList<>();
        private Kind kind = Kind.CHECKBOX;
        private boolean vertical;
        private ComponentTextStyle typography = new ComponentTextStyle();
        private Builder() { }
        private Builder(ChoiceGroup source) {
            options.addAll(source.options); kind = source.kind; vertical = source.vertical;
            typography = new ComponentTextStyle(source.style, source.color, source.inheritance);
        }
        public Builder options(List<Option> values) { var copy = List.copyOf(values); options.clear(); options.addAll(copy); return this; }
        public Builder option(String label, boolean selected) { options.add(new Option(label, selected)); return this; }
        public Builder kind(Kind value) { kind = Objects.requireNonNull(value, "kind"); return this; }
        public Builder vertical(boolean value) { vertical = value; return this; }
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
        public ChoiceGroup build() { return new ChoiceGroup(options, kind, vertical, typography.style(), typography.color(), typography.inheritance()); }
    }
}
