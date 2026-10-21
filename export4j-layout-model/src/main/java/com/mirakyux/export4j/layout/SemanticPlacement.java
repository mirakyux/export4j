// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout;

import java.util.Map;
import java.util.Objects;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.model.Table;

/** A measured semantic table inserted before a zero-based static command, or at the list end.
 * Fragment coordinates use the graphics state at that slot. Sources and font-role maps are immutable
 * snapshots; font resources remain borrowed. No field is resolved and no drawing is generated here.
 * fragmentIndex is zero-based within one measured source stream; fragmentCount is its complete size.
 * Copies must preserve both values. Independent placements of a complete source are not continuations.
 * A grid may finalize its own verified cached-header repetitions as one complete presentation stream.
 */
public record SemanticPlacement(int commandIndex, Table source, TableFragment fragment,
        FontGroup fonts, Map<String, FontGroup> roles, int fragmentIndex, int fragmentCount) {
    public SemanticPlacement {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(fragment, "fragment");
        Objects.requireNonNull(fonts, "fonts");
        roles = Map.copyOf(roles);
        if (commandIndex < 0 || !source.id().equals(fragment.blockId())
                || fragmentCount < 1 || fragmentCount > 100_000 || fragmentIndex < 0 || fragmentIndex >= fragmentCount) {
            throw new IllegalArgumentException("semantic placement requires a valid slot and matching source identity");
        }
        for (var key : roles.keySet()) {
            if (key.isBlank()) { throw new IllegalArgumentException("named font roles must not be blank"); }
        }
        for (var row : fragment.rows()) {
            if (row.rowIndex() >= source.rows().size()) {
                throw new IllegalArgumentException("semantic row has no matching source");
            }
            for (var cell : row.cells()) {
                if (cell.columnIndex() >= source.columns().size()) {
                    throw new IllegalArgumentException("semantic cell has no matching source");
                }
            }
        }
    }

    /** One complete semantic table, with no continuation. All resources remain borrowed. */
    public SemanticPlacement(int commandIndex, Table source, TableFragment fragment,
            FontGroup fonts, Map<String, FontGroup> roles) {
        this(commandIndex, source, fragment, fonts, roles, 0, 1);
    }
}
