// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.graphics.core;

import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.geom.PathIterator;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import com.mirakyux.export4j.font.FontData;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.VectorPath;
import com.mirakyux.export4j.text.GlyphRun;

/** Explicit physical-font bridge to JDK Java2D. Owns no system font lookup or global caches. */
public final class FontOutlines {
    private FontOutlines() { }

    public static Font physicalFont(FontData data, double size) {
        if (!Double.isFinite(size) || size <= 0 || size > Float.MAX_VALUE) { throw new IllegalArgumentException("invalid font size"); }
        try {
            return Font.createFont(Font.TRUETYPE_FONT, new ByteArrayInputStream(data.bytes())).deriveFont((float) size);
        } catch (IOException | FontFormatException failure) {
            throw new IllegalArgumentException("physical font cannot be loaded", failure);
        }
    }

    /** Converts measured glyph IDs directly; advances remain those in the Display List. */
    public static List<VectorPath> glyphs(GlyphRun run, Point baseline) {
        var font = physicalFont(run.font(), run.fontSize());
        return glyphs(run, baseline, font);
    }

    static List<VectorPath> glyphs(GlyphRun run, Point baseline, Font font) {
        int[] ids = run.glyphs().stream().mapToInt(com.mirakyux.export4j.text.PositionedGlyph::glyphId).toArray();
        var vector = font.createGlyphVector(new FontRenderContext(null, true, true), ids);
        double x = baseline.x();
        for (int index = 0; index < ids.length; index++) {
            vector.setGlyphPosition(index, new java.awt.geom.Point2D.Double(x, baseline.y()));
            x += run.glyphs().get(index).advance();
        }
        return paths(vector.getOutline());
    }

    /** Converts quadratic curves exactly to cubic paths; empty shapes produce no paths. */
    public static List<VectorPath> paths(Shape shape) {
        var segments = new ArrayList<VectorPath.Segment>();
        double[] c = new double[6];
        double x = 0;
        double y = 0;
        for (var iterator = shape.getPathIterator(null); !iterator.isDone(); iterator.next()) {
            switch (iterator.currentSegment(c)) {
                case PathIterator.SEG_MOVETO -> {
                    segments.add(new VectorPath.MoveTo(new Point(c[0], c[1])));
                    x = c[0]; y = c[1];
                }
                case PathIterator.SEG_LINETO -> {
                    segments.add(new VectorPath.LineTo(new Point(c[0], c[1])));
                    x = c[0]; y = c[1];
                }
                case PathIterator.SEG_QUADTO -> {
                    segments.add(new VectorPath.CurveTo(new Point(x + (c[0] - x) * 2 / 3, y + (c[1] - y) * 2 / 3),
                            new Point(c[2] + (c[0] - c[2]) * 2 / 3, c[3] + (c[1] - c[3]) * 2 / 3), new Point(c[2], c[3])));
                    x = c[2]; y = c[3];
                }
                case PathIterator.SEG_CUBICTO -> {
                    segments.add(new VectorPath.CurveTo(new Point(c[0], c[1]), new Point(c[2], c[3]), new Point(c[4], c[5])));
                    x = c[4]; y = c[5];
                }
                case PathIterator.SEG_CLOSE -> segments.add(new VectorPath.Close());
                default -> throw new IllegalArgumentException("unsupported path segment");
            }
            if (segments.size() > 2_000_000) { throw new IllegalArgumentException("outline segment budget exceeded"); }
        }
        return segments.isEmpty() ? List.of() : List.of(new VectorPath(segments));
    }
}
