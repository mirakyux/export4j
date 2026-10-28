// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.opentype;

import com.mirakyux.export4j.font.MissingGlyphPolicy;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.VectorPath;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.PageableComponent;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.text.TextDiagnostic;
import java.text.Bidi;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.harfbuzz.HarfBuzz;

/**
 * Immutable pageable text using explicit native OpenType fonts. Horizontal text uses Unicode bidi;
 * vertical text uses TTB metrics/vert substitutions, optional sideways runs and explicit tate-chu-yoko.
 * Line/column breaks are shaped before backend output. No system fallback or implicit native download.
 */
public final class OpenTypeText implements PageableComponent {
    public enum Flow { HORIZONTAL, VERTICAL_RIGHT_TO_LEFT, VERTICAL_LEFT_TO_RIGHT }
    public enum Orientation { MIXED, UPRIGHT, SIDEWAYS }
    /** Original UTF-16 range, fragment and physical line/column bounds. */
    public record Placement(int sourceStart, int sourceEnd, int fragmentIndex, int lineIndex, Rect bounds, boolean tateChuYoko) { }
    public record Result(List<ComponentLayout> parts, List<Placement> placements) {
        public Result { parts = List.copyOf(parts); placements = List.copyOf(placements); }
    }
    private record Span(int start, int end) { }
    private record Segment(int start, int end, int font, int level, boolean sideways, boolean combine, boolean replacement) { }
    private record Piece(OpenTypeShaper.Run run, Segment segment, java.awt.geom.AffineTransform transform) { }
    private record Prepared(int start, int end, List<Piece> pieces, double extent, double cross,
            List<TextDiagnostic> diagnostics, TreeSet<Integer> clusterEnds) { }
    private record Line(Prepared prepared, int start, int end) { }
    private final String text;
    private final List<OpenTypeFont> fonts;
    private final Flow flow;
    private final Orientation orientation;
    private final OpenTypeShaper.Options options;
    private final double lineAdvance;
    private final List<Span> combined;
    private final RgbColor color;
    private final MissingGlyphPolicy missingPolicy;
    private final int placeholder;
    private final int maxShapeCalls;
    private final int maxWorkUtf16;

    private OpenTypeText(Builder builder) {
        text = builder.text; fonts = List.copyOf(builder.fonts); flow = builder.flow; orientation = builder.orientation;
        options = builder.options; lineAdvance = builder.lineAdvance; color = builder.color;
        missingPolicy = builder.missingPolicy; placeholder = builder.placeholder;
        maxShapeCalls = builder.maxShapeCalls; maxWorkUtf16 = builder.maxWorkUtf16;
        if (fonts.isEmpty() || fonts.size() > 32 || text.length() > options.maxUtf16()) { throw new IllegalArgumentException("explicit fonts and bounded text are required"); }
        for (int start = 0; start < text.length();) {
            int cp = text.codePointAt(start);
            if (cp >= 0xd800 && cp <= 0xdfff || Character.isISOControl(cp) && cp != '\n' && cp != '\r') {
                throw new OpenTypeException(OpenTypeException.Code.INVALID_TEXT, start, "invalid OpenType source scalar or control");
            }
            start += Character.charCount(cp);
        }
        var spans = new ArrayList<>(builder.combined); spans.sort(Comparator.comparingInt(Span::start));
        var boundaries = graphemeEnds(text); boundaries.add(0);
        int prior = 0;
        for (var span : spans) {
            if (span.start() < prior || span.end() <= span.start() || span.end() > text.length()
                    || !boundaries.contains(span.start()) || !boundaries.contains(span.end())
                    || text.substring(span.start(), span.end()).codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("tate-chu-yoko ranges must be disjoint whole graphemes without newlines");
            }
            prior = span.end();
        }
        if (!spans.isEmpty() && flow == Flow.HORIZONTAL) { throw new IllegalArgumentException("tate-chu-yoko requires vertical flow"); }
        combined = List.copyOf(spans);
    }
    public static Builder builder(String text, List<OpenTypeFont> fonts) { return new Builder(text, fonts); }
    @Override public List<ComponentLayout> layoutParts(ComponentContext context) { return layoutDetails(context, context.height()).parts(); }
    @Override public List<ComponentLayout> layoutParts(ComponentContext context, double firstPageHeight) { return layoutDetails(context, firstPageHeight).parts(); }

    /** Synchronous bounded measurement, including source/fragment positions. Context fonts are not consulted. */
    public Result layoutDetails(ComponentContext context, double firstPageHeight) {
        Objects.requireNonNull(context, "context");
        if (!Double.isFinite(firstPageHeight) || firstPageHeight < 0 || firstPageHeight > context.height()) { throw new IllegalArgumentException("invalid first page height"); }
        double pitch = lineAdvance == 0 ? fonts.stream().mapToDouble(font -> (font.ascent() + font.descent()) * options.fontSize()).max().orElseThrow() : lineAdvance;
        if (lineAdvance == 0) { pitch = Math.max(pitch, options.fontSize() * 1.25); }
        if (flow == Flow.HORIZONTAL && pitch > context.height() || flow != Flow.HORIZONTAL && pitch > context.width()) { throw doesNotFit(0, "text line or column cannot fit a fresh region"); }
        try (var work = new Work()) {
            var parts = new ArrayList<ComponentLayout>(); var placements = new ArrayList<Placement>();
            int source = 0, fragment = 0; boolean terminalEmpty = text.isEmpty();
            while (source < text.length() || terminalEmpty) {
                ExecutionContext.checkpoint();
                if (fragment >= 10_000) { throw OpenTypeShaper.budget("OpenType fragment budget exceeded"); }
                double height = fragment == 0 && firstPageHeight >= (flow == Flow.HORIZONTAL ? pitch : options.fontSize()) ? firstPageHeight : context.height();
                int capacity = (int) Math.floor(Math.nextUp((flow == Flow.HORIZONTAL ? height : context.width()) / pitch));
                if (capacity <= 0) { throw doesNotFit(source, "text cannot fit the fragment"); }
                var lines = new ArrayList<Line>(); var diagnostics = new ArrayList<TextDiagnostic>();
                for (int line = 0; line < capacity && (source < text.length() || terminalEmpty); line++) {
                    terminalEmpty = false;
                    int paragraphEnd = source;
                    while (paragraphEnd < text.length() && text.charAt(paragraphEnd) != '\r' && text.charAt(paragraphEnd) != '\n') { paragraphEnd++; }
                    Prepared prepared = work.fit(source, paragraphEnd, flow == Flow.HORIZONTAL ? context.width() : height, pitch);
                    int end = prepared.end();
                    if (end == paragraphEnd && paragraphEnd < text.length()) {
                        end++;
                        if (text.charAt(paragraphEnd) == '\r' && end < text.length() && text.charAt(end) == '\n') { end++; }
                    }
                    if (end == source && source < text.length()) { throw doesNotFit(source, "OpenType pagination did not advance"); }
                    lines.add(new Line(prepared, source, end)); diagnostics.addAll(prepared.diagnostics()); source = end;
                }
                var commands = new ArrayList<DrawCommand>();
                double usedHeight = flow == Flow.HORIZONTAL ? Math.max(pitch, lines.size() * pitch) : height;
                for (int index = 0; index < lines.size(); index++) {
                    var line = lines.get(index);
                    double x = flow == Flow.HORIZONTAL ? 0 : flow == Flow.VERTICAL_RIGHT_TO_LEFT ? context.width() - (index + .5) * pitch : (index + .5) * pitch;
                    double y = flow == Flow.HORIZONTAL ? index * pitch : 0;
                    var box = flow == Flow.HORIZONTAL ? new Rect(0, y, context.width(), pitch) : new Rect(x - pitch / 2, 0, pitch, height);
                    if (flow == Flow.HORIZONTAL && line.end() > line.start()) {
                        // A real line rectangle keeps mixed bidi runs in logical reading order.
                        // Glyph-level positions remain available from shaping; selection is line-level.
                        commands.add(new DrawCommand.OutlineText(text.substring(line.start(), line.end()), List.of(), color,
                                new Rect(0, y, Math.max(.001, line.prepared().extent()), pitch)));
                    }
                    for (var piece : line.prepared().pieces().stream().sorted(Comparator.comparingInt(value -> value.segment().start())).toList()) {
                        var matrix = new java.awt.geom.AffineTransform(); matrix.translate(x, y); matrix.concatenate(piece.transform());
                        commands.add(new DrawCommand.Save());
                        commands.add(new DrawCommand.Transform(new com.mirakyux.export4j.graphics.AffineTransform(matrix.getScaleX(), matrix.getShearY(), matrix.getShearX(), matrix.getScaleY(), matrix.getTranslateX(), matrix.getTranslateY())));
                        List<DrawCommand> shaped = piece.run().commands(new Point(0, 0), color);
                        if (flow == Flow.HORIZONTAL || piece.segment().replacement()) {
                            boolean anchored = false;
                            for (var command : shaped) {
                                if (command instanceof DrawCommand.OutlineText outline) {
                                    commands.add(new DrawCommand.OutlineText(flow == Flow.HORIZONTAL || anchored ? "" : text.substring(piece.segment().start(), piece.segment().end()), outline.paths(), outline.color(), outline.logicalBounds())); anchored = true;
                                } else { commands.add(command); }
                            }
                        } else { commands.addAll(shaped); }
                        commands.add(new DrawCommand.Restore());
                        placements.add(new Placement(piece.segment().start(), piece.segment().end(), fragment, index, box, piece.segment().combine()));
                    }
                    if (line.end() > line.prepared().end()) {
                        if (flow != Flow.HORIZONTAL) { commands.add(new DrawCommand.OutlineText(text.substring(line.prepared().end(), line.end()), List.of(), color, box)); }
                        placements.add(new Placement(line.prepared().end(), line.end(), fragment, index, box, false));
                    }
                }
                parts.add(new ComponentLayout(context.width(), usedHeight, lines.size(), commands, diagnostics)); fragment++;
            }
            return new Result(parts, placements);
        }
    }

    private final class Work implements AutoCloseable {
        private final List<NativeFont> handles = new ArrayList<>();
        private final List<Map<Integer, Boolean>> coverage = new ArrayList<>();
        private int calls;
        private long utf16;
        private long glyphs;
        private long pixels;
        Work() {
            try {
                for (var font : fonts) { handles.add(new NativeFont(font)); coverage.add(new HashMap<>()); }
            } catch (RuntimeException | Error failure) { close(); throw failure; }
        }
        @Override public void close() { for (var handle : handles) { handle.close(); } }
        Prepared fit(int start, int end, double extent, double pitch) {
            if (start == end) { return new Prepared(start, end, List.of(), 0, 0, List.of(), new TreeSet<>()); }
            var breaker = BreakIterator.getLineInstance(Locale.forLanguageTag(options.language()));
            breaker.setText(text.substring(start, end));
            var boundaries = new ArrayList<Integer>();
            for (int value = breaker.first(); value != BreakIterator.DONE; value = breaker.next()) {
                int absolute = start + value;
                if (absolute > start && !insideCombined(absolute)) { boundaries.add(absolute); }
            }
            if (boundaries.isEmpty()) { boundaries.add(end); }
            int low = 0, probe = 1; Prepared best = null, first = null;
            while (true) {
                int count = Math.min(probe, boundaries.size());
                Prepared prepared = prepare(start, boundaries.get(count - 1));
                if (first == null) { first = prepared; }
                if (prepared.extent() > Math.nextUp(extent)) {
                    int high = count - 1;
                    while (low < high) {
                        int middle = (low + high + 1) >>> 1;
                        var candidate = prepare(start, boundaries.get(middle - 1));
                        if (candidate.extent() <= Math.nextUp(extent)) { low = middle; best = candidate; } else { high = middle - 1; }
                    }
                    break;
                }
                low = count; best = prepared;
                if (count == boundaries.size()) { break; }
                probe = Math.min(boundaries.size(), count * 2);
            }
            if (best == null) {
                var clusters = new ArrayList<>(Objects.requireNonNull(first).clusterEnds().stream().filter(value -> value > start && !insideCombined(value)).toList());
                low = 0; int high = clusters.size();
                while (low < high) {
                    int middle = (low + high + 1) >>> 1;
                    var candidate = prepare(start, clusters.get(middle - 1));
                    if (candidate.extent() <= Math.nextUp(extent)) { low = middle; best = candidate; } else { high = middle - 1; }
                }
            }
            if (best == null) { throw doesNotFit(start, "one shaped cluster exceeds the line or column extent"); }
            if (best.cross() > Math.nextUp(pitch)) { throw doesNotFit(start, "shaped ink exceeds explicit line or column pitch"); }
            return best;
        }
        Prepared prepare(int start, int end) {
            ExecutionContext.checkpoint();
            if (++calls > maxShapeCalls || (utf16 += end - start) > maxWorkUtf16) { throw OpenTypeShaper.budget("OpenType line measurement work budget exceeded"); }
            var segments = segments(start, end); var diagnostics = new ArrayList<TextDiagnostic>();
            if (flow == Flow.HORIZONTAL) {
                byte[] levels = new byte[segments.size()]; Object[] ordered = segments.toArray();
                for (int i = 0; i < levels.length; i++) { levels[i] = (byte) segments.get(i).level(); }
                Bidi.reorderVisually(levels, 0, ordered, 0, ordered.length);
                segments = new ArrayList<>(); for (var value : ordered) { segments.add((Segment) value); }
            }
            var pieces = new ArrayList<Piece>(); var clusterEnds = new TreeSet<Integer>(); double cursor = 0;
            var allBounds = new Bounds();
            for (var segment : segments) {
                var font = fonts.get(segment.font());
                String value = segment.replacement() ? new String(Character.toChars(placeholder)) : text.substring(segment.start(), segment.end());
                var direction = flow != Flow.HORIZONTAL && !segment.sideways() && !segment.combine() ? OpenTypeShaper.Direction.TOP_TO_BOTTOM
                        : (segment.level() & 1) != 0 ? OpenTypeShaper.Direction.RIGHT_TO_LEFT : OpenTypeShaper.Direction.LEFT_TO_RIGHT;
                var shaped = OpenTypeShaper.shape(font, value, options.withDirection(direction), color);
                glyphs += shaped.glyphs().size();
                for (var glyph : shaped.glyphs()) {
                    clusterEnds.add(segment.replacement() ? segment.end() : segment.start() + glyph.sourceEnd());
                    for (var command : glyph.colorCommands()) { if (command instanceof DrawCommand.Image image) { pixels += (long) image.image().width() * image.image().height(); } }
                }
                if (glyphs > 1_000_000 || pixels > 64_000_000) { throw OpenTypeShaper.budget("OpenType layout glyph or color pixel work budget exceeded"); }
                var bounds = bounds(shaped); var matrix = new java.awt.geom.AffineTransform(); double advance;
                if (flow == Flow.HORIZONTAL) {
                    matrix.translate(cursor, 0); advance = Math.max(shaped.xAdvance(), bounds.maxX);
                } else if (segment.combine()) {
                    double scale = Math.min(1, options.fontSize() / Math.max(.001, Math.max(bounds.width(), bounds.height())));
                    matrix.translate(-bounds.centerX() * scale, cursor + options.fontSize() / 2 - bounds.centerY() * scale); matrix.scale(scale, scale); advance = options.fontSize();
                } else if (segment.sideways()) {
                    matrix.translate(0, cursor); matrix.rotate(Math.PI / 2); advance = shaped.xAdvance();
                } else {
                    matrix.translate(0, cursor); advance = shaped.yAdvance();
                }
                pieces.add(new Piece(shaped, segment, matrix));
                bounds.appendTransformed(allBounds, matrix); cursor += advance;
                if (segment.font() != 0 || segment.replacement()) {
                    int codePoint = text.codePointAt(segment.start());
                    diagnostics.add(new TextDiagnostic(segment.replacement() ? TextDiagnostic.Code.MISSING_GLYPH_REPLACED : TextDiagnostic.Code.FONT_FALLBACK,
                            segment.start(), segment.end(), font.id(), segment.end() - segment.start() == Character.charCount(codePoint) ? codePoint : -1,
                            segment.replacement() ? placeholder : -1));
                }
            }
            double shiftX = flow == Flow.HORIZONTAL ? -Math.min(0, allBounds.minX) : -allBounds.centerX();
            double shiftY = flow == Flow.HORIZONTAL ? -allBounds.minY : -Math.min(0, allBounds.minY);
            var finalPieces = new ArrayList<Piece>();
            for (var piece : pieces) {
                var matrix = java.awt.geom.AffineTransform.getTranslateInstance(shiftX, shiftY); matrix.concatenate(piece.transform());
                finalPieces.add(new Piece(piece.run(), piece.segment(), matrix));
            }
            double extent = flow == Flow.HORIZONTAL ? Math.max(cursor, allBounds.maxX) + shiftX : Math.max(cursor, allBounds.maxY) + shiftY;
            double cross = flow == Flow.HORIZONTAL ? allBounds.height() : allBounds.width();
            return new Prepared(start, end, finalPieces, extent, cross, diagnostics, clusterEnds);
        }
        private List<Segment> segments(int start, int end) {
            var result = new ArrayList<Segment>(); String value = text.substring(start, end);
            var bidi = new Bidi(value, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT);
            var ends = graphemeEnds(value); int pos = start; Character.UnicodeScript previousScript = Character.UnicodeScript.COMMON;
            for (int localEnd : ends) {
                int next = start + localEnd;
                int current = pos;
                Span combine = combined.stream().filter(span -> span.start() == current).findFirst().orElse(null);
                if (combine != null) { next = combine.end(); }
                if (next <= pos) { continue; }
                if (insideCombined(pos)) { continue; }
                int choice = select(pos, next), level = bidi.getLevelAt(pos - start);
                boolean replacement = choice < 0;
                if (replacement) {
                    if (missingPolicy == MissingGlyphPolicy.ERROR) { throw new OpenTypeException(OpenTypeException.Code.MISSING_GLYPH, pos, "explicit font stack lacks a complete grapheme"); }
                    choice = placeholderFont();
                }
                int cp = text.codePointAt(pos); var script = Character.UnicodeScript.of(cp);
                if (script == Character.UnicodeScript.COMMON || script == Character.UnicodeScript.INHERITED) { script = previousScript; }
                boolean sideways = flow != Flow.HORIZONTAL && combine == null && (orientation == Orientation.SIDEWAYS || orientation == Orientation.MIXED && !upright(cp));
                Segment segment = new Segment(pos, next, choice, level, sideways, combine != null, replacement);
                if (!result.isEmpty()) {
                    var prior = result.getLast();
                    if (!replacement && !prior.replacement() && combine == null && !prior.combine() && prior.font() == choice && prior.level() == level && prior.sideways() == sideways && script == previousScript) {
                        result.set(result.size() - 1, new Segment(prior.start(), next, choice, level, sideways, false, false));
                    } else { result.add(segment); }
                } else { result.add(segment); }
                previousScript = script; pos = next;
            }
            return result;
        }
        private int select(int start, int end) {
            for (int font = 0; font < fonts.size(); font++) {
                boolean complete = true;
                for (int pos = start; pos < end;) {
                    int cp = text.codePointAt(pos);
                    if (!ignorable(cp) && !supports(font, cp)) { complete = false; break; }
                    pos += Character.charCount(cp);
                }
                if (complete) { return font; }
            }
            return -1;
        }
        private int placeholderFont() {
            for (int index = 0; index < fonts.size(); index++) { if (supports(index, placeholder)) { return index; } }
            throw new OpenTypeException(OpenTypeException.Code.MISSING_GLYPH, -1, "explicit font stack lacks the selected placeholder");
        }
        private boolean supports(int index, int cp) {
            return coverage.get(index).computeIfAbsent(cp, ignored -> {
                try (var stack = MemoryStack.stackPush()) { return HarfBuzz.hb_font_get_nominal_glyph(handles.get(index).font, cp, stack.ints(0)); }
            });
        }
    }

    private boolean insideCombined(int index) { return combined.stream().anyMatch(span -> index > span.start() && index < span.end()); }
    private static boolean ignorable(int cp) { return Character.getType(cp) == Character.FORMAT || cp >= 0xfe00 && cp <= 0xfe0f || cp >= 0xe0100 && cp <= 0xe01ef; }
    private static boolean upright(int cp) {
        var script = Character.UnicodeScript.of(cp);
        return switch (script) { case HAN, HIRAGANA, KATAKANA, HANGUL, BOPOMOFO, YI -> true; default -> cp >= 0x2e80 && cp <= 0x303f || cp >= 0xfe10 && cp <= 0xfe4f || cp >= 0xff01 && cp <= 0xff60 || cp >= 0x1f000; };
    }
    private static TreeSet<Integer> graphemeEnds(String text) {
        var breaker = BreakIterator.getCharacterInstance(Locale.ROOT); breaker.setText(text); var result = new TreeSet<Integer>();
        for (int end = breaker.first(); end != BreakIterator.DONE; end = breaker.next()) {
            if (end == 0) { continue; }
            if (end < text.length()) {
                int before = text.codePointBefore(end), after = text.codePointAt(end), type = Character.getType(after);
                String name = Character.getName(before);
                if (before == 0x200d || after == 0x200d || type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK || type == Character.ENCLOSING_MARK
                        || after >= 0x1f3fb && after <= 0x1f3ff || name != null && (name.contains("VIRAMA") || name.contains("HALANT") || name.contains("COENG") || name.contains("SAKOT"))) { continue; }
            }
            result.add(end);
        }
        return result;
    }
    private static Bounds bounds(OpenTypeShaper.Run run) {
        var result = new Bounds(); result.add(0, 0); result.add(run.xAdvance(), run.yAdvance());
        for (var glyph : run.glyphs()) {
            for (var path : glyph.outlines()) {
                for (var segment : path.segments()) {
                    switch (segment) {
                        case VectorPath.MoveTo value -> result.add(value.point().x() + glyph.x(), value.point().y() + glyph.y());
                        case VectorPath.LineTo value -> result.add(value.point().x() + glyph.x(), value.point().y() + glyph.y());
                        case VectorPath.CurveTo value -> {
                            result.add(value.control1().x() + glyph.x(), value.control1().y() + glyph.y()); result.add(value.control2().x() + glyph.x(), value.control2().y() + glyph.y()); result.add(value.end().x() + glyph.x(), value.end().y() + glyph.y());
                        }
                        case VectorPath.Close ignored -> { }
                    }
                }
            }
            for (var command : glyph.colorCommands()) {
                if (command instanceof DrawCommand.Image image) { result.add(glyph.x() + image.bounds().x(), glyph.y() + image.bounds().y()); result.add(glyph.x() + image.bounds().x() + image.bounds().width(), glyph.y() + image.bounds().y() + image.bounds().height()); }
            }
        }
        return result;
    }
    private static final class Bounds {
        private double minX = 0, minY = 0, maxX = 0, maxY = 0;
        void add(double x, double y) { minX = Math.min(minX, x); minY = Math.min(minY, y); maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); }
        double width() { return maxX - minX; } double height() { return maxY - minY; }
        double centerX() { return (minX + maxX) / 2; } double centerY() { return (minY + maxY) / 2; }
        void appendTransformed(Bounds target, java.awt.geom.AffineTransform transform) {
            var points = new double[] {minX, minY, maxX, minY, maxX, maxY, minX, maxY}; transform.transform(points, 0, points, 0, 4);
            for (int i = 0; i < points.length; i += 2) { target.add(points[i], points[i + 1]); }
        }
    }
    private static OpenTypeException doesNotFit(int source, String message) { return new OpenTypeException(OpenTypeException.Code.DOES_NOT_FIT, source, message); }

    /** Task-confined configuration; fonts and feature lists are snapshotted by build(). */
    public static final class Builder {
        private final String text;
        private final List<OpenTypeFont> fonts;
        private Flow flow = Flow.HORIZONTAL;
        private Orientation orientation = Orientation.MIXED;
        private OpenTypeShaper.Options options = OpenTypeShaper.Options.of(12);
        private double lineAdvance;
        private final List<Span> combined = new ArrayList<>();
        private RgbColor color = RgbColor.BLACK;
        private MissingGlyphPolicy missingPolicy = MissingGlyphPolicy.ERROR;
        private int placeholder = 0x25a1;
        private int maxShapeCalls = 4096;
        private int maxWorkUtf16 = 4_000_000;
        private Builder(String text, List<OpenTypeFont> fonts) { this.text = Objects.requireNonNull(text, "text"); this.fonts = List.copyOf(fonts); }
        public Builder flow(Flow value) { flow = Objects.requireNonNull(value, "flow"); return this; }
        public Builder orientation(Orientation value) { orientation = Objects.requireNonNull(value, "orientation"); return this; }
        public Builder options(OpenTypeShaper.Options value) { options = Objects.requireNonNull(value, "options"); return this; }
        public Builder fontSize(double size) { options = new OpenTypeShaper.Options(size, options.direction(), options.script(), options.language(), options.features(), options.colorDpi(), options.maxColorPixels(), options.maxUtf16()); return this; }
        /** Fixed line/column pitch in pt. Ink overflow is rejected rather than clipped. */
        public Builder lineAdvance(double value) { if (!Double.isFinite(value) || value <= 0) { throw new IllegalArgumentException("invalid text pitch"); } lineAdvance = value; return this; }
        /** Explicit source UTF-16 group, rendered horizontally within one em of vertical flow. */
        public Builder tateChuYoko(int start, int end) { combined.add(new Span(start, end)); return this; }
        public Builder color(RgbColor value) { color = Objects.requireNonNull(value, "color"); return this; }
        public Builder missingGlyphPolicy(MissingGlyphPolicy value, int replacement) {
            missingPolicy = Objects.requireNonNull(value, "missingGlyphPolicy");
            if (!Character.isValidCodePoint(replacement) || replacement >= 0xd800 && replacement <= 0xdfff || Character.isISOControl(replacement)) { throw new IllegalArgumentException("invalid placeholder scalar"); }
            placeholder = replacement; return this;
        }
        public Builder workBudget(int shapeCalls, int utf16Units) {
            if (shapeCalls <= 0 || shapeCalls > 100_000 || utf16Units <= 0 || utf16Units > 16_000_000) { throw new IllegalArgumentException("invalid OpenType work budget"); }
            maxShapeCalls = shapeCalls; maxWorkUtf16 = utf16Units; return this;
        }
        public OpenTypeText build() { return new OpenTypeText(this); }
    }
}
