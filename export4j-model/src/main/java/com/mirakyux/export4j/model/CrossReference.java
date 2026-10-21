// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.Objects;

/** Bounded wrapped text resolved after pagination. PAGE uses displayed section numbering; NUMBER targets a list item/caption. */
public record CrossReference(String id, String prefix, String targetId, Kind kind, ParagraphFormat format) implements Block {
    public enum Kind { PAGE, NUMBER }

    public CrossReference {
        new Paragraph(id, prefix, format);
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(kind, "kind");
        if (targetId.isBlank()) {
            throw new IllegalArgumentException("reference target must not be blank");
        }
    }
}
