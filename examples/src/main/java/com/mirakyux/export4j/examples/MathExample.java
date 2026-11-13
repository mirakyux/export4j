// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.raster.RasterBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.math.MarkdownMath;
import com.mirakyux.export4j.math.MathOptions;
import com.mirakyux.export4j.math.TexMath;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Public-library formula workflow; no example-private renderer/parser is used. */
public final class MathExample {
    private static final Path OUTPUT = Path.of("examples/build/math");
    private static final String SOURCE = """
            # Export4j optional mathematics

            Inline equation: $x_1^2 + x_2^2 = r^2$ stays with surrounding text.

            $$
            x = \\frac{-b \\pm \\sqrt{b^2-4ac}}{2a}
            $$

            | Kind | Equation |
            | --- | --- |
            | Fraction | $\\frac{a+b}{c}$ |
            | Root | $\\sqrt[3]{x}$ |

            Repeated occurrences: $x^2$ and $x^2$ have separate anchors.

            显式中文正文与数学公式 $\\alpha + \\beta = \\gamma$ 混排。
            """;

    private MathExample() { }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var options = MathOptions.builder().fontSize(14).build();
        var math = MarkdownMath.builder().options(options).build();
        var format = new ParagraphFormat(12, 64, 0, 0, ParagraphAlignment.LEFT, 0, 6, false);
        var cells = CellFormat.builder().fontSize(12).lineHeight(64).build();
        var page = new PageSettings(new PageSize(600, 900), PageMargins.uniform(36));
        var vectors = math.vector(SOURCE, importer -> importer.paragraphFormat(format).cellFormat(cells).pageSettings(page));
        var nativeMath = math.docx(SOURCE, importer -> importer.paragraphFormat(format).cellFormat(cells).pageSettings(page));
        if (!vectors.degradations().isEmpty() || !nativeMath.degradations().isEmpty()
                || !vectors.importReport().issues().isEmpty() || !nativeMath.importReport().issues().isEmpty()
                || vectors.formulas().size() != 7 || nativeMath.document().equations().size() != 7) {
            throw new IOException("unexpected public formula import result");
        }
        var regular = FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Regular.ttf"),
                AuthoringExample.face("LXGWWenKaiTC-Regular.ttf")));
        var engine = DocumentEngine.builder(regular)
                .fontRole("bold", FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Bold.ttf"))))
                .build();
        var display = engine.layout(vectors.document()).display();
        try (var output = Files.newOutputStream(OUTPUT.resolve("formulas.pdf"))) { new PdfBackend().write(display, output); }
        try (var output = Files.newOutputStream(OUTPUT.resolve("formulas.svg"))) { new SvgBackend().write(display, output); }
        if (display.pages().size() != 1) { throw new IOException("formula specimen must have one page"); }
        try (var output = Files.newOutputStream(OUTPUT.resolve("formulas.png"))) {
            new RasterBackend(144).writePage(display.pages().getFirst(), output);
        }
        var adapter = new DocxAdapter();
        try (var output = Files.newOutputStream(OUTPUT.resolve("formulas.docx"))) { adapter.writeMath(nativeMath.document(), output); }
        try (var input = Files.newInputStream(OUTPUT.resolve("formulas.docx"))) {
            var roundtrip = adapter.readMath(input, DocxAdapter.Policy.STRICT);
            if (!roundtrip.diagnostics().isEmpty() || roundtrip.document().equations().size() != 7) {
                throw new IOException("native formula roundtrip changed");
            }
        }
        var rejected = MarkdownMath.builder().fallback(MarkdownMath.Fallback.KEEP_SOURCE).build()
                .docx("Explicit unsupported formula: $\\sum_{i=1}^n i$.");
        if (rejected.degradations().size() != 1 || !rejected.document().equations().isEmpty()) {
            throw new IOException("native unsupported subset must retain its reported source");
        }
        // A directly constructed formula uses the same public API, without Markdown.
        if (TexMath.vector("x^2", options).resource().segmentCount() == 0) { throw new IOException("empty vector"); }
        Files.writeString(OUTPUT.resolve("source.md"), SOURCE);
        Files.writeString(OUTPUT.resolve("result.json"), """
                {"pages":1,"formulas":7,"nativeEquations":7,"degradations":0,"explicitFallbacks":1}
                """);
        System.out.println("Generated public math PDF/SVG/PNG and editable Word equations in " + OUTPUT);
    }
}
