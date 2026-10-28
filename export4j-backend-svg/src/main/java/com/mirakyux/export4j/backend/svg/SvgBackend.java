// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.svg;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.imageio.ImageIO;
import com.mirakyux.export4j.font.FontData;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DocumentBackend;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.FontFamily;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.VectorPath;
import com.mirakyux.export4j.graphics.core.StaticBackendSupport;
import com.mirakyux.export4j.model.DocumentMetadata;

/** Self-contained SVG with outlined text and embedded PNGs. Multiple pages stack vertically with a 20 pt gap.
 * Rejects interactive extras. Caller owns output. Instances are immutable and reusable concurrently. */
public final class SvgBackend implements DocumentBackend {
    private final Map<FontFamily, FontData> standardFonts;

    public SvgBackend() { this(Map.of()); }
    public SvgBackend(Map<FontFamily, FontData> standardFonts) { this.standardFonts = Map.copyOf(standardFonts); }

    public void writePage(DisplayPage page, OutputStream output) throws IOException {
        write(new DisplayDocument(DocumentMetadata.EMPTY, List.of(page)), output);
    }

    @Override
    public void write(DisplayDocument document, OutputStream output) throws IOException {
        Objects.requireNonNull(output, "output");
        if (!document.accessibility().children().isEmpty()) { throw new IllegalArgumentException("SVG output does not map the explicit accessibility tree"); }
        var pages = StaticBackendSupport.outlines(document, standardFonts);
        double width = pages.stream().mapToDouble(p -> p.size().width()).max().orElseThrow();
        double height = pages.stream().mapToDouble(p -> p.size().height()).sum() + 20.0 * (pages.size() - 1);
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(width).append("pt\" height=\"").append(height)
                .append("pt\" viewBox=\"0 0 ").append(width).append(' ').append(height).append("\">");
        xml.append("<title>").append(escape(document.metadata().title())).append("</title>");
        double y = 0;
        int clip = 0;
        for (int index = 0; index < pages.size(); index++) {
            com.mirakyux.export4j.model.ExecutionContext.progress("SVG_PAGE", "", index, index, pages.size());
            var page = pages.get(index);
            xml.append("<svg data-page=\"").append(index + 1).append("\" x=\"0\" y=\"").append(y).append("\" width=\"")
                    .append(page.size().width()).append("\" height=\"").append(page.size().height()).append("\" viewBox=\"0 0 ")
                    .append(page.size().width()).append(' ').append(page.size().height()).append("\">");
            xml.append("<rect width=\"100%\" height=\"100%\" fill=\"white\"/>");
            var groups = new ArrayDeque<Integer>();
            var savedOpacity = new ArrayDeque<double[]>();
            double fillOpacity = 1;
            double strokeOpacity = 1;
            groups.push(0);
            for (var command : page.commands()) {
                com.mirakyux.export4j.model.ExecutionContext.checkpoint();
                switch (command) {
                    case DrawCommand.Save ignored -> { groups.push(0); savedOpacity.push(new double[] {fillOpacity, strokeOpacity}); }
                    case DrawCommand.Restore ignored -> {
                        xml.append("</g>".repeat(groups.pop()));
                        var opacity = savedOpacity.pop(); fillOpacity = opacity[0]; strokeOpacity = opacity[1];
                    }
                    case DrawCommand.Opacity opacity -> { fillOpacity = opacity.fill(); strokeOpacity = opacity.stroke(); }
                    case DrawCommand.Transform transform -> {
                        var m = transform.matrix();
                        xml.append("<g transform=\"matrix(").append(m.a()).append(' ').append(m.b()).append(' ').append(m.c()).append(' ')
                                .append(m.d()).append(' ').append(m.e()).append(' ').append(m.f()).append(")\">");
                        groups.push(groups.pop() + 1);
                    }
                    case DrawCommand.Clip bounds -> {
                        int id = clip++;
                        xml.append("<defs><clipPath id=\"clip").append(id).append("\"><rect ").append(rect(bounds.bounds())).append("/></clipPath></defs>")
                                .append("<g clip-path=\"url(#clip").append(id).append(")\">");
                        groups.push(groups.pop() + 1);
                    }
                    case DrawCommand.FillRect fill -> xml.append("<rect ").append(rect(fill.bounds())).append(" fill=\"").append(color(fill.color())).append('"').append(opacity("fill-opacity", fillOpacity)).append("/>");
                    case DrawCommand.FillPath fill -> path(xml, fill.path(), "fill=\"" + color(fill.color()) + "\"" + opacity("fill-opacity", fillOpacity));
                    case DrawCommand.StrokePath stroke -> path(xml, stroke.path(), "fill=\"none\" stroke=\"" + color(stroke.color()) + "\" stroke-width=\"" + stroke.width() + "\" stroke-linecap=\"butt\" stroke-linejoin=\"miter\"" + opacity("stroke-opacity", strokeOpacity));
                    case DrawCommand.OutlineText text -> {
                        xml.append("<g aria-label=\"").append(escape(text.logicalText())).append("\"><title>").append(escape(text.logicalText())).append("</title>");
                        for (var p : text.paths()) { path(xml, p, "fill=\"" + color(text.color()) + "\"" + opacity("fill-opacity", fillOpacity)); }
                        xml.append("</g>");
                    }
                    case DrawCommand.Image image -> {
                        var png = new ByteArrayOutputStream();
                        if (!ImageIO.write(StaticBackendSupport.image(image.image()), "png", png)) { throw new IOException("PNG writer unavailable"); }
                        xml.append("<image ").append(rect(image.bounds())).append(opacity("opacity", fillOpacity)).append(" preserveAspectRatio=\"none\" href=\"data:image/png;base64,")
                                .append(Base64.getEncoder().encodeToString(png.toByteArray())).append("\"/>");
                    }
                    case DrawCommand.Text ignored -> throw new IllegalStateException("text must be converted before output");
                    case DrawCommand.GlyphText ignored -> throw new IllegalStateException("glyphs must be converted before output");
                }
            }
            xml.append("</g>".repeat(groups.pop())).append("</svg>");
            y += page.size().height() + 20;
        }
        xml.append("</svg>\n");
        output.write(xml.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String rect(Rect r) { return "x=\"" + r.x() + "\" y=\"" + r.y() + "\" width=\"" + r.width() + "\" height=\"" + r.height() + "\""; }
    private static String opacity(String attribute, double value) { return value == 1 ? "" : " " + attribute + "=\"" + value + "\""; }
    private static String color(RgbColor c) { return "rgb(" + c.red() * 100 + "% " + c.green() * 100 + "% " + c.blue() * 100 + "%)"; }

    private static void path(StringBuilder xml, VectorPath path, String attributes) {
        xml.append("<path ").append(attributes).append(" d=\"");
        for (var segment : path.segments()) {
            switch (segment) {
                case VectorPath.MoveTo m -> xml.append('M').append(m.point().x()).append(' ').append(m.point().y());
                case VectorPath.LineTo l -> xml.append('L').append(l.point().x()).append(' ').append(l.point().y());
                case VectorPath.CurveTo c -> xml.append('C').append(c.control1().x()).append(' ').append(c.control1().y()).append(' ')
                        .append(c.control2().x()).append(' ').append(c.control2().y()).append(' ').append(c.end().x()).append(' ').append(c.end().y());
                case VectorPath.Close ignored -> xml.append('Z');
            }
        }
        xml.append("\"/>");
    }

    private static String escape(String value) {
        if (value.codePoints().anyMatch(cp -> cp < 32 && cp != 9 && cp != 10 && cp != 13 || cp >= 0xd800 && cp <= 0xdfff || cp == 0xfffe || cp == 0xffff)) {
            throw new IllegalArgumentException("invalid XML character");
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }
}
