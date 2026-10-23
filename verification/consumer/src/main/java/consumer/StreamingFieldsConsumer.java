// SPDX-License-Identifier: Apache-2.0
package consumer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.layout.core.StreamingTableLimits;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.StreamingTable;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.TableRowSource;

final class StreamingFieldsConsumer {
    private StreamingFieldsConsumer() { }
    static void verify(DocumentEngine engine, Path output) throws IOException {
        var format = CellFormat.builder().fontSize(10).lineHeight(20).build();
        var style = InlineStyle.of(10);
        var header = TableCell.mixed(List.of(new InlineText("CALLER P=", style),
                new InlineField(InlineField.Kind.PAGE, "", 15, style), new InlineText(" T=", style),
                new InlineField(InlineField.Kind.PAGES, "", 15, style)), format);
        var table = StreamingTable.builder("caller-fields").columns(180).padding(0).expectedPages(2)
                .pageSettings(PageSettings.builder().size(200, 100).margins(10).build()).header(new TableRow(List.of(header))).build();
        // Keep the previous public construction shape callable from the published artifacts.
        var oldLimits = new StreamingTableLimits(StreamingTableLimits.DEFAULT.document(), 1000, 1000, 100);
        var summary = engine.export(table, () -> new TableRowSource() {
            private int index;
            @Override public Optional<TableRow> next() {
                if (++index > 4) { return Optional.empty(); }
                return Optional.of(new TableRow(List.of(new TableCell("CALLER-" + index, format))));
            }
        }).limits(oldLimits).backend(new PdfBackend()).to(output.resolve("consumer-streaming-fields.pdf"));
        if (summary.pages() != 2 || summary.bodyRows() != 4) { throw new AssertionError("caller field page counts"); }
    }
}
