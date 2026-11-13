// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.Objects;

/** Automatically numbered caption; counters are independent for each kind, such as Figure or Table. */
public record Caption(String id, String kind, String text, ParagraphFormat format, TextAppearance appearance) implements Block {
    /** Compatibility constructor retaining the explicit default font role and black. */
    public Caption(String id, String kind, String text, ParagraphFormat format) {
        this(id, kind, text, format, TextAppearance.DEFAULT);
    }

    public Caption {
        new Paragraph(id, text, format);
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(appearance, "appearance");
        if (kind.isBlank()) {
            throw new IllegalArgumentException("caption kind must not be blank");
        }
    }
}
