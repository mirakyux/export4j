// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.pdf.PdfOptions;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.PageBackend;
import com.mirakyux.export4j.graphics.PageWriter;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.StreamingTable;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.TableRowSource;

/** Reuses one engine across simultaneous workers and recovery cycles in one bounded JVM. */
public final class StreamingStabilityExample {
    private static final CellFormat FORMAT = CellFormat.builder().fontSize(8).lineHeight(10).build();
    private static final List<String> FAILURES = List.of("READ", "CLOSE", "ACCEPT", "CANCEL", "OUTPUT");
    private StreamingStabilityExample() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 5 || !args[0].matches("discard|pdf")) {
            throw new IllegalArgumentException("provide discard/pdf, workers, rounds, rows and output directory");
        }
        String mode = args[0];
        int workers = Integer.parseInt(args[1]), rounds = Integer.parseInt(args[2]), rows = Integer.parseInt(args[3]);
        if (workers < 2 || workers > 8 || rounds < 5 || rounds > 100 || rows < 100 || rows > 100_000) {
            throw new IllegalArgumentException("stability workload exceeds bounded parameters");
        }
        var output = Path.of(args[4]); Files.createDirectories(output);
        var font = AuthoringExample.face("LiberationSans-Regular.ttf");
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(font))).build();
        var spec = StreamingTable.builder("stability").columns(100, 380, 80).cellFormat(FORMAT).padding(2)
                .pageSettings(PageSettings.builder().size(600, 840).margins(20).build()).header("ROW-ID", "DESCRIPTION", "VALUE").build();
        var barrier = new CyclicBarrier(workers);
        var records = new ArrayList<String>();
        long started = System.nanoTime();
        try (var executor = Executors.newFixedThreadPool(workers)) {
            var futures = new ArrayList<java.util.concurrent.Future<List<String>>>();
            for (int worker = 0; worker < workers; worker++) {
                final int index = worker;
                futures.add(executor.submit(() -> {
                    var local = new ArrayList<String>();
                    var directory = output.resolve("worker-" + index); Files.createDirectories(directory);
                    for (int round = 0; round < rounds; round++) {
                        barrier.await(60, TimeUnit.SECONDS);
                        String failure = FAILURES.get(round % FAILURES.size());
                        String prefix = "W" + index + "-R" + round;
                        var target = directory.resolve("round-" + round + (mode.equals("pdf") ? ".pdf" : ".bin"));
                        Files.writeString(target, "OLD");
                        var cancelled = new AtomicBoolean();
                        var rejection = new IOException("controlled " + failure);
                        var failedSource = new Rows(rows, prefix, failure, rejection);
                        var options = ExecutionOptions.builder().timeout(Duration.ofSeconds(60)).cancelled(cancelled::get)
                                .maxOutputBytes(failure.equals("OUTPUT") ? 1 : 64L * 1024 * 1024).build();
                        try {
                            engine.export(spec, () -> failedSource).backend(backend(mode, failure, cancelled, rejection))
                                    .options(options).to(target);
                            throw new AssertionError("failure injection did not fail: " + failure);
                        } catch (IOException exception) {
                            if (!List.of("READ", "CLOSE", "ACCEPT").contains(failure) || exception != rejection) { throw exception; }
                        } catch (ExecutionException exception) {
                            var expected = failure.equals("CANCEL") ? ExecutionException.Code.CANCELLED : ExecutionException.Code.OUTPUT_LIMIT;
                            if (!List.of("CANCEL", "OUTPUT").contains(failure) || exception.code() != expected) { throw exception; }
                        }
                        if (failedSource.closes != 1 || !Files.readString(target).equals("OLD")) { throw new AssertionError("failed export lifecycle"); }
                        requireNoTemporary(directory);
                        var successful = new Rows(rows, prefix, "", rejection);
                        var summary = engine.export(spec, () -> successful).backend(backend(mode, "", new AtomicBoolean(), rejection))
                                .options(option -> option.timeout(Duration.ofSeconds(60))).to(target);
                        if (summary.bodyRows() != rows || successful.calls != rows + 1 || successful.closes != 1
                                || summary.sourceCells() != 3L * (rows + 1) || summary.outputBytes() != Files.size(target)) {
                            throw new AssertionError("recovered export counters");
                        }
                        requireNoTemporary(directory);
                        local.add("{\"worker\":" + index + ",\"round\":" + round + ",\"failure\":\"" + failure
                                + "\",\"failureCloses\":" + failedSource.closes + ",\"failureNextCalls\":" + failedSource.calls
                                + ",\"rows\":" + summary.bodyRows() + ",\"pages\":" + summary.pages() + ",\"sourceCloses\":" + successful.closes
                                + ",\"sourceNextCalls\":" + successful.calls + ",\"outputBytes\":" + summary.outputBytes()
                                + ",\"observedHeapBytes\":" + ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() + "}");
                    }
                    return local;
                }));
            }
            try {
                for (var future : futures) { records.addAll(future.get(4, TimeUnit.MINUTES)); }
            } finally {
                futures.forEach(future -> future.cancel(true));
                executor.shutdownNow();
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) { throw new IOException("stability workers did not stop"); }
            }
        }
        Files.writeString(output.resolve("report.json"), "{\"mode\":\"" + mode + "\",\"workers\":" + workers
                + ",\"rounds\":" + rounds + ",\"rows\":" + rows + ",\"fontSha256\":\"" + font.data().id()
                + "\",\"maxHeapBytes\":" + Runtime.getRuntime().maxMemory() + ",\"elapsedMilliseconds\":"
                + (System.nanoTime() - started) / 1_000_000.0 + ",\"records\":[" + String.join(",", records) + "]}\n");
        System.out.println("Completed " + mode + ": " + records.size() + " concurrent failure/recovery cycles");
    }

    private static void requireNoTemporary(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            if (files.anyMatch(path -> path.getFileName().toString().startsWith(".export4j-"))) { throw new AssertionError("temporary output leaked"); }
        }
    }

    private static PageBackend backend(String mode, String failure, AtomicBoolean cancelled, IOException rejection) {
        PageBackend delegate = mode.equals("pdf") ? PdfBackend.builder().options(PdfOptions.storage(PdfOptions.Storage.TEMP_FILE)).build()
                : (metadata, output) -> new Discard(output);
        return (metadata, output) -> {
            var writer = delegate.open(metadata, output);
            return new PageWriter() {
                @Override public void accept(DisplayPage page) throws IOException {
                    writer.accept(page);
                    if (failure.equals("ACCEPT")) { throw rejection; }
                    if (failure.equals("CANCEL")) { cancelled.set(true); }
                }
                @Override public void finish() throws IOException { writer.finish(); }
                @Override public void close() throws IOException { writer.close(); }
            };
        };
    }

    private static final class Discard implements PageWriter {
        private final OutputStream output;
        private Discard(OutputStream output) { this.output = output; }
        @Override public void accept(DisplayPage page) { }
        @Override public void finish() throws IOException { output.write(new byte[] {1, 2}); }
        @Override public void close() { }
    }

    private static final class Rows implements TableRowSource {
        private final int count;
        private final String prefix, failure;
        private final IOException rejection;
        private int calls, closes;
        private Rows(int count, String prefix, String failure, IOException rejection) {
            this.count = count; this.prefix = prefix; this.failure = failure; this.rejection = rejection;
        }
        @Override public Optional<TableRow> next() throws IOException {
            if (++calls == 5 && failure.equals("READ")) { throw rejection; }
            return calls > count ? Optional.empty() : Optional.of(new TableRow(List.of(
                    new TableCell(prefix + String.format(Locale.ROOT, "-ROW-%05d", calls), FORMAT),
                    new TableCell("Controlled concurrent export", FORMAT), new TableCell(Integer.toString(calls), FORMAT))));
        }
        @Override public void close() throws IOException { closes++; if (failure.equals("CLOSE")) { throw rejection; } }
    }
}
