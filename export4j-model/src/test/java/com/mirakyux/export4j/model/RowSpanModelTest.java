// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RowSpanModelTest {
    private static final CellFormat FORMAT = CellFormat.of(12);

    @Test void legacyConstructorsAndFactoriesRetainOneRow() {
        var run = new InlineText("text", InlineStyle.of(12));
        var cells = List.of(new TableCell("text", FORMAT), new TableCell("text", FORMAT, List.of(run)),
                new TableCell("text", FORMAT, List.of(run), List.of()),
                new TableCell("text", FORMAT, List.of(run), List.of(), List.of()),
                new TableCell("text", FORMAT, List.of(run), List.of(), List.of(), 2),
                TableCell.rich(List.of(run), FORMAT), TableCell.mixed(List.of(run), FORMAT),
                TableCell.builder().text("text").build());
        assertTrue(cells.stream().allMatch(cell -> cell.rowSpan() == 1));
        assertEquals(2, cells.get(4).columnSpan());
    }

    @Test void rowSpansRequirePositiveValuesAndToBuilderKeepsBothSpansAndContent() {
        for (int span : new int[] {0, -1, Integer.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new TableCell("text", FORMAT, List.of(), List.of(), List.of(), 1, span));
            assertThrows(IllegalArgumentException.class, () -> TableCell.builder().rowSpan(span).build());
        }
        var original = TableCell.builder().content(List.of(new InlineText("A😀", InlineStyle.of(12)),
                new InlineField(InlineField.Kind.PAGE, "", 20, InlineStyle.of(12))))
                .columnSpan(3).rowSpan(2).build();
        assertEquals(original, original.toBuilder().build());
        var changed = original.toBuilder().columnSpan(2).rowSpan(3).build();
        assertEquals(3, changed.rowSpan());
        assertEquals(2, changed.columnSpan());
        assertEquals(original.fields(), changed.fields());
        assertEquals(original.inlines(), changed.inlines());
        assertEquals("A😀\ufffc", changed.text());
        assertEquals(2, original.rowSpan());
        assertEquals(2, original.toBuilder().text("replacement").build().rowSpan());
    }

    @Test void resolvesMixedRectanglesWithoutConfusingLocalAnchorsAndPhysicalColumns() {
        var a = cell("A", 2, 2);
        var b = cell("B", 1, 1);
        var c = cell("C", 1, 3);
        var d = cell("D", 1, 1);
        var table = Table.builder("rectangles").row(a, b, c).row(d)
                .row(cell("E", 3, 1)).row(cell("F", 4, 1)).build();
        var grid = table.grid();
        assertTrue(table.hasRowSpans());
        assertEquals(List.of(3, 1, 1, 1), grid.anchorRows().stream().map(List::size).toList());
        assertSame(a, grid.ownerAt(1, 0).cell());
        assertSame(a, grid.ownerAt(1, 1).cell());
        assertSame(c, grid.ownerAt(2, 3).cell());
        assertSame(d, grid.ownerAt(1, 2).cell());
        assertEquals(0, grid.ownerAt(1, 0).rowIndex());
        assertEquals(2, grid.ownerAt(1, 2).columnIndex());
        assertEquals(1, table.rows().get(1).columnCount());
        assertSame(d, table.rows().get(1).cellAtColumn(0));
        assertThrows(IndexOutOfBoundsException.class, () -> table.rows().get(1).cellAtColumn(2));
        assertTrue(grid.anchorAt(1, 0).isEmpty());
        assertEquals(grid.ownerAt(1, 2), grid.anchorAt(1, 2).orElseThrow());
        assertTrue(grid.anchorAt(0, 1).isEmpty());
    }

    @Test void fullyCoveredRowsPreserveEmptySourcesAndBoundedFirstRowInference() {
        var merged = cell("whole", 3, 3);
        var table = Table.builder("covered").row(merged).coveredRow().coveredRow().build();
        assertEquals(3, table.columns().size());
        assertEquals(0, table.rows().getLast().columnCount());
        assertEquals(List.of(), table.rows().getLast().cells());
        var grid = table.grid();
        assertEquals(List.of(1, 0, 0), grid.anchorRows().stream().map(List::size).toList());
        assertSame(merged, grid.ownerAt(2, 2).cell());
        assertTrue(grid.anchorAt(2, 2).isEmpty());
        assertThrows(IndexOutOfBoundsException.class, () -> TableRow.covered().cellAtColumn(0));
        assertThrows(IllegalArgumentException.class, () -> Table.builder("first-empty").coveredRow().build());
        assertThrows(IllegalArgumentException.class,
                () -> Table.builder("huge").row(cell("wide", Table.MAX_INFERRED_COLUMNS + 1, 2)).coveredRow().build());
    }

    @Test void headerSpansStayInsideTheRepeatedHeaderGroupAndCoveredHeadersRemainExplicit() {
        var table = Table.builder("headers").headerCells(cell("H", 2, 2)).coveredHeaderRow()
                .row("A", "B").build();
        assertEquals(2, table.headerRows());
        assertSame(table.rows().getFirst().cells().getFirst(), table.grid().ownerAt(1, 1).cell());
        assertTrue(table.rows().get(1).cells().isEmpty());
        assertThrows(IllegalStateException.class, () -> Table.builder("late").row("body").coveredHeaderRow());
        assertThrows(IllegalArgumentException.class, () -> Table.builder("crosses").headerCells(cell("H", 2, 2))
                .coveredRow().build());
        assertThrows(IllegalArgumentException.class, () -> Table.builder("empty-header").coveredHeaderRow().row("body").build());
    }

    @Test void rejectsOverlapWhenTheNextEmptyStartHasAnOccupiedSlotInsideItsRectangle() {
        assertThrows(IllegalArgumentException.class, () -> Table.builder("overlap")
                .row(cell("A", 1, 1), cell("B", 1, 2), cell("C", 1, 1))
                .row(cell("crosses-B", 2, 1)).build());
    }

    @Test void rejectsHolesExtraAnchorsExpiredCoverageAndOutOfBoundsSpans() {
        assertThrows(IllegalArgumentException.class, () -> Table.builder("hole")
                .columns(new ColumnWidth.Fixed(30), new ColumnWidth.Fixed(30))
                .row(cell("left", 1, 2)).coveredRow().build());
        assertThrows(IllegalArgumentException.class, () -> Table.builder("extra")
                .row(cell("whole", 2, 2)).row(cell("extra", 1, 1)).build());
        assertThrows(IllegalArgumentException.class, () -> Table.builder("expired")
                .row(cell("one", 1, 1)).coveredRow().build());
        assertThrows(IllegalArgumentException.class, () -> Table.builder("vertical-overflow")
                .row(cell("huge", 1, Integer.MAX_VALUE)).coveredRow().build());
        assertThrows(IllegalArgumentException.class, () -> Table.builder("horizontal-overflow")
                .columns(new ColumnWidth.Fixed(30)).row(cell("huge", Integer.MAX_VALUE, 1)).build());
    }

    @Test void gridSnapshotsAreImmutableAndQueriesRejectEveryInvalidCoordinate() {
        var sources = new ArrayList<>(List.of(new TableRow(List.of(cell("whole", 2, 2))), TableRow.covered()));
        var table = new Table("snapshot", List.of(new ColumnWidth.Fixed(30), new ColumnWidth.Fixed(30)),
                sources, 0, TableFormat.DEFAULT);
        var grid = table.grid();
        sources.clear();
        assertEquals(2, grid.anchorRows().size());
        assertThrows(UnsupportedOperationException.class, () -> grid.anchorRows().clear());
        assertThrows(UnsupportedOperationException.class, () -> grid.anchorRows().getFirst().clear());
        for (int invalid : new int[] {-1, 2, Integer.MAX_VALUE}) {
            assertThrows(IndexOutOfBoundsException.class, () -> grid.ownerAt(invalid, 0));
            assertThrows(IndexOutOfBoundsException.class, () -> grid.ownerAt(0, invalid));
            assertThrows(IndexOutOfBoundsException.class, () -> grid.anchorAt(invalid, 0));
            assertThrows(IndexOutOfBoundsException.class, () -> grid.anchorAt(0, invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> new TableGrid.Anchor(-1, 0, cell("A", 1, 1)));
    }

    @Test void modelKeepsSplitIntentForTheLayoutBoundaryAndStreamingHeadersRejectVerticalSpans() {
        var table = Table.builder("split").format(value -> value.allowRowSplit(true))
                .row(cell("whole", 2, 2)).coveredRow().build();
        assertTrue(table.format().allowRowSplit());
        assertThrows(IllegalArgumentException.class, () -> StreamingTable.builder("stream").columns(30, 30)
                .header(new TableRow(List.of(cell("A", 1, 2), cell("B", 1, 1)))).build());
        assertThrows(IllegalArgumentException.class,
                () -> StreamingTable.builder("stream").columns(30).header(TableRow.covered()).build());
        assertFalse(Table.builder("plain").row("A", "B").build().hasRowSpans());
    }

    @Test void cancellationInterruptsGridPreparationAndConstructorValidationWithoutLeakingScope() {
        var builder = Table.builder("cancel").row(cell("whole", 32, 100));
        for (int row = 1; row < 100; row++) { builder.coveredRow(); }
        var table = builder.build();
        for (boolean construct : new boolean[] {false, true}) {
            var checkpoints = new AtomicInteger();
            var entered = new AtomicBoolean();
            var returned = new AtomicBoolean();
            var options = ExecutionOptions.builder().cancelled(() -> checkpoints.incrementAndGet() >= 20).build();
            var failure = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(options, () -> {
                entered.set(true);
                if (construct) { new Table(table.id(), table.columns(), table.rows(), table.headerRows(), table.format()); }
                else { table.grid(); }
                returned.set(true);
                return true;
            }));
            assertEquals(ExecutionException.Code.CANCELLED, failure.code());
            assertTrue(entered.get());
            assertFalse(returned.get());
            ExecutionContext.checkpoint();
        }
        assertEquals("whole", table.grid().ownerAt(99, 31).cell().text());
    }

    private static TableCell cell(String text, int columns, int rows) {
        return TableCell.builder().text(text).format(FORMAT).columnSpan(columns).rowSpan(rows).build();
    }
}
