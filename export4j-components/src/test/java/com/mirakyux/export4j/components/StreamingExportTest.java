// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.PageBackend;
import com.mirakyux.export4j.graphics.PageWriter;
import com.mirakyux.export4j.layout.core.PageBand;
import com.mirakyux.export4j.layout.core.PageTemplate;
import com.mirakyux.export4j.layout.core.DocumentLayoutException;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.StreamingTable;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.TableRowSource;

class StreamingExportTest {
    @TempDir Path directory;

    private static FontGroup fonts() throws IOException {
        try (var input = StreamingExportTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            return FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }
    private static CellFormat format() { return CellFormat.builder().fontSize(10).lineHeight(14).build(); }
    private static StreamingTable table() {
        return StreamingTable.builder("stream").columns(120)
                .pageSettings(new PageSettings(new PageSize(160, 100), PageMargins.uniform(10)))
                .cellFormat(format()).header("HEAD").padding(3).build();
    }

    private static final class Source implements TableRowSource {
        private final int count;
        private final List<String> events;
        private int reads;
        private int closes;
        private IOException closeFailure;
        private Source(int count, List<String> events) { this.count = count; this.events = events; }
        @Override public Optional<TableRow> next() {
            events.add("read");
            return reads++ < count ? Optional.of(new TableRow(List.of(new TableCell("ROW-" + reads, format())))) : Optional.empty();
        }
        @Override public void close() throws IOException {
            closes++; events.add("source-close"); if (closeFailure != null) { throw closeFailure; }
        }
    }

    private static final class Sink implements PageWriter {
        private final List<String> events;
        private final OutputStream output;
        private int pages;
        private long commands;
        private IOException acceptFailure;
        private IOException finishFailure;
        private IOException closeFailure;
        private Sink(List<String> events, OutputStream output) { this.events = events; this.output = output; }
        @Override public void accept(DisplayPage page) throws IOException {
            pages++; commands += page.commands().size(); events.add("page");
            if (acceptFailure != null) { throw acceptFailure; }
        }
        @Override public void finish() throws IOException {
            events.add("finish"); output.write(new byte[] {1, 2, 3});
            if (finishFailure != null) { throw finishFailure; }
        }
        @Override public void close() throws IOException {
            events.add("writer-close"); output.close(); if (closeFailure != null) { throw closeFailure; }
        }
    }

    @Test void requestIsLazySinglePassClosesBeforeFinishAndReturnsOnlyBoundedCounts() throws IOException {
        var events = new ArrayList<String>();
        var source = new Source(8, events);
        var bytes = new ByteArrayOutputStream();
        Sink[] opened = new Sink[1];
        List<ExecutionOptions.Progress> progress = new ArrayList<>();
        var request = DocumentEngine.builder(fonts()).build().export(table(), () -> {
            events.add("source-open"); return source;
        }).backend((metadata, output) -> { events.add("backend-open"); opened[0] = new Sink(events, output); return opened[0]; })
                .limits(limit -> limit.maxCellCodePoints(100)).diagnostics(diagnostic -> {
                    throw new AssertionError("unexpected warning");
                }).options(option -> option.progress(progress::add));
        assertTrue(events.isEmpty());
        var summary = request.to(bytes);
        assertEquals(8, summary.bodyRows()); assertEquals(3, summary.pages());
        assertEquals(9, summary.sourceCells()); assertEquals(44, summary.sourceCodePoints());
        assertEquals(11, summary.outputLines()); assertEquals(opened[0].commands, summary.commands());
        assertEquals(3, summary.outputBytes()); assertEquals(0, summary.diagnostics());
        assertEquals(9, source.reads); assertEquals(1, source.closes);
        assertEquals(List.of("backend-open", "source-open"), events.subList(0, 2));
        assertTrue(events.indexOf("page") < events.lastIndexOf("read"));
        assertEquals(List.of("source-close", "finish", "writer-close"), events.subList(events.size() - 3, events.size()));
        assertTrue(progress.stream().anyMatch(value -> !value.hasKnownTotal()));
        assertEquals("COMPLETE", progress.getLast().stage());
    }

    @Test void acceptedOutputCountsBytesAndDoesNotCloseBorrowedStream() throws IOException {
        var closed = new AtomicBoolean();
        var bytes = new ByteArrayOutputStream() {
            @Override public void close() { closed.set(true); }
        };
        var result = DocumentEngine.builder(fonts()).build().export(table(), () -> new Source(0, new ArrayList<>()))
                .backend((metadata, output) -> new Sink(new ArrayList<>(), output)).to(bytes);
        assertEquals(3, result.outputBytes()); assertEquals(1, result.pages());
        assertFalse(closed.get()); assertEquals(3, bytes.size());
    }

    @Test void acceptFailureStopsReadsAndPreservesBothCleanupExceptions() throws IOException {
        var events = new ArrayList<String>();
        var source = new Source(100, events);
        source.closeFailure = new IOException("source-close");
        var sink = new Sink(events, new ByteArrayOutputStream());
        sink.acceptFailure = new IOException("accept"); sink.closeFailure = new IOException("writer-close");
        var failure = assertThrows(IOException.class, () -> DocumentEngine.builder(fonts()).build()
                .export(table(), () -> source).backend((metadata, output) -> sink).to(new ByteArrayOutputStream()));
        assertSame(sink.acceptFailure, failure);
        assertEquals(List.of(source.closeFailure, sink.closeFailure), List.of(failure.getSuppressed()));
        assertEquals(4, source.reads); assertEquals(1, source.closes);
        assertEquals(1, sink.pages); assertFalse(events.contains("finish"));
    }

    @Test void sourceCloseFailurePreventsFinishAndAtomicReplacement() throws IOException {
        var target = directory.resolve("report.bin"); Files.writeString(target, "old");
        var events = new ArrayList<String>();
        var source = new Source(1, events); source.closeFailure = new IOException("source-close");
        var sink = new Sink(events, new ByteArrayOutputStream());
        assertSame(source.closeFailure, assertThrows(IOException.class, () -> DocumentEngine.builder(fonts()).build()
                .export(table(), () -> source).backend((metadata, output) -> sink).to(target)));
        assertFalse(events.contains("finish")); assertEquals("old", Files.readString(target));
        try (var entries = Files.list(directory)) { assertEquals(1, entries.count()); }
    }

    @Test void invalidMergedCoverageAfterPageDeliveryKeepsOldTargetAndClosesOnce() throws IOException {
        var target = directory.resolve("merged.bin"); Files.writeString(target, "old");
        var events = new ArrayList<String>();
        var spec = table().toBuilder().columns(40, 40, 40).headerRows(List.of()).build();
        var reads = new int[1];
        var closes = new int[1];
        var source = new TableRowSource() {
            @Override public Optional<TableRow> next() {
                int index = ++reads[0];
                return Optional.of(new TableRow(List.of(TableCell.builder().text("ROW" + index).format(format())
                        .columnSpan(index == 6 ? 4 : 3).build())));
            }
            @Override public void close() { closes[0]++; }
        };
        var sink = new Sink(events, new ByteArrayOutputStream());
        var failure = assertThrows(DocumentLayoutException.class, () -> DocumentEngine.builder(fonts()).build()
                .export(spec, () -> source).backend((metadata, output) -> sink).to(target));
        assertEquals(DocumentLayoutException.Code.INVALID_GEOMETRY, failure.code());
        assertEquals(5, failure.rowIndex()); assertEquals(6, reads[0]); assertEquals(1, closes[0]);
        assertEquals(1, sink.pages); assertFalse(events.contains("finish"));
        assertEquals("old", Files.readString(target));
        try (var entries = Files.list(directory)) { assertEquals(1, entries.count()); }
    }

    @Test void openFailuresReleaseOnlyAcquiredResources() throws IOException {
        var events = new ArrayList<String>();
        var openFailure = new IOException("open");
        var engine = DocumentEngine.builder(fonts()).build();
        assertSame(openFailure, assertThrows(IOException.class, () -> engine.export(table(), () -> {
            events.add("source-open"); return new Source(0, events);
        }).backend((metadata, output) -> { throw openFailure; }).to(new ByteArrayOutputStream())));
        assertTrue(events.isEmpty());
        var sink = new Sink(events, new ByteArrayOutputStream());
        assertSame(openFailure, assertThrows(IOException.class, () -> engine.export(table(), () -> {
            throw openFailure;
        }).backend((metadata, output) -> sink).to(new ByteArrayOutputStream())));
        assertEquals(List.of("writer-close"), events);
    }

    @Test void failureOrCancellationInsideOneSplitRowDoesNotReadTheFollowingSourceRow() throws IOException {
        var engine = DocumentEngine.builder(fonts()).build();
        var spec = table().toBuilder().allowRowSplit(true).build();
        var target = directory.resolve("split.pdf"); Files.writeString(target, "old");
        for (boolean cancellation : List.of(false, true)) {
            var events = new ArrayList<String>();
            var reads = new int[1]; var closes = new int[1];
            var cancelled = new AtomicBoolean();
            var source = new TableRowSource() {
                @Override public Optional<TableRow> next() {
                    reads[0]++;
                    return Optional.of(new TableRow(List.of(new TableCell("LONG\n".repeat(20), format()))));
                }
                @Override public void close() { closes[0]++; }
            };
            var sink = new Sink(events, new ByteArrayOutputStream());
            var rejected = new IOException("page rejected");
            if (!cancellation) { sink.acceptFailure = rejected; }
            var request = engine.export(spec, () -> source).options(options -> options.cancelled(cancelled::get))
                    .backend((metadata, output) -> new PageWriter() {
                        @Override public void accept(DisplayPage page) throws IOException { sink.accept(page); cancelled.set(true); }
                        @Override public void finish() throws IOException { sink.finish(); }
                        @Override public void close() throws IOException { sink.close(); }
                    });
            if (cancellation) {
                assertEquals(ExecutionException.Code.CANCELLED, assertThrows(ExecutionException.class, () -> request.to(target)).code());
            } else { assertSame(rejected, assertThrows(IOException.class, () -> request.to(target))); }
            assertEquals(1, reads[0]); assertEquals(1, closes[0]); assertEquals(1, sink.pages);
            assertFalse(events.contains("finish")); assertEquals("old", Files.readString(target));
            try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
        }
    }

    @Test void finishCloseAndByteBudgetFailuresKeepOldTargetAndPermitNextExport() throws IOException {
        var engine = DocumentEngine.builder(fonts()).build();
        var target = directory.resolve("report.bin"); Files.writeString(target, "old");
        for (String stage : List.of("finish", "close", "budget")) {
            var events = new ArrayList<String>();
            PageBackend backend = (metadata, output) -> {
                var sink = new Sink(events, output);
                if (stage.equals("finish")) { sink.finishFailure = new IOException("finish"); }
                if (stage.equals("close")) { sink.closeFailure = new IOException("close"); }
                return sink;
            };
            var request = engine.export(table(), () -> new Source(0, events)).backend(backend)
                    .options(options -> options.maxOutputBytes(stage.equals("budget") ? 2 : 100));
            if (stage.equals("budget")) { assertEquals(ExecutionException.Code.OUTPUT_LIMIT,
                    assertThrows(ExecutionException.class, () -> request.to(target)).code()); }
            else { assertThrows(IOException.class, () -> request.to(target)); }
            assertEquals("old", Files.readString(target));
            assertTrue(events.contains("writer-close")); ExecutionContext.checkpoint();
        }
        var summary = engine.export(table(), () -> new Source(0, new ArrayList<>()))
                .backend((metadata, output) -> new Sink(new ArrayList<>(), output)).to(target);
        assertEquals(3, summary.outputBytes()); assertEquals(3, Files.size(target));
        try (var entries = Files.list(directory)) { assertEquals(1, entries.count()); }
    }

    @Test void cancellationAfterDeliveryClosesResourcesAndRestoresEnclosingScope() throws IOException {
        var engine = DocumentEngine.builder(fonts()).build();
        var events = new ArrayList<String>();
        var cancelled = new AtomicBoolean();
        var source = new Source(100, events);
        var sink = new Sink(events, new ByteArrayOutputStream());
        var failure = assertThrows(ExecutionException.class, () -> engine.export(table(), () -> source)
                .backend((metadata, output) -> new PageWriter() {
                    @Override public void accept(DisplayPage page) throws IOException { sink.accept(page); cancelled.set(true); }
                    @Override public void finish() throws IOException { sink.finish(); }
                    @Override public void close() throws IOException { sink.close(); }
                }).options(options -> options.cancelled(cancelled::get)).to(new ByteArrayOutputStream()));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code());
        assertEquals(4, source.reads); assertEquals(1, source.closes); assertFalse(events.contains("finish"));
        ExecutionContext.execute(ExecutionOptions.DEFAULT, () -> {
            assertEquals(ExecutionException.Code.TIMEOUT, assertThrows(ExecutionException.class, () ->
                    engine.export(table(), () -> source).backend((metadata, output) -> sink)
                            .options(options -> options.timeout(Duration.ofNanos(1))).to(new ByteArrayOutputStream())).code());
            ExecutionContext.progress("OUTER", "", -1, 0, 0); return null;
        });
    }

    @Test void warningCallbackFailureStopsInputAndCarriesCellContext() throws IOException {
        var events = new ArrayList<String>();
        var strict = fonts();
        var engine = DocumentEngine.builder(FontGroup.placeholder(strict.fonts(), '?')).build();
        var source = new Source(20, events);
        var spec = table().toBuilder().headerRows(List.of(new TableRow(List.of(new TableCell("H🙂", format()))))).build();
        var marker = new IllegalArgumentException("callback");
        assertSame(marker, assertThrows(IllegalArgumentException.class, () -> engine.export(spec, () -> source)
                .backend((metadata, output) -> new Sink(events, output)).diagnostics(diagnostic -> {
                    assertEquals(0, diagnostic.pageIndex()); assertEquals(0, diagnostic.rowIndex());
                    assertEquals(0, diagnostic.columnIndex()); assertEquals("stream", diagnostic.blockId());
                    assertEquals("STREAMING_TABLE", diagnostic.region()); assertEquals(1, diagnostic.diagnostic().sourceStart());
                    throw marker;
                }).to(new ByteArrayOutputStream())));
        assertEquals(4, source.reads); assertEquals(1, source.closes);
        assertFalse(events.contains("page")); assertFalse(events.contains("finish"));
    }

    @Test void reentrantFactoryConfigurationOnlyAffectsTheNextExport() throws IOException {
        var strict = fonts();
        var engine = DocumentEngine.builder(FontGroup.placeholder(strict.fonts(), '?')).build();
        var spec = table().toBuilder().headerRows(List.of(new TableRow(List.of(new TableCell("H🙂", format()))))).build();
        var warnings = new ArrayList<ContextualDiagnostic>();
        DocumentEngine.StreamingExportRequest[] requests = new DocumentEngine.StreamingExportRequest[1];
        requests[0] = engine.export(spec, () -> {
            requests[0].limits(limits -> limits.maxCellCodePoints(1)).diagnostics(diagnostic -> {
                throw new AssertionError("configuration changed during active export");
            });
            return new Source(0, new ArrayList<>());
        }).backend((metadata, output) -> new Sink(new ArrayList<>(), output)).diagnostics(warnings::add);
        assertEquals(1, requests[0].to(new ByteArrayOutputStream()).diagnostics());
        assertEquals(1, warnings.size());
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED,
                assertThrows(DocumentLayoutException.class, () -> requests[0].to(new ByteArrayOutputStream())).code());
    }

    @Test void unsupportedEngineSettingsAndMissingBackendFailBeforeOpeningSource() throws IOException {
        var fonts = fonts();
        var opens = new AtomicBoolean();
        TableRowSource.Factory source = () -> { opens.set(true); return new Source(0, new ArrayList<>()); };
        var engine = DocumentEngine.builder(fonts).build();
        assertThrows(NullPointerException.class, () -> engine.export(table(), source).to(directory.resolve("absent.bin")));
        DocumentEngine.builder(fonts).fontRole("body", fonts).build().export(table(), source);
        assertThrows(IllegalStateException.class, () -> DocumentEngine.builder(fonts)
                .pageTemplate(PageTemplate.builder().header(PageBand.builder().height(20).build()).build())
                .build().export(table(), source));
        assertThrows(IllegalStateException.class, () -> DocumentEngine.builder(fonts)
                .navigation(Navigation.builder().bookmark("head", "stream", 1).build()).build().export(table(), source));
        assertFalse(opens.get());
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }
}
