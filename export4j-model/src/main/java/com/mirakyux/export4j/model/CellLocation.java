// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.Objects;
import java.io.Serial;
import java.io.Serializable;

/** One outer-to-inner semantic cell source step. Row/column and block indexes are zero based;
 * blockIndex=-1 denotes the legacy inline cell itself. All other indexes are nonnegative.
 * Immutable and thread-safe; coordinates describe original anchors, never covered slots or output pages.
 */
public record CellLocation(String tableId, int rowIndex, int columnIndex, int blockIndex) implements Serializable {
    @Serial private static final long serialVersionUID = 1L;
    public CellLocation {
        Objects.requireNonNull(tableId, "tableId");
        if (tableId.isBlank() || rowIndex < 0 || columnIndex < 0 || blockIndex < -1) {
            throw new IllegalArgumentException("invalid semantic cell location");
        }
    }
}
