// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.StreamingTable;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.TableRowSource;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Explicit PNG/JPEG resources and passive links in a single-pass table, with repeated media headers. */
public final class StreamingMediaExample {
    private static final Path OUTPUT = Path.of("examples/build/streaming-media");
    private static final CellFormat FORMAT = CellFormat.builder().fontSize(10).lineHeight(18).build();
    private StreamingMediaExample() { }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Regular.ttf")))).build();
        var png = image("png"); var jpeg = image("jpeg");
        Files.write(OUTPUT.resolve("original.png"), png.bytes());
        Files.write(OUTPUT.resolve("original.jpg"), jpeg.bytes());
        var header = TableCell.mixed(List.of(new InlineImage(png, 16, 8, 0, "Sample logo", "https://example.com/logo"),
                new InlineText(" MEDIA HEADER", InlineStyle.of(10), "https://example.com/header")), FORMAT)
                .toBuilder().columnSpan(2).build();
        var spec = StreamingTable.builder("media").columns(80, 188).padding(3)
                .pageSettings(PageSettings.builder().size(300, 128).margins(16).build())
                .header(new TableRow(List.of(header))).build();
        int[] reads = {0}, closes = {0};
        var result = engine.export(spec, () -> new TableRowSource() {
            @Override public Optional<TableRow> next() {
                if (++reads[0] > 7) { return Optional.empty(); }
                String id = String.format(Locale.ROOT, "%02d", reads[0]);
                return Optional.of(new TableRow(List.of(new TableCell("ROW-" + id, FORMAT),
                        TableCell.mixed(List.of(new InlineText("DETAIL-" + id + " ", InlineStyle.of(10), "https://example.com/row/" + id),
                                new InlineImage(reads[0] % 2 == 0 ? jpeg : png, 12, 8, 0, "Row image " + id,
                                        "https://example.com/image/" + id)), FORMAT))));
            }
            @Override public void close() { closes[0]++; }
        }).backend(new PdfBackend()).to(OUTPUT.resolve("media.pdf"));
        if (result.pages() != 3 || result.bodyRows() != 7 || result.sourceCells() != 15 || reads[0] != 8 || closes[0] != 1) {
            throw new IllegalStateException("single-pass media counters differ");
        }
        Files.writeString(OUTPUT.resolve("counts.json"), "{\"pages\":" + result.pages() + ",\"rows\":" + result.bodyRows()
                + ",\"sourceCells\":" + result.sourceCells() + ",\"reads\":" + reads[0] + ",\"closes\":" + closes[0] + "}\n");
    }

    private static ImageResource image(String format) throws IOException {
        var raster = new BufferedImage(12, 8, BufferedImage.TYPE_INT_RGB);
        try {
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 12; x++) { raster.setRGB(x, y, x < 6 ? 0x2468aa : 0xddaa33); }
            }
            var encoded = new ByteArrayOutputStream();
            if (!javax.imageio.ImageIO.write(raster, format, encoded)) { throw new IOException("sample image encoder unavailable"); }
            return ImageResource.load(encoded.toByteArray());
        } finally { raster.flush(); }
    }
}
