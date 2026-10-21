// SPDX-License-Identifier: Apache-2.0
package consumer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.template.XmlTemplate;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.model.DocumentTheme;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.Table;

/** Exercises public XML theme APIs from the locally published Maven coordinates only. */
final class XmlThemeConsumer {
    private XmlThemeConsumer() { }

    static void verify(FontFace font, Path output) throws IOException {
        var theme = DocumentTheme.builder()
                .bodyFormat(value -> value.fontSize(11).lineHeight(20).spaceAfter(4))
                .headingFormat(1, value -> value.fontSize(18).lineHeight(28))
                .cellFormat(value -> value.fontSize(10).lineHeight(18))
                .tableFormat(value -> value.padding(5).spaceAfter(7))
                .paragraphStyle("report-note", value -> value.fontSize(9).lineHeight(18).spaceAfter(0))
                .inlineStyle("emphasis", value -> value.fontRole("bold").rgb(0x224488).underline(true))
                .build();
        var source = """
                <document version="1">
                  <heading level="1">XML-THEME-CONSUMER</heading>
                  <p>XML-THEME-BODY</p>
                  <p style="report-note">XML-THEME-NOTE ${status}</p>
                  <p><span style="emphasis">XML-THEME-KEY</span> XML-THEME-VALUE</p>
                  <table>
                    <columns><column weight="1"/><column weight="1"/></columns>
                    <row><cell>XML-THEME-CELL</cell><cell><p style="report-note">XML-THEME-BLOCK</p></cell></row>
                  </table>
                </document>
                """;
        var document = XmlTemplate.builder().theme(theme)
                .pageSettings(PageSettings.builder().size(400, 400).margins(20).build())
                .build().compile(source).render(Map.of("status", "OK"));
        var note = (Paragraph) document.blocks().get(2);
        var table = (Table) document.blocks().getLast();
        if (!note.format().equals(theme.paragraphStyle("report-note"))
                || !note.text().equals("XML-THEME-NOTE OK")
                || !table.format().equals(theme.tableFormat())
                || !table.rows().getFirst().cells().getFirst().format().equals(theme.cellFormat())) {
            throw new IllegalStateException("published XML theme defaults or static names were lost");
        }
        var bold = TrueTypeFontLoader.load(Files.readAllBytes(Path.of("../../assets/fonts/LiberationSans-Bold.ttf")));
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(font)))
                .fontRole("bold", FontGroup.strict(List.of(bold))).build();
        var result = engine.export(document).backend(new PdfBackend()).to(output.resolve("consumer-xml-theme.pdf"));
        if (result.display().pages().size() != 1 || !result.diagnostics().isEmpty()) {
            throw new IllegalStateException("published XML theme layout changed unexpectedly");
        }
        engine.export(document).backend(new SvgBackend()).to(output.resolve("consumer-xml-theme.svg"));
        try (var target = Files.newOutputStream(output.resolve("consumer-xml-theme.docx"))) {
            new DocxAdapter().write(document, target);
        }
    }
}
