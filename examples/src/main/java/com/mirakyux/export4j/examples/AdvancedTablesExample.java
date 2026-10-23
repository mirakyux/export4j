// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.StreamingTable;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.TableRowSource;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Automatic-width merged owners and complete-line continuation of a single-pass mixed row. */
public final class AdvancedTablesExample {
    private static final Path OUTPUT = Path.of("examples/build/advanced-tables");
    private static final CellFormat FORMAT = CellFormat.builder().fontSize(10).lineHeight(18).build();
    private AdvancedTablesExample() { }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Regular.ttf")))).build();
        var table = new Table("auto-spans", List.of(new ColumnWidth.Fixed(40), new ColumnWidth.Auto(20, 80),
                new ColumnWidth.Auto(20, 80), new ColumnWidth.Fraction(1)), List.of(
                new TableRow(List.of(cell("AUTO-SPAN HEADER", 4))),
                new TableRow(List.of(cell("R01", 1), cell("AUTOMATIC MERGED", 2).toBuilder().rowSpan(2).build(), cell("T-01", 1))),
                new TableRow(List.of(cell("R02", 1), cell("T-02", 1))),
                new TableRow(List.of(cell("R03", 1), cell("A", 1), cell("B", 1), cell("T-03", 1)))),
                1, new TableFormat(PageMargins.uniform(3), 0, 0));
        for (int width : List.of(340, 150)) {
            // The narrow sample reserves an explicit tail width; Fraction has no content minimum.
            var configured = width == 150 ? new Table(table.id(), List.of(table.columns().get(0), table.columns().get(1),
                    table.columns().get(2), new ColumnWidth.Fixed(30)), table.rows(), table.headerRows(), table.format()) : table;
            var source = new Document(DocumentMetadata.EMPTY, PageSettings.builder().size(width, 240).margins(20).build(), List.of(configured));
            engine.export(source).backend(new PdfBackend()).to(OUTPUT.resolve("auto-" + width + ".pdf"));
            if (width == 340) {
                try (var out = Files.newOutputStream(OUTPUT.resolve("auto.docx"))) { new DocxAdapter().write(source, out); }
            }
        }
        streaming(engine);
    }

    private static TableCell cell(String text, int span) {
        return TableCell.builder().text(text).format(FORMAT).columnSpan(span).build();
    }

    private static void streaming(DocumentEngine engine) throws IOException {
        var raster = new BufferedImage(12, 8, BufferedImage.TYPE_INT_RGB);
        var bytes = new ByteArrayOutputStream();
        try {
            for (int y = 0; y < 8; y++) { for (int x = 0; x < 12; x++) { raster.setRGB(x, y, 0x228866); } }
            if (!javax.imageio.ImageIO.write(raster, "png", bytes)) { throw new IOException("sample encoder unavailable"); }
        } finally { raster.flush(); }
        var picture = new InlineImage(ImageResource.load(bytes.toByteArray()), 12, 8, 0, "Single source picture", "https://example.com/once");
        var spec = StreamingTable.builder("split").columns(68, 100, 120).padding(3).allowRowSplit(true)
                .pageSettings(PageSettings.builder().size(320, 160).margins(16).build())
                .header(new TableRow(List.of(cell("SPLIT HEADER", 3)))).build();
        String lines = IntStream.rangeClosed(1, 11).mapToObj(index -> String.format(Locale.ROOT, "LINE-%02d", index)).collect(Collectors.joining("\n"));
        int[] reads = {0}, closes = {0};
        var readsAtPages = new ArrayList<Integer>();
        var backend = new com.mirakyux.export4j.graphics.PageBackend() {
            @Override public com.mirakyux.export4j.graphics.PageWriter open(DocumentMetadata metadata, java.io.OutputStream output) throws IOException {
                var writer = new PdfBackend().open(metadata, output);
                return new com.mirakyux.export4j.graphics.PageWriter() {
                    @Override public void accept(com.mirakyux.export4j.graphics.DisplayPage page) throws IOException { accept(page, List.of()); }
                    @Override public void accept(com.mirakyux.export4j.graphics.DisplayPage page,
                            List<com.mirakyux.export4j.graphics.DocumentExtras.ExternalLink> links) throws IOException {
                        readsAtPages.add(reads[0]); writer.accept(page, links);
                    }
                    @Override public void finish() throws IOException { writer.finish(); }
                    @Override public void close() throws IOException { writer.close(); }
                };
            }
        };
        var result = engine.export(spec, () -> new TableRowSource() {
            @Override public Optional<TableRow> next() {
                return switch (++reads[0]) {
                    case 1 -> Optional.of(new TableRow(List.of(TableCell.rich(List.of(new InlineText(lines, InlineStyle.of(10),
                            "https://example.com/lines")), FORMAT).toBuilder().columnSpan(2).build(),
                            TableCell.mixed(List.of(new InlineText("SHORT ", InlineStyle.of(10)), picture), FORMAT))));
                    case 2 -> Optional.of(new TableRow(List.of(cell("AFTER-A", 1), cell("AFTER-B", 1), cell("AFTER-C", 1))));
                    default -> Optional.empty();
                };
            }
            @Override public void close() { closes[0]++; }
        }).backend(backend).to(OUTPUT.resolve("split.pdf"));
        if (result.pages() != 3 || result.bodyRows() != 2 || result.sourceCells() != 6 || result.outputLines() != 20
                || !readsAtPages.equals(List.of(1, 1, 3)) || closes[0] != 1) {
            throw new IllegalStateException("streaming continuation counters differ");
        }
        Files.writeString(OUTPUT.resolve("split-counts.json"), "{\"pages\":3,\"rows\":2,\"sourceCells\":6,\"outputLines\":20,\"readsAtPages\":"
                + readsAtPages + ",\"closes\":" + closes[0] + "}\n");
    }
}
