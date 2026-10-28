// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.MissingGlyphPolicy;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.core.FontOutlines;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.text.TextDiagnostic;
import com.mirakyux.export4j.text.TextLayoutException;
import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, atomic vertical text component using explicit physical font outlines.
 * CJK scalars remain upright; ASCII letters/digits turn clockwise individually. Selected punctuation
 * uses its Unicode vertical presentation form. This is an explicit em-cell subset, without OpenType
 * vert/vrt2 substitution, connected scripts, combining sequences, ruby or tate-chu-yoko.
 * Geometry is measured once here; backends neither rotate a horizontal paragraph nor shape text.
 */
public final class VerticalText implements Component {
    /** Order of columns. Every column reads from top to bottom. */
    public enum ColumnOrder { RIGHT_TO_LEFT, LEFT_TO_RIGHT }

    /** Original UTF-16 range and final cell geometry. Newline ranges have renderedCodePoint=-1. */
    public record Placement(int sourceStart, int sourceEnd, int columnIndex, Rect bounds,
            int renderedCodePoint, boolean sideways, String fontId) {
        public Placement {
            if (sourceStart < 0 || sourceEnd <= sourceStart || columnIndex < 0) {
                throw new IllegalArgumentException("invalid vertical text source range");
            }
            Objects.requireNonNull(bounds, "bounds");
            Objects.requireNonNull(fontId, "fontId");
        }
    }

    /** Immutable source-to-cell map alongside the complete drawing. Owns no resources. */
    public record Result(ComponentLayout layout, List<Placement> placements) {
        public Result {
            Objects.requireNonNull(layout, "layout");
            placements = List.copyOf(placements);
        }
    }

    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);
    private final String text;
    private final double fontSize;
    private final double cellAdvance;
    private final double columnAdvance;
    private final double columnHeight;
    private final RgbColor color;
    private final ColumnOrder columnOrder;
    private final int maxCodePoints;
    private final int maxColumns;

    private VerticalText(Builder builder) {
        text = builder.text;
        fontSize = builder.fontSize;
        cellAdvance = builder.cellAdvance;
        columnAdvance = builder.columnAdvance;
        columnHeight = builder.columnHeight;
        color = builder.color;
        columnOrder = builder.columnOrder;
        maxCodePoints = builder.maxCodePoints;
        maxColumns = builder.maxColumns;
        if (text.codePointCount(0, text.length()) > maxCodePoints) {
            throw failure(TextLayoutException.Code.BUDGET_EXCEEDED, 0, "vertical text source budget exceeded");
        }
        for (int index = 0; index < text.length();) {
            int codePoint = text.codePointAt(index);
            if (codePoint >= 0xd800 && codePoint <= 0xdfff
                    || Character.isISOControl(codePoint) && codePoint != '\r' && codePoint != '\n') {
                throw failure(TextLayoutException.Code.INVALID_TEXT, index, "invalid vertical text scalar or control");
            }
            int kind = Character.getType(codePoint);
            if (kind == Character.NON_SPACING_MARK || kind == Character.COMBINING_SPACING_MARK
                    || kind == Character.ENCLOSING_MARK || kind == Character.FORMAT) {
                throw failure(TextLayoutException.Code.UNSUPPORTED_SHAPING, index,
                        "vertical text combining and format sequences require a separate shaping contract");
            }
            if (!supportedScript(codePoint)) {
                throw failure(TextLayoutException.Code.UNSUPPORTED_SHAPING, index,
                        "script is outside the vertical em-cell subset");
            }
            index += Character.charCount(codePoint);
        }
    }

    public static Builder builder(String text) { return new Builder(text); }

    /** Returns precomputed outlines in local coordinates. Uses context defaults for unspecified dimensions/color. */
    @Override public ComponentLayout layout(ComponentContext context) { return layoutDetails(context).layout(); }

    /**
     * Measures the same drawing plus the original UTF-16 map, useful for inspection and diagnostics.
     * Font resources are borrowed; no system fallback, background thread or global cache is used.
     * @throws TextLayoutException for unsupported/missing glyphs, capacity and work-budget failures
     */
    public Result layoutDetails(ComponentContext context) {
        Objects.requireNonNull(context, "context");
        ExecutionContext.checkpoint();
        double size = fontSize == 0 ? context.defaultTextStyle().fontSize() : fontSize;
        double cell = cellAdvance == 0 ? context.defaultTextStyle().lineHeight() : cellAdvance;
        double column = columnAdvance == 0 ? cell : columnAdvance;
        double height = columnHeight == 0 ? context.height() : columnHeight;
        RgbColor ink = color == null ? context.defaultTextColor() : color;
        if (size > Float.MAX_VALUE || (float) size == 0 || height > Math.nextUp(context.height())
                || cell > Math.nextUp(height) || column > Math.nextUp(context.width())) {
            throw failure(TextLayoutException.Code.GLYPH_TOO_WIDE, 0, "vertical cell or component exceeds viewport");
        }
        int capacity = (int) Math.min(Integer.MAX_VALUE, Math.floor(Math.nextUp(height / cell)));
        int columnCount = 1;
        int row = 0;
        var slots = new ArrayList<Slot>();
        var diagnostics = new ArrayList<TextDiagnostic>();
        for (int offset = 0; offset < text.length();) {
            ExecutionContext.checkpoint();
            int cp = text.codePointAt(offset);
            int end = offset + Character.charCount(cp);
            if (cp == '\n' || cp == '\r') {
                if (cp == '\r' && end < text.length() && text.charAt(end) == '\n') { end++; }
                slots.add(new Slot(offset, end, columnCount - 1, Math.min(row, capacity - 1), -1, false, null));
                columnCount++;
                row = 0;
            } else {
                if (row == capacity) { columnCount++; row = 0; }
                int rendered = verticalForm(cp);
                var choice = choose(context, cp, rendered, offset, end, diagnostics);
                boolean sideways = rendered == cp && rendered > 0x20 && rendered < 0x7f;
                slots.add(new Slot(offset, end, columnCount - 1, row++, choice.codePoint(), sideways, choice.face()));
            }
            if (columnCount > maxColumns || columnCount * column > Math.nextUp(context.width())) {
                throw failure(TextLayoutException.Code.BUDGET_EXCEEDED, offset, "vertical column capacity exceeded");
            }
            offset = end;
        }
        double width = columnCount * column;
        var commands = new ArrayList<DrawCommand>();
        var placements = new ArrayList<Placement>();
        Map<String, Font> physicalFonts = new HashMap<>();
        long segmentCount = 0;
        for (var slot : slots) {
            ExecutionContext.checkpoint();
            double x = columnOrder == ColumnOrder.RIGHT_TO_LEFT
                    ? width - (slot.column() + 1) * column : slot.column() * column;
            var bounds = new Rect(x, slot.row() * cell, column, cell);
            String logical = text.substring(slot.start(), slot.end());
            if (slot.face() == null) {
                commands.add(new DrawCommand.OutlineText(logical, List.of(), ink, bounds));
                placements.add(new Placement(slot.start(), slot.end(), slot.column(), bounds, -1, false, ""));
                continue;
            }
            var face = slot.face();
            Font physical = physicalFonts.computeIfAbsent(face.data().id(), ignored -> FontOutlines.physicalFont(face.data(), size));
            int glyphId = face.glyph(slot.rendered()).orElseThrow().glyphId();
            var glyph = physical.createGlyphVector(FRC, new int[] { glyphId });
            var shape = glyph.getOutline();
            if (shape.getBounds2D().isEmpty() && !Character.isWhitespace(slot.rendered())
                    && !Character.isSpaceChar(slot.rendered())) {
                throw failure(TextLayoutException.Code.GLYPH_TOO_WIDE, slot.start(), "vertical glyph has no usable outline at this size");
            }
            var transform = new AffineTransform();
            if (slot.sideways()) {
                shape = AffineTransform.getRotateInstance(Math.PI / 2).createTransformedShape(shape);
                var box = shape.getBounds2D();
                transform.translate(x + column / 2 - box.getCenterX(), bounds.y() + cell / 2 - box.getCenterY());
            } else {
                double lineBox = (face.metrics().ascent() + face.metrics().descent()) * size;
                double advance = face.glyph(slot.rendered()).orElseThrow().advance() * size;
                transform.translate(x + (column - advance) / 2,
                        bounds.y() + (cell - lineBox) / 2 + face.metrics().ascent() * size);
            }
            shape = transform.createTransformedShape(shape);
            var actual = shape.getBounds2D();
            double tolerance = Math.max(Math.ulp(x + column), Math.ulp(bounds.y() + cell)) * 8;
            if (!actual.isEmpty() && (actual.getMinX() < x - tolerance || actual.getMaxX() > x + column + tolerance
                    || actual.getMinY() < bounds.y() - tolerance || actual.getMaxY() > bounds.y() + cell + tolerance)) {
                throw failure(TextLayoutException.Code.GLYPH_TOO_WIDE, slot.start(), "vertical glyph exceeds its reserved cell");
            }
            var paths = FontOutlines.paths(shape);
            segmentCount += paths.stream().mapToLong(path -> path.segments().size()).sum();
            if (segmentCount > 2_000_000) {
                throw failure(TextLayoutException.Code.BUDGET_EXCEEDED, slot.start(), "vertical outline work budget exceeded");
            }
            commands.add(new DrawCommand.OutlineText(logical, paths, ink, bounds));
            placements.add(new Placement(slot.start(), slot.end(), slot.column(), bounds, slot.rendered(), slot.sideways(), face.data().id()));
        }
        return new Result(new ComponentLayout(width, height, columnCount, commands, diagnostics), placements);
    }

    private record Slot(int start, int end, int column, int row, int rendered, boolean sideways, FontFace face) { }
    private record Choice(int codePoint, FontFace face) { }

    private static Choice choose(ComponentContext context, int original, int rendered, int start, int end,
            List<TextDiagnostic> diagnostics) {
        var group = context.fontGroup();
        for (int index = 0; index < group.fonts().size(); index++) {
            var face = group.fonts().get(index);
            if (face.glyph(rendered).isPresent()) {
                if (index != 0) { diagnostics.add(new TextDiagnostic(TextDiagnostic.Code.FONT_FALLBACK, start, end,
                        face.data().id(), original, -1)); }
                return new Choice(rendered, face);
            }
        }
        if (group.missingGlyphPolicy() == MissingGlyphPolicy.PLACEHOLDER_WITH_WARNING) {
            for (var face : group.fonts()) {
                if (face.glyph(group.placeholderCodePoint()).isPresent()) {
                    diagnostics.add(new TextDiagnostic(TextDiagnostic.Code.MISSING_GLYPH_REPLACED, start, end,
                            face.data().id(), original, group.placeholderCodePoint()));
                    return new Choice(group.placeholderCodePoint(), face);
                }
            }
        }
        throw failure(TextLayoutException.Code.MISSING_GLYPH, start, "vertical glyph is absent from the explicit font group");
    }

    private static boolean supportedScript(int cp) {
        if (cp >= 0xff01 && cp <= 0xff60 || cp < 0x7f) { return true; }
        return switch (Character.UnicodeScript.of(cp)) {
            case HAN, HIRAGANA, KATAKANA, HANGUL, BOPOMOFO, COMMON, UNKNOWN -> true;
            default -> false;
        };
    }

    private static int verticalForm(int cp) {
        return switch (cp) {
            case ',', 0xff0c -> 0xfe10;
            case 0x3001 -> 0xfe11;
            case 0x3002 -> 0xfe12;
            case ':', 0xff1a -> 0xfe13;
            case ';', 0xff1b -> 0xfe14;
            case '!', 0xff01 -> 0xfe15;
            case '?', 0xff1f -> 0xfe16;
            case 0x2026 -> 0xfe19;
            case 0x2014 -> 0xfe31;
            case '(', 0xff08 -> 0xfe35;
            case ')', 0xff09 -> 0xfe36;
            case '{', 0xff5b -> 0xfe37;
            case '}', 0xff5d -> 0xfe38;
            case 0x3010 -> 0xfe3b;
            case 0x3011 -> 0xfe3c;
            case 0x300a -> 0xfe3d;
            case 0x300b -> 0xfe3e;
            case 0x3008 -> 0xfe3f;
            case 0x3009 -> 0xfe40;
            case 0x300c -> 0xfe41;
            case 0x300d -> 0xfe42;
            case 0x300e -> 0xfe43;
            case 0x300f -> 0xfe44;
            case '[', 0xff3b -> 0xfe47;
            case ']', 0xff3d -> 0xfe48;
            default -> cp;
        };
    }

    private static TextLayoutException failure(TextLayoutException.Code code, int source, String message) {
        return new TextLayoutException(code, source, message);
    }

    /** Task-confined builder; zero internal defaults inherit from the component context. */
    public static final class Builder {
        private final String text;
        private double fontSize;
        private double cellAdvance;
        private double columnAdvance;
        private double columnHeight;
        private RgbColor color;
        private ColumnOrder columnOrder = ColumnOrder.RIGHT_TO_LEFT;
        private int maxCodePoints = 100_000;
        private int maxColumns = 10_000;

        private Builder(String text) { this.text = Objects.requireNonNull(text, "text"); }
        public Builder fontSize(double value) { fontSize = positive(value); return this; }
        /** Top-to-bottom pitch in pt, also the height reserved for each scalar. */
        public Builder cellAdvance(double value) { cellAdvance = positive(value); return this; }
        /** Horizontal pitch in pt, also the width reserved for each column. */
        public Builder columnAdvance(double value) { columnAdvance = positive(value); return this; }
        /** Fixed height in pt. Omit to use the available fresh-page height. */
        public Builder height(double value) { columnHeight = positive(value); return this; }
        public Builder color(RgbColor value) { color = Objects.requireNonNull(value, "color"); return this; }
        public Builder columnOrder(ColumnOrder value) { columnOrder = Objects.requireNonNull(value, "columnOrder"); return this; }
        public Builder maxCodePoints(int value) {
            if (value <= 0 || value > 100_000) { throw new IllegalArgumentException("maxCodePoints must be within 1..100000"); }
            maxCodePoints = value; return this;
        }
        public Builder maxColumns(int value) {
            if (value <= 0 || value > 10_000) { throw new IllegalArgumentException("maxColumns must be within 1..10000"); }
            maxColumns = value; return this;
        }
        public VerticalText build() { return new VerticalText(this); }
        private static double positive(double value) {
            if (!Double.isFinite(value) || value <= 0 || value > Float.MAX_VALUE) {
                throw new IllegalArgumentException("vertical text dimension must be positive, finite and in shaping range");
            }
            return value;
        }
    }
}
