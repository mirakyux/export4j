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
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Table;

/** The published XML adapter must produce actual final links and native nested DOCX. */
final class XmlBlockCellsConsumer {
    private XmlBlockCellsConsumer() { }

    static void verify(DocumentEngine engine, InlineImage image, Path output) throws IOException {
        var source = """
                <document version="1">
                  <each items="items" as="item">
                    <table id="xml-outer">
                      <columns><column weight="1"/><column weight="1"/></columns>
                      <header><cell column-span="2">CON-XML-HEADER ${item}</cell></header>
                      <row><cell column-span="2" row-span="2">
                        <p id="xml-before">CON-XML-BEFORE ${item} REF=<page-reference target="xml-after" width="18"/></p>
                        <table id="xml-inner">
                          <columns><column weight="1"/></columns>
                          <row><cell row-span="2">CON-XML-INNER ${item}</cell></row><row/>
                        </table>
                        <p id="xml-after">CON-XML-AFTER ${item} P=<page-number width="18"/> N=<page-count width="18"/> R=<page-reference target="xml-before" width="18"/><link href="https://example.com/xml-block"><span>LINK</span></link><link href="https://example.com/xml-block-image"><image ref="mark" width="8" height="8" alt="XML block image"/></link></p>
                      </cell></row><row/>
                    </table>
                  </each>
                </document>
                """;
        var document = XmlTemplate.builder().pageSettings(PageSettings.builder().size(320, 180).margins(20).build())
                .cellFormat(CellFormat.builder().fontSize(10).lineHeight(18).build())
                .bodyFormat(ParagraphFormat.builder().fontSize(10).lineHeight(18).spaceAfter(0).build())
                .image("mark", image.resource()).build().compile(source).render(Map.of("items", List.of("A", "B")));
        var result = engine.export(document).backend(new PdfBackend()).to(output.resolve("consumer-xml-block-cells.pdf"));
        if (result.display().pages().size() != 2 || result.display().extras().links().size() != 4
                || result.display().extras().externalLinks().size() != 4 || !result.diagnostics().isEmpty()) {
            throw new IllegalStateException("XML block-cell clone pagination failed in isolated consumer");
        }
        for (int index = 0; index < 2; index++) {
            var table = (Table) document.blocks().get(index);
            var before = (Paragraph) table.rows().get(1).cells().getFirst().blocks().getFirst();
            if (!before.id().equals("xml-before@" + index)
                    || !before.fields().getFirst().field().targetId().equals("xml-after@" + index)) {
                throw new IllegalStateException("XML lexical descendant reference was not relocated");
            }
        }
        var adapter = new DocxAdapter();
        try (var target = Files.newOutputStream(output.resolve("consumer-xml-block-cells.docx"))) { adapter.write(document, target); }
        try (var input = Files.newInputStream(output.resolve("consumer-xml-block-cells.docx"))) {
            var restored = adapter.read(input, DocxAdapter.Policy.STRICT).document();
            for (int index = 0; index < 2; index++) {
                var table = (Table) restored.sections().getFirst().blocks().get(index);
                var blocks = table.rows().get(1).cells().getFirst().blocks();
                if (blocks.size() != 3 || !((Paragraph) blocks.getFirst()).id().equals("xml-before@" + index)
                        || ((Paragraph) blocks.getLast()).fields().size() != 3) {
                    throw new IllegalStateException("XML native nested DOCX failed in isolated consumer");
                }
            }
        }
    }
}
