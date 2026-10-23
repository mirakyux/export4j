// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.StreamingTable;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.TableRowSource;

/** Final presentation fields without rewinding the source or buffering all pages. */
public final class StreamingFieldsExample {
    private static final Path OUTPUT = Path.of("examples/build/streaming-fields");
    private static final CellFormat CELL = CellFormat.builder().fontSize(10).lineHeight(20).build();
    private static final InlineStyle STYLE = InlineStyle.of(10);
    private StreamingFieldsExample() { }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Regular.ttf")))).build();
        var image = image();
        var header = TableCell.mixed(List.of(new InlineImage(image, 12, 8, 0, "Field logo", "https://example.com/fields/logo"),
                new InlineText(" P=", STYLE), field(InlineField.Kind.PAGE), new InlineText(" T=", STYLE), field(InlineField.Kind.PAGES)), CELL);
        var table = StreamingTable.builder("streaming-fields").columns(180).padding(0).expectedPages(3)
                .pageSettings(PageSettings.builder().size(200, 100).margins(10).build()).header(row(header)).build();
        write(engine, table, java.util.stream.IntStream.rangeClosed(1, 7).mapToObj(index -> row(TableCell.mixed(List.of(
                new InlineText(String.format(Locale.ROOT, "R%02d P=", index), STYLE, "https://example.com/fields/row/" + index),
                field(InlineField.Kind.PAGE)), CELL))).toList(), "fields", 3);
        write(engine, table.toBuilder().expectedPages(2).allowRowSplit(true).build(), List.of(row(TableCell.mixed(List.of(
                new InlineText("A\nB\nC\nSPLIT P=", STYLE), field(InlineField.Kind.PAGE)), CELL))), "split", 2);
        var unknown = table.toBuilder().expectedPages(0).headerRows(List.of(row(TableCell.mixed(List.of(
                new InlineText("UNKNOWN P=", STYLE), field(InlineField.Kind.PAGE)), CELL)))).build();
        write(engine, unknown, java.util.stream.IntStream.rangeClosed(1, 4).mapToObj(index -> row(new TableCell("UNKNOWN-" + index, CELL))).toList(), "unknown", 2);
    }

    private static void write(DocumentEngine engine, StreamingTable table, List<TableRow> rows, String name, int pages) throws IOException {
        int[] reads = {0}, closes = {0};
        var summary = engine.export(table, () -> new TableRowSource() {
            @Override public Optional<TableRow> next() {
                int index = reads[0]++;
                return index < rows.size() ? Optional.of(rows.get(index)) : Optional.empty();
            }
            @Override public void close() { closes[0]++; }
        }).backend(new PdfBackend()).to(OUTPUT.resolve(name + ".pdf"));
        if (summary.pages() != pages || summary.bodyRows() != rows.size() || reads[0] != rows.size() + 1 || closes[0] != 1) {
            throw new IllegalStateException("single-pass field counters differ");
        }
        Files.writeString(OUTPUT.resolve(name + "-counts.json"), "{\"pages\":" + summary.pages() + ",\"rows\":" + summary.bodyRows()
                + ",\"sourceCells\":" + summary.sourceCells() + ",\"outputLines\":" + summary.outputLines()
                + ",\"reads\":" + reads[0] + ",\"closes\":" + closes[0] + "}\n");
    }
    private static InlineField field(InlineField.Kind kind) { return new InlineField(kind, "", 15, STYLE); }
    private static TableRow row(TableCell cell) { return new TableRow(List.of(cell)); }
    private static ImageResource image() throws IOException {
        var raster = new BufferedImage(12, 8, BufferedImage.TYPE_INT_RGB);
        try {
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 12; x++) { raster.setRGB(x, y, x < 6 ? 0x2468aa : 0xddaa33); }
            }
            var encoded = new ByteArrayOutputStream();
            if (!javax.imageio.ImageIO.write(raster, "png", encoded)) { throw new IOException("PNG encoder unavailable"); }
            return ImageResource.load(encoded.toByteArray());
        } finally { raster.flush(); }
    }
}
