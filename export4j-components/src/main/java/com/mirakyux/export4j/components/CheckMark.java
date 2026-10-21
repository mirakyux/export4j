// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.List;
import java.util.Objects;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.graphics.core.TextLayoutPainter;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.text.ParagraphLayout;
import com.mirakyux.export4j.text.TextLine;

/** Font-independent static square check or round radio mark. Size is both width and height in points.
 * Logical selection is retained in outlined text; no interactive field is created. Immutable,
 * thread-safe, owns no resources. Fails when the square cannot fit the viewport.
 */
public record CheckMark(ChoiceGroup.Kind kind, boolean selected, double size, RgbColor color) implements Component {
    public CheckMark {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(color, "color");
        if (!Double.isFinite(size) || size <= 0 || size > Float.MAX_VALUE) { throw new IllegalArgumentException("invalid mark size"); }
    }
    @Override public ComponentLayout layout(ComponentContext context) {
        ComponentSupport.fits(size, size, context);
        char symbol = kind == ChoiceGroup.Kind.CHECKBOX ? (selected ? '☑' : '□') : (selected ? '●' : '○');
        var graphic = FormMarks.graphic(symbol, size, size, -1);
        var line = new TextLine(0, 1, 0, size, size, List.of(), List.of(graphic.ink()));
        var builder = new DisplayListBuilder(new PageSize(size, size));
        TextLayoutPainter.draw(builder, new ParagraphLayout(size, size, List.of(line), List.of()), new Point(0, 0), color);
        return new ComponentLayout(size, size, 1, builder.build().commands(), List.of());
    }
    public static Builder builder() { return new Builder(); }
    public Builder toBuilder() { return builder().kind(kind).selected(selected).size(size).color(color); }
    /** Mutable task-confined construction state. */
    public static final class Builder {
        private ChoiceGroup.Kind kind = ChoiceGroup.Kind.CHECKBOX;
        private boolean selected;
        private double size = 9;
        private RgbColor color = RgbColor.BLACK;
        private Builder() { }
        public Builder kind(ChoiceGroup.Kind value) { kind = Objects.requireNonNull(value, "kind"); return this; }
        public Builder selected(boolean value) { selected = value; return this; }
        public Builder size(double value) { size = value; return this; }
        public Builder color(RgbColor value) { color = Objects.requireNonNull(value, "color"); return this; }
        public CheckMark build() { return new CheckMark(kind, selected, size, color); }
    }
}
