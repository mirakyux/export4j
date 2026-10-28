// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.graphics.core;

import java.awt.Font;
import java.awt.image.BufferedImage;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.mirakyux.export4j.font.FontData;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.FontFamily;
import com.mirakyux.export4j.graphics.RgbImage;
import com.mirakyux.export4j.graphics.VectorPath;

/** Shared bounded conversion for static backends. No hidden fonts; document interactions must be handled separately. */
public final class StaticBackendSupport {
    private StaticBackendSupport() { }

    /** Resolves only existing glyph IDs, never reshapes or changes line/page geometry. */
    public static List<DisplayPage> outlines(DisplayDocument document, Map<FontFamily, FontData> standardFonts) {
        var extras = document.extras();
        if (!extras.fields().isEmpty() || !extras.comments().isEmpty() || !extras.links().isEmpty() || !extras.bookmarks().isEmpty() || !extras.externalLinks().isEmpty()) {
            throw new IllegalArgumentException("static backends require an explicit display without interactive extras");
        }
        if (document.pages().size() > 10_000) { throw new IllegalArgumentException("static page budget exceeded"); }
        Map<String, Font> fonts = new HashMap<>();
        List<DisplayPage> pages = new ArrayList<>();
        long segments = 0;
        long pixels = 0;
        long points = 0;
        long commands = 0;
        for (var page : document.pages()) {
            if (page.size().width() > 14_400 || page.size().height() > 14_400) { throw new IllegalArgumentException("static page dimension budget exceeded"); }
            final long[] nested = new long[1]; GraphicsResources.walk(page.commands(), ignored -> nested[0]++);
            commands += nested[0];
            if (commands > 2_000_000) { throw new IllegalArgumentException("static command budget exceeded"); }
            var converted = new ArrayList<DrawCommand>();
            for (var original : nestedOutlines(page.commands(), fonts, standardFonts, 0)) {
                DrawCommand command = original;
                if (original instanceof DrawCommand.GlyphText glyph) {
                    var run = glyph.run();
                    var font = fonts.computeIfAbsent(run.font().id() + ":" + run.fontSize(), ignored -> FontOutlines.physicalFont(run.font(), run.fontSize()));
                    command = new DrawCommand.OutlineText(run.text(), FontOutlines.glyphs(run, glyph.baseline(), font), glyph.color(),
                            new com.mirakyux.export4j.graphics.Rect(glyph.baseline().x(), glyph.baseline().y() - run.fontSize(), Math.max(1, run.width()), run.fontSize() * 1.5));
                } else if (original instanceof DrawCommand.Text text) {
                    var data = standardFonts.get(text.style().family());
                    if (data == null) { throw new IllegalArgumentException("standard text needs an explicit physical font mapping for static output"); }
                    var font = fonts.computeIfAbsent(data.id() + ":" + text.style().fontSize(), ignored -> FontOutlines.physicalFont(data, text.style().fontSize()));
                    if (font.canDisplayUpTo(text.text()) != -1) { throw new IllegalArgumentException("static font mapping is missing glyphs"); }
                    var vector = font.createGlyphVector(new java.awt.font.FontRenderContext(null, true, true), text.text());
                    command = new DrawCommand.OutlineText(text.text(), FontOutlines.paths(vector.getOutline((float) text.baseline().x(), (float) text.baseline().y())), text.style().color());
                }
                converted.add(command);
            }
            long[] totals = new long[4];
            GraphicsResources.walk(converted, command -> {
                if (command instanceof DrawCommand.FillPath fill) { totals[0] += fill.path().segments().size(); }
                if (command instanceof DrawCommand.StrokePath stroke) { totals[0] += stroke.path().segments().size(); }
                if (command instanceof DrawCommand.PaintFill fill) { totals[0] += fill.path().segments().size(); }
                if (command instanceof DrawCommand.PaintStroke stroke) { totals[0] += stroke.path().segments().size(); }
                if (command instanceof DrawCommand.ClipPath clip) { totals[0] += clip.path().segments().size(); }
                if (command instanceof DrawCommand.Image image) { totals[1] += (long) image.image().width() * image.image().height(); }
                if (command instanceof DrawCommand.OutlineText text) { totals[0] += text.paths().stream().mapToLong(path -> path.segments().size()).sum(); totals[2] += text.logicalText().codePointCount(0, text.logicalText().length()); }
                var paint = command instanceof DrawCommand.PaintFill fill ? fill.paint() : command instanceof DrawCommand.PaintStroke stroke ? stroke.paint() : null;
                if (paint != null) { for (var color : GraphicsResources.colors(paint)) { if (color instanceof com.mirakyux.export4j.graphics.IccColor icc) { totals[3] += icc.profile().length; } if (color instanceof com.mirakyux.export4j.graphics.SpotColor spot && spot.alternate() instanceof com.mirakyux.export4j.graphics.IccColor icc) { totals[3] += icc.profile().length; } } }
            });
            segments += totals[0]; pixels += totals[1]; points += totals[2];
            if (segments > 5_000_000 || pixels > 50_000_000 || points > 2_000_000 || totals[3] > 64L * 1024 * 1024) { throw new IllegalArgumentException("static drawing resource budget exceeded"); }
            pages.add(new DisplayPage(page.size(), converted));
        }
        return List.copyOf(pages);
    }

    private static List<DrawCommand> nestedOutlines(List<DrawCommand> commands, Map<String, Font> fonts,
            Map<FontFamily, FontData> standardFonts, int depth) {
        if (depth > 32) { throw new IllegalArgumentException("nested static depth exceeded"); }
        var result = new ArrayList<DrawCommand>();
        for (var command : commands) {
            if (command instanceof DrawCommand.Group group) {
                command = new DrawCommand.Group(group.bounds(), outlineFragment(group.commands(), fonts, standardFonts, depth + 1), group.opacity(), group.blend());
            } else if (command instanceof DrawCommand.PaintFill fill && fill.paint() instanceof com.mirakyux.export4j.graphics.Paint.Tile tile) {
                command = new DrawCommand.PaintFill(fill.path(), new com.mirakyux.export4j.graphics.Paint.Tile(tile.bounds(), tile.xStep(), tile.yStep(), tile.transform(), outlineFragment(tile.commands(), fonts, standardFonts, depth + 1)), fill.rule());
            } else if (command instanceof DrawCommand.PaintStroke stroke && stroke.paint() instanceof com.mirakyux.export4j.graphics.Paint.Tile tile) {
                command = new DrawCommand.PaintStroke(stroke.path(), new com.mirakyux.export4j.graphics.Paint.Tile(tile.bounds(), tile.xStep(), tile.yStep(), tile.transform(), outlineFragment(tile.commands(), fonts, standardFonts, depth + 1)), stroke.width());
            }
            result.add(command);
        }
        return result;
    }
    private static List<DrawCommand> outlineFragment(List<DrawCommand> commands, Map<String, Font> fonts, Map<FontFamily, FontData> standardFonts, int depth) {
        var result = new ArrayList<DrawCommand>();
        for (var command : nestedOutlines(commands, fonts, standardFonts, depth)) {
            if (command instanceof DrawCommand.GlyphText glyph) {
                var run = glyph.run(); var font = fonts.computeIfAbsent(run.font().id() + ":" + run.fontSize(), ignored -> FontOutlines.physicalFont(run.font(), run.fontSize()));
                command = new DrawCommand.OutlineText(run.text(), FontOutlines.glyphs(run, glyph.baseline(), font), glyph.color(), new com.mirakyux.export4j.graphics.Rect(glyph.baseline().x(), glyph.baseline().y() - run.fontSize(), Math.max(1, run.width()), run.fontSize() * 1.5));
            } else if (command instanceof DrawCommand.Text text) {
                var data = standardFonts.get(text.style().family()); if (data == null) { throw new IllegalArgumentException("nested text needs an explicit font mapping"); }
                var font = fonts.computeIfAbsent(data.id() + ":" + text.style().fontSize(), ignored -> FontOutlines.physicalFont(data, text.style().fontSize()));
                if (font.canDisplayUpTo(text.text()) != -1) { throw new IllegalArgumentException("static font mapping is missing glyphs"); }
                var vector = font.createGlyphVector(new java.awt.font.FontRenderContext(null, true, true), text.text());
                command = new DrawCommand.OutlineText(text.text(), FontOutlines.paths(vector.getOutline((float) text.baseline().x(), (float) text.baseline().y())), text.style().color());
            }
            result.add(command);
        }
        return result;
    }

    public static Path2D.Double path(VectorPath vector) {
        var path = new Path2D.Double(Path2D.WIND_NON_ZERO);
        for (var segment : vector.segments()) {
            switch (segment) {
                case VectorPath.MoveTo move -> path.moveTo(move.point().x(), move.point().y());
                case VectorPath.LineTo line -> path.lineTo(line.point().x(), line.point().y());
                case VectorPath.CurveTo curve -> path.curveTo(curve.control1().x(), curve.control1().y(), curve.control2().x(), curve.control2().y(), curve.end().x(), curve.end().y());
                case VectorPath.Close ignored -> path.closePath();
            }
        }
        return path;
    }

    public static BufferedImage image(RgbImage image) {
        byte[] rgb = image.pixels();
        byte[] alpha = image.alpha();
        var result = new BufferedImage(image.width(), image.height(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.height(); y++) {
            for (int x = 0; x < image.width(); x++) {
                int pixel = y * image.width() + x;
                int index = pixel * 3;
                int a = alpha.length == 0 ? 255 : Byte.toUnsignedInt(alpha[pixel]);
                result.setRGB(x, y, a << 24 | Byte.toUnsignedInt(rgb[index]) << 16 | Byte.toUnsignedInt(rgb[index + 1]) << 8 | Byte.toUnsignedInt(rgb[index + 2]));
            }
        }
        return result;
    }
}
