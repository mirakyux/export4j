// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.ComponentDocumentRenderer;
import com.mirakyux.export4j.components.ImageComponent;
import com.mirakyux.export4j.components.LineComponent;
import com.mirakyux.export4j.components.Navigation;
import com.mirakyux.export4j.components.PageNumberComponent;
import com.mirakyux.export4j.components.ShapeComponent;
import com.mirakyux.export4j.components.TableComponent;
import com.mirakyux.export4j.components.TableOfContents;
import com.mirakyux.export4j.components.TextComponent;
import com.mirakyux.export4j.components.WatermarkComponent;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.RgbImage;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.ComponentBlock;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.PageBand;
import com.mirakyux.export4j.layout.core.PageComposer;
import com.mirakyux.export4j.layout.core.PageTemplate;
import com.mirakyux.export4j.layout.core.Placement;
import com.mirakyux.export4j.layout.core.Position;
import com.mirakyux.export4j.model.Block;
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
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextAlignment;

/** End-to-end component showcase, including actual PDF navigation and interactive fields. */
public final class ComponentPdfExample {
    private static final RgbColor INK = new RgbColor(0.12, 0.24, 0.36);
    private static final RgbColor PALE = new RgbColor(0.91, 0.95, 0.97);
    private static final ParagraphFormat BODY = new ParagraphFormat(11, 18, 0, 0, ParagraphAlignment.LEFT, 0, 12, false);
    private static final ParagraphFormat HEADING = new ParagraphFormat(19, 29, 0, 0, ParagraphAlignment.LEFT, 10, 12, true);

    private ComponentPdfExample() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length > 1) {
            throw new IllegalArgumentException("Expected zero arguments or one output PDF path");
        }
        Path output = Path.of(args.length == 0 ? "examples/build/export4j-components-example.pdf" : args[0]).toAbsolutePath();
        Files.createDirectories(output.getParent());
        var fonts = List.of(load("LiberationSans-Regular.ttf"), load("LXGWWenKaiTC-Regular.ttf"));
        var settings = new PageSettings(PageSize.A4, PageMargins.uniform(48));
        var header = new PageBand(26, 12, List.of(
                new Placement(new TextComponent("EXPORT4J ENGINE  /  组件与交互", ParagraphStyle.of(10), INK),
                        new Position.Relative(Position.Anchor.TOP_LEFT, settings.contentWidth(), 18, 0, 0)),
                new Placement(LineComponent.horizontal(0.7, INK), new Position.Fractional(0, 0.92, 1, 0.08))));
        var footer = new PageBand(20, 12, List.of(new Placement(
                new PageNumberComponent("第 {page} 页 / 共 {pages} 页", new ParagraphStyle(10, 15, 0, 0, TextAlignment.RIGHT), INK),
                new Position.Fractional(0, 0, 1, 1))));
        var template = new PageTemplate(header, footer, List.of(new Placement(
                new WatermarkComponent("EXPORT4J", 62, -32, new RgbColor(0.95, 0.96, 0.97)),
                new Position.Fractional(0.08, 0.15, 0.84, 0.7))), List.of(new Placement(
                new ShapeComponent(ShapeComponent.Kind.ELLIPSE, INK, INK, 0), new Position.Absolute(new Rect(535, 22, 12, 12)))));

        List<Block> blocks = new ArrayList<>();
        blocks.add(new Paragraph("overview", "01  文档组件", HEADING));
        blocks.add(new Paragraph("intro", "同一份文档包含流式正文和定位组件。页眉页脚预留区域，水印位于正文下方；"
                + "图像、形状和线条使用明确的尺寸与坐标。", BODY));
        blocks.add(new ComponentBlock("visuals", ComponentPdfExample::gallery, 0, 18));
        blocks.add(new ComponentBlock("summary", new TableComponent(summary()), 0, 16));
        blocks.add(new Paragraph("forms", "02  评论与填写", HEADING));
        blocks.add(new ComponentBlock("form-panel", ComponentPdfExample::formPanel, 0, 18));
        blocks.add(new Paragraph("records", "03  跨页记录", HEADING));
        blocks.add(new Paragraph("records-note", "以下记录按顺序跨页，续页重复表头。目录与侧栏书签均跳转到正文的实际位置。", BODY));
        blocks.add(records());
        blocks.add(new Paragraph("closing", "04  完成", HEADING));
        blocks.add(new Paragraph("closing-note", "这份示例将绘制内容、页面装饰和交互信息合成为 PDF。"
                + "可通过目录跳转章节，在批注图标查看评论，并在支持表单的阅读器中填写和保存。", BODY));
        var navigation = new Navigation(List.of(new Navigation.Bookmark("文档组件", "overview", 1),
                new Navigation.Bookmark("图像、形状与线条", "visuals", 2),
                new Navigation.Bookmark("评论与填写", "forms", 1),
                new Navigation.Bookmark("跨页记录", "records", 1), new Navigation.Bookmark("完成", "closing", 1)),
                new TableOfContents(true, "目录 / Contents", ParagraphStyle.of(12)),
                List.of(new Navigation.Comment(new Navigation.BlockTarget("forms", new Rect(475, 4, 18, 18)),
                        "Export4j 审阅者", "请填写姓名和备注，并确认已阅读本文档。此内容是 PDF 评论批注。")),
                List.of(new Navigation.TextField(new Navigation.BlockTarget("form-panel", new Rect(125, 36, 320, 25)),
                                "name", "Ada Lovelace", false),
                        new Navigation.TextField(new Navigation.BlockTarget("form-panel", new Rect(125, 76, 320, 48)),
                                "notes", "Review the component report.\nEditable PDF form.", true),
                        new Navigation.CheckBox(new Navigation.BlockTarget("form-panel", new Rect(125, 140, 18, 18)), "accepted", true)));
        var result = new ComponentDocumentRenderer().layout(new Document(
                new DocumentMetadata("Export4j 组件与交互示例", "Export4j contributors"), settings, blocks), template, navigation, fonts);
        try (var stream = Files.newOutputStream(output)) {
            new PdfBackend().write(result.display(), stream);
        }
        System.out.println("Generated: " + output);
        System.out.println("Pages: " + result.display().pages().size() + "; contents: " + result.bodyPageOffset());
        System.out.println("Bookmarks: " + result.display().extras().bookmarks().size() + "; fields: " + result.display().extras().fields().size());
    }

    private static ComponentLayout gallery(ComponentContext context) {
        return panel(context, 175, List.of(
                new Placement(new ImageComponent(image(), ImageComponent.Fit.CONTAIN), new Position.Absolute(new Rect(0, 0, 150, 85))),
                new Placement(new ShapeComponent(ShapeComponent.Kind.RECTANGLE, PALE, INK, 1.5),
                        new Position.Relative(Position.Anchor.TOP_CENTER, 90, 85, 0, 0)),
                new Placement(new ShapeComponent(ShapeComponent.Kind.ELLIPSE, new RgbColor(0.89, 0.93, 0.87), INK, 1.5),
                        new Position.Relative(Position.Anchor.TOP_RIGHT, 90, 85, -20, 0)),
                label("图像 / RGB", 0, 96, 150), label("矩形 / Rectangle", 205, 96, 140), label("椭圆 / Ellipse", 385, 96, 110),
                label("图像使用固定坐标；矩形居中、椭圆靠右；底部线条按比例布局。", 0, 133, 495),
                new Placement(LineComponent.horizontal(1, INK), new Position.Fractional(0, 0.98, 1, 0.02))));
    }

    private static ComponentLayout formPanel(ComponentContext context) {
        return panel(context, 220, List.of(
                new Placement(new ShapeComponent(ShapeComponent.Kind.RECTANGLE, PALE, new RgbColor(0.75, 0.82, 0.86), 0.7),
                        new Position.Fractional(0, 0, 1, 1)),
                label("姓名 / Name", 15, 39, 105), label("备注 / Notes", 15, 81, 105),
                label("已阅读 / Read", 155, 140, 190),
                label("在支持 PDF 表单的阅读器中编辑并保存。", 15, 170, 460),
                label("点击输入区域填写，保存后可再次编辑。", 15, 193, 460)));
    }

    private static Placement label(String text, double x, double y, double width) {
        return new Placement(new TextComponent(text, ParagraphStyle.of(10), INK), new Position.Absolute(new Rect(x, y, width, 22)));
    }

    private static ComponentLayout panel(ComponentContext context, double height, List<Placement> placements) {
        var size = new PageSize(context.width(), height);
        var source = new DisplayDocument(DocumentMetadata.EMPTY, List.of(new DisplayPage(size, List.of())));
        var composition = new PageComposer().composeDetailed(source, new PageSettings(size, PageMargins.uniform(0)),
                new PageTemplate(PageBand.EMPTY, PageBand.EMPTY, List.of(), placements), context.fonts());
        return new ComponentLayout(context.width(), height,
                composition.placements().stream().mapToInt(p -> p.layout().textLines()).sum(),
                composition.display().pages().getFirst().commands(),
                composition.placements().stream().flatMap(p -> p.layout().diagnostics().stream()).toList());
    }

    private static Table summary() {
        return new Table("summary-table", List.of(new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(2)),
                List.of(row("布局方式", "应用"), row("绝对位置", "相对页面的明确坐标"), row("相对位置", "对齐父区域锚点或按比例定位")),
                1, new TableFormat(PageMargins.uniform(5), 0, 0));
    }

    private static Table records() {
        List<TableRow> rows = new ArrayList<>();
        rows.add(row("记录 / ID", "内容 / Description"));
        for (int index = 1; index <= 24; index++) {
            rows.add(row(String.format(Locale.ROOT, "ITEM-%02d", index),
                    "组件组合保留来源，整行跨页，页眉与页脚在最终页数确定后统一绘制。"));
        }
        return new Table("data", List.of(new ColumnWidth.Fixed(95), new ColumnWidth.Fraction(1)), rows, 1,
                new TableFormat(PageMargins.uniform(7), 0, 14));
    }

    private static TableRow row(String first, String second) {
        return new TableRow(List.of(new TableCell(first, CellFormat.of(10)), new TableCell(second, CellFormat.of(10))));
    }

    private static RgbImage image() {
        int width = 180;
        int height = 100;
        byte[] pixels = new byte[width * height * 3];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = (y * width + x) * 3;
                boolean accent = (x - 125) * (x - 125) + (y - 32) * (y - 32) < 18 * 18;
                pixels[index] = (byte) (accent ? 224 : 35 + x / 3);
                pixels[index + 1] = (byte) (accent ? 183 : 75 + y / 2);
                pixels[index + 2] = (byte) (accent ? 95 : 105 + x / 4);
            }
        }
        return new RgbImage(width, height, pixels);
    }

    private static FontFace load(String name) throws IOException {
        try (InputStream stream = ComponentPdfExample.class.getResourceAsStream("/fonts/" + name)) {
            if (stream == null) {
                throw new IOException("Bundled example font is missing");
            }
            return TrueTypeFontLoader.load(stream, TrueTypeFontLoader.DEFAULT_MAX_BYTES);
        }
    }
}
