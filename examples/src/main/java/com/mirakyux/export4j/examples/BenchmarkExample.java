// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.layout.core.DocumentLayouter;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.PaginationPolicy;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;

/** Repeatable layout workload, not a microbenchmark. Font I/O is excluded from measured runs. */
public final class BenchmarkExample {
    private BenchmarkExample() { }

    public static void main(String[] args) throws IOException {
        int count = args.length == 0 ? 5_000 : Integer.parseInt(args[0]);
        if (count < 1 || count > 10_000) {
            throw new IllegalArgumentException("benchmark blocks must be between 1 and 10000");
        }
        Path output = Path.of("examples/build/layout-benchmark.json");
        com.mirakyux.export4j.font.FontFace font;
        try (var input = BenchmarkExample.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            font = TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES);
        }
        var format = ParagraphFormat.of(10).withPagination(PaginationPolicy.BALANCED);
        var settings = new PageSettings(PageSize.A4, PageMargins.uniform(48));
        var blocks = new ArrayList<Paragraph>();
        for (int index = 0; index < count; index++) {
            blocks.add(new Paragraph("p-" + index, "Document layout benchmark " + index
                    + ": explicit fonts, deterministic wrapping, bounded pagination and complete source ranges. Repeatable workload.", format));
        }
        var layouter = new DocumentLayouter();
        layouter.layout(new Document(DocumentMetadata.EMPTY, settings, blocks.subList(0, Math.min(200, count))), List.of(font));
        List<Double> millis = new ArrayList<>();
        int pages = -1;
        for (int run = 0; run < 3; run++) {
            long started = System.nanoTime();
            var result = layouter.layout(new Document(DocumentMetadata.EMPTY, settings, blocks), List.of(font));
            millis.add((System.nanoTime() - started) / 1_000_000.0);
            if (pages >= 0 && pages != result.pages().size()) {
                throw new IllegalStateException("benchmark page count changed between runs");
            }
            pages = result.pages().size();
        }
        var sorted = millis.stream().sorted().toList();
        long peakHeap = ManagementFactory.getMemoryPoolMXBeans().stream().filter(pool -> pool.getType() == MemoryType.HEAP)
                .mapToLong(pool -> pool.getPeakUsage().getUsed()).sum();
        String report = String.format(Locale.ROOT,
                "{\n  \"blocks\": %d,\n  \"pages\": %d,\n  \"runsMillis\": [%.3f, %.3f, %.3f],\n  \"medianMillis\": %.3f,\n  \"heapPoolPeakBytes\": %d,\n  \"maxHeapBytes\": %d,\n  \"java\": \"%s\"\n}\n",
                count, pages, millis.get(0), millis.get(1), millis.get(2), sorted.get(1), peakHeap, Runtime.getRuntime().maxMemory(), System.getProperty("java.version"));
        Files.createDirectories(output.getParent());
        Files.writeString(output, report);
        System.out.print(report);
    }
}
