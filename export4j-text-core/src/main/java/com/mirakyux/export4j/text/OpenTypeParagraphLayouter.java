// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.text;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.font.GraphicAttribute;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineField;
import java.awt.FontFormatException;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.awt.font.TextMeasurer;
import java.awt.geom.AffineTransform;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.text.AttributedString;
import java.text.Bidi;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import com.mirakyux.export4j.font.MissingGlyphPolicy;
import com.mirakyux.export4j.font.FontData;
import com.mirakyux.export4j.font.FontGroup;

/** Shared JDK shaping for semantic text. No system fallback, backend objects or live font handles escape. */
final class OpenTypeParagraphLayouter {
    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);
    private final TextLayoutLimits limits;

    OpenTypeParagraphLayouter(TextLayoutLimits limits) { this.limits = limits; }

    private static Font physical(FontData data, double size) {
        try {
            return Font.createFont(Font.TRUETYPE_FONT, new ByteArrayInputStream(data.bytes())).deriveFont((float) size)
                    .deriveFont(Map.of(TextAttribute.KERNING, TextAttribute.KERNING_ON, TextAttribute.LIGATURES, TextAttribute.LIGATURES_ON));
        } catch (IOException | FontFormatException failure) { throw new IllegalArgumentException("explicit physical font cannot be loaded", failure); }
    }

    static double minimumLineHeight(FontGroup group, double size) {
        double ascent = 0; double descent = 0;
        for (var face : group.fonts()) {
            var metrics = physical(face.data(), size).getLineMetrics("Ag", FRC);
            ascent = Math.max(ascent, Math.max(face.metrics().ascent() * size, metrics.getAscent()));
            descent = Math.max(descent, Math.max(face.metrics().descent() * size, metrics.getDescent()));
        }
        return Math.nextUp(ascent + descent);
    }

    static boolean required(String text) {
        return text.codePoints().anyMatch(cp -> {
            int type = Character.getType(cp);
            return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                    || type == Character.ENCLOSING_MARK || type == Character.FORMAT
                    || switch (Character.UnicodeScript.of(cp)) {
                        case LATIN, HAN, GREEK, CYRILLIC, HIRAGANA, KATAKANA, HANGUL, BOPOMOFO, COMMON -> false;
                        default -> true;
                    };
        });
    }

    private record Range(int start, int end, Font font, StyledSpan style, InlineImage image, InlineField field, InlineGraphic graphic) {
        Range(int start, int end, Font font, StyledSpan style) { this(start, end, font, style, null, null, null); }
    }
    private static final class VectorGraphic extends GraphicAttribute {
        private final InlineGraphic graphic;
        VectorGraphic(InlineGraphic graphic) { super(ROMAN_BASELINE); this.graphic = graphic; }
        @Override public float getAscent() { return (float) graphic.ascent(); }
        @Override public float getDescent() { return (float) graphic.descent(); }
        @Override public float getAdvance() { return (float) graphic.width(); }
        @Override public void draw(Graphics2D graphics, float x, float y) { /* Measurement only. */ }
    }
    private static final class FieldGraphic extends GraphicAttribute {
        private final InlineField field;
        FieldGraphic(InlineField field) { super(ROMAN_BASELINE); this.field = field; }
        @Override public float getAscent() { return (float) Math.max(0, field.style().fontSize() - field.style().baselineShift()); }
        @Override public float getDescent() { return (float) Math.max(0, field.style().baselineShift()); }
        @Override public float getAdvance() { return (float) field.width(); }
        @Override public void draw(Graphics2D graphics, float x, float y) { /* Resolved after pagination. */ }
    }
    private static final class ImageGraphic extends GraphicAttribute {
        private final InlineImage image;
        ImageGraphic(InlineImage image) { super(ROMAN_BASELINE); this.image = image; }
        @Override public float getAscent() { return (float) Math.max(0, image.height() - image.baselineShift()); }
        @Override public float getDescent() { return (float) Math.max(0, image.baselineShift()); }
        @Override public float getAdvance() { return (float) image.width(); }
        @Override public void draw(Graphics2D graphics, float x, float y) { /* Measuring only; backends consume precomputed boxes. */ }
    }
    private record Prepared(String value, List<Integer> source, List<Range> ranges,
            NavigableSet<Integer> boundaries) { }

    ParagraphLayout layout(List<StyledSpan> spans, double width, ParagraphStyle style, double ascent,
            Map<Integer, InlineImage> images, Map<Integer, InlineField> fields, Map<Integer, InlineGraphic> graphics) {
        String source = spans.stream().map(StyledSpan::text).collect(java.util.stream.Collectors.joining());
        List<TextDiagnostic> diagnostics = new ArrayList<>();
        Map<String, Font> fonts = new HashMap<>();
        double descent = 0;
        for (var span : spans) {
            for (var face : span.fonts()) {
                Font font = fonts.computeIfAbsent(face.data().id() + ":" + span.fontSize(), ignored -> physical(face.data(), span.fontSize()));
                var metrics = font.getLineMetrics("Ag", FRC);
                ascent = Math.max(ascent, metrics.getAscent() - span.baselineShift());
                descent = Math.max(descent, Math.max(face.metrics().descent() * span.fontSize(), metrics.getDescent()) + span.baselineShift());
            }
        }
        if (images.size() > 1024 || images.keySet().stream().anyMatch(index -> index < 0 || index >= source.length() || source.charAt(index) != '\ufffc')) {
            throw new IllegalArgumentException("invalid inline image markers");
        }
        if (fields.size() > 1024 || fields.keySet().stream().anyMatch(index -> index < 0 || index >= source.length()
                || source.charAt(index) != '\ufffc' || images.containsKey(index))) {
            throw new IllegalArgumentException("invalid inline field markers");
        }
        if (graphics.size() > 1024 || graphics.keySet().stream().anyMatch(index -> index < 0 || index >= source.length()
                || source.charAt(index) != '\ufffc' || images.containsKey(index) || fields.containsKey(index))) {
            throw new IllegalArgumentException("invalid inline graphic markers");
        }
        for (var graphic : graphics.values()) {
            ascent = Math.max(ascent, graphic.ascent());
            descent = Math.max(descent, graphic.descent());
        }
        for (var image : images.values()) {
            ascent = Math.max(ascent, image.height() - image.baselineShift());
            descent = Math.max(descent, image.baselineShift());
        }
        if (ascent + descent > Math.nextUp(style.lineHeight())) { throw failure(TextLayoutException.Code.LINE_HEIGHT_TOO_SMALL, 0); }
        List<TextLine> lines = new ArrayList<>();
        long segments = 0;
        for (int start = 0;;) {
            int end = start;
            while (end < source.length() && source.charAt(end) != '\n' && source.charAt(end) != '\r') { end++; }
            var prepared = prepare(source, start, end, spans, fonts, diagnostics, images, fields, graphics);
            String value = prepared.value();
            if (value.isEmpty()) {
                lines.add(new TextLine(start, end, 0, lines.size() * style.lineHeight() + ascent, 0, List.of()));
            } else {
                var attributed = new AttributedString(value);
                for (var range : prepared.ranges()) {
                    if (range.graphic() != null) { attributed.addAttribute(TextAttribute.CHAR_REPLACEMENT, new VectorGraphic(range.graphic()), range.start(), range.end()); }
                    else if (range.field() != null) { attributed.addAttribute(TextAttribute.CHAR_REPLACEMENT, new FieldGraphic(range.field()), range.start(), range.end()); }
                    else if (range.image() != null) { attributed.addAttribute(TextAttribute.CHAR_REPLACEMENT, new ImageGraphic(range.image()), range.start(), range.end()); }
                    else { attributed.addAttribute(TextAttribute.FONT, range.font(), range.start(), range.end()); }
                }
                var measurer = new TextMeasurer(attributed.getIterator(), FRC);
                var natural = BreakIterator.getLineInstance(Locale.ROOT);
                natural.setText(value);
                var boundaries = prepared.boundaries();
                var paragraphBidi = new Bidi(value, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT);
                char[] chars = value.toCharArray();
                retainShapingBoundaries(prepared, paragraphBidi, chars);
                for (int position = 0; position < value.length();) {
                    com.mirakyux.export4j.model.ExecutionContext.checkpoint();
                    if (lines.size() >= limits.maxLines()) { throw failure(TextLayoutException.Code.BUDGET_EXCEEDED, prepared.source().get(position)); }
                    double indent = lines.isEmpty() ? style.firstLineIndent() : style.subsequentLineIndent();
                    double available = width - indent;
                    if (available > Float.MAX_VALUE) { throw new IllegalArgumentException("line width exceeds shaping range"); }
                    // Verify TextMeasurer's estimate against its actual line advance. Atomic graphics can
                    // make the estimate reject a line at its own previously measured auto-column width.
                    int fit = measurer.getLineBreakIndex(position, (float) available);
                    fit = boundaries.floor(fit);
                    while (fit > position && measurer.getLayout(position, fit).getAdvance() > Math.nextUp(available)) {
                        fit = boundaries.lower(fit);
                    }
                    while (fit < value.length()) {
                        int next = boundaries.higher(fit);
                        if (measurer.getLayout(position, next).getAdvance() > Math.nextUp(available)) { break; }
                        fit = next;
                    }
                    int limit = fit;
                    if (fit < value.length()) {
                        int legal = natural.isBoundary(fit) ? fit : natural.preceding(fit);
                        while (legal > position && !boundaries.contains(legal)) { legal = natural.preceding(legal); }
                        if (legal > position) { limit = legal; }
                        while (limit > position && !LineBreakRules.allowed(source, prepared.source().get(limit), style.lineBreakPolicy())) {
                            limit = boundaries.lower(limit);
                        }
                    }
                    if (limit <= position) { throw failure(TextLayoutException.Code.GLYPH_TOO_WIDE, prepared.source().get(position)); }
                    int consumed = limit;
                    if (limit < value.length()) {
                        while (consumed < value.length() && value.charAt(consumed) == ' ') { consumed++; }
                        while (limit > position && value.charAt(limit - 1) == ' ') { limit--; }
                    }
                    List<TextOutline> ink = new ArrayList<>();
                    List<ImageBox> imageBoxes = new ArrayList<>();
                    List<FieldBox> fieldBoxes = new ArrayList<>();
                    double advance = 0;
                    if (limit > position) {
                        var line = measurer.getLayout(position, limit);
                        if (line.getAscent() + line.getDescent() > Math.nextUp(style.lineHeight())) {
                            throw failure(TextLayoutException.Code.LINE_HEIGHT_TOO_SMALL, prepared.source().get(position));
                        }
                        advance = line.getAdvance();
                        var expansion = style.alignment() == TextAlignment.JUSTIFY && consumed < value.length()
                                ? TextJustification.of(value.substring(position, limit), line, available) : null;
                        if (expansion != null) { advance += expansion.expansion(); }
                        var bidi = paragraphBidi.createLineBidi(position, limit);
                        List<Range> pieces = new ArrayList<>();
                        Map<Integer, Boolean> directions = new HashMap<>();
                        for (int run = 0; run < bidi.getRunCount(); run++) {
                            int from = position + bidi.getRunStart(run);
                            int to = position + bidi.getRunLimit(run);
                            for (var range : prepared.ranges()) {
                                int a = Math.max(from, range.start());
                                int b = Math.min(to, range.end());
                                if (a < b) {
                                    pieces.add(new Range(a, b, range.font(), range.style(), range.image(), range.field(), range.graphic()));
                                    directions.put(a, (bidi.getRunLevel(run) & 1) != 0);
                                }
                            }
                        }
                        pieces.sort(Comparator.comparingInt(Range::start));
                        for (int pieceIndex = 0; pieceIndex < pieces.size();) {
                            var piece = pieces.get(pieceIndex++);
                            int a = piece.start() - position;
                            int b = piece.end() - position;
                            if (piece.graphic() != null) {
                                double x = line.getLogicalHighlightShape(a, b).getBounds2D().getMinX();
                                if (expansion != null) { x += expansion.shift(x); }
                                var graphicInk = piece.graphic().ink();
                                var paths = new ArrayList<TextOutline.Segment>();
                                for (var segment : graphicInk.segments()) {
                                    var coordinates = new ArrayList<>(segment.coordinates());
                                    for (int i = 0; i < coordinates.size(); i += 2) { coordinates.set(i, coordinates.get(i) + x); }
                                    paths.add(new TextOutline.Segment(segment.operation(), coordinates));
                                }
                                segments += paths.size();
                                if (segments > 2_000_000) { throw failure(TextLayoutException.Code.BUDGET_EXCEEDED, prepared.source().get(piece.start())); }
                                ink.add(new TextOutline(graphicInk.logicalText(), paths, graphicInk.rgb()));
                                continue;
                            }
                            if (piece.field() != null) {
                                double x = line.getLogicalHighlightShape(a, b).getBounds2D().getMinX();
                                if (expansion != null) { x += expansion.shift(x); }
                                fieldBoxes.add(new FieldBox(prepared.source().get(piece.start()), piece.field(), Math.max(0, x), ink.size()));
                                ink.add(new TextOutline("\ufffc", List.of(), piece.style().rgb()));
                                continue;
                            }
                            if (piece.image() != null) {
                                double x = line.getLogicalHighlightShape(a, b).getBounds2D().getMinX();
                                if (expansion != null) { x += expansion.shift(x); }
                                imageBoxes.add(new ImageBox(prepared.source().get(piece.start()), piece.image(), Math.max(0, x)));
                                ink.add(new TextOutline("\ufffc", List.of(), piece.style().rgb()));
                                continue;
                            }
                            int lastPiece = pieceIndex;
                            while (lastPiece < pieces.size()) {
                                var next = pieces.get(lastPiece);
                                var previous = pieces.get(lastPiece - 1);
                                if (previous.end() != next.start() || next.font() != piece.font()
                                        || !directions.get(next.start()).equals(directions.get(piece.start()))) { break; }
                                lastPiece++;
                            }
                            b = pieces.get(lastPiece - 1).end() - position;
                            var runPieces = pieces.subList(pieceIndex - 1, lastPiece);
                            int flags = directions.get(piece.start()) ? Font.LAYOUT_RIGHT_TO_LEFT : Font.LAYOUT_LEFT_TO_RIGHT;
                            var vector = piece.font().layoutGlyphVector(FRC, chars, piece.start(), pieces.get(lastPiece - 1).end(), flags);
                            double x = line.getLogicalHighlightShape(a, b).getBounds2D().getMinX();
                            List<List<TextOutline.Segment>> runInk = new ArrayList<>();
                            for (int index = 0; index < runPieces.size(); index++) { runInk.add(new ArrayList<>()); }
                            for (int glyph = 0; glyph < vector.getNumGlyphs(); glyph++) {
                                int owner = pieceAt(runPieces, piece.start() + vector.getGlyphCharIndex(glyph));
                                var glyphStyle = runPieces.get(owner).style();
                                double shift = expansion == null ? 0 : expansion.shift(x + vector.getGlyphPosition(glyph).getX());
                                var transform = AffineTransform.getTranslateInstance(x + shift, glyphStyle.baselineShift());
                                runInk.get(owner).addAll(paths(transform.createTransformedShape(vector.getGlyphOutline(glyph))));
                            }
                            for (int index = 0; index < runPieces.size(); index++) {
                                var styledPiece = runPieces.get(index);
                                var paths = runInk.get(index);
                                var bounds = line.getLogicalHighlightShape(styledPiece.start() - position, styledPiece.end() - position).getBounds2D();
                                double decorationX = bounds.getMinX();
                                double decorationWidth = bounds.getWidth();
                                if (expansion != null) {
                                    decorationWidth += expansion.shift(decorationX + decorationWidth) - expansion.shift(decorationX);
                                    decorationX += expansion.shift(decorationX);
                                }
                                if (styledPiece.style().underline()) { paths.addAll(paths(new Rectangle2D.Double(decorationX, styledPiece.style().baselineShift() + styledPiece.style().fontSize() * .1, decorationWidth, Math.max(.3, styledPiece.style().fontSize() / 18)))); }
                                if (styledPiece.style().strikeThrough()) { paths.addAll(paths(new Rectangle2D.Double(decorationX, styledPiece.style().baselineShift() - styledPiece.style().fontSize() * .3, decorationWidth, Math.max(.3, styledPiece.style().fontSize() / 18)))); }
                                segments += paths.size();
                                if (segments > 2_000_000) { throw failure(TextLayoutException.Code.BUDGET_EXCEEDED, prepared.source().get(styledPiece.start())); }
                                ink.add(new TextOutline(source.substring(prepared.source().get(styledPiece.start()), prepared.source().get(styledPiece.end())), paths, styledPiece.style().rgb()));
                            }
                            pieceIndex = lastPiece;
                        }
                    }
                    double offset = indent + switch (style.alignment()) {
                        case LEFT, JUSTIFY -> 0; case CENTER -> (available - advance) / 2; case RIGHT -> available - advance;
                    };
                    lines.add(new TextLine(prepared.source().get(position), prepared.source().get(consumed), offset,
                            lines.size() * style.lineHeight() + ascent, advance, List.of(), ink, imageBoxes, fieldBoxes));
                    position = consumed;
                }
            }
            if (lines.size() > limits.maxLines()) { throw failure(TextLayoutException.Code.BUDGET_EXCEEDED, end); }
            if (end == source.length()) { break; }
            start = end + (source.charAt(end) == '\r' && end + 1 < source.length() && source.charAt(end + 1) == '\n' ? 2 : 1);
        }
        return new ParagraphLayout(width, lines.size() * style.lineHeight(), lines, diagnostics);
    }

    private static Prepared prepare(String source, int base, int limit, List<StyledSpan> spans,
            Map<String, Font> fonts, List<TextDiagnostic> diagnostics, Map<Integer, InlineImage> images,
            Map<Integer, InlineField> fields, Map<Integer, InlineGraphic> graphics) {
        var originalBoundaries = TextClusters.boundaries(source.substring(base, limit)).iterator();
        List<Integer> mapping = new ArrayList<>();
        List<Range> ranges = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        NavigableSet<Integer> boundaries = new TreeSet<>();
        boundaries.add(0);
        int spanIndex = 0;
        int spanBase = 0;
        int work = 0;
        for (int start = originalBoundaries.next(); originalBoundaries.hasNext();) {
            int end = originalBoundaries.next();
            while (spanBase + spans.get(spanIndex).text().length() <= base + start && spanIndex + 1 < spans.size()) {
                spanBase += spans.get(spanIndex++).text().length();
            }
            var span = spans.get(spanIndex);
            int covered = spanBase + span.text().length();
            for (int next = spanIndex + 1; base + end > covered; next++) {
                if (!sameStyle(span, spans.get(next))) { throw failure(TextLayoutException.Code.INVALID_TEXT, base + start); }
                covered += spans.get(next).text().length();
            }
            if (images.containsKey(base + start) || fields.containsKey(base + start) || graphics.containsKey(base + start)) {
                if (end != start + 1) { throw failure(TextLayoutException.Code.INVALID_TEXT, base + start); }
                var field = fields.get(base + start);
                if (field != null && (field.style().fontSize() != span.fontSize() || field.style().rgb() != span.rgb()
                        || field.style().baselineShift() != span.baselineShift() || field.style().underline() != span.underline()
                        || field.style().strikeThrough() != span.strikeThrough())) {
                    throw failure(TextLayoutException.Code.INVALID_TEXT, base + start);
                }
                mapping.add(base + start);
                ranges.add(new Range(value.length(), value.length() + 1, null, span, images.get(base + start), fields.get(base + start), graphics.get(base + start)));
                value.append('\ufffc');
                boundaries.add(value.length());
                start = end;
                continue;
            }
            StringBuilder cluster = new StringBuilder();
            var replaced = new HashSet<Integer>();
            for (int cursor = base + start; cursor < base + end;) {
                if ((work++ & 1023) == 0) { com.mirakyux.export4j.model.ExecutionContext.checkpoint(); }
                int original = source.codePointAt(cursor);
                int cp = original;
                if (cp >= 0xd800 && cp <= 0xdfff || Character.isISOControl(cp)
                        || Character.getType(cp) == Character.LINE_SEPARATOR || Character.getType(cp) == Character.PARAGRAPH_SEPARATOR) {
                    throw failure(TextLayoutException.Code.INVALID_TEXT, cursor);
                }
                if (!isFormat(cp) && span.fonts().stream().noneMatch(f -> f.glyph(original).isPresent())) {
                    if (span.fontGroup().missingGlyphPolicy() == MissingGlyphPolicy.ERROR) { throw failure(TextLayoutException.Code.MISSING_GLYPH, cursor); }
                    cp = span.fontGroup().placeholderCodePoint();
                    replaced.add(cursor);
                }
                for (int i = 0; i < Character.charCount(cp); i++) { mapping.add(cursor); }
                cluster.appendCodePoint(cp);
                cursor += Character.charCount(original);
            }
            int selected = -1;
            Font physical = null;
            for (int index = 0; index < span.fonts().size(); index++) {
                var data = span.fonts().get(index).data();
                String key = data.id() + ":" + span.fontSize();
                Font candidate = fonts.get(key);
                if (candidate == null) {
                    candidate = physical(data, span.fontSize());
                    fonts.put(key, candidate);
                }
                Font font = candidate;
                if (covers(font, cluster)) { selected = index; physical = candidate; break; }
            }
            if (selected < 0) { throw failure(TextLayoutException.Code.MISSING_GLYPH, base + start); }
            String id = span.fonts().get(selected).data().id();
            for (int cursor = base + start; cursor < base + end;) {
                if ((work++ & 1023) == 0) { com.mirakyux.export4j.model.ExecutionContext.checkpoint(); }
                int cp = source.codePointAt(cursor);
                if (replaced.contains(cursor)) {
                    diagnostics.add(new TextDiagnostic(TextDiagnostic.Code.MISSING_GLYPH_REPLACED, cursor, cursor + Character.charCount(cp), id, cp, span.fontGroup().placeholderCodePoint()));
                } else if (selected > 0 && !isFormat(cp)) {
                    diagnostics.add(new TextDiagnostic(TextDiagnostic.Code.FONT_FALLBACK, cursor, cursor + Character.charCount(cp), id));
                }
                cursor += Character.charCount(cp);
            }
            int from = value.length();
            value.append(cluster);
            boundaries.add(value.length());
            start = end;
            if (!ranges.isEmpty()) {
                var previous = ranges.getLast();
                if (previous.image() == null && previous.font() == physical && previous.style() == span) {
                    ranges.set(ranges.size() - 1, new Range(previous.start(), value.length(), physical, span));
                    continue;
                }
            }
            ranges.add(new Range(from, value.length(), physical, span));
        }
        mapping.add(limit);
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

    private static boolean sameStyle(StyledSpan first, StyledSpan second) {
        return first.fontGroup().equals(second.fontGroup()) && first.fontSize() == second.fontSize()
                && first.rgb() == second.rgb() && first.baselineShift() == second.baselineShift()
                && first.underline() == second.underline() && first.strikeThrough() == second.strikeThrough();
    }

    private static int pieceAt(List<Range> pieces, int position) {
        int low = 0; int high = pieces.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (pieces.get(middle).end() <= position) { low = middle + 1; } else { high = middle; }
        }
        return low;
    }

    private static void retainShapingBoundaries(Prepared prepared, Bidi bidi, char[] chars) {
        for (int run = 0; run < bidi.getRunCount(); run++) {
            com.mirakyux.export4j.model.ExecutionContext.checkpoint();
            List<Range> pieces = new ArrayList<>();
            for (var range : prepared.ranges()) {
                int start = Math.max(bidi.getRunStart(run), range.start());
                int end = Math.min(bidi.getRunLimit(run), range.end());
                if (start < end) { pieces.add(new Range(start, end, range.font(), range.style(), range.image(), range.field(), range.graphic())); }
            }
            for (int index = 0; index < pieces.size();) {
                var first = pieces.get(index++);
                if (first.font() == null) { continue; }
                int last = index;
                while (last < pieces.size() && pieces.get(last - 1).end() == pieces.get(last).start()
                        && first.font() == pieces.get(last).font()) { last++; }
                int end = pieces.get(last - 1).end();
                int flags = (bidi.getRunLevel(run) & 1) == 0 ? Font.LAYOUT_LEFT_TO_RIGHT : Font.LAYOUT_RIGHT_TO_LEFT;
                var vector = first.font().layoutGlyphVector(FRC, chars, first.start(), end, flags);
                var clusters = shapingBoundaries(vector, first.start(), end, prepared.value());
                prepared.boundaries().subSet(first.start(), false, end, false).retainAll(clusters);
                validateShapingStyles(clusters, pieces.subList(index - 1, last), prepared.source());
                index = last;
            }
        }
    }

    private static NavigableSet<Integer> shapingBoundaries(java.awt.font.GlyphVector vector,
            int start, int end, String value) {
        var clusters = new TreeSet<Integer>();
        var consumed = new ArrayList<Integer>();
        clusters.add(start);
        clusters.add(end);
        for (int glyph = 0; glyph < vector.getNumGlyphs(); glyph++) {
            int index = start + vector.getGlyphCharIndex(glyph);
            clusters.add(index);
            // Some JDK shapers retain a zero-advance part of a ligature (for example Arabic
            // lam/alef), rather than omitting its char index. Its ink may remain nonempty.
            int cp = value.codePointAt(index);
            if (index > start && !isFormat(cp) && !Character.isWhitespace(cp)
                    && vector.getGlyphPosition(glyph).equals(vector.getGlyphPosition(glyph + 1))) {
                consumed.add(index);
            }
        }
        clusters.removeAll(consumed);
        return clusters;
    }

    private static void validateShapingStyles(NavigableSet<Integer> clusters, List<Range> pieces,
            List<Integer> source) {
        for (int boundary : clusters) {
            if (boundary == pieces.getLast().end()) { break; }
            int end = clusters.higher(boundary);
            int first = pieceAt(pieces, boundary);
            int last = pieceAt(pieces, end - 1);
            for (int index = first + 1; index <= last; index++) {
                if (!sameStyle(pieces.get(first).style(), pieces.get(index).style())) {
                    throw failure(TextLayoutException.Code.INVALID_TEXT, source.get(boundary));
                }
            }
        }
    }

    private static boolean isFormat(int cp) { return Character.getType(cp) == Character.FORMAT; }

    private static TextLayoutException failure(TextLayoutException.Code code, int source) {
        return new TextLayoutException(code, source, "OpenType paragraph failed: " + code);
    }

    private static List<TextOutline.Segment> paths(Shape shape) {
        List<TextOutline.Segment> result = new ArrayList<>();
        double[] c = new double[6];
        double x = 0;
        double y = 0;
        for (var it = shape.getPathIterator(null); !it.isDone(); it.next()) {
            switch (it.currentSegment(c)) {
                case PathIterator.SEG_MOVETO -> { result.add(new TextOutline.Segment(TextOutline.Operation.MOVE, List.of(c[0], c[1]))); x = c[0]; y = c[1]; }
                case PathIterator.SEG_LINETO -> { result.add(new TextOutline.Segment(TextOutline.Operation.LINE, List.of(c[0], c[1]))); x = c[0]; y = c[1]; }
                case PathIterator.SEG_QUADTO -> { result.add(new TextOutline.Segment(TextOutline.Operation.CURVE, List.of(x + (c[0] - x) * 2 / 3, y + (c[1] - y) * 2 / 3, c[2] + (c[0] - c[2]) * 2 / 3, c[3] + (c[1] - c[3]) * 2 / 3, c[2], c[3]))); x = c[2]; y = c[3]; }
                case PathIterator.SEG_CUBICTO -> { result.add(new TextOutline.Segment(TextOutline.Operation.CURVE, List.of(c[0], c[1], c[2], c[3], c[4], c[5]))); x = c[4]; y = c[5]; }
                case PathIterator.SEG_CLOSE -> result.add(new TextOutline.Segment(TextOutline.Operation.CLOSE, List.of()));
                default -> throw new IllegalArgumentException("unsupported outline");
            }
            if (result.size() > 2_000_000) { throw failure(TextLayoutException.Code.BUDGET_EXCEEDED, 0); }
        }
        return result;
    }
}
