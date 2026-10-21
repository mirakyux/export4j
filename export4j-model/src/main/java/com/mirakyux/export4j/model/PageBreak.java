// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.Objects;

/** Starts the next content on a fresh page. Leading, consecutive and trailing breaks do not create blank pages. */
public record PageBreak(String id) implements Block {
    public PageBreak {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("page break ID must not be blank");
        }
    }
}
