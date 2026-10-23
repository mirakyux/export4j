// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.ContinuousColumns;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.Documents;
import com.mirakyux.export4j.components.FlowColumn;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;

/** Unequal columns with semantic rows, automatic merged owners and atomic nested content. */
public final class VariableTablesExample {
    private static final Path OUTPUT = Path.of("examples/build/variable-tables");
    private static final CellFormat CELL = CellFormat.builder().fontSize(10).lineHeight(18).build();
    private static final InlineStyle STYLE = InlineStyle.of(10);
    private VariableTablesExample() { }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Regular.ttf")))).build();
        var rows = Table.builder("rows").cellFormat(CELL).format(format -> format.padding(3)).headerCells(header());
        for (int row = 1; row <= 18; row++) {
            rows.row(TableCell.rich(List.of(new InlineText(label("ROW", row) + " alpha beta gamma delta", STYLE,
                    "https://example.com/row/" + row)), CELL));
        }
        rows.row(TableCell.mixed(List.of(new InlineText("PICTURE ", STYLE), picture()), CELL));
        export(engine, rows.build(), "rows", false);

        var merged = Table.builder("merged").cellFormat(CELL).format(format -> format.padding(3))
                .columns(new ColumnWidth.Auto(28, 48), new ColumnWidth.Fraction(1))
                .headerCells(header().toBuilder().columnSpan(2).build());
        for (int group = 1; group <= 6; group++) {
            merged.row(TableCell.builder().text("G" + group).format(CELL).rowSpan(2).build(),
                    new TableCell(label("MERGE", group * 2 - 1) + " alpha beta gamma", CELL));
            merged.row(new TableCell(label("MERGE", group * 2) + " alpha beta gamma", CELL));
        }
        export(engine, merged.build(), "merged", false);

        var nested = Table.builder("outer").cellFormat(CELL).format(format -> format.padding(3)).headerCells(header());
        for (int row = 1; row <= 5; row++) {
            var inner = Table.builder("inner-" + row).cellFormat(CELL).format(format -> format.padding(1))
                    .row(label("INNER", row) + " alpha beta gamma").build();
            nested.row(TableCell.builder().format(CELL).blocks(List.of(new com.mirakyux.export4j.model.Paragraph("leaf-" + row,
                            label("LEAF", row) + " alpha beta", com.mirakyux.export4j.model.ParagraphFormat.of(10)), inner)).build());
        }
        export(engine, nested.build(), "nested", true);
    }

    private static TableCell header() {
        return TableCell.mixed(List.of(new InlineText("PAGE=", STYLE), new InlineField(InlineField.Kind.PAGE, "", 12, STYLE),
                new InlineText(" OF=", STYLE), new InlineField(InlineField.Kind.PAGES, "", 12, STYLE)), CELL);
    }

    private static String label(String prefix, int index) { return String.format(Locale.ROOT, "%s-%02d", prefix, index); }

    private static void export(DocumentEngine engine, Table table, String name, boolean balanced) throws IOException {
        var flow = FlowColumn.builder().padding(2).semanticTable(table).build();
        var columns = ContinuousColumns.builder(flow).columnWeights(List.of(1.0, 2.0)).gap(12).balanced(balanced).build();
        var source = Documents.builder().pageSettings(PageSettings.builder().size(400, 220).margins(20).build())
                .component(name + "-host", columns).build();
        engine.export(source).backend(new PdfBackend()).to(OUTPUT.resolve(name + ".pdf"));
    }

    private static InlineImage picture() throws IOException {
        var image = new BufferedImage(12, 8, BufferedImage.TYPE_INT_RGB);
        var bytes = new ByteArrayOutputStream();
        try {
            for (int y = 0; y < 8; y++) { for (int x = 0; x < 12; x++) { image.setRGB(x, y, 0x2277AA); } }
            if (!javax.imageio.ImageIO.write(image, "png", bytes)) { throw new IOException("sample image encoder unavailable"); }
        } finally { image.flush(); }
        return new InlineImage(ImageResource.load(bytes.toByteArray()), 12, 8, 0, "One picture", "https://example.com/picture");
    }
}
