// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Comment;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Contents;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Control;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.ControlKind;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.ControlOptions;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Drawing;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.ListItem;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Picture;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Point;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Range;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Revision;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.RevisionKind;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.TextBox;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.VerticalAlignment;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.A;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.OD;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.PIC;
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
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.direct;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.element;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.encode;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.identity;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.is;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.length;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.number;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.on;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.paragraph;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.property;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.remove;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.unwrap;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.word;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticXml.zip;
import static com.mirakyux.export4j.adapter.docx.DocxAdapter.children;
import static com.mirakyux.export4j.adapter.docx.DocxAdapter.child;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.SemanticTree;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Extracts native semantics, then feeds a normalized package to the established body reader.
 * DOM/package maps are transient; only typed values survive. Unknown markup follows the read policy.
 */
final class DocxSemanticReader {
    private final DocxAdapter adapter; private final DocxLimits limits; private final DocxAdapter.Policy policy;
    private final List<DocxAdapter.Diagnostic> diagnostics = new ArrayList<>();
    private final List<Function<Map<String, Point>, Drawing>> drawings = new ArrayList<>();
    private final List<Function<Map<String, Point>, Revision>> revisions = new ArrayList<>();
    private final List<Function<Map<String, Point>, Control>> controls = new ArrayList<>();
    private final List<Function<Map<String, Point>, Contents>> contents = new ArrayList<>();
    private final Map<String, Element> starts = new HashMap<>(); private final Map<String, Element> ends = new HashMap<>();
    private final Map<String, List<String>> sideIds = new LinkedHashMap<>(); private final Map<String, Element> commentElements = new LinkedHashMap<>();
    private final Set<String> tocTokens = new HashSet<>();
    private final Set<String> usedIds = new HashSet<>(); private final Map<String, List<Block>> sideBlocks = new HashMap<>();
    private int next; private int markerId;
    private DocxSemanticReader(DocxAdapter adapter, DocxLimits limits, DocxAdapter.Policy policy) { this.adapter = adapter; this.limits = limits; this.policy = policy; }
    static DocxAdapter.SemanticReadResult read(DocxAdapter adapter, DocxLimits limits, InputStream input, DocxAdapter.Policy policy) throws IOException {
        try { return new DocxSemanticReader(adapter, limits, policy).read(input); }
        catch (IllegalArgumentException | ArithmeticException exception) { throw new IOException("invalid DOCX native semantics", exception); }
    }
    private DocxAdapter.SemanticReadResult read(InputStream input) throws IOException {
        var parts = adapter.unzip(input); var roots = DocxPackageEditor.validateSemanticPackage(parts);
        for (var entry : roots.entrySet()) {
            if (!entry.getKey().startsWith("word/") || entry.getKey().endsWith(".rels")) { continue; }
            selectCompatibleContent(entry.getValue()); parts.put(entry.getKey(), encode(entry.getValue(), limits.maxEntryBytes()));
        }
        boolean stylesMaterialized = adapter.materializeSemanticStyles(parts, policy, diagnostics);
        for (var name : new ArrayList<>(roots.keySet())) { if (parts.containsKey(name)) { roots.put(name, DocxAdapter.parse(parts.get(name))); } else { roots.remove(name); } }
        var main = roots.get("word/document.xml"); var body = child(main, "body");
        if (body == null) { throw new IOException("Word body missing"); }
        var stories = new LinkedHashMap<String, Element>(); stories.put("word/document.xml", main);
        for (var entry : roots.entrySet()) { if (is(entry.getValue(), "hdr") || is(entry.getValue(), "ftr")) { stories.put(entry.getKey(), entry.getValue()); } }
        Element commentsRoot = roots.get("word/comments.xml");
        for (var root : stories.values()) { collectExistingIds(root); }
        if (commentsRoot != null) { collectExistingIds(commentsRoot); }
        for (var root : stories.values()) { assignIds(root); }
        if (commentsRoot != null) { assignIds(commentsRoot); }
        for (var entry : stories.entrySet()) { moves(entry.getValue()); normalize(entry.getValue()); fields(entry.getValue()); images(entry.getValue(), parts, roots.get(relationshipPart(entry.getKey())), body); }
        if (commentsRoot != null) {
            for (var comment : children(commentsRoot)) {
                if (!is(comment, "comment")) { loss("comments", "unknown comment container"); continue; }
                String id = attr(comment, "id", ""); if (id.isBlank() || commentElements.putIfAbsent(id, comment) != null) { throw new IOException("duplicate or missing comment ID"); }
                normalize(comment); fields(comment); images(comment, parts, roots.get("word/_rels/comments.xml.rels"), body);
                side("comment/" + id, children(comment), body);
            }
            parts.remove("word/comments.xml"); parts.remove("word/_rels/comments.xml.rels");
            for (var root : roots.values()) { if (REL.equals(root.getNamespaceURI())) { for (var rel : children(root)) { if (rel.getAttribute("Type").equals(R + "/comments")) { remove(rel); } } } }
            var types = roots.get("[Content_Types].xml"); for (var type : children(types)) { if (type.getAttribute("PartName").equals("/word/comments.xml")) { remove(type); } }
        }
        var points = new HashMap<String, Point>();
        for (var root : stories.values()) { locate(root, points); }
        for (var root : stories.values()) { for (var mark : all(root, OD, "mark")) { remove(mark); } }
        for (var entry : roots.entrySet()) { if (parts.containsKey(entry.getKey())) { parts.put(entry.getKey(), encode(entry.getValue(), limits.maxEntryBytes())); } }
        var imported = adapter.readSemanticBody(new ByteArrayInputStream(zip(parts, limits)), policy, stylesMaterialized); diagnostics.addAll(imported.diagnostics());
        var indexed = new HashMap<String, Block>();
        for (var section : imported.document().sections()) { SemanticTree.walk(section.blocks(), visit -> indexed.put(visit.block().id(), visit.block())); }
        var sideSet = new HashSet<String>();
        for (var entry : sideIds.entrySet()) {
            sideSet.addAll(entry.getValue()); var blocks = new ArrayList<Block>();
            for (String id : entry.getValue()) { var block = indexed.get(id); if (block == null) { throw new IOException("native side story failed semantic import"); } blocks.add(block); }
            sideBlocks.put(entry.getKey(), List.copyOf(blocks));
        }
        var sections = imported.document().sections().stream().map(s -> new Section(s.id(), s.pageSettings(), s.blocks().stream().filter(b -> !sideSet.contains(b.id())).toList(), s.firstPageNumber(), s.pageRegions())).toList();
        var comments = new ArrayList<Comment>();
        for (var entry : commentElements.entrySet()) {
            var source = entry.getValue(); String token = "comment:" + entry.getKey();
            if (!points.containsKey(token + "/start") || !points.containsKey(token + "/end")) { throw new IOException("comment content has no complete range"); }
            comments.add(new Comment(identity(source, entry.getKey()), range(points, token), attr(source, "author", ""), attr(source, "initials", ""), date(source), sideBlocks.get("comment/" + entry.getKey())));
        }
        for (String id : starts.keySet()) { if (id.startsWith("comment:") && !commentElements.containsKey(id.substring(8))) { throw new IOException("comment range references missing comment content"); } }
        var nativeDrawings = drawings.stream().map(f -> f.apply(points)).toList();
        var nativeRevisions = mergeRevisions(revisions.stream().map(f -> f.apply(points)).toList());
        return new DocxAdapter.SemanticReadResult(new DocxSemanticDocument(new SectionedDocument(imported.document().metadata(), sections, imported.document().navigation()), nativeDrawings, comments,
                nativeRevisions, controls.stream().map(f -> f.apply(points)).toList(), contents.stream().map(f -> f.apply(points)).toList()), diagnostics);
    }
    private static String relationshipPart(String name) { int slash = name.lastIndexOf('/'); return name.substring(0, slash) + "/_rels/" + name.substring(slash + 1) + ".rels"; }
    private static void selectCompatibleContent(Element root) throws IOException {
        String compatibility = "http://schemas.openxmlformats.org/markup-compatibility/2006";
        for (var alternative : new ArrayList<>(all(root, compatibility, "AlternateContent"))) {
            boolean attached = false; for (Node parent = alternative; parent != null; parent = parent.getParentNode()) { if (parent == root) { attached = true; break; } }
            if (!attached) { continue; }
            Element selected = null;
            for (var choice : children(alternative)) {
                if (!compatibility.equals(choice.getNamespaceURI()) || !choice.getLocalName().equals("Choice")) { continue; }
                String requires = choice.getAttribute("Requires").strip(); boolean supported = !requires.isEmpty();
                for (String prefix : requires.split("\\s+")) { String namespace = choice.lookupNamespaceURI(prefix); supported &= namespace != null && Set.of(WPS, W14, W15).contains(namespace); }
                if (supported) { selected = choice; break; }
            }
            if (selected == null) { throw new IOException("alternate Word content has no supported typed representation"); }
            while (selected.hasChildNodes()) { alternative.getParentNode().insertBefore(selected.getFirstChild(), alternative); }
            remove(alternative);
        }
    }
    private static void moves(Element root) throws IOException {
        var from = new ArrayDeque<String>(); var to = new ArrayDeque<String>(); var names = new HashMap<String, String>();
        for (var e : descendants(root)) {
            if (!W.equals(e.getNamespaceURI())) { continue; } String name = e.getLocalName();
            if (name.equals("moveFromRangeStart") || name.equals("moveToRangeStart")) { String id = attr(e, "id", ""); String move = attr(e, "name", ""); if (id.isBlank() || move.isBlank() || names.putIfAbsent(name + "/" + id, move) != null) { throw new IOException("invalid move range identity"); } (name.startsWith("moveFrom") ? from : to).push(move); }
            else if (name.equals("moveFromRangeEnd") || name.equals("moveToRangeEnd")) { var stack = name.startsWith("moveFrom") ? from : to; if (stack.isEmpty()) { throw new IOException("unbalanced Word move range"); } stack.pop(); }
            else if ((name.equals("moveFrom") || name.equals("moveTo")) && !e.hasAttributeNS(OD, "moveId")) { var stack = name.equals("moveFrom") ? from : to; if (!stack.isEmpty()) { e.setAttributeNS(OD, "od:moveId", stack.peek()); } }
        }
        if (!from.isEmpty() || !to.isEmpty()) { throw new IOException("unclosed Word move range"); }
    }
    private void collectExistingIds(Element root) throws IOException {
        for (var element : descendants(root)) { if ((is(element, "p") || is(element, "tbl")) && element.hasAttributeNS(OD, "id") && !usedIds.add(element.getAttributeNS(OD, "id"))) { throw new IOException("duplicate native paragraph ID"); } }
    }
    private void assignIds(Element root) {
        for (var element : descendants(root)) { if ((is(element, "p") || is(element, "tbl")) && !element.hasAttributeNS(OD, "id") && !element.hasAttributeNS(OD, "cellTail") && !element.hasAttributeNS(OD, "boundary") && !continuation(element)) {
            String id; do { id = "word-" + (is(element, "p") ? "p-" : "table-") + next++; } while (!usedIds.add(id)); element.setAttributeNS(OD, "od:id", id);
        } }
    }
    private static boolean continuation(Element element) { for (Node n = element; n instanceof Element e; n = n.getParentNode()) { if (is(e, "tc") && DocxCells.continuation(e)) { return true; } } return false; }
    private static List<Element> descendants(Element root) { var result = new ArrayList<Element>(); result.add(root); var nodes = root.getElementsByTagName("*"); for (int i = 0; i < nodes.getLength(); i++) { result.add((Element)nodes.item(i)); } return result; }
    private Element mark(Element at, String token, boolean end) {
        var marker = element(at, OD, "od:mark"); marker.setAttribute("token", token + (end ? "/end" : "/start")); return marker;
    }
    private void bracket(Element wrapper, String token) throws IOException {
        if (paragraph(wrapper) != null) { wrapper.getParentNode().insertBefore(mark(wrapper, token, false), wrapper); wrapper.getParentNode().insertBefore(mark(wrapper, token, true), wrapper.getNextSibling()); }
        else {
            var ps = all(wrapper, W, "p"); if (ps.isEmpty()) { throw new IOException("native block feature requires paragraph content"); }
            var first = ps.getFirst(); var last = ps.getLast(); first.insertBefore(mark(first, token, false), child(first, "pPr") == null ? first.getFirstChild() : child(first, "pPr").getNextSibling()); last.appendChild(mark(last, token, true));
        }
    }
    private void normalize(Element root) throws IOException {
        for (var e : children(root)) { normalize(e); }
        if (!W.equals(root.getNamespaceURI())) { return; }
        switch (root.getLocalName()) {
            case "ins", "del", "moveFrom", "moveTo" -> {
                String id = identity(root, "revision-" + markerId++); String token = "revision:" + markerId++;
                var kind = switch(root.getLocalName()) { case "ins" -> RevisionKind.INSERT; case "del" -> RevisionKind.DELETE; case "moveFrom" -> RevisionKind.MOVE_FROM; default -> RevisionKind.MOVE_TO; };
                String pair = root.hasAttributeNS(OD, "moveId") ? root.getAttributeNS(OD, "moveId") : attr(root, "id", ""); String author = attr(root, "author", ""); var date = date(root);
                bracket(root, token); revisions.add(p -> new Revision(id, kind, range(p, token), author, date, kind == RevisionKind.MOVE_FROM || kind == RevisionKind.MOVE_TO ? pair : "", null, null)); unwrap(root);
            }
            case "delText" -> root.getOwnerDocument().renameNode(root, W, "w:t");
            case "commentRangeStart", "commentRangeEnd" -> {
                String token = "comment:" + attr(root, "id", ""); boolean end = is(root, "commentRangeEnd");
                if ((end ? ends : starts).putIfAbsent(token, root) != null) { throw new IOException("duplicate comment range boundary"); }
                root.getParentNode().replaceChild(mark(root, token, end), root);
            }
            case "commentReference" -> remove(root);
            case "rPrChange", "pPrChange" -> {
                var owner = root.getParentNode(); if (!(owner instanceof Element properties)) { throw new IOException("orphan format revision"); }
                var container = properties.getParentNode(); if (!(container instanceof Element target)) { throw new IOException("orphan format revision"); }
                var prior = child(root, is(root, "rPrChange") ? "rPr" : "pPr"); if (prior == null) { throw new IOException("format revision has no prior properties"); }
                String token = "format:" + markerId++; String id = identity(root, "format-" + markerId++); String author = attr(root, "author", ""); var date = date(root);
                boolean run = is(root, "rPrChange"); double paragraphSize = DocxAdapter.format(child(paragraph(root), "pPr")).fontSize();
                if (!run && child(prior, "rPr") != null) { throw new IOException("prior paragraph properties cannot contain run properties; font changes require a run format revision"); }
                var priorStyle = run ? DocxAdapter.inline(prior, paragraphSize) : null;
                var priorParagraph = run ? null : DocxAdapter.format(prior).toBuilder().fontSize(paragraphSize).build();
                if (run) { bracket(target, token); } else { target.insertBefore(mark(target, token, false), properties.getNextSibling()); target.appendChild(mark(target, token, true)); }
                revisions.add(p -> new Revision(id, run ? RevisionKind.RUN_FORMAT : RevisionKind.PARAGRAPH_FORMAT, range(p, token), author, date, "", priorStyle, priorParagraph)); remove(root);
            }
            case "sdt" -> {
                var pr = child(root, "sdtPr"); var content = child(root, "sdtContent"); if (pr == null || content == null) { throw new IOException("content control lacks properties or content"); }
                var type = controlKind(pr); String lock = attr(child(pr, "lock"), "val", "unlocked");
                if (child(pr, "dataBinding") != null || !lock.equals("unlocked") && !(type == ControlKind.GROUP && lock.equals("contentLocked"))) { throw new IOException("bound or locked content controls require an explicit data/permission provider"); }
                String id = root.hasAttributeNS(OD, "id") ? root.getAttributeNS(OD, "id") : attr(child(pr, "id"), "val", "control-" + markerId++); String token = "control:" + markerId++;
                boolean block = paragraph(root) == null; String tag = attr(child(pr, "tag"), "val", ""); String title = attr(child(pr, "alias"), "val", "");
                var list = child(pr, type == ControlKind.COMBO_BOX ? "comboBox" : "dropDownList"); var items = new ArrayList<ListItem>();
                for (var item : children(list)) { if (!is(item, "listItem")) { throw new IOException("unknown content control list item"); } items.add(new ListItem(attr(item, "displayText", attr(item, "value", "")), attr(item, "value", ""))); }
                var checkbox = direct(pr, W14, "checkbox"); boolean checked = checkbox != null && extensionOn(direct(checkbox, W14, "checked"));
                var dateControl = child(pr, "date"); String format = dateControl == null ? "" : attr(child(dateControl, "dateFormat"), "val", "yyyy-MM-dd"); String language = dateControl == null ? "" : attr(child(dateControl, "lid"), "val", "en-US");
                validateControlProperties(pr, type);
                bracket(content, token); controls.add(p -> new Control(id, type, range(p, token), tag, title, checked, items, format, language, block));
                remove(pr); var endPr = child(root, "sdtEndPr"); remove(endPr); unwrap(content); unwrap(root);
            }
            case "moveFromRangeStart", "moveFromRangeEnd", "moveToRangeStart", "moveToRangeEnd" -> remove(root);
            default -> { }
        }
    }
    private static boolean extensionOn(Element e) { if (e == null) { return false; } String value = e.getAttributeNS(e.getNamespaceURI(), "val"); return !Set.of("0", "false", "off").contains(value); }
    private static ControlKind controlKind(Element pr) throws IOException {
        if (direct(pr, W14, "checkbox") != null) { return ControlKind.CHECKBOX; }
        if (direct(pr, W15, "repeatingSection") != null) { return ControlKind.REPEATING_SECTION; }
        if (direct(pr, W15, "repeatingSectionItem") != null) { return ControlKind.REPEATING_ITEM; }
        for (var e : children(pr)) { if (!W.equals(e.getNamespaceURI())) { continue; } var type = switch(e.getLocalName()) { case "text" -> ControlKind.PLAIN_TEXT; case "richText" -> ControlKind.RICH_TEXT; case "dropDownList" -> ControlKind.DROP_DOWN; case "comboBox" -> ControlKind.COMBO_BOX; case "date" -> ControlKind.DATE; case "picture" -> ControlKind.PICTURE; case "group" -> ControlKind.GROUP; default -> null; }; if (type != null) { return type; } }
        return ControlKind.RICH_TEXT;
    }
    private void validateControlProperties(Element pr, ControlKind kind) throws IOException {
        var allowed = Set.of("id", "tag", "alias", "text", "richText", "dropDownList", "comboBox", "date", "picture", "group", "lock", "placeholder", "showingPlcHdr", "temporary", "appearance", "color", "rPr");
        for (var e : children(pr)) {
            if (W.equals(e.getNamespaceURI()) && allowed.contains(e.getLocalName()) || W14.equals(e.getNamespaceURI()) && e.getLocalName().equals("checkbox") || W15.equals(e.getNamespaceURI()) && Set.of("repeatingSection", "repeatingSectionItem").contains(e.getLocalName())) { continue; }
            throw new IOException("unsupported content control property: " + e.getLocalName());
        }
        if (child(pr, "placeholder") != null || child(pr, "showingPlcHdr") != null || child(pr, "temporary") != null || child(pr, "rPr") != null) { loss("sdtPr", "control placeholder/temporary/default-format presentation is materialized as its actual content"); }
        var checkbox = direct(pr, W14, "checkbox");
        if (checkbox != null && (direct(checkbox, W14, "checkedState") != null || direct(checkbox, W14, "uncheckedState") != null)) { loss("sdtPr/checkbox", "checkbox state glyph/font presentation is materialized in the actual value content"); }
        var date = child(pr, "date");
        if (date != null && (date.hasAttributeNS(W, "fullDate") || child(date, "storeMappedDataAs") != null || child(date, "calendar") != null)) { loss("sdtPr/date", "date canonical storage/calendar metadata is materialized as the displayed value, format and language"); }
    }
    private static OffsetDateTime date(Element e) throws IOException { String value = attr(e, "date", ""); if (value.isBlank()) { return null; } try { return OffsetDateTime.parse(value); } catch (java.time.format.DateTimeParseException failure) { throw new IOException("invalid revision/comment date", failure); } }
    private static Range range(Map<String, Point> points, String token) { var a = points.get(token + "/start"); var b = points.get(token + "/end"); if (a == null || b == null) { throw new IllegalArgumentException("native feature lacks complete semantic boundaries: " + token); } return new Range(a, b); }
    private void locate(Element root, Map<String, Point> points) throws IOException {
        for (var p : all(root, W, "p")) { if (!p.hasAttributeNS(OD, "id")) { continue; } locateChildren(p, p.getAttributeNS(OD, "id"), 0, points); }
    }
    private int locateChildren(Element root, String id, int offset, Map<String, Point> points) throws IOException {
        for (var e : children(root)) {
            if (OD.equals(e.getNamespaceURI()) && e.getLocalName().equals("mark")) { if (points.putIfAbsent(e.getAttribute("token"), new Point(id, offset)) != null) { throw new IOException("duplicate native semantic boundary"); } }
            else if (is(e, "pPr") || is(e, "rPr")) { continue; }
            else if (is(e, "t") || is(e, "delText") || is(e, "drawing") || is(e, "fldSimple") || is(e, "tab") || is(e, "br")) { offset += length(e); }
            else { offset = locateChildren(e, id, offset, points); }
        }
        return offset;
    }
    private void side(String key, List<Element> content, Element body) throws IOException {
        var ids = new ArrayList<String>(); var boundary = child(body, "sectPr");
        for (var e : content) { if (!is(e, "p") && !is(e, "tbl")) { throw new IOException("native side story accepts paragraphs/tables only"); }
            String id = e.getAttributeNS(OD, "id"); if (id.isBlank()) { throw new IOException("native side story lacks semantic ID"); } ids.add(id);
            body.insertBefore(body.getOwnerDocument().importNode(e, true), boundary);
        }
        if (ids.isEmpty()) { throw new IOException("empty native side story"); } sideIds.put(key, List.copyOf(ids));
    }
    private void loss(String element, String message) throws IOException { DocxAdapter.loss(policy, diagnostics, "word/document.xml", element, message); }
    private static List<Revision> mergeRevisions(List<Revision> values) throws IOException {
        var result = new LinkedHashMap<String, Revision>();
        for (var r : values) { var prior = result.get(r.id()); if (prior == null) { result.put(r.id(), r); }
            else { if (prior.kind() != r.kind() || !prior.author().equals(r.author()) || !Objects.equals(prior.date(), r.date()) || !Objects.equals(prior.priorStyle(), r.priorStyle()) || !Objects.equals(prior.priorParagraphFormat(), r.priorParagraphFormat())) { throw new IOException("conflicting duplicate revision identity"); }
                result.put(r.id(), new Revision(r.id(), r.kind(), new Range(prior.range().start(), r.range().end()), r.author(), r.date(), r.moveId(), r.priorStyle(), r.priorParagraphFormat())); }
        }
        return List.copyOf(result.values());
    }
    // Native fields and drawings are decoded below, after wrappers have been normalized.
    private static final class FieldState { Element begin; Element separator; Element end; FieldState parent; final StringBuilder code = new StringBuilder(); final List<Element> instructions = new ArrayList<>(); }
    private void fields(Element root) throws IOException {
        var stack = new ArrayList<FieldState>(); var complete = new ArrayList<FieldState>();
        for (var e : descendants(root)) {
            if (is(e, "fldChar")) { String type = attr(e, "fldCharType", ""); if (type.equals("begin")) { var f = new FieldState(); f.begin = e; f.parent = stack.isEmpty() ? null : stack.getLast(); stack.add(f); }
                else if (type.equals("separate") && !stack.isEmpty() && stack.getLast().separator == null) { stack.getLast().separator = e; }
                else if (type.equals("end") && !stack.isEmpty()) { var f = stack.removeLast(); f.end = e; complete.add(f); }
                else { throw new IOException("unbalanced Word native field"); }
            } else if (is(e, "instrText")) { if (stack.isEmpty() || stack.getLast().separator != null) { throw new IOException("field instruction outside code range"); } var f = stack.getLast(); f.code.append(e.getTextContent()); f.instructions.add(e); if (f.code.length() > 2048) { throw new IOException("Word field instruction budget exceeded"); } }
        }
        if (!stack.isEmpty()) { throw new IOException("unterminated Word native field"); }
        for (var f : complete) {
            String instruction = f.code.toString().strip(); String command = instruction.split("\\s+", 2)[0].toUpperCase(Locale.ROOT);
            if (command.equals("TOC") || command.startsWith("FORM")) {
                if (f.separator == null) { throw new IOException("native field requires explicit cached content separator"); }
                String token = "field:" + markerId++; String id = f.begin.hasAttributeNS(OD, "id") ? f.begin.getAttributeNS(OD, "id") : "field-" + markerId++;
                f.separator.getParentNode().insertBefore(mark(f.separator, token, false), f.separator.getNextSibling()); f.end.getParentNode().insertBefore(mark(f.end, token, true), f.end);
                if (command.equals("TOC")) { toc(id, token, instruction, dirty(f.begin)); } else { legacy(id, token, command, f.begin); }
                for (var e : f.instructions) { remove(e); } remove(f.begin); remove(f.separator); remove(f.end);
            } else if (insideToc(f)) { for (var e : f.instructions) { remove(e); } remove(f.begin); remove(f.separator); remove(f.end); }
            else { normalizeOrdinaryField(f, instruction); }
        }
        for (var field : all(root, W, "fldSimple")) {
            String code = attr(field, "instr", "").strip(); if (code.toUpperCase(Locale.ROOT).startsWith("TOC ") || code.equalsIgnoreCase("TOC")) {
                String token = "toc:" + markerId++; bracket(field, token); toc(identity(field, "toc-" + markerId++), token, code, dirty(field)); unwrap(field);
            }
        }
        int tocDepth = 0;
        for (var e : descendants(root)) {
            if (OD.equals(e.getNamespaceURI()) && e.getLocalName().equals("mark")) { String token = e.getAttribute("token"); String key = token.substring(0, token.lastIndexOf('/')); if (tocTokens.contains(key)) { tocDepth += token.endsWith("/start") ? 1 : -1; } }
            else if (tocDepth > 0 && is(e, "hyperlink") && e.hasAttributeNS(W, "anchor") && !e.hasAttributeNS(R, "id")) { unwrap(e); }
        }
    }
    private static boolean insideToc(FieldState f) { for (var p = f.parent; p != null; p = p.parent) { if (p.code.toString().strip().toUpperCase(Locale.ROOT).matches("TOC(?:\\s.*)?")) { return true; } } return false; }
    private void normalizeOrdinaryField(FieldState f, String instruction) throws IOException {
        var first = f.begin.getParentNode(); var last = f.end.getParentNode();
        if (first.getParentNode() != last.getParentNode() || !(first.getParentNode() instanceof Element parent) || !is(parent, "p")) { throw new IOException("ordinary complex fields must be flat within one paragraph"); }
        var simple = word(parent, "fldSimple"); simple.setAttributeNS(W, "w:instr", instruction);
        if (f.begin.hasAttributeNS(OD, "width")) { simple.setAttributeNS(OD, "od:width", f.begin.getAttributeNS(OD, "width")); }
        parent.insertBefore(simple, first); boolean cache = false;
        for (Node node = first; node != null;) { Node nextNode = node.getNextSibling(); if (f.separator != null && node == f.separator.getParentNode()) { cache = true; remove((Element)node); }
            else if (node == last) { parent.removeChild(node); break; }
            else if (cache) { simple.appendChild(node); } else { parent.removeChild(node); } node = nextNode; }
    }
    private static boolean dirty(Element field) { return Set.of("true", "1", "on").contains(attr(field, "dirty", "false")); }
    private void toc(String id, String token, String instruction, boolean dirty) throws IOException {
        tocTokens.add(token);
        var levels = Pattern.compile("\\\\o\\s+\"([1-9])-([1-9])\"", Pattern.CASE_INSENSITIVE).matcher(instruction); int first = 1; int last = 3;
        if (levels.find()) { first = Integer.parseInt(levels.group(1)); last = Integer.parseInt(levels.group(2)); }
        String rest = instruction.replaceFirst("(?i)^TOC", "").replaceAll("(?i)\\\\o\\s+\"[1-9]-[1-9]\"", "").replaceAll("(?i)\\\\[huzn]", "").strip();
        if (!rest.isEmpty()) { throw new IOException("unsupported TOC switches require a typed mapping"); }
        int from = first; int to = last; boolean links = instruction.matches("(?s).*\\\\[hH](?:\\s|$).*"); boolean pageNumbers = !instruction.matches("(?s).*\\\\[nN](?:\\s|$).*");
        contents.add(p -> new Contents(id, range(p, token), from, to, links, pageNumbers, dirty));
    }
    private void legacy(String id, String token, String command, Element begin) throws IOException {
        var data = child(begin, "ffData"); if (data == null) { throw new IOException("legacy form field requires ffData"); }
        for (var e : children(data)) { if (!Set.of("name", "enabled", "calcOnExit", "textInput", "checkBox", "ddList", "statusText", "helpText").contains(e.getLocalName())) { throw new IOException("unsupported legacy form behavior"); } }
        var type = switch(command) { case "FORMTEXT" -> ControlKind.LEGACY_TEXT; case "FORMCHECKBOX" -> ControlKind.LEGACY_CHECKBOX; case "FORMDROPDOWN" -> ControlKind.LEGACY_DROP_DOWN; default -> throw new IOException("unsupported legacy form field"); };
        String name = attr(child(data, "name"), "val", ""); var items = new ArrayList<ListItem>();
        for (var item : children(child(data, "ddList"))) { if (is(item, "listEntry")) { String value = attr(item, "val", ""); items.add(new ListItem(value, value)); } }
        boolean checked = on(child(child(data, "checkBox"), "checked"));
        var input = child(data, "textInput"); var cb = child(data, "checkBox"); var dd = child(data, "ddList");
        var options = new ControlOptions(attr(child(input, "default"), "val", ""), (int)number(attr(child(input, "maxLength"), "val", "0")), attr(child(input, "type"), "val", "regular"), attr(child(input, "format"), "val", ""),
                formHelp(child(data, "helpText")), formHelp(child(data, "statusText")), on(child(data, "calcOnExit")), child(data, "enabled") == null || on(child(data, "enabled")), number(attr(child(cb, "size"), "val", "0")) / 2, on(child(cb, "default")), (int)number(attr(child(dd, "default"), "val", "0")));
        controls.add(p -> new Control(id, type, range(p, token), name, name, checked, items, "", "", false, options));
    }
    private static String formHelp(Element value) throws IOException { if (value == null) { return ""; } if (!attr(value, "type", "text").equals("text")) { throw new IOException("automatic legacy form help text requires an explicit glossary mapping"); } return attr(value, "val", ""); }
    private void images(Element root, Map<String, byte[]> parts, Element relationships, Element body) throws IOException {
        var decoder = new DocxImages.Reader(parts, relationships);
        var drawingElements = new ArrayList<>(all(root, W, "drawing")); Collections.reverse(drawingElements);
        for (var drawing : drawingElements) {
            if (drawing.getParentNode() == null) { continue; }
            var container = direct(drawing, WP, "anchor"); var inline = direct(drawing, WP, "inline"); if (container == null) { container = inline; }
            if (container == null) { throw new IOException("unsupported native drawing container"); }
            var text = all(container, WPS, "wsp"); var transforms = all(container, A, "xfrm"); var crops = all(container, A, "srcRect");
            boolean advanced = inline == null || !text.isEmpty() || !crops.isEmpty() || transforms.stream().anyMatch(t -> t.hasAttribute("rot") || t.hasAttribute("flipH") || t.hasAttribute("flipV"));
            DocxSemanticDrawings.validate(container);
            if (!advanced) { DocxSemanticDrawings.normalizePicture(container); continue; }
            var placement = DocxSemanticDrawings.placement(container); var extent = direct(container, WP, "extent"); if (extent == null) { throw new IOException("drawing extent missing"); }
            var properties = direct(container, WP, "docPr"); String id = properties == null ? "drawing-" + markerId++ : properties.getAttribute("name"); if (id.isBlank()) { id = "drawing-" + markerId++; }
            String token = "drawing:" + markerId++; drawing.getParentNode().insertBefore(mark(drawing, token, false), drawing);
            if (!text.isEmpty()) {
                if (text.size() != 1) { throw new IOException("multiple text shapes require a group drawing model"); }
                var shape = text.getFirst(); var box = direct(direct(shape, WPS, "txbx"), W, "txbxContent"); if (box == null) { throw new IOException("textbox content missing"); }
                var boxBody = direct(shape, WPS, "bodyPr"); var shapePr = direct(shape, WPS, "spPr");
                double width = number(extent.getAttribute("cx")) / 12700; double height = number(extent.getAttribute("cy")) / 12700;
                double t = DocxSemanticDrawings.inset(boxBody, "tIns", 3.6); double r = DocxSemanticDrawings.inset(boxBody, "rIns", 7.2); double b = DocxSemanticDrawings.inset(boxBody, "bIns", 3.6); double l = DocxSemanticDrawings.inset(boxBody, "lIns", 7.2);
                var vertical = boxBody == null ? VerticalAlignment.TOP : switch(boxBody.getAttribute("anchor")) { case "ctr" -> VerticalAlignment.CENTER; case "b" -> VerticalAlignment.BOTTOM; default -> VerticalAlignment.TOP; };
                Integer fill = DocxSemanticDrawings.color(direct(shapePr, A, "solidFill")); var line = direct(shapePr, A, "ln"); Integer border = DocxSemanticDrawings.color(direct(line, A, "solidFill")); double lineWidth = line == null || !line.hasAttribute("w") ? 0 : number(line.getAttribute("w")) / 12700;
                var transform = DocxSemanticDrawings.transform(direct(shapePr, A, "xfrm"), null);
                String side = "textbox/" + token; side(side, children(box), body); String drawingId = id;
                drawings.add(p -> new Drawing(drawingId, p.get(token + "/start"), placement, new TextBox(sideBlocks.get(side), width, height, t, r, b, l, vertical, fill, border, lineWidth, transform))); remove(drawing);
            } else {
                var picture = all(container, PIC, "pic"); if (picture.size() != 1) { throw new IOException("native drawing requires one picture or textbox"); }
                var fill = direct(picture.getFirst(), PIC, "blipFill"); var transform = DocxSemanticDrawings.transform(direct(direct(picture.getFirst(), PIC, "spPr"), A, "xfrm"), direct(fill, A, "srcRect"));
                var normalized = (Element)drawing.cloneNode(true); var normalizedContainer = direct(normalized, WP, inline == null ? "anchor" : "inline");
                DocxSemanticDrawings.normalizePicture(normalizedContainer);
                var image = decoder.read(normalized, 0, ""); drawing.getParentNode().replaceChild(normalized, drawing); String drawingId = id;
                drawings.add(p -> new Drawing(drawingId, p.get(token + "/start"), placement, new Picture(image, transform)));
            }
        }
    }
}
