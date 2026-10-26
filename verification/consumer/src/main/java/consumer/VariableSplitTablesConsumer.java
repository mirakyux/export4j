// SPDX-License-Identifier: Apache-2.0
package consumer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.ContinuousColumns;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.Documents;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;

final class VariableSplitTablesConsumer {
    private VariableSplitTablesConsumer() { }
    static void verify(DocumentEngine engine, Path output) throws IOException {
        var style = InlineStyle.of(10);
        var format = CellFormat.builder().fontSize(10).lineHeight(18).build();
        StringBuilder text = new StringBuilder();
        for (int index = 1; index <= 40; index++) { text.append(String.format(Locale.ROOT, "C%03d alpha beta ", index)); }
        var table = Table.builder("caller-split").cellFormat(format).format(value -> value.padding(2).allowRowSplit(true))
                .headerCells(TableCell.mixed(List.of(new InlineText("PAGE=", style), new InlineField(InlineField.Kind.PAGE, "", 12, style),
                        new InlineText(" OF=", style), new InlineField(InlineField.Kind.PAGES, "", 12, style)), format))
                .row(TableCell.rich(List.of(new InlineText(text.toString(), style, "https://example.com/caller")), format)).build();
        var columns = ContinuousColumns.builder(new com.mirakyux.export4j.components.SemanticTable(table))
                .columnWeights(List.of(1.0, 2.0)).gap(12).build();
        var document = Documents.builder().pageSettings(PageSettings.builder().size(320, 180).margins(20).build())
                .component("caller-host", columns).build();
        engine.export(document).backend(new PdfBackend()).to(output.resolve("consumer-variable-split-tables.pdf"));
    }
}
