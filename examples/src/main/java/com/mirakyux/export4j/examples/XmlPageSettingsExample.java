// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.template.XmlTemplate;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.IntStream;

/** Self-authored XML paper presets, absolute units, partial overrides and native page settings. */
public final class XmlPageSettingsExample {
    private static final Path OUTPUT = Path.of("examples/build/xml-page-settings");
    private static final String ORIGINAL_PAGE = "<page size=\"A4\" margin=\"15mm\" margin-top=\"20mm\" margin-bottom=\"18mm\" margin-left=\"22mm\"/>";

    private XmlPageSettingsExample() { }

    /** Generates every page from explicit local resources; no system font lookup or remote I/O. */
    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        String source;
        try (var stream = Objects.requireNonNull(XmlPageSettingsExample.class.getResourceAsStream("/templates/page-settings.xml"),
                "XML page settings resource"); var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            var text = new StringBuilder();
            var buffer = new char[2048];
            int count;
            while ((count = reader.read(buffer)) != -1) { text.append(buffer, 0, count); }
            source = text.toString();
        }
        var regular = FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Regular.ttf")));
        var bold = FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Bold.ttf")));
        var engine = DocumentEngine.builder(regular).fontRole("bold", bold).build();
        // Deliberately different Java defaults make XML precedence observable in each exported format.
        var compiler = XmlTemplate.builder()
                .pageSettings(PageSettings.builder().size(320, 400).margins(12).build())
                .bodyFormat(ParagraphFormat.builder().fontSize(10).lineHeight(16).build())
                .cellFormat(new CellFormat(10, 16, ParagraphAlignment.LEFT)).build();
        output(compiler, engine, source, "a4", 36, 2);
        output(compiler, engine, source.replace(ORIGINAL_PAGE,
                "<page size=\"LETTER\" orientation=\"LANDSCAPE\" margin=\"14mm\" margin-top=\"18mm\" margin-left=\"20mm\"/>"),
                "letter-landscape", 8, 1);
        output(compiler, engine, source.replace(ORIGINAL_PAGE,
                "<page width=\"14.8cm\" height=\"21cm\" margin=\"12mm\" margin-bottom=\"14mm\"/>"),
                "custom", 22, 2);
    }

    private static void output(XmlTemplate compiler, DocumentEngine engine, String source, String name, int rows, int pages)
            throws IOException {
        var records = IntStream.rangeClosed(1, rows).mapToObj(index -> Map.of(
                "code", "ROW-%03d".formatted(index), "description", "Synthetic observation %03d".formatted(index))).toList();
        var document = compiler.compile(source).render(Map.of("title", "XML-PAGE-" + name.toUpperCase(java.util.Locale.ROOT),
                "records", records));
        var result = engine.export(document).backend(new PdfBackend()).to(OUTPUT.resolve(name + ".pdf"));
        if (result.display().pages().size() != pages || !result.contextualDiagnostics().isEmpty()) {
            throw new IllegalStateException("XML paper fixture has unexpected pagination or diagnostics: " + name);
        }
        try (var svg = Files.newOutputStream(OUTPUT.resolve(name + ".svg"))) { new SvgBackend().write(result.display(), svg); }
        var adapter = new DocxAdapter();
        var path = OUTPUT.resolve(name + ".docx");
        try (var output = Files.newOutputStream(path)) { adapter.write(document, output); }
        try (var input = Files.newInputStream(path)) {
            var imported = adapter.read(input, DocxAdapter.Policy.STRICT);
            if (!imported.diagnostics().isEmpty() || imported.document().sections().size() != 1
                    || !twips(document.pageSettings()).equals(imported.document().sections().getFirst().pageSettings())) {
                throw new IOException("Native DOCX page settings did not round-trip: " + name);
            }
        }
        Files.writeString(OUTPUT.resolve(name + ".xml"), source, StandardCharsets.UTF_8);
        System.out.println("XML paper fixture: " + name + " (" + pages + " pages)");
    }

    private static PageSettings twips(PageSettings source) {
        // Standard OOXML stores integral twips; the import must preserve exactly that quantized geometry.
        var margins = source.margins();
        return new PageSettings(new PageSize(twips(source.size().width()), twips(source.size().height())),
                new PageMargins(twips(margins.top()), twips(margins.right()), twips(margins.bottom()), twips(margins.left())));
    }

    private static double twips(double points) { return Math.round(points * 20) / 20.0; }
}
