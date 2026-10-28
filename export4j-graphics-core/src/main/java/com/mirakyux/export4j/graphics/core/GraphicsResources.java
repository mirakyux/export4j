// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.graphics.core;

import com.mirakyux.export4j.graphics.AffineTransform;
import com.mirakyux.export4j.graphics.BlendMode;
import com.mirakyux.export4j.graphics.CmykColor;
import com.mirakyux.export4j.graphics.ColorValue;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.IccColor;
import com.mirakyux.export4j.graphics.Paint;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.SpotColor;
import com.mirakyux.export4j.graphics.VectorPath;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingIssue;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Shared traversal and explicit print-color conversion. No external resources are resolved. */
public final class GraphicsResources {
    private GraphicsResources() { }

    /** Traverses nested groups and tiles with finite work and depth, counting each use. */
    public static void walk(List<DrawCommand> commands, Consumer<DrawCommand> visitor) { walk(commands, visitor, 0, new int[1]); }
    private static void walk(List<DrawCommand> commands, Consumer<DrawCommand> visitor, int depth, int[] count) {
        if (depth > 32) { throw new IllegalArgumentException("nested paint depth budget exceeded"); }
        int saved = 0;
        for (var command : commands) {
            com.mirakyux.export4j.model.ExecutionContext.checkpoint();
            if (++count[0] > 2_000_000) { throw new IllegalArgumentException("nested paint command budget exceeded"); }
            if (command instanceof DrawCommand.Save && ++saved + depth > 64) { throw new IllegalArgumentException("nested paint state budget exceeded"); }
            if (command instanceof DrawCommand.Restore) { saved--; }
            visitor.accept(command);
            if (command instanceof DrawCommand.Group group) { walk(group.commands(), visitor, depth + 1, count); }
            Paint paint = command instanceof DrawCommand.PaintFill fill ? fill.paint() : command instanceof DrawCommand.PaintStroke stroke ? stroke.paint() : null;
            if (paint instanceof Paint.Tile tile) { walk(tile.commands(), visitor, depth + 1, count); }
        }
    }

    public static RgbColor rgb(ColorValue value) {
        return switch (value) {
            case RgbColor rgb -> rgb;
            case CmykColor c -> new RgbColor((1 - c.cyan()) * (1 - c.black()), (1 - c.magenta()) * (1 - c.black()), (1 - c.yellow()) * (1 - c.black()));
            case IccColor icc -> {
                float[] components = new float[icc.components().size()];
                for (int i = 0; i < components.length; i++) { components[i] = icc.components().get(i).floatValue(); }
                var converted = new ICC_ColorSpace(ICC_Profile.getInstance(icc.profile())).toRGB(components);
                yield new RgbColor(clamp(converted[0]), clamp(converted[1]), clamp(converted[2]));
            }
            case SpotColor spot -> {
                var full = rgb(spot.alternate());
                yield new RgbColor(1 - spot.tint() * (1 - full.red()), 1 - spot.tint() * (1 - full.green()), 1 - spot.tint() * (1 - full.blue()));
            }
        };
    }
    private static double clamp(double value) { return Math.max(0, Math.min(1, value)); }

    /** Preflights color-preservation losses before output. Actual is returned only after a successful write. */
    public static List<ProcessingIssue> colorIssues(List<DisplayPage> pages, String target, ProcessingPolicy policy) {
        var issues = new ArrayList<ProcessingIssue>();
        for (int page = 0; page < pages.size(); page++) {
            int pageNumber = page;
            walk(pages.get(page).commands(), command -> {
                Paint paint = command instanceof DrawCommand.PaintFill fill ? fill.paint() : command instanceof DrawCommand.PaintStroke stroke ? stroke.paint() : null;
                if (paint == null) { return; }
                var colors = colors(paint);
                for (var value : colors) {
                    if (value instanceof RgbColor) { continue; }
                    String feature = value instanceof SpotColor ? "spot-color" : value instanceof IccColor ? "icc-color" : "cmyk-color";
                    var rule = policy.resolve(target, feature, "graphics-color");
                    var issue = new ProcessingIssue("GRAPHICS", "DISPLAY", target, feature, "graphics-color",
                            new ProcessingIssue.Location("", "page-" + pageNumber, "", List.of(), -1, -1),
                            "target uses RGB appearance rather than the original print color space", rule.action(), rule.treatment(),
                            List.of("print color space and named ink identity are replaced by explicit RGB appearance"));
                    if (rule.action() == ProcessingPolicy.Action.ERROR || rule.treatment() != ProcessingPolicy.Treatment.DEGRADE) { throw new ProcessingException(issue); }
                    issues.add(issue);
                }
            });
        }
        return List.copyOf(issues);
    }

    public static List<ColorValue> colors(Paint paint) {
        return switch (paint) {
            case Paint.Solid solid -> List.of(solid.color());
            case Paint.Linear linear -> linear.stops().stream().map(Paint.Stop::color).toList();
            case Paint.Radial radial -> radial.stops().stream().map(Paint.Stop::color).toList();
            case Paint.Tile ignored -> List.of();
        };
    }

    /** Maps paint resources without changing command indices or logical-text bindings. */
    public static List<DrawCommand> mapPaints(List<DrawCommand> source, java.util.function.UnaryOperator<Paint> mapper) {
        walk(source, ignored -> { });
        return mapPaints(source, mapper, 0);
    }
    private static List<DrawCommand> mapPaints(List<DrawCommand> source, java.util.function.UnaryOperator<Paint> mapper, int depth) {
        if (depth > 32) { throw new IllegalArgumentException("paint mapping depth exceeded"); }
        var result = new ArrayList<DrawCommand>();
        for (var command : source) {
            if (command instanceof DrawCommand.Group group) { command = new DrawCommand.Group(group.bounds(), mapPaints(group.commands(), mapper, depth + 1), group.opacity(), group.blend()); }
            else if (command instanceof DrawCommand.PaintFill fill) { command = new DrawCommand.PaintFill(fill.path(), mapPaint(fill.paint(), mapper, depth), fill.rule()); }
            else if (command instanceof DrawCommand.PaintStroke stroke) { command = new DrawCommand.PaintStroke(stroke.path(), mapPaint(stroke.paint(), mapper, depth), stroke.width()); }
            result.add(command);
        }
        return List.copyOf(result);
    }
    private static Paint mapPaint(Paint paint, java.util.function.UnaryOperator<Paint> mapper, int depth) {
        if (paint instanceof Paint.Tile tile) { paint = new Paint.Tile(tile.bounds(), tile.xStep(), tile.yStep(), tile.transform(), mapPaints(tile.commands(), mapper, depth + 1)); }
        return mapper.apply(paint);
    }

    public static java.awt.geom.AffineTransform matrix(AffineTransform matrix) { return new java.awt.geom.AffineTransform(matrix.a(), matrix.b(), matrix.c(), matrix.d(), matrix.e(), matrix.f()); }
    public static AffineTransform matrix(java.awt.geom.AffineTransform matrix) { var m = new double[6]; matrix.getMatrix(m); return new AffineTransform(m[0], m[1], m[2], m[3], m[4], m[5]); }
    public static VectorPath vector(java.awt.Shape shape) {
        var segments = new ArrayList<VectorPath.Segment>(); var iterator = shape.getPathIterator(null); var c = new double[6];
        double x = 0, y = 0;
        while (!iterator.isDone()) {
            switch (iterator.currentSegment(c)) {
                case java.awt.geom.PathIterator.SEG_MOVETO -> { segments.add(new VectorPath.MoveTo(new Point(c[0], c[1]))); x = c[0]; y = c[1]; }
                case java.awt.geom.PathIterator.SEG_LINETO -> { segments.add(new VectorPath.LineTo(new Point(c[0], c[1]))); x = c[0]; y = c[1]; }
                case java.awt.geom.PathIterator.SEG_CUBICTO -> { segments.add(new VectorPath.CurveTo(new Point(c[0], c[1]), new Point(c[2], c[3]), new Point(c[4], c[5]))); x = c[4]; y = c[5]; }
                case java.awt.geom.PathIterator.SEG_QUADTO -> { segments.add(new VectorPath.CurveTo(new Point(x + (c[0] - x) * 2 / 3, y + (c[1] - y) * 2 / 3), new Point(c[2] + (c[0] - c[2]) * 2 / 3, c[3] + (c[1] - c[3]) * 2 / 3), new Point(c[2], c[3]))); x = c[2]; y = c[3]; }
                case java.awt.geom.PathIterator.SEG_CLOSE -> segments.add(new VectorPath.Close());
                default -> throw new IllegalArgumentException("unsupported shape segment");
            }
            iterator.next();
            if (segments.size() > 1_000_000) { throw new IllegalArgumentException("shape segment budget exceeded"); }
        }
        if (segments.isEmpty()) { segments.add(new VectorPath.MoveTo(new Point(0, 0))); }
        return new VectorPath(segments);
    }
    public static java.awt.geom.Path2D.Double path(VectorPath path, DrawCommand.FillRule rule) { var result = StaticBackendSupport.path(path); result.setWindingRule(rule == DrawCommand.FillRule.EVEN_ODD ? java.awt.geom.Path2D.WIND_EVEN_ODD : java.awt.geom.Path2D.WIND_NON_ZERO); return result; }
}
