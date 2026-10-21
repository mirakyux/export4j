// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.template.MarkdownTemplate;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.ParagraphFormat;

/** Strict Markdown, literal data binding and explicit resources; all data are fictional. */
public final class MarkdownTemplateExample {
    private MarkdownTemplateExample() { }

    public static void main(String[] args) throws IOException {
        var output = Path.of("examples/build/markdown-templates");
        Files.createDirectories(output);
        var compiler = MarkdownTemplate.builder()
                .pageSettings(PageSettings.builder().size(420, 480).margins(28).build())
                .bodyFormat(ParagraphFormat.builder().fontSize(12).lineHeight(26).build())
                .image("mark", AuthoringExample.image().resource(), 16, 16).build();
        var regular = FontGroup.strict(List.of(AuthoringExample.face("LXGWWenKaiTC-Regular.ttf"),
                AuthoringExample.face("LiberationSans-Regular.ttf")));
        var engine = DocumentEngine.builder(regular)
                .fontRole("bold", FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Bold.ttf"))))
                .fontRole("italic", FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Italic.ttf"))))
                .fontRole("boldItalic", FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-BoldItalic.ttf"))))
                .build();
        try (var input = Objects.requireNonNull(MarkdownTemplateExample.class.getResourceAsStream("/templates/summary.md"));
                var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            var document = compiler.compile(reader).render(Map.of("report", Map.of("title", "青禾巡检摘要",
                    "note", "**数据标记不再解析**。")));
            var result = engine.export(document).backend(new PdfBackend()).to(output.resolve("summary.pdf"));
            if (result.display().pages().size() != 1) { throw new IOException("Markdown example unexpectedly paginated"); }
            try (var target = Files.newOutputStream(output.resolve("summary.docx"))) { new DocxAdapter().write(document, target); }
        }
        System.out.println("Strict Markdown PDF and DOCX: " + output.toAbsolutePath());
    }
}
