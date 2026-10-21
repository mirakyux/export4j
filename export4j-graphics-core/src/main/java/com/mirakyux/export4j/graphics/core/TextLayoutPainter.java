// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.graphics.core;

import java.util.Objects;
import com.mirakyux.export4j.graphics.DrawingSurface;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.VectorPath;
import java.util.List;
import com.mirakyux.export4j.text.GlyphRun;
import com.mirakyux.export4j.text.ParagraphLayout;
import com.mirakyux.export4j.text.TextLine;
import com.mirakyux.export4j.graphics.Rect;
import java.util.ArrayList;

/** Converts immutable text layout to drawing commands without measuring or changing line breaks. */
public final class TextLayoutPainter {
    private TextLayoutPainter() {
    }

    /** Draws relative to a top-left origin in points; all arguments must be non-null. */
    public static void draw(DrawingSurface surface, ParagraphLayout layout, Point origin, RgbColor color) {
        Objects.requireNonNull(surface, "surface");
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(color, "color");
        var images = new java.util.HashMap<String, com.mirakyux.export4j.graphics.RgbImage>();
        for (TextLine line : layout.lines()) {
            if (!line.fields().isEmpty()) { throw new IllegalArgumentException("inline fields must be resolved before painting"); }
            double x = origin.x() + line.offsetX();
            for (var box : line.images()) {
                var item = box.image();
                var image = images.computeIfAbsent(item.resource().id(), ignored -> {
                    try { return ImageDecoder.decode(item.resource().bytes(), com.mirakyux.export4j.model.ImageResource.MAX_BYTES,
                            com.mirakyux.export4j.model.ImageResource.MAX_PIXELS); }
                    catch (java.io.IOException failure) { throw new IllegalArgumentException("inline image decode failed", failure); }
                });
                surface.drawImage(image, new Rect(x + box.x(), origin.y() + line.baselineY() + item.baselineShift() - item.height(), item.width(), item.height()));
            }
            if (!line.outlines().isEmpty()) {
                double baseline = origin.y() + line.baselineY();
                String text = line.outlines().stream().map(com.mirakyux.export4j.text.TextOutline::logicalText)
                        .collect(java.util.stream.Collectors.joining());
                double height = layout.height() / layout.lines().size();
                surface.drawOutlinedText(text, List.of(), color, new Rect(x,
                        baseline - layout.lines().getFirst().baselineY(), Math.max(1, line.width()), height));
                for (var outline : line.outlines()) {
                    var ink = outline.rgb() < 0 ? color : new RgbColor((outline.rgb() >> 16 & 255) / 255.0,
                            (outline.rgb() >> 8 & 255) / 255.0, (outline.rgb() & 255) / 255.0);
                    List<VectorPath.Segment> segments = new ArrayList<>();
                    for (var segment : outline.segments()) {
                        var c = segment.coordinates();
                        segments.add(switch (segment.operation()) {
                            case MOVE -> new VectorPath.MoveTo(new Point(x + c.get(0), baseline + c.get(1)));
                            case LINE -> new VectorPath.LineTo(new Point(x + c.get(0), baseline + c.get(1)));
                            case CURVE -> new VectorPath.CurveTo(new Point(x + c.get(0), baseline + c.get(1)),
                                    new Point(x + c.get(2), baseline + c.get(3)), new Point(x + c.get(4), baseline + c.get(5)));
                            case CLOSE -> new VectorPath.Close();
                        });
                    }
                    if (!segments.isEmpty()) { surface.fillPath(new VectorPath(segments), ink); }
                }
            }
            for (GlyphRun run : line.runs()) {
                var ink = run.rgb() < 0 ? color : new RgbColor((run.rgb() >> 16 & 255) / 255.0,
                        (run.rgb() >> 8 & 255) / 255.0, (run.rgb() & 255) / 255.0);
                double baseline = origin.y() + line.baselineY() + run.baselineShift();
                surface.drawGlyphRun(run, new Point(x, baseline), ink);
                if (run.underline()) {
                    line(surface, x, baseline + run.fontSize() * 0.1, run.width(), ink, run.fontSize());
                }
                if (run.strikeThrough()) {
                    line(surface, x, baseline - run.fontSize() * 0.3, run.width(), ink, run.fontSize());
                }
                x += run.width();
            }
        }
    }

    private static void line(DrawingSurface surface, double x, double y, double width, RgbColor color, double size) {
        surface.strokePath(new VectorPath(List.of(new VectorPath.MoveTo(new Point(x, y)),
                new VectorPath.LineTo(new Point(x + width, y)))), color, Math.max(0.3, size / 18));
    }
}
