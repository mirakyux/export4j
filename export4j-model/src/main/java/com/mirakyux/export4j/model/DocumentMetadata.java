// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.Objects;

/** Immutable, non-null document metadata. Empty strings indicate unspecified values. */
public record DocumentMetadata(String title, String author) {
    public static final DocumentMetadata EMPTY = new DocumentMetadata("", "");

    public DocumentMetadata {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(author, "author");
    }
}
