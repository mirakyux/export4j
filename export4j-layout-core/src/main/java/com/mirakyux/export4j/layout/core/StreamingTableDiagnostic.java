// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import java.util.Objects;
import com.mirakyux.export4j.text.TextDiagnostic;

/** One synchronous presentation diagnostic. Indices are zero-based; rowIndex counts source headers
 * before body rows and columnIndex is the physical source column. For STREAMING_TABLE, diagnostic
 * UTF-16 positions belong to the original source cell. INLINE_FIELD:n identifies the field's original
 * cell-local UTF-16 position n, while the diagnostic range belongs to its generated value.
 * A repeated header emits again for its actual page. No source text or resource path is retained.
 */
public record StreamingTableDiagnostic(String blockId, int pageIndex, int rowIndex, int columnIndex,
        TextDiagnostic diagnostic, String region) {
    /** Compatibility constructor for original source-cell diagnostics. */
    public StreamingTableDiagnostic(String blockId, int pageIndex, int rowIndex, int columnIndex, TextDiagnostic diagnostic) {
        this(blockId, pageIndex, rowIndex, columnIndex, diagnostic, "STREAMING_TABLE");
    }
    public StreamingTableDiagnostic {
        Objects.requireNonNull(blockId, "blockId");
        Objects.requireNonNull(diagnostic, "diagnostic");
        Objects.requireNonNull(region, "region");
        if (blockId.isBlank() || region.isBlank() || pageIndex < 0 || rowIndex < 0 || columnIndex < 0) {
            throw new IllegalArgumentException("invalid streaming diagnostic context");
        }
    }
}
