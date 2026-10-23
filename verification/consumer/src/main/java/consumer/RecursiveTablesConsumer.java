// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.ContinuousColumns;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.Documents;
import com.mirakyux.export4j.components.RichTextComponent;
import com.mirakyux.export4j.components.SemanticTable;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class RecursiveTablesConsumer {
    private RecursiveTablesConsumer() { }
    static void verify(DocumentEngine engine, Path output) throws IOException {
        var format = CellFormat.builder().fontSize(9).lineHeight(14).build();
        var inner = Table.builder("caller-inner").cellFormat(format).format(value -> value.padding(1).allowRowSplit(true))
                .header("CALLER-HEADER").row("ONE\nTWO\nTHREE\nFOUR\nFIVE\nSIX\nSEVEN\nEIGHT").build();
        var middle = Table.builder("caller-middle").format(value -> value.padding(1).allowRowSplit(true))
                .row(TableCell.builder().blocks(List.of(inner)).build()).build();
        var outer = Table.builder("caller-outer").format(value -> value.padding(1).allowRowSplit(true))
                .row(TableCell.builder().blocks(List.of(middle, new Paragraph("caller-end", "CALLER-END", ParagraphFormat.of(9))))
                        .rowSpan(2).build()).coveredRow().build();
        var page = PageSettings.builder().size(300, 130).margins(12).build();
        var document = Documents.builder().pageSettings(page).add(outer).build();
        engine.write(document, new PdfBackend(), output.resolve("consumer-recursive.pdf"));
        try (var stream = Files.newOutputStream(output.resolve("consumer-recursive.docx"))) { new DocxAdapter().write(document, stream); }
        var rich = RichTextComponent.builder().lineHeight(14).add("ALPHA\nBETA\nGAMMA\nDELTA\nEPSILON", InlineStyle.of(9)).build();
        Document columns = Documents.builder().pageSettings(page).component(ContinuousColumns.builder(rich.flow())
                .columns(2).balanced(true).build()).build();
        engine.write(columns, new PdfBackend(), output.resolve("consumer-rich-flow.pdf"));
        engine.write(Documents.builder().pageSettings(page).component(new SemanticTable(outer)).build(), new PdfBackend(),
                output.resolve("consumer-recursive-component.pdf"));
    }
}
