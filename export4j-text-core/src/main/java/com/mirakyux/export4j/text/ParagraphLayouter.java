// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.text;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntToDoubleFunction;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;

/**
 * Bounded paragraph wrapping using ROOT-locale JDK line breaks and scalar-safe emergency wrapping.
 * Explicit LF/CR/CRLF create lines. Wrap separator spaces are consumed but not drawn.
 * Performs no file I/O or page allocation; safe to reuse if its supplied shaper is thread-safe.
 */
public final class ParagraphLayouter {
    private final TextShaper shaper;
    private final TextLayoutLimits limits;
    private final boolean adaptive;

    /** Minimum fixed line height for a stack using either simple metrics or JDK OpenType metrics, in points. */
    public static double minimumLineHeight(FontGroup group, double size) {
        if (!Double.isFinite(size) || size <= 0 || size > Float.MAX_VALUE) { throw new IllegalArgumentException("invalid font size"); }
        return OpenTypeParagraphLayouter.minimumLineHeight(group, size);
    }

    /** Whether default adaptive layout uses physical OpenType shaping for this source/stack. */
    public static boolean requiresOpenType(String text, FontGroup group) {
        return OpenTypeParagraphLayouter.required(Objects.requireNonNull(text, "text"))
                || group.fonts().stream().anyMatch(face -> face.data().cffOutlines());
    }

    public ParagraphLayouter() {
        this(new SimpleTextShaper(), TextLayoutLimits.DEFAULT, true);
    }

    public ParagraphLayouter(TextShaper shaper, TextLayoutLimits limits) {
        this(shaper, limits, false);
    }

    private ParagraphLayouter(TextShaper shaper, TextLayoutLimits limits, boolean adaptive) {
        this.shaper = Objects.requireNonNull(shaper, "shaper");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.adaptive = adaptive;
    }

    public ParagraphLayout layout(String text, List<FontFace> fonts, double width, ParagraphStyle style) {
        return layout(text, FontGroup.strict(fonts), width, style);
    }

    public ParagraphLayout layout(String text, FontGroup group, double width, ParagraphStyle style) {
        return layout(List.of(new StyledSpan(text, group, style.fontSize(), -1, 0, false, false)), width, style);
    }

    /** Shapes each span once and wraps the combined source, preserving style boundaries and UTF-16 indices. */
    public ParagraphLayout layout(List<StyledSpan> spans, double width, ParagraphStyle style) {
        return layout(spans, width, style, Map.of());
    }

    /** Wraps against a positive width per zero-based output line, without slicing the logical source.
     * width is the maximum viewport width in points. The synchronous callback must be deterministic and
     * return a finite width no greater than that maximum, leaving positive space after indentation.
     */
    public ParagraphLayout layout(List<StyledSpan> spans, double width, ParagraphStyle style,
            IntToDoubleFunction lineWidths) {
        Objects.requireNonNull(lineWidths, "lineWidths");
        return layout(spans, width, style, Map.of(), Map.of(), Map.of(), false, lineWidths);
    }

    /** Shapes atomic inline images together with text using the default adaptive layouter.
     * Keys are original UTF-16 U+FFFC positions. An explicitly injected text shaper rejects images.
     */
    public ParagraphLayout layout(List<StyledSpan> spans, double width, ParagraphStyle style,
            Map<Integer, com.mirakyux.export4j.model.InlineImage> images) {
        return layout(spans, width, style, images, Map.of());
    }

    /** Measures atomic fields at original UTF-16 marker positions without resolving their values.
     * Only the adaptive layouter supports fields. Painting unresolved fields fails explicitly.
     */
    public ParagraphLayout layout(List<StyledSpan> spans, double width, ParagraphStyle style,
            Map<Integer, com.mirakyux.export4j.model.InlineImage> images,
            Map<Integer, com.mirakyux.export4j.model.InlineField> fields) {
        return layout(spans, width, style, images, fields, Map.of(), false);
    }

    /** Places bounded atomic vector ink with text, images and deferred fields. Keys are original
     * UTF-16 U+FFFC positions and cannot overlap other inline objects. Requires the default layouter.
     * Graphics contribute ascent/descent to the fixed line frame; oversized objects fail.
     */
    public ParagraphLayout layout(List<StyledSpan> spans, double width, ParagraphStyle style,
            Map<Integer, com.mirakyux.export4j.model.InlineImage> images,
            Map<Integer, com.mirakyux.export4j.model.InlineField> fields,
            Map<Integer, InlineGraphic> graphics) {
        return layout(spans, width, style, images, fields, graphics, false);
    }

    /** Explicit physical-font shaping into outlines, preserving logical source and diagnostics.
     * Uses the same bounded wrapping and font policies as adaptive layout. Requires the default layouter.
     */
    public ParagraphLayout layoutOpenType(List<StyledSpan> spans, double width, ParagraphStyle style) {
        return layout(spans, width, style, Map.of(), Map.of(), Map.of(), true);
    }

    private ParagraphLayout layout(List<StyledSpan> spans, double width, ParagraphStyle style,
            Map<Integer, com.mirakyux.export4j.model.InlineImage> images,
            Map<Integer, com.mirakyux.export4j.model.InlineField> fields,
            Map<Integer, InlineGraphic> graphics, boolean forceOpenType) {
        return layout(spans, width, style, images, fields, graphics, forceOpenType, line -> width);
    }

    private ParagraphLayout layout(List<StyledSpan> spans, double width, ParagraphStyle style,
            Map<Integer, com.mirakyux.export4j.model.InlineImage> images,
            Map<Integer, com.mirakyux.export4j.model.InlineField> fields,
            Map<Integer, InlineGraphic> graphics, boolean forceOpenType, IntToDoubleFunction lineWidths) {
        images = Map.copyOf(images);
        fields = Map.copyOf(fields);
        graphics = Map.copyOf(graphics);
        if (!adaptive && (!images.isEmpty() || !fields.isEmpty() || !graphics.isEmpty() || forceOpenType)) {
            throw new IllegalArgumentException("inline objects and OpenType require the adaptive paragraph layouter");
        }
        spans = List.copyOf(spans);
        if (spans.isEmpty()) {
            throw new IllegalArgumentException("at least one styled span is required");
        }
        String text = spans.stream().map(StyledSpan::text).collect(java.util.stream.Collectors.joining());
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(style, "style");
        if (!Double.isFinite(width) || width <= style.firstLineIndent()
                || width <= style.subsequentLineIndent()) {
            throw new IllegalArgumentException("fonts and width after indentation must be valid");
        }
        if (text.codePointCount(0, text.length()) > limits.maxCodePoints()) {
            throw new TextLayoutException(TextLayoutException.Code.BUDGET_EXCEEDED, 0,
                    "text code point budget exceeded");
        }
        // One line box for the whole configured stack keeps mixed-font baselines equally spaced.
        double ascent = spans.stream().mapToDouble(span -> span.fonts().stream()
                .mapToDouble(font -> font.metrics().ascent() * span.fontSize() - span.baselineShift()).max().orElseThrow()).max().orElseThrow();
        double descent = spans.stream().mapToDouble(span -> span.fonts().stream()
                .mapToDouble(font -> font.metrics().descent() * span.fontSize() + span.baselineShift()).max().orElseThrow()).max().orElseThrow();
        ascent = Math.max(0, ascent);
        descent = Math.max(0, descent);
        if (ascent + descent > style.lineHeight()) {
            throw new TextLayoutException(TextLayoutException.Code.LINE_HEIGHT_TOO_SMALL, 0,
                    "line height is smaller than the configured font stack's line box");
        }
        if (!images.isEmpty() || !fields.isEmpty() || !graphics.isEmpty() || forceOpenType || adaptive && (OpenTypeParagraphLayouter.required(text)
                || spans.stream().flatMap(span -> span.fonts().stream()).anyMatch(font -> font.data().cffOutlines()))) {
            return new OpenTypeParagraphLayouter(limits).layout(spans, width, style, ascent, images, fields, graphics,
                    line -> checkedLineWidth(lineWidths, line, width, style));
        }
        List<TextLine> lines = new ArrayList<>();
        List<TextDiagnostic> diagnostics = new ArrayList<>();
        Map<Integer, StyledSpan> styles = new HashMap<>();
        int start = 0;
        while (true) {
            com.mirakyux.export4j.model.ExecutionContext.checkpoint();
            int end = start;
            while (end < text.length() && text.charAt(end) != '\n' && text.charAt(end) != '\r') {
                end++;
            }
            String part = text.substring(start, end);
            List<ShapedGlyph> glyphs = new ArrayList<>();
            int spanStart = 0;
            for (var span : spans) {
                int from = Math.max(start, spanStart);
                int to = Math.min(end, spanStart + span.text().length());
                if (from < to) {
                    ShapedText shaped = shaper.shape(text.substring(from, to), span.fontGroup(), span.fontSize(), from);
                    diagnostics.addAll(shaped.diagnostics());
                    for (var glyph : shaped.glyphs()) {
                        styles.put(glyph.sourceStart(), span);
                        glyphs.add(glyph);
                    }
                }
                spanStart += span.text().length();
            }
            appendLines(part, start, glyphs, ascent, line -> checkedLineWidth(lineWidths, line, width, style), style, lines, styles);
            if (end == text.length()) {
                break;
            }
            start = end + (text.charAt(end) == '\r' && end + 1 < text.length()
                    && text.charAt(end + 1) == '\n' ? 2 : 1);
        }
        return new ParagraphLayout(width, lines.size() * style.lineHeight(), lines, diagnostics);
    }

    private static double checkedLineWidth(IntToDoubleFunction widths, int line, double maximum, ParagraphStyle style) {
        double value = widths.applyAsDouble(line);
        double indent = line == 0 ? style.firstLineIndent() : style.subsequentLineIndent();
        if (!Double.isFinite(value) || value <= indent || value > Math.nextUp(maximum)) {
            throw new IllegalArgumentException("line width must fit the viewport and leave positive text space");
        }
        return value;
    }

    private void appendLines(String source, int sourceOffset, List<ShapedGlyph> glyphs,
            double ascent, IntToDoubleFunction widths, ParagraphStyle style, List<TextLine> lines, Map<Integer, StyledSpan> styles) {
        boolean[] breaks = new boolean[source.length() + 1];
        BreakIterator iterator = BreakIterator.getLineInstance(Locale.ROOT);
        iterator.setText(source);
        for (int boundary = iterator.first(); boundary != BreakIterator.DONE; boundary = iterator.next()) {
            breaks[boundary] = true;
        }
        int start = 0;
        do {
            com.mirakyux.export4j.model.ExecutionContext.checkpoint();
            double indent = lines.isEmpty() ? style.firstLineIndent() : style.subsequentLineIndent();
            double available = widths.applyAsDouble(lines.size()) - indent;
            double measured = 0;
            int fit = start;
            int legalBreak = start;
            while (fit < glyphs.size()) {
                ShapedGlyph glyph = glyphs.get(fit);
                if (measured + glyph.glyph().advance() > available) {
                    break;
                }
                measured += glyph.glyph().advance();
                fit++;
                if (breaks[glyph.sourceEnd() - sourceOffset]) {
                    legalBreak = fit;
                }
            }
            if (fit == start && start < glyphs.size()) {
                throw new TextLayoutException(TextLayoutException.Code.GLYPH_TOO_WIDE,
                        glyphs.get(start).sourceStart(), "one glyph exceeds available line width");
            }
            boolean wrapped = fit < glyphs.size();
            int end = wrapped && legalBreak > start ? legalBreak : fit;
            if (wrapped && style.lineBreakPolicy() == com.mirakyux.export4j.model.LineBreakPolicy.CJK_STRICT) {
                while (end > start && !LineBreakRules.allowed(source, glyphs.get(end - 1).sourceEnd() - sourceOffset, style.lineBreakPolicy())) { end--; }
                if (end == start) {
                    throw new TextLayoutException(TextLayoutException.Code.GLYPH_TOO_WIDE, glyphs.get(start).sourceStart(),
                            "one nonbreaking punctuation group exceeds available line width");
                }
            }
            int consumed = end;
            if (wrapped) {
                while (consumed < glyphs.size() && glyphs.get(consumed).glyph().codePoint() == ' ') {
                    consumed++;
                }
                while (end > start && glyphs.get(end - 1).glyph().codePoint() == ' ') {
                    end--;
                }
            }
            addLine(source, glyphs, start, end, consumed, sourceOffset, ascent, indent, available, style, lines, styles, consumed < glyphs.size());
            start = consumed;
        } while (start < glyphs.size());
    }

    private void addLine(String source, List<ShapedGlyph> glyphs, int start, int end, int consumed, int sourceOffset,
            double ascent, double indent, double available, ParagraphStyle style, List<TextLine> lines, Map<Integer, StyledSpan> styles, boolean wrapped) {
        int sourceStart = start < glyphs.size() ? glyphs.get(start).sourceStart() : sourceOffset;
        if (lines.size() >= limits.maxLines()) {
            throw new TextLayoutException(TextLayoutException.Code.BUDGET_EXCEEDED, sourceStart,
                    "line budget exceeded");
        }
        double width = 0;
        java.util.Set<Integer> gaps = java.util.Set.of();
        double expansion = 0;
        if (wrapped && end > start && style.alignment() == TextAlignment.JUSTIFY) {
            int from = glyphs.get(start).sourceStart() - sourceOffset;
            int to = glyphs.get(end - 1).sourceEnd() - sourceOffset;
            gaps = new java.util.HashSet<>(LineBreakRules.expansionBoundaries(source.substring(from, to)).stream()
                    .map(boundary -> boundary + from + sourceOffset).toList());
            double measured = 0;
            for (int i = start; i < end; i++) { measured += glyphs.get(i).glyph().advance(); }
            expansion = gaps.isEmpty() ? 0 : Math.max(0, available - measured) / gaps.size();
        }
        List<GlyphRun> runs = new ArrayList<>();
        int cursor = start;
        while (cursor < end) {
            ShapedGlyph first = glyphs.get(cursor);
            StyledSpan span = styles.get(first.sourceStart());
            List<PositionedGlyph> runGlyphs = new ArrayList<>();
            do {
                ShapedGlyph glyph = glyphs.get(cursor++);
                var positioned = glyph.glyph();
                double advance = positioned.advance() + (gaps.contains(glyph.sourceEnd()) ? expansion : 0);
                width += advance;
                runGlyphs.add(advance == positioned.advance() ? positioned : new PositionedGlyph(positioned.codePoint(), positioned.glyphId(), advance));
            } while (cursor < end && glyphs.get(cursor).font().id().equals(first.font().id())
                    && styles.get(glyphs.get(cursor).sourceStart()) == span);
            runs.add(new GlyphRun(first.font(), span.fontSize(), runGlyphs, span.rgb(), span.baselineShift(), span.underline(), span.strikeThrough()));
        }
        double offset = indent + switch (style.alignment()) {
            case LEFT, JUSTIFY -> 0;
            case CENTER -> (available - width) / 2;
            case RIGHT -> available - width;
        };
        int sourceEnd = consumed > start ? glyphs.get(consumed - 1).sourceEnd() : sourceStart;
        lines.add(new TextLine(sourceStart, sourceEnd, offset,
                lines.size() * style.lineHeight() + ascent, width, runs));
    }
}
