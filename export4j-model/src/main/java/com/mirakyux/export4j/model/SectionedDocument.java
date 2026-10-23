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
        var blocks = new java.util.ArrayList<Block>();
        var headers = new HashSet<PageRegion>();
        var footers = new HashSet<PageRegion>();
        var backgrounds = new HashSet<PageRegion>(); var foregrounds = new HashSet<PageRegion>();
        for (var section : sections) {
            blocks.addAll(section.blocks());
            for (var band : section.pageRegions().headers()) { if (headers.add(band)) { blocks.addAll(band.blocks()); } }
            for (var band : section.pageRegions().footers()) { if (footers.add(band)) { blocks.addAll(band.blocks()); } }
            for (var band : section.pageRegions().backgrounds()) { if (backgrounds.add(band)) { blocks.addAll(band.blocks()); } }
            for (var band : section.pageRegions().foregrounds()) { if (foregrounds.add(band)) { blocks.addAll(band.blocks()); } }
        }
        SemanticTree.validateUniqueIds(blocks);
    }
}
