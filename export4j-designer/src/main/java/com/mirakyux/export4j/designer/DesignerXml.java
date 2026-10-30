// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.designer;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.HashSet;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/** Edits retained source rather than flattening a bound document back to a template. */
final class DesignerXml {
    record Entry(String path, String tag, String id, String label, List<Entry> children) {
        @Override public String toString() { return label; }
    }
    record PreviewSource(String source, Map<String, String> paths) { }
    private DesignerXml() { }
    static Document parse(String source) throws Exception {
        if (source.length() > 1_000_000) { throw new IllegalArgumentException("XML source exceeds 1,000,000 UTF-16 units"); }
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new DefaultHandler() {
            @Override public void error(org.xml.sax.SAXParseException ex) throws SAXException { throw ex; }
            @Override public void fatalError(org.xml.sax.SAXParseException ex) throws SAXException { throw ex; }
        });
        var document = builder.parse(new InputSource(new StringReader(source)));
        checkTree(document.getDocumentElement(), 0, new int[1]);
        return document;
    }
    private static void checkTree(Element node, int depth, int[] count) {
        if (depth > 48 || ++count[0] > 20_000) { throw new IllegalArgumentException("XML designer tree budget exceeded"); }
        for (var child : children(node)) { checkTree(child, depth + 1, count); }
    }
    static List<Element> children(Element node) {
        var list = new ArrayList<Element>();
        for (var child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element) { list.add(element); }
        }
        return list;
    }
    static Entry outline(Element node, String path) {
        var nested = new ArrayList<Entry>(); var children = children(node);
        for (int n = 0; n < children.size(); n++) { nested.add(outline(children.get(n), path + "/" + n)); }
        String text = node.getTextContent().strip().replaceAll("\\s+", " ");
        if (text.length() > 36) { text = text.substring(0, 36) + "…"; }
        String id = node.getAttribute("id");
        String label = node.getTagName() + (id.isEmpty() ? "" : " #" + id) + (nested.isEmpty() ? "  " + text : "");
        return new Entry(path, node.getTagName(), id, label, List.copyOf(nested));
    }
    static PreviewSource previewSource(String source) throws Exception {
        var document = parse(source); var paths = new LinkedHashMap<String, String>();
        String prefix = "e4j-preview-";
        while (source.contains(prefix)) { prefix += "x-"; }
        previewIds(document.getDocumentElement(), "", prefix, paths);
        return new PreviewSource(serialize(document), Map.copyOf(paths));
    }
    private static void previewIds(Element node, String path, String prefix, Map<String, String> paths) {
        if (List.of("p", "heading", "bullet", "numbered", "table", "caption", "cross-reference", "page-break").contains(node.getTagName())) {
            if (!node.hasAttribute("id")) { node.setAttribute("id", prefix + path.substring(1).replace('/', '-')); }
            paths.put(node.getAttribute("id"), path);
        }
        var nested = children(node);
        for (int n = 0; n < nested.size(); n++) { previewIds(nested.get(n), path + "/" + n, prefix, paths); }
    }
    static Element at(Document document, String path) {
        Element node = document.getDocumentElement();
        if (!path.isEmpty()) {
            for (String part : path.substring(1).split("/")) { node = children(node).get(Integer.parseInt(part)); }
        }
        return node;
    }
    static String serialize(org.w3c.dom.Node node) throws Exception {
        var factory = TransformerFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        var writer = factory.newTransformer();
        writer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        // Indenting mixed content changes its literal whitespace and therefore its text.
        writer.setOutputProperty(OutputKeys.INDENT, "no");
        var output = new StringWriter(); writer.transform(new DOMSource(node), new StreamResult(output));
        return output.toString();
    }
    static String properties(String source, String path, Map<String, String> attributes, String text) throws Exception {
        var document = parse(source); var element = at(document, path);
        var old = element.getAttributes();
        while (old.getLength() != 0) { element.removeAttributeNode((org.w3c.dom.Attr) old.item(0)); }
        attributes.forEach(element::setAttribute);
        if (text != null) {
            if (!children(element).isEmpty()) { throw new IllegalArgumentException("mixed content must be edited in the node XML tab"); }
            element.setTextContent(text);
        }
        return serialize(document);
    }
    static String patchProperties(String source, List<String> paths, Map<String, String> changes) throws Exception {
        var document = parse(source);
        var unique = new HashSet<String>();
        if (paths.isEmpty() || paths.size() > 200) { throw new IllegalArgumentException("select 1 to 200 elements"); }
        for (String path : paths) {
            if (!unique.add(path)) { throw new IllegalArgumentException("duplicate selection path"); }
            var element = at(document, path);
            for (var change : changes.entrySet()) {
                if (change.getValue().isEmpty()) { element.removeAttribute(change.getKey()); }
                else { element.setAttribute(change.getKey(), change.getValue()); }
            }
        }
        return serialize(document);
    }
    static String removeMany(String source, List<String> paths) throws Exception {
        var document = parse(source);
        var nodes = selectedSiblings(document, paths);
        nodes.forEach(node -> node.getParentNode().removeChild(node));
        return serialize(document);
    }
    static String relocateMany(String source, List<String> paths, String target, boolean after) throws Exception {
        var document = parse(source);
        var nodes = selectedSiblings(document, paths);
        if (!target.contains("/") || !target.substring(0, target.lastIndexOf('/')).equals(paths.getFirst().substring(0, paths.getFirst().lastIndexOf('/')))) {
            throw new IllegalArgumentException("drop target must be a sibling in the same container");
        }
        var destination = at(document, target);
        var parent = nodes.getFirst().getParentNode();
        if (destination.getParentNode() != parent || nodes.contains(destination)) {
            throw new IllegalArgumentException("drop target must be an unselected sibling");
        }
        var anchor = after ? destination.getNextSibling() : destination;
        for (var node : nodes) { parent.insertBefore(node, anchor); }
        return serialize(document);
    }
    private static List<Element> selectedSiblings(Document document, List<String> paths) {
        if (paths.isEmpty() || paths.size() > 200 || paths.size() != new HashSet<>(paths).size()) {
            throw new IllegalArgumentException("select 1 to 200 distinct elements");
        }
        var nodes = paths.stream().map(path -> at(document, path)).toList();
        var parent = nodes.getFirst().getParentNode();
        if (!(parent instanceof Element) || nodes.stream().anyMatch(node -> node.getParentNode() != parent)) {
            throw new IllegalArgumentException("multi-selection must contain siblings in one container");
        }
        return children((Element) parent).stream().filter(nodes::contains).toList();
    }
    static String replace(String source, String path, String fragment) throws Exception {
        var document = parse(source); var replacement = parse(fragment).getDocumentElement();
        var node = at(document, path);
        node.getParentNode().replaceChild(document.importNode(replacement, true), node);
        return serialize(document);
    }
    static String insert(String source, String path, String fragment) throws Exception {
        var document = parse(source); var selected = at(document, path);
        var fresh = document.importNode(parse(fragment).getDocumentElement(), true);
        if (List.of("document", "section", "then", "else", "each", "cell").contains(selected.getTagName())) {
            selected.appendChild(fresh);
        } else { selected.getParentNode().insertBefore(fresh, selected.getNextSibling()); }
        return serialize(document);
    }
    static String insertBeside(String source, String path, String fragment, boolean after) throws Exception {
        var document = parse(source); var selected = at(document, path);
        if (!(selected.getParentNode() instanceof Element parent)) { throw new IllegalArgumentException("insertion needs a body element target"); }
        var fresh = document.importNode(parse(fragment).getDocumentElement(), true);
        parent.insertBefore(fresh, after ? selected.getNextSibling() : selected);
        return serialize(document);
    }
    static String startSection(String source, String path, String id, String initialId) throws Exception {
        var document = parse(source);
        var root = document.getDocumentElement();
        var selected = at(document, path);
        if (!id.matches("[A-Za-z_][A-Za-z_0-9-]{0,127}") || !initialId.matches("[A-Za-z_][A-Za-z_0-9-]{0,127}")) {
            throw new IllegalArgumentException("section IDs must be static identifiers");
        }
        if (id.equals(initialId)) { throw new IllegalArgumentException("section IDs must be distinct"); }
        boolean sectioned = children(root).stream().anyMatch(node -> node.getTagName().equals("section"));
        if (!(selected.getParentNode() instanceof Element parent)) {
            throw new IllegalArgumentException("select a body element to start a section");
        }
        if (sectioned ? parent.getParentNode() != root || !parent.getTagName().equals("section") : parent != root) {
            throw new IllegalArgumentException("select a direct body element to start a section");
        }
        if (List.of("page", "header", "footer", "background", "foreground", "navigation", "notes", "section").contains(selected.getTagName())) {
            throw new IllegalArgumentException("select a body element to start a section");
        }
        var sections = root.getElementsByTagName("section");
        for (int index = 0; index < sections.getLength(); index++) {
            var element = (Element) sections.item(index);
            if (element.getAttribute("id").equals(id) || element.getAttribute("id").equals(initialId)) {
                throw new IllegalArgumentException("section ID already exists");
            }
        }
        var next = document.createElement("section"); next.setAttribute("id", id);
        if (sectioned) {
            var firstBody = children(parent).stream().filter(node -> !List.of("page", "header", "footer", "background", "foreground").contains(node.getTagName())).findFirst().orElseThrow();
            if (firstBody == selected) { throw new IllegalArgumentException("this is the first body element; edit the current section settings instead"); }
            parent.getParentNode().insertBefore(next, parent.getNextSibling());
        } else {
            var first = document.createElement("section"); first.setAttribute("id", initialId);
            var body = children(root).stream().filter(node -> !List.of("page", "header", "footer", "background", "foreground", "navigation", "notes").contains(node.getTagName())).findFirst().orElseThrow();
            root.insertBefore(first, body);
            root.insertBefore(next, first.getNextSibling());
            for (Node node = body; node != selected; ) {
                var following = node.getNextSibling();
                first.appendChild(node);
                node = following;
            }
            if (children(first).isEmpty()) { root.removeChild(first); }
        }
        for (Node node = selected; node != null; ) {
            var following = node.getNextSibling();
            next.appendChild(node);
            node = following;
        }
        return serialize(document);
    }
    static String move(String source, String path, int direction) throws Exception {
        var document = parse(source); var selected = at(document, path);
        if (!(selected.getParentNode() instanceof Element parent)) { throw new IllegalArgumentException("cannot move document root"); }
        var siblings = children(parent); int current = siblings.indexOf(selected); int target = current + direction;
        if (target < 0 || target >= siblings.size()) { throw new IllegalArgumentException("already at the container boundary"); }
        var other = siblings.get(target);
        parent.insertBefore(selected, direction < 0 ? other : other.getNextSibling());
        return serialize(document);
    }
    static String remove(String source, String path) throws Exception {
        var document = parse(source); var node = at(document, path);
        if (!(node.getParentNode() instanceof Element)) { throw new IllegalArgumentException("cannot delete document root"); }
        node.getParentNode().removeChild(node); return serialize(document);
    }
    static String moveBefore(String source, String path, String target) throws Exception {
        return relocate(source, path, target, false);
    }
    static String relocate(String source, String path, String target, boolean after) throws Exception {
        var document = parse(source); var node = at(document, path); var destination = at(document, target);
        if (!(node.getParentNode() instanceof Element parent) || parent != destination.getParentNode()) {
            throw new IllegalArgumentException("drag sorting requires two siblings in the same container");
        }
        if (node != destination) { parent.insertBefore(node, after ? destination.getNextSibling() : destination); }
        return serialize(document);
    }
    static String nextId(String source) {
        for (int n = 1; n <= 20_001; n++) { String id = "design-" + n; if (!source.contains("\"" + id + "\"") && !source.contains("'" + id + "'")) { return id; } }
        throw new IllegalArgumentException("designer ID budget exceeded");
    }
}
