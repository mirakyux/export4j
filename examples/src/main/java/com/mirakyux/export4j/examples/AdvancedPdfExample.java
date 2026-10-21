// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.BoxComponent;
import com.mirakyux.export4j.components.ComponentDocumentRenderer;
import com.mirakyux.export4j.components.FlowColumn;
import com.mirakyux.export4j.components.FlowText;
import com.mirakyux.export4j.components.GridTable;
import com.mirakyux.export4j.components.ImageComponent;
import com.mirakyux.export4j.components.Navigation;
import com.mirakyux.export4j.components.PageNumberComponent;
import com.mirakyux.export4j.components.RowComponent;
import com.mirakyux.export4j.components.SectionTemplate;
import com.mirakyux.export4j.components.SvgComponent;
import com.mirakyux.export4j.components.TextComponent;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.RgbImage;
import com.mirakyux.export4j.graphics.core.ImageDecoder;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentBlock;
import com.mirakyux.export4j.layout.core.PageBand;
import com.mirakyux.export4j.layout.core.PageTemplate;
import com.mirakyux.export4j.layout.core.Placement;
import com.mirakyux.export4j.layout.core.Position;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.Caption;
import com.mirakyux.export4j.model.CrossReference;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.Note;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.PageBreak;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.PaginationPolicy;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.StyleSheet;
import com.mirakyux.export4j.text.ParagraphStyle;

/** Comprehensive deterministic PDF fixture; all images are generated locally and all fonts are explicit. */
public final class AdvancedPdfExample {
    private static final RgbColor INK = new RgbColor(0.12, 0.25, 0.38);
    private static final RgbColor PALE = new RgbColor(0.92, 0.96, 0.98);
    private static final ParagraphFormat BODY = new ParagraphFormat(11, 19, 0, 0, ParagraphAlignment.LEFT, 0, 10, false, PaginationPolicy.BALANCED);

    private AdvancedPdfExample() { }

    public static void main(String[] args) throws IOException {
        if (args.length > 1) {
            throw new IllegalArgumentException("Expected zero arguments or one output path");
        }
        Path output = Path.of(args.length == 0 ? "examples/build/export4j-advanced-example.pdf" : args[0]).toAbsolutePath();
        var latin = load("LiberationSans-Regular.ttf");
        var chinese = load("LXGWWenKaiTC-Regular.ttf");
        var fonts = List.of(latin, chinese);
        var roles = Map.of("bold", FontGroup.strict(List.of(load("LiberationSans-Bold.ttf"), chinese)),
                "italic", FontGroup.strict(List.of(load("LiberationSans-Italic.ttf"), chinese)),
                "boldItalic", FontGroup.strict(List.of(load("LiberationSans-BoldItalic.ttf"), chinese)),
                "placeholderLatin", FontGroup.placeholder(List.of(latin), 0x25a1));
        var styles = new StyleSheet(Map.of("body", BODY), Map.of("normal", InlineStyle.of(11),
                "bold", new InlineStyle("bold", 13, 0x285780, 0, false, false),
                "italic", new InlineStyle("italic", 11, 0x885522, 0, true, false),
                "boldItalic", new InlineStyle("boldItalic", 11, 0x245c46, 0, false, false)));
        var portrait = new PageSettings(PageSize.A4, PageMargins.uniform(48));
        var landscape = new PageSettings(new PageSize(PageSize.A4.height(), PageSize.A4.width()), PageMargins.uniform(42));
        var png = image("png");
        var jpeg = image("jpeg");
        var svg = SvgComponent.parse("<svg viewBox='0 0 160 80'><rect x='1' y='1' width='158' height='78' fill='#eaf3f8'/>"
                + "<circle cx='40' cy='40' r='24' fill='#387ca3'/><path d='M 82 20 L 138 40 L 82 60 Z' fill='#d7a35c'/></svg>");
        List<Block> first = new ArrayList<>();
        first.add(new Heading("intro-title", "01  排版与语义 / Typography", 1));
        first.add(new Paragraph("intro", "这是可运行的文档引擎综合样本，涵盖分页、富文本、分节、图文表格和可编辑表单。", styles.paragraph("body")));
        first.add(new Paragraph("rich", List.of(new InlineText("Regular / ", styles.inline("normal")), new InlineText("Real Bold / ", styles.inline("bold")),
                new InlineText("Italic + underline / ", styles.inline("italic")), new InlineText("Bold Italic", styles.inline("boldItalic")),
                new InlineText("  H", styles.inline("normal")), new InlineText("2", new InlineStyle("", 8, 0x285780, 3, false, false)),
                new InlineText("O  x", styles.inline("normal")), new InlineText("2", new InlineStyle("", 8, 0xaa4020, -4, false, false)),
                new InlineText("  删除线", new InlineStyle("", 11, 0x606060, 0, false, true))), BODY));
        first.add(new NumberedItem("item1", "features", "编号在同一列表中连续递增，题注具有独立计数器。", 0, BODY));
        first.add(new Paragraph("placeholder-demo", List.of(new InlineText("Latin-only font policy: 中文",
                new InlineStyle("placeholderLatin", 10, 0x885522, 0, false, false))), BODY));
        first.add(new NumberedItem("item2", "features", "目录、交叉引用和书签都指向最终页面。", 0, BODY));
        first.add(new CrossReference("figure-ref", "资源题注 / See ", "figure", CrossReference.Kind.NUMBER, BODY));
        first.add(new CrossReference("table-ref", "横向表格见页 / Table page ", "table-title", CrossReference.Kind.PAGE, BODY));
        first.add(new ComponentBlock("resources", new RowComponent(List.of(new BoxComponent(145, 80, new ImageComponent(png, ImageComponent.Fit.CONTAIN)),
                new BoxComponent(145, 80, new ImageComponent(jpeg, ImageComponent.Fit.CONTAIN)), new BoxComponent(145, 80, svg)),
                PageMargins.uniform(4), 10, FlowColumn.Alignment.CENTER), 8, 4));
        first.add(new Caption("figure", "Figure", "PNG transparency / JPEG decoding / SVG vectors", BODY));
        first.add(new PageBreak("forms-break"));
        first.add(new Heading("forms-title", "02  可编辑表单 / Editable forms", 2));
        first.add(new Paragraph("forms-info", "中文字段使用完整嵌入字体。单选组和下拉框均保留字段值、默认值与外观。", BODY));
        first.add(new ComponentBlock("form-area", context -> {
            var builder = new com.mirakyux.export4j.graphics.core.DisplayListBuilder(new PageSize(context.width(), 180));
            var text = new com.mirakyux.export4j.text.ParagraphLayouter();
            var labels = text.layout("姓名 / Name\n\n状态 / Status\n\n模式 / Mode\n\n备注 / Notes", context.fonts(), 140, ParagraphStyle.of(11));
            com.mirakyux.export4j.graphics.core.TextLayoutPainter.draw(builder, labels, new com.mirakyux.export4j.graphics.Point(0, 0), INK);
            return new com.mirakyux.export4j.layout.ComponentLayout(context.width(), 180, labels.lines().size(), builder.build().commands(), labels.diagnostics());
        }, 8, 10));
        first.add(new Paragraph("form-options", "单选项从左到右：草稿 draft、最终 final。", BODY));
        List<Block> second = List.of(new Heading("table-title", "03  合并单元格与图文 / Merged grid", 1),
                new Paragraph("table-info", "表头跨页重复；纵向合并连接的行组整体分页，单元格支持内边距、底色与垂直对齐。", BODY),
                new ComponentBlock("advanced-grid", grid(png), 8, 8));
        StringBuilder nestedText = new StringBuilder();
        for (int index = 1; index <= 62; index++) {
            if (index > 1) {
                nestedText.append('\n');
            }
            nestedText.append(String.format(java.util.Locale.ROOT, "FLOW-%02d  Nested column preserves padding and source order.", index));
        }
        var column = new FlowColumn(List.of(new FlowColumn(List.of(new FlowText(nestedText.toString(), ParagraphStyle.of(11), INK, PaginationPolicy.BALANCED)),
                PageMargins.uniform(8), 5, FlowColumn.Alignment.START)), PageMargins.uniform(10), 8, FlowColumn.Alignment.CENTER);
        var third = List.<Block>of(new Heading("flow-title", "04  嵌套容器与续页 / Nested flow", 1),
                new Paragraph("flow-info", "纵向容器按完整行分页，每个续页片段保留两层内边距。", BODY),
                new ComponentBlock("nested-flow", column, 6, 10), new Paragraph("end", "正文结束 / End of report", BODY));
        var document = new SectionedDocument(new DocumentMetadata("Export4j advanced components", "Export4j"), List.of(
                new Section("portrait", portrait, first, 0), new Section("landscape", landscape, second, 1), new Section("nested", portrait, third, 0)));
        var automatic = Navigation.fromHeadings(document);
        var fields = List.<Navigation.Field>of(new Navigation.TextField(target(148, 0, 230, 24), "cn_name", "文档引擎", false, List.of(chinese.data())),
                new Navigation.ChoiceField(target(148, 34, 230, 24), "status", List.of("草稿", "审核", "完成"), "审核", List.of(chinese.data())),
                new Navigation.RadioGroup(target(148, 68, 160, 20), "mode", List.of("draft", "final"), "draft"),
                new Navigation.TextField(target(148, 102, 300, 58), "cn_notes", "可编辑中文备注\n请填写并保存。", true, List.of(chinese.data())));
        var navigation = new Navigation(automatic.bookmarks(), automatic.contents(),
                List.of(new Navigation.Comment(new Navigation.BlockTarget("rich", new Rect(465, 0, 18, 18)), "审阅者", "粗斜体由真实字体变体绘制。")), fields);
        var result = new ComponentDocumentRenderer().layout(document, List.of(template("FIRST / 首页"), template("LANDSCAPE / 横向"), template("FLOW / 容器")),
                navigation, FontGroup.strict(fonts), roles, List.of(new Note("note-foot", "intro", "脚注位于引用标记所在页底部，正文预先留出区域。", Note.Kind.FOOTNOTE),
                        new Note("note-end", "item1", "尾注统一放在文档末尾；编号与正文上标一致。", Note.Kind.ENDNOTE)));
        Files.createDirectories(output.getParent());
        try (var stream = Files.newOutputStream(output)) {
            new PdfBackend().write(result.display(), stream);
        }
        System.out.println("Generated " + result.display().pages().size() + " pages: " + output);
        for (var diagnostic : result.diagnostics()) {
            if (diagnostic.severity() == com.mirakyux.export4j.text.TextDiagnostic.Severity.WARNING) {
                System.err.printf(java.util.Locale.ROOT, "WARNING %s: U+%04X -> U+%04X at UTF-16 [%d,%d)%n", diagnostic.code(),
                        diagnostic.sourceCodePoint(), diagnostic.replacementCodePoint(), diagnostic.sourceStart(), diagnostic.sourceEnd());
            }
        }
    }

    private static Navigation.Target target(double x, double y, double width, double height) {
        return new Navigation.BlockTarget("form-area", new Rect(x, y, width, height));
    }

    private static SectionTemplate template(String first) {
        return new SectionTemplate(page(first), page("ODD / 奇数页"), page("EVEN / 偶数页"));
    }

    private static PageTemplate page(String label) {
        return new PageTemplate(new PageBand(24, 12, List.of(new Placement(new TextComponent("EXPORT4J  /  " + label,
                ParagraphStyle.of(10), INK), new Position.Fractional(0, 0, 1, 1)))), new PageBand(20, 12, List.of(new Placement(
                new PageNumberComponent("第 {page} 页  /  共 {pages} 页", ParagraphStyle.of(10), INK), new Position.Fractional(0, 0, 1, 1)))), List.of(), List.of());
    }

    private static GridTable grid(RgbImage image) {
        List<GridTable.Cell> cells = new ArrayList<>();
        cells.add(cell(0, 0, 1, 3, new TextComponent("MERGED HEADER  /  重复表头", ParagraphStyle.of(12), INK), PALE));
        for (int pair = 0; pair < 12; pair++) {
            int row = 1 + pair * 2;
            String group = String.format(java.util.Locale.ROOT, "GROUP-%02d", pair + 1);
            cells.add(cell(row, 0, 2, 1, new TextComponent(group + "\n纵向合并", ParagraphStyle.of(11), INK), PALE));
            cells.add(cell(row, 1, 1, 1, new TextComponent("图文单元格 " + group, ParagraphStyle.of(11), INK), RgbColor.WHITE));
            cells.add(cell(row + 1, 1, 1, 1, new TextComponent("垂直居中 / centered", ParagraphStyle.of(11), INK), RgbColor.WHITE));
            cells.add(cell(row, 2, 2, 1, new RowComponent(List.of(new BoxComponent(65, 40, new ImageComponent(image, ImageComponent.Fit.CONTAIN)),
                    new TextComponent("PNG\nalpha", ParagraphStyle.of(10), INK)), PageMargins.uniform(0), 8, FlowColumn.Alignment.CENTER), RgbColor.WHITE));
        }
        return new GridTable(25, List.of(1.0, 1.5, 1.5), 1, cells);
    }

    private static GridTable.Cell cell(int row, int col, int rows, int cols, Component component, RgbColor background) {
        return new GridTable.Cell(row, col, rows, cols, component, PageMargins.uniform(6), FlowColumn.Alignment.CENTER, background, INK, 0.6);
    }

    private static RgbImage image(String format) throws IOException {
        var image = new BufferedImage(160, 80, format.equals("png") ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 80; y++) {
            for (int x = 0; x < 160; x++) {
                int alpha = format.equals("png") ? x * 255 / 159 : 255;
                image.setRGB(x, y, alpha << 24 | 0x347fa0 | (y * 100 / 79));
            }
        }
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(image, format, bytes);
        return ImageDecoder.decode(bytes.toByteArray(), 64 * 1024, 20_000);
    }

    private static FontFace load(String name) throws IOException {
        try (var input = AdvancedPdfExample.class.getResourceAsStream("/fonts/" + name)) {
            if (input == null) {
                throw new IOException("Missing explicit font: " + name);
            }
            return TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES);
        }
    }
}
