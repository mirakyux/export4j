// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Comment;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Control;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.ControlKind;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Drawing;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Picture;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Point;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Range;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Revision;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.RevisionKind;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.TextBox;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.A;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.OD;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.R;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.REL;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.W;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.W14;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.W15;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.WP;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.WPS;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.all;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.attr;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.boundary;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.element;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.emu;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.encode;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.ensureNamespaces;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.identity;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.is;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.length;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.paragraph;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.property;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.unwrap;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.word;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.zip;
import static com.mirakyux.export4j.adapter.docx.DocxAdapter.children;
import static com.mirakyux.export4j.adapter.docx.DocxAdapter.child;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Rebuilds a fresh standard OOXML package from typed values. No original package data is consulted. */
final class DocxSemanticWriter {
    private final DocxAdapter adapter; private final DocxLimits limits;
    private final Map<String, byte[]> parts; private final Map<String, Element> roots = new LinkedHashMap<>();
    private final Map<String, Element> paragraphs = new LinkedHashMap<>(); private int nextRelationship; private int nextIdentity = 1;
    private Map<String, Paragraph> sourceParagraphs = Map.of();
    private DocxSemanticWriter(DocxAdapter adapter, DocxLimits limits, Map<String, byte[]> parts) { this.adapter = adapter; this.limits = limits; this.parts = parts; }
    static byte[] write(DocxAdapter adapter, DocxLimits limits, DocxSemanticDocument document) throws IOException {
        DocxSemanticSupport.budget(document, limits);
        var encoded = new ByteArrayOutputStream(); adapter.write(document.body(), encoded);
        var writer = new DocxSemanticWriter(adapter, limits, adapter.unzip(new ByteArrayInputStream(encoded.toByteArray()))); return writer.write(document);
    }
    private byte[] write(DocxSemanticDocument document) throws IOException {
        for (var part : parts.entrySet()) { if (part.getKey().endsWith(".xml") || part.getKey().endsWith(".rels")) { roots.put(part.getKey(), DocxAdapter.parse(part.getValue())); } }
        // A native package preserves input values. Word's automatic dirty-field update also resets legacy forms.
        var settings = roots.get("word/settings.xml");
        if (settings != null) { var update = child(settings, "updateFields"); if (update != null) { settings.removeChild(update); } }
        for (var root : roots.values()) { for (var field : all(root, W, "fldSimple")) { field.removeAttributeNS(W, "dirty"); } }
        sourceParagraphs = DocxSemanticSupport.paragraphs(document.body(), document.drawings());
        index();
        for (var root : roots.values()) { for (var properties : all(root, WP, "docPr")) { nextIdentity = Math.max(nextIdentity, Integer.parseInt(properties.getAttribute("id")) + 1); } }
        // Nested textboxes require their anchors to exist first. Iterate only while real progress occurs.
        var remaining = new ArrayList<>(document.drawings());
        for (int pass = 0; !remaining.isEmpty(); pass++) {
            if (pass > 64) { throw new IOException("cyclic or overdeep native drawing graph"); }
            boolean progress = false;
            for (var iterator = remaining.iterator(); iterator.hasNext();) {
                var drawing = iterator.next(); var p = paragraphs.get(drawing.anchor().paragraphId()); if (p == null) { continue; }
                if (drawing.content() instanceof Picture) {
                    Node boundary = boundary(p, drawing.anchor().utf16()); if (!(boundary instanceof Element e)) { throw new IOException("native picture marker missing"); }
                    Element inlineDrawing = is(e, "drawing") ? e : all(e, W, "drawing").stream().findFirst().orElseThrow(() -> new IOException("native picture marker has no image"));
                    DocxSemanticDrawings.picture(inlineDrawing, drawing);
                } else {
                    var box = (TextBox)drawing.content(); var run = word(p, "r"); var content = textbox(p, drawing, box); run.appendChild(content);
                    p.insertBefore(run, boundary(p, drawing.anchor().utf16())); index();
                }
                iterator.remove(); progress = true;
            }
            if (!progress) { throw new IOException("native drawing anchor missing"); }
        }
        // Range markers must precede semantic wrappers: splitting an existing sdt would clone its identity.
        comments(document.comments());
        for (var revision : document.revisions()) { revision(revision); }
        // Inner controls first so enclosing ranges retain their nested values.
        var controls = new ArrayList<>(document.controls()); controls.sort(Comparator.comparingLong((Control c) -> span(c.range())).thenComparing(Control::id));
        for (var control : controls) { control(control); }
        for (var toc : document.contents()) { field(toc.range(), "TOC \\o \"" + toc.firstLevel() + "-" + toc.lastLevel() + "\"" + (toc.hyperlinks() ? " \\h" : "") + (toc.pageNumbers() ? "" : " \\n"), toc.id(), toc.dirty(), null); }
        for (var entry : roots.entrySet()) { if (entry.getKey().startsWith("word/") && (is(entry.getValue(), "document") || is(entry.getValue(), "hdr") || is(entry.getValue(), "ftr") || is(entry.getValue(), "comments"))) { ensureNamespaces(entry.getValue()); } parts.put(entry.getKey(), encode(entry.getValue(), limits.maxEntryBytes())); }
        var result = zip(parts, limits); DocxPackageEditor.validateSemanticPackage(adapter.unzip(new ByteArrayInputStream(result))); return result;
    }
    private void index() throws IOException {
        for (var entry : roots.entrySet()) { if (!entry.getKey().startsWith("word/") || entry.getKey().endsWith(".rels")) { continue; }
            for (var p : all(entry.getValue(), W, "p")) { String id = p.getAttributeNS(OD, "id"); if (!id.isBlank()) { var prior = paragraphs.putIfAbsent(id, p); if (prior != null && prior != p) { throw new IOException("duplicate output paragraph ID"); } } }
        }
    }
    private Element p(Point point) throws IOException { var p = paragraphs.get(point.paragraphId()); if (p == null) { throw new IOException("output native paragraph anchor missing"); } return p; }
    private List<Element> rangeParagraphs(Range range) throws IOException {
        var start = p(range.start()); var end = p(range.end()); if (start == end) { return List.of(start); }
        Element story = start; while (story.getParentNode() instanceof Element parent && !is(parent, "txbxContent")) { story = parent; }
        var all = all(story, W, "p"); int a = all.indexOf(start); int b = all.indexOf(end);
        if (a < 0 || b < a) { throw new IOException("native range crosses independent Word stories"); } return all.subList(a, b + 1);
    }
    private long span(Range range) { var a = new ArrayList<>(paragraphs.keySet()); return Math.max(0, a.indexOf(range.end().paragraphId()) - a.indexOf(range.start().paragraphId())) * 1000000L + range.end().utf16() - range.start().utf16(); }
    private void wrap(Range range, java.util.function.Function<Element, Element> factory) throws IOException {
        var selected = rangeParagraphs(range);
        for (var paragraph : selected) {
            int start = paragraph == selected.getFirst() ? range.start().utf16() : 0; int end = paragraph == selected.getLast() ? range.end().utf16() : length(paragraph);
            Node right = boundary(paragraph, end); Node left = boundary(paragraph, start); var wrapper = factory.apply(paragraph);
            paragraph.insertBefore(wrapper, left);
            for (Node current = left; current != right && current != null;) { Node following = current.getNextSibling(); wrapper.appendChild(current); current = following; }
        }
    }
    private void revision(Revision revision) throws IOException {
        int numeric = nextIdentity++;
        if (revision.kind() == RevisionKind.PARAGRAPH_FORMAT) {
            for (var paragraph : rangeParagraphs(revision.range())) { var pr = child(paragraph, "pPr"); if (pr == null) { pr = word(paragraph, "pPr"); paragraph.insertBefore(pr, paragraph.getFirstChild()); } var change = word(pr, "pPrChange"); revisionIdentity(change, revision, numeric); change.appendChild(paragraphProperties(paragraph, revision.priorParagraphFormat())); pr.appendChild(change); }
        } else if (revision.kind() == RevisionKind.RUN_FORMAT) {
            wrap(revision.range(), owner -> { var wrapper = word(owner, "ins"); wrapper.setAttributeNS(OD, "od:formatCarrier", "true"); return wrapper; });
            for (var paragraph : rangeParagraphs(revision.range())) { for (var carrier : all(paragraph, W, "ins")) { if (!carrier.hasAttributeNS(OD, "formatCarrier")) { continue; }
                for (var run : all(carrier, W, "r")) { var pr = child(run, "rPr"); if (pr == null) { pr = word(run, "rPr"); run.insertBefore(pr, run.getFirstChild()); } var change = word(pr, "rPrChange"); revisionIdentity(change, revision, numeric); change.appendChild(runProperties(run, revision.priorStyle())); pr.appendChild(change); } unwrap(carrier);
            } }
        } else {
            String name = switch(revision.kind()) { case INSERT -> "ins"; case DELETE -> "del"; case MOVE_FROM -> "moveFrom"; case MOVE_TO -> "moveTo"; default -> throw new IllegalArgumentException("unexpected revision kind"); };
            wrap(revision.range(), owner -> { var wrapper = word(owner, name); revisionIdentity(wrapper, revision, numeric); if (!revision.moveId().isEmpty()) { wrapper.setAttributeNS(OD, "od:moveId", revision.moveId()); }
                return wrapper; });
            if (revision.kind() == RevisionKind.MOVE_FROM || revision.kind() == RevisionKind.MOVE_TO) {
                var start = p(revision.range().start()); var end = p(revision.range().end()); var prefix = revision.kind() == RevisionKind.MOVE_FROM ? "moveFromRange" : "moveToRange";
                var endMarker = word(end, prefix + "End"); endMarker.setAttributeNS(W, "w:id", Integer.toString(numeric)); end.insertBefore(endMarker, boundary(end, revision.range().end().utf16()));
                var startMarker = word(start, prefix + "Start"); startMarker.setAttributeNS(W, "w:id", Integer.toString(numeric)); startMarker.setAttributeNS(W, "w:name", revision.moveId()); startMarker.setAttributeNS(W, "w:author", revision.author()); if (revision.date() != null) { startMarker.setAttributeNS(W, "w:date", revision.date().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)); } start.insertBefore(startMarker, boundary(start, revision.range().start().utf16()));
            }
            if (revision.kind() == RevisionKind.DELETE) { for (var paragraph : rangeParagraphs(revision.range())) { for (var wrapper : all(paragraph, W, name)) { if (identity(wrapper, "").equals(revision.id())) { for (var t : all(wrapper, W, "t")) { t.getOwnerDocument().renameNode(t, W, "w:delText"); } } } } }
        }
    }
    private static void revisionIdentity(Element element, Revision revision, int numeric) { identity(element, revision.id(), numeric); element.setAttributeNS(W, "w:author", revision.author()); if (revision.date() != null) { element.setAttributeNS(W, "w:date", revision.date().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)); } }
    private Element runProperties(Element owner, InlineStyle style) {
        var pr = word(owner, "rPr");
        switch (style.fontRole()) {
            case "" -> { }
            case "bold" -> pr.appendChild(word(owner, "b"));
            case "italic" -> pr.appendChild(word(owner, "i"));
            case "boldItalic" -> { pr.appendChild(word(owner, "b")); pr.appendChild(word(owner, "i")); }
            default -> throw new IllegalArgumentException("prior revision font role needs an explicit DOCX font/style mapping");
        }
        pr.appendChild(property(owner, "sz", Long.toString(Math.round(style.fontSize() * 2)))); pr.appendChild(property(owner, "color", String.format(Locale.ROOT, "%06X", style.rgb())));
        if (style.underline()) { pr.appendChild(property(owner, "u", "single")); } if (style.strikeThrough()) { pr.appendChild(property(owner, "strike", "true")); }
        if (style.baselineShift() != 0) { pr.appendChild(property(owner, "position", Long.toString(Math.round(-style.baselineShift() * 2)))); } return pr;
    }
    private Element paragraphProperties(Element owner, ParagraphFormat format) throws IOException {
        var source = fragment(List.of(new Paragraph("native-old-format", "", format)), 400); var pr = child(source.getFirst(), "pPr");
        if (pr == null) { return word(owner, "pPr"); }
        var run = child(pr, "rPr"); if (run != null) { pr.removeChild(run); }
        return (Element)owner.getOwnerDocument().importNode(pr, true);
    }
    private void control(Control c) throws IOException {
        if (c.kind() == ControlKind.LEGACY_TEXT || c.kind() == ControlKind.LEGACY_CHECKBOX || c.kind() == ControlKind.LEGACY_DROP_DOWN) { field(c.range(), switch(c.kind()) { case LEGACY_TEXT -> "FORMTEXT"; case LEGACY_CHECKBOX -> "FORMCHECKBOX"; default -> "FORMDROPDOWN"; }, c.id(), false, c); return; }
        var owner = p(c.range().start()); var sdt = word(owner, "sdt"); sdt.setAttributeNS(OD, "od:id", c.id()); var pr = word(owner, "sdtPr"); pr.appendChild(property(owner, "id", Integer.toString(nextIdentity++)));
        if (!c.tag().isEmpty()) { pr.appendChild(property(owner, "tag", c.tag())); } if (!c.title().isEmpty()) { pr.appendChild(property(owner, "alias", c.title())); }
        if (c.kind() == ControlKind.GROUP) { pr.appendChild(property(owner, "lock", "contentLocked")); }
        var type = switch(c.kind()) { case RICH_TEXT -> word(owner, "richText"); case PLAIN_TEXT -> word(owner, "text"); case DROP_DOWN -> word(owner, "dropDownList"); case COMBO_BOX -> word(owner, "comboBox"); case DATE -> word(owner, "date"); case PICTURE -> word(owner, "picture"); case GROUP -> word(owner, "group"); case CHECKBOX -> element(owner, W14, "w14:checkbox"); case REPEATING_SECTION -> element(owner, W15, "w15:repeatingSection"); case REPEATING_ITEM -> element(owner, W15, "w15:repeatingSectionItem"); default -> throw new IOException("unknown content control kind"); };
        if (c.kind() == ControlKind.CHECKBOX) { var checked = element(owner, W14, "w14:checked"); checked.setAttributeNS(W14, "w14:val", c.checked() ? "1" : "0"); type.appendChild(checked); }
        for (var item : c.items()) { var e = word(owner, "listItem"); e.setAttributeNS(W, "w:displayText", item.label()); e.setAttributeNS(W, "w:value", item.value()); type.appendChild(e); }
        if (c.kind() == ControlKind.DATE) { type.appendChild(property(owner, "dateFormat", c.dateFormat())); type.appendChild(property(owner, "lid", c.language())); }
        pr.appendChild(type); sdt.appendChild(pr); var content = word(owner, "sdtContent"); sdt.appendChild(content);
        if (c.blockLevel()) {
            var end = p(c.range().end());
            if (c.range().start().utf16() != 0 || c.range().end().utf16() != length(end)) { throw new IOException("block content controls require complete sibling blocks"); }
            Node parent = owner.getParentNode(); while (parent instanceof Element && !ancestor(parent, end)) { parent = parent.getParentNode(); }
            if (!(parent instanceof Element container) || !(is(container, "body") || is(container, "tc") || is(container, "sdtContent") || is(container, "txbxContent") || is(container, "hdr") || is(container, "ftr"))) { throw new IOException("block content controls must share a Word block story"); }
            Element firstBlock = owner; while (firstBlock.getParentNode() != parent) { firstBlock = (Element)firstBlock.getParentNode(); }
            Element lastBlock = end; while (lastBlock.getParentNode() != parent) { lastBlock = (Element)lastBlock.getParentNode(); }
            var firstParagraphs = all(firstBlock, W, "p"); var lastParagraphs = all(lastBlock, W, "p");
            if (firstBlock != owner && (firstParagraphs.isEmpty() || firstParagraphs.getFirst() != owner) || lastBlock != end && (lastParagraphs.isEmpty() || lastParagraphs.getLast() != end)) { throw new IOException("block control boundary selects only part of an enclosing block"); }
            var stop = lastBlock.getNextSibling(); parent.insertBefore(sdt, firstBlock);
            for (Node node = firstBlock; node != stop && node != null;) { var next = node.getNextSibling(); content.appendChild(node); node = next; }
        } else {
            if (!c.range().start().paragraphId().equals(c.range().end().paragraphId())) { throw new IOException("inline controls cannot cross paragraphs"); }
            Node right = boundary(owner, c.range().end().utf16()); Node left = boundary(owner, c.range().start().utf16()); owner.insertBefore(sdt, left);
            for (Node node = left; node != right && node != null;) { var next = node.getNextSibling(); content.appendChild(node); node = next; }
        }
    }
    private static boolean ancestor(Node parent, Node child) { for (var node = child; node != null; node = node.getParentNode()) { if (node == parent) { return true; } } return false; }
    private void field(Range range, String instruction, String id, boolean dirty, Control legacy) throws IOException {
        var beginParagraph = p(range.start()); var endParagraph = p(range.end());
        Node endBoundary = boundary(endParagraph, range.end().utf16()); var end = fieldRun(endParagraph, "end"); endParagraph.insertBefore(end, endBoundary);
        Node startBoundary = boundary(beginParagraph, range.start().utf16()); var begin = fieldRun(beginParagraph, "begin"); var beginChar = child(begin, "fldChar"); beginChar.setAttributeNS(OD, "od:id", id);
        if (dirty) { beginChar.setAttributeNS(W, "w:dirty", "true"); }
        if (legacy != null) { beginChar.appendChild(formData(beginParagraph, legacy)); }
        var code = word(beginParagraph, "r"); var text = word(beginParagraph, "instrText"); text.setAttributeNS(javax.xml.XMLConstants.XML_NS_URI, "xml:space", "preserve"); text.setTextContent(" " + instruction + " "); code.appendChild(text);
        beginParagraph.insertBefore(begin, startBoundary); beginParagraph.insertBefore(code, startBoundary); beginParagraph.insertBefore(fieldRun(beginParagraph, "separate"), startBoundary);
    }
    private static Element fieldRun(Element owner, String kind) { var run = word(owner, "r"); var field = word(owner, "fldChar"); field.setAttributeNS(W, "w:fldCharType", kind); run.appendChild(field); return run; }
    private Element formData(Element owner, Control control) {
        var options = control.options(); var ff = word(owner, "ffData"); ff.appendChild(property(owner, "name", control.tag().isEmpty() ? control.id() : control.tag())); ff.appendChild(property(owner, "enabled", options.enabled() ? "1" : "0")); ff.appendChild(property(owner, "calcOnExit", options.calculateOnExit() ? "1" : "0"));
        if (!options.helpText().isEmpty()) { var e = property(owner, "helpText", options.helpText()); e.setAttributeNS(W, "w:type", "text"); ff.appendChild(e); } if (!options.statusText().isEmpty()) { var e = property(owner, "statusText", options.statusText()); e.setAttributeNS(W, "w:type", "text"); ff.appendChild(e); }
        if (control.kind() == ControlKind.LEGACY_CHECKBOX) { var cb = word(owner, "checkBox"); cb.appendChild(options.checkboxSize() == 0 ? word(owner, "sizeAuto") : property(owner, "size", Long.toString(Math.round(options.checkboxSize() * 2)))); cb.appendChild(property(owner, "default", options.defaultChecked() ? "1" : "0")); cb.appendChild(property(owner, "checked", control.checked() ? "1" : "0")); ff.appendChild(cb); }
        else if (control.kind() == ControlKind.LEGACY_DROP_DOWN) { var list = word(owner, "ddList");
            String selected = sourceParagraphs.get(control.range().start().paragraphId()).text().substring(control.range().start().utf16(), control.range().end().utf16());
            int result = 0; while (!control.items().get(result).label().equals(selected)) { result++; }
            list.appendChild(property(owner, "result", Integer.toString(result))); list.appendChild(property(owner, "default", Integer.toString(options.defaultSelection())));
            for (var item : control.items()) { list.appendChild(property(owner, "listEntry", item.label())); } ff.appendChild(list); }
        else { var input = word(owner, "textInput"); input.appendChild(property(owner, "type", options.textType())); input.appendChild(property(owner, "default", options.defaultText())); if (options.maxLength() > 0) { input.appendChild(property(owner, "maxLength", Integer.toString(options.maxLength()))); } if (!options.textFormat().isEmpty()) { input.appendChild(property(owner, "format", options.textFormat())); } ff.appendChild(input); } return ff;
    }
    private void comments(List<Comment> comments) throws IOException {
        if (comments.isEmpty()) { return; }
        var root = DocxAdapter.parse(("<w:comments xmlns:w=\"" + W + "\"/>").getBytes(StandardCharsets.UTF_8)); roots.put("word/comments.xml", root);
        int id = 0;
        for (var comment : comments) {
            String numeric = Integer.toString(id++); var value = word(root, "comment"); identity(value, comment.id(), Integer.parseInt(numeric)); value.setAttributeNS(W, "w:author", comment.author()); value.setAttributeNS(W, "w:initials", comment.initials()); if (comment.date() != null) { value.setAttributeNS(W, "w:date", comment.date().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)); }
            for (var block : fragment(comment.blocks(), 400, "word/comments.xml")) { value.appendChild(root.getOwnerDocument().importNode(block, true)); } root.appendChild(value);
            var a = p(comment.range().start()); var b = p(comment.range().end()); Node end = boundary(b, comment.range().end().utf16());
            var endMark = word(b, "commentRangeEnd"); endMark.setAttributeNS(W, "w:id", numeric); b.insertBefore(endMark, end); var reference = word(b, "r"); var ref = word(b, "commentReference"); ref.setAttributeNS(W, "w:id", numeric); reference.appendChild(ref); b.insertBefore(reference, end);
            var startMark = word(a, "commentRangeStart"); startMark.setAttributeNS(W, "w:id", numeric); a.insertBefore(startMark, boundary(a, comment.range().start().utf16()));
        }
        addRelation(R + "/comments", "comments.xml"); contentType("/word/comments.xml", "application/vnd.openxmlformats-officedocument.wordprocessingml.comments+xml");
    }
    private Element textbox(Element owner, Drawing drawing, TextBox box) throws IOException {
        var wDrawing = word(owner, "drawing"); var inline = element(owner, WP, "wp:inline"); wDrawing.appendChild(inline); var extent = element(owner, WP, "wp:extent"); extent.setAttribute("cx", Long.toString(emu(box.width()))); extent.setAttribute("cy", Long.toString(emu(box.height()))); inline.appendChild(extent);
        var docPr = element(owner, WP, "wp:docPr"); docPr.setAttribute("id", Integer.toString(nextIdentity++)); docPr.setAttribute("name", drawing.id()); inline.appendChild(docPr);
        var graphic = element(owner, A, "a:graphic"); var data = element(owner, A, "a:graphicData"); data.setAttribute("uri", WPS); var shape = element(owner, WPS, "wps:wsp"); var nv = element(owner, WPS, "wps:cNvSpPr"); nv.setAttribute("txBox", "1"); shape.appendChild(nv);
        var sp = element(owner, WPS, "wps:spPr"); var transform = element(owner, A, "a:xfrm"); DocxSemanticDrawings.writeTransform(transform, box.transform()); var off = element(owner, A, "a:off"); off.setAttribute("x", "0"); off.setAttribute("y", "0"); transform.appendChild(off); var ext = element(owner, A, "a:ext"); ext.setAttribute("cx", Long.toString(emu(box.width()))); ext.setAttribute("cy", Long.toString(emu(box.height()))); transform.appendChild(ext); sp.appendChild(transform);
        var geometry = element(owner, A, "a:prstGeom"); geometry.setAttribute("prst", "rect"); geometry.appendChild(element(owner, A, "a:avLst")); sp.appendChild(geometry); sp.appendChild(fill(owner, box.fillRgb()));
        var line = element(owner, A, "a:ln"); line.setAttribute("w", Long.toString(emu(box.borderWidth()))); line.appendChild(fill(owner, box.borderRgb())); sp.appendChild(line); shape.appendChild(sp);
        var txbx = element(owner, WPS, "wps:txbx"); var content = word(owner, "txbxContent");
        for (var block : fragment(box.blocks(), box.width() - box.insetLeft() - box.insetRight(), storyPart(owner))) { content.appendChild(owner.getOwnerDocument().importNode(block, true)); } txbx.appendChild(content); shape.appendChild(txbx);
        var body = element(owner, WPS, "wps:bodyPr"); body.setAttribute("tIns", Long.toString(emu(box.insetTop()))); body.setAttribute("rIns", Long.toString(emu(box.insetRight()))); body.setAttribute("bIns", Long.toString(emu(box.insetBottom()))); body.setAttribute("lIns", Long.toString(emu(box.insetLeft()))); body.setAttribute("anchor", switch(box.verticalAlignment()) { case TOP -> "t"; case CENTER -> "ctr"; case BOTTOM -> "b"; }); body.appendChild(element(owner, A, "a:noAutofit")); shape.appendChild(body); data.appendChild(shape); graphic.appendChild(data); inline.appendChild(graphic); DocxSemanticDrawings.place(inline, drawing.placement()); return wDrawing;
    }
    private static Element fill(Element owner, Integer rgb) { if (rgb == null) { return element(owner, A, "a:noFill"); } var fill = element(owner, A, "a:solidFill"); var color = element(owner, A, "a:srgbClr"); color.setAttribute("val", String.format(Locale.ROOT, "%06X", rgb)); fill.appendChild(color); return fill; }
    private List<Element> fragment(List<Block> blocks, double width) throws IOException {
        return fragment(blocks, width, "word/document.xml");
    }
    private List<Element> fragment(List<Block> blocks, double width, String ownerPart) throws IOException {
        var source = new com.mirakyux.export4j.model.Document(DocumentMetadata.EMPTY, new PageSettings(new PageSize(width + 144, 1000), PageMargins.uniform(72)), blocks);
        var bytes = new ByteArrayOutputStream(); adapter.write(source, bytes); var fragment = adapter.unzip(new ByteArrayInputStream(bytes.toByteArray())); var document = DocxAdapter.parse(fragment.get("word/document.xml"));
        var relationMap = new HashMap<String, String>(); var rel = fragment.get("word/_rels/document.xml.rels");
        if (rel != null) { for (var r : children(DocxAdapter.parse(rel))) { if (Set.of(R + "/hyperlink", R + "/image").contains(r.getAttribute("Type"))) {
            String newId = addRelation(ownerPart, r.getAttribute("Type"), r.getAttribute("Target")); relationMap.put(r.getAttribute("Id"), newId); if (r.hasAttribute("TargetMode")) { relation(ownerPart, newId).setAttribute("TargetMode", r.getAttribute("TargetMode")); }
        } } }
        for (var e : all(document, "*", "*")) { for (String attr : List.of("id", "embed")) { String prior = e.getAttributeNS(R, attr); if (relationMap.containsKey(prior)) { e.setAttributeNS(R, "r:" + attr, relationMap.get(prior)); } } }
        for (var entry : fragment.entrySet()) { if (entry.getKey().startsWith("word/media/")) { byte[] prior = parts.putIfAbsent(entry.getKey(), entry.getValue()); if (prior != null && !Arrays.equals(prior, entry.getValue())) { throw new IOException("media identity collision"); } } }
        for (var type : children(DocxAdapter.parse(fragment.get("[Content_Types].xml")))) { if (type.getLocalName().equals("Default") && Set.of("png", "jpg", "jpeg").contains(type.getAttribute("Extension"))) { var types = roots.get("[Content_Types].xml"); if (children(types).stream().noneMatch(t -> t.getAttribute("Extension").equals(type.getAttribute("Extension")))) { types.appendChild(types.getOwnerDocument().importNode(type, true)); } } }
        return children(child(document, "body")).stream().filter(e -> is(e, "p") || is(e, "tbl")).toList();
    }
    private String storyPart(Element owner) throws IOException { for (var entry : roots.entrySet()) { if (owner.getOwnerDocument() == entry.getValue().getOwnerDocument()) { return entry.getKey(); } } throw new IOException("native drawing story owner missing"); }
    private static String relPart(String owner) { int slash = owner.lastIndexOf('/'); return owner.substring(0, slash) + "/_rels/" + owner.substring(slash + 1) + ".rels"; }
    private String addRelation(String type, String target) throws IOException { return addRelation("word/document.xml", type, target); }
    private String addRelation(String owner, String type, String target) throws IOException { String name = relPart(owner); var root = roots.get(name); if (root == null) { root = DocxAdapter.parse(("<Relationships xmlns=\"" + REL + "\"/>").getBytes(StandardCharsets.UTF_8)); roots.put(name, root); } String id; do { id = "native-" + nextRelationship++; } while (findRelation(root, id) != null); var r = element(root, REL, "Relationship"); r.setAttribute("Id", id); r.setAttribute("Type", type); r.setAttribute("Target", target); root.appendChild(r); return id; }
    private Element relation(String owner, String id) { return findRelation(roots.get(relPart(owner)), id); }
    private static Element findRelation(Element root, String id) { return children(root).stream().filter(e -> e.getAttribute("Id").equals(id)).findFirst().orElse(null); }
    private void contentType(String name, String mime) { var root = roots.get("[Content_Types].xml"); var value = element(root, root.getNamespaceURI(), "Override"); value.setAttribute("PartName", name); value.setAttribute("ContentType", mime); root.appendChild(value); }
}
