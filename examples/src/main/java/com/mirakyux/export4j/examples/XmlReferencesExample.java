// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.template.XmlTemplate;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.Navigation;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.DocumentTemplate;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;

/** Offline XML fields, numbered targets, passive links and strict DOCX boundaries. */
public final class XmlReferencesExample {
    private static final Path OUTPUT = Path.of("examples/build/xml-references");
    private XmlReferencesExample() { }

    /** Writes only self-authored fixtures within the project build directory. */
    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var compiler = XmlTemplate.builder()
                .pageSettings(PageSettings.builder().size(420, 320).margins(24).build())
                .bodyFormat(ParagraphFormat.builder().fontSize(11).lineHeight(24).build())
                .cellFormat(new CellFormat(11, 22, ParagraphAlignment.LEFT))
                .image("mark", AuthoringExample.image().resource()).build();
        var fonts = FontGroup.strict(List.of(AuthoringExample.face("LXGWWenKaiTC-Regular.ttf"),
                AuthoringExample.face("LiberationSans-Regular.ttf")));
        var data = Map.<String, Object>of("title", "青禾站（虚构）", "records", IntStream.rangeClosed(1, 2)
                .mapToObj(row -> Map.of("code", "REC-" + row, "notes", IntStream.rangeClosed(1, 14)
                        .mapToObj(line -> String.format(Locale.ROOT, "XML%02d-%02d 记录已核查。", row, line))
                        .collect(Collectors.joining("\n")))).toList());
        var document = compile(compiler, "references.xml").render(data);
        var result = DocumentEngine.builder(fonts).navigation(Navigation.fromHeadings(document)).build()
                .export(document).backend(new PdfBackend()).to(OUTPUT.resolve("references.pdf"));
        if (!result.diagnostics().isEmpty()) { throw new IOException("unexpected XML reference diagnostics"); }
        var rejected = new ByteArrayOutputStream();
        try {
            new DocxAdapter().write(document, rejected);
            throw new IllegalStateException("repeated header PAGE must be rejected by DOCX export");
        } catch (IOException expected) {
            if (!"DOCX repeated table headers cannot preserve per-occurrence PAGE fields".equals(expected.getMessage())) {
                throw expected;
            }
            if (rejected.size() != 0) { throw new IOException("DOCX rejection wrote a partial package", expected); }
            Files.writeString(OUTPUT.resolve("docx-rejected.txt"), "REPEATED_HEADER_PAGE_REJECTED\n", StandardCharsets.UTF_8);
        }
        var wordDocument = compile(compiler, "references-word.xml").render(data);
        var engine = DocumentEngine.builder(fonts).build();
        engine.export(wordDocument).backend(new PdfBackend()).to(OUTPUT.resolve("word-fields.pdf"));
        try (var output = Files.newOutputStream(OUTPUT.resolve("word-fields.docx"))) {
            new DocxAdapter().write(wordDocument, output);
        }
        System.out.println("XML references: " + result.display().pages().size() + " pages with final contents offset");
    }

    private static DocumentTemplate compile(XmlTemplate compiler, String name) throws IOException {
        try (var input = Objects.requireNonNull(XmlReferencesExample.class.getResourceAsStream("/templates/" + name));
                var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            return compiler.compile(reader);
        }
    }
}
