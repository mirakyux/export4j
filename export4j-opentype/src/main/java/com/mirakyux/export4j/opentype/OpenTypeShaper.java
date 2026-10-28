// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.opentype;

import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.VectorPath;
import com.mirakyux.export4j.graphics.core.FontOutlines;
import com.mirakyux.export4j.model.ExecutionContext;
import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.harfbuzz.HarfBuzz;
import org.lwjgl.util.harfbuzz.hb_feature_t;

/**
 * Synchronous explicit shaping of one directional/script run. Performs GSUB/GPOS, variations and
 * color-paint evaluation before backend rendering. It does not perform the Unicode bidi algorithm;
 * use {@link OpenTypeText} for mixed-direction paragraphs and pagination.
 */
public final class OpenTypeShaper {
    public enum Direction { AUTO, LEFT_TO_RIGHT, RIGHT_TO_LEFT, TOP_TO_BOTTOM, BOTTOM_TO_TOP }
    /** Points, BCP-47 language and optional ISO 15924 script; empty script asks HarfBuzz to infer it.
     * Color fonts are rasterized at colorDpi with at most maxColorPixels per glyph. */
    public record Options(double fontSize, Direction direction, String script, String language,
            List<String> features, int colorDpi, int maxColorPixels, int maxUtf16) {
        public Options {
            Objects.requireNonNull(direction, "direction"); Objects.requireNonNull(script, "script"); Objects.requireNonNull(language, "language");
            features = List.copyOf(features);
            if (!Double.isFinite(fontSize) || fontSize < 0.001 || fontSize > 10000 || (!script.isEmpty() && !script.matches("[A-Za-z]{4}"))
                    || !language.matches("[A-Za-z0-9-]{1,63}") || features.size() > 64
                    || colorDpi < 72 || colorDpi > 1200 || maxColorPixels <= 0 || maxColorPixels > 16_000_000
                    || maxUtf16 <= 0 || maxUtf16 > 1_000_000) { throw new IllegalArgumentException("invalid OpenType shaping options"); }
            for (String feature : features) {
                if (feature.length() > 128 || !feature.matches("[A-Za-z0-9_+\\-=\\[\\]: ]+")) { throw new IllegalArgumentException("invalid OpenType feature"); }
            }
        }
        public static Options of(double size) { return new Options(size, Direction.AUTO, "", "und", List.of(), 144, 4_000_000, 100_000); }
        public Options withDirection(Direction value) { return new Options(fontSize, value, script, language, features, colorDpi, maxColorPixels, maxUtf16); }
        public Options withFeatures(List<String> values) { return new Options(fontSize, direction, script, language, values, colorDpi, maxColorPixels, maxUtf16); }
    }
    /** Immutable glyph geometry. Coordinates use the run's pen origin, X right/Y down, in points. */
    public record Glyph(int glyphId, int sourceStart, int sourceEnd, double x, double y,
            double xAdvance, double yAdvance, List<VectorPath> outlines, List<DrawCommand> colorCommands) {
        public Glyph { outlines = List.copyOf(outlines); colorCommands = List.copyOf(colorCommands); }
    }
    /** Exact shaped run; glyph order is visual, cluster source offsets are original UTF-16. */
    public record Run(String text, String fontId, Direction direction, double fontSize,
            double xAdvance, double yAdvance, List<Glyph> glyphs) {
        public Run { glyphs = List.copyOf(glyphs); }
        /** Drawing keeps one logical-text anchor for the shaped run; per-glyph source is retained in glyphs. */
        public List<DrawCommand> commands(Point origin, RgbColor foreground) {
            Objects.requireNonNull(origin, "origin"); Objects.requireNonNull(foreground, "foreground");
            var result = new ArrayList<DrawCommand>();
            if (!glyphs.isEmpty()) {
                var cluster = glyphs;
                var paths = new ArrayList<VectorPath>();
                double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
                for (var glyph : cluster) {
                    double x = origin.x() + glyph.x(), y = origin.y() + glyph.y();
                    for (var path : glyph.outlines()) { paths.add(translate(path, x, y)); }
                    minX = Math.min(minX, x); minY = Math.min(minY, y - fontSize);
                    maxX = Math.max(maxX, x + Math.max(Math.abs(glyph.xAdvance()), fontSize * 0.25)); maxY = Math.max(maxY, y + Math.max(Math.abs(glyph.yAdvance()), fontSize * 0.25));
                }
                result.add(new DrawCommand.OutlineText(text, paths, foreground,
                        new Rect(minX, minY, Math.max(0.001, maxX - minX), Math.max(0.001, maxY - minY))));
                for (var glyph : cluster) {
                    if (!glyph.colorCommands().isEmpty()) {
                        result.add(new DrawCommand.Save());
                        result.add(new DrawCommand.Transform(com.mirakyux.export4j.graphics.AffineTransform.translation(origin.x() + glyph.x(), origin.y() + glyph.y())));
                        result.addAll(glyph.colorCommands());
                        result.add(new DrawCommand.Restore());
                    }
                }
            }
            return List.copyOf(result);
        }
    }
    private OpenTypeShaper() { }

    public static Run shape(OpenTypeFont font, String text, Options options) {
        return shape(font, text, options, RgbColor.BLACK);
    }
    /** Explicit foreground is also passed to palette entries that reference the current text color. */
    public static Run shape(OpenTypeFont font, String text, Options options, RgbColor foreground) {
        Objects.requireNonNull(font, "font"); Objects.requireNonNull(text, "text"); Objects.requireNonNull(options, "options");
        Objects.requireNonNull(foreground, "foreground");
        validateText(text, options.maxUtf16());
        ExecutionContext.checkpoint();
        try (var nativeFont = new NativeFont(font); var stack = MemoryStack.stackPush(); var outlines = new NativeOutline()) {
            long buffer = HarfBuzz.hb_buffer_create();
            try {
                HarfBuzz.hb_buffer_set_cluster_level(buffer, HarfBuzz.HB_BUFFER_CLUSTER_LEVEL_MONOTONE_GRAPHEMES);
                HarfBuzz.hb_buffer_add_utf16(buffer, text, 0, -1);
                if (options.direction() != Direction.AUTO) { HarfBuzz.hb_buffer_set_direction(buffer, nativeDirection(options.direction())); }
                if (!options.script().isEmpty()) { HarfBuzz.hb_buffer_set_script(buffer, HarfBuzz.hb_script_from_string(options.script())); }
                HarfBuzz.hb_buffer_set_language(buffer, HarfBuzz.hb_language_from_string(options.language()));
                HarfBuzz.hb_buffer_guess_segment_properties(buffer);
                var features = hb_feature_t.calloc(options.features().size(), stack);
                for (int index = 0; index < options.features().size(); index++) {
                    if (!HarfBuzz.hb_feature_from_string(options.features().get(index), features.get(index))) { throw new IllegalArgumentException("invalid OpenType feature syntax"); }
                }
                HarfBuzz.hb_shape(nativeFont.font, buffer, features);
                int count = HarfBuzz.hb_buffer_get_length(buffer);
                if (count > 1_000_000 || count > Math.max(32L, text.length() * 32L)) { throw budget("shaped glyph budget exceeded"); }
                var infos = HarfBuzz.hb_buffer_get_glyph_infos(buffer);
                var positions = HarfBuzz.hb_buffer_get_glyph_positions(buffer);
                if (count > 0 && (infos == null || positions == null)) { throw budget("native shaping allocation failed"); }
                var ends = new TreeSet<Integer>(); ends.add(text.length());
                for (int i = 0; i < count; i++) { ends.add(Objects.requireNonNull(infos).get(i).cluster()); }
                var result = new ArrayList<Glyph>();
                double scale = options.fontSize() / font.unitsPerEm(), penX = 0, penY = 0;
                long segments = 0, colorPixels = 0;
                for (int i = 0; i < count; i++) {
                    ExecutionContext.checkpoint();
                    var info = Objects.requireNonNull(infos).get(i); var position = Objects.requireNonNull(positions).get(i);
                    int start = info.cluster(); Integer end = ends.higher(start);
                    if (start < 0 || start >= text.length() || end == null || !boundary(text, start) || !boundary(text, end)) {
                        throw new OpenTypeException(OpenTypeException.Code.INVALID_TEXT, start, "invalid shaped UTF-16 cluster");
                    }
                    if (info.codepoint() == 0) { throw new OpenTypeException(OpenTypeException.Code.MISSING_GLYPH, start, "explicit font lacks the shaped cluster"); }
                    var shape = outlines.glyph(nativeFont.font, info.codepoint());
                    var transformed = AffineTransform.getScaleInstance(scale, -scale).createTransformedShape(shape);
                    var paths = FontOutlines.paths(transformed);
                    segments += paths.stream().mapToLong(path -> path.segments().size()).sum();
                    if (segments > 2_000_000) { throw budget("shaped outline budget exceeded"); }
                    List<DrawCommand> color = font.color() ? NativeColor.paint(nativeFont, info.codepoint(), font.palette(), options, foreground) : List.of();
                    for (var command : color) {
                        if (command instanceof DrawCommand.Image image) { colorPixels += (long) image.image().width() * image.image().height(); }
                    }
                    if (colorPixels > 64_000_000) { throw budget("shaped run cumulative color pixel budget exceeded"); }
                    if (font.color()) { paths = List.of(); }
                    result.add(new Glyph(info.codepoint(), start, end, penX + position.x_offset() * scale,
                            penY - position.y_offset() * scale, position.x_advance() * scale, -position.y_advance() * scale, paths, color));
                    penX += position.x_advance() * scale; penY -= position.y_advance() * scale;
                }
                return new Run(text, font.id(), direction(HarfBuzz.hb_buffer_get_direction(buffer)), options.fontSize(), penX, penY, result);
            } finally { HarfBuzz.hb_buffer_destroy(buffer); }
        }
    }

    static void validateText(String text, int maxUtf16) {
        if (text.length() > maxUtf16) { throw budget("OpenType source UTF-16 budget exceeded"); }
        for (int pos = 0; pos < text.length();) {
            int cp = text.codePointAt(pos);
            if (cp >= 0xd800 && cp <= 0xdfff || Character.isISOControl(cp)) {
                throw new OpenTypeException(OpenTypeException.Code.INVALID_TEXT, pos, "invalid scalar or control in shaping run");
            }
            pos += Character.charCount(cp);
        }
    }
    static boolean boundary(String text, int index) { return index == 0 || index == text.length() || !Character.isLowSurrogate(text.charAt(index)); }
    private static int nativeDirection(Direction value) {
        return switch (value) { case AUTO -> HarfBuzz.HB_DIRECTION_INVALID; case LEFT_TO_RIGHT -> HarfBuzz.HB_DIRECTION_LTR; case RIGHT_TO_LEFT -> HarfBuzz.HB_DIRECTION_RTL; case TOP_TO_BOTTOM -> HarfBuzz.HB_DIRECTION_TTB; case BOTTOM_TO_TOP -> HarfBuzz.HB_DIRECTION_BTT; };
    }
    private static Direction direction(int value) {
        if (value == HarfBuzz.HB_DIRECTION_RTL) { return Direction.RIGHT_TO_LEFT; }
        if (value == HarfBuzz.HB_DIRECTION_TTB) { return Direction.TOP_TO_BOTTOM; }
        if (value == HarfBuzz.HB_DIRECTION_BTT) { return Direction.BOTTOM_TO_TOP; }
        return Direction.LEFT_TO_RIGHT;
    }
    private static VectorPath translate(VectorPath path, double x, double y) {
        return new VectorPath(path.segments().stream().map(segment -> switch (segment) {
            case VectorPath.MoveTo value -> (VectorPath.Segment) new VectorPath.MoveTo(new Point(value.point().x() + x, value.point().y() + y));
            case VectorPath.LineTo value -> new VectorPath.LineTo(new Point(value.point().x() + x, value.point().y() + y));
            case VectorPath.CurveTo value -> new VectorPath.CurveTo(new Point(value.control1().x() + x, value.control1().y() + y), new Point(value.control2().x() + x, value.control2().y() + y), new Point(value.end().x() + x, value.end().y() + y));
            case VectorPath.Close ignored -> new VectorPath.Close();
        }).toList());
    }
    static OpenTypeException budget(String message) { return new OpenTypeException(OpenTypeException.Code.BUDGET_EXCEEDED, -1, message); }
}
