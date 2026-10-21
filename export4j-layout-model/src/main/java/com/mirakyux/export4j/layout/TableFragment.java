// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout;

import java.util.List;
import java.util.Objects;
import com.mirakyux.export4j.graphics.Point;

/** Complete table rows on one page; dimensions in points, origin page-local. Lists are immutable snapshots. */
public record TableFragment(String blockId, int blockIndex, Point origin, double width, double height,
        List<TableRowFragment> rows) implements LayoutFragment {
    public TableFragment {
        Objects.requireNonNull(blockId, "blockId");
        Objects.requireNonNull(origin, "origin");
        rows = List.copyOf(rows);
        if (blockId.isBlank() || blockIndex < 0 || rows.isEmpty()
                || !Double.isFinite(width) || width <= 0 || !Double.isFinite(height) || height <= 0) {
            throw new IllegalArgumentException("invalid table fragment");
        }
    }
}
