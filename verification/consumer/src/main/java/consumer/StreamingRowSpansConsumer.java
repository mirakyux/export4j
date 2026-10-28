// SPDX-License-Identifier: Apache-2.0
package consumer;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.layout.core.DocumentLayoutException;
import com.mirakyux.export4j.layout.core.DocumentLayoutLimits;
import com.mirakyux.export4j.layout.core.StreamingTableLayouter;
import com.mirakyux.export4j.layout.core.StreamingTableLimits;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.StreamingTable;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.TableRowSource;

/** Published API verification of bounded body-row groups, page fields and atomic failure. */
public final class StreamingRowSpansConsumer {
    private static final CellFormat FORMAT = CellFormat.of(10);
    private StreamingRowSpansConsumer() { }

    public static void main(String[] args) throws Exception {
        var font = TrueTypeFontLoader.load(Files.readAllBytes(Path.of("../../assets/fonts/LiberationSans-Regular.ttf")));
        var fonts = FontGroup.strict(List.of(font));
        Path output = Path.of(System.getProperty("consumer.outputDirectory")).resolve("streaming-row-spans");
        Files.createDirectories(output);
        if (args.length == 2 && args[0].equals("--capacity")) { capacity(fonts, output, Integer.parseInt(args[1])); return; }
        var engine = DocumentEngine.builder(fonts).build();
        var spec = StreamingTable.builder("groups").columns(50, 50).padding(0)
                .pageSettings(new PageSettings(new PageSize(120, 80), PageMargins.uniform(10)))
                .cellFormat(FORMAT).header("LEFT", "RIGHT").allowRowSpans(true).build();
        var style = InlineStyle.of(10);
        var owner = TableCell.rich(List.of(new InlineText("OWNER", style, "https://example.com/groups/owner")), FORMAT)
                .toBuilder().rowSpan(2).build();
        var pageField = new InlineField(InlineField.Kind.PAGE, "", 8, style);
        var fieldCell = TableCell.mixed(List.of(new InlineText("B3 P=", style), pageField), FORMAT);
        var pixels = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 2; y++) { for (int x = 0; x < 2; x++) { pixels.setRGB(x, y, 0x2266aa); } }
        var png = new ByteArrayOutputStream(); ImageIO.write(pixels, "png", png); pixels.flush();
        var picture = new InlineImage(ImageResource.load(png.toByteArray()), 8, 8, 0, "blue square", "https://example.com/groups/image");
        var full = TableCell.mixed(List.of(new InlineText("FULL P=", style), pageField, new InlineText(" ", style), picture), FORMAT)
                .toBuilder().columnSpan(2).rowSpan(2).build();
        var source = new Rows(row("PRE-A", "A"), row("PRE-B", "B"), row(owner, fieldCell), row(cell("B4", 1, 1)),
                row("A5", "B5"), row(full), TableRow.covered());
        var summary = engine.export(spec, () -> source).limits(limits -> limits.maxBufferedRows(3).maxBufferedCodePoints(100))
                .backend(new PdfBackend()).to(output.resolve("groups.pdf"));
        if (summary.pages() != 3 || summary.bodyRows() != 7 || summary.sourceCells() != 12 || summary.outputLines() != 16
                || source.reads != 8 || source.closes != 1) { throw new IllegalStateException("group source or presentation counters differ"); }
        var transitive = new Rows(row(cell("L", 2, 1), cell("R1", 1, 1)), row(cell("R2", 2, 1)), row(cell("L3", 1, 1)));
        var chained = engine.export(spec, () -> transitive).backend(new PdfBackend()).to(output.resolve("transitive.pdf"));
        if (chained.pages() != 1 || chained.bodyRows() != 3 || transitive.closes != 1) {
            throw new IllegalStateException("transitive group ownership differs");
        }
        Path target = output.resolve("protected.bin"); Files.writeString(target, "old-target");
        var incomplete = new Rows(row("A", "B"), row("C", "D"), row("E", "F"), row("G", "H"), row(cell("END", 2, 2)));
        boolean rejected = false;
        try { engine.export(spec, () -> incomplete).backend(new PdfBackend()).to(target); }
        catch (DocumentLayoutException expected) { rejected = expected.code() == DocumentLayoutException.Code.INVALID_GEOMETRY; }
        if (!rejected || incomplete.closes != 1 || !Files.readString(target).equals("old-target")) {
            throw new IllegalStateException("late incomplete group did not preserve target and source ownership");
        }
        Files.delete(target);
        try (var paths = Files.list(output)) {
            if (paths.anyMatch(path -> path.getFileName().toString().endsWith(".tmp"))) {
                throw new IllegalStateException("atomic group failure left temporary output");
            }
        }
        Files.writeString(output.resolve("counts.json"), "{\"pages\":3,\"rows\":7,\"sourceCells\":12,\"outputLines\":16,\"reads\":8,\"closes\":1,\"lateFailureProtected\":true}\n");
    }

    private static void capacity(FontGroup fonts, Path output, int count) throws IOException {
        if (count <= 0 || count > 1_000_000) { throw new IllegalArgumentException("capacity row count out of range"); }
        var spec = StreamingTable.builder("capacity-groups").columns(100, 100).padding(1)
                .cellFormat(FORMAT).header("GROUP", "ROW").allowRowSpans(true).build();
        int[] reads = new int[1]; int[] closes = new int[1]; int[] emitted = new int[1];
        TableRowSource source = new TableRowSource() {
            @Override public Optional<TableRow> next() {
                int index = reads[0]++;
                if (index == count) { return Optional.empty(); }
                return Optional.of(index % 3 == 0
                        ? row(cell("G" + index, Math.min(3, count - index), 1), cell("R" + index, 1, 1))
                        : row(cell("R" + index, 1, 1)));
            }
            @Override public void close() { closes[0]++; }
        };
        var limits = StreamingTableLimits.DEFAULT.toBuilder().document(new DocumentLayoutLimits(1, 20_000_000,
                2_000_000, 100_000, count + 1, 2, 2L * (count + 1))).maxBufferedRows(3).maxBufferedCodePoints(1000).build();
        StreamingTableLayouter.Summary result;
        long started = System.nanoTime();
        try (source) {
            result = new StreamingTableLayouter().layout(spec, source, fonts, page -> {
                var table = (TableFragment) page.elements().getFirst();
                int last = table.rows().getLast().rowIndex();
                if (reads[0] > last + 3) { throw new IllegalStateException("source read beyond one connected group"); }
                emitted[0]++;
            }, ignored -> { }, limits);
        }
        if (result.bodyRows() != count || reads[0] != count + 1 || closes[0] != 1 || emitted[0] != result.pages()) {
            throw new IllegalStateException("capacity counters differ");
        }
        long elapsed = System.nanoTime() - started;
        Files.writeString(output.resolve("capacity-" + count + ".json"), "{\"rows\":" + count + ",\"pages\":" + result.pages()
                + ",\"reads\":" + reads[0] + ",\"closes\":1,\"maxBufferedRows\":3,\"maxHeapBytes\":"
                + Runtime.getRuntime().maxMemory() + ",\"elapsedNanos\":" + elapsed + "}\n");
    }

    private static TableCell cell(String value, int rows, int columns) {
        return TableCell.builder().text(value).format(FORMAT).rowSpan(rows).columnSpan(columns).build();
    }
    private static TableRow row(TableCell... cells) { return new TableRow(List.of(cells)); }
    private static TableRow row(String left, String right) { return row(cell(left, 1, 1), cell(right, 1, 1)); }
    private static final class Rows implements TableRowSource {
        private final List<TableRow> rows;
        private int reads;
        private int closes;
        private Rows(TableRow... rows) { this.rows = List.of(rows); }
        @Override public Optional<TableRow> next() {
            int index = reads++;
            return index < rows.size() ? Optional.of(rows.get(index)) : Optional.empty();
        }
        @Override public void close() { closes++; }
    }
}
