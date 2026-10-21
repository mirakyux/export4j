// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.imageio.ImageIO;
import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.ComponentDocumentLayout;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.OpenTypeText;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.layout.core.ComponentBlock;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.LineBreakPolicy;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextAlignment;

/** Small authoring entry points, styled semantic blocks and precomputed justification fixtures. */
public final class AuthoringExample {
    private static final Path OUTPUT = Path.of("examples/build/authoring");
    static final ParagraphFormat BODY = new ParagraphFormat(12, 24, 0, 0, ParagraphAlignment.JUSTIFY, 0, 6, false)
            .withLineBreakPolicy(LineBreakPolicy.CJK_STRICT);
    static final PageSettings PAGE = new PageSettings(new PageSize(420, 595), PageMargins.uniform(36));
    static final String CHINESE = "中文排版需要保持文字顺序，正确处理（开头与结尾的标点），并让正文两端对齐。"
            + "这里检查省略号……与破折号——，这些组合应当保持完整。末行使用自然宽度。";
    static final String LATIN = "Justified text distributes spaces on wrapped lines while keeping the last line natural. "
            + "Fonts stay explicit and glyph shapes retain their original proportions. ";
    private AuthoringExample() { }

    static FontFace face(String name) throws IOException {
        try (var input = AuthoringExample.class.getResourceAsStream("/fonts/" + name)) {
            if (input == null) { throw new IOException("example font is missing"); }
            return TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES);
        }
    }

    static DocumentEngine engine() throws IOException {
        return DocumentEngine.builder(FontGroup.strict(List.of(face("LiberationSans-Regular.ttf"), face("LXGWWenKaiTC-Regular.ttf"), face("NotoNaskhArabic-Regular.ttf"))))
                .fontRole("bold", FontGroup.strict(List.of(face("LiberationSans-Bold.ttf"), face("LXGWWenKaiTC-Regular.ttf"))))
                .build();
    }

    static InlineText text(String value) { return new InlineText(value, InlineStyle.of(12)); }

    static InlineText accent(String value) {
        return new InlineText(value, new InlineStyle("bold", 12, 0x285080, 0, true, false), "https://example.com/authoring");
    }

    static InlineImage image() throws IOException {
        var raster = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 8; y++) { for (int x = 0; x < 8; x++) { raster.setRGB(x, y, (x + y) % 2 == 0 ? 0x285080 : 0xcadbea); } }
        var bytes = new ByteArrayOutputStream(); ImageIO.write(raster, "png", bytes);
        return new InlineImage(ImageResource.load(bytes.toByteArray()), 16, 16, 0, "Generated checker", "https://example.com/image");
    }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        Files.writeString(OUTPUT.resolve("simple-cjk.txt"), CHINESE.repeat(2));
        Files.writeString(OUTPUT.resolve("simple-latin.txt"), LATIN.repeat(2));
        var engine = engine(); var backend = new PdfBackend();
        var quick = Document.builder().heading("季度报告", 1).paragraph("正文可以直接编写，普通内容自动生成 ID。")
                .bullet("支持显式字体与结构化诊断")
                .numbered("steps", "准备数据").numbered("steps", "导出报告")
                .table(List.of("指标", "数值"), List.of(List.of("收入", "120"), List.of("成本", "80"))).build();
        var simpleEngine = DocumentEngine.builder(FontGroup.strict(List.of(face("LiberationSans-Regular.ttf"), face("LXGWWenKaiTC-Regular.ttf")))).build();
        preview("quickstart", simpleEngine.write(quick, backend, OUTPUT.resolve("quickstart.pdf")));

        var document = Document.builder(new DocumentMetadata("Typography and authoring", "mirakyux"), PAGE).bodyFormat(BODY)
                .add(Heading.rich("title", List.of(accent("Report / "), text("中文排版")), 1, BODY))
                .paragraph("simple-cjk", CHINESE.repeat(2)).paragraph("simple-latin", LATIN.repeat(2)).pageBreak()
                .heading(List.of(accent("Rich heading / "), text("富文本标题")), 1)
                .bullet(List.of(accent("Linked list / "), text("保留颜色、下划线与外链")))
                .numbered("steps", List.of(accent("Numbered / "), text("真实粗体与编号")))
                .paragraph("shaped", "AVATAR a\u0301 العربية لا 123 " + LATIN + CHINESE)
                .paragraph("objects", List.of(text("PAGE="), new InlineField(InlineField.Kind.PAGE, "", 24, InlineStyle.of(12)),
                        text(" IMAGE="), image(), text(" " + LATIN)))
                .add(Table.text("table", List.of("Language", "Justified content"), List.of(List.of("中文", CHINESE), List.of("Latin", LATIN)),
                        new CellFormat(12, 24, ParagraphAlignment.JUSTIFY, LineBreakPolicy.CJK_STRICT))).build();
        var layout = engine.write(document, backend, OUTPUT.resolve("typography.pdf"));
        preview("typography", layout);
        var adapter = new DocxAdapter();
        try (var output = Files.newOutputStream(OUTPUT.resolve("typography.docx"))) { adapter.write(document, output); }
        try (var input = Files.newInputStream(OUTPUT.resolve("typography.docx"))) {
            var imported = adapter.read(input, DocxAdapter.Policy.STRICT);
            if (!imported.diagnostics().isEmpty()) { throw new IOException("unexpected semantic import loss"); }
            var restored = new Document(imported.document().metadata(), PAGE, imported.document().sections().getFirst().blocks());
            preview("imported", engine.write(restored, backend, OUTPUT.resolve("imported.pdf")));
        }

        var component = new OpenTypeText("COMPONENT / " + CHINESE.repeat(2) + "\nالعربية لا 123 " + LATIN,
                new ParagraphStyle(12, 24, 0, 0, TextAlignment.JUSTIFY, LineBreakPolicy.CJK_STRICT), RgbColor.BLACK);
        Files.writeString(OUTPUT.resolve("component.txt"), component.text());
        preview("component", engine.write(Document.builder().pageSettings(PAGE).add(new ComponentBlock("component", component)).build(),
                backend, OUTPUT.resolve("component.pdf")));
        System.out.println("Authoring fixtures: " + OUTPUT.toAbsolutePath());
    }

    private static void preview(String name, ComponentDocumentLayout layout) throws IOException {
        try (var output = Files.newOutputStream(OUTPUT.resolve(name + ".svg"))) {
            // Static previews explicitly contain page drawings; links remain in the corresponding PDF.
            new SvgBackend().write(new DisplayDocument(layout.display().metadata(), layout.display().pages()), output);
        }
        var lines = new ArrayList<String>();
        for (var page : layout.body().pages()) {
            for (var fragment : page.fragments()) {
                for (var line : fragment.layout().lines()) {
                    lines.add(String.join("\t", fragment.blockId(), Integer.toString(page.pageIndex()), Integer.toString(line.sourceStart()),
                            Integer.toString(line.sourceEnd()), Double.toString(fragment.origin().x() + line.offsetX()),
                            Double.toString(fragment.origin().y() + line.baselineY()), Double.toString(line.width()), Base64.getEncoder().encodeToString(line.outlines().stream()
                                    .map(com.mirakyux.export4j.text.TextOutline::logicalText).collect(java.util.stream.Collectors.joining()).getBytes(StandardCharsets.UTF_8))));
                }
            }
        }
        Files.write(OUTPUT.resolve(name + "-lines.tsv"), lines, StandardCharsets.UTF_8);
    }
}
