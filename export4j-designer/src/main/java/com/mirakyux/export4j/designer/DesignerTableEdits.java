// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.designer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Source-preserving rectangular table edits. Physical coordinates include existing spans. */
final class DesignerTableEdits {
    private record Anchor(Element cell, Element row, int y, int x, int height, int width) { }
    private record Grid(List<Element> rows, List<Anchor> anchors, Anchor[][] slots) { }
    private DesignerTableEdits() { }

    private static Grid grid(Element table) {
        if (!List.of("table", "rows").contains(table.getTagName())) { throw new IllegalArgumentException("请选择表格。"); }
        var columns = DesignerXml.children(table).stream().filter(node -> node.getTagName().equals("columns")).findFirst().orElseThrow();
        int width = DesignerXml.children(columns).size();
        var rows = DesignerXml.children(table).stream().filter(node -> List.of("header", "row").contains(node.getTagName())).toList();
        if (width < 1 || rows.isEmpty() || (long) width * rows.size() > 100_000) { throw new IllegalArgumentException("表格编辑网格超过预算。"); }
        var slots = new Anchor[rows.size()][width]; var anchors = new ArrayList<Anchor>();
        for (int y = 0; y < rows.size(); y++) {
            int x = 0;
            for (var cell : DesignerXml.children(rows.get(y))) {
                while (x < width && slots[y][x] != null) { x++; }
                int w = span(cell, "column-span"), h = span(cell, "row-span");
                if (x + w > width || y + h > rows.size()) { throw new IllegalArgumentException("单元格跨度超出网格。"); }
                var anchor = new Anchor(cell, rows.get(y), y, x, h, w);
                for (int dy = y; dy < y + h; dy++) {
                    if (!rows.get(dy).getTagName().equals(rows.get(y).getTagName())) { throw new IllegalArgumentException("合并不能跨越表头和正文。"); }
                    for (int dx = x; dx < x + w; dx++) {
                        if (slots[dy][dx] != null) { throw new IllegalArgumentException("单元格跨度重叠。"); }
                        slots[dy][dx] = anchor;
                    }
                }
                anchors.add(anchor); x += w;
            }
            for (var slot : slots[y]) { if (slot == null) { throw new IllegalArgumentException("表格网格不完整。"); } }
        }
        return new Grid(rows, anchors, slots);
    }
    private static int span(Element cell, String name) {
        if (!cell.hasAttribute(name)) { return 1; }
        int value = Integer.parseInt(cell.getAttribute(name));
        if (value < 1 || value > 100_000) { throw new IllegalArgumentException("单元格跨度必须为正整数。"); }
        return value;
    }
    static String merge(String source, String tablePath, List<String> paths) throws Exception {
        var document = DesignerXml.parse(source); var table = DesignerXml.at(document, tablePath); var grid = grid(table);
        var selected = new HashSet<Element>();
        for (var path : paths) { selected.add(DesignerXml.at(document, path)); }
        var anchors = grid.anchors().stream().filter(anchor -> selected.contains(anchor.cell())).toList();
        if (anchors.size() != selected.size() || anchors.size() < 2) { throw new IllegalArgumentException("请选择同一表格中至少两个单元格。"); }
        int top = anchors.stream().mapToInt(Anchor::y).min().orElseThrow(), left = anchors.stream().mapToInt(Anchor::x).min().orElseThrow();
        int bottom = anchors.stream().mapToInt(anchor -> anchor.y() + anchor.height()).max().orElseThrow();
        int right = anchors.stream().mapToInt(anchor -> anchor.x() + anchor.width()).max().orElseThrow();
        for (int y = top; y < bottom; y++) {
            if (!grid.rows().get(y).getTagName().equals(grid.rows().get(top).getTagName())) { throw new IllegalArgumentException("合并不能跨越表头和正文。"); }
            for (int x = left; x < right; x++) {
                if (!selected.contains(grid.slots()[y][x].cell())) { throw new IllegalArgumentException("合并选区必须为完整矩形，不能遗漏单元格。"); }
            }
        }
        if (anchors.stream().anyMatch(anchor -> !rules(anchor.cell()).isEmpty())) {
            throw new IllegalArgumentException("请先移除所选单元格的条件样式，再合并；不会静默丢失规则。");
        }
        var owner = grid.slots()[top][left].cell();
        boolean blocks = anchors.stream().anyMatch(anchor -> DesignerXml.children(anchor.cell()).stream()
                .anyMatch(child -> List.of("p", "table").contains(child.getTagName())));
        if (blocks && grid.rows().get(top).getTagName().equals("header")) { throw new IllegalArgumentException("表头不支持多段块内容。"); }
        var combined = document.createDocumentFragment();
        for (var anchor : anchors) {
            var cell = anchor.cell();
            if (blocks) {
                if (DesignerXml.children(cell).stream().anyMatch(child -> List.of("p", "table").contains(child.getTagName()))) {
                    for (var child : DesignerXml.children(cell)) { combined.appendChild(child.cloneNode(true)); }
                } else {
                    var paragraph = document.createElement("p");
                    for (String name : List.of("size", "line-height", "align", "role", "color", "line-break")) {
                        if (cell.hasAttribute(name)) { paragraph.setAttribute(name, cell.getAttribute(name)); }
                    }
                    for (var child = cell.getFirstChild(); child != null; child = child.getNextSibling()) { paragraph.appendChild(child.cloneNode(true)); }
                    combined.appendChild(paragraph);
                }
            } else {
                if (combined.hasChildNodes()) { combined.appendChild(document.createTextNode("\n")); }
                for (var child = cell.getFirstChild(); child != null; child = child.getNextSibling()) { combined.appendChild(styledCopy(cell, child)); }
            }
        }
        while (owner.hasChildNodes()) { owner.removeChild(owner.getFirstChild()); }
        owner.appendChild(combined); owner.setAttribute("column-span", Integer.toString(right - left)); owner.setAttribute("row-span", Integer.toString(bottom - top));
        for (var anchor : anchors) { if (anchor.cell() != owner) { anchor.row().removeChild(anchor.cell()); } }
        grid(table);
        return DesignerXml.serialize(document);
    }
    private static Node styledCopy(Element source, Node child) {
        var copy = child.cloneNode(true);
        if ((copy.getNodeType() == Node.TEXT_NODE || copy.getNodeType() == Node.CDATA_SECTION_NODE) && !copy.getNodeValue().isEmpty()) {
            var span = source.getOwnerDocument().createElement("span"); span.appendChild(copy); copy = span;
        }
        if (copy instanceof Element element) {
            if (element.getTagName().equals("link")) {
                while (element.hasChildNodes()) { element.removeChild(element.getFirstChild()); }
                for (var nested = child.getFirstChild(); nested != null; nested = nested.getNextSibling()) { element.appendChild(styledCopy(source, nested)); }
            } else if (!element.getTagName().equals("image")) {
                for (String name : List.of("size", "role", "color")) {
                    if (!element.hasAttribute(name) && source.hasAttribute(name)) { element.setAttribute(name, source.getAttribute(name)); }
                }
            }
        }
        return copy;
    }
    private static List<Element> rules(Element cell) {
        return DesignerXml.children(cell).stream().filter(child -> child.getTagName().equals("style-if")).toList();
    }
    static String split(String source, String tablePath, String cellPath) throws Exception {
        var document = DesignerXml.parse(source); var table = DesignerXml.at(document, tablePath); var grid = grid(table);
        var cell = DesignerXml.at(document, cellPath);
        var anchor = grid.anchors().stream().filter(item -> item.cell() == cell).findFirst().orElseThrow();
        if (anchor.width() == 1 && anchor.height() == 1) { throw new IllegalArgumentException("当前单元格尚未合并。"); }
        cell.removeAttribute("column-span"); cell.removeAttribute("row-span");
        for (int y = anchor.y(); y < anchor.y() + anchor.height(); y++) {
            for (int x = anchor.x(); x < anchor.x() + anchor.width(); x++) {
                if (y == anchor.y() && x == anchor.x()) { continue; }
                int rowIndex = y, columnIndex = x;
                var next = grid.anchors().stream().filter(item -> item.y() == rowIndex && item.x() > columnIndex).findFirst();
                var empty = document.createElement("cell");
                for (String name : List.of("size", "line-height", "align", "role", "color", "line-break")) {
                    if (cell.hasAttribute(name)) { empty.setAttribute(name, cell.getAttribute(name)); }
                }
                grid.rows().get(y).insertBefore(empty, next.map(Anchor::cell).orElse(null));
            }
        }
        grid(table);
        return DesignerXml.serialize(document);
    }
}
