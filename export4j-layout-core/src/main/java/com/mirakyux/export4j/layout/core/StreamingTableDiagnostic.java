// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import java.util.Objects;
import com.mirakyux.export4j.text.TextDiagnostic;

/** One synchronous presentation diagnostic. Indices are zero-based; rowIndex counts source headers
 * before body rows, columnIndex and diagnostic UTF-16 positions belong to the original source cell.
 * A repeated header emits again for its actual page. No source text or resource path is retained.
 */
public record StreamingTableDiagnostic(String blockId, int pageIndex, int rowIndex, int columnIndex,
        TextDiagnostic diagnostic) {
    public StreamingTableDiagnostic {
        Objects.requireNonNull(blockId, "blockId");
        Objects.requireNonNull(diagnostic, "diagnostic");
        if (blockId.isBlank() || pageIndex < 0 || rowIndex < 0 || columnIndex < 0) {
            throw new IllegalArgumentException("invalid streaming diagnostic context");
        }
    }
}
