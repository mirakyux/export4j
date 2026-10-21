// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntFunction;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.PageWriter;
import com.mirakyux.export4j.layout.core.DocumentLayoutException;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.StreamingTable;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.TableRowSource;
import com.mirakyux.export4j.text.TextDiagnostic;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingRichTextExportTest {
    private static final CellFormat FORMAT = CellFormat.builder().fontSize(10).lineHeight(20).build();
    private static final InlineStyle PLAIN = InlineStyle.of(10);
    private static FontGroup regular;
    private static FontGroup bold;
    @TempDir Path directory;

    @BeforeAll static void loadFonts() throws IOException {
        regular = load("LiberationSans-Regular.ttf"); bold = load("LiberationSans-Bold.ttf");
    }

    private static FontGroup load(String name) throws IOException {
        try (var input = StreamingRichTextExportTest.class.getResourceAsStream("/fonts/" + name)) {
            return FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }

    private static InlineStyle role(String name) { return PLAIN.toBuilder().fontRole(name).rgb(0x224488).build(); }
    private static TableRow row(InlineText... runs) { return new TableRow(List.of(TableCell.rich(List.of(runs), FORMAT))); }
    private static StreamingTable table() {
        return StreamingTable.builder("rich-export").columns(160).padding(0).cellFormat(FORMAT).header("HEAD")
                .pageSettings(new PageSettings(new PageSize(180, 100), PageMargins.uniform(10))).build();
    }

    private static final class Source implements TableRowSource {
        private final int count;
        private final IntFunction<TableRow> values;
        private final List<String> events;
        private int reads;
        private int closes;
        private IOException closeFailure;
        private Source(int count, IntFunction<TableRow> values, List<String> events) {
            this.count = count; this.values = values; this.events = events;
        }
        @Override public Optional<TableRow> next() {
            reads++; events.add("read"); return reads <= count ? Optional.of(values.apply(reads)) : Optional.empty();
        }
        @Override public void close() throws IOException {
            closes++; events.add("source-close"); if (closeFailure != null) { throw closeFailure; }
        }
    }

    private static final class Sink implements PageWriter {
        private final OutputStream output;
        private final List<String> events;
        private final List<DisplayPage> pages = new ArrayList<>();
        private IOException closeFailure;
        private IOException acceptFailure;
        private Sink(OutputStream output, List<String> events) { this.output = output; this.events = events; }
        @Override public void accept(DisplayPage page) throws IOException {
            pages.add(page); events.add("page"); if (acceptFailure != null) { throw acceptFailure; }
        }
        @Override public void finish() throws IOException { events.add("finish"); output.write(new byte[] {1, 2, 3}); }
        @Override public void close() throws IOException {
            events.add("writer-close"); output.close(); if (closeFailure != null) { throw closeFailure; }
        }
    }

    @Test void registeredRolesReachFinalPagesWhileSourceRemainsLazyAndOwned() throws IOException {
        var events = new ArrayList<String>();
        var source = new Source(5, index -> row(new InlineText("R" + index, PLAIN), new InlineText("B", role("bold"))), events);
        Sink[] sink = new Sink[1];
        var request = DocumentEngine.builder(regular).fontRole("bold", bold).build().export(table(), () -> {
            events.add("source-open"); return source;
        }).backend((metadata, output) -> { events.add("writer-open"); sink[0] = new Sink(output, events); return sink[0]; });
        assertTrue(events.isEmpty());
        var summary = request.to(new ByteArrayOutputStream());
        assertEquals(5, summary.bodyRows()); assertEquals(2, summary.pages());
        assertEquals(6, summary.sourceCells()); assertEquals(19, summary.sourceCodePoints());
        assertEquals(7, summary.outputLines()); assertEquals(3, summary.outputBytes());
        assertEquals(6, source.reads); assertEquals(1, source.closes);
        assertEquals(List.of("writer-open", "source-open"), events.subList(0, 2));
        assertEquals(List.of("source-close", "finish", "writer-close"), events.subList(events.size() - 3, events.size()));
        var marked = sink[0].pages.stream().flatMap(page -> page.commands().stream()).filter(DrawCommand.GlyphText.class::isInstance)
                .map(DrawCommand.GlyphText.class::cast).filter(value -> value.run().text().equals("B")).toList();
        assertEquals(5, marked.size());
        assertTrue(marked.stream().allMatch(value -> value.run().font().id().equals(bold.fonts().getFirst().data().id())
                && value.run().rgb() == 0x224488));
    }

    @Test void unusedRoleAllowsOldPlainSourceAndEngineRolesAreConstructionSnapshots() throws IOException {
        var builder = DocumentEngine.builder(regular).fontRole("bold", bold);
        var engine = builder.build(); builder.fontRole("late", regular);
        var events = new ArrayList<String>();
        var summary = engine.export(table(), () -> new Source(1, index -> new TableRow(List.of(new TableCell("PLAIN", FORMAT))), events))
                .backend((metadata, output) -> new Sink(output, events)).to(new ByteArrayOutputStream());
        assertEquals(1, summary.bodyRows());
        var source = new Source(1, index -> row(new InlineText("A", PLAIN), new InlineText("Z", role("late"))), events);
        var failure = assertThrows(DocumentLayoutException.class, () -> engine.export(table(), () -> source)
                .backend((metadata, output) -> new Sink(output, events)).to(new ByteArrayOutputStream()));
        assertEquals(DocumentLayoutException.Code.TEXT_LAYOUT_FAILED, failure.code());
        assertEquals(1, failure.rowIndex()); assertEquals(0, failure.columnIndex()); assertEquals(1, failure.sourceIndex());
        assertEquals(1, source.closes);
    }

    @Test void unknownRolePreservesTargetAndOriginalFailureWithBothCloseFailures() throws IOException {
        var target = directory.resolve("report.bin"); Files.writeString(target, "previous");
        var events = new ArrayList<String>();
        var source = new Source(1, index -> row(new InlineText("A", PLAIN), new InlineText("SECRET", role("missing"))), events);
        source.closeFailure = new IOException("source-close");
        Sink[] opened = new Sink[1];
        var failure = assertThrows(DocumentLayoutException.class, () -> DocumentEngine.builder(regular).fontRole("bold", bold).build()
                .export(table(), () -> source).backend((metadata, output) -> {
                    opened[0] = new Sink(output, events); opened[0].closeFailure = new IOException("writer-close"); return opened[0];
                }).to(target));
        assertEquals(DocumentLayoutException.Code.TEXT_LAYOUT_FAILED, failure.code()); assertEquals(1, failure.sourceIndex());
        assertEquals(List.of(source.closeFailure, opened[0].closeFailure), List.of(failure.getSuppressed()));
        assertFalse(failure.getMessage().contains("SECRET")); assertEquals("previous", Files.readString(target));
        assertEquals(1, source.closes); assertTrue(opened[0].pages.isEmpty()); assertFalse(events.contains("finish"));
        try (var entries = Files.list(directory)) { assertEquals(1, entries.count()); }
        ExecutionContext.checkpoint();
    }

    @Test void linkedEmptyRunCannotBeSilentlyDroppedAndTemporaryTargetIsRemoved() throws IOException {
        var target = directory.resolve("links.bin"); Files.writeString(target, "previous");
        var events = new ArrayList<String>();
        var source = new Source(1, index -> row(new InlineText("LABEL", PLAIN), new InlineText("", role("bold"), "https://example.com/")), events);
        var failure = assertThrows(DocumentLayoutException.class, () -> DocumentEngine.builder(regular).fontRole("bold", bold).build()
                .export(table(), () -> source).backend((metadata, output) -> new Sink(output, events)).to(target));
        assertEquals(DocumentLayoutException.Code.UNSUPPORTED_COMPONENT, failure.code());
        assertEquals(1, source.reads); assertEquals(1, source.closes); assertFalse(events.contains("page"));
        assertFalse(events.contains("finish")); assertEquals("previous", Files.readString(target));
        try (var entries = Files.list(directory)) { assertEquals(1, entries.count()); }
    }

    @Test void richPageDeliveryFailureStopsAfterOnePendingRowAndKeepsTarget() throws IOException {
        var target = directory.resolve("report.bin"); Files.writeString(target, "previous");
        var events = new ArrayList<String>();
        var source = new Source(100, index -> row(new InlineText("R" + index, role("bold"))), events);
        var sentinel = new IOException("page"); Sink[] sink = new Sink[1];
        assertSame(sentinel, assertThrows(IOException.class, () -> DocumentEngine.builder(regular).fontRole("bold", bold).build()
                .export(table(), () -> source).backend((metadata, output) -> {
                    sink[0] = new Sink(output, events); sink[0].acceptFailure = sentinel; return sink[0];
                }).to(target)));
        assertEquals(4, source.reads); assertEquals(1, source.closes); assertEquals(1, sink[0].pages.size());
        assertFalse(events.contains("finish")); assertEquals("previous", Files.readString(target));
        try (var entries = Files.list(directory)) { assertEquals(1, entries.count()); }
    }

    @Test void roleWarningCallbackHasUtf16AndActualPageAndCanAbortBeforePageDelivery() throws IOException {
        var spec = table().toBuilder().headerRows(List.of(row(new InlineText("H", PLAIN), new InlineText("😀", role("warn"))))).build();
        var events = new ArrayList<String>();
        var source = new Source(100, index -> row(new InlineText("R" + index, PLAIN)), events);
        var sentinel = new IllegalStateException("diagnostic");
        assertSame(sentinel, assertThrows(IllegalStateException.class, () -> DocumentEngine.builder(regular)
                .fontRole("warn", FontGroup.placeholder(bold.fonts(), '?')).build().export(spec, () -> source)
                .backend((metadata, output) -> new Sink(output, events)).diagnostics(diagnostic -> {
                    assertEquals("rich-export", diagnostic.blockId()); assertEquals("STREAMING_TABLE", diagnostic.region());
                    assertEquals(0, diagnostic.pageIndex()); assertEquals(0, diagnostic.rowIndex()); assertEquals(0, diagnostic.columnIndex());
                    assertEquals(1, diagnostic.diagnostic().sourceStart()); assertEquals(3, diagnostic.diagnostic().sourceEnd());
                    assertEquals(bold.fonts().getFirst().data().id(), diagnostic.diagnostic().fontId());
                    assertEquals(TextDiagnostic.Severity.WARNING, diagnostic.diagnostic().severity()); throw sentinel;
                }).to(new ByteArrayOutputStream())));
        assertEquals(4, source.reads); assertEquals(1, source.closes); assertFalse(events.contains("page"));
        assertFalse(events.contains("finish")); ExecutionContext.checkpoint();
    }

    @Test void runLimitConfigurationReentryOnlyChangesTheNextExport() throws IOException {
        var events = new ArrayList<String>(); var sources = new ArrayList<Source>();
        DocumentEngine.StreamingExportRequest[] request = new DocumentEngine.StreamingExportRequest[1];
        request[0] = DocumentEngine.builder(regular).fontRole("bold", bold).build().export(table(), () -> {
            request[0].limits(limits -> limits.maxCellInlines(1));
            var source = new Source(1, index -> row(new InlineText("", PLAIN), new InlineText("B", role("bold"))), events);
            sources.add(source); return source;
        }).limits(limits -> limits.maxCellInlines(2)).backend((metadata, output) -> new Sink(output, events));
        assertEquals(1, request[0].to(new ByteArrayOutputStream()).bodyRows());
        var failure = assertThrows(DocumentLayoutException.class, () -> request[0].to(new ByteArrayOutputStream()));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, failure.code());
        assertEquals(1, failure.rowIndex()); assertEquals(0, failure.columnIndex());
        assertEquals(2, sources.size()); assertTrue(sources.stream().allMatch(source -> source.closes == 1));
    }

    @Test void richCancellationClosesOwnedSourceAndRestoresScopeForNextExport() throws IOException {
        var events = new ArrayList<String>(); var cancelled = new AtomicBoolean();
        var engine = DocumentEngine.builder(regular).fontRole("bold", bold).build();
        var source = new Source(100, index -> row(new InlineText("R" + index, role("bold"))), events);
        var failure = assertThrows(ExecutionException.class, () -> engine.export(table(), () -> source)
                .backend((metadata, output) -> new PageWriter() {
                    @Override public void accept(DisplayPage page) { events.add("page"); cancelled.set(true); }
                    @Override public void finish() { events.add("finish"); }
                    @Override public void close() { events.add("writer-close"); }
                }).options(options -> options.cancelled(cancelled::get)).to(new ByteArrayOutputStream()));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code()); assertEquals(4, source.reads);
        assertEquals(1, source.closes); assertFalse(events.contains("finish")); ExecutionContext.checkpoint();
        assertEquals(1, engine.export(table(), () -> new Source(1, index -> row(new InlineText("OK", role("bold"))), events))
                .backend((metadata, output) -> new Sink(output, events)).to(new ByteArrayOutputStream()).bodyRows());
    }
}
