// SPDX-License-Identifier: Apache-2.0
package consumer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;

final class NestedAutomaticTablesConsumer {
    private NestedAutomaticTablesConsumer() { }
    static void verify(DocumentEngine engine, Path output) throws IOException {
        var cell = CellFormat.builder().fontSize(8).lineHeight(12).build();
        var inner = Table.builder("caller-auto-child").columns(new ColumnWidth.Auto(24, 60), new ColumnWidth.Fixed(40))
                .cellFormat(cell).format(format -> format.padding(2)).row("CALLER-AUTO", "TAIL").build();
        var outer = Table.builder("caller-auto-parent").format(format -> format.padding(4))
                .row(TableCell.builder().blocks(List.of(inner)).build()).build();
        var source = new Document(DocumentMetadata.EMPTY, PageSettings.builder().size(180, 100).margins(10).build(), List.of(outer));
        engine.export(source).backend(new PdfBackend()).to(output.resolve("consumer-nested-auto.pdf"));
        try (var stream = Files.newOutputStream(output.resolve("consumer-nested-auto.docx"))) { new DocxAdapter().write(source, stream); }
    }
}
