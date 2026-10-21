// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.graphics;

import java.util.Objects;
import com.mirakyux.export4j.text.GlyphRun;

/**
 * Immutable precision drawing operations. All coordinates and stroke widths are in points.
 * Operations execute in list order. Save/Restore scope transforms and clipping.
 */
public sealed interface DrawCommand permits DrawCommand.FillRect, DrawCommand.FillPath,
        DrawCommand.StrokePath, DrawCommand.Text, DrawCommand.Image, DrawCommand.Save,
        DrawCommand.Restore, DrawCommand.Transform, DrawCommand.Clip, DrawCommand.GlyphText, DrawCommand.OutlineText {

    /** Pre-shaped text outlines in logical coordinates. logicalText is reading order, used as PDF ActualText/SVG metadata.
     * Empty paths represent whitespace. No backend reshaping is permitted. */
    record OutlineText(String logicalText, java.util.List<VectorPath> paths, RgbColor color, Rect logicalBounds) implements DrawCommand {
        public OutlineText {
            Objects.requireNonNull(logicalText, "logicalText");
            paths = java.util.List.copyOf(paths);
            Objects.requireNonNull(color, "color");
            Objects.requireNonNull(logicalBounds, "logicalBounds");
            if (logicalText.codePoints().anyMatch(cp -> Character.isISOControl(cp) && cp != '\n' && cp != '\r')) {
                throw new IllegalArgumentException("outlined text contains control characters");
            }
        }

        public OutlineText(String logicalText, java.util.List<VectorPath> paths, RgbColor color) {
            this(logicalText, paths, color, new Rect(0, 0, 1, 1));
        }
    }

    /** Draws premeasured glyphs with their explicit advances and the exact source font program. */
    record GlyphText(GlyphRun run, Point baseline, RgbColor color) implements DrawCommand {
        public GlyphText {
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(baseline, "baseline");
            Objects.requireNonNull(color, "color");
        }
    }

    record FillRect(Rect bounds, RgbColor color) implements DrawCommand {
        public FillRect {
            Objects.requireNonNull(bounds, "bounds");
            Objects.requireNonNull(color, "color");
        }
    }

    record FillPath(VectorPath path, RgbColor color) implements DrawCommand {
        public FillPath {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(color, "color");
        }
    }

    /** Strokes a path with a positive finite width; butt caps and miter joins are used. */
    record StrokePath(VectorPath path, RgbColor color, double width) implements DrawCommand {
        public StrokePath {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(color, "color");
            if (!Double.isFinite(width) || width <= 0) {
                throw new IllegalArgumentException("stroke width must be finite and positive");
            }
        }
    }

    /** Single-line text whose position is the left end of the baseline; no automatic layout. */
    record Text(String text, Point baseline, TextStyle style) implements DrawCommand {
        public Text {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(baseline, "baseline");
            Objects.requireNonNull(style, "style");
            if (text.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("text cannot contain control characters");
            }
        }
    }

    /** Draws an RGB image upright into the given rectangle, stretching if necessary. */
    record Image(RgbImage image, Rect bounds) implements DrawCommand {
        public Image {
            Objects.requireNonNull(image, "image");
            Objects.requireNonNull(bounds, "bounds");
        }
    }

    record Save() implements DrawCommand {
    }

    record Restore() implements DrawCommand {
    }

    /**
     * Concatenates a local-to-current matrix. If the current matrix is C, the result is C*M.
     * Thus a new local point is first mapped by M and then by the existing transform C.
     */
    record Transform(AffineTransform matrix) implements DrawCommand {
        public Transform {
            Objects.requireNonNull(matrix, "matrix");
        }
    }

    /** Intersects the current clip with a rectangle in the current coordinate system. */
    record Clip(Rect bounds) implements DrawCommand {
        public Clip {
            Objects.requireNonNull(bounds, "bounds");
        }
    }
}
