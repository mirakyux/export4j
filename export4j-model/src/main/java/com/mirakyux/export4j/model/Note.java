// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.Objects;

/** Numbered footnote/endnote. An explicit InlineField NOTE_MARKER anchors its exact line;
 * otherwise a superscript marker is appended and follows the paragraph's final fragment.
 */
public record Note(String id, String paragraphId, String text, Kind kind) {
    public enum Kind { FOOTNOTE, ENDNOTE }

    public Note {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(paragraphId, "paragraphId");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(kind, "kind");
        if (id.isBlank() || paragraphId.isBlank() || text.isBlank()) {
            throw new IllegalArgumentException("note identifiers and text must not be blank");
        }
    }
}
