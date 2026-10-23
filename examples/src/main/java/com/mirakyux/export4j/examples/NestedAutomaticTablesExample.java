// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;

/** Inline nested automatic columns, independently shrunk within a parent cell and continued by source. */
public final class NestedAutomaticTablesExample {
    private static final Path OUTPUT = Path.of("examples/build/nested-automatic-tables");
    private static final CellFormat CELL = CellFormat.builder().fontSize(8).lineHeight(12).build();
    private static final InlineStyle STYLE = InlineStyle.of(8);
    private NestedAutomaticTablesExample() { }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Regular.ttf")))).build();
        for (int width : List.of(320, 140)) {
            var source = document(width, true);
            var name = width == 320 ? "wide" : "narrow";
            var result = engine.export(source).backend(new PdfBackend()).to(OUTPUT.resolve(name + ".pdf"));
            if (result.body().pages().size() != (width == 320 ? 1 : 2)) { throw new IllegalStateException("nested automatic page count differs"); }
            // A separate native-field fixture has no repeated header: Word's per-occurrence PAGE
            // table-header restriction remains explicit, rather than silently flattening a field.
            try (var output = Files.newOutputStream(OUTPUT.resolve(name + ".docx"))) { new DocxAdapter().write(document(width, false), output); }
        }
    }

    private static Document document(int width, boolean repeated) {
        var header = TableCell.mixed(List.of(new InlineText("NESTED P=", STYLE),
                new InlineField(InlineField.Kind.PAGE, "", 12, STYLE), new InlineText(" T=", STYLE),
                new InlineField(InlineField.Kind.PAGES, "", 12, STYLE)), CELL).toBuilder().columnSpan(2).build();
        var inner = Table.builder("automatic-inner").columns(new ColumnWidth.Auto(24, 90), new ColumnWidth.Fixed(40))
                .cellFormat(CELL).format(format -> format.padding(2).allowRowSplit(true)).headerCells(header);
        for (int index = 1; index <= 6; index++) {
            String number = String.format(Locale.ROOT, "%02d", index);
            inner.row(TableCell.rich(List.of(new InlineText("ITEM-" + number + " WWWWWWWWWW", STYLE,
                    "https://example.com/nested-auto/" + index)), CELL), new TableCell("T-" + number, CELL));
        }
        var child = inner.build();
        if (!repeated) { child = new Table(child.id(), child.columns(), child.rows(), 0, child.format()); }
        var outer = Table.builder("outer-auto").format(format -> format.padding(4).allowRowSplit(true))
                .row(TableCell.builder().blocks(List.of(child)).build()).build();
        return new Document(DocumentMetadata.EMPTY, PageSettings.builder().size(width, 240).margins(16).build(), List.of(outer));
    }
}
