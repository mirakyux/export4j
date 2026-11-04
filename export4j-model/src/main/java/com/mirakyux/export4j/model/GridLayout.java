// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable semantic grid with one to twelve equal-width tracks and point gaps.
 * Items are explicitly ordered by row then column start, never overlap, and may leave empty tracks.
 * Missing row numbers create no blank rows. Each occupied row moves as a whole between body regions.
 * Nested grids/columns, row spans and automatic placement are outside this subset.
 */
public record GridLayout(String id, int tracks, double columnGap, double rowGap, List<GridItem> items) implements Block {
    /** Twelve equal tracks. */
    public GridLayout(String id, double columnGap, double rowGap, List<GridItem> items) {
        this(id, 12, columnGap, rowGap, items);
    }

    public GridLayout {
        Objects.requireNonNull(id, "id");
        items = List.copyOf(items);
        if (id.isBlank() || tracks < 1 || tracks > 12 || !Double.isFinite(columnGap) || columnGap < 0
                || !Double.isFinite(rowGap) || rowGap < 0 || items.isEmpty() || items.size() > 100_000) {
            throw new IllegalArgumentException("invalid semantic grid layout");
        }
        int previousRow = 0;
        long previousEnd = 0;
        for (var item : items) {
            ExecutionContext.checkpoint();
            long end = (long) item.columnStart() + item.columnSpan() - 1;
            if (end > tracks || item.row() < previousRow
                    || item.row() == previousRow && item.columnStart() <= previousEnd) {
                throw new IllegalArgumentException("grid items must be ordered, nonoverlapping and within the tracks");
            }
            previousRow = item.row(); previousEnd = end;
        }
    }

    /** Starts with twelve tracks and 12 pt column/row gaps. */
    public static Builder builder(String id) { return new Builder(id); }
    public Builder toBuilder() { return new Builder(id).tracks(tracks).columnGap(columnGap).rowGap(rowGap).items(items); }

    /** Task-confined authoring with immutable snapshots. */
    public static final class Builder {
        private final String id;
        private int tracks = 12;
        private double columnGap = 12;
        private double rowGap = 12;
        private final List<GridItem> items = new ArrayList<>();
        private Builder(String id) { this.id = Objects.requireNonNull(id, "id"); }
        public Builder tracks(int value) { tracks = value; return this; }
        public Builder columnGap(double value) { columnGap = value; return this; }
        public Builder rowGap(double value) { rowGap = value; return this; }
        public Builder items(List<GridItem> values) { items.clear(); items.addAll(List.copyOf(values)); return this; }
        public Builder add(GridItem value) { items.add(Objects.requireNonNull(value, "item")); return this; }
        public GridLayout build() { return new GridLayout(id, tracks, columnGap, rowGap, items); }
    }
}
