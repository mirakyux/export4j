// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import static com.mirakyux.export4j.adapter.docx.DocxAdapter.child;
import static com.mirakyux.export4j.adapter.docx.DocxAdapter.children;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Standard table geometry and proven-empty paragraphs that keep adjacent grids independent. */
final class DocxTables {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String OD = "urn:com.mirakyux.export4j:semantic:1";

    private DocxTables() { }

    static void separate(StringBuilder xml) {
        xml.append("<w:p od:tableSeparator=\"true\"><w:pPr><w:spacing w:before=\"0\" w:after=\"0\" w:line=\"1\" w:lineRule=\"exact\"/>"
                + "<w:rPr><w:sz w:val=\"2\"/></w:rPr></w:pPr></w:p>");
    }

    static boolean isSeparator(Element element) throws IOException {
        if (!element.hasAttributeNS(OD, "tableSeparator")) { return false; }
        if (!(element.getParentNode() instanceof Element parent) || !W.equals(parent.getNamespaceURI())
                || !Set.of("body", "tc", "hdr", "ftr").contains(parent.getLocalName())
                || !tableSibling(element, true) || !tableSibling(element, false)) {
            throw new IOException("DOCX table separator must be between adjacent tables");
        }
        separatorShape(element, "p");
        return true;
    }

    private static boolean tableSibling(Element element, boolean previous) {
        Node sibling = previous ? element.getPreviousSibling() : element.getNextSibling();
        while (sibling != null && !(sibling instanceof Element)) {
            sibling = previous ? sibling.getPreviousSibling() : sibling.getNextSibling();
        }
        return sibling instanceof Element table && W.equals(table.getNamespaceURI()) && table.getLocalName().equals("tbl");
    }

    private static void separatorShape(Element element, String name) throws IOException {
        Map<String, String> attributes = switch (name) {
            case "p" -> Map.of("tableSeparator", "true");
            case "spacing" -> Map.of("before", "0", "after", "0", "line", "1", "lineRule", "exact");
            case "sz" -> Map.of("val", "2");
            default -> Map.of();
        };
        String namespace = name.equals("p") ? OD : W;
        if (!W.equals(element.getNamespaceURI()) || !name.equals(element.getLocalName()) || !element.getTextContent().isBlank()) {
            throw new IOException("DOCX table separator cannot contain semantic content");
        }
        int count = 0;
        var actual = element.getAttributes();
        for (int index = 0; index < actual.getLength(); index++) {
            var attribute = actual.item(index);
            if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI())) { continue; }
            if (!namespace.equals(attribute.getNamespaceURI())
                    || !attribute.getNodeValue().equals(attributes.get(attribute.getLocalName()))) {
                throw new IOException("DOCX table separator cannot carry other attributes");
            }
            count++;
        }
        List<String> names = switch (name) {
            case "p" -> List.of("pPr");
            case "pPr" -> List.of("spacing", "rPr");
            case "rPr" -> List.of("sz");
            default -> List.of();
        };
        var content = children(element);
        if (count != attributes.size() || content.size() != names.size()) { throw new IOException("invalid DOCX table separator shape"); }
        for (int index = 0; index < content.size(); index++) { separatorShape(content.get(index), names.get(index)); }
    }

    static void validateWidths(Element table, DocxAdapter.Policy policy, List<DocxAdapter.Diagnostic> diagnostics) throws IOException {
        var widths = new ArrayList<Long>();
        for (var column : children(child(table, "tblGrid"))) { widths.add(widthValue(column)); }
        long total = 0;
        try { for (long width : widths) { total = Math.addExact(total, width); } }
        catch (ArithmeticException failure) { throw new IOException("DOCX table grid width overflows", failure); }
        var properties = child(table, "tblPr");
        preferredWidth(properties, "tblW", widths.isEmpty() ? -1 : total, policy, diagnostics);
        var layout = single(properties, "tblLayout");
        if (layout != null && !layout.getAttributeNS(W, "type").equals("fixed")) {
            DocxAdapter.loss(policy, diagnostics, "word/document.xml", "tblLayout", "automatic Word table layout is reduced to the explicit grid");
        }
        for (var row : children(table)) {
            if (!W.equals(row.getNamespaceURI()) || !row.getLocalName().equals("tr")) { continue; }
            int column = 0;
            for (var cell : children(row)) {
                if (!W.equals(cell.getNamespaceURI()) || !cell.getLocalName().equals("tc")) { continue; }
                int span = DocxAdapter.readColumnSpan(cell);
                long width = -1;
                if (!widths.isEmpty()) {
                    if (span > widths.size() - column) { throw new IOException("DOCX cell coverage exceeds the table grid"); }
                    width = 0;
                    for (int offset = 0; offset < span; offset++) { width += widths.get(column + offset); }
                }
                preferredWidth(child(cell, "tcPr"), "tcW", width, policy, diagnostics);
                if (span > Integer.MAX_VALUE - column) { throw new IOException("DOCX table column span overflows"); }
                column += span;
            }
        }
    }

    private static void preferredWidth(Element parent, String name, long expected, DocxAdapter.Policy policy,
            List<DocxAdapter.Diagnostic> diagnostics) throws IOException {
        var declaration = single(parent, name);
        if (declaration == null) { return; }
        long width = widthValue(declaration);
        if (!declaration.getAttributeNS(W, "type").equals("dxa") || width != expected) {
            DocxAdapter.loss(policy, diagnostics, "word/document.xml", name, "preferred width differs from the explicit table grid");
        }
    }

    private static Element single(Element parent, String name) throws IOException {
        var matches = children(parent).stream().filter(element -> W.equals(element.getNamespaceURI()) && element.getLocalName().equals(name)).toList();
        if (matches.size() > 1) { throw new IOException("duplicate DOCX table width property: " + name); }
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private static long widthValue(Element element) throws IOException {
        try {
            long width = Long.parseLong(element.getAttributeNS(W, "w").trim());
            if (width < 0) { throw new NumberFormatException("negative width"); }
            return width;
        } catch (NumberFormatException failure) { throw new IOException("invalid DOCX table width", failure); }
    }
}
