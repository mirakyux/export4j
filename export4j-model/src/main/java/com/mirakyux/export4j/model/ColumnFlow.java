// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable continuous, top-to-bottom then left-to-right body flow.
 * Columns use points and positive relative widths. Content retains its semantic IDs and original
 * UTF-16 positions. This first semantic form accepts paragraphs and tables, outside cells/regions;
 * recursive columns, explicit page breaks and keep-with-next paragraph chains are unsupported.
 */
public record ColumnFlow(String id, List<Block> blocks, int columns, double gap, boolean balanced,
        List<Double> columnWeights) implements Block {
    /** Equal-width columns; content and configuration are snapshotted. */
    public ColumnFlow(String id, List<Block> blocks, int columns, double gap, boolean balanced) {
        this(id, blocks, columns, gap, balanced, List.of());
    }

    public ColumnFlow {
        Objects.requireNonNull(id, "id");
        blocks = List.copyOf(blocks);
        columnWeights = List.copyOf(columnWeights);
        if (id.isBlank() || blocks.isEmpty() || blocks.size() > 100_000 || columns < 1 || columns > 3
                || !Double.isFinite(gap) || gap < 0) {
            throw new IllegalArgumentException("invalid continuous column flow");
        }
        if (!columnWeights.isEmpty() && columnWeights.size() != columns) {
            throw new IllegalArgumentException("column weights must match the column count");
        }
        for (double weight : columnWeights) {
            if (!Double.isFinite(weight) || weight <= 0) {
                throw new IllegalArgumentException("column weights must be positive and finite");
            }
        }
        for (var block : blocks) {
            ExecutionContext.checkpoint();
            if (!(block instanceof Paragraph) && !(block instanceof Table)) {
                throw new IllegalArgumentException("continuous columns accept only paragraphs and tables");
            }
            if (block instanceof Paragraph paragraph && (paragraph.format().pagination().pageBreakBefore()
                    || paragraph.format().pagination().keepWithNext())) {
                throw new IllegalArgumentException("continuous columns do not support page breaks or keep-with-next chains");
            }
        }
    }

    /** Creates a task-confined builder with two balanced columns and an 18 pt gap. */
    public static Builder builder(String id) { return new Builder(id); }

    /** Creates an independent builder; child values are immutable and remain shared. */
    public Builder toBuilder() {
        return new Builder(id).blocks(blocks).columns(columns).gap(gap).balanced(balanced).columnWeights(columnWeights);
    }

    /** Task-confined authoring. Lists are copied when supplied and again when built. */
    public static final class Builder {
        private final String id;
        private final List<Block> blocks = new ArrayList<>();
        private int columns = 2;
        private double gap = 18;
        private boolean balanced = true;
        private List<Double> columnWeights = List.of();
        private Builder(String id) { this.id = Objects.requireNonNull(id, "id"); }
        public Builder add(Block value) { blocks.add(Objects.requireNonNull(value, "block")); return this; }
        public Builder blocks(List<Block> values) { blocks.clear(); blocks.addAll(List.copyOf(values)); return this; }
        public Builder columns(int value) { columns = value; return this; }
        /** Nonnegative space between columns, in points. */
        public Builder gap(double value) { gap = value; return this; }
        /** Enables at most 24 bounded trials to reduce the common column height. */
        public Builder balanced(boolean value) { balanced = value; return this; }
        /** Empty restores equal widths; otherwise one positive relative width per column. */
        public Builder columnWeights(List<Double> values) { columnWeights = List.copyOf(values); return this; }
        public ColumnFlow build() { return new ColumnFlow(id, blocks, columns, gap, balanced, columnWeights); }
    }
}
