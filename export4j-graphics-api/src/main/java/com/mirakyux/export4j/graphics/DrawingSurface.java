// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.graphics;

import com.mirakyux.export4j.text.GlyphRun;

/**
 * Precision drawing contract. Coordinates and dimensions are in points, origin top-left.
 * Text uses a baseline position. Arguments are non-null and validated on entry.
 * Mutable implementations are task-confined unless explicitly documented otherwise.
 */
public interface DrawingSurface {
    default DrawingSurface fillPath(VectorPath path, Paint paint, DrawCommand.FillRule rule) { throw new UnsupportedOperationException("surface does not support explicit paint"); }
    default DrawingSurface strokePath(VectorPath path, Paint paint, double width) { throw new UnsupportedOperationException("surface does not support explicit paint"); }
    default DrawingSurface clipPath(VectorPath path, DrawCommand.FillRule rule) { throw new UnsupportedOperationException("surface does not support path clipping"); }
    default DrawingSurface blend(BlendMode mode) { throw new UnsupportedOperationException("surface does not support blend modes"); }
    default DrawingSurface group(Rect bounds, java.util.List<DrawCommand> commands, double opacity, BlendMode blend) { throw new UnsupportedOperationException("surface does not support isolated groups"); }
    DrawingSurface fillRect(Rect bounds, RgbColor color);

    DrawingSurface fillPath(VectorPath path, RgbColor color);

    DrawingSurface strokePath(VectorPath path, RgbColor color, double width);

    DrawingSurface drawText(String text, Point baseline, TextStyle style);

    DrawingSurface drawGlyphRun(GlyphRun run, Point baseline, RgbColor color);

    /** Draws already-shaped outlines and their logical reading text; implementations must preserve both. */
    default DrawingSurface drawOutlinedText(String text, java.util.List<VectorPath> paths, RgbColor color, Rect bounds) {
        throw new UnsupportedOperationException("surface does not support outlined logical text");
    }

    DrawingSurface drawImage(RgbImage image, Rect bounds);

    DrawingSurface save();

    DrawingSurface restore();

    DrawingSurface transform(AffineTransform matrix);

    DrawingSurface clip(Rect bounds);

    /** Replaces fill/text/image and stroke alpha in [0,1]. Save/Restore scope this state.
     * Paints composite individually; this does not create an isolated transparency group. */
    default DrawingSurface opacity(double fill, double stroke) {
        throw new UnsupportedOperationException("surface does not support paint opacity");
    }
}
