// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import com.mirakyux.export4j.graphics.AffineTransform;
import com.mirakyux.export4j.graphics.BlendMode;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Paint;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.VectorPath;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.graphics.core.GraphicsResources;
import com.mirakyux.export4j.graphics.core.StaticBackendSupport;
import com.mirakyux.export4j.model.PageSize;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

/** Finite offline SVG vector graph. All references are local, resolved with cycle checks. */
final class SvgParser {
    private static final Set<String> COMMON = Set.of("id", "fill", "stroke", "stroke-width", "fill-opacity", "stroke-opacity", "opacity", "transform", "clip-path", "clip-rule", "fill-rule", "marker-start", "marker-mid", "marker-end", "style", "mix-blend-mode", "isolation");
    private static final Set<String> STYLE = Set.of("fill", "stroke", "stroke-width", "fill-opacity", "stroke-opacity", "opacity", "clip-path", "clip-rule", "fill-rule", "marker-start", "marker-mid", "marker-end", "stop-color", "stop-opacity", "mix-blend-mode", "isolation");
    private static final Set<String> SHAPES = Set.of("rect", "circle", "ellipse", "line", "polygon", "polyline", "path");
    private static final Pattern TRANSFORM = Pattern.compile("([A-Za-z]+)\\s*\\(([^()]*)\\)");
    private final Map<String, Element> ids = new HashMap<>();
    private final Set<Element> resolving = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Rect viewport;
    private final PageSize size;
    private int expanded;
    private SvgParser(Rect viewport) { this.viewport = viewport; size = new PageSize(viewport.width(), viewport.height()); }

    static DisplayPage parse(String source) {
        Objects.requireNonNull(source, "source"); if (source.length() > 1_048_576) { throw new IllegalArgumentException("SVG input budget exceeded"); }
        try {
            var factory = DocumentBuilderFactory.newInstance(); factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); factory.setFeature("http://xml.org/sax/features/external-general-entities", false); factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, ""); factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
            var parser = factory.newDocumentBuilder(); parser.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() { @Override public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; } @Override public void error(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; } });
            Element root = parser.parse(new InputSource(new StringReader(source))).getDocumentElement(); if (!root.getTagName().equals("svg") || root.getElementsByTagName("*").getLength() > 10_000) { throw new IllegalArgumentException("invalid SVG root or element budget"); }
            double[] view = numbers(root.getAttribute("viewBox")); if (view.length != 4) { throw new IllegalArgumentException("SVG requires a four-number viewBox"); }
            var engine = new SvgParser(new Rect(view[0], view[1], view[2], view[3])); engine.index(root, -1);
            var builder = new DisplayListBuilder(engine.size); builder.save().clip(new Rect(0, 0, view[2], view[3])).transform(AffineTransform.translation(-view[0], -view[1]));
            engine.node(root, builder, Ink.DEFAULT, -1); builder.restore(); return builder.build();
        } catch (javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException | java.io.IOException failure) { throw new IllegalArgumentException("invalid or unsafe SVG", failure); }
    }
    private void index(Element element, int depth) {
        if (depth > 64) { throw new IllegalArgumentException("SVG nesting budget exceeded"); }
        var allowed = new HashSet<>(COMMON); allowed.addAll(switch (element.getTagName()) {
            case "svg" -> Set.of("xmlns", "xmlns:xlink", "viewBox");
            case "symbol" -> Set.of("viewBox", "preserveAspectRatio");
            case "g", "defs", "title", "desc" -> Set.of();
            case "rect" -> Set.of("x", "y", "width", "height");
            case "circle" -> Set.of("cx", "cy", "r"); case "ellipse" -> Set.of("cx", "cy", "rx", "ry");
            case "line" -> Set.of("x1", "y1", "x2", "y2"); case "polygon", "polyline" -> Set.of("points"); case "path" -> Set.of("d");
            case "use" -> Set.of("href", "xlink:href", "x", "y", "width", "height");
            case "clipPath" -> Set.of("clipPathUnits");
            case "linearGradient" -> Set.of("href", "xlink:href", "x1", "y1", "x2", "y2", "gradientUnits", "gradientTransform", "spreadMethod");
            case "radialGradient" -> Set.of("href", "xlink:href", "cx", "cy", "r", "fx", "fy", "gradientUnits", "gradientTransform", "spreadMethod");
            case "stop" -> Set.of("offset", "stop-color", "stop-opacity");
            case "pattern" -> Set.of("href", "xlink:href", "x", "y", "width", "height", "patternUnits", "patternContentUnits", "patternTransform", "viewBox", "preserveAspectRatio");
            case "marker" -> Set.of("markerUnits", "markerWidth", "markerHeight", "refX", "refY", "orient", "viewBox", "preserveAspectRatio", "overflow");
            default -> throw new IllegalArgumentException("unsupported SVG element");
        });
        for (int i = 0; i < element.getAttributes().getLength(); i++) { if (!allowed.contains(element.getAttributes().item(i).getNodeName())) { throw new IllegalArgumentException("unsupported SVG attribute"); } }
        if (element.hasAttribute("style")) { styles(element); }
        if (element.hasAttribute("id")) { String id = element.getAttribute("id"); if (!id.matches("[A-Za-z_][A-Za-z0-9_.:-]{0,127}") || ids.putIfAbsent(id, element) != null) { throw new IllegalArgumentException("invalid or duplicate SVG id"); } }
        if (element.hasAttribute("href") || element.hasAttribute("xlink:href")) { local(property(element, "href", property(element, "xlink:href", ""))); }
        for (var child : children(element)) { index(child, depth + 1); }
    }
    private record Ink(String fill, String stroke, double width, double fillAlpha, double strokeAlpha, DrawCommand.FillRule rule, String start, String mid, String end) {
        static final Ink DEFAULT = new Ink("#000000", "none", 1, 1, 1, DrawCommand.FillRule.NONZERO, "none", "none", "none");
    }
    private Ink ink(Element element, Ink parent) {
        double width = value(property(element, "stroke-width", Double.toString(parent.width()))); if (width < 0) { throw new IllegalArgumentException("negative SVG stroke width"); }
        return new Ink(property(element, "fill", parent.fill()), property(element, "stroke", parent.stroke()), width,
                opacity(property(element, "fill-opacity", Double.toString(parent.fillAlpha()))), opacity(property(element, "stroke-opacity", Double.toString(parent.strokeAlpha()))),
                rule(property(element, "fill-rule", parent.rule() == DrawCommand.FillRule.NONZERO ? "nonzero" : "evenodd")), property(element, "marker-start", parent.start()), property(element, "marker-mid", parent.mid()), property(element, "marker-end", parent.end()));
    }
    private void node(Element element, DisplayListBuilder target, Ink inherited, int depth) {
        tick(depth); String tag = element.getTagName(); if (Set.of("defs", "title", "desc", "linearGradient", "radialGradient", "pattern", "marker", "clipPath", "stop", "symbol").contains(tag)) { return; }
        var ink = ink(element, inherited); double opacity = opacity(property(element, "opacity", "1")); BlendMode blend = blend(property(element, "mix-blend-mode", "normal"));
        boolean isolated = property(element, "isolation", "auto").equals("isolate"); if (!Set.of("auto", "isolate").contains(property(element, "isolation", "auto"))) { throw new IllegalArgumentException("invalid SVG isolation"); }
        boolean group = opacity != 1 || blend != BlendMode.NORMAL || isolated;
        var builder = group ? new DisplayListBuilder(size) : target;
        boolean scoped = element.hasAttribute("transform") || !property(element, "clip-path", "none").equals("none") || tag.equals("use") || SHAPES.contains(tag);
        if (scoped) { builder.save(); }
        if (element.hasAttribute("transform")) { builder.transform(transform(element.getAttribute("transform"))); }
        var geometry = SHAPES.contains(tag) ? SvgComponent.geometry(element) : null;
        Rectangle2D objectBounds = geometry == null ? bounds(element, depth + 1) : StaticBackendSupport.path(geometry).getBounds2D();
        String clip = property(element, "clip-path", "none"); if (!clip.equals("none")) { var area = clip(reference(clip, "clipPath"), objectBounds, depth + 1); builder.clipPath(GraphicsResources.vector(area), DrawCommand.FillRule.NONZERO); }
        if (geometry != null) {
            if (!children(element).isEmpty()) { throw new IllegalArgumentException("SVG shape cannot contain elements"); }
            builder.opacity(ink.fillAlpha(), ink.strokeAlpha());
            if (!ink.fill().equals("none") && !tag.equals("line")) {
                Paint fill = paint(ink.fill(), objectBounds, depth + 1);
                if (fill instanceof Paint.Solid solid && solid.color() instanceof RgbColor rgb && ink.rule() == DrawCommand.FillRule.NONZERO) { builder.fillPath(geometry, rgb); }
                else { builder.fillPath(geometry, fill, ink.rule()); }
            }
            if (!ink.stroke().equals("none") && ink.width() > 0) {
                Paint stroke = paint(ink.stroke(), objectBounds, depth + 1);
                if (stroke instanceof Paint.Solid solid && solid.color() instanceof RgbColor rgb) { builder.strokePath(geometry, rgb, ink.width()); }
                else { builder.strokePath(geometry, stroke, ink.width()); }
            }
            markers(geometry, ink, builder, depth + 1);
        } else if (tag.equals("use")) {
            Element reference = href(element); enter(reference);
            try {
                builder.transform(AffineTransform.translation(number(element, "x", 0), number(element, "y", 0)));
                if (reference.getTagName().equals("symbol")) {
                    var view = numbers(reference.getAttribute("viewBox")); if (view.length != 4) { throw new IllegalArgumentException("symbol requires viewBox"); }
                    double width = number(element, "width", view[2]), height = number(element, "height", view[3]); builder.clip(new Rect(0, 0, width, height)); builder.transform(viewport(view, width, height, property(reference, "preserveAspectRatio", "xMidYMid meet")));
                    for (var child : children(reference)) { node(child, builder, ink(reference, ink), depth + 1); }
                } else { node(reference, builder, ink, depth + 1); }
            } finally { leave(reference); }
        } else { for (var child : children(element)) { node(child, builder, ink, depth + 1); } }
        if (scoped) { builder.restore(); }
        if (group) { target.group(viewport, builder.build().commands(), opacity, blend); }
    }
    private void tick(int depth) { com.mirakyux.export4j.model.ExecutionContext.checkpoint(); if (depth > 64 || ++expanded > 100_000) { throw new IllegalArgumentException("SVG expansion budget exceeded"); } }
    private void enter(Element element) { if (!resolving.add(element)) { throw new IllegalArgumentException("cyclic SVG resource reference"); } if (resolving.size() > 32) { throw new IllegalArgumentException("SVG reference depth exceeded"); } }
    private void leave(Element element) { resolving.remove(element); }
    private Element href(Element element) { String source = property(element, "href", property(element, "xlink:href", "")); var reference = ids.get(local(source)); if (reference == null) { throw new IllegalArgumentException("unresolved local SVG reference"); } return reference; }
    private Element reference(String source, String tag) { if (!source.matches("url\\(#[A-Za-z_][A-Za-z0-9_.:-]{0,127}\\)")) { throw new IllegalArgumentException("SVG references must be local fragment URLs"); } var element = ids.get(source.substring(5, source.length() - 1)); if (element == null || tag != null && !element.getTagName().equals(tag)) { throw new IllegalArgumentException("unresolved or mismatched SVG resource"); } return element; }
    private static String local(String source) { if (!source.matches("#[A-Za-z_][A-Za-z0-9_.:-]{0,127}")) { throw new IllegalArgumentException("SVG links must be local fragments"); } return source.substring(1); }

    private Paint paint(String source, Rectangle2D bounds, int depth) {
        if (!source.startsWith("url(")) { return new Paint.Solid(color(source)); }
        var element = reference(source, null); enter(element);
        try {
            return switch (element.getTagName()) {
                case "linearGradient", "radialGradient" -> gradient(element, bounds);
                case "pattern" -> pattern(element, bounds, depth + 1);
                default -> throw new IllegalArgumentException("SVG paint reference has wrong type");
            };
        } finally { leave(element); }
    }
    private record Definition(Map<String, String> attributes, List<Element> content) { }
    private Definition definition(Element element) {
        var attributes = new HashMap<String, String>(); List<Element> content = List.of();
        if (element.hasAttribute("href") || element.hasAttribute("xlink:href")) {
            var parent = href(element); if (!parent.getTagName().equals(element.getTagName())) { throw new IllegalArgumentException("SVG inherited resource types differ"); } enter(parent);
            try { var inherited = definition(parent); attributes.putAll(inherited.attributes()); content = inherited.content(); } finally { leave(parent); }
        }
        for (int i = 0; i < element.getAttributes().getLength(); i++) { var attribute = element.getAttributes().item(i); attributes.put(attribute.getNodeName(), attribute.getNodeValue()); }
        if (!children(element).isEmpty()) { content = children(element); } return new Definition(Map.copyOf(attributes), content);
    }
    private Paint gradient(Element element, Rectangle2D bounds) {
        var definition = definition(element); var attributes = definition.attributes(); boolean object = units(attributes.getOrDefault("gradientUnits", "objectBoundingBox"));
        var stops = new ArrayList<Paint.Stop>(); double prior = 0;
        for (var stop : definition.content()) {
            if (!stop.getTagName().equals("stop")) { throw new IllegalArgumentException("gradient children must be stops"); }
            double offset = Math.max(prior, opacity(property(stop, "offset", "0"))); prior = offset;
            stops.add(new Paint.Stop(offset, color(property(stop, "stop-color", "#000000")), opacity(property(stop, "stop-opacity", "1"))));
        }
        if (stops.isEmpty() || stops.size() > 254) { throw new IllegalArgumentException("gradient stop budget requires 1..254 stops"); }
        if (stops.getFirst().offset() != 0) { var first = stops.getFirst(); stops.addFirst(new Paint.Stop(0, first.color(), first.opacity())); }
        if (stops.getLast().offset() != 1) { var last = stops.getLast(); stops.add(new Paint.Stop(1, last.color(), last.opacity())); }
        if (stops.size() == 1) { var first = stops.getFirst(); stops.add(new Paint.Stop(1, first.color(), first.opacity())); }
        AffineTransform matrix = resourceTransform(attributes.getOrDefault("gradientTransform", ""), object, bounds);
        var spread = switch (attributes.getOrDefault("spreadMethod", "pad")) { case "pad" -> Paint.Spread.PAD; case "repeat" -> Paint.Spread.REPEAT; case "reflect" -> Paint.Spread.REFLECT; default -> throw new IllegalArgumentException("invalid SVG gradient spread"); };
        if (element.getTagName().equals("linearGradient")) { return new Paint.Linear(new Point(coordinate(attributes.getOrDefault("x1", "0%"), object, true), coordinate(attributes.getOrDefault("y1", "0%"), object, false)), new Point(coordinate(attributes.getOrDefault("x2", "100%"), object, true), coordinate(attributes.getOrDefault("y2", "0%"), object, false)), stops, spread, matrix); }
        double cx = coordinate(attributes.getOrDefault("cx", "50%"), object, true), cy = coordinate(attributes.getOrDefault("cy", "50%"), object, false), radius = radialCoordinate(attributes.getOrDefault("r", "50%"), object);
        double fx = coordinate(attributes.getOrDefault("fx", attributes.getOrDefault("cx", "50%")), object, true), fy = coordinate(attributes.getOrDefault("fy", attributes.getOrDefault("cy", "50%")), object, false);
        double distance = Math.hypot(fx - cx, fy - cy); if (radius > 0 && distance >= radius) { double factor = Math.nextDown(radius) / distance; fx = cx + (fx - cx) * factor; fy = cy + (fy - cy) * factor; }
        return new Paint.Radial(new Point(cx, cy), new Point(fx, fy), radius, stops, spread, matrix);
    }
    private Paint pattern(Element element, Rectangle2D bounds, int depth) {
        var definition = definition(element); var attributes = definition.attributes(); boolean object = units(attributes.getOrDefault("patternUnits", "objectBoundingBox"));
        double x = coordinate(attributes.getOrDefault("x", "0"), object, true), y = coordinate(attributes.getOrDefault("y", "0"), object, false), width = coordinate(attributes.getOrDefault("width", "0"), object, true), height = coordinate(attributes.getOrDefault("height", "0"), object, false);
        if (width <= 0 || height <= 0) { throw new IllegalArgumentException("SVG pattern dimensions must be positive"); }
        var matrix = GraphicsResources.matrix(resourceTransform(attributes.getOrDefault("patternTransform", ""), object, bounds));
        var builder = new DisplayListBuilder(size); builder.save();
        if (attributes.containsKey("viewBox")) { builder.transform(AffineTransform.translation(x, y)); builder.transform(viewport(numbers(attributes.get("viewBox")), width, height, attributes.getOrDefault("preserveAspectRatio", "xMidYMid meet"))); }
        else {
            boolean contentObject = units(attributes.getOrDefault("patternContentUnits", "userSpaceOnUse"));
            if (object != contentObject) {
                var change = object ? new java.awt.geom.AffineTransform(1 / bounds.getWidth(), 0, 0, 1 / bounds.getHeight(), -bounds.getX() / bounds.getWidth(), -bounds.getY() / bounds.getHeight()) : new java.awt.geom.AffineTransform(bounds.getWidth(), 0, 0, bounds.getHeight(), bounds.getX(), bounds.getY()); builder.transform(GraphicsResources.matrix(change));
            }
        }
        for (var child : definition.content()) { node(child, builder, ink(element, Ink.DEFAULT), depth + 1); } builder.restore();
        return new Paint.Tile(new Rect(x, y, width, height), width, height, GraphicsResources.matrix(matrix), builder.build().commands());
    }
    private AffineTransform resourceTransform(String source, boolean object, Rectangle2D bounds) {
        var matrix = new java.awt.geom.AffineTransform(); if (object) { if (bounds.getWidth() <= 0 || bounds.getHeight() <= 0) { throw new IllegalArgumentException("SVG object-bounds resource requires nonzero bounds"); } matrix.translate(bounds.getX(), bounds.getY()); matrix.scale(bounds.getWidth(), bounds.getHeight()); }
        if (!source.isBlank()) { matrix.concatenate(GraphicsResources.matrix(transform(source))); } return GraphicsResources.matrix(matrix);
    }
    private double coordinate(String source, boolean object, boolean horizontal) { source = source.trim(); if (!source.endsWith("%")) { return value(source); } double fraction = value(source.substring(0, source.length() - 1)) / 100; return object ? fraction : fraction * (horizontal ? viewport.width() : viewport.height()) + (horizontal ? viewport.x() : viewport.y()); }
    private double radialCoordinate(String source, boolean object) { if (!source.endsWith("%")) { return value(source); } double fraction = value(source.substring(0, source.length() - 1)) / 100; return object ? fraction : fraction * Math.hypot(viewport.width(), viewport.height()) / Math.sqrt(2); }
    private static boolean units(String source) { return switch (source) { case "objectBoundingBox" -> true; case "userSpaceOnUse" -> false; default -> throw new IllegalArgumentException("invalid SVG resource units"); }; }

    private Area clip(Element element, Rectangle2D bounds, int depth) {
        tick(depth); enter(element);
        try {
            var result = new Area(); for (var child : children(element)) { result.add(clipShape(child, depth + 1)); }
            if (element.hasAttribute("transform")) { result.transform(GraphicsResources.matrix(transform(element.getAttribute("transform")))); }
            String nested = property(element, "clip-path", "none"); if (!nested.equals("none")) { result.intersect(clip(reference(nested, "clipPath"), result.getBounds2D(), depth + 1)); }
            if (units(property(element, "clipPathUnits", "userSpaceOnUse"))) { var matrix = new java.awt.geom.AffineTransform(); matrix.translate(bounds.getX(), bounds.getY()); matrix.scale(bounds.getWidth(), bounds.getHeight()); result.transform(matrix); }
            return result;
        } finally { leave(element); }
    }
    private Area clipShape(Element element, int depth) {
        tick(depth); Area result;
        if (SHAPES.contains(element.getTagName())) { result = new Area(GraphicsResources.path(SvgComponent.geometry(element), rule(property(element, "clip-rule", "nonzero")))); }
        else if (element.getTagName().equals("use")) { var reference = href(element); enter(reference); try { result = clipShape(reference, depth + 1); result.transform(java.awt.geom.AffineTransform.getTranslateInstance(number(element, "x", 0), number(element, "y", 0))); } finally { leave(reference); } }
        else { throw new IllegalArgumentException("unsupported SVG clipping content"); }
        String nested = property(element, "clip-path", "none"); if (!nested.equals("none")) { result.intersect(clip(reference(nested, "clipPath"), result.getBounds2D(), depth + 1)); }
        if (element.hasAttribute("transform")) { result.transform(GraphicsResources.matrix(transform(element.getAttribute("transform")))); } return result;
    }
    private Rectangle2D bounds(Element element, int depth) {
        tick(depth); var area = new Area();
        for (var child : children(element)) {
            java.awt.Shape shape;
            if (SHAPES.contains(child.getTagName())) { shape = StaticBackendSupport.path(SvgComponent.geometry(child)); }
            else if (child.getTagName().equals("g")) { shape = bounds(child, depth + 1); }
            else if (child.getTagName().equals("use")) { var reference = href(child); enter(reference); try { shape = SHAPES.contains(reference.getTagName()) ? StaticBackendSupport.path(SvgComponent.geometry(reference)) : bounds(reference, depth + 1); shape = java.awt.geom.AffineTransform.getTranslateInstance(number(child, "x", 0), number(child, "y", 0)).createTransformedShape(shape); } finally { leave(reference); } }
            else { continue; }
            if (child.hasAttribute("transform")) { shape = GraphicsResources.matrix(transform(child.getAttribute("transform"))).createTransformedShape(shape); } area.add(new Area(shape));
        }
        return area.getBounds2D();
    }

    private void markers(VectorPath path, Ink ink, DisplayListBuilder builder, int depth) {
        if (ink.start().equals("none") && ink.mid().equals("none") && ink.end().equals("none")) { return; }
        var edges = new ArrayList<Edge>(); Point current = null, first = null;
        for (var segment : path.segments()) {
            if (segment instanceof VectorPath.MoveTo move) { markerEdges(edges, ink, builder, depth); edges.clear(); current = move.point(); first = current; }
            else if (segment instanceof VectorPath.LineTo line) { edges.add(new Edge(current, line.point(), vector(current, line.point()), vector(current, line.point()))); current = line.point(); }
            else if (segment instanceof VectorPath.CurveTo curve) { var start = vector(current, curve.control1()); if (zero(start)) { start = vector(current, curve.control2()); } if (zero(start)) { start = vector(current, curve.end()); } var end = vector(curve.control2(), curve.end()); if (zero(end)) { end = vector(curve.control1(), curve.end()); } if (zero(end)) { end = vector(current, curve.end()); } edges.add(new Edge(current, curve.end(), start, end)); current = curve.end(); }
            else if (segment instanceof VectorPath.Close && current != null) { edges.add(new Edge(current, first, vector(current, first), vector(current, first))); current = first; }
        }
        markerEdges(edges, ink, builder, depth);
    }
    private record Edge(Point start, Point end, Point directionStart, Point directionEnd) { }
    private void markerEdges(List<Edge> edges, Ink ink, DisplayListBuilder builder, int depth) {
        if (edges.isEmpty()) { return; } marker(ink.start(), edges.getFirst().start(), angle(edges.getFirst().directionStart()), true, ink, builder, depth);
        for (int i = 1; i < edges.size(); i++) { var before = edges.get(i - 1).directionEnd(); var after = edges.get(i).directionStart(); double a = angle(before), b = angle(after), delta = Math.atan2(Math.sin(b - a), Math.cos(b - a)); marker(ink.mid(), edges.get(i).start(), a + delta / 2, false, ink, builder, depth); }
        marker(ink.end(), edges.getLast().end(), angle(edges.getLast().directionEnd()), false, ink, builder, depth);
    }
    private void marker(String source, Point point, double autoAngle, boolean start, Ink context, DisplayListBuilder builder, int depth) {
        if (source.equals("none")) { return; } var marker = reference(source, "marker"); enter(marker);
        try {
            double width = number(marker, "markerWidth", 3), height = number(marker, "markerHeight", 3), refX = number(marker, "refX", 0), refY = number(marker, "refY", 0); new PageSize(width, height);
            String orient = property(marker, "orient", "0"); double angle = orient.equals("auto") || orient.equals("auto-start-reverse") ? autoAngle + (orient.equals("auto-start-reverse") && start ? Math.PI : 0) : Math.toRadians(value(orient));
            double scale = switch (property(marker, "markerUnits", "strokeWidth")) { case "strokeWidth" -> context.width(); case "userSpaceOnUse" -> 1; default -> throw new IllegalArgumentException("invalid SVG marker units"); };
            if (scale == 0) { return; } builder.save().transform(AffineTransform.translation(point.x(), point.y())).transform(new AffineTransform(Math.cos(angle) * scale, Math.sin(angle) * scale, -Math.sin(angle) * scale, Math.cos(angle) * scale, 0, 0));
            AffineTransform viewMatrix = marker.hasAttribute("viewBox") ? viewport(numbers(marker.getAttribute("viewBox")), width, height, property(marker, "preserveAspectRatio", "xMidYMid meet")) : AffineTransform.IDENTITY;
            var reference = GraphicsResources.matrix(viewMatrix).transform(new java.awt.geom.Point2D.Double(refX, refY), null); builder.transform(AffineTransform.translation(-reference.getX(), -reference.getY()));
            if (!property(marker, "overflow", "hidden").equals("visible")) { builder.clip(new Rect(0, 0, width, height)); } builder.transform(viewMatrix);
            var markerInk = ink(marker, Ink.DEFAULT); markerInk = new Ink(contextColor(markerInk.fill(), context), contextColor(markerInk.stroke(), context), markerInk.width(), markerInk.fillAlpha(), markerInk.strokeAlpha(), markerInk.rule(), markerInk.start(), markerInk.mid(), markerInk.end());
            for (var child : children(marker)) { contextualNode(child, builder, markerInk, context, depth + 1); } builder.restore();
        } finally { leave(marker); }
    }
    private void contextualNode(Element element, DisplayListBuilder builder, Ink inherited, Ink context, int depth) {
        // Context paint is expanded on an independent element snapshot; the parsed document is immutable to callers.
        var copy = (Element) element.cloneNode(true); replaceContext(copy, context); node(copy, builder, inherited, depth);
    }
    private static void replaceContext(Element element, Ink context) { for (String name : List.of("fill", "stroke")) { if (element.hasAttribute(name)) { element.setAttribute(name, contextColor(element.getAttribute(name), context)); } } if (element.hasAttribute("style")) { String style = element.getAttribute("style").replace("context-fill", context.fill()).replace("context-stroke", context.stroke()); element.setAttribute("style", style); } for (var child : children(element)) { replaceContext(child, context); } }
    private static String contextColor(String value, Ink context) { return value.equals("context-fill") ? context.fill() : value.equals("context-stroke") ? context.stroke() : value; }
    private static Point vector(Point a, Point b) { return new Point(b.x() - a.x(), b.y() - a.y()); }
    private static boolean zero(Point p) { return p.x() == 0 && p.y() == 0; }
    private static double angle(Point p) { return Math.atan2(p.y(), p.x()); }

    private static AffineTransform viewport(double[] view, double width, double height, String aspect) {
        if (view.length != 4 || view[2] <= 0 || view[3] <= 0) { throw new IllegalArgumentException("invalid SVG viewport"); }
        double sx = width / view[2], sy = height / view[3], x = 0, y = 0;
        if (!aspect.equals("none")) { var parts = aspect.trim().split("\\s+"); if (parts.length > 2 || !parts[0].matches("x(Min|Mid|Max)Y(Min|Mid|Max)") || parts.length == 2 && !Set.of("meet", "slice").contains(parts[1])) { throw new IllegalArgumentException("invalid SVG aspect ratio"); } double scale = parts.length == 2 && parts[1].equals("slice") ? Math.max(sx, sy) : Math.min(sx, sy); sx = sy = scale; x = (width - view[2] * sx) * (parts[0].contains("xMid") ? .5 : parts[0].contains("xMax") ? 1 : 0); y = (height - view[3] * sy) * (parts[0].contains("YMid") ? .5 : parts[0].contains("YMax") ? 1 : 0); }
        return new AffineTransform(sx, 0, 0, sy, x - view[0] * sx, y - view[1] * sy);
    }
    static AffineTransform transform(String source) {
        var result = new java.awt.geom.AffineTransform(); var matcher = TRANSFORM.matcher(source); int end = 0, count = 0;
        while (matcher.find()) {
            if (!source.substring(end, matcher.start()).matches("[\\s,]*") || ++count > 64) { throw new IllegalArgumentException("invalid or excessive SVG transforms"); } double[] v = numbers(matcher.group(2));
            switch (matcher.group(1)) {
                case "matrix" -> { arity(v, 6, 6); result.concatenate(new java.awt.geom.AffineTransform(v)); }
                case "translate" -> { arity(v, 1, 2); result.translate(v[0], v.length == 2 ? v[1] : 0); }
                case "scale" -> { arity(v, 1, 2); result.scale(v[0], v.length == 2 ? v[1] : v[0]); }
                case "rotate" -> { if (v.length != 1 && v.length != 3) { throw new IllegalArgumentException("invalid SVG rotate arguments"); } if (v.length == 3) { result.rotate(Math.toRadians(v[0]), v[1], v[2]); } else { result.rotate(Math.toRadians(v[0])); } }
                case "skewX", "skewY" -> { arity(v, 1, 1); double tangent = Math.tan(Math.toRadians(v[0])); result.shear(matcher.group(1).equals("skewX") ? tangent : 0, matcher.group(1).equals("skewY") ? tangent : 0); }
                default -> throw new IllegalArgumentException("unsupported SVG transform");
            }
            end = matcher.end();
        }
        if (count == 0 || !source.substring(end).isBlank()) { throw new IllegalArgumentException("invalid SVG transform suffix"); } return GraphicsResources.matrix(result);
    }
    private static void arity(double[] v, int min, int max) { if (v.length < min || v.length > max) { throw new IllegalArgumentException("invalid SVG transform arguments"); } }
    private static List<Element> children(Element parent) {
        var result = new ArrayList<Element>(); for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) { if (node instanceof Element child) { result.add(child); } else if (node.getNodeType() == org.w3c.dom.Node.TEXT_NODE && !node.getTextContent().isBlank() && !Set.of("title", "desc").contains(parent.getTagName())) { throw new IllegalArgumentException("SVG text content is unsupported"); } } return result;
    }
    private static Map<String, String> styles(Element element) {
        var result = new HashMap<String, String>(); for (String item : element.getAttribute("style").split(";")) { if (item.isBlank()) { continue; } int colon = item.indexOf(':'); if (colon < 0 || !STYLE.contains(item.substring(0, colon).trim())) { throw new IllegalArgumentException("unsupported SVG style property"); } result.put(item.substring(0, colon).trim(), item.substring(colon + 1).trim()); } return result;
    }
    private static String property(Element element, String name, String fallback) { return styles(element).getOrDefault(name, element.hasAttribute(name) ? element.getAttribute(name).trim() : fallback); }
    private static double number(Element element, String name, double fallback) { return value(property(element, name, Double.toString(fallback))); }
    private static double value(String source) { double value = Double.parseDouble(source); if (!Double.isFinite(value)) { throw new IllegalArgumentException("nonfinite SVG number"); } return value; }
    private static double[] numbers(String input) { if (input.isBlank()) { return new double[0]; } return Arrays.stream(input.trim().split("[\\s,]+")).mapToDouble(SvgParser::value).toArray(); }
    private static double opacity(String source) { double value = source.endsWith("%") ? value(source.substring(0, source.length() - 1)) / 100 : value(source); if (value < 0 || value > 1) { throw new IllegalArgumentException("SVG opacity must be in [0,1]"); } return value; }
    private static DrawCommand.FillRule rule(String source) { return switch (source) { case "nonzero" -> DrawCommand.FillRule.NONZERO; case "evenodd" -> DrawCommand.FillRule.EVEN_ODD; default -> throw new IllegalArgumentException("invalid SVG winding rule"); }; }
    private static BlendMode blend(String source) { try { return BlendMode.valueOf(source.toUpperCase(Locale.ROOT).replace('-', '_')); } catch (IllegalArgumentException failure) { throw new IllegalArgumentException("unsupported SVG blend mode"); } }
    private static RgbColor color(String source) { source = switch (source) { case "black" -> "#000000"; case "white" -> "#ffffff"; case "red" -> "#ff0000"; case "green" -> "#008000"; case "blue" -> "#0000ff"; default -> source; }; if (source.matches("#[0-9a-fA-F]{3}")) { source = "#" + source.charAt(1) + source.charAt(1) + source.charAt(2) + source.charAt(2) + source.charAt(3) + source.charAt(3); } return SvgComponent.color(source); }
}
