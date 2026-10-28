// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.BulletItem;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.PageRegion;
import com.mirakyux.export4j.model.PageRegions;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.TableCell;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import static com.mirakyux.export4j.adapter.pdf.PdfSemanticImporter.Glyph;
import static com.mirakyux.export4j.adapter.pdf.PdfSemanticImporter.Item;

/** Bounded geometric inference. No inferred semantic relationship is treated as source authority. */
final class PdfSemanticAnalysis {
    interface Context {
        String id() throws IOException;
        void work() throws IOException;
        void inferred(String effect, int page);
        List<PreparedPdfImport.Source> sources();
    }
    record Line(List<Glyph> glyphs, double x, double y, double right, double bottom, double size) {
        String text() throws IOException { return PdfSemanticImporter.runs(glyphs).stream().map(InlineText::text).collect(java.util.stream.Collectors.joining()); }
    }
    private static final Pattern MARKER = Pattern.compile("^(?:([-*•◦▪])\\s+|(\\d{1,9})[.)]\\s+)");
    private final Context context;
    private final int page;
    PdfSemanticAnalysis(Context context, int page) { this.context = context; this.page = page; }

    static List<Line> fragments(List<Glyph> glyphs, Context context) throws IOException {
        var result = new ArrayList<Line>();
        for (var row : PdfSemanticImporter.lines(glyphs)) {
            var part = new ArrayList<Glyph>(); Glyph previous = null;
            for (var glyph : row) {
                context.work();
                if (previous != null && glyph.x() - previous.x() - previous.width() > Math.max(12, glyph.style().fontSize() * 1.6)) {
                    if (!part.isEmpty()) { result.add(line(part)); } part = new ArrayList<>();
                }
                part.add(glyph); previous = glyph;
            }
            if (!part.isEmpty()) { result.add(line(part)); }
        }
        return result;
    }
    static Line line(List<Glyph> glyphs) {
        return new Line(List.copyOf(glyphs), glyphs.stream().mapToDouble(Glyph::x).min().orElse(0),
                glyphs.stream().mapToDouble(value -> value.baseline() - value.height()).min().orElse(0),
                glyphs.stream().mapToDouble(value -> value.x() + value.width()).max().orElse(0),
                glyphs.stream().mapToDouble(Glyph::baseline).max().orElse(0),
                glyphs.stream().mapToDouble(value -> value.style().fontSize()).max().orElse(12));
    }
    List<Item> recover(List<Glyph> glyphs, double pageWidth) throws IOException {
        var lines = fragments(glyphs, context);
        double body = glyphs.stream().mapToDouble(value -> value.style().fontSize()).sorted().skip(glyphs.size() / 2).findFirst().orElse(12);
        var ordered = arrangeLines(lines, body, 0); var result = new ArrayList<Item>();
        String listId = ""; double listX = 0;
        for (int index = 0; index < ordered.size();) {
            context.work(); var first = ordered.get(index++); var selected = new ArrayList<Line>(); selected.add(first);
            var marker = MARKER.matcher(first.text()); boolean marked = marker.find(); boolean heading = first.size() >= body * 1.3;
            while (index < ordered.size() && !heading) {
                var next = ordered.get(index); context.work();
                double gap = next.bottom() - selected.getLast().bottom();
                boolean sameColumn = Math.abs(next.x() - first.x()) <= Math.max(2, first.size() * .2)
                        || marked && next.x() > first.x() && next.x() <= first.x() + first.size() * 4;
                if (!sameColumn || gap < first.size() * .8 || gap > first.size() * 1.7
                        || Math.abs(next.size() - first.size()) > first.size() * .15 || MARKER.matcher(next.text()).find()
                        || !marked && selected.getLast().text().stripTrailing().matches(".*[.!?:。！？：]$")) { break; }
                selected.add(next); index++;
            }
            var runs = joined(selected); String id = context.id(); var format = ParagraphFormat.of(first.size()); Block block;
            if (marked) {
                var bodyRuns = trim(runs, marker.end());
                int level = Math.min(9, 1 + Math.max(0, (int) Math.round((first.x() - listX) / Math.max(12, first.size() * 2))));
                if (listId.isEmpty() || Math.abs(first.x() - listX) > first.size() * 16) { listId = "pdf-list-" + id; listX = first.x(); level = 1; }
                if (marker.group(2) != null) {
                    int number = Integer.parseInt(marker.group(2));
                    if (number == 0) { block = new Paragraph(id, runs, format); context.inferred("ZERO_NUMBER_MARKER_RETAINED_AS_TEXT_UNCERTAIN_LIST", page); }
                    else { block = NumberedItem.rich(id, listId, bodyRuns, number, format, level, first.glyphs().getFirst().style()); }
                } else { block = BulletItem.rich(id, bodyRuns, marker.group(1), level, format, first.glyphs().getFirst().style()); }
                context.inferred("LIST_AND_INDENT_INFERRED_DECIMAL_PUNCTUATION_NORMALIZED", page);
            } else if (heading) {
                block = Heading.rich(id, runs, first.size() >= body * 1.6 ? 1 : 2, format); listId = "";
                context.inferred("HEADING_LEVEL_INFERRED_FROM_RELATIVE_FONT_SIZE", page);
            } else { block = new Paragraph(id, runs, format); listId = ""; }
            if (selected.size() > 1) { context.inferred("PARAGRAPH_LINE_BREAKS_JOINED_WITH_SPACES_GEOMETRIC_INFERENCE", page); }
            double x = selected.stream().mapToDouble(Line::x).min().orElse(0); double y = selected.stream().mapToDouble(Line::y).min().orElse(0);
            double right = selected.stream().mapToDouble(Line::right).max().orElse(x); double bottom = selected.stream().mapToDouble(Line::bottom).max().orElse(y);
            result.add(new Item(block, x, y, right - x, bottom - y));
            // Leaf provenance keeps the actual line boxes, not whitespace introduced by inference.
            for (var value : selected) { context.sources().add(new PreparedPdfImport.Source(id, page, value.x(), value.y(), value.right() - value.x(), value.bottom() - value.y())); }
        }
        if (pageWidth <= 0) { throw PdfObjects.invalid(); }
        return result;
    }
    private List<Line> arrangeLines(List<Line> values, double body, int depth) throws IOException {
        if (values.size() < 2) { return values; }
        context.work(); if (depth > 64) { throw new PdfOperationException(PdfOperationException.Code.DEPTH_LIMIT, 0, page); }
        // A large, isolated heading separates column bands even when its text crosses the gutter.
        for (var value : values) {
            context.work();
            if (value.size() < body * 1.3 || values.stream().anyMatch(other -> other != value && other.y() < value.bottom() && other.bottom() > value.y())) { continue; }
            var above = values.stream().filter(other -> other != value && other.bottom() <= value.y()).toList();
            var below = values.stream().filter(other -> other != value && other.y() >= value.bottom()).toList();
            if (above.size() + below.size() + 1 == values.size()) {
                var result = new ArrayList<>(arrangeLines(above, body, depth + 1)); result.add(value); result.addAll(arrangeLines(below, body, depth + 1)); return result;
            }
        }
        var byX = values.stream().sorted(Comparator.comparingDouble(Line::x)).toList();
        double right = byX.getFirst().right(); double split = Double.NaN; double widest = Math.max(24, body * 2);
        for (int i = 1; i < byX.size(); i++) {
            context.work(); var value = byX.get(i);
            if (value.x() - right > widest) { widest = value.x() - right; split = (right + value.x()) / 2; }
            right = Math.max(right, value.right());
        }
        if (Double.isFinite(split)) {
            double edge = split; var left = values.stream().filter(value -> value.right() <= edge).toList(); var next = values.stream().filter(value -> value.x() >= edge).toList();
            if (left.size() >= 2 && next.size() >= 2 && left.stream().anyMatch(a -> next.stream().anyMatch(b -> a.y() < b.bottom() && b.y() < a.bottom()))) {
                context.inferred("COLUMN_READING_ORDER_INFERRED_LEFT_TO_RIGHT_TOP_TO_BOTTOM", page);
                var result = new ArrayList<>(arrangeLines(left, body, depth + 1)); result.addAll(arrangeLines(next, body, depth + 1)); return result;
            }
        }
        return values.stream().sorted(Comparator.comparingDouble(Line::y).thenComparingDouble(Line::x)).toList();
    }
    static List<InlineText> joined(List<Line> lines) throws IOException {
        var runs = new ArrayList<InlineText>();
        for (var value : lines) {
            if (!runs.isEmpty() && !runs.getLast().text().endsWith(" ") && !value.glyphs().getFirst().text().startsWith(" ")) { runs.add(new InlineText(" ", runs.getLast().style())); }
            runs.addAll(PdfSemanticImporter.runs(value.glyphs()));
        }
        return List.copyOf(runs);
    }
    private static List<InlineText> trim(List<InlineText> runs, int count) {
        var result = new ArrayList<InlineText>();
        for (var run : runs) {
            if (count >= run.text().length()) { count -= run.text().length(); }
            else { result.add(new InlineText(run.text().substring(count), run.style(), run.hyperlink())); count = 0; }
        }
        return List.copyOf(result);
    }
    static List<Item> order(List<Item> items, Context context, int page) throws IOException {
        // Paragraph construction already establishes column-local order. Apply the same gutter rule
        // to tables and images without flattening them into a row-major sort.
        if (items.size() < 2) { return items; }
        // Isolated spanning blocks divide the page into independent column bands.
        double min = items.stream().mapToDouble(Item::x).min().orElse(0); double max = items.stream().mapToDouble(item -> item.x() + item.width()).max().orElse(0);
        for (var item : items) {
            context.work();
            if (!(item.block() instanceof Heading) && item.width() < (max - min) * .7) { continue; }
            var before = items.stream().filter(other -> other != item && other.y() + other.height() <= item.y()).toList();
            var after = items.stream().filter(other -> other != item && other.y() >= item.y() + item.height()).toList();
            if (before.size() + after.size() + 1 == items.size()) { var result = new ArrayList<>(order(before, context, page)); result.add(item); result.addAll(order(after, context, page)); return result; }
        }
        var sorted = items.stream().sorted(Comparator.comparingDouble(Item::x)).toList();
        double right = sorted.getFirst().x() + sorted.getFirst().width(); double split = Double.NaN; double gap = 24;
        for (int i = 1; i < sorted.size(); i++) { context.work(); var value = sorted.get(i); if (value.x() - right > gap) { gap = value.x() - right; split = (value.x() + right) / 2; } right = Math.max(right, value.x() + value.width()); }
        if (Double.isFinite(split)) {
            double edge = split; var left = items.stream().filter(item -> item.x() + item.width() <= edge).toList(); var next = items.stream().filter(item -> item.x() >= edge).toList();
            if (!left.isEmpty() && !next.isEmpty()) { var result = new ArrayList<>(order(left, context, page)); result.addAll(order(next, context, page)); return result; }
        }
        return items.stream().sorted(Comparator.comparingDouble(Item::y).thenComparingDouble(Item::x)).toList();
    }
    static List<Section> finish(List<Section> sections, Context context) throws IOException {
        var boxes = new HashMap<String, PreparedPdfImport.Source>();
        for (var source : context.sources()) {
            boxes.merge(source.blockId(), source, (a, b) -> {
                double left = Math.min(a.x(), b.x()); double top = Math.min(a.y(), b.y());
                return new PreparedPdfImport.Source(a.blockId(), a.page(), left, top, Math.max(a.x() + a.width(), b.x() + b.width()) - left, Math.max(a.y() + a.height(), b.y() + b.height()) - top);
            });
        }
        var occurrences = new HashMap<String, Set<Integer>>();
        for (var section : sections) {
            for (var block : section.blocks()) {
                context.work(); var source = boxes.get(block.id()); if (source == null || !(block instanceof Paragraph || block instanceof Heading)) { continue; }
                String key = bandKey(block, source, section.pageSettings().size().height());
                if (!key.isEmpty()) { occurrences.computeIfAbsent(key, ignored -> new HashSet<>()).add(source.page()); }
            }
        }
        var result = new ArrayList<Section>();
        for (var section : sections) {
            var body = new ArrayList<Block>(); var header = new ArrayList<Block>(); var footer = new ArrayList<Block>(); int page = -1;
            for (var block : section.blocks()) {
                context.work(); var source = boxes.get(block.id()); if (source != null) { page = source.page(); }
                String key = source == null ? "" : bandKey(block, source, section.pageSettings().size().height());
                if (!key.isEmpty() && occurrences.getOrDefault(key, Set.of()).size() >= 2) {
                    var paragraph = block instanceof Heading value ? new Paragraph(value.id(), value.inlines(), value.format()) : (Paragraph) block;
                    (key.startsWith("H:") ? header : footer).add(paragraph);
                } else { body.add(block); }
            }
            if (!header.isEmpty() || !footer.isEmpty()) { context.inferred("REPEATING_HEADER_FOOTER_INFERRED_AND_REMOVED_FROM_BODY", page); }
            var bands = new PageRegions(region(header), region(footer));
            result.add(new Section(section.id(), section.pageSettings(), body, section.firstPageNumber(), bands));
        }
        return continueTables(result, context, boxes);
    }
    private static PageRegion region(List<Block> blocks) {
        if (blocks.isEmpty()) { return PageRegion.EMPTY; }
        double height = blocks.stream().map(Paragraph.class::cast).mapToDouble(value -> value.format().lineHeight() + value.format().spaceAfter()).sum();
        return new PageRegion(height, 4, blocks);
    }
    private static String bandKey(Block block, PreparedPdfImport.Source source, double height) {
        String text = block instanceof Paragraph value ? value.text() : block instanceof Heading value ? value.text() : "";
        if (text.isBlank() || source.height() > height * .12) { return ""; }
        String kind = source.y() + source.height() < height * .12 ? "H:" : source.y() > height * .88 ? "F:" : "";
        return kind.isEmpty() ? "" : kind + Math.round(source.y() / 4) + ":" + text.replaceAll("\\d+", "#");
    }
    private static List<Section> continueTables(List<Section> sections, Context context, Map<String, PreparedPdfImport.Source> boxes) throws IOException {
        var result = new ArrayList<Section>();
        for (var section : sections) {
            context.work();
            if (!result.isEmpty() && !section.blocks().isEmpty()) {
                var previous = result.getLast();
                if (!previous.blocks().isEmpty() && previous.blocks().getLast() instanceof Table first && section.blocks().getFirst() instanceof Table second) {
                    var a = boxes.get(first.id()); var b = boxes.get(second.id());
                    if (a != null && b != null && b.page() == a.page() + 1 && a.y() + a.height() > previous.pageSettings().size().height() * .65
                            && b.y() < section.pageSettings().size().height() * .35 && Math.abs(a.x() - b.x()) <= 2
                            && Math.abs(a.width() - b.width()) <= 2 && columnsEqual(first, second)) {
                        boolean repeated = rowText(first.rows().getFirst()).equals(rowText(second.rows().getFirst()))
                                && first.rows().getFirst().cells().stream().allMatch(cell -> cell.rowSpan() == 1)
                                && second.rows().getFirst().cells().stream().allMatch(cell -> cell.rowSpan() == 1);
                        int skip = repeated && second.rows().size() > 1 ? 1 : 0;
                        var rows = new ArrayList<>(first.rows()); rows.addAll(second.rows().subList(skip, second.rows().size()));
                        var inlinePaths = new HashMap<String, List<Integer>>();
                        if (repeated && first.rows().getFirst().cells().stream().allMatch(cell -> !cell.hasBlocks() || cell.blocks().size() == 1 && cell.blocks().getFirst() instanceof Paragraph)) {
                            var headers = new ArrayList<TableCell>(); int column = 0;
                            for (var cell : first.rows().getFirst().cells()) {
                                if (cell.hasBlocks()) {
                                    var paragraph = (Paragraph) cell.blocks().getFirst(); inlinePaths.put(paragraph.id(), List.of(0, column));
                                    headers.add(TableCell.builder().format(cell.format()).columnSpan(cell.columnSpan()).rowSpan(cell.rowSpan()).content(paragraph.content()).build());
                                } else { headers.add(cell); }
                                column += cell.columnSpan();
                            }
                            rows.set(0, new TableRow(headers));
                        } else if (repeated) { repeated = false; skip = 0; rows = new ArrayList<>(first.rows()); rows.addAll(second.rows()); }
                        var merged = new Table(first.id(), first.columns(), rows, repeated ? 1 : first.headerRows(), first.format());
                        var previousBlocks = new ArrayList<>(previous.blocks()); previousBlocks.set(previousBlocks.size() - 1, merged);
                        result.set(result.size() - 1, new Section(previous.id(), previous.pageSettings(), previousBlocks, previous.firstPageNumber(), previous.pageRegions()));
                        // Keep explicit provenance for the continuation, including a removed repeated
                        // header's original leaf. No source text is duplicated into the body.
                        var replacements = new HashMap<String, String>(); replacements.put(second.id(), first.id());
                        if (skip > 0) {
                            var original = rowIds(first.rows().getFirst()); var duplicate = rowIds(second.rows().getFirst());
                            if (original.size() == duplicate.size()) { for (int i = 0; i < original.size(); i++) { replacements.put(duplicate.get(i), original.get(i)); } }
                        }
                        for (int i = 0; i < context.sources().size(); i++) {
                            var source = context.sources().get(i); var replacement = replacements.get(source.blockId());
                            String leaf = replacement == null ? source.blockId() : replacement; var path = inlinePaths.get(leaf);
                            if (path != null) { context.sources().set(i, new PreparedPdfImport.Source(first.id(), source.page(), source.x(), source.y(), source.width(), source.height(), path)); }
                            else if (replacement != null) { context.sources().set(i, new PreparedPdfImport.Source(replacement, source.page(), source.x(), source.y(), source.width(), source.height(), source.cellPath())); }
                        }
                        boxes.put(first.id(), new PreparedPdfImport.Source(first.id(), b.page(), b.x(), b.y(), b.width(), b.height()));
                        if (skip > 0) { context.inferred("CROSS_PAGE_TABLE_CONTINUATION_AND_REPEATED_HEADER_INFERRED", b.page()); }
                        else { context.inferred("CROSS_PAGE_TABLE_CONTINUATION_INFERRED_WITHOUT_REPEATED_HEADER", b.page()); }
                        var remaining = section.blocks().subList(1, section.blocks().size());
                        if (!remaining.isEmpty() || !section.pageRegions().equals(PageRegions.EMPTY)) { result.add(new Section(section.id(), section.pageSettings(), remaining, section.firstPageNumber(), section.pageRegions())); }
                        continue;
                    }
                }
            }
            result.add(section);
        }
        return List.copyOf(result);
    }
    private static boolean columnsEqual(Table first, Table second) {
        if (first.columns().size() != second.columns().size()) { return false; }
        for (int column = 0; column < first.columns().size(); column++) {
            var a = first.columns().get(column); var b = second.columns().get(column);
            if (a instanceof ColumnWidth.Fraction x && b instanceof ColumnWidth.Fraction y) { if (Math.abs(x.weight() - y.weight()) > 1) { return false; } }
            else if (!a.equals(b)) { return false; }
        }
        return true;
    }
    private static String rowText(TableRow row) {
        return row.cells().stream().map(cell -> cell.hasBlocks() ? cell.blocks().stream().map(block -> block instanceof Paragraph value ? value.text() : "[TABLE]").collect(java.util.stream.Collectors.joining("\n")) : cell.text()).collect(java.util.stream.Collectors.joining("\t"));
    }
    private static List<String> rowIds(TableRow row) {
        var result = new ArrayList<String>();
        for (var cell : row.cells()) { if (cell.hasBlocks()) { for (var block : cell.blocks()) { result.add(block.id()); if (block instanceof Table table) { for (var child : table.rows()) { result.addAll(rowIds(child)); } } } } }
        return result;
    }
}
