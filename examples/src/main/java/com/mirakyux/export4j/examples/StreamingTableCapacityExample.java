// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.pdf.PdfLimits;
import com.mirakyux.export4j.backend.pdf.PdfOptions;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.layout.core.DocumentLayoutLimits;
import com.mirakyux.export4j.layout.core.StreamingTableLayouter;
import com.mirakyux.export4j.layout.core.StreamingTableLimits;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.StreamingTable;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.TableRowSource;

/** One workload per independent JVM. External PowerShell sampling measures process memory and temporary disk. */
public final class StreamingTableCapacityExample {
    private static final CellFormat FORMAT = CellFormat.builder().fontSize(8).lineHeight(10).build();

    private StreamingTableCapacityExample() { }

    public static void main(String[] args) throws IOException {
        if ((args.length != 4 && args.length != 5) || !args[0].matches("layout-discard|pdf")
                || !(args.length == 4 ? args[1].matches("1000|10000|100000") : args[1].matches("[1-9][0-9]{0,5}"))
                || args.length == 5 && !args[4].matches("plain|rich")
                || !args[3].matches("[A-Za-z0-9-]{1,64}")) {
            throw new IllegalArgumentException("Expected mode, rows, output directory, run ID and optional plain/rich variant");
        }
        String mode = args[0];
        int rowCount = Integer.parseInt(args[1]);
        if (rowCount > 100_000) { throw new IllegalArgumentException("capacity rows must not exceed 100000"); }
        String variant = args.length == 5 ? args[4] : "plain";
        boolean rich = variant.equals("rich");
        Path output = Path.of(args[2]).toAbsolutePath();
        Files.createDirectories(output);
        long started = System.nanoTime();
        var heap = new HeapObservations();
        var regular = AuthoringExample.face("LiberationSans-Regular.ttf");
        var fonts = FontGroup.strict(List.of(regular));
        Map<String, FontGroup> roles = rich ? Map.of("bold", FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Bold.ttf"))),
                "accent", FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Italic.ttf")))) : Map.of();
        var specification = StreamingTable.builder("capacity").columns(70, 390, 100).cellFormat(FORMAT)
                .metadata(new DocumentMetadata("Controlled one-way table workload", "Export4j synthetic fixture"))
                .pageSettings(PageSettings.builder().size(600, 840).margins(20).build())
                .padding(PageMargins.uniform(2));
        if (rich) {
            specification.header(new TableRow(List.of(richCell(run("ROW-ID", "bold", 8, 0x224488, 0)),
                    richCell(run("DESCRIPTION", "accent", 8, 0x884422, 0)), richCell(run("VALUE", "bold", 8, 0x224488, 0)))));
        } else { specification.header("ROW-ID", "DESCRIPTION", "VALUE"); }
        var table = specification.build();
        long headerInlineRuns = rich ? table.headerRows().stream().flatMap(row -> row.cells().stream())
                .mapToLong(cell -> cell.inlines().size()).sum() : 0;
        // Header source counts once; output limits allow its repeated presentation on every page.
        var limits = StreamingTableLimits.builder().document(new DocumentLayoutLimits(1, 20_000_000,
                1_000_000, 10_000, rowCount + 1, 3, 3L * (rowCount + 1)))
                .maxCellCodePoints(256).maxRowCodePoints(768).maxCellInlines(8).maxRowInlines(16).maxDiagnostics(100).build();
        var source = new Rows(rowCount, heap, rich);
        var control = ExecutionOptions.builder().timeout(Duration.ofMinutes(10)).maxOutputBytes(512L * 1024 * 1024)
                .progress(progress -> heap.observePage(progress.pageIndex())).build();
        long beforeRows = heap.sample();
        long bodyRows;
        long pages;
        long cells;
        long codePoints;
        long lines;
        long commands = 0;
        long bytes = 0;
        if (mode.equals("layout-discard")) {
            var result = ExecutionContext.execute(control, () -> {
                try (source) {
                    return new StreamingTableLayouter().layout(table, source, fonts, roles, page -> heap.sample(),
                            diagnostic -> { throw new IllegalStateException("Unexpected capacity diagnostic"); }, limits);
                }
            });
            bodyRows = result.bodyRows(); pages = result.pages(); cells = result.sourceCells();
            codePoints = result.sourceCodePoints(); lines = result.outputLines();
        } else {
            var pdfLimits = PdfLimits.builder().maxPages(10_000).maxCommands(10_000_000)
                    .maxTextCodePoints(20_000_000).maxPathSegments(10_000_000).build();
            var backend = PdfBackend.builder().limits(pdfLimits)
                    .options(PdfOptions.storage(PdfOptions.Storage.TEMP_FILE)).build();
            var engine = DocumentEngine.builder(fonts);
            roles.forEach(engine::fontRole);
            var result = engine.build().export(table, () -> source).backend(backend)
                    .limits(limits).options(control)
                    .diagnostics(diagnostic -> { throw new IllegalStateException("Unexpected capacity diagnostic"); })
                    .to(output.resolve("table.pdf"));
            bodyRows = result.bodyRows(); pages = result.pages(); cells = result.sourceCells();
            codePoints = result.sourceCodePoints(); lines = result.outputLines();
            commands = result.commands(); bytes = result.outputBytes();
            if (bytes != Files.size(output.resolve("table.pdf"))) { throw new IllegalStateException("Output byte count differs"); }
        }
        long afterExport = heap.sample();
        if (bodyRows != rowCount || source.calls != rowCount + 1 || source.closes != 1 || cells != 3L * (rowCount + 1)) {
            throw new IllegalStateException("Capacity source counters or ownership differ");
        }
        long elapsedNanos = System.nanoTime() - started;
        var runtimeFonts = new java.util.ArrayList<String>();
        runtimeFonts.add(font("LiberationSans-Regular.ttf", regular));
        if (rich) {
            runtimeFonts.add(font("LiberationSans-Bold.ttf", roles.get("bold").fonts().getFirst()));
            runtimeFonts.add(font("LiberationSans-Italic.ttf", roles.get("accent").fonts().getFirst()));
        }
        String report = "{\"run\":\"" + args[3] + "\",\"mode\":\"" + mode + "\",\"variant\":\"" + variant
                + "\",\"pid\":" + ProcessHandle.current().pid()
                + ",\"java\":\"" + Runtime.version() + "\",\"rows\":" + bodyRows + ",\"pages\":" + pages
                + ",\"sourceCells\":" + cells + ",\"sourceCodePoints\":" + codePoints + ",\"outputLines\":" + lines
                + ",\"sourceInlineRuns\":" + (rich ? source.inlineRuns + headerInlineRuns : 0) + ",\"runtimeFonts\":[" + String.join(",", runtimeFonts) + "]"
                + ",\"commands\":" + commands + ",\"outputBytes\":" + bytes + ",\"sourceNextCalls\":" + source.calls
                + ",\"sourceCloses\":" + source.closes + ",\"elapsedMilliseconds\":" + elapsedNanos / 1_000_000.0
                + ",\"rowsPerSecond\":" + String.format(Locale.ROOT, "%.3f", bodyRows * 1_000_000_000.0 / elapsedNanos)
                + ",\"maxHeapBytes\":" + Runtime.getRuntime().maxMemory() + ",\"observedHeapSamples\":" + heap.samples
                + ",\"peakObservedHeapBytes\":" + heap.peak + ",\"heapBeforeRowsBytes\":" + beforeRows
                + ",\"heapAfterExportBytes\":" + afterExport + ",\"gcCollections\":" + gcCollections()
                + ",\"gcMilliseconds\":" + gcMilliseconds() + "}\n";
        Files.writeString(output.resolve("report.json"), report);
        System.out.print(report);
    }

    private static String font(String resource, FontFace face) {
        return "{\"resource\":\"" + resource + "\",\"sha256\":\"" + face.data().id() + "\",\"bytes\":" + face.data().byteSize() + "}";
    }

    private static InlineText run(String text, String role, double size, int color, double shift) {
        return new InlineText(text, new InlineStyle(role, size, color, shift, false, false));
    }

    private static TableCell richCell(InlineText... values) { return TableCell.builder().format(FORMAT).content(List.of(values)).build(); }

    private static long gcCollections() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean -> Math.max(0, bean.getCollectionCount())).sum();
    }

    private static long gcMilliseconds() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean -> Math.max(0, bean.getCollectionTime())).sum();
    }

    private static final class HeapObservations {
        private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        private long samples;
        private long peak;
        private int lastPage = -1;

        private void observePage(int pageIndex) {
            if (pageIndex >= 0 && pageIndex != lastPage) { lastPage = pageIndex; sample(); }
        }

        private long sample() {
            long used = memory.getHeapMemoryUsage().getUsed();
            samples++; peak = Math.max(peak, used); return used;
        }
    }

    private static final class Rows implements TableRowSource {
        private final int rowCount;
        private final HeapObservations heap;
        private int index;
        private int calls;
        private int closes;
        private final boolean rich;
        private long inlineRuns;

        private Rows(int rowCount, HeapObservations heap, boolean rich) { this.rowCount = rowCount; this.heap = heap; this.rich = rich; }

        @Override public Optional<TableRow> next() {
            calls++;
            if (index == rowCount) { return Optional.empty(); }
            if ((index & 255) == 0) { heap.sample(); }
            String number = String.format(Locale.ROOT, "%06d", ++index);
            if (rich) {
                var row = new TableRow(List.of(richCell(run("ROW-" + number, "bold", 8, 0x224488, 0)),
                        richCell(run("Synthetic record ", "", 8, 0, 0), run(number, "accent", 8, 0x884422, 0),
                                run(" from a one-way source.", "", 8, 0, 0)),
                        richCell(run("VALUE-", "", 8, 0, 0), run(number, "bold", 6, 0x224488, -1.5))));
                inlineRuns += row.cells().stream().mapToLong(cell -> cell.inlines().size()).sum();
                return Optional.of(row);
            }
            return Optional.of(new TableRow(List.of(new TableCell("ROW-" + number, FORMAT),
                    new TableCell("Synthetic record " + number + " from a one-way source.", FORMAT),
                    new TableCell("VALUE-" + number, FORMAT))));
        }

        @Override public void close() { closes++; }
    }
}
