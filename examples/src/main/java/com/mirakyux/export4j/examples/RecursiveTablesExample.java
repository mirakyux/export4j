// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.ContinuousColumns;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.Documents;
import com.mirakyux.export4j.components.SemanticTable;
import com.mirakyux.export4j.components.RichTextComponent;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineImage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Recursive semantic continuations with three table levels and a vertically merged parent. */
public final class RecursiveTablesExample {
    private static final Path OUTPUT = Path.of("examples/build/recursive-tables");
    private static final CellFormat CELL = CellFormat.builder().fontSize(8).lineHeight(12).build();
    private static final ParagraphFormat TEXT = ParagraphFormat.builder().fontSize(8).lineHeight(12).spaceAfter(0).build();
    private static final InlineStyle INK = InlineStyle.of(8);
    private RecursiveTablesExample() { }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Regular.ttf"))))
                .fontRole("bold", FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Bold.ttf")))).build();
        for (String name : List.of("nested", "columns")) {
            var source = source();
            var builder = Documents.builder().pageSettings(PageSettings.builder().size(420, 240).margins(16).build());
            if (name.equals("nested")) { builder.add(source); }
            else { builder.component(ContinuousColumns.builder(new SemanticTable(source)).columns(2).gap(16).build()); }
            builder.add(new Paragraph("tail", "DOCUMENT-END", TEXT));
            var document = builder.build();
            var result = engine.export(document).backend(new PdfBackend()).to(OUTPUT.resolve(name + ".pdf"));
            try (var stream = Files.newOutputStream(OUTPUT.resolve(name + ".svg"))) {
                new SvgBackend().write(new DisplayDocument(result.display().metadata(), result.display().pages()), stream);
            }
            if (name.equals("nested")) {
                try (var stream = Files.newOutputStream(OUTPUT.resolve("nested.docx"))) { new DocxAdapter().write(document, stream); }
            }
            System.out.println(name + ": " + result.display().pages().size() + " pages");
        }
        var rich = RichTextComponent.builder().lineHeight(16);
        for (int line = 1; line <= 30; line++) {
            rich.add(String.format(Locale.ROOT, "RICH-%02d\n", line),
                    new InlineStyle(line % 2 == 0 ? "bold" : "", 9, line % 2 == 0 ? 0x224488 : 0x884422, 0, false, false));
        }
        engine.export(Documents.builder().pageSettings(PageSettings.builder().size(420, 240).margins(16).build())
                .component(ContinuousColumns.builder(rich.build().flow()).columns(2).gap(16).balanced(true).build()).build())
                .backend(new PdfBackend()).to(OUTPUT.resolve("rich.pdf"));
    }

    private static Table source() throws IOException {
        var inner = Table.builder("inner").cellFormat(CELL).format(format -> format.padding(1).allowRowSplit(true))
                .header("INNER-HEAD");
        for (int row = 1; row <= 24; row++) { inner.row(String.format(Locale.ROOT, "ROW-%02d", row)); }
        var middle = Table.builder("middle").cellFormat(CELL).format(format -> format.padding(1).allowRowSplit(true))
                .header("MIDDLE-HEAD").row(TableCell.builder().format(CELL).blocks(List.of(inner.build())).build()).build();
        var end = Paragraph.mixed("end", List.of(new InlineText("BLOCK-END P=", INK),
                new InlineField(InlineField.Kind.PAGE, "", 12, INK), new InlineText(" N=", INK),
                new InlineField(InlineField.Kind.PAGES, "", 12, INK), new InlineText(" LINK", INK, "https://example.com/recursive"),
                new InlineImage(image(), 8, 8, 0, "Generated marker", "https://example.com/marker")), TEXT);
        return Table.builder("outer").columns(new ColumnWidth.Fraction(4), new ColumnWidth.Fraction(1)).cellFormat(CELL)
                .format(format -> format.padding(2).allowRowSplit(true))
                .headerCells(TableCell.builder().format(CELL).columnSpan(2).text("OUTER-HEAD").build())
                .row(TableCell.builder().format(CELL).rowSpan(2).blocks(List.of(new Paragraph("begin", "BLOCK-BEGIN", TEXT), middle, end))
                        .build(), new TableCell("SIDE-A", CELL))
                .row(new TableCell("SIDE-B", CELL)).build();
    }

    private static ImageResource image() throws IOException {
        var pixels = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 4; y++) { for (int x = 0; x < 4; x++) { pixels.setRGB(x, y, x < 2 ? 0x224488 : 0x4488cc); } }
        var bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(pixels, "png", bytes)) { throw new IOException("PNG encoder unavailable"); }
        return ImageResource.load(bytes.toByteArray());
    }
}
