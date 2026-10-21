// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingTableTest {
    private static final PageSettings PAGE = new PageSettings(new PageSize(140, 80), PageMargins.uniform(10));

    @Test void specificationSnapshotsColumnsHeadersAndBuilderDefaults() {
        var widths = new ArrayList<>(List.of(40.0, 60.0));
        var rows = new ArrayList<>(List.of(new TableRow(List.of(new TableCell("A", CellFormat.of(10)), new TableCell("B", CellFormat.of(10))))));
        var builder = StreamingTable.builder("stream").pageSettings(PAGE).columnWidths(widths).headerRows(rows).padding(2);
        var first = builder.build();
        widths.set(0, 80.0); rows.clear(); builder.columns(110).headerRows(List.of());
        assertEquals(List.of(40.0, 60.0), first.columnWidths());
        assertEquals(1, first.headerRows().size());
        assertEquals(first, first.toBuilder().build());
        assertThrows(UnsupportedOperationException.class, () -> first.columnWidths().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.headerRows().clear());
    }

    @Test void convenienceHeaderFormatAppliesOnlyToLaterHeaders() {
        var table = StreamingTable.builder("stream").pageSettings(PAGE).columns(100).padding(0)
                .cellFormat(CellFormat.of(10)).header("FIRST").cellFormat(CellFormat.of(14)).header("SECOND").build();
        assertEquals(List.of(10.0, 14.0), table.headerRows().stream().map(row -> row.cells().getFirst().format().fontSize()).toList());
        assertEquals(DocumentMetadata.EMPTY, table.metadata());
        assertFalse((Object) table instanceof Block);
    }

    @Test void columnsMayLeaveSpaceButMustLeaveTextAreaAndStayWithinContent() {
        assertNotNull(StreamingTable.builder("stream").pageSettings(PAGE).columns(100).build());
        for (double bad : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY, 121}) {
            assertThrows(IllegalArgumentException.class, () -> StreamingTable.builder("stream").pageSettings(PAGE).columns(bad).padding(0).build());
        }
        assertThrows(IllegalArgumentException.class, () -> StreamingTable.builder("stream").pageSettings(PAGE).columns(6).padding(3).build());
        assertThrows(IllegalArgumentException.class, () -> StreamingTable.builder("stream").pageSettings(PAGE).columns(100).header("A", "B").build());
        assertThrows(IllegalArgumentException.class, () -> StreamingTable.builder(" ").columns(100).build());
        assertThrows(IllegalArgumentException.class, () -> StreamingTable.builder("stream").build());
    }

    @Test void resourceFreeSourceAndFactoryCanBeLambdas() throws IOException {
        TableRowSource.Factory factory = () -> Optional::<TableRow>empty;
        try (var source = factory.open()) { assertTrue(source.next().isEmpty()); }
    }

    @Test void progressExplicitlyDistinguishesUnknownFromKnownStageTotals() {
        var unknown = new ExecutionOptions.Progress("ROWS", "table", 0, 250, -1);
        assertFalse(unknown.hasKnownTotal());
        assertEquals(-1, unknown.total());
        var known = new ExecutionOptions.Progress("DONE", "table", 0, 250, 250);
        assertTrue(known.hasKnownTotal());
        assertTrue(new ExecutionOptions.Progress("EMPTY", "", -1, 0, 0).hasKnownTotal());
        assertThrows(IllegalArgumentException.class, () -> new ExecutionOptions.Progress("ROWS", "", 0, 0, -2));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionOptions.Progress("ROWS", "", 0, -1, -1));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionOptions.Progress("ROWS", "", -2, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionOptions.Progress("ROWS", "", 0, 2, 1));
    }
}
