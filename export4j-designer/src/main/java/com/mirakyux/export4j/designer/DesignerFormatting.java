// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.designer;

import java.util.List;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Formatting is a draft edit; inline text and mixed-content whitespace remain literal. */
final class DesignerFormatting {
    private static final List<String> CONTAINERS = List.of("document", "fragment", "section", "header", "footer",
            "background", "foreground", "box", "navigation", "contents", "typography", "notes", "if", "then", "else",
            "each", "table", "rows", "columns", "row");
    private DesignerFormatting() { }

    static String xml(String source) throws Exception {
        var document = DesignerXml.parse(source);
        indent(document.getDocumentElement(), 0, false);
        String result = DesignerXml.serialize(document);
        if (result.length() > 1_000_000) { throw new IllegalArgumentException("formatted XML exceeds source budget"); }
        return result;
    }

    private static void indent(Element element, int depth, boolean inheritedPreserve) {
        boolean preserve = element.hasAttribute("xml:space") ? element.getAttribute("xml:space").equals("preserve") : inheritedPreserve;
        if (preserve) { return; }
        var elements = DesignerXml.children(element);
        boolean blockCell = element.getTagName().equals("cell") && !elements.isEmpty()
                && elements.stream().allMatch(child -> List.of("p", "table").contains(child.getTagName()));
        if (!CONTAINERS.contains(element.getTagName()) && !blockCell) { return; }
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.CDATA_SECTION_NODE
                    || child.getNodeType() == Node.TEXT_NODE && !child.getNodeValue().isBlank()) { return; }
        }
        var retained = new java.util.ArrayList<Node>();
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() != Node.TEXT_NODE) { retained.add(child); }
        }
        while (element.hasChildNodes()) { element.removeChild(element.getFirstChild()); }
        var document = element.getOwnerDocument();
        for (var child : retained) {
            element.appendChild(document.createTextNode("\n" + "  ".repeat(depth + 1)));
            element.appendChild(child);
            if (child instanceof Element nested) { indent(nested, depth + 1, false); }
        }
        if (!retained.isEmpty()) { element.appendChild(document.createTextNode("\n" + "  ".repeat(depth))); }
    }

    static String json(String source) {
        // The server parser rejects duplicate keys and retains bounded BigDecimal values exactly.
        String compact = BindingJson.write(BindingJson.parse(source));
        var result = new StringBuilder(); int depth = 0; boolean quoted = false; boolean escaped = false;
        for (int index = 0; index < compact.length(); index++) {
            char value = compact.charAt(index);
            if (quoted) {
                result.append(value);
                if (escaped) { escaped = false; } else if (value == '\\') { escaped = true; } else if (value == '"') { quoted = false; }
            } else if (value == '"') { quoted = true; result.append(value); }
            else if (value == '{' || value == '[') {
                result.append(value); depth++;
                char next = compact.charAt(index + 1);
                if (next != '}' && next != ']') { result.append('\n').append("  ".repeat(depth)); }
            } else if (value == '}' || value == ']') {
                depth--; char previous = compact.charAt(index - 1);
                if (previous != '{' && previous != '[') { result.append('\n').append("  ".repeat(depth)); }
                result.append(value);
            } else if (value == ',') { result.append(",\n").append("  ".repeat(depth)); }
            else if (value == ':') { result.append(": "); }
            else { result.append(value); }
        }
        if (result.length() > 1_000_000) { throw new IllegalArgumentException("formatted JSON exceeds source budget"); }
        return result.toString();
    }
}
