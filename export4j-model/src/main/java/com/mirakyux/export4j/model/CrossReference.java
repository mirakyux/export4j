// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.Objects;

/** Bounded wrapped text resolved after pagination. PAGE uses displayed section numbering; NUMBER targets a list item/caption. */
public record CrossReference(String id, String prefix, String targetId, Kind kind, ParagraphFormat format,
        TextAppearance appearance) implements Block {
    public enum Kind { PAGE, NUMBER }

    /** Compatibility constructor retaining the explicit default font role and black. */
    public CrossReference(String id, String prefix, String targetId, Kind kind, ParagraphFormat format) {
        this(id, prefix, targetId, kind, format, TextAppearance.DEFAULT);
    }

    public CrossReference {
        new Paragraph(id, prefix, format);
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(appearance, "appearance");
        if (targetId.isBlank()) {
            throw new IllegalArgumentException("reference target must not be blank");
        }
    }
}
