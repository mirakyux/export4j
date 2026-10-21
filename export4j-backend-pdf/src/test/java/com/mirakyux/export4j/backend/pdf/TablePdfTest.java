// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.layout.core.DocumentLayouter;
import com.mirakyux.export4j.layout.core.DocumentPainter;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.model.TableRow;

class TablePdfTest {
    @Test
    void mixedChineseTablePdfRepeatsHeaderAndPreservesEveryBodyRowInOrder() throws IOException {
        List<FontFace> fonts = List.of(load("LiberationSans-Regular.ttf"), load("LXGWWenKaiTC-Regular.ttf"));
        List<TableRow> rows = new ArrayList<>();
        rows.add(row("编号 / ID", "内容 / Content"));
        for (int index = 0; index < 9; index++) {
            rows.add(row(String.format(Locale.ROOT, "Row%02d", index), "文档表格内容"));
        }
        ParagraphFormat format = new ParagraphFormat(12, 18, 0, 0, ParagraphAlignment.LEFT, 0, 0, false);
        Table table = new Table("t", List.of(new ColumnWidth.Fixed(90), new ColumnWidth.Fraction(1)), rows,
                1, new TableFormat(PageMargins.uniform(2), 0, 0));
        var document = new Document(new DocumentMetadata("Table integration", "Export4j"),
                new PageSettings(new PageSize(320, 150), PageMargins.uniform(20)),
                List.of(new Paragraph("before", "Before", format), table, new Paragraph("after", "After", format)));
        var layout = new DocumentLayouter().layout(document, fonts);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new PdfBackend().write(DocumentPainter.paint(layout), output);
        try (PDDocument pdf = Loader.loadPDF(output.toByteArray())) {
            assertEquals(3, pdf.getNumberOfPages());
            assertEquals("Table integration", pdf.getDocumentInformation().getTitle());
            String text = new PDFTextStripper().getText(pdf).replaceAll("\\s+", "");
            assertEquals(3, occurrences(text, "编号/ID"));
            assertEquals(3, occurrences(text, "内容/Content"));
            assertEquals(9, occurrences(text, "文档表格内容"));
            int previous = text.indexOf("Before");
            assertTrue(previous >= 0);
            for (int index = 0; index < 9; index++) {
                String marker = String.format(Locale.ROOT, "Row%02d", index);
                assertEquals(1, occurrences(text, marker));
                int position = text.indexOf(marker);
                assertTrue(position > previous);
                previous = position;
            }
            assertTrue(text.indexOf("After") > previous);
            for (var page : pdf.getPages()) {
                assertEquals(320, page.getMediaBox().getWidth());
                for (var name : page.getResources().getFontNames()) {
                    assertTrue(page.getResources().getFont(name).isEmbedded());
                    assertTrue(page.getResources().getFont(name).getCOSObject().containsKey("ToUnicode"));
                }
            }
        }
    }

    private static int occurrences(String text, String token) {
        return (text.length() - text.replace(token, "").length()) / token.length();
    }

    private static TableRow row(String first, String second) {
        return new TableRow(List.of(new TableCell(first, CellFormat.of(12)), new TableCell(second, CellFormat.of(12))));
    }

    private static FontFace load(String name) throws IOException {
        try (InputStream input = TablePdfTest.class.getResourceAsStream("/fonts/" + name)) {
            if (input == null) {
                throw new IOException("Missing test font");
            }
            return TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES);
        }
    }
}
