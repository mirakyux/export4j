// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.backend.raster.RasterBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.FontScope;
import com.mirakyux.export4j.components.OpenTypeText;
import com.mirakyux.export4j.components.MultiColumn;
import com.mirakyux.export4j.components.FloatText;
import com.mirakyux.export4j.components.Navigation;
import com.mirakyux.export4j.components.SectionTemplate;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.RgbImage;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.ComponentBlock;
import com.mirakyux.export4j.layout.core.PageTemplate;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.BulletItem;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.Note;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextAlignment;

/** Local, reproducible fixtures. Only the caller prints status; all resources are supplied explicitly. */
public final class ProductionExample {
    private ProductionExample() { }
    private static final Path OUTPUT = Path.of("examples/build/production");
    private static final PageSettings SETTINGS = new PageSettings(new PageSize(430, 360), PageMargins.uniform(24));
    private static final DocumentMetadata META = new DocumentMetadata("Document engine local verification", "mirakyux");
    private static final ParagraphStyle STYLE = new ParagraphStyle(12, 26, 0, 0, TextAlignment.LEFT);

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        FontFace latin = font("LiberationSans-Regular.ttf");
        FontFace chinese = font("LXGWWenKaiTC-Regular.ttf");
        FontFace arabic = font("NotoNaskhArabic-Regular.ttf");
        FontGroup bodyFonts = FontGroup.strict(List.of(latin, chinese));
        FontGroup complexFonts = FontGroup.strict(List.of(latin, arabic, chinese));
        var engine = DocumentEngine.builder(bodyFonts).fontRole("bold", FontGroup.strict(List.of(font("LiberationSans-Bold.ttf"), chinese))).build();
        var typography = Document.builder(META, SETTINGS)
                .heading("h", "OpenType / Unicode", 1, ParagraphFormat.of(16))
                .add(new ComponentBlock("shaping", new FontScope(new OpenTypeText(
                        "Kerning: AVATAR To Wa; ligatures: ffi office\nCombining: a\u0301 e\u0308 A\u030a\nArabic: العربية لا سلام 123\nHebrew: שָׁלוֹם עולם 123\nMixed: English العربية 456 English\n中文标点：（测试），禁止行首标点。\n中文与 English 混排。", STYLE, RgbColor.BLACK), complexFonts), 0, 0)).build();
        var shaped = engine.layout(typography);
        try (var output = Files.newOutputStream(OUTPUT.resolve("typography.pdf"))) { new PdfBackend().write(shaped.display(), output); }
        try (var output = Files.newOutputStream(OUTPUT.resolve("typography.svg"))) { new SvgBackend().write(shaped.display(), output); }
        try (var output = Files.newOutputStream(OUTPUT.resolve("typography-pages.zip"))) { new RasterBackend(144).write(shaped.display(), output); }
        String columnText = java.util.stream.IntStream.rangeClosed(1, 40).mapToObj(i -> "COL-" + String.format(java.util.Locale.ROOT, "%02d", i) + "  阅读顺序：由上到下，再向右。").collect(java.util.stream.Collectors.joining("\n"));
        var columns = Document.builder(META, SETTINGS).add(new ComponentBlock("columns", new MultiColumn(new OpenTypeText(columnText, STYLE, RgbColor.BLACK), 2, 18), 0, 0)).build();
        pdf(engine, columns, "columns.pdf");
        var image = new RgbImage(2, 2, new byte[] {40, 100, (byte) 180, 80, (byte) 160, (byte) 210, (byte) 160, (byte) 210, (byte) 235, (byte) 220, (byte) 235, (byte) 245});
        var floating = (com.mirakyux.export4j.layout.core.Component) ctx -> new ComponentLayout(ctx.width(), ctx.height(), 0,
                List.of(new DrawCommand.Image(image, new Rect(0, 0, ctx.width(), ctx.height()))), List.of());
        String floatText = "图像旁的文字使用较窄区域；到达图像底部后恢复整行宽度，下一页也使用完整宽度。 " .repeat(14);
        var floatDoc = Document.builder(META, SETTINGS).add(new ComponentBlock("float", new FloatText(new OpenTypeText(floatText, STYLE, RgbColor.BLACK), floating, 105, 104, 12, FloatText.Side.LEFT), 0, 0)).build();
        pdf(engine, floatDoc, "float.pdf");
        var table = table(40);
        pdf(engine, Document.builder(META, SETTINGS).add(table).build(), "split-table.pdf");
        String notes = java.util.stream.IntStream.rangeClosed(1, 30).mapToObj(i -> "NOTE-" + String.format(java.util.Locale.ROOT, "%02d", i) + "  长脚注按完整行续页，文字只出现一次。").collect(java.util.stream.Collectors.joining("\n"));
        var noteDoc = new SectionedDocument(META, List.of(new Section("notes", SETTINGS, List.of(new Paragraph("anchor", "正文脚注锚点；续页区域保留在页脚上方。", ParagraphFormat.of(12))), 0)));
        var noteLayout = engine.layout(noteDoc, List.of(SectionTemplate.uniform(PageTemplate.EMPTY)), Navigation.EMPTY, List.of(new Note("long-note", "anchor", notes, Note.Kind.FOOTNOTE)));
        try (var output = Files.newOutputStream(OUTPUT.resolve("notes.pdf"))) { new PdfBackend().write(noteLayout.display(), output); }
        var format = ParagraphFormat.of(12);
        List<Block> semantic = List.of(new Heading("title", "多格式语义样例", 1, ParagraphFormat.of(16)),
                new Paragraph("rich", List.of(new InlineText("富文本 / ", InlineStyle.of(12)), new InlineText("Bold", new InlineStyle("bold", 12, 0x285080, 0, true, false))), format),
                new NumberedItem("one", "list", "第一级", 0, format), new NumberedItem("child", "list", "第二级", 0, format, 2),
                new BulletItem("bullet", "项目符号", "•", 1, format), table(3));
        var docx = new SectionedDocument(META, List.of(new Section("portrait", SETTINGS, semantic, 1),
                new Section("landscape", new PageSettings(new PageSize(500, 330), PageMargins.uniform(24)), List.of(new Paragraph("end", "横向分节，页码从 10 开始。", format)), 10)));
        try (var output = Files.newOutputStream(OUTPUT.resolve("semantic.docx"))) { new DocxAdapter().write(docx, output); }
        try (var input = Files.newInputStream(OUTPUT.resolve("semantic.docx"))) {
            var roundtrip = new DocxAdapter().read(input, DocxAdapter.Policy.STRICT);
            var layout = engine.layout(roundtrip.document(), List.of(SectionTemplate.uniform(PageTemplate.EMPTY), SectionTemplate.uniform(PageTemplate.EMPTY)), Navigation.EMPTY, List.of());
            try (var output = Files.newOutputStream(OUTPUT.resolve("semantic-roundtrip.pdf"))) { new PdfBackend().write(layout.display(), output); }
        }
        System.out.println("Local fixtures: " + OUTPUT.toAbsolutePath());
    }

    private static Table table(int lines) {
        String rows = java.util.stream.IntStream.rangeClosed(1, lines).mapToObj(i -> "ROW-" + String.format(java.util.Locale.ROOT, "%02d", i) + "  完整行续页").collect(java.util.stream.Collectors.joining("\n"));
        var cf = CellFormat.of(10);
        return new Table("table", List.of(new ColumnWidth.Fraction(2), new ColumnWidth.Fraction(1)), List.of(
                new TableRow(List.of(new TableCell("长单元格 / continued cell", cf), new TableCell("短单元格", cf))),
                new TableRow(List.of(new TableCell(rows, cf), new TableCell("SHORT: 只出现一次", cf)))), 1, new TableFormat(PageMargins.uniform(4), 0, 0, true));
    }

    private static void pdf(DocumentEngine engine, Document document, String name) throws IOException {
        try (var output = Files.newOutputStream(OUTPUT.resolve(name))) { engine.write(document, new PdfBackend(), output); }
    }
    private static FontFace font(String name) throws IOException {
        try (var input = ProductionExample.class.getResourceAsStream("/fonts/" + name)) { return TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES); }
    }
}
