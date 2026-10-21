// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Shared synchronous semantic preorder traversal. Visits every occurrence, including repeated instances;
 * only real cell anchors own descendants. Supports at most two table levels and retains no mutable state.
 * Custom top-level blocks are leaves. Cell descendants must be supported Paragraph/Table values.
 */
public final class SemanticTree {
    private SemanticTree() { }

    /** Immutable source visit. rootBlockIndex belongs to the original supplied root list; cellPath is an
     * outer-to-inner defensive snapshot. Empty paths identify root blocks. The block remains borrowed.
     */
    public record Visit(Block block, int rootBlockIndex, List<CellLocation> cellPath) {
        public Visit {
            Objects.requireNonNull(block, "block");
            cellPath = List.copyOf(cellPath);
            if (rootBlockIndex < 0) { throw new IllegalArgumentException("negative root block index"); }
        }
    }

    /** Visits roots and descendants in source preorder, without identity deduplication. The visitor runs
     * inline and is not retained; cancellation checkpoints apply throughout traversal. Invalid cell types,
     * cell pagination flags or a third table level throw IllegalArgumentException.
     */
    public static void walk(List<? extends Block> roots, Consumer<Visit> visitor) {
        var values = List.copyOf(roots);
        Objects.requireNonNull(visitor, "visitor");
        for (int index = 0; index < values.size(); index++) {
            walk(values.get(index), index, List.of(), 0, visitor);
        }
    }

    /** Convenience traversal of one root, whose rootBlockIndex is zero. */
    public static void walk(Block root, Consumer<Visit> visitor) {
        walk(List.of(Objects.requireNonNull(root, "root")), visitor);
    }

    private static void walk(Block block, int rootIndex, List<CellLocation> path,
            int tableDepth, Consumer<Visit> visitor) {
        ExecutionContext.checkpoint();
        if (!path.isEmpty()) { validateCellBlock(block); }
        int depth = tableDepth + (block instanceof Table ? 1 : 0);
        if (depth > 2) { throw new IllegalArgumentException("semantic cells support at most two table levels"); }
        visitor.accept(new Visit(block, rootIndex, path));
        if (block instanceof Table table && table.hasBlockCells()) {
            var grid = table.grid();
            for (var row : grid.anchorRows()) {
                ExecutionContext.checkpoint();
                for (var anchor : row) {
                    var cell = anchor.cell();
                    if (!cell.hasBlocks()) { continue; }
                    for (int index = 0; index < cell.blocks().size(); index++) {
                        var nested = new ArrayList<>(path);
                        nested.add(new CellLocation(table.id(), anchor.rowIndex(), anchor.columnIndex(), index));
                        walk(cell.blocks().get(index), rootIndex, nested, depth, visitor);
                    }
                }
            }
        }
    }

    static void validateCellBlocks(List<Block> blocks) {
        for (var block : blocks) { validateCellSubtree(block, 1); }
    }

    private static void validateCellSubtree(Block block, int ownerDepth) {
        ExecutionContext.checkpoint();
        validateCellBlock(block);
        if (block instanceof Table table) {
            if (ownerDepth >= 2) { throw new IllegalArgumentException("semantic cells support at most two table levels"); }
            for (var row : table.rows()) {
                ExecutionContext.checkpoint();
                for (var cell : row.cells()) {
                    for (var child : cell.blocks()) { validateCellSubtree(child, ownerDepth + 1); }
                }
            }
        }
    }

    private static void validateCellBlock(Block block) {
        if (block instanceof Paragraph paragraph) {
            var pagination = paragraph.format().pagination();
            if (pagination.pageBreakBefore() || pagination.keepWithNext()) {
                throw new IllegalArgumentException("cell paragraphs do not support pageBreakBefore or keepWithNext");
            }
            if (paragraph.fields().stream().anyMatch(field -> field.field().kind() == InlineField.Kind.NOTE_MARKER)) {
                throw new IllegalArgumentException("cell paragraphs do not support note markers");
            }
        } else if (!(block instanceof Table)) {
            throw new IllegalArgumentException("semantic block cells accept only paragraphs and tables");
        }
    }

    static void validateUniqueIds(List<? extends Block> blocks) {
        var ids = new HashSet<String>();
        walk(blocks, visit -> {
            String id = visit.block().id();
            if (id == null || id.isBlank()) { throw new IllegalArgumentException("block IDs must not be blank"); }
            if (!ids.add(id)) { throw new IllegalArgumentException("block IDs must be unique throughout the semantic tree"); }
        });
    }
}
