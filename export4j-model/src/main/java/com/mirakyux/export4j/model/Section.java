// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.List;
import java.util.Objects;

/** A section starts on a new page with its own size/margins. firstPageNumber=0 continues numbering. */
public record Section(String id, PageSettings pageSettings, List<Block> blocks, int firstPageNumber) {
    public static Builder builder(String id) { return new Builder(id); }
    /** Task-confined section builder. Supply blocks from semantic or component authoring.
     * Block IDs must remain unique across all sections. */
    public static final class Builder {
        private final String id;
        private PageSettings pageSettings = PageSettings.A4;
        private final java.util.ArrayList<Block> blocks = new java.util.ArrayList<>();
        private int firstPageNumber;
        private Builder(String id) { this.id = Objects.requireNonNull(id, "id"); }
        public Builder pageSettings(PageSettings value) { pageSettings = Objects.requireNonNull(value, "pageSettings"); return this; }
        public Builder firstPageNumber(int value) { firstPageNumber = value; return this; }
        public Builder add(Block block) { blocks.add(Objects.requireNonNull(block, "block")); return this; }
        public Builder blocks(List<Block> values) { blocks.clear(); blocks.addAll(List.copyOf(values)); return this; }
        public Section build() { return new Section(id, pageSettings, blocks, firstPageNumber); }
    }
    public Section {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(pageSettings, "pageSettings");
        blocks = List.copyOf(blocks);
        if (id.isBlank() || firstPageNumber < 0) {
            throw new IllegalArgumentException("invalid section ID or page number");
        }
    }
}
