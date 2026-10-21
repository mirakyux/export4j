// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.graphics.core.TextLayoutPainter;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.text.ParagraphLayouter;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextAlignment;

/** Embedded-font Chinese and Latin wrapping demo; fonts are local, licensed classpath resources. */
public final class TextPdfExample {
    private TextPdfExample() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length > 1) {
            throw new IllegalArgumentException("Expected zero arguments or one output PDF path");
        }
        Path output = Path.of(args.length == 0 ? "examples/build/export4j-text-example.pdf" : args[0])
                .toAbsolutePath();
        Files.createDirectories(output.getParent());
        List<FontFace> fonts = List.of(loadFont("LiberationSans-Regular.ttf"),
                loadFont("LXGWWenKaiTC-Regular.ttf"));
        ParagraphLayouter layouter = new ParagraphLayouter();
        DisplayListBuilder page = new DisplayListBuilder(PageSize.A4);
        RgbColor ink = new RgbColor(0.12, 0.18, 0.27);
        RgbColor blue = new RgbColor(0.15, 0.35, 0.75);
        page.fillRect(new Rect(36, 36, 523, 6), blue);
        var heading = layouter.layout("Export4j 字体与文本排版", fonts, 523,
                new ParagraphStyle(23, 38, 0, 0, TextAlignment.LEFT));
        TextLayoutPainter.draw(page, heading, new Point(36, 58), ink);
        var paragraph = layouter.layout(
                "中文与 English 可以在同一段落中排版。Latin characters use Liberation Sans，"
                        + "中文按显式顺序回退到霞鹜文楷 TC。文字先测量，再换行，最后通过同一份字体文件生成 PDF。"
                        + "这里演示首行缩进、固定行距、标点换行以及可以复制的 Unicode 文本。"
                        + "\nExplicit line breaks preserve the original source positions. "
                        + "The PDF backend consumes measured glyph advances without deciding line breaks.",
                fonts, 523, new ParagraphStyle(14, 24, 28, 0, TextAlignment.LEFT));
        TextLayoutPainter.draw(page, paragraph, new Point(36, 120), ink);
        double y = 150 + paragraph.height();
        for (TextAlignment alignment : TextAlignment.values()) {
            page.fillRect(new Rect(36, y, 523, 48), new RgbColor(0.95, 0.96, 0.98));
            var aligned = layouter.layout(alignment + " 对齐示例：中文 / English", fonts, 499,
                    new ParagraphStyle(13, 24, 0, 0, alignment));
            TextLayoutPainter.draw(page, aligned, new Point(48, y + 10), ink);
            y += 64;
        }
        var footer = layouter.layout("字体已嵌入 · 自动分页示例见 flowPdf · 复杂塑形尚未实现",
                fonts, 523, new ParagraphStyle(10, 18, 0, 0, TextAlignment.LEFT));
        TextLayoutPainter.draw(page, footer, new Point(36, 790), ink);
        var document = new DisplayDocument(new DocumentMetadata("Export4j 字体与文本排版",
                "Export4j contributors"), List.of(page.build()));
        try (OutputStream stream = Files.newOutputStream(output)) {
            new PdfBackend().write(document, stream);
        }
        System.out.println("Generated: " + output);
        System.out.println("Font fallback diagnostics: " + paragraph.diagnostics().size());
    }

    private static FontFace loadFont(String name) throws IOException {
        try (InputStream input = TextPdfExample.class.getResourceAsStream("/fonts/" + name)) {
            if (input == null) {
                throw new IOException("Bundled example font is missing");
            }
            return TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES);
        }
    }
}
