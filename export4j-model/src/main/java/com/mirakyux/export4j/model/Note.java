// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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

    /** Validates the complete note snapshot without lowering or changing the document. IDs cannot
     * collide with any body/region block. Anchors are top-level body paragraphs, lists or captions;
     * an explicit marker must occur once in the owning paragraph. Unmarked notes use legacy end markers.
     * Traversal observes synchronous cancellation; callers retain their own source/work budgets.
     */
    public static void validate(SectionedDocument document, List<Note> notes) {
        Objects.requireNonNull(document, "document"); notes = List.copyOf(notes);
        var ids = new HashSet<String>();
        var parents = new HashSet<String>();
        var markers = new HashMap<String, String>();
        for (var section : document.sections()) {
            ExecutionContext.checkpoint();
            SemanticTree.walk(section.blocks(), visit -> {
                var block = visit.block(); ids.add(block.id());
                if (visit.cellPath().isEmpty() && (block instanceof Paragraph || block instanceof NumberedItem
                        || block instanceof BulletItem || block instanceof Caption)) { parents.add(block.id()); }
                if (block instanceof Paragraph paragraph) {
                    for (var span : paragraph.fields()) {
                        ExecutionContext.checkpoint(); var field = span.field();
                        if (field.kind() == InlineField.Kind.NOTE_MARKER
                                && (!visit.cellPath().isEmpty() || markers.putIfAbsent(field.targetId(), paragraph.id()) != null)) {
                            throw new IllegalArgumentException("invalid or duplicate note marker");
                        }
                    }
                }
            });
            section.pageRegions().map((name, region) -> {
                SemanticTree.walk(region.blocks(), visit -> ids.add(visit.block().id())); return region;
            });
        }
        for (var note : notes) {
            ExecutionContext.checkpoint();
            if (!ids.add(note.id())) { throw new IllegalArgumentException("note IDs must be globally unique"); }
            if (!parents.contains(note.paragraphId())) { throw new IllegalArgumentException("note requires a top-level body paragraph, list or caption anchor"); }
            String owner = markers.remove(note.id());
            if (owner != null && !owner.equals(note.paragraphId())) { throw new IllegalArgumentException("note marker owner mismatch"); }
        }
        if (!markers.isEmpty()) { throw new IllegalArgumentException("note marker has no supplied note"); }
    }
}
