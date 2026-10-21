// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.layout.core.DocumentLayouter;
import com.mirakyux.export4j.layout.core.DocumentPainter;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.model.TableRow;

/** Mixed semantic content with varying row heights, whole-row pagination and repeated table headers. */
public final class TablePdfExample {
    private TablePdfExample() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length > 1) {
            throw new IllegalArgumentException("Expected zero arguments or one output PDF path");
        }
        Path output = Path.of(args.length == 0 ? "examples/build/export4j-table-example.pdf" : args[0]).toAbsolutePath();
        Files.createDirectories(output.getParent());
        List<FontFace> fonts = List.of(load("LiberationSans-Regular.ttf"), load("LXGWWenKaiTC-Regular.ttf"));
        ParagraphFormat title = new ParagraphFormat(24, 36, 0, 0, ParagraphAlignment.LEFT, 0, 14, true);
        ParagraphFormat note = new ParagraphFormat(11, 18, 0, 0, ParagraphAlignment.LEFT, 8, 12, true);
        CellFormat body = new CellFormat(11, 18, ParagraphAlignment.LEFT);
        CellFormat centered = new CellFormat(11, 18, ParagraphAlignment.CENTER);
        List<TableRow> rows = new ArrayList<>();
        rows.add(new TableRow(List.of(new TableCell("编号 / ID", centered), new TableCell("验证内容 / Description", body),
                new TableCell("结果", centered))));
        for (int index = 1; index <= 32; index++) {
            String text = "语义表格保留行列来源，单元格按列宽自动换行，整行移动到下一页。"
                    + "每个正文行只输出一次，续页重新放置表头。";
            if (index % 4 == 0) {
                text += "\nExtra detail: measured once, repeated headers on continuation pages.";
            }
            rows.add(new TableRow(List.of(new TableCell(String.format(Locale.ROOT, "ROW-%02d", index), centered),
                    new TableCell(text, body), new TableCell("通过", centered))));
        }
        Table table = new Table("results", List.of(new ColumnWidth.Fixed(76), new ColumnWidth.Fraction(1),
                new ColumnWidth.Fixed(52)), rows, 1, new TableFormat(PageMargins.uniform(7), 0, 12));
        Document document = new Document(new DocumentMetadata("Export4j 跨页表格报告", "Export4j contributors"),
                PageSettings.A4, List.of(new Paragraph("title", "Export4j 跨页表格报告", title),
                        new Paragraph("intro", "32 条正文记录按顺序排列。灰色表头在续页重复，较长单元格自动增加行高。", note),
                        table, new Paragraph("conclusion", "表格结束\n正文行仅出现一次，表头重复带有来源标记。"
                                + "合并单元格、嵌套表格与单行拆页尚未支持。", note)));
        var layout = new DocumentLayouter().layout(document, fonts);
        try (OutputStream stream = Files.newOutputStream(output)) {
            new PdfBackend().write(DocumentPainter.paint(layout), stream);
        }
        System.out.println("Generated: " + output);
        System.out.println("Pages: " + layout.pages().size());
        System.out.println("Table fragments: " + layout.pages().stream().flatMap(page -> page.elements().stream())
                .filter(TableFragment.class::isInstance).count());
    }

    private static FontFace load(String name) throws IOException {
        try (InputStream input = TablePdfExample.class.getResourceAsStream("/fonts/" + name)) {
            if (input == null) {
                throw new IOException("Bundled example font is missing");
            }
            return TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES);
        }
    }
}
