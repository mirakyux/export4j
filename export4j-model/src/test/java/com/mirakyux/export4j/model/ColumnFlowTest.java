// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ColumnFlowTest {
    private static final Paragraph P = new Paragraph("p", "Body", ParagraphFormat.of(10));

    @Test
    void sourceAndWeightsAreSnapshotsAndTraversalPreservesHostIndex() {
        var children = new ArrayList<Block>(List.of(P));
        var weights = new ArrayList<>(List.of(1.0, 2.0));
        var source = new ColumnFlow("columns", children, 2, 12, true, weights);
        children.clear(); weights.clear();
        assertEquals(List.of(P), source.blocks());
        assertEquals(List.of(1.0, 2.0), source.columnWeights());
        assertEquals(source, source.toBuilder().build());
        var visits = new ArrayList<SemanticTree.Visit>();
        SemanticTree.walk(List.of(new Paragraph("before", "Before", P.format()), source), visits::add);
        assertEquals(List.of("before", "columns", "p"), visits.stream().map(v -> v.block().id()).toList());
        assertEquals(List.of(0, 1, 1), visits.stream().map(SemanticTree.Visit::rootBlockIndex).toList());
        assertThrows(IllegalArgumentException.class, () -> new Document(DocumentMetadata.EMPTY, PageSettings.A4,
                List.of(source, new Paragraph("p", "Duplicate", P.format()))));
    }

    @Test
    void invalidGeometryAndUnsupportedChildrenFailBeforeLayout() {
        assertThrows(IllegalArgumentException.class, () -> new ColumnFlow("c", List.of(P), 4, 12, true));
        assertThrows(IllegalArgumentException.class, () -> new ColumnFlow("c", List.of(P), 2, Double.NaN, true));
        assertThrows(IllegalArgumentException.class, () -> new ColumnFlow("c", List.of(P), 2, 12, true, List.of(1.0)));
        assertThrows(IllegalArgumentException.class, () -> new ColumnFlow("c", List.of(P), 2, 12, true, List.of(1.0, 0.0)));
        assertThrows(IllegalArgumentException.class, () -> new ColumnFlow("c", List.of(new PageBreak("break")), 2, 12, true));
        var nested = new ColumnFlow("inner", List.of(P), 2, 12, true);
        assertThrows(IllegalArgumentException.class, () -> new ColumnFlow("outer", List.of(nested), 2, 12, true));
        var keep = new Paragraph(P.id(), P.text(), P.format().withPagination(new PaginationPolicy(true, false, 1, 1)));
        assertThrows(IllegalArgumentException.class, () -> new ColumnFlow("c", List.of(keep), 2, 12, true));
    }

    @Test
    void notesRejectColumnDescendantAnchorsAndMarkersBeforeLowering() {
        var columns = new ColumnFlow("columns", List.of(P), 2, 12, true);
        var source = new SectionedDocument(DocumentMetadata.EMPTY,
                List.of(new Section("body", PageSettings.A4, List.of(columns), 0)));
        for (var kind : Note.Kind.values()) {
            assertThrows(IllegalArgumentException.class,
                    () -> Note.validate(source, List.of(new Note("note", P.id(), "Content", kind))));
        }
        var marker = new InlineField(InlineField.Kind.NOTE_MARKER, "note", 12, InlineStyle.of(10));
        var marked = Paragraph.mixed("marked", List.of(marker), ParagraphFormat.of(10));
        var markedSource = new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("body", PageSettings.A4,
                List.of(new ColumnFlow("columns", List.of(marked), 2, 12, true)), 0)));
        assertThrows(IllegalArgumentException.class, () -> Note.validate(markedSource, List.of()));
        assertThrows(IllegalArgumentException.class, () -> Note.validate(markedSource,
                List.of(new Note("note", "marked", "Content", Note.Kind.FOOTNOTE))));
        var rootSource = new SectionedDocument(DocumentMetadata.EMPTY,
                List.of(new Section("body", PageSettings.A4, List.of(marked), 0)));
        Note.validate(rootSource, List.of(new Note("note", "marked", "Content", Note.Kind.FOOTNOTE)));
    }
}
