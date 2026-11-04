// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** One immutable grid item, identified independently from its original semantic children.
 * Row, start and span are one-based. Items belong to GridLayout only; their content is atomic
 * with the other items in the same row. Images use ordinary paragraph inline images.
 */
public record GridItem(String id, int row, int columnStart, int columnSpan, List<Block> blocks) implements Block {
    public GridItem {
        Objects.requireNonNull(id, "id");
        blocks = List.copyOf(blocks);
        if (id.isBlank() || row < 1 || columnStart < 1 || columnSpan < 1 || blocks.isEmpty() || blocks.size() > 100_000) {
            throw new IllegalArgumentException("invalid semantic grid item");
        }
        for (var block : blocks) {
            ExecutionContext.checkpoint();
            if (!(block instanceof Paragraph) && !(block instanceof Table)) {
                throw new IllegalArgumentException("grid items accept only paragraphs and tables");
            }
            if (block instanceof Paragraph paragraph && (paragraph.format().pagination().pageBreakBefore()
                    || paragraph.format().pagination().keepWithNext())) {
                throw new IllegalArgumentException("grid items do not support page breaks or keep-with-next chains");
            }
        }
    }

    public static Builder builder(String id) { return new Builder(id); }
    public Builder toBuilder() { return new Builder(id).row(row).columnStart(columnStart).columnSpan(columnSpan).blocks(blocks); }

    /** Task-confined builder. Content lists are snapshotted on assignment and construction. */
    public static final class Builder {
        private final String id;
        private int row = 1;
        private int start = 1;
        private int span = 1;
        private final List<Block> blocks = new ArrayList<>();
        private Builder(String id) { this.id = Objects.requireNonNull(id, "id"); }
        public Builder row(int value) { row = value; return this; }
        public Builder columnStart(int value) { start = value; return this; }
        public Builder columnSpan(int value) { span = value; return this; }
        public Builder blocks(List<Block> values) { blocks.clear(); blocks.addAll(List.copyOf(values)); return this; }
        public Builder add(Block value) { blocks.add(Objects.requireNonNull(value, "block")); return this; }
        public GridItem build() { return new GridItem(id, row, start, span, blocks); }
    }
}
