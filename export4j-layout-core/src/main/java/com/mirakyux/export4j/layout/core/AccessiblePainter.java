// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import com.mirakyux.export4j.graphics.AccessibilityTree;
import com.mirakyux.export4j.graphics.AccessibilityTree.Cell;
import com.mirakyux.export4j.graphics.AccessibilityTree.CommandRange;
import com.mirakyux.export4j.graphics.AccessibilityTree.Node;
import com.mirakyux.export4j.graphics.AccessibilityTree.Role;
import com.mirakyux.export4j.graphics.AccessibilityTree.Scope;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.layout.DocumentLayout;
import com.mirakyux.export4j.layout.LayoutFragment;
import com.mirakyux.export4j.layout.LayoutPage;
import com.mirakyux.export4j.layout.ParagraphFragment;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.layout.TableRowFragment;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.BulletItem;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.PageBreak;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.text.GlyphRun;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Source identities, rather than page coordinates, determine the logical order. */
final class AccessiblePainter {
    private AccessiblePainter() { }
    private static final class Logical {
        final String id; final Role role; final String alt; final Cell cell;
        final List<CommandRange> content = new ArrayList<>(); final List<Logical> children = new ArrayList<>();
        Logical(String id, Role role) { this(id, role, "", Cell.NONE); }
        Logical(String id, Role role, String alt, Cell cell) { this.id = id; this.role = role; this.alt = alt; this.cell = cell; }
        Node freeze() { return new Node(id, role, alt, "", cell, content, children.stream().map(Logical::freeze).toList()); }
    }
    static DisplayDocument paint(Document source, DocumentLayout layout, String language) {
        Objects.requireNonNull(source, "source"); Objects.requireNonNull(layout, "layout"); Objects.requireNonNull(language, "language");
        if (!source.metadata().equals(layout.metadata())) { throw new IllegalArgumentException("accessible source metadata differs from its layout"); }
        var roots = new ArrayList<Logical>(); var owners = new HashMap<String, Logical>(); var cells = new HashMap<String, Logical>();
        int ordinal = 0;
        for (Block block : source.blocks()) {
            if (block instanceof PageBreak) { continue; }
            var node = logical(block, "block" + ordinal++, cells); roots.add(node);
            if (owners.putIfAbsent(block.id(), node) != null) { throw new IllegalArgumentException("duplicate accessible source ID"); }
        }
        var artifacts = new ArrayList<CommandRange>(); var pages = new ArrayList<DisplayPage>(); var seen = new java.util.HashSet<String>();
        for (var page : layout.pages()) {
            var commands = new ArrayList<DrawCommand>();
            for (var fragment : page.elements()) {
                com.mirakyux.export4j.model.ExecutionContext.checkpoint();
                if (fragment.blockIndex() >= source.blocks().size()) { throw new IllegalArgumentException("accessible layout source index differs"); }
                var block = source.blocks().get(fragment.blockIndex()); var owner = owners.get(fragment.blockId());
                if (owner == null || !block.id().equals(fragment.blockId())) { throw new IllegalArgumentException("accessible layout source identity differs"); }
                seen.add(block.id());
                if (fragment instanceof TableFragment table && block instanceof Table) {
                    for (var row : table.rows()) {
                        for (var cell : row.cells()) {
                            if (cell.hasBlocks()) { throw new IllegalArgumentException("accessible block cells require explicit bindings"); }
                            var single = new TableFragment(table.blockId(), table.blockIndex(), table.origin(), table.width(), table.height(),
                                    List.of(new TableRowFragment(row.rowIndex(), row.header(), row.repeatedHeader(), List.of(cell), row.continuedRow())));
                            var painted = fragment(page, single); int first = commands.size(); commands.addAll(painted);
                            int decoration = row.header() ? 2 : 1;
                            range(artifacts, page.pageIndex(), first, first + decoration);
                            if (row.repeatedHeader()) { range(artifacts, page.pageIndex(), first + decoration, commands.size()); }
                            else {
                                var node = cells.get(block.id() + ":" + row.rowIndex() + ":" + cell.columnIndex());
                                if (node == null) { throw new IllegalArgumentException("accessible table cell is not a source anchor"); }
                                range(node.content, page.pageIndex(), first + decoration, commands.size());
                            }
                        }
                    }
                } else if (fragment instanceof ParagraphFragment paragraph && !(block instanceof Table)) {
                    var painted = fragment(page, paragraph); int first = commands.size();
                    if (block instanceof BulletItem || block instanceof NumberedItem) {
                        var item = owner.children.getFirst(); int labelUnits = 0;
                        if (paragraph.firstLineIndex() == 0) {
                            String prefix = painted.stream().filter(DrawCommand.GlyphText.class::isInstance).map(DrawCommand.GlyphText.class::cast)
                                    .map(command -> command.run().text()).collect(java.util.stream.Collectors.joining());
                            int space = prefix.indexOf(' ');
                            if (space < 0 || block instanceof BulletItem bullet && !prefix.startsWith(bullet.marker() + " ")
                                    || block instanceof NumberedItem && !prefix.substring(0, space).matches("[0-9]+\\.")) {
                                throw new IllegalArgumentException("accessible list label cannot be bound to its original glyphs");
                            }
                            labelUnits = space + 1;
                        }
                        splitList(painted, commands, labelUnits, page.pageIndex(), item.children.get(0), item.children.get(1));
                    } else {
                        for (var command : painted) {
                            // A standalone figure has its own semantic alternative. The ordinary selection
                            // anchor for U+FFFC would otherwise become an extra spoken glyph in PDF readers.
                            if (owner.role == Role.FIGURE && command instanceof DrawCommand.OutlineText outline
                                    && outline.logicalText().equals("\ufffc") && outline.paths().isEmpty()) { continue; }
                            commands.add(command);
                        }
                        range(owner.content, page.pageIndex(), first, commands.size());
                    }
                } else { throw new IllegalArgumentException("accessible components require explicit bindings"); }
            }
            pages.add(new DisplayPage(page.size(), commands));
        }
        if (!seen.equals(owners.keySet())) { throw new IllegalArgumentException("accessible source was not laid out"); }
        var tree = new AccessibilityTree(language, roots.stream().map(Logical::freeze).toList(), artifacts);
        return new DisplayDocument(layout.metadata(), pages, com.mirakyux.export4j.graphics.DocumentExtras.EMPTY, tree);
    }
    private static Logical logical(Block block, String id, Map<String, Logical> cells) {
        if (block instanceof Heading heading) { return new Logical(id, Role.valueOf("H" + heading.level())); }
        if (block instanceof Paragraph paragraph) {
            if (!paragraph.images().isEmpty()) {
                if (paragraph.images().size() != 1 || !paragraph.text().equals("\ufffc")) { throw new IllegalArgumentException("mixed accessible figures require explicit bindings"); }
                return new Logical(id, Role.FIGURE, paragraph.images().getFirst().image().altText(), Cell.NONE);
            }
            return new Logical(id, Role.P);
        }
        if (block instanceof BulletItem bullet && bullet.level() == 1 || block instanceof NumberedItem numbered && numbered.level() == 1) {
            var list = new Logical(id, Role.L); var item = new Logical(id + "-item", Role.LI);
            item.children.add(new Logical(id + "-label", Role.LBL)); item.children.add(new Logical(id + "-body", Role.LBODY)); list.children.add(item); return list;
        }
        if (block instanceof Table table) {
            var result = new Logical(id, Role.TABLE); var grid = table.grid();
            for (int r = 0; r < table.rows().size(); r++) {
                var row = new Logical(id + "-row" + r, Role.TR);
                for (var anchor : grid.anchorRows().get(r)) {
                    if (anchor.cell().hasBlocks() || !anchor.cell().images().isEmpty()) { throw new IllegalArgumentException("complex accessible cells require explicit bindings"); }
                    boolean header = r < table.headerRows(); var headers = new ArrayList<String>();
                    if (!header) {
                        for (int h = 0; h < table.headerRows(); h++) {
                            for (var candidate : grid.anchorRows().get(h)) {
                                if (candidate.columnIndex() < anchor.columnIndex() + anchor.cell().columnSpan()
                                        && anchor.columnIndex() < candidate.columnIndex() + candidate.cell().columnSpan()) { headers.add(id + "-cell" + h + "-" + candidate.columnIndex()); }
                            }
                        }
                    }
                    var cell = new Logical(id + "-cell" + r + "-" + anchor.columnIndex(), header ? Role.TH : Role.TD, "",
                            new Cell(header ? Scope.COLUMN : Scope.NONE, anchor.cell().rowSpan(), anchor.cell().columnSpan(), headers));
                    cells.put(block.id() + ":" + r + ":" + anchor.columnIndex(), cell); row.children.add(cell);
                }
                if (!row.children.isEmpty()) { result.children.add(row); }
            }
            return result;
        }
        throw new IllegalArgumentException("source block requires explicit accessibility bindings");
    }
    private static List<DrawCommand> fragment(LayoutPage page, LayoutFragment fragment) {
        return DocumentPainter.paintPage(new LayoutPage(page.pageIndex(), page.size(), page.contentBounds(), List.of(fragment))).commands();
    }
    private static void splitList(List<DrawCommand> source, List<DrawCommand> target, int remaining, int page, Logical label, Logical body) {
        for (var command : source) {
            if (remaining > 0 && command instanceof DrawCommand.GlyphText text) {
                int glyphCount = 0, units = 0;
                for (var glyph : text.run().glyphs()) { int size = Character.charCount(glyph.codePoint()); if (units + size > remaining) { break; } units += size; glyphCount++; }
                if (glyphCount > 0) {
                    var first = run(text.run(), 0, glyphCount); int start = target.size(); target.add(new DrawCommand.GlyphText(first, text.baseline(), text.color()));
                    range(label.content, page, start, target.size()); remaining -= units;
                    if (glyphCount < text.run().glyphs().size()) {
                        start = target.size(); target.add(new DrawCommand.GlyphText(run(text.run(), glyphCount, text.run().glyphs().size()), new Point(text.baseline().x() + first.width(), text.baseline().y()), text.color()));
                        range(body.content, page, start, target.size());
                    }
                    continue;
                }
            }
            int first = target.size(); target.add(command); range((remaining > 0 ? label : body).content, page, first, target.size());
        }
        if (remaining != 0) { throw new IllegalArgumentException("accessible list label splits an unsupported glyph representation"); }
    }
    private static GlyphRun run(GlyphRun source, int first, int end) {
        return new GlyphRun(source.font(), source.fontSize(), source.glyphs().subList(first, end), source.rgb(), source.baselineShift(), source.underline(), source.strikeThrough());
    }
    private static void range(List<CommandRange> ranges, int page, int first, int end) { if (first < end) { ranges.add(new CommandRange(page, first, end)); } }
}
