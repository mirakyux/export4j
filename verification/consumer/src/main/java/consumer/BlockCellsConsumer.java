// SPDX-License-Identifier: Apache-2.0
package consumer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.layout.ParagraphFragment;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;

/** Compiles only against the local Maven bundle; no project dependencies or private helpers. */
final class BlockCellsConsumer {
    private BlockCellsConsumer() { }

    static void verify(DocumentEngine engine, InlineImage image, Path output) throws IOException {
        var style = InlineStyle.of(10);
        var paragraph = ParagraphFormat.builder().fontSize(10).lineHeight(18).spaceAfter(0).build();
        var cell = CellFormat.builder().fontSize(10).lineHeight(18).build();
        var blocks = TableCell.builder().format(cell).columnSpan(2).rowSpan(2)
                .paragraph("consumer-before", "CON-BLOCK-BEFORE")
                .table("consumer-inner", table -> table.columns(new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1))
                        .cellFormat(cell).format(format -> format.padding(2).spaceAfter(0))
                        .row(TableCell.builder().format(cell).text("CON-INNER-A\nCON-INNER-B").rowSpan(2).build(), new TableCell("CON-INNER-C", cell))
                        .row("CON-INNER-D"))
                .paragraph(Paragraph.mixed("consumer-after", List.of(new InlineText("CON-BLOCK-AFTER P=", style),
                        new InlineField(InlineField.Kind.PAGE, "", 18, style), new InlineText(" N=", style),
                        new InlineField(InlineField.Kind.PAGES, "", 18, style), new InlineText(" R=", style),
                        new InlineField(InlineField.Kind.REFERENCE_PAGE, "consumer-before", 18, style),
                        new InlineText("LINK", style, "https://example.com/block-cell"),
                        new InlineImage(image.resource(), 8, 8, 0, "Block image", "https://example.com/block-cell-image")), paragraph))
                .build();
        var table = Table.builder("consumer-block-cells").columns(new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1)).cellFormat(cell)
                .format(format -> format.padding(2).spaceAfter(0))
                .headerCells(TableCell.builder().text("CON-BLOCK-HEADER").format(cell).columnSpan(2).build())
                .row(blocks).coveredRow().build();
        var document = new Document(DocumentMetadata.EMPTY, PageSettings.builder().size(320, 240).margins(20).build(), List.of(table));
        var result = engine.export(document).backend(new PdfBackend()).to(output.resolve("consumer-block-cells.pdf"));
        var fragment = (TableFragment) result.body().pages().getFirst().elements().getFirst();
        var descendants = fragment.rows().get(1).cells().getFirst().blocks();
        var after = (ParagraphFragment) descendants.getLast();
        String logical = after.layout().lines().stream().map(line -> line.outlines().isEmpty()
                ? line.runs().stream().map(run -> run.text()).collect(java.util.stream.Collectors.joining())
                : line.outlines().stream().map(ink -> ink.logicalText()).collect(java.util.stream.Collectors.joining()))
                .collect(java.util.stream.Collectors.joining());
        if (result.display().pages().size() != 1 || result.display().extras().externalLinks().size() != 2
                || result.display().extras().links().size() != 1 || !result.diagnostics().isEmpty()
                || !logical.contains("CON-BLOCK-AFTER P=1 N=1 R=1LINK") || descendants.size() != 3
                || !((TableFragment) descendants.get(1)).blockId().equals("consumer-inner")) {
            throw new IllegalStateException("block-cell final semantics failed in isolated consumer");
        }
        var adapter = new DocxAdapter();
        try (var target = Files.newOutputStream(output.resolve("consumer-block-cells.docx"))) { adapter.write(document, target); }
        try (var source = Files.newInputStream(output.resolve("consumer-block-cells.docx"))) {
            var restored = (Table) adapter.read(source, DocxAdapter.Policy.STRICT).document().sections().getFirst().blocks().getFirst();
            var restoredCell = restored.rows().get(1).cells().getFirst();
            var inner = (Table) restoredCell.blocks().get(1);
            if (restoredCell.columnSpan() != 2 || restoredCell.rowSpan() != 2 || restoredCell.blocks().size() != 3
                    || inner.rows().getFirst().cells().getFirst().rowSpan() != 2
                    || !((Paragraph) restoredCell.blocks().getFirst()).text().equals("CON-BLOCK-BEFORE")
                    || ((Paragraph) restoredCell.blocks().getLast()).fields().size() != 3) {
                throw new IllegalStateException("standard native block-cell DOCX failed in isolated consumer");
            }
        }
    }
}
