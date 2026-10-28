// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.svg;

import com.mirakyux.export4j.graphics.AffineTransform;
import com.mirakyux.export4j.graphics.BlendMode;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Paint;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.VectorPath;
import com.mirakyux.export4j.graphics.core.GraphicsResources;
import com.mirakyux.export4j.graphics.core.StaticBackendSupport;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

/** Native, local SVG paint resources. One writer owns document-unique definition identifiers. */
final class SvgGraphicsWriter {
    private final StringBuilder xml;
    private int identifier;
    SvgGraphicsWriter(StringBuilder xml) { this.xml = xml; }
    private record State(double fill, double stroke, BlendMode blend) { }
    void commands(List<DrawCommand> commands) throws IOException {
        var groups = new ArrayDeque<Integer>(); var states = new ArrayDeque<State>();
        groups.push(0); State state = new State(1, 1, BlendMode.NORMAL);
        for (var command : commands) {
            com.mirakyux.export4j.model.ExecutionContext.checkpoint();
            String style = state.blend() == BlendMode.NORMAL ? "" : " style=\"mix-blend-mode:" + blend(state.blend()) + "\"";
            switch (command) {
                case DrawCommand.Save ignored -> { groups.push(0); states.push(state); }
                case DrawCommand.Restore ignored -> { xml.append("</g>".repeat(groups.pop())); state = states.pop(); }
                case DrawCommand.Opacity o -> state = new State(o.fill(), o.stroke(), state.blend());
                case DrawCommand.Blend b -> state = new State(state.fill(), state.stroke(), b.mode());
                case DrawCommand.Transform t -> { xml.append("<g transform=\"").append(matrix(t.matrix())).append("\">"); groups.push(groups.pop() + 1); }
                case DrawCommand.Clip c -> { String id = id(); xml.append("<defs><clipPath id=\"").append(id).append("\"><rect ").append(rect(c.bounds())).append("/></clipPath></defs><g clip-path=\"url(#").append(id).append(")\">"); groups.push(groups.pop() + 1); }
                case DrawCommand.ClipPath c -> { String id = id(); xml.append("<defs><clipPath id=\"").append(id).append("\">"); path(c.path(), "clip-rule=\"" + rule(c.rule()) + "\""); xml.append("</clipPath></defs><g clip-path=\"url(#").append(id).append(")\">"); groups.push(groups.pop() + 1); }
                case DrawCommand.FillRect f -> xml.append("<rect ").append(rect(f.bounds())).append(" fill=\"").append(color(f.color())).append('"').append(alpha("fill-opacity", state.fill())).append(style).append("/>");
                case DrawCommand.FillPath f -> path(f.path(), "fill=\"" + color(f.color()) + "\"" + alpha("fill-opacity", state.fill()) + style);
                case DrawCommand.StrokePath s -> path(s.path(), "fill=\"none\" stroke=\"" + color(s.color()) + "\" stroke-width=\"" + s.width() + "\"" + alpha("stroke-opacity", state.stroke()) + style);
                case DrawCommand.PaintFill f -> { String paint = paint(f.paint()); path(f.path(), "fill=\"" + paint + "\" fill-rule=\"" + rule(f.rule()) + "\"" + alpha("fill-opacity", state.fill()) + style); }
                case DrawCommand.PaintStroke s -> { String paint = paint(s.paint()); path(s.path(), "fill=\"none\" stroke=\"" + paint + "\" stroke-width=\"" + s.width() + "\"" + alpha("stroke-opacity", state.stroke()) + style); }
                case DrawCommand.Group group -> {
                    String id = id(); xml.append("<defs><clipPath id=\"").append(id).append("\"><rect ").append(rect(group.bounds())).append("/></clipPath></defs>");
                    xml.append("<g clip-path=\"url(#").append(id).append(")\" opacity=\"").append(state.fill() * group.opacity()).append("\" style=\"isolation:isolate;mix-blend-mode:").append(blend(group.blend())).append("\">"); commands(group.commands()); xml.append("</g>");
                }
                case DrawCommand.OutlineText t -> { xml.append("<g aria-label=\"").append(escape(t.logicalText())).append("\"><title>").append(escape(t.logicalText())).append("</title>"); for (var path : t.paths()) { path(path, "fill=\"" + color(t.color()) + "\"" + alpha("fill-opacity", state.fill()) + style); } xml.append("</g>"); }
                case DrawCommand.Image i -> {
                    var bytes = new ByteArrayOutputStream(); var image = StaticBackendSupport.image(i.image());
                    try { if (!ImageIO.write(image, "png", bytes)) { throw new IOException("PNG writer unavailable"); } } finally { image.flush(); }
                    xml.append("<image ").append(rect(i.bounds())).append(alpha("opacity", state.fill())).append(style).append(" preserveAspectRatio=\"none\" href=\"data:image/png;base64,").append(Base64.getEncoder().encodeToString(bytes.toByteArray())).append("\"/>");
                }
                case DrawCommand.Text ignored -> throw new IllegalArgumentException("SVG text must be outlined");
                case DrawCommand.GlyphText ignored -> throw new IllegalArgumentException("SVG glyphs must be outlined");
            }
        }
        xml.append("</g>".repeat(groups.pop()));
    }
    private String paint(Paint paint) throws IOException {
        if (paint instanceof Paint.Solid solid) { return color(GraphicsResources.rgb(solid.color())); }
        String id = id(); xml.append("<defs>");
        if (paint instanceof Paint.Linear linear) {
            xml.append("<linearGradient id=\"").append(id).append("\" gradientUnits=\"userSpaceOnUse\" x1=\"").append(linear.start().x()).append("\" y1=\"").append(linear.start().y()).append("\" x2=\"").append(linear.end().x()).append("\" y2=\"").append(linear.end().y()).append("\" spreadMethod=\"").append(linear.spread().name().toLowerCase(Locale.ROOT)).append("\" gradientTransform=\"").append(matrix(linear.transform())).append("\">"); stops(linear.stops()); xml.append("</linearGradient>");
        } else if (paint instanceof Paint.Radial radial) {
            xml.append("<radialGradient id=\"").append(id).append("\" gradientUnits=\"userSpaceOnUse\" cx=\"").append(radial.center().x()).append("\" cy=\"").append(radial.center().y()).append("\" fx=\"").append(radial.focus().x()).append("\" fy=\"").append(radial.focus().y()).append("\" r=\"").append(radial.radius()).append("\" spreadMethod=\"").append(radial.spread().name().toLowerCase(Locale.ROOT)).append("\" gradientTransform=\"").append(matrix(radial.transform())).append("\">"); stops(radial.stops()); xml.append("</radialGradient>");
        } else {
            var tile = (Paint.Tile) paint; var r = tile.bounds();
            xml.append("<pattern id=\"").append(id).append("\" patternUnits=\"userSpaceOnUse\" patternContentUnits=\"userSpaceOnUse\" x=\"").append(r.x()).append("\" y=\"").append(r.y()).append("\" width=\"").append(tile.xStep()).append("\" height=\"").append(tile.yStep()).append("\" patternTransform=\"").append(matrix(tile.transform())).append("\">");
            xml.append("<svg x=\"0\" y=\"0\" width=\"").append(r.width()).append("\" height=\"").append(r.height()).append("\" viewBox=\"").append(r.x()).append(' ').append(r.y()).append(' ').append(r.width()).append(' ').append(r.height()).append("\">"); commands(tile.commands()); xml.append("</svg></pattern>");
        }
        xml.append("</defs>"); return "url(#" + id + ")";
    }
    private void stops(List<Paint.Stop> stops) { for (var stop : stops) { xml.append("<stop offset=\"").append(stop.offset()).append("\" stop-color=\"").append(color(GraphicsResources.rgb(stop.color()))).append("\" stop-opacity=\"").append(stop.opacity()).append("\"/>"); } }
    private String id() { return "e4j-paint-" + identifier++; }
    private static String rule(DrawCommand.FillRule rule) { return rule == DrawCommand.FillRule.EVEN_ODD ? "evenodd" : "nonzero"; }
    private static String blend(BlendMode mode) { return mode.name().toLowerCase(Locale.ROOT).replace('_', '-'); }
    private static String matrix(AffineTransform m) { return "matrix(" + m.a() + " " + m.b() + " " + m.c() + " " + m.d() + " " + m.e() + " " + m.f() + ")"; }
    private static String alpha(String name, double value) { return value == 1 ? "" : " " + name + "=\"" + value + "\""; }
    private static String rect(Rect r) { return "x=\"" + r.x() + "\" y=\"" + r.y() + "\" width=\"" + r.width() + "\" height=\"" + r.height() + "\""; }
    private static String color(RgbColor c) { return "rgb(" + c.red() * 100 + "% " + c.green() * 100 + "% " + c.blue() * 100 + "%)"; }
    private void path(VectorPath path, String attributes) {
        xml.append("<path ").append(attributes).append(" d=\"");
        for (var segment : path.segments()) {
            switch (segment) {
                case VectorPath.MoveTo m -> xml.append('M').append(m.point().x()).append(' ').append(m.point().y());
                case VectorPath.LineTo l -> xml.append('L').append(l.point().x()).append(' ').append(l.point().y());
                case VectorPath.CurveTo c -> xml.append('C').append(c.control1().x()).append(' ').append(c.control1().y()).append(' ').append(c.control2().x()).append(' ').append(c.control2().y()).append(' ').append(c.end().x()).append(' ').append(c.end().y());
                case VectorPath.Close ignored -> xml.append('Z');
            }
        }
        xml.append("\"/>");
    }
    private static String escape(String value) {
        if (value.codePoints().anyMatch(cp -> cp < 32 && cp != 9 && cp != 10 && cp != 13 || cp >= 0xd800 && cp <= 0xdfff || cp == 0xfffe || cp == 0xffff)) { throw new IllegalArgumentException("invalid XML character"); }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }
}
