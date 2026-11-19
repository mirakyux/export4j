// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import com.mirakyux.export4j.layout.core.ComponentLimits;
import com.mirakyux.export4j.layout.core.DocumentLayoutLimits;
import com.mirakyux.export4j.layout.core.DocumentLayouter;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentNavigation;
import com.mirakyux.export4j.model.Note;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.SemanticTree;
import com.mirakyux.export4j.text.ParagraphLayouter;

/** Shared semantic preparation only; full IR and page replay retain independent ownership. */
record PreparedSemanticDocument(SemanticBlocks.Result semantic, List<Block> sourceBlocks) {
    PreparedSemanticDocument {
        Objects.requireNonNull(semantic, "semantic");
        sourceBlocks = List.copyOf(sourceBlocks);
    }

    static PreparedSemanticDocument compile(SectionedDocument source, List<Note> notes) {
        var semantic = SemanticBlocks.compile(source, notes);
        return new PreparedSemanticDocument(semantic,
                semantic.document().sections().stream().flatMap(section -> section.blocks().stream()).toList());
    }

    static Navigation navigation(SectionedDocument source, Navigation navigation) {
        if (source.navigation().equals(DocumentNavigation.NONE)) { return navigation; }
        if (!navigation.equals(Navigation.EMPTY)) { throw new IllegalStateException("portable navigation conflicts with runtime navigation"); }
        return Navigation.fromDocument(source);
    }

    SectionedDocument document() { return semantic.document(); }

    Set<String> reservedIds(List<Note> notes) {
        var ids = new LinkedHashSet<String>();
        SemanticTree.walk(sourceBlocks, visit -> ids.add(visit.block().id()));
        notes.forEach(note -> ids.add(note.id()));
        return ids;
    }

    void validateSources(PageSettings settings, List<Block> sources, List<Note> notes,
            DocumentLayoutLimits limits, ComponentLimits componentLimits) {
        new DocumentLayouter(new ParagraphLayouter(), limits, componentLimits).validate(
                Footnotes.validationSource(new Document(document().metadata(), settings, sources), notes, limits));
    }
}
