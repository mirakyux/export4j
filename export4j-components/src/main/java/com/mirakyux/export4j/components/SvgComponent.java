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

/** Offline SVG vector subset: groups, shapes, absolute M/L/C/Z paths, affine transforms and paint opacity.
 * Rejects DTD, external resources, scripts, CSS, isolated group opacity and unsupported attributes.
 */
public final class SvgComponent implements Component {
    private static final Pattern TOKEN = Pattern.compile("[MLCZ]|[-+]?(?:\\d*\\.\\d+|\\d+\\.?\\d*)(?:[eE][-+]?\\d+)?");
    private static final Pattern TRANSFORM = Pattern.compile("([A-Za-z]+)\\s*\\(([^()]*)\\)");
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
            attributes(root, Set.of("xmlns", "viewBox", "fill", "stroke", "stroke-width", "fill-opacity", "stroke-opacity", "transform"));
            double[] view = numbers(root.getAttribute("viewBox"));
            if (view.length != 4) {
                throw new IllegalArgumentException("SVG requires a four-number viewBox");
            }
            var builder = new DisplayListBuilder(new PageSize(view[2], view[3]));
            builder.save().clip(new Rect(0, 0, view[2], view[3]))
                    .transform(AffineTransform.translation(-view[0], -view[1]));
            children(root, builder, new Ink("#000000", "none", 1, 1, 1), 0);
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

    private record Ink(String fill, String stroke, double width, double fillOpacity, double strokeOpacity) { }

    private static void children(Element parent, DisplayListBuilder builder, Ink inherited, int depth) {
        if (depth > 64) {
            throw new IllegalArgumentException("SVG nesting budget exceeded");
        }
        Ink ink = new Ink(attr(parent, "fill", inherited.fill()), attr(parent, "stroke", inherited.stroke()),
                number(parent, "stroke-width", inherited.width()),
                opacity(parent, "fill-opacity", inherited.fillOpacity()), opacity(parent, "stroke-opacity", inherited.strokeOpacity()));
        if (ink.width() < 0) {
            throw new IllegalArgumentException("negative SVG stroke width");
        }
        boolean transformed = parent.hasAttribute("transform");
        if (transformed) { builder.save(); transform(parent, builder); }
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element element)) {
                if (node.getNodeType() == org.w3c.dom.Node.TEXT_NODE && !node.getTextContent().isBlank()) {
                    throw new IllegalArgumentException("SVG text is unsupported");
                }
                continue;
            }
            String tag = element.getTagName();
            var allowed = new java.util.HashSet<>(Set.of("fill", "stroke", "stroke-width", "fill-opacity", "stroke-opacity", "transform"));
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
            builder.save();
            transform(element, builder);
            builder.opacity(opacity(element, "fill-opacity", ink.fillOpacity()), opacity(element, "stroke-opacity", ink.strokeOpacity()));
            if (!fill.equals("none") && !tag.equals("line") && !tag.equals("polyline")) {
                builder.fillPath(path, color(fill));
            }
            if (!stroke.equals("none") && strokeWidth > 0) {
                builder.strokePath(path, color(stroke), strokeWidth);
            }
            builder.restore();
        }
        if (transformed) { builder.restore(); }
    }

    private static double opacity(Element element, String name, double fallback) {
        if (!element.hasAttribute(name)) { return fallback; }
        String source = element.getAttribute(name).trim();
        double value = source.endsWith("%") ? Double.parseDouble(source.substring(0, source.length() - 1)) / 100
                : Double.parseDouble(source);
        if (!Double.isFinite(value) || value < 0 || value > 1) { throw new IllegalArgumentException("SVG paint opacity must be in [0,1]"); }
        return value;
    }

    private static void transform(Element element, DisplayListBuilder builder) {
        if (!element.hasAttribute("transform")) { return; }
        String source = element.getAttribute("transform");
        var matcher = TRANSFORM.matcher(source);
        int end = 0;
        int count = 0;
        while (matcher.find()) {
            if (!source.substring(end, matcher.start()).matches("[\\s,]*") || ++count > 64) {
                throw new IllegalArgumentException("invalid or excessive SVG transforms");
            }
            double[] values = numbers(matcher.group(2));
            if (java.util.Arrays.stream(values).anyMatch(value -> !Double.isFinite(value))) {
                throw new IllegalArgumentException("nonfinite SVG transform");
            }
            switch (matcher.group(1)) {
                case "matrix" -> {
                    arity(values, 6, 6);
                    builder.transform(new AffineTransform(values[0], values[1], values[2], values[3], values[4], values[5]));
                }
                case "translate" -> {
                    arity(values, 1, 2);
                    builder.transform(AffineTransform.translation(values[0], values.length == 2 ? values[1] : 0));
                }
                case "scale" -> {
                    arity(values, 1, 2);
                    builder.transform(new AffineTransform(values[0], 0, 0, values.length == 2 ? values[1] : values[0], 0, 0));
                }
                case "rotate" -> {
                    if (values.length != 1 && values.length != 3) { throw new IllegalArgumentException("invalid SVG rotate arguments"); }
                    double radians = Math.toRadians(values[0]);
                    double cosine = Math.cos(radians);
                    double sine = Math.sin(radians);
                    if (values.length == 3) { builder.transform(AffineTransform.translation(values[1], values[2])); }
                    builder.transform(new AffineTransform(cosine, sine, -sine, cosine, 0, 0));
                    if (values.length == 3) { builder.transform(AffineTransform.translation(-values[1], -values[2])); }
                }
                case "skewX", "skewY" -> {
                    arity(values, 1, 1);
                    double tangent = Math.tan(Math.toRadians(values[0]));
                    builder.transform(matcher.group(1).equals("skewX") ? new AffineTransform(1, 0, tangent, 1, 0, 0)
                            : new AffineTransform(1, tangent, 0, 1, 0, 0));
                }
                default -> throw new IllegalArgumentException("unsupported SVG transform");
            }
            end = matcher.end();
        }
        if (count == 0 || !source.substring(end).isBlank()) { throw new IllegalArgumentException("invalid SVG transform suffix"); }
    }

    private static void arity(double[] values, int minimum, int maximum) {
        if (values.length < minimum || values.length > maximum) { throw new IllegalArgumentException("invalid SVG transform arguments"); }
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
