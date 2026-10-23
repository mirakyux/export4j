// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.StreamingTable;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.TableRowSource;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

final class AdvancedTablesConsumer {
    private AdvancedTablesConsumer() { }
    static void verify(DocumentEngine engine, Path output) throws IOException {
        var format = CellFormat.builder().fontSize(10).lineHeight(18).build();
        var page = PageSettings.builder().size(220, 120).margins(20).build();
        var merged = TableCell.builder().text("CALLER AUTOMATIC").format(format).columnSpan(2).build();
        var table = Table.builder("caller-auto").columns(new ColumnWidth.Auto(20, 80), new ColumnWidth.Auto(20, 80))
                .row(merged).row(new TableCell("A", format), new TableCell("B", format)).build();
        engine.export(new Document(DocumentMetadata.EMPTY, page, List.of(table))).backend(new PdfBackend())
                .to(output.resolve("consumer-auto-spans.pdf"));
        var spec = StreamingTable.builder("caller-split").pageSettings(page).columns(180).padding(2).allowRowSplit(true).build();
        var result = engine.export(spec, () -> new TableRowSource() {
            private boolean emitted;
            @Override public Optional<TableRow> next() {
                if (emitted) { return Optional.empty(); }
                emitted = true;
                return Optional.of(new TableRow(List.of(new TableCell("CALLER-1\nCALLER-2\nCALLER-3\nCALLER-4\nCALLER-5", format))));
            }
        }).backend(new PdfBackend()).to(output.resolve("consumer-streaming-split.pdf"));
        if (result.bodyRows() != 1 || result.pages() != 2 || !spec.toBuilder().build().allowRowSplit()) {
            throw new IllegalStateException("isolated streaming continuation failed");
        }
    }
}
