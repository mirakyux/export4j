// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Immutable ordered sections; block IDs are globally unique, including across sections. */
public record SectionedDocument(DocumentMetadata metadata, List<Section> sections) {
    /** Task-confined builder; all section and block IDs remain globally unique. */
    public static Builder builder() { return new Builder(); }
    public static final class Builder {
        private DocumentMetadata metadata = DocumentMetadata.EMPTY;
        private final java.util.ArrayList<Section> sections = new java.util.ArrayList<>();
        private Builder() { }
        public Builder metadata(DocumentMetadata value) { metadata = Objects.requireNonNull(value, "metadata"); return this; }
        public Builder section(Section value) { sections.add(Objects.requireNonNull(value, "section")); return this; }
        public Builder section(String id, java.util.function.Consumer<Section.Builder> configure) {
            var value = Section.builder(id); configure.accept(value); return section(value.build());
        }
        public SectionedDocument build() { return new SectionedDocument(metadata, sections); }
    }
    public SectionedDocument {
        Objects.requireNonNull(metadata, "metadata");
        sections = List.copyOf(sections);
        var ids = new HashSet<String>();
        if (sections.isEmpty() || sections.stream().anyMatch(section -> !ids.add(section.id()))) {
            throw new IllegalArgumentException("sections must be nonempty and uniquely named");
        }
        new Document(metadata, sections.getFirst().pageSettings(), sections.stream().flatMap(section -> section.blocks().stream()).toList());
    }
}
