// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.Objects;
import java.util.function.Consumer;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.layout.core.TextInheritance;
import com.mirakyux.export4j.model.LineBreakPolicy;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextAlignment;

/** Task-confined text construction state shared by public component builders. */
final class ComponentTextStyle {
    private ParagraphStyle style;
    private RgbColor color;
    private TextInheritance inheritance;

    ComponentTextStyle() { this(ParagraphStyle.of(12), RgbColor.BLACK, TextInheritance.ALL); }
    ComponentTextStyle(ComponentTextStyle source) { this(source.style, source.color, source.inheritance); }
    ComponentTextStyle(ParagraphStyle style, RgbColor color, TextInheritance inheritance) {
        this.style = Objects.requireNonNull(style, "style");
        this.color = Objects.requireNonNull(color, "color");
        this.inheritance = Objects.requireNonNull(inheritance, "inheritance");
    }

    ParagraphStyle style() { return style; }
    RgbColor color() { return color; }
    TextInheritance inheritance() { return inheritance; }

    void style(ParagraphStyle value) {
        style = Objects.requireNonNull(value, "style");
        inheritance = inheritance.withStyleInheritance(false);
    }
    void style(Consumer<ParagraphStyle.Builder> configure) {
        var value = style.toBuilder();
        Objects.requireNonNull(configure, "configure").accept(value);
        style(value.build());
    }
    void color(RgbColor value) {
        color = Objects.requireNonNull(value, "color");
        inheritance = inheritance.withColorInheritance(false);
    }
    void inheritStyle() { inheritance = inheritance.withStyleInheritance(true); }
    void inheritColor() { inheritance = inheritance.withColorInheritance(true); }
    void inherit(TextInheritance.Property property) { inheritance = inheritance.withInherited(property); }

    private void property(TextInheritance.Property property, Consumer<ParagraphStyle.Builder> configure) {
        var value = style.toBuilder(); configure.accept(value);
        style = value.build();
        inheritance = inheritance.withExplicit(property);
    }
    void fontSize(double value) { property(TextInheritance.Property.FONT_SIZE, builder -> builder.fontSize(value)); }
    void lineHeight(double value) { property(TextInheritance.Property.LINE_HEIGHT, builder -> builder.lineHeight(value)); }
    void firstLineIndent(double value) { property(TextInheritance.Property.FIRST_LINE_INDENT, builder -> builder.firstLineIndent(value)); }
    void subsequentLineIndent(double value) { property(TextInheritance.Property.SUBSEQUENT_LINE_INDENT, builder -> builder.subsequentLineIndent(value)); }
    void alignment(TextAlignment value) { property(TextInheritance.Property.ALIGNMENT, builder -> builder.alignment(value)); }
    void lineBreakPolicy(LineBreakPolicy value) { property(TextInheritance.Property.LINE_BREAK_POLICY, builder -> builder.lineBreakPolicy(value)); }
}
