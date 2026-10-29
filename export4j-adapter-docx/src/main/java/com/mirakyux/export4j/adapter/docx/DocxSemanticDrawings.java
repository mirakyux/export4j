// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Align;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Axis;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Drawing;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Picture;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Placement;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.RelativeTo;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Transform;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Vertex;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Wrap;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.WrapSide;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.A;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.PIC;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.R;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.W;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.WP;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.WPS;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.all;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.attr;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.direct;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.element;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.emu;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.is;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.number;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.on;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.paragraph;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.remove;
import static com.mirakyux.export4j.adapter.docx.DocxAdapter.children;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import org.w3c.dom.Element;

final class DocxSemanticDrawings {
    private DocxSemanticDrawings() { }
    static Placement placement(Element container) throws IOException {
        if (container.getLocalName().equals("inline")) { return Placement.inline(); }
        var wrap = Wrap.NONE; var side = WrapSide.BOTH; var polygon = new ArrayList<Vertex>();
        for (var e : children(container)) {
            if (!WP.equals(e.getNamespaceURI()) || !e.getLocalName().startsWith("wrap")) { continue; }
            wrap = switch(e.getLocalName()) { case "wrapNone" -> Wrap.NONE; case "wrapSquare" -> Wrap.SQUARE; case "wrapTopAndBottom" -> Wrap.TOP_AND_BOTTOM; case "wrapTight" -> Wrap.TIGHT; case "wrapThrough" -> Wrap.THROUGH; default -> throw new IOException("unsupported drawing wrap"); };
            side = switch(e.getAttribute("wrapText")) { case "left" -> WrapSide.LEFT; case "right" -> WrapSide.RIGHT; case "largest" -> WrapSide.LARGEST; default -> WrapSide.BOTH; };
            var shape = direct(e, WP, "wrapPolygon"); for (var v : children(shape)) { polygon.add(new Vertex(number(v.getAttribute("x")), number(v.getAttribute("y")))); }
        }
        return new Placement(axis(direct(container, WP, "positionH")), axis(direct(container, WP, "positionV")), wrap, side, polygon,
                flag(container, "behindDoc", false), flag(container, "allowOverlap", true), flag(container, "layoutInCell", true), longNumber(container.getAttribute("relativeHeight"), 0),
                inset(container, "distT", 0), inset(container, "distR", 0), inset(container, "distB", 0), inset(container, "distL", 0));
    }
    private static Axis axis(Element e) throws IOException {
        if (e == null) { throw new IOException("floating drawing requires both positioning axes"); }
        var base = switch(e.getAttribute("relativeFrom")) { case "page" -> RelativeTo.PAGE; case "margin" -> RelativeTo.MARGIN; case "column" -> RelativeTo.COLUMN; case "paragraph" -> RelativeTo.PARAGRAPH; case "character" -> RelativeTo.CHARACTER; case "line" -> RelativeTo.LINE; case "leftMargin" -> RelativeTo.LEFT_MARGIN; case "rightMargin" -> RelativeTo.RIGHT_MARGIN; case "insideMargin" -> RelativeTo.INSIDE_MARGIN; case "outsideMargin" -> RelativeTo.OUTSIDE_MARGIN; case "topMargin" -> RelativeTo.TOP_MARGIN; case "bottomMargin" -> RelativeTo.BOTTOM_MARGIN; default -> throw new IOException("unknown drawing position base"); };
        var align = direct(e, WP, "align"); var offset = direct(e, WP, "posOffset"); if ((align == null) == (offset == null)) { throw new IOException("drawing axis must have exactly one alignment or offset"); }
        if (offset != null) { return Axis.offset(base, number(offset.getTextContent()) / 12700); }
        var value = switch(align.getTextContent()) { case "left" -> Align.LEFT; case "center" -> Align.CENTER; case "right" -> Align.RIGHT; case "top" -> Align.TOP; case "bottom" -> Align.BOTTOM; case "inside" -> Align.INSIDE; case "outside" -> Align.OUTSIDE; default -> throw new IOException("unsupported drawing alignment"); };
        return new Axis(base, value, 0);
    }
    static Transform transform(Element transform, Element crop) throws IOException {
        if (transform == null) { throw new IOException("native drawing transform missing"); }
        var off = direct(transform, A, "off"); if (off == null || number(off.getAttribute("x")) != 0 || number(off.getAttribute("y")) != 0) { throw new IOException("nonzero local drawing offsets need a group coordinate mapping"); }
        return new Transform(number(defaultAttribute(transform, "rot", "0")) / 60000, flag(transform, "flipH", false), flag(transform, "flipV", false), fraction(crop, "l"), fraction(crop, "t"), fraction(crop, "r"), fraction(crop, "b"));
    }
    private static double fraction(Element e, String attribute) throws IOException { return e == null || !e.hasAttribute(attribute) ? 0 : number(e.getAttribute(attribute)) / 100000; }
    static double inset(Element e, String attribute, double fallback) throws IOException { return e == null || !e.hasAttribute(attribute) ? fallback : number(e.getAttribute(attribute)) / 12700; }
    static Integer color(Element fill) throws IOException { if (fill == null) { return null; } var rgb = direct(fill, A, "srgbClr"); if (rgb == null || !children(rgb).isEmpty()) { throw new IOException("theme/system/modified drawing colors require an explicit color mapping"); } try { return Integer.valueOf(rgb.getAttribute("val"), 16); } catch (NumberFormatException ex) { throw new IOException("invalid drawing RGB", ex); } }
    private static String defaultAttribute(Element e, String name, String fallback) { return e.hasAttribute(name) ? e.getAttribute(name) : fallback; }
    private static long longNumber(String value, long fallback) throws IOException { try { return value.isEmpty() ? fallback : Long.parseLong(value); } catch (NumberFormatException ex) { throw new IOException("invalid drawing integer", ex); } }
    private static boolean flag(Element e, String attribute, boolean fallback) throws IOException { if (!e.hasAttribute(attribute)) { return fallback; } return switch(e.getAttribute(attribute)) { case "1", "true", "on" -> true; case "0", "false", "off" -> false; default -> throw new IOException("invalid drawing boolean"); }; }
    static void normalizePicture(Element container) throws IOException {
        var doc = container.getOwnerDocument(); doc.renameNode(container, WP, "wp:inline");
        while (container.getAttributes().getLength() > 0) { container.removeAttributeNode((org.w3c.dom.Attr)container.getAttributes().item(0)); }
        for (var e : children(container)) { if (WP.equals(e.getNamespaceURI()) && !Set.of("extent", "docPr", "cNvGraphicFramePr", "effectExtent").contains(e.getLocalName())) { remove(e); } }
        for (var crop : all(container, A, "srcRect")) { remove(crop); }
        for (var x : all(container, A, "xfrm")) { x.removeAttribute("rot"); x.removeAttribute("flipH"); x.removeAttribute("flipV"); }
        for (var e : children(container)) { DocxImages.validate(e); }
    }
    /** Unknown drawings/effects are rejected before normalization can erase their meaning. */
    static void validate(Element e) throws IOException {
        if (W.equals(e.getNamespaceURI()) && e.getLocalName().equals("txbxContent")) { return; }
        String ns = e.getNamespaceURI(); String name = e.getLocalName(); Set<String> attributes;
        if (WP.equals(ns)) { attributes = switch(name) {
            case "inline" -> Set.of("distT", "distR", "distB", "distL");
            case "anchor" -> Set.of("distT", "distR", "distB", "distL", "simplePos", "relativeHeight", "behindDoc", "locked", "layoutInCell", "allowOverlap");
            case "simplePos", "start", "lineTo" -> Set.of("x", "y"); case "positionH", "positionV" -> Set.of("relativeFrom");
            case "align", "posOffset", "wrapNone", "wrapTopAndBottom", "cNvGraphicFramePr" -> Set.of();
            case "wrapSquare", "wrapTight", "wrapThrough" -> Set.of("wrapText", "distT", "distR", "distB", "distL");
            case "wrapPolygon" -> Set.of("edited"); case "extent" -> Set.of("cx", "cy"); case "effectExtent" -> Set.of("l", "t", "r", "b");
            case "docPr" -> Set.of("id", "name", "descr"); default -> throw new IOException("unknown native Word drawing element: " + name);
        }; }
        else if (A.equals(ns)) { attributes = switch(name) {
            case "graphic", "stretch", "fillRect", "avLst", "solidFill", "noFill", "noAutofit" -> Set.of(); case "graphicData" -> Set.of("uri");
            case "xfrm" -> Set.of("rot", "flipH", "flipV"); case "srcRect" -> Set.of("l", "t", "r", "b"); case "off" -> Set.of("x", "y"); case "ext" -> Set.of("cx", "cy");
            case "prstGeom" -> Set.of("prst"); case "blip" -> Set.of("embed", "cstate"); case "hlinkClick" -> Set.of("id"); case "picLocks", "graphicFrameLocks" -> Set.of("noChangeAspect");
            case "srgbClr" -> Set.of("val"); case "ln" -> Set.of("w"); default -> throw new IOException("unknown native DrawingML element: " + name);
        }; }
        else if (PIC.equals(ns)) { attributes = switch(name) { case "pic", "nvPicPr", "cNvPicPr", "blipFill", "spPr" -> Set.of(); case "cNvPr" -> Set.of("id", "name", "descr"); default -> throw new IOException("unknown native picture element"); }; }
        else if (WPS.equals(ns)) { attributes = switch(name) { case "wsp", "spPr", "txbx", "cNvPr", "cNvCnPr" -> Set.of(); case "cNvSpPr" -> Set.of("txBox"); case "bodyPr" -> Set.of("tIns", "rIns", "bIns", "lIns", "anchor", "wrap", "rot", "vert", "anchorCtr", "rtlCol", "numCol", "spcFirstLastPara", "horzOverflow", "vertOverflow"); default -> throw new IOException("unknown native textbox element: " + name); }; }
        else { throw new IOException("unsupported native drawing namespace"); }
        for (int i = 0; i < e.getAttributes().getLength(); i++) { var attr = e.getAttributes().item(i); if (javax.xml.XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attr.getNamespaceURI())) { continue; }
            if ("http://schemas.microsoft.com/office/word/2010/wordprocessingDrawing".equals(attr.getNamespaceURI()) && Set.of("anchor", "inline").contains(name) && Set.of("anchorId", "editId").contains(attr.getLocalName())) {
                if (!attr.getNodeValue().matches("[0-9A-Fa-f]{8}")) { throw new IOException("invalid Word drawing edit identity"); }
                continue;
            }
            String local = attr.getLocalName() == null ? attr.getNodeName() : attr.getLocalName(); boolean relationship = (name.equals("blip") && local.equals("embed") || name.equals("hlinkClick") && local.equals("id"));
            if (!attributes.contains(local) || relationship != R.equals(attr.getNamespaceURI()) || !relationship && attr.getNamespaceURI() != null) { throw new IOException("unsupported native drawing attribute: " + local); }
        }
        if (name.equals("simplePos") && (number(e.getAttribute("x")) != 0 || number(e.getAttribute("y")) != 0) || name.equals("anchor") && flag(e, "simplePos", false)) { throw new IOException("absolute simplePos drawing mode is unsupported"); }
        if (name.equals("effectExtent")) { for (String side : attributes) { if (number(defaultAttribute(e, side, "0")) != 0) { throw new IOException("native drawing effects require a typed effect mapping"); } } }
        if (name.equals("prstGeom") && !e.getAttribute("prst").equals("rect")) { throw new IOException("nonrectangular shapes require a typed path mapping"); }
        if (name.equals("bodyPr")) {
            if (e.hasAttribute("rot") && number(e.getAttribute("rot")) != 0 || e.hasAttribute("vert") && !e.getAttribute("vert").equals("horz") || e.hasAttribute("numCol") && !e.getAttribute("numCol").equals("1") || flag(e, "anchorCtr", false) || flag(e, "rtlCol", false)) { throw new IOException("textbox text rotation/columns require a typed text-layout mapping"); }
            if (e.hasAttribute("anchor") && !Set.of("t", "ctr", "b").contains(e.getAttribute("anchor")) || e.hasAttribute("wrap") && !e.getAttribute("wrap").equals("square") || e.hasAttribute("horzOverflow") && !e.getAttribute("horzOverflow").equals("overflow") || e.hasAttribute("vertOverflow") && !e.getAttribute("vertOverflow").equals("overflow") || flag(e, "spcFirstLastPara", false)) { throw new IOException("textbox text flow property needs an explicit mapping"); }
        }
        for (var child : children(e)) { validate(child); }
    }
    static Element picture(Element standardDrawing, Drawing drawing) throws IOException {
        var container = direct(standardDrawing, WP, "inline"); if (container == null) { throw new IOException("native picture source must be a real inline image"); }
        var picture = (Picture)drawing.content(); var transform = picture.transform(); var x = all(container, A, "xfrm").getFirst(); writeTransform(x, transform);
        var fill = all(container, PIC, "blipFill").getFirst();
        if (transform.cropLeft() != 0 || transform.cropTop() != 0 || transform.cropRight() != 0 || transform.cropBottom() != 0) { var crop = element(fill, A, "a:srcRect"); crop.setAttribute("l", Long.toString(Math.round(transform.cropLeft() * 100000))); crop.setAttribute("t", Long.toString(Math.round(transform.cropTop() * 100000))); crop.setAttribute("r", Long.toString(Math.round(transform.cropRight() * 100000))); crop.setAttribute("b", Long.toString(Math.round(transform.cropBottom() * 100000))); fill.insertBefore(crop, direct(fill, A, "stretch")); }
        var docPr = direct(container, WP, "docPr"); docPr.setAttribute("name", drawing.id()); place(container, drawing.placement()); return standardDrawing;
    }
    static void writeTransform(Element x, Transform transform) { if (transform.rotation() != 0) { x.setAttribute("rot", Long.toString(Math.round(transform.rotation() * 60000))); } if (transform.flipHorizontal()) { x.setAttribute("flipH", "1"); } if (transform.flipVertical()) { x.setAttribute("flipV", "1"); } }
    static void place(Element container, Placement placement) {
        if (placement.wrap() == Wrap.INLINE) { return; }
        container.getOwnerDocument().renameNode(container, WP, "wp:anchor");
        container.setAttribute("distT", Long.toString(emu(placement.distanceTop()))); container.setAttribute("distR", Long.toString(emu(placement.distanceRight()))); container.setAttribute("distB", Long.toString(emu(placement.distanceBottom()))); container.setAttribute("distL", Long.toString(emu(placement.distanceLeft())));
        container.setAttribute("simplePos", "0"); container.setAttribute("relativeHeight", Long.toString(placement.zOrder())); container.setAttribute("behindDoc", placement.behindText() ? "1" : "0"); container.setAttribute("locked", "0"); container.setAttribute("layoutInCell", placement.layoutInCell() ? "1" : "0"); container.setAttribute("allowOverlap", placement.allowOverlap() ? "1" : "0");
        var first = container.getFirstChild(); var simple = element(container, WP, "wp:simplePos"); simple.setAttribute("x", "0"); simple.setAttribute("y", "0"); container.insertBefore(simple, first);
        container.insertBefore(axis(container, "positionH", placement.horizontal()), first); container.insertBefore(axis(container, "positionV", placement.vertical()), first);
        String wrap = switch(placement.wrap()) { case NONE -> "wrapNone"; case SQUARE -> "wrapSquare"; case TOP_AND_BOTTOM -> "wrapTopAndBottom"; case TIGHT -> "wrapTight"; case THROUGH -> "wrapThrough"; default -> throw new IllegalArgumentException("inline wrap cannot be floating"); };
        var w = element(container, WP, "wp:" + wrap); if (placement.wrap() == Wrap.SQUARE || placement.wrap() == Wrap.TIGHT || placement.wrap() == Wrap.THROUGH) { w.setAttribute("wrapText", switch(placement.wrapSide()) { case BOTH -> "bothSides"; case LEFT -> "left"; case RIGHT -> "right"; case LARGEST -> "largest"; }); }
        if (!placement.polygon().isEmpty()) { var polygon = element(w, WP, "wp:wrapPolygon"); polygon.setAttribute("edited", "1"); int index = 0; for (var vertex : placement.polygon()) { var e = element(w, WP, "wp:" + (index++ == 0 ? "start" : "lineTo")); e.setAttribute("x", Long.toString(Math.round(vertex.x()))); e.setAttribute("y", Long.toString(Math.round(vertex.y()))); polygon.appendChild(e); } w.appendChild(polygon); }
        var docPr = container.getElementsByTagNameNS(WP, "docPr").item(0); container.insertBefore(w, docPr);
    }
    private static Element axis(Element owner, String name, Axis axis) {
        var e = element(owner, WP, "wp:" + name); String base = switch(axis.relativeTo()) { case LEFT_MARGIN -> "leftMargin"; case RIGHT_MARGIN -> "rightMargin"; case INSIDE_MARGIN -> "insideMargin"; case OUTSIDE_MARGIN -> "outsideMargin"; case TOP_MARGIN -> "topMargin"; case BOTTOM_MARGIN -> "bottomMargin"; default -> axis.relativeTo().name().toLowerCase(Locale.ROOT); }; e.setAttribute("relativeFrom", base);
        var value = element(owner, WP, "wp:" + (axis.align() == Align.OFFSET ? "posOffset" : "align")); value.setTextContent(axis.align() == Align.OFFSET ? Long.toString(emu(axis.offset())) : axis.align().name().toLowerCase(Locale.ROOT)); e.appendChild(value); return e;
    }
}
