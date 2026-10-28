// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.w3c.dom.Element;
import com.mirakyux.export4j.graphics.AffineTransform;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.VectorPath;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.model.PageSize;

/** Offline SVG vectors with local definitions, gradients, patterns, markers, path clips and isolated groups.
 * Rejects DTD, external resources, scripts and unsupported styling; all expansion is bounded.
 */
public final class SvgComponent implements Component {
    private static final Pattern TOKEN = Pattern.compile("[MLCZ]|[-+]?(?:\\d*\\.\\d+|\\d+\\.?\\d*)(?:[eE][-+]?\\d+)?");
    private final DisplayPage drawing;

    private SvgComponent(DisplayPage drawing) {
        this.drawing = drawing;
    }

    /** Bounded to 1,048,576 UTF-16 code units and 10,000 elements. viewBox is required; aspect ratio is preserved. */
    public static SvgComponent parse(String xml) {
        return new SvgComponent(SvgParser.parse(xml));
    }

    @Override
    public ComponentLayout layout(ComponentContext context) {
        double scale = Math.min(context.width() / drawing.size().width(), context.height() / drawing.size().height());
        double width = drawing.size().width() * scale;
        double height = drawing.size().height() * scale;
        var builder = new DisplayListBuilder(new PageSize(width, height));
        builder.save().transform(new AffineTransform(scale, 0, 0, scale, 0, 0)).appendCommands(drawing.commands()).restore();
        return new ComponentLayout(width, height, 0, builder.build().commands(), List.of());
    }

    static VectorPath geometry(Element element) {
        String tag = element.getTagName();
        if (tag.equals("path")) {
            var tokens = new ArrayList<String>();
            var matcher = TOKEN.matcher(element.getAttribute("d"));
            int end = 0;
            while (matcher.find()) {
                if (!element.getAttribute("d").substring(end, matcher.start()).matches("[\\s,]*")) {
                    throw new IllegalArgumentException("unsupported SVG path command");
                }
                tokens.add(matcher.group());
                end = matcher.end();
            }
            if (!element.getAttribute("d").substring(end).isBlank()) {
                throw new IllegalArgumentException("invalid SVG path suffix");
            }
            List<VectorPath.Segment> segments = new ArrayList<>();
            for (int index = 0; index < tokens.size();) {
                String command = tokens.get(index++);
                int count = switch (command) { case "M", "L" -> 2; case "C" -> 6; case "Z" -> 0;
                    default -> throw new IllegalArgumentException("SVG paths require explicit absolute commands"); };
                if (index + count > tokens.size()) {
                    throw new IllegalArgumentException("incomplete SVG path command");
                }
                double[] coords = new double[count];
                for (int offset = 0; offset < count; offset++) {
                    coords[offset] = Double.parseDouble(tokens.get(index++));
                }
                segments.add(switch (command) {
                    case "M" -> new VectorPath.MoveTo(new Point(coords[0], coords[1]));
                    case "L" -> new VectorPath.LineTo(new Point(coords[0], coords[1]));
                    case "C" -> new VectorPath.CurveTo(new Point(coords[0], coords[1]), new Point(coords[2], coords[3]), new Point(coords[4], coords[5]));
                    default -> new VectorPath.Close();
                });
            }
            return new VectorPath(segments);
        }
        if (tag.equals("circle") || tag.equals("ellipse")) {
            double x = number(element, "cx", 0), y = number(element, "cy", 0);
            double rx = number(element, tag.equals("circle") ? "r" : "rx", 0);
            double ry = tag.equals("circle") ? rx : number(element, "ry", 0);
            if (rx <= 0 || ry <= 0) {
                throw new IllegalArgumentException("SVG radius must be positive");
            }
            double k = 0.5522847498307936;
            return new VectorPath(List.of(new VectorPath.MoveTo(new Point(x + rx, y)),
                    new VectorPath.CurveTo(new Point(x + rx, y + k * ry), new Point(x + k * rx, y + ry), new Point(x, y + ry)),
                    new VectorPath.CurveTo(new Point(x - k * rx, y + ry), new Point(x - rx, y + k * ry), new Point(x - rx, y)),
                    new VectorPath.CurveTo(new Point(x - rx, y - k * ry), new Point(x - k * rx, y - ry), new Point(x, y - ry)),
                    new VectorPath.CurveTo(new Point(x + k * rx, y - ry), new Point(x + rx, y - k * ry), new Point(x + rx, y)), new VectorPath.Close()));
        }
        double[] points;
        boolean closed = tag.equals("rect") || tag.equals("polygon");
        if (tag.equals("rect")) {
            double x = number(element, "x", 0), y = number(element, "y", 0);
            double w = number(element, "width", 0), h = number(element, "height", 0);
            new PageSize(w, h);
            points = new double[] {x, y, x + w, y, x + w, y + h, x, y + h};
        } else if (tag.equals("line")) {
            points = new double[] {number(element, "x1", 0), number(element, "y1", 0), number(element, "x2", 0), number(element, "y2", 0)};
        } else {
            points = numbers(element.getAttribute("points"));
        }
        if (points.length < 4 || points.length % 2 != 0) {
            throw new IllegalArgumentException("invalid SVG points");
        }
        List<VectorPath.Segment> segments = new ArrayList<>();
        segments.add(new VectorPath.MoveTo(new Point(points[0], points[1])));
        for (int index = 2; index < points.length; index += 2) {
            segments.add(new VectorPath.LineTo(new Point(points[index], points[index + 1])));
        }
        if (closed) {
            segments.add(new VectorPath.Close());
        }
        return new VectorPath(segments);
    }

    private static double number(Element element, String name, double fallback) {
        double value = element.hasAttribute(name) ? Double.parseDouble(element.getAttribute(name)) : fallback;
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("nonfinite SVG number");
        }
        return value;
    }

    private static double[] numbers(String input) {
        return java.util.Arrays.stream(input.trim().split("[\\s,]+" )).mapToDouble(Double::parseDouble).toArray();
    }

    static RgbColor color(String input) {
        if (!input.matches("#[0-9a-fA-F]{6}")) {
            throw new IllegalArgumentException("SVG colors require six-digit RGB hex or none");
        }
        int rgb = Integer.parseInt(input.substring(1), 16);
        return new RgbColor((rgb >> 16 & 255) / 255.0, (rgb >> 8 & 255) / 255.0, (rgb & 255) / 255.0);
    }
}
