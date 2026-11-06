// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableRowSourcesTest {
    @Test void iterableFactoryOpensLazilyAndMapsExactlyOncePerRow() throws IOException {
        var opens = new AtomicInteger(); var mapped = new AtomicInteger();
        Iterable<Integer> values = () -> { opens.incrementAndGet(); return List.of(1, 2).iterator(); };
        var factory = TableRowSources.fromIterable(values, number -> {
            mapped.incrementAndGet(); return TableRow.text("R" + number);
        });
        assertEquals(0, opens.get());
        for (int pass = 0; pass < 2; pass++) {
            try (var rows = factory.open()) {
                assertEquals("R1", rows.next().orElseThrow().cells().getFirst().text());
                assertEquals("R2", rows.next().orElseThrow().cells().getFirst().text());
                assertTrue(rows.next().isEmpty()); assertTrue(rows.next().isEmpty());
            }
        }
        assertEquals(2, opens.get()); assertEquals(4, mapped.get());
    }

    @Test void endOfInputIsPermanentAndBorrowedIteratorIsNotClosed() throws IOException {
        class Borrowed implements Iterator<TableRow>, AutoCloseable {
            int probes; boolean closed;
            @Override public boolean hasNext() { probes++; return false; }
            @Override public TableRow next() { throw new AssertionError("read after end"); }
            @Override public void close() { closed = true; }
        }
        var iterator = new Borrowed(); var rows = TableRowSources.fromIterator(iterator);
        assertTrue(rows.next().isEmpty()); assertTrue(rows.next().isEmpty());
        assertEquals(1, iterator.probes);
        rows.close(); rows.close(); assertFalse(iterator.closed);
        assertThrows(IllegalStateException.class, rows::next);
    }

    @Test void streamFactoriesReopenAndCloseEachOwnedStreamExactlyOnce() throws IOException {
        var opens = new AtomicInteger(); var closes = new AtomicInteger();
        var factory = TableRowSources.fromStreams(() -> {
            opens.incrementAndGet(); return Stream.of(TableRow.text("ONE")).onClose(closes::incrementAndGet);
        });
        assertEquals(0, opens.get());
        for (int pass = 0; pass < 2; pass++) {
            var rows = factory.open();
            try (rows) { assertEquals("ONE", rows.next().orElseThrow().cells().getFirst().text()); }
            rows.close();
        }
        assertEquals(2, opens.get()); assertEquals(2, closes.get());
    }

    @Test void mapperFailuresAndNullRowsStillAllowOwnedStreamCleanup() throws IOException {
        var closes = new AtomicInteger(); var injected = new IllegalArgumentException("mapping failure");
        try (var rows = TableRowSources.fromStream(Stream.of(1).onClose(closes::incrementAndGet), value -> { throw injected; })) {
            assertSame(injected, assertThrows(IllegalArgumentException.class, rows::next));
        }
        try (var rows = TableRowSources.fromStream(Stream.of(1).onClose(closes::incrementAndGet), value -> null)) {
            assertThrows(NullPointerException.class, rows::next);
        }
        assertEquals(2, closes.get());
    }

    @Test void streamSetupFailureClosesOwnedStreamAndPreservesSuppressedFailure() {
        var closes = new AtomicInteger(); var closeFailure = new IllegalStateException("close failure");
        var stream = Stream.of(1).onClose(() -> { closes.incrementAndGet(); throw closeFailure; });
        stream.iterator(); // Consumes the stream's only traversal, making source setup fail.
        var failure = assertThrows(IllegalStateException.class,
                () -> TableRowSources.fromStream(stream, number -> TableRow.text(number.toString())));
        assertEquals(1, closes.get()); assertSame(closeFailure, failure.getSuppressed()[0]);
    }

    @Test void invalidMapperReleasesTransferredStreamWithoutTraversal() {
        var closes = new AtomicInteger();
        assertThrows(NullPointerException.class, () -> TableRowSources.fromStream(
                Stream.of(1).onClose(closes::incrementAndGet), (Function<Integer, TableRow>) null));
        assertEquals(1, closes.get());
    }

    @Test void textRowsKeepTheirFormatAndSnapshotCallerArrays() {
        String[] values = {"A", "B"}; var format = CellFormat.of(10);
        var row = TableRow.text(format, values); values[0] = "changed";
        assertEquals(List.of("A", "B"), row.cells().stream().map(TableCell::text).toList());
        assertEquals(format, row.cells().getFirst().format());
        assertEquals(CellFormat.of(12), TableRow.text("default").cells().getFirst().format());
        assertThrows(NullPointerException.class, () -> TableRow.text("valid", null));
    }
}
