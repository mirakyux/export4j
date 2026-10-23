// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.StreamingTable;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.TableRowSource;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

final class StreamingMediaConsumer {
    private StreamingMediaConsumer() { }
    static void verify(DocumentEngine engine, InlineImage image, Path output) throws IOException {
        var format = CellFormat.builder().fontSize(10).lineHeight(24).build();
        var picture = image.toBuilder().width(16).height(8).baselineShift(0).hyperlink("https://example.com/caller-image").build();
        var spec = StreamingTable.builder("caller-media").columns(180).padding(2)
                .pageSettings(PageSettings.builder().size(220, 120).margins(20).build()).build();
        var result = engine.export(spec, () -> new TableRowSource() {
            private boolean emitted;
            @Override public Optional<TableRow> next() {
                if (emitted) { return Optional.empty(); }
                emitted = true;
                return Optional.of(new TableRow(List.of(TableCell.mixed(List.of(new InlineText("CALLER MEDIA ",
                        InlineStyle.of(10), "https://example.com/caller"), picture), format))));
            }
        }).backend(new PdfBackend()).to(output.resolve("consumer-streaming-media.pdf"));
        if (result.pages() != 1 || result.bodyRows() != 1 || result.sourceCells() != 1) {
            throw new IllegalStateException("isolated streaming media counters differ");
        }
    }
}
