// SPDX-License-Identifier: Apache-2.0
package consumer;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import com.mirakyux.export4j.backend.pdf.PdfSpoolBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.StreamingReplayPlan;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.layout.core.DocumentLayoutLimits;
import com.mirakyux.export4j.layout.core.StreamingTableLimits;
import com.mirakyux.export4j.model.*;

/** Published replay/spool API exercise and independently launched finite capacity runs. */
public final class StreamingReplayConsumer {
    private static final CellFormat FORMAT = new CellFormat(10, 14, ParagraphAlignment.LEFT);
    private StreamingReplayConsumer() { }
    public static void main(String[] args) throws Exception {
        var bytes = Files.readAllBytes(Path.of("../../assets/fonts/LiberationSans-Regular.ttf"));
        var fonts = FontGroup.strict(List.of(TrueTypeFontLoader.load(bytes)));
        Path output = Path.of(System.getProperty("consumer.outputDirectory")).resolve("streaming-replay"); Files.createDirectories(output);
        if (args.length >= 3 && args[0].equals("--capacity")) {
            capacity(fonts, output, Integer.parseInt(args[1]), args[2]); return;
        }
        var style = InlineStyle.of(10);
        var spec = StreamingTable.builder("replay").columns(110, 110).semanticCells(true).allowRowSpans(true).allowRowSplit(true)
                .pageSettings(new PageSettings(new PageSize(260, 240), PageMargins.uniform(20))).padding(2)
                .header(new TableRow(List.of(TableCell.mixed(List.of(new InlineText("Page ", style),
                        new InlineField(InlineField.Kind.PAGE, "", 18, style), new InlineText(" / ", style),
                        new InlineField(InlineField.Kind.PAGES, "", 18, style)), FORMAT), new TableCell("REPORT", FORMAT)))).build();
        var child = Paragraph.mixed("details-first", List.of(new InlineText("Nested details; last page ", style, "https://example.org/replay"),
                new InlineField(InlineField.Kind.REFERENCE_PAGE, "last", 25, style)), ParagraphFormat.of(10));
        var nested = Table.builder("details-table").columns(new ColumnWidth.Fraction(1))
                .format(new TableFormat(PageMargins.uniform(2), 0, 0, true)).header("DETAILS")
                .row(TableCell.builder().blocks(List.of(child, new Paragraph("details-long", java.util.stream.IntStream.rangeClosed(1, 90)
                        .mapToObj(index -> String.format(java.util.Locale.ROOT, "Nested body NEST-%03d.", index)).collect(java.util.stream.Collectors.joining(" ")),
                        ParagraphFormat.of(10)))).build()).build();
        var rows = List.of(new TableRow(List.of(TableCell.builder().format(FORMAT).rowSpan(2)
                        .content(List.of(new InlineText("OWNER ", style), new InlineField(InlineField.Kind.REFERENCE_NUMBER, "note", 22, style),
                                new InlineText("\n" + "Owner continuation\n".repeat(32), style))).build(),
                TableCell.builder().blocks(List.of(nested)).build())),
                new TableRow(List.of(new TableCell("NEXT SOURCE ROW", FORMAT))),
                new TableRow(List.of(new TableCell("LAST TARGET", FORMAT), TableCell.mixed(List.of(picture(), new InlineText(" END OF REPORT", style)), FORMAT))));
        var opens = new AtomicInteger(); var closes = new AtomicInteger();
        var plan = StreamingReplayPlan.builder().columns(List.of(new ColumnWidth.Auto(90, 120), new ColumnWidth.Fraction(1)), 2, 300_000)
                .rowTarget("last", 2, "2").blockTarget("details", "details-first", "1")
                .bookmark("Details", "details", 1).bookmark("Final target", "last", 1).contents("Contents")
                .footnote("note", 0, "This footnote is reserved once on the first connected-group page.").build();
        var summary = DocumentEngine.builder(fonts).build().exportReplay(spec, () -> {
            opens.incrementAndGet(); return new TableRowSource() {
                private int row;
                @Override public Optional<TableRow> next() { return row < rows.size() ? Optional.of(rows.get(row++)) : Optional.empty(); }
                @Override public void close() { closes.incrementAndGet(); }
            };
        }).plan(plan).backend(new PdfSpoolBackend(output)).to(output.resolve("replay.pdf"));
        if (opens.get() != 3 || closes.get() != 3 || summary.bodyRows() != 3 || summary.pages() < 4) {
            throw new IllegalStateException("replay lifecycle or source counters differ");
        }
        Files.writeString(output.resolve("counts.json"), "{\"pages\":" + summary.pages() + ",\"bodyRows\":3,\"opens\":3,\"closes\":3,\"bytes\":" + summary.outputBytes() + "}\n");
    }

    private static InlineImage picture() throws IOException {
        var pixels = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 2; y++) { for (int x = 0; x < 2; x++) { pixels.setRGB(x, y, 0x2266aa); } }
        var output = new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(pixels, "png", output); pixels.flush();
        return new InlineImage(ImageResource.load(output.toByteArray()), 8, 8, 0, "blue square", "https://example.org/replay/image");
    }

    private static void capacity(FontGroup fonts, Path output, int count, String mode) throws Exception {
        if (count <= 0 || count > 1_000_000 || !List.of("pdf", "replay", "layout", "stability").contains(mode)) {
            throw new IllegalArgumentException("invalid capacity input");
        }
        if (mode.equals("stability")) { stability(fonts, output, count); return; }
        var spec = StreamingTable.builder("capacity").columns(100, 100).padding(1).cellFormat(FORMAT).header("ID", "VALUE").build();
        var limits = StreamingTableLimits.DEFAULT.toBuilder().document(new DocumentLayoutLimits(1, 40_000_000, 4_000_000,
                100_000, count + 1, 2, 2L * (count + 1))).build();
        int[] reads = {0}, closes = {0}, opens = {0}; long[] observedHeap = {0};
        TableRowSource.Factory factory = () -> {
            opens[0]++;
            return new TableRowSource() {
                private int row;
                @Override public Optional<TableRow> next() {
                    reads[0]++; if (row == count) { return Optional.empty(); }
                    int value = row++;
                    return Optional.of(new TableRow(List.of(new TableCell("R" + value, FORMAT), new TableCell("VALUE " + value, FORMAT))));
                }
                @Override public void close() { closes[0]++; }
            };
        };
        int pages;
        long start = System.nanoTime();
        if (mode.equals("layout")) {
            try (var source = factory.open()) {
                pages = new com.mirakyux.export4j.layout.core.StreamingTableLayouter().layout(spec, source, fonts,
                        page -> sample(observedHeap), ignored -> { }, limits).pages();
            }
        } else if (mode.equals("pdf")) {
            pages = DocumentEngine.builder(fonts).build().export(spec, factory).limits(limits)
                    .options(options -> options.progress(progress -> sample(observedHeap)))
                    .backend(new PdfSpoolBackend(output)).to(output.resolve("capacity-" + mode + "-" + count + ".pdf")).pages();
        } else {
            pages = DocumentEngine.builder(fonts).build().exportReplay(spec, factory).limits(limits)
                    .options(options -> options.progress(progress -> sample(observedHeap)))
                    .backend(new PdfSpoolBackend(output)).to(output.resolve("capacity-" + mode + "-" + count + ".pdf")).pages();
        }
        long nanos = System.nanoTime() - start;
        int passes = mode.equals("replay") ? 2 : 1;
        if (opens[0] != passes || closes[0] != passes || reads[0] != passes * (count + 1)) { throw new IllegalStateException("capacity lifecycle counters differ"); }
        try (var files = Files.list(output)) {
            if (files.anyMatch(path -> path.getFileName().toString().startsWith(".export4j"))) { throw new IllegalStateException("capacity left temporary files"); }
        }
        Files.writeString(output.resolve("capacity-" + mode + "-" + count + ".json"), "{\"rows\":" + count + ",\"pages\":" + pages
                + ",\"passes\":" + passes + ",\"reads\":" + reads[0] + ",\"closes\":" + closes[0] + ",\"maxHeapBytes\":" + Runtime.getRuntime().maxMemory()
                + ",\"observedHeapBytes\":" + observedHeap[0] + ",\"elapsedNanos\":" + nanos + "}\n");
    }
    private static void sample(long[] peak) { peak[0] = Math.max(peak[0], Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()); }

    private static void stability(FontGroup fonts, Path output, int count) throws Exception {
        var engine = DocumentEngine.builder(fonts).build(); var backend = new PdfSpoolBackend(output);
        var spec = StreamingTable.builder("stability").columns(100).build();
        var closes = new AtomicInteger(); int failures = 0;
        for (int run = 0; run < 12; run++) {
            final boolean fail = run % 3 == 0; final boolean cancel = run % 3 == 1;
            int[] reads = {0}; var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
            Path target = output.resolve("stability-protected.bin"); Files.writeString(target, "previous");
            try {
                engine.export(spec, () -> new TableRowSource() {
                    @Override public Optional<TableRow> next() throws IOException {
                        int row = reads[0]++;
                        if (row == count / 2 && fail) { throw new IOException("injected source failure"); }
                        if (row == count / 2 && cancel) { cancelled.set(true); }
                        return row < count ? Optional.of(new TableRow(List.of(new TableCell("R" + row, FORMAT)))) : Optional.empty();
                    }
                    @Override public void close() { closes.incrementAndGet(); }
                }).options(options -> options.cancelled(cancelled::get)).backend(backend).to(target);
                if (fail || cancel) { throw new IllegalStateException("injected failure did not abort"); }
            } catch (IOException | com.mirakyux.export4j.model.ExecutionException expected) {
                if (!fail && !cancel) { throw expected; }
                if (!Files.readString(target).equals("previous")) { throw new IllegalStateException("failed stability run changed target"); }
                failures++;
            }
            Files.delete(target);
            try (var files = Files.list(output)) {
                if (files.anyMatch(path -> path.getFileName().toString().startsWith(".export4j"))) { throw new IllegalStateException("stability leaked temporary files"); }
            }
        }
        if (closes.get() != 12 || failures != 8) { throw new IllegalStateException("stability exit counts differ"); }
        Files.writeString(output.resolve("stability.json"), "{\"runs\":12,\"failures\":8,\"successfulRecoveryRuns\":4,\"closes\":12,\"temporaryFiles\":0}\n");
    }
}
