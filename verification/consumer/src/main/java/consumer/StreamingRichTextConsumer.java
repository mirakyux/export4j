// SPDX-License-Identifier: Apache-2.0
package consumer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.layout.core.StreamingTableLimits;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.StreamingTable;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.TableRowSource;

/** Published API caller keeps rich source rows lazy and uses an explicit physical bold face. */
final class StreamingRichTextConsumer {
    private StreamingRichTextConsumer() { }

    static void verify(FontFace ordinaryFace, Path output) throws IOException {
        var boldFace = TrueTypeFontLoader.load(Files.readAllBytes(Path.of("../../assets/fonts/LiberationSans-Bold.ttf")));
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(ordinaryFace)))
                .fontRole("bold", FontGroup.strict(List.of(boldFace))).build();
        var format = CellFormat.builder().fontSize(10).lineHeight(18).build();
        var ink = InlineStyle.of(10);
        var blue = InlineStyle.builder().fontSize(10).fontRole("bold").rgb(0x224488).underline(true).build();
        var table = StreamingTable.builder("consumer-rich-stream").columns(80, 200)
                .pageSettings(PageSettings.builder().size(320, 180).margins(20).build()).padding(3)
                .header(new TableRow(List.of(TableCell.rich(List.of(new InlineText("SR-ID", blue)), format),
                        TableCell.rich(List.of(new InlineText("SR-STATE", blue)), format)))).build();
        int[] lifecycle = {0, 0, 0};
        var result = engine.export(table, () -> new TableRowSource() {
            private int index;

            @Override public Optional<TableRow> next() {
                lifecycle[0]++;
                if (index == 8) { return Optional.empty(); }
                String label = String.format(Locale.ROOT, "SR%02d", ++index);
                lifecycle[1]++;
                return Optional.of(new TableRow(List.of(TableCell.builder().format(format).text(label).build(),
                        TableCell.rich(List.of(new InlineText("STATE ", ink), new InlineText(label + " OK", blue)), format))));
            }

            @Override public void close() { lifecycle[2]++; }
        }).limits(StreamingTableLimits.builder().maxCellInlines(4).maxRowInlines(8).build())
                .backend(new PdfBackend()).to(output.resolve("consumer-streaming-rich-text.pdf"));
        if (result.bodyRows() != 8 || result.pages() != 2 || result.sourceCells() != 18
                || result.outputLines() != 20 || result.diagnostics() != 0
                || lifecycle[0] != 9 || lifecycle[1] != 8 || lifecycle[2] != 1) {
            throw new IllegalStateException("rich streaming public API lost its lazy rows, styles or lifecycle");
        }
    }
}
