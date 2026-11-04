// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.AffineTransform;
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
            List<CellLocation> sourcePath, ParagraphLayout layout, FontGroup fonts, Map<String, FontGroup> roles,
            AffineTransform transform) { }

    private SemanticFragments() { }

    static void walk(LayoutFragment fragment, Consumer<Located> nodes, Consumer<Text> texts) {
        walk(fragment, Map.of(), nodes, texts);
    }

    static void walk(LayoutFragment fragment, Map<String, Double> rotations, Consumer<Located> nodes, Consumer<Text> texts) {
        new Traversal(nodes, texts, null, rotations).visit(fragment, new Point(0, 0), fragment.blockIndex(), List.of(),
                null, Map.of(), AffineTransform.IDENTITY, 0);
    }

    static LayoutFragment map(LayoutFragment fragment, FontGroup fonts, Map<String, FontGroup> roles,
            Function<Text, ParagraphLayout> mapper) {
        return map(fragment, fonts, roles, Map.of(), mapper);
    }

    static LayoutFragment map(LayoutFragment fragment, FontGroup fonts, Map<String, FontGroup> roles,
            Map<String, Double> rotations, Function<Text, ParagraphLayout> mapper) {
        return new Traversal(ignored -> { }, ignored -> { }, mapper, rotations)
                .visit(fragment, new Point(0, 0), fragment.blockIndex(), List.of(), fonts, roles, AffineTransform.IDENTITY, 0);
    }

    static Rect transformedBounds(Rect bounds, AffineTransform transform) {
        if (transform.equals(AffineTransform.IDENTITY)) { return bounds; }
        double left = Double.POSITIVE_INFINITY, top = Double.POSITIVE_INFINITY;
        double right = Double.NEGATIVE_INFINITY, bottom = Double.NEGATIVE_INFINITY;
        for (double x : new double[] {bounds.x(), bounds.x() + bounds.width()}) {
            for (double y : new double[] {bounds.y(), bounds.y() + bounds.height()}) {
                double px = transform.a() * x + transform.c() * y + transform.e();
                double py = transform.b() * x + transform.d() * y + transform.f();
                left = Math.min(left, px); right = Math.max(right, px);
                top = Math.min(top, py); bottom = Math.max(bottom, py);
            }
        }
        return new Rect(left, top, right - left, bottom - top);
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
        private final Map<String, Double> rotations;

        private Traversal(Consumer<Located> nodes, Consumer<Text> texts, Function<Text, ParagraphLayout> mapper,
                Map<String, Double> rotations) {
            this.nodes = nodes;
            this.texts = texts;
            this.mapper = mapper;
            this.rotations = rotations;
        }

        private LayoutFragment visit(LayoutFragment fragment, Point translation, int rootBlockIndex,
                List<CellLocation> path, FontGroup fonts, Map<String, FontGroup> roles,
                AffineTransform inherited, int depth) {
            ExecutionContext.checkpoint();
            if (depth > 64) { throw new IllegalArgumentException("semantic fragment nesting budget exceeded"); }
            Point origin = add(translation, fragment.origin());
            double angle = rotations.getOrDefault(fragment.blockId(), 0.0);
            var transform = angle == 0 ? inherited : concatenate(inherited, AffineTransform.fittedRotation(
                    switch (fragment) {
                        case ParagraphFragment p -> new Rect(origin.x(), origin.y(), p.layout().width(), p.layout().height());
                        case TableFragment t -> new Rect(origin.x(), origin.y(), t.width(), t.height());
                        case ComponentFragment c -> new Rect(origin.x(), origin.y(), c.layout().width(), c.layout().height());
                    }, angle));
            nodes.accept(new Located(fragment, origin, rootBlockIndex, path));
            return switch (fragment) {
                case ParagraphFragment paragraph -> {
                    var cell = path.isEmpty() ? null : path.getLast();
                    var leaf = new Text(paragraph.blockId(), rootBlockIndex, cell == null ? -1 : cell.rowIndex(),
                            cell == null ? -1 : cell.columnIndex(), origin, path, paragraph.layout(), fonts, roles, transform);
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
                                    var child = visit(cell.blocks().get(index), contentOrigin, rootBlockIndex, childPath, fonts, roles, transform, depth + 1);
                                    if (children != null) { children.add(child); }
                                }
                                if (cells != null) { cells.add(new CellFragment(cell.columnIndex(), cell.bounds(), cell.textOrigin(),
                                        new CellFragment.Blocks(children, cell.sourceBlockIndices()))); }
                            } else {
                                var sourcePath = append(path, new CellLocation(table.blockId(), row.rowIndex(), cell.columnIndex(), -1));
                                var leaf = new Text(table.blockId(), rootBlockIndex, row.rowIndex(), cell.columnIndex(),
                                        contentOrigin, sourcePath, cell.layout(), fonts, roles, transform);
                                texts.accept(leaf);
                                if (cells != null) { cells.add(new CellFragment(cell.columnIndex(), cell.bounds(), cell.textOrigin(),
                                        mapper.apply(leaf), cell.firstLineIndex())); }
                            }
                        }
                        if (rows != null) { rows.add(new TableRowFragment(row.rowIndex(), row.header(), row.repeatedHeader(), cells, row.continuedRow())); }
                    }
                    yield rows == null ? table : new TableFragment(table.blockId(), table.blockIndex(), table.origin(), table.width(), table.height(), rows, table.drawBorders());
                }
                case ComponentFragment component -> {
                    var placements = mapper == null ? null : new ArrayList<SemanticPlacement>();
                    for (var located : ComponentSemantics.locate(component.layout(), origin)) {
                        var placed = located.placement();
                        var placementTraversal = new Traversal(nodes, texts, mapper, ModelRotations.of(List.of(placed.source())));
                        var resolved = (TableFragment) placementTraversal.visit(placed.fragment(), located.translation(), rootBlockIndex, path,
                                placed.fonts(), placed.roles(), transform, depth + 1);
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

    private static AffineTransform concatenate(AffineTransform a, AffineTransform b) {
        return new AffineTransform(a.a() * b.a() + a.c() * b.b(), a.b() * b.a() + a.d() * b.b(),
                a.a() * b.c() + a.c() * b.d(), a.b() * b.c() + a.d() * b.d(),
                a.a() * b.e() + a.c() * b.f() + a.e(), a.b() * b.e() + a.d() * b.f() + a.f());
    }
}
