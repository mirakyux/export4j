// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BlockCellModelTest {
    private static final CellFormat CELL = CellFormat.of(12);
    private static final ParagraphFormat PARAGRAPH = ParagraphFormat.of(12);

    @Test void allLegacyConstructorsRemainInlineAndTheNewRecordShapeIsExplicit() {
        var run = new InlineText("text", InlineStyle.of(12));
        var values = List.of(new TableCell("text", CELL), new TableCell("text", CELL, List.of(run)),
                new TableCell("text", CELL, List.of(run), List.of()),
                new TableCell("text", CELL, List.of(run), List.of(), List.of()),
                new TableCell("text", CELL, List.of(run), List.of(), List.of(), 2),
                new TableCell("text", CELL, List.of(run), List.of(), List.of(), 2, 3));
        for (var value : values) {
            assertFalse(value.hasBlocks());
            assertEquals("text", value.text());
            assertTrue(value.blocks().isEmpty());
            assertEquals(value, value.toBuilder().build());
        }
        assertEquals(8, TableCell.class.getRecordComponents().length);
        assertEquals(3, values.getLast().rowSpan());
    }

    @Test void nonemptyBlockSnapshotsRetainOriginalParagraphsAndRejectLegacyViews() {
        var paragraph = new Paragraph("leaf", "", PARAGRAPH);
        var source = new ArrayList<Block>(List.of(paragraph));
        var builder = TableCell.builder().blocks(source).columnSpan(2).rowSpan(3);
        source.clear();
        var cell = builder.build();
        assertTrue(cell.hasBlocks());
        assertEquals(List.of(paragraph), cell.blocks());
        assertSame(paragraph, cell.blocks().getFirst());
        assertThrows(UnsupportedOperationException.class, () -> cell.blocks().add(paragraph));
        assertThrows(IllegalStateException.class, cell::text);
        assertThrows(IllegalStateException.class, cell::inlines);
        assertThrows(IllegalStateException.class, cell::images);
        assertThrows(IllegalStateException.class, cell::fields);
        assertEquals(cell, cell.toBuilder().build());
        assertNotSame(cell.blocks(), source);
        assertThrows(IllegalArgumentException.class, () -> TableCell.builder().blocks(List.of()));
    }

    @Test void builderContentModesReplaceEachOtherAndNestedCallbacksAreSnapshotted() {
        var retained = new AtomicReference<Table.Builder>();
        var builder = TableCell.builder().text("discarded").format(CellFormat.builder().fontSize(10).lineHeight(17).build())
                .paragraph("before", "First").table("inner", table -> {
                    table.row("Initial"); retained.set(table);
                }).paragraph(new Paragraph("after", "Last", PARAGRAPH));
        var cell = builder.build();
        retained.get().row("Later");
        assertEquals(List.of("before", "inner", "after"), cell.blocks().stream().map(Block::id).toList());
        assertEquals(1, ((Table) cell.blocks().get(1)).rows().size());
        assertEquals(17, ((Paragraph) cell.blocks().getFirst()).format().lineHeight());
        assertEquals("replacement", cell.toBuilder().text("replacement").build().text());
        assertFalse(cell.toBuilder().content(List.of(new InlineText("inline", InlineStyle.of(12)))).build().hasBlocks());
        assertEquals(3, cell.blocks().size());
    }

    @Test void canonicalConstructorRejectsMixedContentInsteadOfHidingEitherBranch() {
        var blocks = List.<Block>of(new Paragraph("leaf", "Leaf", PARAGRAPH));
        assertThrows(IllegalArgumentException.class,
                () -> new TableCell("hidden", CELL, List.of(), List.of(), List.of(), 1, 1, blocks));
        assertThrows(IllegalArgumentException.class,
                () -> new TableCell("", CELL, List.of(new InlineText("", InlineStyle.of(12))), List.of(), List.of(), 1, 1, blocks));
    }

    @Test void cellBlocksRejectUnknownTypesPaginationFlagsAndNoteMarkers() {
        for (Block block : List.of(new PageBreak("break"), new Heading("heading", "Heading", 1), (Block) () -> "custom")) {
            assertThrows(IllegalArgumentException.class, () -> TableCell.builder().blocks(List.of(block)).build());
        }
        for (var pagination : List.of(PaginationPolicy.builder().pageBreakBefore(true).build(),
                PaginationPolicy.builder().keepWithNext(true).build())) {
            var paragraph = new Paragraph("leaf", "Leaf", PARAGRAPH.withPagination(pagination));
            assertThrows(IllegalArgumentException.class, () -> TableCell.builder().blocks(List.of(paragraph)).build());
        }
        var marker = Paragraph.mixed("leaf", List.of(new InlineField(InlineField.Kind.NOTE_MARKER,
                "note", 10, InlineStyle.of(12))), PARAGRAPH);
        assertThrows(IllegalArgumentException.class, () -> TableCell.builder().blocks(List.of(marker)).build());
    }

    @Test void allowsTwoTableLevelsAndRejectsAnAdditionalOuterTable() {
        var inner = table("inner", TableCell.builder().paragraph("inner-leaf", "Leaf").build());
        var outer = table("outer", TableCell.builder().paragraph("before", "Before")
                .blocks(List.of(new Paragraph("before", "Before", PARAGRAPH), inner)).build());
        assertTrue(inner.hasBlockCells());
        assertTrue(outer.hasBlockCells());
        assertThrows(IllegalArgumentException.class, () -> TableCell.builder().blocks(List.of(outer)).build());
        assertFalse(table("plain", new TableCell("Text", CELL)).hasBlockCells());
    }

    @Test void documentAndSectionsRejectAllDescendantIdCollisionsIncludingRepeatedInstances() {
        var child = table("inner", TableCell.builder().paragraph("leaf", "Leaf").build());
        var first = table("outer", TableCell.builder().blocks(List.of(child)).build());
        assertThrows(IllegalArgumentException.class, () -> Document.builder().add(first).paragraph("leaf", "Conflict").build());
        var shared = Table.builder("shared").row(TableCell.builder().blocks(List.of(child)).build(),
                TableCell.builder().blocks(List.of(child)).build()).build();
        assertThrows(IllegalArgumentException.class, () -> Document.builder().add(shared).build());
        assertThrows(IllegalArgumentException.class, () -> SectionedDocument.builder()
                .section(Section.builder("one").add(first).build())
                .section(Section.builder("two").add(new Paragraph("leaf", "Conflict", PARAGRAPH)).build()).build());
    }

    @Test void automaticRootIdsReserveExplicitDescendantIds() {
        var table = table("outer", TableCell.builder().paragraph("auto-1", "Leaf").build());
        var document = Document.builder().add(table).paragraph("Root").build();
        assertEquals("auto-2", document.blocks().getLast().id());
    }

    @Test void sharedPreorderRetainsRootIndexesAndPhysicalAnchorPathsWithoutCoveredDuplicates() {
        var inner = table("inner", TableCell.builder().paragraph("nested", "Nested").build());
        var merged = TableCell.builder().blocks(List.of(new Paragraph("before", "Before", PARAGRAPH), inner,
                new Paragraph("after", "After", PARAGRAPH))).columnSpan(2).rowSpan(2).build();
        var outer = Table.builder("outer").row(merged, new TableCell("side", CELL))
                .row(new TableCell("tail", CELL)).build();
        var visits = new ArrayList<SemanticTree.Visit>();
        SemanticTree.walk(List.of(new Paragraph("root", "Root", PARAGRAPH), outer), visits::add);
        assertEquals(List.of("root", "outer", "before", "inner", "nested", "after"),
                visits.stream().map(visit -> visit.block().id()).toList());
        assertEquals(List.of(0, 1, 1, 1, 1, 1), visits.stream().map(SemanticTree.Visit::rootBlockIndex).toList());
        assertEquals(List.of(new CellLocation("outer", 0, 0, 1), new CellLocation("inner", 0, 0, 0)),
                visits.get(4).cellPath());
        assertThrows(UnsupportedOperationException.class, () -> visits.get(4).cellPath().clear());
        var repeated = new ArrayList<SemanticTree.Visit>();
        SemanticTree.walk(List.of(inner, inner), repeated::add);
        assertEquals(List.of("inner", "nested", "inner", "nested"), repeated.stream().map(v -> v.block().id()).toList());
    }

    @Test void cellLocationsAndVisitPathsValidateAndSnapshotAndLocationsSerialize() throws Exception {
        for (int[] indexes : List.of(new int[] {-1, 0, 0}, new int[] {0, -1, 0}, new int[] {0, 0, -2})) {
            assertThrows(IllegalArgumentException.class, () -> new CellLocation("table", indexes[0], indexes[1], indexes[2]));
        }
        var location = new CellLocation("table", 1, 2, -1);
        var path = new ArrayList<>(List.of(location));
        var visit = new SemanticTree.Visit(new Paragraph("leaf", "Leaf", PARAGRAPH), 0, path);
        path.clear();
        assertEquals(List.of(location), visit.cellPath());
        var bytes = new ByteArrayOutputStream();
        try (var output = new ObjectOutputStream(bytes)) { output.writeObject(location); }
        try (var input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertEquals(location, input.readObject());
        }
    }

    @Test void flatCoveredTablesDoNotPrepareAnUnnecessaryGridDuringTraversal() throws IOException {
        int rowCount = 1024;
        var source = Table.builder("flat").row(TableCell.builder().text("Covered").rowSpan(rowCount).build());
        for (int index = 1; index < rowCount; index++) { source.coveredRow(); }
        var table = source.build();
        var checks = new AtomicInteger();
        var visits = new ArrayList<SemanticTree.Visit>();
        ExecutionContext.execute(ExecutionOptions.builder().cancelled(() -> checks.incrementAndGet() > rowCount + 10).build(),
                () -> { SemanticTree.walk(table, visits::add); return true; });
        assertEquals(List.of("flat"), visits.stream().map(visit -> visit.block().id()).toList());
        assertTrue(checks.get() <= rowCount + 10);
    }

    @Test void sharedTraversalCooperatesWithCancellationAndRestoresTheOuterExecutionScope() {
        var children = java.util.stream.IntStream.range(0, 100)
                .<Block>mapToObj(index -> new Paragraph("leaf-" + index, "Leaf", PARAGRAPH)).toList();
        var source = table("outer", TableCell.builder().blocks(children).build());
        var checks = new AtomicInteger();
        var failure = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(
                ExecutionOptions.builder().cancelled(() -> checks.incrementAndGet() >= 12).build(), () -> {
                    SemanticTree.walk(source, ignored -> { }); return true;
                }));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code());
        ExecutionContext.checkpoint();
        var count = new AtomicInteger();
        SemanticTree.walk(source, ignored -> count.incrementAndGet());
        assertEquals(101, count.get());
    }

    @Test void streamingHeadersRejectBlockContentAtTheirPublicEntryPoint() {
        var row = new TableRow(List.of(TableCell.builder().paragraph("leaf", "Leaf").build()));
        assertThrows(IllegalArgumentException.class, () -> StreamingTable.builder("stream")
                .columns(100).header(row).build());
    }

    private static Table table(String id, TableCell cell) { return Table.builder(id).row(cell).build(); }
}
