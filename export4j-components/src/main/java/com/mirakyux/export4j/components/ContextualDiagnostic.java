// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.Objects;
import java.util.List;
import com.mirakyux.export4j.model.CellLocation;
import com.mirakyux.export4j.text.TextDiagnostic;

/** Immutable final zero-based physical page and original leaf/region; row/column=-1 when unavailable.
 * Cell ancestry is ordered outermost to innermost and snapshotted. Diagnostic UTF-16 positions belong
 * to the leaf source, or to a generated value when region starts with INLINE_FIELD; never to concatenated blocks.
 */
public record ContextualDiagnostic(String blockId, int pageIndex, String region, int rowIndex, int columnIndex,
        TextDiagnostic diagnostic, List<CellLocation> sourcePath) {
    public ContextualDiagnostic {
        Objects.requireNonNull(blockId, "blockId");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(diagnostic, "diagnostic");
        sourcePath = List.copyOf(sourcePath);
        if (pageIndex < 0 || rowIndex < -1 || columnIndex < -1) { throw new IllegalArgumentException("invalid diagnostic context"); }
    }

    /** Legacy context without cell ancestry; source positions still belong to the original text leaf. */
    public ContextualDiagnostic(String blockId, int pageIndex, String region, int rowIndex, int columnIndex, TextDiagnostic diagnostic) {
        this(blockId, pageIndex, region, rowIndex, columnIndex, diagnostic, List.of());
    }
}
