// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.awt.font.TextMeasurer;
import java.text.AttributedString;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.Objects;
import java.util.function.IntToDoubleFunction;
import com.mirakyux.export4j.font.MissingGlyphPolicy;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.core.FontOutlines;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.FlowRegions;
import com.mirakyux.export4j.layout.core.RegionFlow;
import com.mirakyux.export4j.layout.core.TextInheritance;
import com.mirakyux.export4j.model.PaginationPolicy;
import com.mirakyux.export4j.text.LineBreakRules;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextDiagnostic;
import com.mirakyux.export4j.text.TextAlignment;
import com.mirakyux.export4j.text.TextClusters;
import com.mirakyux.export4j.text.TextJustification;
import com.mirakyux.export4j.model.LineBreakPolicy;

/** JDK OpenType shaping, kerning, ligatures, combining marks and bidi using explicit physical fonts.
 * Outlines are precomputed at layout time. PDF selection/search depends on reader support for ActualText.
 * Does not provide color emoji, variable-font axes, vertical text or full PDF/UA tagging.
 * Builders inherit unset style and color from the layout context; constructors keep explicit values.
 */
public record OpenTypeText(String text, ParagraphStyle style, RgbColor color, Direction direction,
        boolean kerning, boolean ligatures, boolean cjkPunctuation, TextInheritance inheritance) implements RegionFlow {
    public enum Direction { AUTO, LEFT_TO_RIGHT, RIGHT_TO_LEFT }
    record Line(int sourceStart, int sourceEnd, double width, DrawCommand.OutlineText command) { }
    record Measured(List<Line> lines, List<TextDiagnostic> diagnostics) { }

    public OpenTypeText(String text, ParagraphStyle style, RgbColor color, Direction direction,
            boolean kerning, boolean ligatures, boolean cjkPunctuation) {
        this(text, style, color, direction, kerning, ligatures, cjkPunctuation, TextInheritance.NONE);
    }

    public OpenTypeText {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(color, "color");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(inheritance, "inheritance");
        if (text.codePointCount(0, text.length()) > 200_000) { throw new IllegalArgumentException("OpenType text budget exceeded"); }
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset);
            if (cp >= 0xd800 && cp <= 0xdfff || Character.isISOControl(cp) && cp != '\n' && cp != '\r') {
                throw new IllegalArgumentException("invalid Unicode or unsupported control at UTF-16 index " + offset);
            }
            offset += Character.charCount(cp);
        }
    }

    public OpenTypeText(String text, ParagraphStyle style, RgbColor color) {
        this(text, style, color, Direction.AUTO, true, true, true);
    }

    @Override
    public List<ComponentLayout> layoutParts(ComponentContext context) { return RegionFlow.super.layoutParts(context); }

    @Override
    public List<ComponentLayout> layoutParts(ComponentContext context, double firstPageHeight) {
        return RegionFlow.super.layoutParts(context, firstPageHeight);
    }

    @Override
    public List<RegionFlow.Fragment> layoutRegions(ComponentContext context, FlowRegions regions) {
        if (!inheritance.equals(TextInheritance.NONE)) { return resolved(context).layoutRegions(context, regions); }
        ComponentSupport.fits(context.width(), regions.continuationHeight(), context);
        var measured = measure(context, line -> context.width(), line -> 0);
        List<RegionFlow.Fragment> parts = new ArrayList<>();
        for (var slice : LineFlowSlices.split(measured.lines().size(), style.lineHeight(), regions, PaginationPolicy.DEFAULT)) {
            int first = slice.first();
            int count = slice.count();
            var commands = new ArrayList<DrawCommand>();
            for (int line = 0; line < count; line++) {
                commands.add(new DrawCommand.Save());
                commands.add(new DrawCommand.Transform(com.mirakyux.export4j.graphics.AffineTransform.translation(0, line * style.lineHeight())));
                commands.add(measured.lines().get(first + line).command());
                commands.add(new DrawCommand.Restore());
            }
            int start = measured.lines().get(first).sourceStart();
            int end = measured.lines().get(first + count - 1).sourceEnd();
            var diagnostics = measured.diagnostics().stream().filter(d -> d.sourceStart() >= start && d.sourceStart() < end).toList();
            parts.add(new RegionFlow.Fragment(slice.region(), new ComponentLayout(context.width(), count * style.lineHeight(), count, commands, diagnostics)));
        }
        return List.copyOf(parts);
    }

    OpenTypeText resolved(ComponentContext context) {
        return inheritance.equals(TextInheritance.NONE) ? this : new OpenTypeText(text,
                inheritance.resolveStyle(style, context), inheritance.resolveColor(color, context), direction,
                kerning, ligatures, cjkPunctuation);
    }

    Measured measure(ComponentContext context, IntToDoubleFunction widths, IntToDoubleFunction offsets) {
        if (!inheritance.equals(TextInheritance.NONE)) { return resolved(context).measure(context, widths, offsets); }
        List<Font> fonts = context.fonts().stream().map(face -> FontOutlines.physicalFont(face.data(), style.fontSize())
                .deriveFont(java.util.Map.of(TextAttribute.KERNING, kerning ? TextAttribute.KERNING_ON : 0,
                        TextAttribute.LIGATURES, ligatures ? TextAttribute.LIGATURES_ON : 0))).toList();
        var frc = new FontRenderContext(null, true, true);
        List<Line> lines = new ArrayList<>();
        List<TextDiagnostic> diagnostics = new ArrayList<>();
        long segments = 0;
        int start = 0;
        while (true) {
            com.mirakyux.export4j.model.ExecutionContext.checkpoint();
            int end = start;
            while (end < text.length() && text.charAt(end) != '\r' && text.charAt(end) != '\n') { end++; }
            var prepared = prepare(text.substring(start, end), start, context, fonts, diagnostics);
            if (prepared.value().isEmpty()) {
                if (lines.size() >= 100_000) { throw new IllegalArgumentException("OpenType output budget exceeded"); }
                lines.add(new Line(start, end, 0, new DrawCommand.OutlineText("", List.of(), color)));
            } else {
                var attributed = new AttributedString(prepared.value());
                for (var range : prepared.ranges()) { attributed.addAttribute(TextAttribute.FONT, fonts.get(range.font()), range.start(), range.end()); }
                if (kerning) { attributed.addAttribute(TextAttribute.KERNING, TextAttribute.KERNING_ON); }
                if (ligatures) { attributed.addAttribute(TextAttribute.LIGATURES, TextAttribute.LIGATURES_ON); }
                if (direction != Direction.AUTO) { attributed.addAttribute(TextAttribute.RUN_DIRECTION, direction == Direction.RIGHT_TO_LEFT); }
                var measurer = new TextMeasurer(attributed.getIterator(), frc);
                var natural = BreakIterator.getLineInstance(Locale.ROOT);
                natural.setText(prepared.value());
                var boundaries = prepared.boundaries();
                int baseDirection = switch (direction) {
                    case AUTO -> java.text.Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT;
                    case LEFT_TO_RIGHT -> java.text.Bidi.DIRECTION_LEFT_TO_RIGHT;
                    case RIGHT_TO_LEFT -> java.text.Bidi.DIRECTION_RIGHT_TO_LEFT;
                };
                var paragraphBidi = new java.text.Bidi(prepared.value(), baseDirection);
                retainShapingBoundaries(prepared, fonts, frc, paragraphBidi);
                int position = 0;
                while (position < prepared.value().length()) {
                    com.mirakyux.export4j.model.ExecutionContext.checkpoint();
                    double indent = lines.isEmpty() ? style.firstLineIndent() : style.subsequentLineIndent();
                    double available = widths.applyAsDouble(lines.size()) - indent;
                    if (!Double.isFinite(available) || available <= 0 || available > Float.MAX_VALUE) {
                        throw new IllegalArgumentException("invalid OpenType line width");
                    }
                    int fit = measurer.getLineBreakIndex(position, (float) available);
                    fit = boundaries.floor(fit);
                    while (fit > position && measurer.getLayout(position, fit).getAdvance() > Math.nextUp(available)) {
                        fit = boundaries.lower(fit);
                    }
                    while (fit < prepared.value().length()) {
                        int next = boundaries.higher(fit);
                        if (measurer.getLayout(position, next).getAdvance() > Math.nextUp(available)) { break; }
                        fit = next;
                    }
                    int limit = fit;
                    if (fit < prepared.value().length()) {
                        int legal = natural.isBoundary(fit) ? fit : natural.preceding(fit);
                        while (legal > position && !boundaries.contains(legal)) { legal = natural.preceding(legal); }
                        if (legal > position) { limit = legal; }
                        while (limit > position && (cjkPunctuation || style.lineBreakPolicy() == LineBreakPolicy.CJK_STRICT)
                                && !LineBreakRules.allowed(text, prepared.source().get(limit), style.lineBreakPolicy())) {
                            limit = boundaries.lower(limit);
                        }
                    }
                    if (limit <= position) { throw new IllegalArgumentException("one grapheme or punctuation group exceeds line width"); }
                    int consumed = limit;
                    if (style.alignment() == TextAlignment.JUSTIFY && limit < prepared.value().length()) {
                        while (consumed < prepared.value().length() && prepared.value().charAt(consumed) == ' ') { consumed++; }
                        while (limit > position && prepared.value().charAt(limit - 1) == ' ') { limit--; }
                    }
                    if (limit == position) { position = consumed; continue; }
                    var shaped = measurer.getLayout(position, limit);
                    if (shaped.getAscent() + shaped.getDescent() + shaped.getLeading() > Math.nextUp(style.lineHeight())) {
                        throw new IllegalArgumentException("OpenType font line box exceeds fixed line height");
                    }
                    var expansion = style.alignment() == TextAlignment.JUSTIFY && consumed < prepared.value().length()
                            ? TextJustification.of(prepared.value().substring(position, limit), shaped, available) : null;
                    double advance = shaped.getAdvance() + (expansion == null ? 0 : expansion.expansion());
                    double align = switch (style.alignment()) {
                        case LEFT, JUSTIFY -> 0;
                        case CENTER -> (available - shaped.getAdvance()) / 2;
                        case RIGHT -> available - shaped.getAdvance();
                    };
                    var transform = java.awt.geom.AffineTransform.getTranslateInstance(offsets.applyAsDouble(lines.size()) + indent + align, shaped.getAscent());
                    var paths = FontOutlines.paths(expansion == null || expansion.expansion() == 0 ? shaped.getOutline(transform)
                            : transform.createTransformedShape(justifiedOutline(shaped, prepared, position, limit, fonts, frc, expansion, paragraphBidi.createLineBidi(position, limit))));
                    segments += paths.stream().mapToLong(p -> p.segments().size()).sum();
                    if (segments > 2_000_000 || lines.size() >= 100_000) { throw new IllegalArgumentException("OpenType output budget exceeded"); }
                    int originalStart = prepared.source().get(position);
                    int originalEnd = prepared.source().get(consumed);
                    lines.add(new Line(originalStart, originalEnd, advance, new DrawCommand.OutlineText(
                            text.substring(originalStart, originalEnd), paths, color,
                            new com.mirakyux.export4j.graphics.Rect(offsets.applyAsDouble(lines.size()) + indent + align, 0,
                                    Math.max(1, advance), style.lineHeight()))));
                    position = consumed;
                }
            }
            if (end == text.length()) { break; }
            start = end + (text.charAt(end) == '\r' && end + 1 < text.length() && text.charAt(end + 1) == '\n' ? 2 : 1);
        }
        return new Measured(List.copyOf(lines), List.copyOf(diagnostics));
    }

    private java.awt.Shape justifiedOutline(java.awt.font.TextLayout line, Prepared prepared, int position, int limit,
            List<Font> fonts, FontRenderContext frc, TextJustification expansion, java.text.Bidi bidi) {
        char[] chars = prepared.value().toCharArray();
        var result = new java.awt.geom.Path2D.Double();
        for (int run = 0; run < bidi.getRunCount(); run++) {
            for (var range : prepared.ranges()) {
                int a = Math.max(bidi.getRunStart(run), range.start() - position);
                int b = Math.min(bidi.getRunLimit(run), range.end() - position);
                if (a >= b) { continue; }
                int flags = (bidi.getRunLevel(run) & 1) == 0 ? Font.LAYOUT_LEFT_TO_RIGHT : Font.LAYOUT_RIGHT_TO_LEFT;
                var vector = fonts.get(range.font()).layoutGlyphVector(frc, chars, position + a, position + b, flags);
                double x = line.getLogicalHighlightShape(a, b).getBounds2D().getMinX();
                for (int glyph = 0; glyph < vector.getNumGlyphs(); glyph++) {
                    var shift = java.awt.geom.AffineTransform.getTranslateInstance(x + expansion.shift(x + vector.getGlyphPosition(glyph).getX()), 0);
                    result.append(shift.createTransformedShape(vector.getGlyphOutline(glyph)), false);
                }
            }
        }
        return result;
    }

    private record Range(int start, int end, int font) { }
    private record Prepared(String value, List<Integer> source, List<Range> ranges,
            NavigableSet<Integer> boundaries) { }

    private static void retainShapingBoundaries(Prepared prepared, List<Font> fonts, FontRenderContext frc,
            java.text.Bidi bidi) {
        char[] chars = prepared.value().toCharArray();
        for (int run = 0; run < bidi.getRunCount(); run++) {
            com.mirakyux.export4j.model.ExecutionContext.checkpoint();
            int flags = (bidi.getRunLevel(run) & 1) == 0 ? Font.LAYOUT_LEFT_TO_RIGHT : Font.LAYOUT_RIGHT_TO_LEFT;
            for (var range : prepared.ranges()) {
                int start = Math.max(bidi.getRunStart(run), range.start());
                int end = Math.min(bidi.getRunLimit(run), range.end());
                if (start >= end) { continue; }
                var vector = fonts.get(range.font()).layoutGlyphVector(frc, chars, start, end, flags);
                var clusters = new TreeSet<Integer>();
                var consumed = new ArrayList<Integer>();
                clusters.add(start);
                clusters.add(end);
                for (int glyph = 0; glyph < vector.getNumGlyphs(); glyph++) {
                    int index = start + vector.getGlyphCharIndex(glyph);
                    clusters.add(index);
                    int cp = prepared.value().codePointAt(index);
                    if (index > start && !isFormat(cp) && !Character.isWhitespace(cp)
                            && vector.getGlyphPosition(glyph).equals(vector.getGlyphPosition(glyph + 1))) { consumed.add(index); }
                }
                clusters.removeAll(consumed);
                prepared.boundaries().subSet(start, false, end, false).retainAll(clusters);
            }
        }
    }

    private static Prepared prepare(String source, int base, ComponentContext context, List<Font> fonts, List<TextDiagnostic> diagnostics) {
        StringBuilder value = new StringBuilder();
        List<Integer> mapping = new ArrayList<>();
        List<Range> ranges = new ArrayList<>();
        NavigableSet<Integer> boundaries = new TreeSet<>();
        boundaries.add(0);
        var originalBoundaries = TextClusters.boundaries(source).iterator();
        int work = 0;
        for (int start = originalBoundaries.next(); originalBoundaries.hasNext();) {
            int end = originalBoundaries.next();
            StringBuilder cluster = new StringBuilder();
            var replaced = new HashSet<Integer>();
            for (int offset = start; offset < end;) {
                if ((work++ & 1023) == 0) { com.mirakyux.export4j.model.ExecutionContext.checkpoint(); }
                int cp = source.codePointAt(offset);
                int original = cp;
                boolean supported = isFormat(cp) || context.fonts().stream().anyMatch(f -> f.glyph(original).isPresent());
                if (!supported) {
                    if (context.fontGroup().missingGlyphPolicy() == MissingGlyphPolicy.ERROR) {
                        throw new IllegalArgumentException("missing glyph at UTF-16 index " + (base + offset));
                    }
                    cp = context.fontGroup().placeholderCodePoint();
                    replaced.add(offset);
                }
                for (int i = 0; i < Character.charCount(cp); i++) { mapping.add(base + offset); }
                cluster.appendCodePoint(cp);
                offset += Character.charCount(original);
            }
            int selected = -1;
            for (int index = 0; index < fonts.size(); index++) {
                Font candidate = fonts.get(index);
                if (covers(candidate, cluster)) { selected = index; break; }
            }
            if (selected < 0) { throw new IllegalArgumentException("no single font covers grapheme at UTF-16 index " + (base + start)); }
            String fontId = context.fonts().get(selected).data().id();
            for (int offset = start; offset < end;) {
                if ((work++ & 1023) == 0) { com.mirakyux.export4j.model.ExecutionContext.checkpoint(); }
                int cp = source.codePointAt(offset);
                if (replaced.contains(offset)) {
                    diagnostics.add(new TextDiagnostic(TextDiagnostic.Code.MISSING_GLYPH_REPLACED, base + offset,
                            base + offset + Character.charCount(cp), fontId, cp, context.fontGroup().placeholderCodePoint()));
                } else if (selected > 0 && !isFormat(cp)) {
                    diagnostics.add(new TextDiagnostic(TextDiagnostic.Code.FONT_FALLBACK, base + offset,
                            base + offset + Character.charCount(cp), fontId, cp, -1));
                }
                offset += Character.charCount(cp);
            }
            int from = value.length();
            value.append(cluster);
            boundaries.add(value.length());
            if (!ranges.isEmpty() && ranges.getLast().font() == selected) {
                ranges.set(ranges.size() - 1, new Range(ranges.getLast().start(), value.length(), selected));
            } else { ranges.add(new Range(from, value.length(), selected)); }
            start = end;
        }
        mapping.add(base + source.length());
        return new Prepared(value.toString(), mapping, ranges, boundaries);
    }

    private static boolean covers(Font font, StringBuilder cluster) {
        int work = 0;
        for (int cursor = 0; cursor < cluster.length();) {
            if ((++work & 1023) == 0) { com.mirakyux.export4j.model.ExecutionContext.checkpoint(); }
            int cp = cluster.codePointAt(cursor);
            if (!isFormat(cp) && !font.canDisplay(cp)) { return false; }
            cursor += Character.charCount(cp);
        }
        return true;
    }

    private static boolean isFormat(int cp) { return Character.getType(cp) == Character.FORMAT; }

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
        private Direction direction = Direction.AUTO;
        private boolean kerning = true;
        private boolean ligatures = true;
        private boolean cjkPunctuation = true;

        private Builder() { }
        private Builder(OpenTypeText source) {
            text = source.text; typography = new ComponentTextStyle(source.style, source.color, source.inheritance); direction = source.direction;
            kerning = source.kerning; ligatures = source.ligatures; cjkPunctuation = source.cjkPunctuation;
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
        public Builder direction(Direction value) { direction = java.util.Objects.requireNonNull(value, "direction"); return this; }
        public Builder kerning(boolean value) { kerning = value; return this; }
        public Builder ligatures(boolean value) { ligatures = value; return this; }
        public Builder cjkPunctuation(boolean value) { cjkPunctuation = value; return this; }

        public OpenTypeText build() { return new OpenTypeText(text, typography.style(), typography.color(), direction, kerning, ligatures, cjkPunctuation,
                typography.inheritance()); }
    }
}
