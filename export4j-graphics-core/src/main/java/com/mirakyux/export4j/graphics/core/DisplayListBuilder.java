// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.graphics.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import com.mirakyux.export4j.graphics.AffineTransform;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.DrawingSurface;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.RgbImage;
import com.mirakyux.export4j.graphics.TextStyle;
import com.mirakyux.export4j.graphics.VectorPath;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.text.GlyphRun;

/** Task-confined page recorder. Build creates an immutable snapshot and requires balanced state. */
public final class DisplayListBuilder implements DrawingSurface {
    private final PageSize size;
    private final List<DrawCommand> commands = new ArrayList<>();
    private int depth;

    public DisplayListBuilder(PageSize size) {
        this.size = Objects.requireNonNull(size, "size");
    }

    /** Number of commands recorded so far, including graphics-state operations; creates no snapshot. */
    public int commandCount() {
        return commands.size();
    }

    @Override public DisplayListBuilder fillPath(VectorPath path, com.mirakyux.export4j.graphics.Paint paint, DrawCommand.FillRule rule) { commands.add(new DrawCommand.PaintFill(path, paint, rule)); return this; }
    public DisplayListBuilder fillPath(VectorPath path, com.mirakyux.export4j.graphics.Paint paint) { return fillPath(path, paint, DrawCommand.FillRule.NONZERO); }
    @Override public DisplayListBuilder strokePath(VectorPath path, com.mirakyux.export4j.graphics.Paint paint, double width) { commands.add(new DrawCommand.PaintStroke(path, paint, width)); return this; }
    @Override public DisplayListBuilder clipPath(VectorPath path, DrawCommand.FillRule rule) { commands.add(new DrawCommand.ClipPath(path, rule)); return this; }
    @Override public DisplayListBuilder blend(com.mirakyux.export4j.graphics.BlendMode mode) { commands.add(new DrawCommand.Blend(mode)); return this; }
    @Override public DisplayListBuilder group(Rect bounds, List<DrawCommand> nested, double opacity, com.mirakyux.export4j.graphics.BlendMode blend) { commands.add(new DrawCommand.Group(bounds, nested, opacity, blend)); return this; }

    @Override
    public DisplayListBuilder fillRect(Rect bounds, RgbColor color) {
        commands.add(new DrawCommand.FillRect(bounds, color));
        return this;
    }

    @Override
    public DisplayListBuilder fillPath(VectorPath path, RgbColor color) {
        commands.add(new DrawCommand.FillPath(path, color));
        return this;
    }

    @Override
    public DisplayListBuilder strokePath(VectorPath path, RgbColor color, double width) {
        commands.add(new DrawCommand.StrokePath(path, color, width));
        return this;
    }

    @Override
    public DisplayListBuilder drawText(String text, Point baseline, TextStyle style) {
        commands.add(new DrawCommand.Text(text, baseline, style));
        return this;
    }

    @Override
    public DisplayListBuilder drawGlyphRun(GlyphRun run, Point baseline, RgbColor color) {
        commands.add(new DrawCommand.GlyphText(run, baseline, color));
        return this;
    }

    @Override
    public DisplayListBuilder drawOutlinedText(String text, List<VectorPath> paths, RgbColor color, Rect bounds) {
        commands.add(new DrawCommand.OutlineText(text, paths, color, bounds));
        return this;
    }

    @Override
    public DisplayListBuilder drawImage(RgbImage image, Rect bounds) {
        commands.add(new DrawCommand.Image(image, bounds));
        return this;
    }

    @Override
    public DisplayListBuilder save() {
        commands.add(new DrawCommand.Save());
        depth++;
        return this;
    }

    @Override
    public DisplayListBuilder restore() {
        if (depth == 0) {
            throw new IllegalStateException("restore has no matching save");
        }
        commands.add(new DrawCommand.Restore());
        depth--;
        return this;
    }

    @Override
    public DisplayListBuilder transform(AffineTransform matrix) {
        commands.add(new DrawCommand.Transform(matrix));
        return this;
    }

    @Override
    public DisplayListBuilder clip(Rect bounds) {
        commands.add(new DrawCommand.Clip(bounds));
        return this;
    }

    @Override
    public DisplayListBuilder opacity(double fill, double stroke) {
        commands.add(new DrawCommand.Opacity(fill, stroke));
        return this;
    }

    /** Appends an independently balanced immutable command fragment without consuming this builder's saved state. */
    public DisplayListBuilder appendCommands(List<DrawCommand> additions) {
        // Validate the fragment independently so it cannot consume the caller's saved graphics state.
        DisplayPage fragment = new DisplayPage(size, additions);
        commands.addAll(fragment.commands());
        return this;
    }

    /** Returns a snapshot; later builder changes cannot affect a previously built page. */
    public DisplayPage build() {
        if (depth != 0) {
            throw new IllegalStateException("unbalanced save/restore state");
        }
        return new DisplayPage(size, commands);
    }
}
