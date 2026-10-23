// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.PageBackend;
import com.mirakyux.export4j.graphics.PageWriter;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.StreamingTable;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.TableRowSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Single-pass fixed-column horizontal spans with alternating anchor counts and repeated headers. */
public final class StreamingColumnSpansExample {
    private static final Path OUTPUT = Path.of("examples/build/streaming-column-spans");
    private static final CellFormat FORMAT = CellFormat.builder().fontSize(10).lineHeight(16).build();
    private StreamingColumnSpansExample() { }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Regular.ttf"))))
                .fontRole("bold", FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Bold.ttf")))).build();
        var spec = StreamingTable.builder("spans").pageSettings(PageSettings.builder().size(360, 128).margins(16).build())
                .columns(68, 132, 128).padding(3)
                .header(new TableRow(List.of(cell("SPANNING TABLE", 3))))
                .header(new TableRow(List.of(cell("MERGED-HEADER", 2), cell("STATUS", 1)))).build();
        var rows = new Rows();
        var calls = new ArrayList<Integer>();
        PageBackend backend = (metadata, output) -> {
            var writer = new PdfBackend().open(metadata, output);
            return new PageWriter() {
                private int page;
                @Override public void accept(DisplayPage value) throws IOException {
                    calls.add(rows.reads);
                    int pending = Math.min(rows.reads, 5) - Math.min(2 * (page + 1), 5);
                    if (pending != (page < 2 ? 1 : 0) || rows.closes != 0) {
                        throw new IllegalStateException("single-row lookahead or source ownership changed");
                    }
                    try (var svg = Files.newOutputStream(OUTPUT.resolve("page-" + (++page) + ".svg"))) {
                        new SvgBackend().write(new DisplayDocument(metadata, List.of(value)), svg);
                    }
                    writer.accept(value);
                }
                @Override public void finish() throws IOException {
                    if (rows.closes != 1) { throw new IllegalStateException("source must close before finish"); }
                    writer.finish();
                }
                @Override public void close() throws IOException { writer.close(); }
            };
        };
        var result = engine.export(spec, () -> rows).backend(backend).to(OUTPUT.resolve("spans.pdf"));
        if (result.pages() != 3 || result.bodyRows() != 5 || result.sourceCells() != 14 || result.outputLines() != 20
                || rows.closes != 1 || !calls.equals(List.of(3, 5, 6))) {
            throw new IllegalStateException("streaming span counters differ");
        }
        Files.writeString(OUTPUT.resolve("counts.json"), "{\"pages\":" + result.pages() + ",\"bodyRows\":" + result.bodyRows()
                + ",\"sourceCells\":" + result.sourceCells() + ",\"sourceCodePoints\":" + result.sourceCodePoints()
                + ",\"outputLines\":" + result.outputLines() + ",\"readsAtPages\":" + calls + ",\"closes\":" + rows.closes + "}\n");
    }

    private static TableCell cell(String value, int span) {
        return TableCell.builder().text(value).format(FORMAT).columnSpan(span).build();
    }

    private static final class Rows implements TableRowSource {
        private int reads;
        private int closes;
        @Override public Optional<TableRow> next() {
            if (++reads > 5) { return Optional.empty(); }
            String number = String.format(Locale.ROOT, "%02d", reads);
            return Optional.of(new TableRow(switch (reads % 3) {
                case 1 -> List.of(TableCell.rich(List.of(new InlineText("ANCHOR-" + number,
                        new InlineStyle("bold", 10, 0x224488, 0, true, false)),
                        new InlineText(" rich", InlineStyle.of(10))), FORMAT).toBuilder().columnSpan(2).build(), cell("TAIL-" + number, 1));
                case 2 -> List.of(cell("COLA-" + number, 1), cell("COLB-" + number, 1), cell("COLC-" + number, 1));
                default -> List.of(cell("FULL-" + number + " A WIDE MERGED CELL", 3));
            }));
        }
        @Override public void close() { closes++; }
    }
}
