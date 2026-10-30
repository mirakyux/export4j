// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import static com.mirakyux.export4j.adapter.docx.DocxAdapter.children;
import static com.mirakyux.export4j.adapter.docx.DocxAdapter.child;

import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.Table;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import org.w3c.dom.Element;

/** Task-confined semantic indexing. Physical continuation/tail paragraphs never acquire source IDs. */
final class DocxCells {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String OD = "urn:com.mirakyux.export4j:semantic:1";
    private DocxCells() { }

    static final class Budget {
        private final DocxLimits limits;
        private long nodes;
        private long slots;
        private long points;
        Budget(DocxLimits limits) { this.limits = limits; }
        void node() throws IOException {
            ExecutionContext.checkpoint();
            if (++nodes > limits.maxBlocks()) { throw new IOException("DOCX semantic block budget exceeded"); }
        }
        void slots(int rows, int columns) throws IOException {
            long count = (long) rows * columns;
            if (count > limits.maxTableSlots() - slots) { throw new IOException("DOCX cumulative table slot budget exceeded"); }
            slots += count;
        }
        void text(String text) throws IOException {
            long count = text.codePointCount(0, text.length());
            if (count > limits.maxSourceCodePoints() - points) { throw new IOException("DOCX cumulative source text budget exceeded"); }
            points += count;
        }
    }

    static Map<Element, String> index(Element body, Budget budget) throws IOException {
        return index(body, budget, new HashSet<>(), "");
    }

    static Map<Element, String> index(Element body, Budget budget, Set<String> used, String prefix) throws IOException {
        List<Element> elements = new ArrayList<>();
        for (var element : children(body)) {
            if (!W.equals(element.getNamespaceURI())) { continue; }
            if (element.getLocalName().equals("p") && !element.getAttributeNS(OD, "boundary").equals("true") && !DocxTables.isSeparator(element)) {
                budget.node(); elements.add(element);
            } else if (element.getLocalName().equals("tbl")) { indexTable(element, 1, elements, budget); }
        }
        for (var element : elements) {
            if (element.hasAttributeNS(OD, "id")) {
                String id = element.getAttributeNS(OD, "id");
                if (id.isBlank() || !used.add(id)) { throw new IOException("duplicate or empty DOCX semantic ID"); }
            }
        }
        Map<Element, String> ids = new HashMap<>();
        int next = 0;
        for (var element : elements) {
            String id = element.getAttributeNS(OD, "id");
            if (id.isEmpty()) {
                do { id = prefix + (element.getLocalName().equals("p") ? "p-" : "table-") + next++; } while (!used.add(id));
            }
            ids.put(element, id);
        }
        return ids;
    }

    private static void indexTable(Element table, int depth, List<Element> elements, Budget budget) throws IOException {
        if (depth > com.mirakyux.export4j.model.SemanticTree.MAX_TABLE_DEPTH) { throw new IOException("DOCX semantic table nesting budget exceeded"); }
        budget.node(); elements.add(table);
        int columns = children(child(table, "tblGrid")).size();
        var rows = children(table).stream().filter(element -> W.equals(element.getNamespaceURI())
                && element.getLocalName().equals("tr")).toList();
        if (columns == 0 && !rows.isEmpty()) {
            for (var cell : children(rows.getFirst())) {
                if (!W.equals(cell.getNamespaceURI()) || !cell.getLocalName().equals("tc")) { continue; }
                int span = DocxAdapter.readColumnSpan(cell);
                if (span > Table.MAX_INFERRED_COLUMNS - columns) { throw new IOException("DOCX inferred column count exceeds limit"); }
                columns += span;
            }
        }
        budget.slots(rows.size(), columns);
        for (var row : children(table)) {
            if (!W.equals(row.getNamespaceURI()) || !row.getLocalName().equals("tr")) { continue; }
            for (var cell : children(row)) {
                if (!W.equals(cell.getNamespaceURI()) || !cell.getLocalName().equals("tc")) { continue; }
                ExecutionContext.checkpoint();
                var content = content(cell);
                if (continuation(cell)) { continue; }
                if (!blockCell(cell, content)) { continue; }
                for (var element : content) {
                    if (element.getLocalName().equals("tbl")) { indexTable(element, depth + 1, elements, budget); }
                    else { budget.node(); elements.add(element); }
                }
            }
        }
    }

    /** Metadata-free multiple paragraphs are real ordered blocks, including every empty paragraph. */
    static boolean blockCell(Element cell, List<Element> content) throws IOException {
        if (cell.hasAttributeNS(OD, "content") && !cell.getAttributeNS(OD, "content").equals("blocks")) {
            throw new IOException("invalid DOCX cell content branch");
        }
        return cell.hasAttributeNS(OD, "content") || content.size() > 1
                || content.stream().anyMatch(element -> element.hasAttributeNS(OD, "id"))
                || content.stream().anyMatch(element -> element.getLocalName().equals("tbl"));
    }

    static boolean continuation(Element cell) {
        for (var properties : children(cell)) {
            if (!W.equals(properties.getNamespaceURI()) || !properties.getLocalName().equals("tcPr")) { continue; }
            for (var merge : children(properties)) {
                if (W.equals(merge.getNamespaceURI()) && merge.getLocalName().equals("vMerge")) {
                    return !merge.hasAttributeNS(W, "val") || merge.getAttributeNS(W, "val").trim().equals("continue");
                }
            }
        }
        return false;
    }

    static List<Element> content(Element cell) throws IOException {
        List<Element> result = new ArrayList<>();
        var direct = children(cell);
        for (int i = 0; i < direct.size(); i++) {
            var element = direct.get(i);
            if (DocxTables.isSeparator(element)) { continue; }
            if (element.hasAttributeNS(OD, "cellTail")) {
                if (!W.equals(element.getNamespaceURI()) || !element.getLocalName().equals("p")
                        || !element.getAttributeNS(OD, "cellTail").equals("true") || i != direct.size() - 1
                        || result.isEmpty() || !result.getLast().getLocalName().equals("tbl")
                        || !children(element).isEmpty() || !whitespace(element.getTextContent())) {
                    throw new IOException("invalid DOCX structural cell tail paragraph");
                }
                var attributes = element.getAttributes();
                for (int a = 0; a < attributes.getLength(); a++) {
                    var attribute = attributes.item(a);
                    if (!XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI())
                            && !(OD.equals(attribute.getNamespaceURI()) && attribute.getLocalName().equals("cellTail"))) {
                        throw new IOException("DOCX structural cell tail cannot carry semantic attributes");
                    }
                }
                continue;
            }
            if (W.equals(element.getNamespaceURI()) && Set.of("p", "tbl").contains(element.getLocalName())) { result.add(element); }
        }
        return result;
    }

    static void validateTailLocation(Element element) throws IOException {
        if (element.hasAttributeNS(OD, "content") && (!W.equals(element.getNamespaceURI()) || !element.getLocalName().equals("tc"))) {
            throw new IOException("DOCX cell content branch marker requires a cell");
        }
        if (element.hasAttributeNS(OD, "cellTail") && (!(element.getParentNode() instanceof Element parent)
                || !W.equals(parent.getNamespaceURI()) || !parent.getLocalName().equals("tc"))) {
            throw new IOException("DOCX structural cell tail must be a direct cell paragraph");
        }
    }

    private static boolean whitespace(String text) {
        return text.chars().allMatch(value -> value == ' ' || value == '\t' || value == '\n' || value == '\r');
    }
}
