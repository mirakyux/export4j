// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.template.XmlTemplate;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/** Exercises XML absolute paper units through isolated Maven artifacts. */
final class XmlPageSettingsConsumer {
    private XmlPageSettingsConsumer() { }

    static void verify(FontFace font, Path output) throws IOException {
        var document = XmlTemplate.builder().pageSettings(PageSettings.A4)
                .bodyFormat(ParagraphFormat.builder().fontSize(10).lineHeight(16).build())
                .cellFormat(new CellFormat(10, 16, ParagraphAlignment.LEFT)).build().compile("""
                        <document version="1" title="XML page settings consumer">
                          <page width="5in" height="4in" margin=".25in" margin-top=".5in" margin-left=".375in"/>
                          <heading level="1" size="16" line-height="24" space-after="8">XML-PAGE-CONSUMER</heading>
                          <p space-after="8">Registered font. Static paper geometry.</p>
                          <rows items="records" as="record">
                            <columns><column width="72"/><column weight="1"/></columns>
                            <header><cell>CODE</cell><cell>DESCRIPTION</cell></header>
                            <row><cell>${record.code}</cell><cell>${record.description}</cell></row>
                          </rows>
                          <p space-before="8">PAGE-SETTINGS-END</p>
                        </document>
                        """).render(Map.of("records", IntStream.rangeClosed(1, 4).mapToObj(index -> Map.of(
                        "code", "ROW-%03d".formatted(index), "description", "Synthetic observation %03d".formatted(index))).toList()));
        var expected = PageSettings.builder().size(360, 288).margins(36, 18, 18, 27).build();
        if (!expected.equals(document.pageSettings())) { throw new IllegalStateException("Published XML absolute units differ"); }
        var result = DocumentEngine.builder(FontGroup.strict(List.of(font))).build().export(document)
                .backend(new PdfBackend()).to(output.resolve("consumer-xml-page-settings.pdf"));
        if (result.display().pages().size() != 1 || !result.contextualDiagnostics().isEmpty()) {
            throw new IllegalStateException("Published XML paper pagination or diagnostics differ");
        }
        try (var svg = Files.newOutputStream(output.resolve("consumer-xml-page-settings.svg"))) {
            new SvgBackend().write(result.display(), svg);
        }
        var path = output.resolve("consumer-xml-page-settings.docx");
        var adapter = new DocxAdapter();
        try (var target = Files.newOutputStream(path)) { adapter.write(document, target); }
        try (var input = Files.newInputStream(path)) {
            var imported = adapter.read(input, DocxAdapter.Policy.STRICT);
            if (!imported.diagnostics().isEmpty() || !expected.equals(imported.document().sections().getFirst().pageSettings())) {
                throw new IllegalStateException("Published XML paper native DOCX differs");
            }
        }
    }
}
