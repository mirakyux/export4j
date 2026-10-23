// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.StreamingTable;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.TableRowSource;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

final class StreamingColumnSpansConsumer {
    private StreamingColumnSpansConsumer() { }
    static void verify(DocumentEngine engine, Path output) throws IOException {
        var format = CellFormat.builder().fontSize(10).lineHeight(16).build();
        var spec = StreamingTable.builder("caller-spans").columns(50, 60, 70).padding(2)
                .pageSettings(PageSettings.builder().size(220, 160).margins(20).build())
                .header(new TableRow(List.of(TableCell.builder().text("CALLER-HEADER").format(format).columnSpan(3).build()))).build();
        TableRowSource.Factory source = () -> new TableRowSource() {
            private int next;
            @Override public Optional<TableRow> next() {
                if (++next > 3) { return Optional.empty(); }
                return Optional.of(new TableRow(List.of(TableCell.builder().text("CALL-" + next).format(format).columnSpan(2).build(),
                        new TableCell("TAIL-" + next, format))));
            }
        };
        var result = engine.export(spec, source).backend(new PdfBackend()).to(output.resolve("consumer-streaming-spans.pdf"));
        if (result.sourceCells() != 7 || result.bodyRows() != 3 || result.pages() != 1) {
            throw new IllegalStateException("isolated horizontal-span counters differ");
        }
    }
}
