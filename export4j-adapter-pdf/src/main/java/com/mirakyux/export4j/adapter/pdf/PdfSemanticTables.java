// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableRow;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static com.mirakyux.export4j.adapter.pdf.PdfSemanticImporter.Glyph;
import static com.mirakyux.export4j.adapter.pdf.PdfSemanticImporter.Item;

/** Rule connectivity and repeated alignment supply evidence, never authoritative PDF semantics. */
final class PdfSemanticTables {
    record Rule(double x1, double y1, double x2, double y2) {
        boolean horizontal() { return Math.abs(y1 - y2) < .5 && Math.abs(x1 - x2) >= 8; }
        boolean vertical() { return Math.abs(x1 - x2) < .5 && Math.abs(y1 - y2) >= 8; }
        double left() { return Math.min(x1, x2); } double right() { return Math.max(x1, x2); }
        double top() { return Math.min(y1, y2); } double bottom() { return Math.max(y1, y2); }
    }
    private record Grid(List<Double> x, List<Double> y, List<Rule> rules, boolean aligned, List<Cell> inferredCells) {
        Grid(List<Double> x, List<Double> y, List<Rule> rules, boolean aligned) { this(x, y, rules, aligned, List.of()); }
        double left() { return x.getFirst(); } double right() { return x.getLast(); }
        double top() { return y.getFirst(); } double bottom() { return y.getLast(); }
        double area() { return (right() - left()) * (bottom() - top()); }
        boolean contains(double px, double py) { return px >= left() && px <= right() && py >= top() && py <= bottom(); }
        boolean contains(Grid other) { return other.left() > left() + .5 && other.right() < right() - .5 && other.top() > top() + .5 && other.bottom() < bottom() - .5; }
    }
    private record Cell(int row, int column, int rows, int columns) { }
    private final PdfSemanticAnalysis.Context context;
    private final PdfLimits limits;
    private final int page;
    PdfSemanticTables(PdfSemanticAnalysis.Context context, PdfLimits limits, int page) { this.context = context; this.limits = limits; this.page = page; }

    void recover(List<Glyph> glyphs, List<Item> items, List<Rule> sourceRules) throws IOException {
        var rules = sourceRules.stream().filter(value -> value.horizontal() || value.vertical()).toList();
        var candidates = ruled(rules);
        candidates.removeIf(grid -> items.stream().anyMatch(item -> item.block() instanceof Table && intersects(grid, item)));
        // A ruled region owns its aligned text. Never infer a second wireless table from that text.
        var remaining = glyphs.stream().filter(glyph -> candidates.stream().noneMatch(grid -> grid.contains(glyph.x() + glyph.width() / 2, glyph.baseline() - glyph.height() / 2))).toList();
        candidates.addAll(aligned(remaining));
        candidates.sort(Comparator.comparingDouble(Grid::area).reversed());
        var accepted = new ArrayList<Grid>(); var uncertain = new ArrayList<Grid>();
        for (var candidate : candidates) {
            context.work();
            if (accepted.stream().anyMatch(other -> !other.contains(candidate) && !candidate.contains(other) && overlap(other, candidate))) {
                context.inferred("TABLE_OVERLAP_UNCERTAIN_TEXT_RETAINED", page); continue;
            }
            if (cells(candidate) == null || !fits(candidate, glyphs, items)) { uncertain.add(candidate); context.inferred("TABLE_CANDIDATE_UNCERTAIN_TEXT_RETAINED", page); continue; }
            accepted.add(candidate);
        }
        var used = Collections.newSetFromMap(new IdentityHashMap<Glyph, Boolean>());
        var usedImages = Collections.newSetFromMap(new IdentityHashMap<Item, Boolean>());
        var tables = new ArrayList<Item>();
        for (var grid : accepted) {
            if (accepted.stream().anyMatch(other -> other != grid && other.contains(grid))) { continue; }
            var table = build(grid, accepted, glyphs, items, used, usedImages, 1);
            tables.add(new Item(table, grid.left(), grid.top(), grid.right() - grid.left(), grid.bottom() - grid.top()));
        }
        // A rejected ruled grid is evidence of row-oriented text, not evidence of prose
        // columns. Keep its physical lines intact without asserting cell ownership.
        for (var grid : uncertain) {
            var selected = glyphs.stream().filter(glyph -> !used.contains(glyph) && grid.contains(glyph.x() + glyph.width() / 2, glyph.baseline() - glyph.height() / 2)).toList();
            for (var lineGlyphs : PdfSemanticImporter.lines(selected)) {
                context.work(); var line = PdfSemanticAnalysis.line(lineGlyphs); String id = context.id();
                tables.add(new Item(new Paragraph(id, PdfSemanticImporter.runs(lineGlyphs), ParagraphFormat.of(line.size())), line.x(), line.y(), line.right() - line.x(), line.bottom() - line.y()));
                context.sources().add(new PreparedPdfImport.Source(id, page, line.x(), line.y(), line.right() - line.x(), line.bottom() - line.y())); used.addAll(lineGlyphs);
            }
        }
        glyphs.removeIf(used::contains); items.removeIf(usedImages::contains); items.addAll(tables);
    }
    private List<Grid> ruled(List<Rule> rules) throws IOException {
        int[] parent = new int[rules.size()]; for (int i = 0; i < parent.length; i++) { parent[i] = i; }
        for (int i = 0; i < rules.size(); i++) {
            for (int j = i + 1; j < rules.size(); j++) {
                context.work(); var a = rules.get(i); var b = rules.get(j);
                boolean touches = a.left() <= b.right() + 2 && b.left() <= a.right() + 2 && a.top() <= b.bottom() + 2 && b.top() <= a.bottom() + 2;
                // Full-width parallel rules can be a partially ruled table even without its sides.
                boolean parallel = a.horizontal() && b.horizontal() && Math.abs(a.left() - b.left()) < 1 && Math.abs(a.right() - b.right()) < 1 && Math.abs(a.y1() - b.y1()) < 60;
                if (touches || parallel) { union(parent, i, j); }
            }
        }
        var groups = new HashMap<Integer, List<Rule>>();
        for (int i = 0; i < parent.length; i++) { groups.computeIfAbsent(find(parent, i), ignored -> new ArrayList<>()).add(rules.get(i)); }
        var result = new ArrayList<Grid>();
        for (var group : groups.values()) {
            context.work(); var horizontal = group.stream().filter(Rule::horizontal).toList(); var vertical = group.stream().filter(Rule::vertical).toList();
            if (horizontal.size() < 2 || vertical.size() < 2) { continue; }
            var x = new ArrayList<Double>(); var y = new ArrayList<Double>();
            // A small break in a rule must not manufacture a new row or column.
            // Perpendicular rules establish internal axes; segment endpoints only
            // establish the outside extent, including a partially missing border.
            for (var value : vertical) { x.add(value.x1()); }
            for (var value : horizontal) { y.add(value.y1()); }
            x.add(group.stream().mapToDouble(Rule::left).min().orElseThrow()); x.add(group.stream().mapToDouble(Rule::right).max().orElseThrow());
            y.add(group.stream().mapToDouble(Rule::top).min().orElseThrow()); y.add(group.stream().mapToDouble(Rule::bottom).max().orElseThrow());
            var xs = edges(x); var ys = edges(y);
            if ((long) (xs.size() - 1) * (ys.size() - 1) > limits.maxObjects()) { throw new PdfOperationException(PdfOperationException.Code.OBJECT_LIMIT, 0, page); }
            if (xs.size() < 2 || ys.size() < 2 || xs.size() == 2 && ys.size() == 2) { continue; }
            result.add(new Grid(xs, ys, List.copyOf(group), false));
        }
        return result;
    }
    private List<Grid> aligned(List<Glyph> glyphs) throws IOException {
        var fragments = PdfSemanticAnalysis.fragments(glyphs, context);
        var rows = new ArrayList<List<PdfSemanticAnalysis.Line>>();
        for (var value : fragments) {
            if (rows.isEmpty() || Math.abs(rows.getLast().getFirst().bottom() - value.bottom()) > .5) { rows.add(new ArrayList<>()); }
            rows.getLast().add(value);
        }
        var result = new ArrayList<Grid>();
        for (int i = 0; i < rows.size();) {
            var first = rows.get(i); if (first.size() < 2 || first.size() > 8) { i++; continue; }
            var selected = new ArrayList<List<PdfSemanticAnalysis.Line>>(); selected.add(first); int next = i + 1;
            while (next < rows.size()) {
                context.work(); var row = rows.get(next); var previous = rows.get(next - 1);
                if (row.size() != first.size() || row.getFirst().bottom() - previous.getFirst().bottom() > first.getFirst().size() * 2.5) { break; }
                boolean agrees = true;
                for (int column = 0; column < row.size(); column++) { if (Math.abs(row.get(column).x() - first.get(column).x()) > 2) { agrees = false; } }
                if (!agrees) { break; } selected.add(row); next++;
            }
            if (selected.size() >= 3) {
                int numeric = 0; int compact = 0; int total = 0;
                for (var row : selected) { for (var value : row) { context.work(); String text = value.text().strip(); total++; if (text.matches("[-+]?\\d[\\d.,% /:-]*")) { numeric++; } if (text.length() <= 18 && text.split("\\s+").length <= 3) { compact++; } } }
                // Equal-column prose is not enough evidence. Numeric cells or a compact repeated
                // matrix distinguish accepted candidates; ambiguous alignment remains prose.
                boolean evidence = numeric >= selected.size() - 1 || compact == total;
                if (evidence) {
                    var x = new ArrayList<Double>(); x.add(first.getFirst().x() - 3);
                    for (int column = 1; column < first.size(); column++) {
                        int previousColumn = column - 1; double right = selected.stream().mapToDouble(row -> row.get(previousColumn).right()).max().orElse(0);
                        if (right + 8 >= first.get(column).x()) { evidence = false; break; }
                        x.add((right + first.get(column).x()) / 2);
                    }
                    x.add(selected.stream().mapToDouble(row -> row.getLast().right()).max().orElse(0) + 3);
                    if (evidence) {
                        var y = new ArrayList<Double>(); y.add(selected.getFirst().stream().mapToDouble(PdfSemanticAnalysis.Line::y).min().orElse(0) - 3);
                        for (int row = 1; row < selected.size(); row++) { y.add((selected.get(row - 1).getFirst().bottom() + selected.get(row).stream().mapToDouble(PdfSemanticAnalysis.Line::y).min().orElse(0)) / 2); }
                        y.add(selected.getLast().getFirst().bottom() + 3);
                        var grid = new Grid(List.copyOf(x), List.copyOf(y), List.of(), true);
                        result.add(i > 0 ? spanningHeader(grid, rows.get(i - 1), selected.getFirst()) : grid);
                    }
                }
                if (!evidence) { context.inferred("ALIGNED_TEXT_UNCERTAIN_TABLE_NOT_ASSERTED_CONTENT_RETAINED", page); }
            }
            i = Math.max(i + 1, next);
        }
        return result;
    }
    private Grid spanningHeader(Grid grid, List<PdfSemanticAnalysis.Line> header, List<PdfSemanticAnalysis.Line> firstBody) throws IOException {
        int columns = grid.x().size() - 1;
        if (header.isEmpty() || header.size() >= columns || firstBody.getFirst().bottom() - header.getFirst().bottom() > firstBody.getFirst().size() * 2) { return grid; }
        var x = new ArrayList<>(grid.x()); x.set(columns, Math.max(x.getLast(), header.getLast().right() + 3));
        var anchors = new ArrayList<Cell>(); int cursor = 0; boolean span = false;
        for (int index = 0; index < header.size(); index++) {
            context.work(); var value = header.get(index);
            if (cursor >= columns) { return grid; }
            if (Math.abs(value.x() - firstBody.get(cursor).x()) > 2) { return grid; }
            int end = index + 1 == header.size() ? columns : cursor + 1;
            if (index + 1 < header.size()) {
                while (end < columns && Math.abs(header.get(index + 1).x() - firstBody.get(end).x()) > 2) { end++; }
            }
            if (end <= cursor || end > columns || value.right() > x.get(end) + .5) { return grid; }
            // Spanning is asserted only when visible text crosses a body-column boundary.
            // An absent value alone cannot distinguish a merged cell from an empty cell.
            if (end - cursor > 1 && value.right() <= grid.x().get(cursor + 1)) { return grid; }
            anchors.add(new Cell(0, cursor, 1, end - cursor)); span |= end - cursor > 1; cursor = end;
        }
        if (!span || cursor != columns) { return grid; }
        var y = new ArrayList<Double>(); y.add(header.stream().mapToDouble(PdfSemanticAnalysis.Line::y).min().orElseThrow() - 3);
        y.add((header.getFirst().bottom() + firstBody.stream().mapToDouble(PdfSemanticAnalysis.Line::y).min().orElseThrow()) / 2);
        y.addAll(grid.y().subList(1, grid.y().size()));
        for (int row = 1; row < y.size() - 1; row++) { for (int column = 0; column < columns; column++) { anchors.add(new Cell(row, column, 1, 1)); } }
        context.inferred("WIRELESS_SPANNING_HEADER_INFERRED_FROM_TEXT_CROSSING_ESTABLISHED_COLUMN_BOUNDARY", page);
        return new Grid(List.copyOf(x), List.copyOf(y), grid.rules(), true, List.copyOf(anchors));
    }
    private List<Cell> cells(Grid grid) throws IOException {
        if (!grid.inferredCells().isEmpty()) { return grid.inferredCells(); }
        int columns = grid.x().size() - 1; int rows = grid.y().size() - 1; int[] owners = new int[columns * rows];
        for (int i = 0; i < owners.length; i++) { owners[i] = i; }
        if (!grid.aligned()) {
            for (int row = 0; row < rows; row++) {
                for (int column = 0; column < columns; column++) {
                    context.work();
                    if (column + 1 < columns && coverage(grid.rules(), false, grid.x().get(column + 1), grid.y().get(row), grid.y().get(row + 1)) < .7) { union(owners, row * columns + column, row * columns + column + 1); }
                    if (row + 1 < rows && coverage(grid.rules(), true, grid.y().get(row + 1), grid.x().get(column), grid.x().get(column + 1)) < .7) { union(owners, row * columns + column, (row + 1) * columns + column); }
                }
            }
        }
        var groups = new HashMap<Integer, List<Integer>>();
        for (int i = 0; i < owners.length; i++) { groups.computeIfAbsent(find(owners, i), ignored -> new ArrayList<>()).add(i); }
        var result = new ArrayList<Cell>();
        for (var slots : groups.values()) {
            int top = rows; int left = columns; int bottom = 0; int right = 0;
            for (int slot : slots) { top = Math.min(top, slot / columns); bottom = Math.max(bottom, slot / columns); left = Math.min(left, slot % columns); right = Math.max(right, slot % columns); }
            if ((bottom - top + 1) * (right - left + 1) != slots.size()) { return null; }
            result.add(new Cell(top, left, bottom - top + 1, right - left + 1));
        }
        result.sort(Comparator.comparingInt(Cell::row).thenComparingInt(Cell::column)); return result;
    }
    private double coverage(List<Rule> rules, boolean horizontal, double position, double start, double end) throws IOException {
        var intervals = new ArrayList<double[]>();
        for (var rule : rules) {
            context.work();
            if (horizontal ? rule.horizontal() && Math.abs(rule.y1() - position) <= 1 : rule.vertical() && Math.abs(rule.x1() - position) <= 1) {
                double a = Math.max(start, horizontal ? rule.left() : rule.top()); double b = Math.min(end, horizontal ? rule.right() : rule.bottom()); if (b > a) { intervals.add(new double[]{a, b}); }
            }
        }
        intervals.sort(Comparator.comparingDouble(value -> value[0])); double covered = 0; double last = start;
        for (var value : intervals) { if (value[1] > last) { covered += value[1] - Math.max(last, value[0]); last = value[1]; } }
        return covered / (end - start);
    }
    private boolean fits(Grid grid, List<Glyph> glyphs, List<Item> images) throws IOException {
        int selected = 0; var owners = cells(grid); if (owners == null) { return false; }
        for (var glyph : glyphs) {
            context.work(); if (!grid.contains(glyph.x() + glyph.width() / 2, glyph.baseline() - glyph.height() / 2)) { continue; }
            selected++; if (glyph.x() < grid.left() - .5 || glyph.x() + glyph.width() > grid.right() + .5 || glyph.baseline() - glyph.height() < grid.top() - .5 || glyph.baseline() > grid.bottom() + .5) { return false; }
            double cx = glyph.x() + glyph.width() / 2; double cy = glyph.baseline() - glyph.height() / 2;
            boolean owned = false;
            for (var cell : owners) {
                context.work(); double left = grid.x().get(cell.column()); double right = grid.x().get(cell.column() + cell.columns()); double top = grid.y().get(cell.row()); double bottom = grid.y().get(cell.row() + cell.rows());
                if (cx >= left && cx < right && cy >= top && cy < bottom) { owned = glyph.x() >= left - .5 && glyph.x() + glyph.width() <= right + .5 && glyph.baseline() - glyph.height() >= top - .5 && glyph.baseline() <= bottom + .5; break; }
            }
            if (!owned) { return false; }
        }
        for (var image : images) { context.work(); if (intersects(grid, image) && (image.x() < grid.left() || image.x() + image.width() > grid.right() || image.y() < grid.top() || image.y() + image.height() > grid.bottom())) { return false; } }
        return selected >= 2;
    }
    private Table build(Grid grid, List<Grid> grids, List<Glyph> glyphs, List<Item> images, Set<Glyph> used, Set<Item> usedImages, int depth) throws IOException {
        if (depth > Math.min(16, limits.maxDepth())) { throw new PdfOperationException(PdfOperationException.Code.DEPTH_LIMIT, 0, page); }
        String id = context.id(); var cells = cells(grid); if (cells == null) { throw PdfObjects.invalid(); }
        var children = grids.stream().filter(child -> child != grid && grid.contains(child)
                && grids.stream().noneMatch(middle -> middle != grid && middle != child && grid.contains(middle) && middle.contains(child))).toList();
        var rows = new ArrayList<TableRow>();
        for (int row = 0; row < grid.y().size() - 1; row++) {
            var anchors = new ArrayList<TableCell>();
            for (var cell : cells) {
                if (cell.row() != row) { continue; } context.work();
                double left = grid.x().get(cell.column()); double right = grid.x().get(cell.column() + cell.columns());
                double top = grid.y().get(row); double bottom = grid.y().get(row + cell.rows());
                var localChildren = children.stream().filter(child -> child.left() >= left && child.right() <= right && child.top() >= top && child.bottom() <= bottom).toList();
                var content = new ArrayList<Item>(); var selected = new ArrayList<Glyph>();
                for (var glyph : glyphs) {
                    context.work(); double x = glyph.x() + glyph.width() / 2; double y = glyph.baseline() - glyph.height() / 2;
                    if (x >= left && x < right && y >= top && y < bottom && localChildren.stream().noneMatch(child -> child.contains(x, y))) {
                        if (glyph.x() < left - .5 || glyph.x() + glyph.width() > right + .5 || glyph.baseline() - glyph.height() < top - .5 || glyph.baseline() > bottom + .5) {
                            // A table boundary through a glyph has no safe cell ownership.
                            throw new PdfOperationException(PdfOperationException.Code.UNSUPPORTED_FEATURE, 0, page);
                        }
                        selected.add(glyph); used.add(glyph);
                    }
                }
                var lines = PdfSemanticAnalysis.fragments(selected, context);
                if (!lines.isEmpty()) {
                    String paragraphId = context.id(); double size = selected.stream().mapToDouble(value -> value.style().fontSize()).max().orElse(12);
                    var paragraph = new Paragraph(paragraphId, PdfSemanticAnalysis.joined(lines), ParagraphFormat.of(size));
                    content.add(new Item(paragraph, left, lines.getFirst().y(), right - left, lines.getLast().bottom() - lines.getFirst().y()));
                    for (var line : lines) { context.sources().add(new PreparedPdfImport.Source(paragraphId, page, line.x(), line.y(), line.right() - line.x(), line.bottom() - line.y())); }
                }
                for (var child : localChildren) {
                    var table = build(child, grids, glyphs, images, used, usedImages, depth + 1);
                    content.add(new Item(table, child.left(), child.top(), child.right() - child.left(), child.bottom() - child.top()));
                }
                for (var image : images) {
                    context.work(); if (image.x() >= left && image.x() + image.width() <= right && image.y() >= top && image.y() + image.height() <= bottom
                            && localChildren.stream().noneMatch(child -> intersects(child, image))) { content.add(image); usedImages.add(image); }
                }
                content.sort(Comparator.comparingDouble(Item::y).thenComparingDouble(Item::x));
                if (content.isEmpty()) { content.add(new Item(new Paragraph(context.id(), "", ParagraphFormat.of(12)), left, top, right - left, bottom - top)); }
                anchors.add(TableCell.builder().format(CellFormat.of(content.stream().filter(item -> item.block() instanceof Paragraph).mapToDouble(item -> ((Paragraph) item.block()).format().fontSize()).max().orElse(12)))
                        .columnSpan(cell.columns()).rowSpan(cell.rows()).blocks(content.stream().map(Item::block).toList()).build());
            }
            rows.add(anchors.isEmpty() ? TableRow.covered() : new TableRow(anchors));
        }
        var columns = new ArrayList<ColumnWidth>(); for (int i = 1; i < grid.x().size(); i++) { columns.add(new ColumnWidth.Fraction(grid.x().get(i) - grid.x().get(i - 1))); }
        var table = new Table(id, columns, rows, 0, com.mirakyux.export4j.model.TableFormat.DEFAULT);
        context.sources().add(new PreparedPdfImport.Source(id, page, grid.left(), grid.top(), grid.right() - grid.left(), grid.bottom() - grid.top()));
        context.inferred(grid.aligned() ? "WIRELESS_TABLE_INFERRED_FROM_REPEATED_ALIGNMENT_AND_COMPACT_CELLS" : "RULED_TABLE_GRID_INFERRED_FROM_CONNECTED_SEGMENTS", page);
        if (!grid.aligned() && cells.stream().anyMatch(cell -> cell.rows() > 1 || cell.columns() > 1)) { context.inferred("TABLE_MERGED_CELLS_INFERRED_FROM_MISSING_INTERNAL_RULES", page); }
        if (!children.isEmpty()) { context.inferred("NESTED_TABLE_INFERRED_FROM_CONTAINED_INDEPENDENT_GRID", page); }
        return table;
    }
    private static List<Double> edges(List<Double> values) {
        var sorted = values.stream().sorted().toList(); var result = new ArrayList<Double>();
        for (double value : sorted) { if (result.isEmpty() || value - result.getLast() > .75) { result.add(value); } }
        return List.copyOf(result);
    }
    private static int find(int[] parent, int value) { while (parent[value] != value) { parent[value] = parent[parent[value]]; value = parent[value]; } return value; }
    private static void union(int[] parent, int a, int b) { parent[find(parent, a)] = find(parent, b); }
    private static boolean overlap(Grid a, Grid b) { return a.left() < b.right() && b.left() < a.right() && a.top() < b.bottom() && b.top() < a.bottom(); }
    private static boolean intersects(Grid grid, Item item) { return grid.left() < item.x() + item.width() && item.x() < grid.right() && grid.top() < item.y() + item.height() && item.y() < grid.bottom(); }
}
