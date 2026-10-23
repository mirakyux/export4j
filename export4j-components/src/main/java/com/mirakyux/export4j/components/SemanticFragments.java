// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.layout.CellFragment;
import com.mirakyux.export4j.layout.ComponentFragment;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.LayoutFragment;
import com.mirakyux.export4j.layout.ParagraphFragment;
import com.mirakyux.export4j.layout.SemanticPlacement;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.layout.TableRowFragment;
import com.mirakyux.export4j.layout.core.ComponentSemantics;
import com.mirakyux.export4j.model.CellLocation;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.text.ParagraphLayout;

/** One final-page walk over real IR nodes and text leaves; cell children retain local coordinates. */
final class SemanticFragments {
    record Located(LayoutFragment fragment, Point origin, int rootBlockIndex, List<CellLocation> sourcePath) { }
    record Text(String blockId, int blockIndex, int rowIndex, int columnIndex, Point origin,
            List<CellLocation> sourcePath, ParagraphLayout layout, FontGroup fonts, Map<String, FontGroup> roles) { }

    private SemanticFragments() { }

    static void walk(LayoutFragment fragment, Consumer<Located> nodes, Consumer<Text> texts) {
        new Traversal(nodes, texts, null).visit(fragment, new Point(0, 0), fragment.blockIndex(), List.of(), null, Map.of(), 0);
    }

    static LayoutFragment map(LayoutFragment fragment, FontGroup fonts, Map<String, FontGroup> roles,
            Function<Text, ParagraphLayout> mapper) {
        return new Traversal(ignored -> { }, ignored -> { }, mapper)
                .visit(fragment, new Point(0, 0), fragment.blockIndex(), List.of(), fonts, roles, 0);
    }

    private static List<CellLocation> append(List<CellLocation> path, CellLocation location) {
        var result = new ArrayList<>(path);
        result.add(location);
        return List.copyOf(result);
    }

    private static Point add(Point a, Point b) { return new Point(a.x() + b.x(), a.y() + b.y()); }

    private static final class Traversal {
        private final Consumer<Located> nodes;
        private final Consumer<Text> texts;
        private final Function<Text, ParagraphLayout> mapper;

        private Traversal(Consumer<Located> nodes, Consumer<Text> texts, Function<Text, ParagraphLayout> mapper) {
            this.nodes = nodes;
            this.texts = texts;
            this.mapper = mapper;
        }

        private LayoutFragment visit(LayoutFragment fragment, Point translation, int rootBlockIndex,
                List<CellLocation> path, FontGroup fonts, Map<String, FontGroup> roles, int depth) {
            ExecutionContext.checkpoint();
            if (depth > 64) { throw new IllegalArgumentException("semantic fragment nesting budget exceeded"); }
            Point origin = add(translation, fragment.origin());
            nodes.accept(new Located(fragment, origin, rootBlockIndex, path));
            return switch (fragment) {
                case ParagraphFragment paragraph -> {
                    var cell = path.isEmpty() ? null : path.getLast();
                    var leaf = new Text(paragraph.blockId(), rootBlockIndex, cell == null ? -1 : cell.rowIndex(),
                            cell == null ? -1 : cell.columnIndex(), origin, path, paragraph.layout(), fonts, roles);
                    texts.accept(leaf);
                    yield mapper == null ? paragraph : new ParagraphFragment(paragraph.blockId(), paragraph.blockIndex(),
                            paragraph.firstLineIndex(), paragraph.origin(), mapper.apply(leaf));
                }
                case TableFragment table -> {
                    var rows = mapper == null ? null : new ArrayList<TableRowFragment>();
                    for (var row : table.rows()) {
                        var cells = mapper == null ? null : new ArrayList<CellFragment>();
                        for (var cell : row.cells()) {
                            ExecutionContext.checkpoint();
                            Point contentOrigin = add(origin, cell.textOrigin());
                            if (cell.hasBlocks()) {
                                var children = mapper == null ? null : new ArrayList<LayoutFragment>();
                                for (int index = 0; index < cell.blocks().size(); index++) {
                                    var childPath = append(path, new CellLocation(table.blockId(), row.rowIndex(), cell.columnIndex(), cell.sourceBlockIndices().get(index)));
                                    var child = visit(cell.blocks().get(index), contentOrigin, rootBlockIndex, childPath, fonts, roles, depth + 1);
                                    if (children != null) { children.add(child); }
                                }
                                if (cells != null) { cells.add(new CellFragment(cell.columnIndex(), cell.bounds(), cell.textOrigin(),
                                        new CellFragment.Blocks(children, cell.sourceBlockIndices()))); }
                            } else {
                                var sourcePath = append(path, new CellLocation(table.blockId(), row.rowIndex(), cell.columnIndex(), -1));
                                var leaf = new Text(table.blockId(), rootBlockIndex, row.rowIndex(), cell.columnIndex(),
                                        contentOrigin, sourcePath, cell.layout(), fonts, roles);
                                texts.accept(leaf);
                                if (cells != null) { cells.add(new CellFragment(cell.columnIndex(), cell.bounds(), cell.textOrigin(),
                                        mapper.apply(leaf), cell.firstLineIndex())); }
                            }
                        }
                        if (rows != null) { rows.add(new TableRowFragment(row.rowIndex(), row.header(), row.repeatedHeader(), cells, row.continuedRow())); }
                    }
                    yield rows == null ? table : new TableFragment(table.blockId(), table.blockIndex(), table.origin(), table.width(), table.height(), rows);
                }
                case ComponentFragment component -> {
                    var placements = mapper == null ? null : new ArrayList<SemanticPlacement>();
                    for (var located : ComponentSemantics.locate(component.layout(), origin)) {
                        var placed = located.placement();
                        var resolved = (TableFragment) visit(placed.fragment(), located.translation(), rootBlockIndex, path,
                                placed.fonts(), placed.roles(), depth + 1);
                        if (placements != null) {
                            placements.add(new SemanticPlacement(placed.commandIndex(), placed.source(), resolved, placed.fonts(), placed.roles(),
                                    placed.fragmentIndex(), placed.fragmentCount()));
                        }
                    }
                    var old = component.layout();
                    yield placements == null ? component : new ComponentFragment(component.blockId(), component.blockIndex(), component.origin(),
                            new ComponentLayout(old.width(), old.height(), old.textLines(), old.staticCommands(), old.diagnostics(), placements));
                }
            };
        }
    }
}
