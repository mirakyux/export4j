// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.Export4j;
import com.mirakyux.export4j.FontFamily;
import com.mirakyux.export4j.font.FontSource;
import com.mirakyux.export4j.math.MathException;
import com.mirakyux.export4j.layout.core.DocumentLayoutException;
import com.mirakyux.export4j.model.ContentOptions;
import com.mirakyux.export4j.model.DocumentTheme;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineImage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Collections;
import java.util.HexFormat;
import java.security.MessageDigest;
import javax.imageio.ImageIO;

/** Independent published-API fixture; no example class or implementation source is referenced. */
public final class EasyExportConsumer {
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

    private EasyExportConsumer() { }

    public static void main(String[] args) throws Exception {
        var output = Files.createDirectories(Path.of(System.getProperty("consumer.outputDirectory", "build/output"))
                .resolve("easy-export"));
        var starterJar = runtimeProvider();
        var fonts = Path.of("../../assets/fonts");
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
                 "fontCacheHits":%d,"uniqueRuntimeProviders":true,"starterJarSha256":"%s"}
                """.formatted(hits, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(Files.readAllBytes(starterJar)))));
        themeExports(latin, output.resolve("theme"));
        System.out.println("Generated configured-once PDF/DOCX source and XML examples in " + output);
    }

    private static void themeExports(FontFamily latin, Path output) throws Exception {
        Files.createDirectories(output);
        var builder = DocumentTheme.builder().bodyRgb(0x223344).cellRgb(0x445566)
                .bodyFormat(p -> p.fontSize(10).lineHeight(28))
                .cellFormat(c -> c.fontSize(9).lineHeight(28));
        var markdown = new StringBuilder();
        var html = new StringBuilder();
        for (int level = 1; level <= 6; level++) {
            int size = 19 - level;
            builder.headingFormat(level, p -> p.fontSize(size).lineHeight(32).spaceBefore(3).spaceAfter(5))
                    .headingRgb(level, 0x884422 + level * 0x080808);
            markdown.append("#".repeat(level)).append(" Glyph").append(level).append("\n\n")
                    .append("#".repeat(level)).append(" Header").append(level).append(" $x^2$\n\n");
            html.append("<h").append(level).append(">Glyph").append(level).append("</h").append(level).append('>')
                    .append("<h").append(level).append(">Header").append(level).append(" $x^2$</h").append(level).append('>');
        }
        markdown.append("GlyphBody\n\nBody $x^2$\n\n| Column |\n| --- |\n| GlyphCell |\n| Cell $x^2$ |\n");
        html.append("<p>GlyphBody</p><p>Body $x^2$</p><table><tr><td>GlyphCell</td></tr><tr><td>Cell $x^2$</td></tr></table>");
        var theme = builder.build();
        var declaredBodyValues = ContentOptions.builder().fontSize(10).rgb(0x223344).build();
        try (var exporter = Export4j.builder(latin).theme(theme).build()) {
            pair(exporter.markdown(markdown.toString()), output, "markdown", 8);
            pair(exporter.html(html.toString()), output, "html", 8);
            pair(exporter.markdown(html.toString()).options(ContentOptions.builder().rawHtml(true).build()),
                    output, "raw-html", 8);
            pair(exporter.xml(XML, Map.of("body", markdown.toString())), output, "xml", 8);
            pair(exporter.markdown(markdown.toString()).options(declaredBodyValues), output, "request", 8);
            pair(exporter.html("<div style='color:#000000;font-size:10pt'><h1>GlyphCssHeader</h1><h1>CssHeader $x^2$</h1>"
                    + "<table><tr><td>GlyphCssCell</td></tr><tr><td>CssCell $x^2$</td></tr></table></div>"), output, "css", 2);
        }
        try (var exporter = Export4j.builder(latin).theme(theme)
                .defaults(ContentOptions.builder().rgb(0).build()).build()) {
            pair(exporter.markdown(markdown.toString()), output, "global", 8);
        }
        // A DOCX declares the chosen family; the reader owns installed-font substitution.
        try (var exporter = Export4j.builder(latin).build()) {
            requireResult(exporter.text("中文字体声明").docx(output.resolve("declaration-only.docx")), 0);
            Path protectedPdf = output.resolve("physical-glyph-rejection.bin");
            Files.writeString(protectedPdf, "old-target");
            try {
                exporter.text("中文字体声明").pdf(protectedPdf);
                throw new IllegalStateException("PDF accepted missing physical glyphs");
            } catch (DocumentLayoutException expected) {
                if (!Files.readString(protectedPdf).equals("old-target")) {
                    throw new IllegalStateException("missing glyph failure changed old PDF target", expected);
                }
            }
        }
    }

    private static void pair(Export4j.Request request, Path output, String name, int formulas) throws Exception {
        requireResult(request.pdf(output.resolve(name + ".pdf")), formulas);
        requireResult(request.docx(output.resolve(name + ".docx")), formulas);
    }

    private static Path runtimeProvider() throws Exception {
        var jar = Path.of(Export4j.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!Files.isRegularFile(jar) || !jar.getFileName().toString().endsWith(".jar")) {
            throw new IllegalStateException("starter API must load from a published JAR");
        }
        for (var type : java.util.List.of(Export4j.class, FontFamily.class)) {
            if (Collections.list(type.getClassLoader().getResources(type.getName().replace('.', '/') + ".class")).size() != 1) {
                throw new IllegalStateException("starter API has duplicate runtime providers");
            }
        }
        return jar;
    }

    /** Typed data remains consumer-owned until the target operation binds it. */
    public record TemplateData(String body) { }

    /** Independent ordinary JavaBean fixture, without a JSON mapper dependency. */
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
