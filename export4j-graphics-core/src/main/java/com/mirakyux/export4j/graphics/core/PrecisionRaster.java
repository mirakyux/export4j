// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.graphics.core;

import com.mirakyux.export4j.graphics.BlendMode;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Paint;
import com.mirakyux.export4j.graphics.RgbColor;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.CompositeContext;
import java.awt.Graphics2D;
import java.awt.PaintContext;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.util.ArrayDeque;
import java.util.List;

/** Bounded precision-command rasterizer; text must already be outlined. No layout or font selection. */
public final class PrecisionRaster {
    private PrecisionRaster() { }
    public static BufferedImage render(List<DrawCommand> commands, double width, double height, double scale, boolean white) {
        var budget = new Budget();
        var image = budget.image(Math.ceil(width * scale), Math.ceil(height * scale));
        var graphics = image.createGraphics();
        try {
            if (white) { graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, image.getWidth(), image.getHeight()); }
            graphics.scale(scale, scale); configure(graphics); draw(commands, graphics, image.getWidth(), image.getHeight(), budget, 0);
        } finally { graphics.dispose(); }
        return image;
    }
    private static void configure(Graphics2D g) { g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON); g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR); }
    private record State(double fill, double stroke, BlendMode blend) { }
    private static void draw(List<DrawCommand> commands, Graphics2D initial, int canvasWidth, int canvasHeight, Budget budget, int depth) {
        if (depth > 32) { throw new IllegalArgumentException("raster group depth budget exceeded"); }
        var saved = new ArrayDeque<Graphics2D>(); var states = new ArrayDeque<State>();
        Graphics2D g = (Graphics2D) initial.create(); State state = new State(1, 1, BlendMode.NORMAL);
        try {
            for (var command : commands) {
                com.mirakyux.export4j.model.ExecutionContext.checkpoint();
                switch (command) {
                    case DrawCommand.Save ignored -> { saved.push(g); g = (Graphics2D) g.create(); states.push(state); }
                    case DrawCommand.Restore ignored -> { g.dispose(); g = saved.pop(); state = states.pop(); }
                    case DrawCommand.Transform t -> g.transform(GraphicsResources.matrix(t.matrix()));
                    case DrawCommand.Clip c -> { var r = c.bounds(); g.clip(new Rectangle2D.Double(r.x(), r.y(), r.width(), r.height())); }
                    case DrawCommand.ClipPath c -> g.clip(GraphicsResources.path(c.path(), c.rule()));
                    case DrawCommand.Opacity o -> state = new State(o.fill(), o.stroke(), state.blend());
                    case DrawCommand.Blend b -> state = new State(state.fill(), state.stroke(), b.mode());
                    case DrawCommand.FillRect f -> { var r = f.bounds(); g.setComposite(composite(state.blend(), state.fill())); g.setColor(color(f.color())); g.fill(new Rectangle2D.Double(r.x(), r.y(), r.width(), r.height())); }
                    case DrawCommand.FillPath f -> { g.setComposite(composite(state.blend(), state.fill())); g.setColor(color(f.color())); g.fill(StaticBackendSupport.path(f.path())); }
                    case DrawCommand.StrokePath s -> { g.setComposite(composite(state.blend(), state.stroke())); g.setColor(color(s.color())); g.setStroke(stroke(s.width())); g.draw(StaticBackendSupport.path(s.path())); }
                    case DrawCommand.PaintFill f -> { g.setComposite(composite(state.blend(), state.fill())); g.setPaint(paint(f.paint(), g, budget, depth)); g.fill(GraphicsResources.path(f.path(), f.rule())); }
                    case DrawCommand.PaintStroke s -> { g.setComposite(composite(state.blend(), state.stroke())); g.setPaint(paint(s.paint(), g, budget, depth)); g.setStroke(stroke(s.width())); g.draw(StaticBackendSupport.path(s.path())); }
                    case DrawCommand.OutlineText text -> { g.setComposite(composite(state.blend(), state.fill())); g.setColor(color(text.color())); for (var path : text.paths()) { g.fill(StaticBackendSupport.path(path)); } }
                    case DrawCommand.Image i -> {
                        g.setComposite(composite(state.blend(), state.fill())); var r = i.bounds(); var m = new AffineTransform(); m.translate(r.x(), r.y()); m.scale(r.width() / i.image().width(), r.height() / i.image().height());
                        var image = StaticBackendSupport.image(i.image()); try { g.drawImage(image, m, null); } finally { image.flush(); }
                    }
                    case DrawCommand.Group group -> {
                        var r = group.bounds(); var bounds = g.getTransform().createTransformedShape(new Rectangle2D.Double(r.x(), r.y(), r.width(), r.height())).getBounds();
                        bounds = bounds.intersection(new Rectangle(0, 0, canvasWidth, canvasHeight));
                        if (bounds.isEmpty()) { continue; }
                        var layer = budget.image(bounds.width, bounds.height); var layerGraphics = layer.createGraphics();
                        try {
                            configure(layerGraphics); layerGraphics.translate(-bounds.x, -bounds.y); layerGraphics.transform(g.getTransform());
                            if (g.getClip() != null) { layerGraphics.clip(g.getClip()); }
                            layerGraphics.clip(new Rectangle2D.Double(r.x(), r.y(), r.width(), r.height()));
                            draw(group.commands(), layerGraphics, bounds.width, bounds.height, budget, depth + 1);
                            var target = (Graphics2D) g.create();
                            try { target.setTransform(new AffineTransform()); target.setClip(g.getTransform().createTransformedShape(g.getClip() == null ? new Rectangle2D.Double(r.x(), r.y(), r.width(), r.height()) : g.getClip())); target.setComposite(composite(group.blend(), state.fill() * group.opacity())); target.drawImage(layer, bounds.x, bounds.y, null); }
                            finally { target.dispose(); }
                        } finally { layerGraphics.dispose(); layer.flush(); }
                    }
                    case DrawCommand.Text ignored -> throw new IllegalArgumentException("raster text must be outlined");
                    case DrawCommand.GlyphText ignored -> throw new IllegalArgumentException("raster glyphs must be outlined");
                }
            }
        } finally { g.dispose(); saved.forEach(Graphics2D::dispose); }
    }
    private static BasicStroke stroke(double width) { if (width > Float.MAX_VALUE) { throw new IllegalArgumentException("stroke width overflow"); } return new BasicStroke((float) width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER); }
    private static Color color(RgbColor color) { return new Color((float) color.red(), (float) color.green(), (float) color.blue()); }
    private static Composite composite(BlendMode mode, double opacity) { return mode == BlendMode.NORMAL ? AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) opacity) : new Blending(mode, opacity); }

    private static java.awt.Paint paint(Paint paint, Graphics2D g, Budget budget, int depth) {
        if (paint instanceof Paint.Solid solid) { return color(GraphicsResources.rgb(solid.color())); }
        var matrix = paint instanceof Paint.Linear p ? p.transform() : paint instanceof Paint.Radial p ? p.transform() : ((Paint.Tile) paint).transform();
        Sampler sampler;
        if (paint instanceof Paint.Tile tile) {
            var r = tile.bounds();
            var combined = g.getTransform(); combined.concatenate(GraphicsResources.matrix(tile.transform()));
            double scale = Math.max(1, Math.max(Math.hypot(combined.getScaleX(), combined.getShearY()), Math.hypot(combined.getShearX(), combined.getScaleY())));
            var image = budget.image(Math.ceil(tile.xStep() * scale), Math.ceil(tile.yStep() * scale)); var tg = image.createGraphics();
            try {
                configure(tg); tg.scale(image.getWidth() / tile.xStep(), image.getHeight() / tile.yStep()); tg.translate(-r.x(), -r.y());
                tg.clip(new Rectangle2D.Double(r.x(), r.y(), r.width(), r.height())); draw(tile.commands(), tg, image.getWidth(), image.getHeight(), budget, depth + 1);
            } finally { tg.dispose(); }
            sampler = (x, y) -> { int ix = (int) Math.floor(mod(x - r.x(), tile.xStep()) / tile.xStep() * image.getWidth()); int iy = (int) Math.floor(mod(y - r.y(), tile.yStep()) / tile.yStep() * image.getHeight()); return image.getRGB(Math.min(ix, image.getWidth() - 1), Math.min(iy, image.getHeight() - 1)); };
        } else {
            var stops = paint instanceof Paint.Linear linear ? linear.stops() : ((Paint.Radial) paint).stops();
            double[][] colors = new double[stops.size()][4];
            for (int i = 0; i < colors.length; i++) { var c = GraphicsResources.rgb(stops.get(i).color()); colors[i] = new double[] {c.red(), c.green(), c.blue(), stops.get(i).opacity()}; }
            var spread = paint instanceof Paint.Linear linear ? linear.spread() : ((Paint.Radial) paint).spread();
            sampler = (x, y) -> {
                double t;
                if (paint instanceof Paint.Linear linear) { double dx = linear.end().x() - linear.start().x(), dy = linear.end().y() - linear.start().y(); t = ((x - linear.start().x()) * dx + (y - linear.start().y()) * dy) / (dx * dx + dy * dy); }
                else { var radial = (Paint.Radial) paint; double dx = x - radial.focus().x(), dy = y - radial.focus().y(), cx = radial.center().x() - radial.focus().x(), cy = radial.center().y() - radial.focus().y(); double dot = dx * cx + dy * cy, a = radial.radius() * radial.radius() - cx * cx - cy * cy; t = (Math.sqrt(Math.max(0, dot * dot + a * (dx * dx + dy * dy))) - dot) / a; }
                t = switch (spread) { case PAD -> Math.max(0, Math.min(1, t)); case REPEAT -> mod(t, 1); case REFLECT -> { double r = mod(t, 2); yield r <= 1 ? r : 2 - r; } };
                int index = 0; while (index + 1 < stops.size() && t >= stops.get(index + 1).offset()) { index++; }
                int next = Math.min(index + 1, stops.size() - 1); double length = stops.get(next).offset() - stops.get(index).offset(); double fraction = length == 0 ? 0 : (t - stops.get(index).offset()) / length;
                int result = 0; for (int c = 0; c < 4; c++) { int value = (int) Math.round(255 * (colors[index][c] + fraction * (colors[next][c] - colors[index][c]))); result |= Math.max(0, Math.min(255, value)) << (c == 3 ? 24 : 16 - c * 8); } return result;
            };
        }
        final Sampler source = sampler;
        return new java.awt.Paint() {
            @Override public int getTransparency() { return Transparency.TRANSLUCENT; }
            @Override public PaintContext createContext(ColorModel cm, Rectangle db, Rectangle2D ub, AffineTransform transform, RenderingHints hints) {
                transform = new AffineTransform(transform); transform.concatenate(GraphicsResources.matrix(matrix));
                final AffineTransform inverse; try { inverse = transform.createInverse(); } catch (NoninvertibleTransformException failure) { throw new IllegalArgumentException("singular paint transform", failure); }
                return new PaintContext() {
                    @Override public void dispose() { }
                    @Override public ColorModel getColorModel() { return ColorModel.getRGBdefault(); }
                    @Override public Raster getRaster(int x, int y, int width, int height) {
                        var raster = getColorModel().createCompatibleWritableRaster(width, height); int[] values = new int[width * height]; var p = new Point2D.Double();
                        for (int row = 0; row < height; row++) { com.mirakyux.export4j.model.ExecutionContext.checkpoint(); for (int col = 0; col < width; col++) { p.setLocation(x + col + 0.5, y + row + 0.5); inverse.transform(p, p); values[row * width + col] = source.argb(p.x, p.y); } }
                        raster.setDataElements(0, 0, width, height, values); return raster;
                    }
                };
            }
        };
    }
    @FunctionalInterface private interface Sampler { int argb(double x, double y); }
    private static double mod(double value, double period) { return value - period * Math.floor(value / period); }
    private static final class Budget {
        long pixels;
        BufferedImage image(double width, double height) {
            if (!Double.isFinite(width) || !Double.isFinite(height) || width < 1 || height < 1 || width > 100_000 || height > 100_000 || width * height > 100_000_000 - pixels) { throw new IllegalArgumentException("raster intermediate pixel budget exceeded"); }
            pixels += (long) width * (long) height; return new BufferedImage((int) width, (int) height, BufferedImage.TYPE_INT_ARGB);
        }
    }

    private record Blending(BlendMode mode, double opacity) implements Composite {
        @Override public CompositeContext createContext(ColorModel sourceModel, ColorModel targetModel, RenderingHints hints) {
            return new CompositeContext() {
                @Override public void dispose() { }
                @Override public void compose(Raster source, Raster target, WritableRaster output) {
                    int width = Math.min(source.getWidth(), target.getWidth()), height = Math.min(source.getHeight(), target.getHeight());
                    Object s = null, d = null, result = null;
                    for (int y = 0; y < height; y++) { com.mirakyux.export4j.model.ExecutionContext.checkpoint(); for (int x = 0; x < width; x++) {
                        s = source.getDataElements(source.getMinX() + x, source.getMinY() + y, s); d = target.getDataElements(target.getMinX() + x, target.getMinY() + y, d);
                        int sc = sourceModel.getRGB(s), dc = targetModel.getRGB(d); double sa = (sc >>> 24) / 255.0 * opacity, da = (dc >>> 24) / 255.0;
                        double[] cs = rgb(sc), cb = rgb(dc), blend = blend(mode, cb, cs); double alpha = sa + da - sa * da; int argb = (int) Math.round(alpha * 255) << 24;
                        for (int c = 0; c < 3; c++) { double value = alpha == 0 ? 0 : (sa * (1 - da) * cs[c] + sa * da * blend[c] + (1 - sa) * da * cb[c]) / alpha; argb |= (int) Math.round(Math.max(0, Math.min(1, value)) * 255) << (16 - c * 8); }
                        result = targetModel.getDataElements(argb, result); output.setDataElements(output.getMinX() + x, output.getMinY() + y, result);
                    } }
                }
            };
        }
    }
    private static double[] rgb(int color) { return new double[] {(color >> 16 & 255) / 255.0, (color >> 8 & 255) / 255.0, (color & 255) / 255.0}; }
    private static double[] blend(BlendMode mode, double[] b, double[] s) {
        if (mode == BlendMode.HUE) { return setLum(setSat(s.clone(), sat(b)), lum(b)); }
        if (mode == BlendMode.SATURATION) { return setLum(setSat(b.clone(), sat(s)), lum(b)); }
        if (mode == BlendMode.COLOR) { return setLum(s.clone(), lum(b)); }
        if (mode == BlendMode.LUMINOSITY) { return setLum(b.clone(), lum(s)); }
        var result = new double[3];
        for (int i = 0; i < 3; i++) { double cb = b[i], cs = s[i]; result[i] = switch (mode) {
            case NORMAL -> cs; case MULTIPLY -> cb * cs; case SCREEN -> cb + cs - cb * cs;
            case OVERLAY -> cb <= .5 ? 2 * cb * cs : 1 - 2 * (1 - cb) * (1 - cs);
            case DARKEN -> Math.min(cb, cs); case LIGHTEN -> Math.max(cb, cs);
            case COLOR_DODGE -> cb == 0 ? 0 : cs == 1 ? 1 : Math.min(1, cb / (1 - cs));
            case COLOR_BURN -> cb == 1 ? 1 : cs == 0 ? 0 : 1 - Math.min(1, (1 - cb) / cs);
            case HARD_LIGHT -> cs <= .5 ? 2 * cs * cb : 1 - 2 * (1 - cs) * (1 - cb);
            case SOFT_LIGHT -> cs <= .5 ? cb - (1 - 2 * cs) * cb * (1 - cb) : cb + (2 * cs - 1) * ((cb <= .25 ? ((16 * cb - 12) * cb + 4) * cb : Math.sqrt(cb)) - cb);
            case DIFFERENCE -> Math.abs(cb - cs); case EXCLUSION -> cb + cs - 2 * cb * cs;
            default -> throw new IllegalArgumentException("unhandled blend mode");
        }; } return result;
    }
    private static double lum(double[] c) { return .3 * c[0] + .59 * c[1] + .11 * c[2]; }
    private static double sat(double[] c) { return Math.max(c[0], Math.max(c[1], c[2])) - Math.min(c[0], Math.min(c[1], c[2])); }
    private static double[] setSat(double[] c, double saturation) {
        int min = 0, max = 0; for (int i = 1; i < 3; i++) { if (c[i] < c[min]) { min = i; } if (c[i] > c[max]) { max = i; } }
        if (min == max) { return new double[3]; } int mid = 3 - min - max; c[mid] = (c[mid] - c[min]) * saturation / (c[max] - c[min]); c[max] = saturation; c[min] = 0; return c;
    }
    private static double[] setLum(double[] c, double luminosity) {
        double d = luminosity - lum(c); for (int i = 0; i < 3; i++) { c[i] += d; }
        double l = lum(c), n = Math.min(c[0], Math.min(c[1], c[2])), x = Math.max(c[0], Math.max(c[1], c[2]));
        if (n < 0) { for (int i = 0; i < 3; i++) { c[i] = l + (c[i] - l) * l / (l - n); } }
        if (x > 1) { for (int i = 0; i < 3; i++) { c[i] = l + (c[i] - l) * (1 - l) / (x - l); } } return c;
    }
}
