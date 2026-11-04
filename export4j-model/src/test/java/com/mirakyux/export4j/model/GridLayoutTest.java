// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GridLayoutTest {
    private static Paragraph p(String id) { return new Paragraph(id, "Body", ParagraphFormat.of(10)); }
    private static GridItem item(String id, int row, int start, int span) {
        return new GridItem(id, row, start, span, List.of(p(id + "-p")));
    }

    @Test void snapshotsAndTraversalRetainItemIdentityAndHostIndex() {
        var children = new ArrayList<Block>(List.of(p("p")));
        var item = new GridItem("item", 3, 4, 6, children);
        var items = new ArrayList<>(List.of(item));
        var grid = new GridLayout("grid", 12, 12, 8, items);
        children.clear(); items.clear();
        assertEquals(grid, grid.toBuilder().build());
        assertEquals(item, item.toBuilder().build());
        var visits = new ArrayList<SemanticTree.Visit>();
        SemanticTree.walk(List.of(p("before"), grid), visits::add);
        assertEquals(List.of("before", "grid", "item", "p"), visits.stream().map(v -> v.block().id()).toList());
        assertEquals(List.of(0, 1, 1, 1), visits.stream().map(SemanticTree.Visit::rootBlockIndex).toList());
        assertTrue(visits.stream().allMatch(v -> v.cellPath().isEmpty()));
        assertEquals(12, GridLayout.builder("defaults").add(item).build().tracks());
    }

    @Test void orderedExplicitPlacementAllowsHolesAndSkippedRowNumbers() {
        new GridLayout("grid", 12, 0, 0, List.of(item("a", 1, 1, 6), item("b", 1, 7, 6), item("c", 5, 4, 3)));
        assertThrows(IllegalArgumentException.class, () -> new GridLayout("g", 12, 0, 0,
                List.of(item("a", 1, 1, 8), item("b", 1, 8, 4))));
        assertThrows(IllegalArgumentException.class, () -> new GridLayout("g", 12, 0, 0,
                List.of(item("a", 2, 1, 2), item("b", 1, 3, 2))));
        assertThrows(IllegalArgumentException.class, () -> new GridLayout("g", 12, 0, 0,
                List.of(item("a", 1, 7, 1), item("b", 1, 1, 1))));
        assertThrows(IllegalArgumentException.class, () -> new GridLayout("g", 12, 0, 0, List.of(item("a", 1, 12, 2))));
        assertThrows(IllegalArgumentException.class, () -> new GridLayout("g", 12, 0, 0,
                List.of(item("a", 1, Integer.MAX_VALUE, Integer.MAX_VALUE))));
    }

    @Test void invalidDimensionsAndUnsupportedContainersFailExplicitly() {
        for (int tracks : List.of(0, 13)) {
            assertThrows(IllegalArgumentException.class, () -> new GridLayout("g", tracks, 0, 0, List.of(item("a", 1, 1, 1))));
        }
        for (double gap : List.of(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException.class, () -> new GridLayout("g", 12, gap, 0, List.of(item("a", 1, 1, 1))));
            assertThrows(IllegalArgumentException.class, () -> new GridLayout("g", 12, 0, gap, List.of(item("a", 1, 1, 1))));
        }
        assertThrows(IllegalArgumentException.class, () -> new GridItem("i", 0, 1, 1, List.of(p("p"))));
        assertThrows(IllegalArgumentException.class, () -> new GridItem("i", 1, 1, 0, List.of(p("p"))));
        assertThrows(IllegalArgumentException.class, () -> new GridLayout("g", 12, 0, 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new GridItem("i", 1, 1, 1, List.of()));
        var grid = new GridLayout("g", 12, 0, 0, List.of(item("a", 1, 1, 1)));
        var columns = new ColumnFlow("c", List.of(p("p")), 2, 10, true);
        for (var block : List.of(grid, columns, new PageBreak("break"))) {
            assertThrows(IllegalArgumentException.class, () -> new GridItem("i", 1, 1, 1, List.of(block)));
        }
        assertThrows(IllegalArgumentException.class, () -> new ColumnFlow("c", List.of(grid), 2, 10, true));
        assertThrows(IllegalArgumentException.class, () -> new PageRegion(100, 0, List.of(grid)));
        assertThrows(IllegalArgumentException.class, () -> TableCell.builder().blocks(List.of(grid)).build());
        var item = item("root", 1, 1, 1);
        assertThrows(IllegalArgumentException.class, () -> new Document(DocumentMetadata.EMPTY, PageSettings.A4, List.of(item)));
        assertThrows(IllegalArgumentException.class, () -> new Section("s", PageSettings.A4, List.of(item), 0));
    }

    @Test void allItemAndDescendantIdsShareTheDocumentNamespace() {
        var grid = new GridLayout("g", 12, 0, 0, List.of(new GridItem("i", 1, 1, 6, List.of(p("duplicate")))));
        assertThrows(IllegalArgumentException.class, () -> new Document(DocumentMetadata.EMPTY, PageSettings.A4,
                List.of(grid, p("duplicate"))));
        var duplicateItem = new GridLayout("g", 12, 0, 0,
                List.of(item("same", 1, 1, 6), item("same", 1, 7, 6)));
        assertThrows(IllegalArgumentException.class, () -> new Document(DocumentMetadata.EMPTY, PageSettings.A4, List.of(duplicateItem)));
    }

    @Test void notesRejectGridDescendantsAsAnchorsAndMarkers() {
        var marked = Paragraph.mixed("p", List.of(new InlineField(InlineField.Kind.NOTE_MARKER, "n", 10, InlineStyle.of(10))), ParagraphFormat.of(10));
        for (var paragraph : List.of(p("p"), marked)) {
            var grid = new GridLayout("g", 12, 0, 0, List.of(new GridItem("i", 1, 1, 12, List.of(paragraph))));
            var source = new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("s", PageSettings.A4, List.of(grid), 0)));
            assertThrows(IllegalArgumentException.class, () -> Note.validate(source, List.of(new Note("n", "p", "note", Note.Kind.FOOTNOTE))));
            if (paragraph == marked) { assertThrows(IllegalArgumentException.class, () -> Note.validate(source, List.of())); }
        }
    }
}
