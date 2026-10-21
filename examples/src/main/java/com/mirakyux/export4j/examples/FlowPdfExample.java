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
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.graphics.core.TextLayoutPainter;
import com.mirakyux.export4j.layout.core.DocumentLayouter;
import com.mirakyux.export4j.layout.core.DocumentPainter;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.text.ParagraphLayouter;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextAlignment;

/** Semantic document to paginated PDF; page decorations are an explicit example-level drawing step. */
public final class FlowPdfExample {
    private FlowPdfExample() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length > 1) {
            throw new IllegalArgumentException("Expected zero arguments or one output PDF path");
        }
        Path output = Path.of(args.length == 0 ? "examples/build/export4j-flow-example.pdf" : args[0])
                .toAbsolutePath();
        Files.createDirectories(output.getParent());
        List<FontFace> fonts = List.of(loadFont("LiberationSans-Regular.ttf"), loadFont("LXGWWenKaiTC-Regular.ttf"));
        ParagraphFormat title = new ParagraphFormat(25, 38, 0, 0, ParagraphAlignment.LEFT, 0, 16, true);
        ParagraphFormat body = new ParagraphFormat(12, 21, 24, 0, ParagraphAlignment.LEFT, 0, 12, false);
        ParagraphFormat note = new ParagraphFormat(11, 18, 0, 0, ParagraphAlignment.LEFT, 12, 8, true);
        StringBuilder text = new StringBuilder();
        for (int index = 1; index <= 22; index++) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(String.format(Locale.ROOT, "片段 %02d：", index))
                    .append("语义段落保存正文与样式，字体配置由调用方显式传入。布局引擎先完成换行，")
                    .append("再把完整的行依次放入页面内容区域。跨页只移动已有行，不重新计算断点，")
                    .append("因此文字顺序、字体回退、基线与原文位置都能持续追踪。")
                    .append(" English text follows the same measured glyph positions across page boundaries.");
        }
        Document document = new Document(new DocumentMetadata("Export4j 自动分页报告",
                "Export4j contributors"), PageSettings.A4, List.of(
                new Paragraph("title", "Export4j 自动分页报告", title),
                new Paragraph("intro", "从语义模型到页面布局，再到嵌入字体的 PDF。"
                        + "下方长段落将自动跨页，页边距与行距保持一致。", note),
                new Paragraph("long-body", text.toString(), body),
                new Paragraph("conclusion", "验证结论\n段落在完整行之间分页；正文没有丢失或重复。"
                        + "\n孤行控制与正式页眉页脚组件将在后续实现。", note)));
        var layout = new DocumentLayouter().layout(document, fonts);
        DisplayDocument display = decorate(DocumentPainter.paint(layout), fonts);
        try (OutputStream stream = Files.newOutputStream(output)) {
            new PdfBackend().write(display, stream);
        }
        System.out.println("Generated: " + output);
        System.out.println("Pages: " + layout.pages().size());
        System.out.println("Paragraph fragments: "
                + layout.pages().stream().mapToInt(page -> page.fragments().size()).sum());
    }

    private static DisplayDocument decorate(DisplayDocument document, List<FontFace> fonts) {
        List<DisplayPage> pages = new ArrayList<>();
        ParagraphLayouter text = new ParagraphLayouter();
        for (int index = 0; index < document.pages().size(); index++) {
            DisplayPage source = document.pages().get(index);
            DisplayListBuilder decoration = new DisplayListBuilder(source.size());
            RgbColor blue = new RgbColor(0.15, 0.35, 0.75);
            decoration.fillRect(new Rect(54, 42, source.size().width() - 108, 2), blue);
            TextLayoutPainter.draw(decoration, text.layout("Export4j / Automatic paragraph flow", fonts,
                    source.size().width() - 108, new ParagraphStyle(9, 14, 0, 0, TextAlignment.LEFT)),
                    new Point(54, 23), blue);
            TextLayoutPainter.draw(decoration, text.layout("第 " + (index + 1) + " / "
                    + document.pages().size() + " 页", fonts, source.size().width() - 108,
                    new ParagraphStyle(9, 14, 0, 0, TextAlignment.RIGHT)),
                    new Point(54, source.size().height() - 36), blue);
            List<DrawCommand> commands = new ArrayList<>(source.commands());
            commands.addAll(decoration.build().commands());
            pages.add(new DisplayPage(source.size(), commands));
        }
        return new DisplayDocument(document.metadata(), pages);
    }

    private static FontFace loadFont(String name) throws IOException {
        try (InputStream input = FlowPdfExample.class.getResourceAsStream("/fonts/" + name)) {
            if (input == null) {
                throw new IOException("Bundled example font is missing");
            }
            return TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES);
        }
    }
}
