// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import com.mirakyux.export4j.text.InlineGraphic;
import com.mirakyux.export4j.text.ParagraphLayout;
import com.mirakyux.export4j.text.ParagraphLayouter;
import com.mirakyux.export4j.model.LineBreakPolicy;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextAlignment;
import com.mirakyux.export4j.text.StyledSpan;

/** Static form labels with vector □/☑ checkboxes and ○/● radio marks. These four characters are
 * reserved controls; other text follows explicit fonts, bidi shaping and missing-glyph policies.
 * Wraps once and continues at complete lines. Mark width equals font size, ink size is 72 percent;
 * all lengths are points. Original UTF-16 positions and logical marks are retained. No AcroForm is
 * created. Immutable, thread-safe, owns no resources. Fixed regions require one fragment.
 * Builders inherit unset style and color from the layout context; constructors keep explicit values.
 */
public record FormText(String text, ParagraphStyle style, RgbColor color, TextInheritance inheritance) implements RegionFlow {
    public FormText(String text, ParagraphStyle style, RgbColor color) {
        this(text, style, color, TextInheritance.NONE);
    }

    public FormText {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(color, "color");
        Objects.requireNonNull(inheritance, "inheritance");
    }

    ParagraphLayout measure(ComponentContext context) {
        return measure(context, line -> context.width());
    }

    ParagraphLayout measure(ComponentContext context, java.util.function.IntToDoubleFunction widths) {
        if (!inheritance.equals(TextInheritance.NONE)) { return resolved(context).measure(context, widths); }
        var source = new StringBuilder(text);
        Map<Integer, InlineGraphic> graphics = new HashMap<>();
        for (int index = 0; index < text.length(); index++) {
            if ((index & 4095) == 0) { com.mirakyux.export4j.model.ExecutionContext.checkpoint(); }
            char value = text.charAt(index);
            if (FormMarks.isMark(value)) {
                if (graphics.size() == 1024) { throw new IllegalArgumentException("form mark budget exceeded"); }
                source.setCharAt(index, '\ufffc');
                graphics.put(index, FormMarks.graphic(value, style.fontSize(), style.fontSize() * .72, -1));
            }
        }
        return new ParagraphLayouter().layout(List.of(new StyledSpan(source.toString(), context.fontGroup(),
                style.fontSize(), -1, 0, false, false)), context.width(), style, Map.of(), Map.of(), graphics, widths);
    }

    ComponentLayout paint(ParagraphLayout measured, int first, int count) {
        var lines = measured.lines().subList(first, first + count).stream()
                .map(line -> line.atBaseline(line.baselineY() - first * style.lineHeight())).toList();
        int start = lines.getFirst().sourceStart();
        int end = lines.getLast().sourceEnd();
        var diagnostics = measured.diagnostics().stream().filter(d -> d.sourceStart() >= start && d.sourceStart() < end).toList();
        var paragraph = new ParagraphLayout(measured.width(), count * style.lineHeight(), lines, diagnostics);
        var builder = new DisplayListBuilder(new PageSize(paragraph.width(), paragraph.height()));
        TextLayoutPainter.draw(builder, paragraph, new Point(0, 0), color);
        return new ComponentLayout(paragraph.width(), paragraph.height(), count, builder.build().commands(), diagnostics);
    }

    @Override public List<Fragment> layoutRegions(ComponentContext context, FlowRegions regions) {
        if (!inheritance.equals(TextInheritance.NONE)) { return resolved(context).layoutRegions(context, regions); }
        ComponentSupport.fits(context.width(), regions.continuationHeight(), context);
        var measured = measure(context);
        var result = new ArrayList<Fragment>();
        for (var slice : LineFlowSlices.split(measured.lines().size(), style.lineHeight(), regions, PaginationPolicy.DEFAULT)) {
            result.add(new Fragment(slice.region(), paint(measured, slice.first(), slice.count())));
        }
        return List.copyOf(result);
    }

    FormText resolved(ComponentContext context) {
        return inheritance.equals(TextInheritance.NONE) ? this : new FormText(text,
                inheritance.resolveStyle(style, context), inheritance.resolveColor(color, context));
    }

    /** Creates task-confined construction state; build() takes an immutable snapshot. */
    public static Builder builder() { return new Builder(); }
    public static Builder builder(String text) { return new Builder().text(text); }
    public Builder toBuilder() { return new Builder(this); }

    public static final class Builder {
        private final StringBuilder text = new StringBuilder();
        private ComponentTextStyle typography = new ComponentTextStyle();
        private Builder() { }
        private Builder(FormText source) {
            text.append(source.text); typography = new ComponentTextStyle(source.style, source.color, source.inheritance);
        }
        /** Replaces all content; use append() for successive fragments. */
        public Builder text(String value) { text.setLength(0); text.append(Objects.requireNonNull(value, "text")); return this; }
        public Builder append(String value) { text.append(Objects.requireNonNull(value, "text")); return this; }
        public Builder checkbox(boolean selected) { text.append(selected ? '☑' : '□'); return this; }
        public Builder radio(boolean selected) { text.append(selected ? '●' : '○'); return this; }
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
        public FormText build() { return new FormText(text.toString(), typography.style(), typography.color(), typography.inheritance()); }
    }
}
