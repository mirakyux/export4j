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
import com.mirakyux.export4j.model.DocumentTheme;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.PageSettings;

/** Exercises explicit offline XML fragments through the published public API. */
final class XmlIncludeConsumer {
    private XmlIncludeConsumer() { }

    static void verify(FontFace font, Path output) throws IOException {
        var fragment = """
                <fragment version="1">
                  <heading id="topic" level="2">INCLUDE-PANEL</heading>
                  <p style="note">INCLUDE-STATUS ${status}</p>
                  <numbered id="step" list-id="steps">INCLUDE-STEP</numbered>
                </fragment>
                """;
        var source = """
                <document version="1">
                  <heading level="1">XML-INCLUDE-CONSUMER</heading>
                  <include ref="panel" prefix="first"/>
                  <include ref="panel" prefix="second"/>
                </document>
                """;
        var theme = DocumentTheme.builder()
                .bodyFormat(value -> value.fontSize(11).lineHeight(20).spaceAfter(3))
                .headingFormat(1, value -> value.fontSize(18).lineHeight(28))
                .headingFormat(2, value -> value.fontSize(14).lineHeight(22))
                .paragraphStyle("note", value -> value.fontSize(9).lineHeight(18).spaceAfter(0))
                .build();
        var document = XmlTemplate.builder().theme(theme).fragment("panel", fragment)
                .pageSettings(PageSettings.builder().size(400, 400).margins(20).build())
                .build().compile(source).render(Map.of("status", "OK"));
        if (document.blocks().size() != 7 || !document.blocks().get(1).id().equals("first-topic")
                || !document.blocks().get(4).id().equals("second-topic")
                || !((NumberedItem) document.blocks().get(3)).listId().equals("first-steps")
                || !((NumberedItem) document.blocks().get(6)).listId().equals("second-steps")) {
            throw new IllegalStateException("offline include structure or local namespaces were lost");
        }
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(font))).build();
        var result = engine.export(document).backend(new PdfBackend()).to(output.resolve("consumer-xml-include.pdf"));
        if (result.display().pages().size() != 1 || !result.diagnostics().isEmpty()) {
            throw new IllegalStateException("published offline XML include layout changed");
        }
        engine.export(document).backend(new SvgBackend()).to(output.resolve("consumer-xml-include.svg"));
        try (var target = Files.newOutputStream(output.resolve("consumer-xml-include.docx"))) {
            new DocxAdapter().write(document, target);
        }
    }
}
