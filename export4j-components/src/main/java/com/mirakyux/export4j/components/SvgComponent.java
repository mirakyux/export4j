// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
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

/** Offline SVG vector subset: groups, rect, circle/ellipse, line, polygon/polyline, absolute M/L/C/Z paths.
 * Rejects DTD, external resources, scripts, CSS, transforms and unsupported attributes rather than silently losing them.
 */
public final class SvgComponent implements Component {
    private static final Pattern TOKEN = Pattern.compile("[MLCZ]|[-+]?(?:\\d*\\.\\d+|\\d+\\.?\\d*)(?:[eE][-+]?\\d+)?");
    private final DisplayPage drawing;

    private SvgComponent(DisplayPage drawing) {
        this.drawing = drawing;
    }

    /** Bounded to 1,048,576 UTF-16 code units and 10,000 elements. viewBox is required; aspect ratio is preserved. */
    public static SvgComponent parse(String xml) {
        if (xml.length() > 1_048_576) {
            throw new IllegalArgumentException("SVG input budget exceeded");
        }
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var parser = factory.newDocumentBuilder();
            parser.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override public void fatalError(org.xml.sax.SAXParseException error) throws org.xml.sax.SAXException { throw error; }
                @Override public void error(org.xml.sax.SAXParseException error) throws org.xml.sax.SAXException { throw error; }
            });
            Element root = parser.parse(new InputSource(new StringReader(xml))).getDocumentElement();
            if (!root.getTagName().equals("svg") || root.getElementsByTagName("*").getLength() > 10_000) {
                throw new IllegalArgumentException("invalid SVG root or element budget");
            }
            attributes(root, Set.of("xmlns", "viewBox", "fill", "stroke", "stroke-width"));
            double[] view = numbers(root.getAttribute("viewBox"));
            if (view.length != 4) {
                throw new IllegalArgumentException("SVG requires a four-number viewBox");
            }
            var builder = new DisplayListBuilder(new PageSize(view[2], view[3]));
            builder.save().clip(new Rect(0, 0, view[2], view[3]))
                    .transform(AffineTransform.translation(-view[0], -view[1]));
            children(root, builder, new Ink("#000000", "none", 1), 0);
            builder.restore();
            return new SvgComponent(builder.build());
        } catch (javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException | java.io.IOException error) {
            throw new IllegalArgumentException("invalid or unsafe SVG", error);
        }
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

    private record Ink(String fill, String stroke, double width) { }

    private static void children(Element parent, DisplayListBuilder builder, Ink inherited, int depth) {
        if (depth > 64) {
            throw new IllegalArgumentException("SVG nesting budget exceeded");
        }
        Ink ink = new Ink(attr(parent, "fill", inherited.fill()), attr(parent, "stroke", inherited.stroke()),
                number(parent, "stroke-width", inherited.width()));
        if (ink.width() < 0) {
            throw new IllegalArgumentException("negative SVG stroke width");
        }
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element element)) {
                if (node.getNodeType() == org.w3c.dom.Node.TEXT_NODE && !node.getTextContent().isBlank()) {
                    throw new IllegalArgumentException("SVG text is unsupported");
                }
                continue;
            }
            String tag = element.getTagName();
            var allowed = new java.util.HashSet<>(Set.of("fill", "stroke", "stroke-width"));
            allowed.addAll(switch (tag) {
                case "g" -> Set.of();
                case "rect" -> Set.of("x", "y", "width", "height");
                case "circle" -> Set.of("cx", "cy", "r");
                case "ellipse" -> Set.of("cx", "cy", "rx", "ry");
                case "line" -> Set.of("x1", "y1", "x2", "y2");
                case "polygon", "polyline" -> Set.of("points");
                case "path" -> Set.of("d");
                default -> throw new IllegalArgumentException("unsupported SVG element: " + tag);
            });
            attributes(element, allowed);
            if (tag.equals("g")) {
                children(element, builder, ink, depth + 1);
                continue;
            }
            if (element.hasChildNodes()) {
                throw new IllegalArgumentException("SVG shape cannot contain child nodes");
            }
            var path = geometry(element);
            String fill = attr(element, "fill", ink.fill());
            String stroke = attr(element, "stroke", ink.stroke());
            double strokeWidth = number(element, "stroke-width", ink.width());
            if (strokeWidth < 0) {
                throw new IllegalArgumentException("negative SVG stroke width");
            }
            if (!fill.equals("none") && !tag.equals("line") && !tag.equals("polyline")) {
                builder.fillPath(path, color(fill));
            }
            if (!stroke.equals("none") && strokeWidth > 0) {
                builder.strokePath(path, color(stroke), strokeWidth);
            }
        }
    }

    private static VectorPath geometry(Element element) {
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

    private static void attributes(Element element, Set<String> allowed) {
        for (int index = 0; index < element.getAttributes().getLength(); index++) {
            if (!allowed.contains(element.getAttributes().item(index).getNodeName())) {
                throw new IllegalArgumentException("unsupported SVG attribute");
            }
        }
    }

    private static String attr(Element element, String name, String fallback) {
        return element.hasAttribute(name) ? element.getAttribute(name) : fallback;
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

    private static RgbColor color(String input) {
        if (!input.matches("#[0-9a-fA-F]{6}")) {
            throw new IllegalArgumentException("SVG colors require six-digit RGB hex or none");
        }
        int rgb = Integer.parseInt(input.substring(1), 16);
        return new RgbColor((rgb >> 16 & 255) / 255.0, (rgb >> 8 & 255) / 255.0, (rgb & 255) / 255.0);
    }
}
