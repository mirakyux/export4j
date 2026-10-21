// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Element;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineImage;

/** Package-local DrawingML subset. Explicit embedded PNG/JPEG only; never resolves external resources. */
final class DocxImages {
    static final class LimitException extends IOException {
        @java.io.Serial private static final long serialVersionUID = 1L;
        LimitException() { super("DOCX image resource budget exceeded"); }
    }
    static final String WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing";
    static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";
    static final String PIC = "http://schemas.openxmlformats.org/drawingml/2006/picture";
    static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private DocxImages() { }

    static final class Writer {
        private final Map<String, ImageResource> resources = new LinkedHashMap<>();
        private final int maxDrawingCharacters;
        private long drawingCharacters;
        private int drawingId;
        Writer(int maxDrawingCharacters) { this.maxDrawingCharacters = maxDrawingCharacters; }

        String drawing(InlineImage image, String hyperlinkId) throws IOException {
            var resource = image.resource(); resources.putIfAbsent(resource.id(), resource);
            checkBudget(resources);
            int id = ++drawingId;
            long cx = Math.round(image.width() * 12700); long cy = Math.round(image.height() * 12700);
            if (cx <= 0 || cy <= 0) { throw new IOException("inline image dimensions are below one EMU"); }
            String xml = "<w:drawing xmlns:wp=\"" + WP + "\" xmlns:a=\"" + A + "\" xmlns:pic=\"" + PIC + "\">"
                    + "<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"" + cx + "\" cy=\"" + cy + "\"/>"
                    + "<wp:docPr id=\"" + id + "\" name=\"Image " + id + "\" descr=\"" + escape(image.altText()) + "\">"
                    + (hyperlinkId.isEmpty() ? "" : "<a:hlinkClick r:id=\"" + hyperlinkId + "\"/>") + "</wp:docPr>"
                    + "<a:graphic><a:graphicData uri=\"" + PIC + "\"><pic:pic><pic:nvPicPr><pic:cNvPr id=\"" + id + "\" name=\"Image " + id + "\"/>"
                    + "<pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed=\"img-" + resource.id() + "\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>"
                    + "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"" + cx + "\" cy=\"" + cy + "\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>"
                    + "</pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing>";
            drawingCharacters += xml.length();
            if (drawingCharacters > maxDrawingCharacters) { throw new IOException("DOCX image markup budget exceeded"); }
            return xml;
        }

        Map<String, byte[]> parts() {
            Map<String, byte[]> result = new LinkedHashMap<>();
            resources.forEach((id, resource) -> result.put("word/media/" + id + extension(resource), resource.bytes()));
            return result;
        }

        String relationships() {
            var xml = new StringBuilder();
            resources.forEach((id, resource) -> xml.append("<Relationship Id=\"img-").append(id).append("\" Type=\"").append(R)
                    .append("/image\" Target=\"media/").append(id).append(extension(resource)).append("\"/>"));
            return xml.toString();
        }
        private static String extension(ImageResource image) { return image.mediaType().equals("image/png") ? ".png" : ".jpg"; }
    }

    static final class Reader {
        private final Map<String, byte[]> parts;
        private final Map<String, String> targets = new HashMap<>();
        private final Map<String, String> hyperlinks = new HashMap<>();
        private final Map<String, ImageResource> decoded = new HashMap<>();
        Reader(Map<String, byte[]> parts, Element relationships) throws IOException {
            this.parts = parts;
            if (relationships == null) { return; }
            Set<String> ids = new java.util.HashSet<>();
            for (var relation : elements(relationships)) {
                String id = relation.getAttribute("Id");
                if (id.isEmpty() || !ids.add(id)) { throw new IOException("missing or duplicate DOCX relationship ID"); }
                if (relation.getAttribute("Type").equals(R + "/hyperlink") && relation.getAttribute("TargetMode").equals("External")) {
                    hyperlinks.put(id, new com.mirakyux.export4j.model.ExternalDestination(relation.getAttribute("Target")).uri());
                }
                if (!relation.getAttribute("Type").equals(R + "/image")) { continue; }
                if (!relation.getAttribute("TargetMode").isEmpty() && !relation.getAttribute("TargetMode").equals("Internal")) { continue; }
                try {
                    URI target = URI.create(relation.getAttribute("Target"));
                    if (target.isAbsolute() || target.getRawAuthority() != null || target.getQuery() != null || target.getFragment() != null) { throw new IllegalArgumentException(); }
                    String path = target.getPath();
                    if (path == null || path.startsWith("/") || path.contains("\\") || path.contains(":") || java.util.List.of(path.split("/", -1)).contains("..")) { throw new IllegalArgumentException(); }
                    String part = "word/" + path;
                    if (!part.startsWith("word/media/") || !parts.containsKey(part)) { throw new IOException("embedded DOCX image part is missing or outside media"); }
                    targets.put(id, part);
                } catch (IllegalArgumentException failure) { throw new IOException("unsafe DOCX image relationship", failure); }
            }
        }

        InlineImage read(Element drawing, double shift, String hyperlink) throws IOException {
            for (int i = 0; i < drawing.getAttributes().getLength(); i++) {
                if (!"http://www.w3.org/2000/xmlns/".equals(drawing.getAttributes().item(i).getNamespaceURI())) {
                    throw new IOException("unsupported drawing wrapper attribute");
                }
            }
            if (elements(drawing).size() != 1) { throw new IOException("inline image requires one drawing"); }
            var inline = direct(drawing, WP, "inline");
            var extent = direct(inline, WP, "extent");
            var graphic = direct(inline, A, "graphic");
            var data = direct(graphic, A, "graphicData");
            var picture = direct(data, PIC, "pic");
            var fill = direct(picture, PIC, "blipFill");
            var blip = direct(fill, A, "blip");
            var properties = direct(inline, WP, "docPr");
            var link = direct(properties, A, "hlinkClick");
            if (link != null) {
                String destination = hyperlinks.get(link.getAttributeNS(R, "id"));
                if (destination == null || !hyperlink.isEmpty() && !hyperlink.equals(destination)) { throw new IOException("missing or inconsistent image hyperlink"); }
                hyperlink = destination;
            }
            var shape = direct(picture, PIC, "spPr");
            var transform = direct(shape, A, "xfrm");
            var shapeExtent = direct(transform, A, "ext");
            if (inline == null || extent == null || data == null || !PIC.equals(data.getAttribute("uri")) || blip == null
                    || shapeExtent == null || !extent.getAttribute("cx").equals(shapeExtent.getAttribute("cx"))
                    || !extent.getAttribute("cy").equals(shapeExtent.getAttribute("cy"))) { throw new IOException("incomplete or inconsistent inline DrawingML image"); }
            String part = targets.get(blip.getAttributeNS(R, "embed"));
            if (part == null) { throw new IOException("inline image relationship is missing or external"); }
            ImageResource resource = decoded.get(part);
            if (resource == null) { resource = ImageResource.load(parts.get(part)); decoded.put(part, resource); checkBudget(decoded); }
            try {
                return new InlineImage(resource, Long.parseLong(extent.getAttribute("cx")) / 12700.0,
                        Long.parseLong(extent.getAttribute("cy")) / 12700.0, shift, properties == null ? "" : properties.getAttribute("descr"), hyperlink);
            } catch (IllegalArgumentException failure) { throw new IOException("invalid inline image dimensions or description", failure); }
        }

        boolean contains(String part) { return targets.containsValue(part); }
    }

    /** Rejects unsupported transformations, cropping, effects, linked images and unknown drawing properties. */
    static void validate(Element root) throws IOException {
        String namespace = root.getNamespaceURI(); String name = root.getLocalName();
        Set<String> attributes;
        if (WP.equals(namespace)) {
            attributes = switch (name) {
                case "inline" -> Set.of("distT", "distB", "distL", "distR");
                case "extent" -> Set.of("cx", "cy");
                case "docPr" -> Set.of("id", "name", "descr");
                case "effectExtent" -> Set.of("l", "t", "r", "b");
                case "cNvGraphicFramePr" -> Set.of();
                default -> throw new IOException("unsupported Word drawing markup");
            };
        } else if (A.equals(namespace)) {
            attributes = switch (name) {
                case "graphic", "stretch", "fillRect", "avLst", "xfrm" -> Set.of();
                case "graphicData" -> Set.of("uri");
                case "hlinkClick" -> Set.of("id");
                case "blip" -> Set.of("embed", "cstate");
                case "off" -> Set.of("x", "y");
                case "ext" -> Set.of("cx", "cy");
                case "prstGeom" -> Set.of("prst");
                case "graphicFrameLocks", "picLocks" -> Set.of("noChangeAspect");
                default -> throw new IOException("unsupported DrawingML markup");
            };
        } else if (PIC.equals(namespace)) {
            attributes = switch (name) {
                case "pic", "nvPicPr", "cNvPicPr", "blipFill", "spPr" -> Set.of();
                case "cNvPr" -> Set.of("id", "name", "descr");
                default -> throw new IOException("unsupported picture markup");
            };
        } else { throw new IOException("unsupported drawing namespace"); }
        for (int i = 0; i < root.getAttributes().getLength(); i++) {
            var attr = root.getAttributes().item(i);
            if ("http://www.w3.org/2000/xmlns/".equals(attr.getNamespaceURI())) { continue; }
            String attribute = attr.getLocalName() == null ? attr.getNodeName() : attr.getLocalName();
            boolean embedded = name.equals("blip") && attribute.equals("embed") || name.equals("hlinkClick") && attribute.equals("id");
            if (!attributes.contains(attribute) || embedded != R.equals(attr.getNamespaceURI())
                    || !embedded && attr.getNamespaceURI() != null) { throw new IOException("unsupported drawing attribute"); }
        }
        if (name.equals("inline")) { for (String distance : attributes) { if (root.hasAttribute(distance) && !root.getAttribute(distance).equals("0")) { throw new IOException("inline image distances are unsupported"); } } }
        if (name.equals("off") && (!root.getAttribute("x").equals("0") || !root.getAttribute("y").equals("0"))) { throw new IOException("inline image transform offset is unsupported"); }
        if (name.equals("prstGeom") && !root.getAttribute("prst").equals("rect")) { throw new IOException("nonrectangular image geometry is unsupported"); }
        if (name.equals("effectExtent")) { for (String edge : attributes) { if (!root.getAttribute(edge).equals("0")) { throw new IOException("image effects are unsupported"); } } }
        Set<String> allowedChildren = switch (name) {
            case "inline" -> Set.of(WP + "|extent", WP + "|docPr", WP + "|cNvGraphicFramePr", WP + "|effectExtent", A + "|graphic");
            case "cNvGraphicFramePr" -> Set.of(A + "|graphicFrameLocks");
            case "docPr" -> Set.of(A + "|hlinkClick");
            case "graphic" -> Set.of(A + "|graphicData");
            case "graphicData" -> Set.of(PIC + "|pic");
            case "pic" -> Set.of(PIC + "|nvPicPr", PIC + "|blipFill", PIC + "|spPr");
            case "nvPicPr" -> Set.of(PIC + "|cNvPr", PIC + "|cNvPicPr");
            case "cNvPicPr" -> Set.of(A + "|picLocks");
            case "blipFill" -> Set.of(A + "|blip", A + "|stretch");
            case "stretch" -> Set.of(A + "|fillRect");
            case "spPr" -> Set.of(A + "|xfrm", A + "|prstGeom");
            case "xfrm" -> Set.of(A + "|off", A + "|ext");
            case "prstGeom" -> Set.of(A + "|avLst");
            default -> Set.of();
        };
        Set<String> seen = new java.util.HashSet<>();
        for (var child : elements(root)) {
            String key = child.getNamespaceURI() + "|" + child.getLocalName();
            if (!allowedChildren.contains(key) || !seen.add(key)) { throw new IOException("unsupported or duplicate image drawing child"); }
            validate(child);
        }
    }

    private static void checkBudget(Map<String, ImageResource> resources) throws IOException {
        long bytes = 0; long pixels = 0;
        for (var resource : resources.values()) { bytes += resource.byteSize(); pixels += (long) resource.pixelWidth() * resource.pixelHeight(); }
        if (bytes > 64L * 1024 * 1024 || pixels > 50_000_000) { throw new LimitException(); }
    }

    private static java.util.List<Element> elements(Element element) {
        var result = new java.util.ArrayList<Element>();
        if (element != null) { for (var node = element.getFirstChild(); node != null; node = node.getNextSibling()) { if (node instanceof Element child) { result.add(child); } } }
        return result;
    }
    private static Element direct(Element element, String namespace, String name) throws IOException {
        Element result = null;
        for (var child : elements(element)) { if (namespace.equals(child.getNamespaceURI()) && name.equals(child.getLocalName())) { if (result != null) { throw new IOException("duplicate image drawing element"); } result = child; } }
        return result;
    }
    private static String escape(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;"); }
}
