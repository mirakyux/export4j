// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.raster.RasterBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.math.MarkdownMath;
import com.mirakyux.export4j.math.MathException;
import com.mirakyux.export4j.math.MathOptions;
import com.mirakyux.export4j.math.TexMath;
import com.mirakyux.export4j.model.ParagraphFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Properties;

/** Public mathematical API proof, using only independently resolved Maven artifacts. */
public final class MathConsumer {
    private static final String SOURCE = "MATH-CONSUMER\n\nInline $\\frac{1}{2}$ and $x_1^2$.\n\n"
            + "$$\\sqrt{a+b}$$\n\nRepeated $\\frac{1}{2}$.\n";

    private MathConsumer() { }

    public static void main(String[] args) throws Exception {
        var output = Files.createDirectories(Path.of(System.getProperty("consumer.outputDirectory", "build/output"))
                .resolve("math"));
        var jar = Path.of(TexMath.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        require(Files.isRegularFile(jar) && jar.getFileName().toString().endsWith(".jar"),
                "mathematical API must load from a published JAR");
        var loader = TexMath.class.getClassLoader();
        require(Collections.list(loader.getResources("com/mirakyux/export4j/math/TexMath.class")).size() == 1
                && Collections.list(loader.getResources("org/scilab/forge/jlatexmath/TeXFormula.class")).size() == 1,
                "mathematical runtime classes must have exactly one provider");
        var math = new MarkdownMath();
        var imported = math.vector(SOURCE, builder -> builder.paragraphFormat(ParagraphFormat.builder().fontSize(12).lineHeight(40).build()));
        require(imported.formulas().size() == 4 && imported.degradations().isEmpty(), "vector formula occurrences changed");
        var font = TrueTypeFontLoader.load("../../assets/fonts/LiberationSans-Regular.ttf");
        var display = DocumentEngine.builder(FontGroup.strict(java.util.List.of(font))).build()
                .layout(imported.document()).display();
        require(display.pages().size() == 1, "mathematical consumer must occupy one page");
        try (var stream = Files.newOutputStream(output.resolve("formulas.pdf"))) { new PdfBackend().write(display, stream); }
        try (var stream = Files.newOutputStream(output.resolve("formulas.svg"))) { new SvgBackend().write(display, stream); }
        try (var stream = Files.newOutputStream(output.resolve("formulas.png"))) {
            new RasterBackend(144).writePage(display.pages().getFirst(), stream);
        }
        var nativeDocument = math.docx(SOURCE);
        require(nativeDocument.document().equations().size() == 4 && nativeDocument.degradations().isEmpty(),
                "native formula occurrences changed");
        try (var stream = Files.newOutputStream(output.resolve("formulas.docx"))) {
            new DocxAdapter().writeMath(nativeDocument.document(), stream);
        }
        require(math.scan("`$code$` and [link](https://example.com/$path$), $x$.").size() == 1,
                "code or URL was recognized as mathematics");
        var fallback = MarkdownMath.builder().fallback(MarkdownMath.Fallback.KEEP_SOURCE).build()
                .vector("Literal $\\unsupported{x}$.");
        require(fallback.degradations().size() == 1, "explicit fallback must report its failed occurrence");
        try {
            MarkdownMath.builder().fallback(MarkdownMath.Fallback.KEEP_SOURCE)
                    .options(MathOptions.builder().maxSegments(1).build()).build().vector("$\\frac{1}{2}$");
            throw new IllegalStateException("fallback bypassed a geometry budget");
        } catch (MathException expected) {
            require(expected.reason() == MathException.Reason.LIMIT_EXCEEDED, "unexpected geometry failure category");
        }
        var report = new Properties();
        report.setProperty("vectorFormulas", "4"); report.setProperty("nativeEquations", "4");
        report.setProperty("pages", "1"); report.setProperty("uniqueRuntimeProviders", "true");
        report.setProperty("codeAndLinkProtected", "true"); report.setProperty("fallbackReported", "true");
        report.setProperty("budgetCannotFallback", "true");
        report.setProperty("mathJar", jar.getFileName().toString());
        report.setProperty("mathJarSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(jar))));
        try (var stream = Files.newOutputStream(output.resolve("consumer-report.xml"))) {
            report.storeToXML(stream, "Published optional mathematical API acceptance");
        }
        System.out.println("Published mathematical API: four vector/native formulas, guards and explicit fallback passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message); }
    }
}
