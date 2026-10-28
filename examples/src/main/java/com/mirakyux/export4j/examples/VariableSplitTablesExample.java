// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import com.mirakyux.export4j.model.InlineContent;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;

/** Complete source cells rewrap over unequal columns, preserving pictures, links and final page fields. */
public final class VariableSplitTablesExample {
    private static final Path OUTPUT = Path.of("examples/build/variable-split-tables");
    private static final CellFormat CELL = CellFormat.builder().fontSize(10).lineHeight(18).build();
    private static final InlineStyle STYLE = InlineStyle.of(10);
    private VariableSplitTablesExample() { }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Regular.ttf")))).build();
        var mixed = new ArrayList<InlineContent>();
        for (int index = 1; index <= 72; index++) {
            mixed.add(new InlineText(String.format(Locale.ROOT, "T%03d alpha beta ", index), STYLE, "https://example.com/continued"));
            if (index == 40) { mixed.add(picture()); }
        }
        mixed.add(new InlineText("TAIL PAGE=", STYLE));
        mixed.add(new InlineField(InlineField.Kind.PAGE, "", 12, STYLE));
        var continued = Table.builder("continued").cellFormat(CELL).format(format -> format.padding(3).allowRowSplit(true))
                .columns(new ColumnWidth.Fixed(36), new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1))
                .headerCells(header().toBuilder().columnSpan(3).build())
                .row(new TableCell("KEY", CELL), TableCell.mixed(mixed, CELL).toBuilder().columnSpan(2).build())
                .row(new TableCell("END", CELL), TableCell.builder().text("AFTER").format(CELL).columnSpan(2).build()).build();
        export(engine, continued, "continued", false);

        var balanced = Table.builder("balanced").cellFormat(CELL).format(format -> format.padding(3).allowRowSplit(true))
                .headerCells(header());
        for (int row = 1; row <= 3; row++) {
            StringBuilder source = new StringBuilder();
            for (int item = 1; item <= 16; item++) { source.append(String.format(Locale.ROOT, "B%03d gamma delta ", (row - 1) * 16 + item)); }
            balanced.row(source.toString());
        }
        export(engine, balanced.build(), "balanced", true);

        var owner = new ArrayList<InlineContent>();
        for (int item = 1; item <= 36; item++) {
            owner.add(new InlineText(String.format(Locale.ROOT, "V%03d alpha beta ", item), STYLE, "https://example.com/vertical-owner"));
            if (item == 18) { owner.add(picture()); }
        }
        owner.add(new InlineText("\nOP=", STYLE));
        owner.add(new InlineField(InlineField.Kind.PAGE, "", 12, STYLE));
        var neighbour = new StringBuilder();
        for (int item = 1; item <= 12; item++) { neighbour.append(String.format(Locale.ROOT, "N%03d gamma ", item)); }
        var vertical = Table.builder("vertical-owners").cellFormat(CELL).format(format -> format.padding(3).allowRowSplit(true))
                .columns(new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1))
                .headerCells(header().toBuilder().columnSpan(3).build())
                .row(TableCell.mixed(owner, CELL).toBuilder().columnSpan(2).rowSpan(3).build(), new TableCell("RIGHT-A", CELL))
                .row(TableCell.builder().text(neighbour.toString()).format(CELL).rowSpan(2).build())
                .coveredRow()
                .row(TableCell.builder().text("END").format(CELL).columnSpan(2).build(), new TableCell("AFTER", CELL)).build();
        export(engine, vertical, "vertical", false);
        export(engine, vertical, "vertical-balanced", true);
    }

    private static TableCell header() {
        return TableCell.mixed(List.of(new InlineText("PAGE=", STYLE), new InlineField(InlineField.Kind.PAGE, "", 12, STYLE),
                new InlineText(" OF=", STYLE), new InlineField(InlineField.Kind.PAGES, "", 12, STYLE)), CELL);
    }

    private static void export(DocumentEngine engine, Table table, String name, boolean balanced) throws IOException {
        var flow = FlowColumn.builder().padding(2).fixedText("BEGIN").semanticTable(table).fixedText("FINISH").build();
        var columns = ContinuousColumns.builder(flow).columnWeights(List.of(1.0, 2.0)).gap(12).balanced(balanced).build();
        var document = Documents.builder().pageSettings(PageSettings.builder().size(400, 220).margins(20).build())
                .component(name + "-host", columns).build();
        engine.export(document).backend(new PdfBackend()).to(OUTPUT.resolve(name + ".pdf"));
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
