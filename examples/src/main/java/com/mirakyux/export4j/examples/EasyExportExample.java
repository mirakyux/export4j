// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.Export4j;
import com.mirakyux.export4j.FontFamily;
import com.mirakyux.export4j.font.FontSource;
import com.mirakyux.export4j.math.MathException;
import com.mirakyux.export4j.model.DocumentTheme;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineImage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.imageio.ImageIO;

/** Configure fonts and style once; business calls select only source, data and destination. */
public final class EasyExportExample {
    private static final String SOURCE = """
            # EASY-EXPORT / configured once

            中英文正文 Chinese and English share one configured exporter.

            Regular **bold** *italic* ***both*** text.

            Chinese formula $x + \\text{年}$ uses the same declared physical fonts.

            Inline $x_1^2$ and $\\frac{a+b}{c}$ remain editable in Word.

            | Kind | Value |
            | --- | --- |
            | Root | $\\sqrt{x}$ |
            | Chinese | 中文单元格 |

            $$
            x^2 + y^2 = z^2
            $$

            Explicit image ![badge](sample:badge) follows the shared resource declaration.
            """;
    private static final String XML = """
            <document version="1" content-format="MARKDOWN">
              <content id="body" source="body"/>
            </document>
            """;
    private static final String HTML = """
            <p>HTML-ENTRY 中文正文 with <strong>bold</strong> text and $x^2$.</p>
            <p><img src="sample:badge" alt="badge"/> Explicit resource.</p>
            """;
    private static final String LITERAL = "TEXT-ENTRY 中文 **literal** <b>HTML</b> $x^2$";

    private EasyExportExample() { }

    public static void main(String[] args) throws Exception {
        var output = Files.createDirectories(Path.of("examples/build/easy-export"));
        var fonts = Path.of("assets/fonts");
        var latin = FontFamily.builder("Liberation Sans", FontSource.file(fonts.resolve("LiberationSans-Regular.ttf")))
                .bold(FontSource.file(fonts.resolve("LiberationSans-Bold.ttf")))
                .italic(FontSource.file(fonts.resolve("LiberationSans-Italic.ttf")))
                .boldItalic(FontSource.file(fonts.resolve("LiberationSans-BoldItalic.ttf"))).build();
        var chinese = FontFamily.of("LXGW WenKai TC", FontSource.file(fonts.resolve("LXGWWenKaiTC-Regular.ttf")));
        var theme = DocumentTheme.builder().rgb(0x223344)
                .bodyFormat(body -> body.fontSize(12).lineHeight(32).spaceAfter(8))
                .cellFormat(cell -> cell.fontSize(12).lineHeight(32)).build();
        long hits;
        try (var exporter = Export4j.builder(latin).eastAsia(chinese).theme(theme)
                .image("sample:badge", badge()).build()) {
            requireResult(exporter.markdown(SOURCE).pdf(output.resolve("markdown.pdf")), 5);
            requireResult(exporter.markdown(SOURCE).docx(output.resolve("markdown.docx")), 5);
            requireResult(exporter.xml(XML, Map.of("body", SOURCE)).pdf(output.resolve("xml.pdf")), 5);
            requireResult(exporter.xml(XML, Map.of("body", SOURCE)).docx(output.resolve("xml.docx")), 5);
            requireResult(exporter.xml(XML, new TemplateData(SOURCE)).pdf(output.resolve("xml-record.pdf")), 5);
            requireResult(exporter.xml(XML, new TemplateData(SOURCE)).docx(output.resolve("xml-record.docx")), 5);
            requireResult(exporter.xml(XML, new TemplateBean(SOURCE)).pdf(output.resolve("xml-bean.pdf")), 5);
            requireResult(exporter.xml(XML, new TemplateBean(SOURCE)).docx(output.resolve("xml-bean.docx")), 5);
            requireResult(exporter.html(HTML).pdf(output.resolve("html.pdf")), 1);
            requireResult(exporter.html(HTML).docx(output.resolve("html.docx")), 1);
            requireResult(exporter.text(LITERAL).pdf(output.resolve("text.pdf")), 0);
            requireResult(exporter.text(LITERAL).docx(output.resolve("text.docx")), 0);

            // A PDF-only formula must not be rejected by an unrelated DOCX support limit.
            requireResult(exporter.markdown("VISUAL-ONLY $\\bar{x}$").pdf(output.resolve("visual-only.pdf")), 1);
            var protectedOutput = output.resolve("strict-native-rejection.bin");
            Files.writeString(protectedOutput, "old-target");
            try {
                exporter.markdown("VISUAL-ONLY $\\bar{x}$").docx(protectedOutput);
                throw new IllegalStateException("unsupported native mathematics was silently changed");
            } catch (MathException expected) {
                if (expected.reason() != MathException.Reason.UNSUPPORTED_SYNTAX
                        || !Files.readString(protectedOutput).equals("old-target")) {
                    throw new IllegalStateException("strict native failure changed the previous destination", expected);
                }
            }
            hits = exporter.cacheStats().hits();
            if (hits == 0) { throw new IllegalStateException("default font cache was not reused"); }
        }
        Files.writeString(output.resolve("source.md"), SOURCE);
        Files.writeString(output.resolve("source.xml"), XML);
        Files.writeString(output.resolve("source.html"), HTML);
        Files.writeString(output.resolve("source.txt"), LITERAL);
        Files.writeString(output.resolve("result.json"), """
                {"markdownFormulas":5,"xmlFormulas":5,"htmlFormulas":1,"textFormulas":0,
                 "visualOnlyFormulas":1,"degradations":0,"strictNativeFailurePreserved":true,
                 "fontCacheHits":%d}
                """.formatted(hits));
        System.out.println("Generated configured-once PDF/DOCX source and XML examples in " + output);
    }

    /** A record exposes its components as template properties. */
    public record TemplateData(String body) { }

    /** An ordinary DTO exposes public getter properties without a JSON conversion step. */
    public static final class TemplateBean {
        private final String body;
        public TemplateBean(String body) { this.body = java.util.Objects.requireNonNull(body, "body"); }
        public String getBody() { return body; }
    }

    private static void requireResult(Export4j.Result result, int formulas) {
        if (result.formulas() != formulas || !result.degradations().isEmpty()) {
            throw new IllegalStateException("unexpected formula count or representation change");
        }
    }

    private static InlineImage badge() throws Exception {
        var image = new BufferedImage(24, 16, BufferedImage.TYPE_INT_RGB);
        try {
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    image.setRGB(x, y, x < 12 ? 0x224466 : 0xCC6600);
                }
            }
            var bytes = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", bytes)) { throw new IllegalStateException("PNG writer unavailable"); }
            return InlineImage.atWidth(ImageResource.load(bytes.toByteArray()), 30, "badge");
        } finally { image.flush(); }
    }
}
