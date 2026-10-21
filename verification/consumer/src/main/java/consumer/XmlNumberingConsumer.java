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
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.ParagraphFormat;

/** Exercises multilevel XML numbering through the isolated published API. */
final class XmlNumberingConsumer {
    private XmlNumberingConsumer() { }

    static void verify(FontFace font, Path output) throws IOException {
        var fragment = """
                <fragment version="1">
                  <numbered id="parent" list-id="checks">CONSUMER-LOCAL-PARENT ${item.code}</numbered>
                  <numbered id="child" list-id="checks" level="2">CONSUMER-LOCAL-CHILD ${item.code}</numbered>
                  <p id="ref">CONSUMER-LOCAL-REF ${item.code} self=<number-reference target="child" width="36"/> global=<number-reference target="global-child" width="36"/> <link href="https://example.com/consumer-numbering">DETAILS-${item.code}</link></p>
                </fragment>
                """;
        var source = """
                <document version="1">
                  <heading id="report" level="1" size="16" line-height="24" space-after="4">XML-NUMBERING-CONSUMER</heading>
                  <numbered id="global-parent" list-id="global" restart="5">CONSUMER-PARENT</numbered>
                  <numbered id="global-child" list-id="global" level="2" restart="3">CONSUMER-CHILD</numbered>
                  <p id="main-ref">CONSUMER-MAIN-REF number=<number-reference target="global-child" width="36"/></p>
                  <page-break id="break"/>
                  <p id="included-title">CONSUMER-INCLUDED</p>
                  <each items="records" as="item"><include ref="record" prefix="record"/></each>
                  <include ref="record" prefix="separate"/>
                </document>
                """;
        var document = XmlTemplate.builder().fragment("record", fragment)
                .pageSettings(PageSettings.builder().size(440, 460).margins(20).build())
                .bodyFormat(ParagraphFormat.builder().fontSize(10).lineHeight(18).spaceAfter(4).build())
                .build().compile(source).render(Map.of("records", List.of(Map.of("code", "C1"), Map.of("code", "C2")),
                        "item", Map.of("code", "ROOT")));
        var items = document.blocks().stream().filter(NumberedItem.class::isInstance).map(NumberedItem.class::cast).toList();
        if (items.size() != 8 || !items.stream().map(NumberedItem::level).toList().equals(List.of(1, 2, 1, 2, 1, 2, 1, 2))
                || !items.stream().map(NumberedItem::listId).toList().equals(List.of("global", "global", "record-checks", "record-checks",
                        "record-checks", "record-checks", "separate-checks", "separate-checks"))) {
            throw new IllegalStateException("published multilevel numbering lost levels or list identities");
        }
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(font))).build();
        var result = engine.export(document).backend(new PdfBackend()).to(output.resolve("consumer-xml-numbering.pdf"));
        if (result.display().pages().size() != 2 || result.display().extras().links().size() != 7
                || result.display().extras().externalLinks().size() != 3 || !result.contextualDiagnostics().isEmpty()) {
            throw new IllegalStateException("published numbering pages, final references or external links differ");
        }
        var adapter = new DocxAdapter();
        var path = output.resolve("consumer-xml-numbering.docx");
        try (var target = Files.newOutputStream(path)) { adapter.write(document, target); }
        try (var input = Files.newInputStream(path)) {
            var imported = adapter.read(input, DocxAdapter.Policy.STRICT);
            if (!imported.diagnostics().isEmpty() || imported.document().sections().size() != 1
                    || !document.metadata().equals(imported.document().metadata())
                    || !document.pageSettings().equals(imported.document().sections().getFirst().pageSettings())
                    || !nativeValues(document.blocks()).equals(nativeValues(imported.document().sections().getFirst().blocks()))) {
                throw new IllegalStateException("published multilevel DOCX model did not round-trip strictly");
            }
        }
    }

    private static List<Block> nativeValues(List<Block> blocks) {
        return blocks.stream().<Block>map(block -> switch (block) {
            case Heading heading -> new Heading(heading.id(), heading.text(), heading.level(), heading.format(),
                    nativeRuns(heading.inlines(), heading.text(), heading.format().fontSize()));
            case NumberedItem item -> new NumberedItem(item.id(), item.listId(), item.text(), item.restartAt(), item.format(), item.level(),
                    nativeRuns(item.inlines(), item.text(), item.format().fontSize()));
            default -> block;
        }).toList();
    }

    private static List<InlineText> nativeRuns(List<InlineText> runs, String text, double size) {
        // Only one redundant unlinked default run is equivalent to the native short form.
        return runs.equals(List.of(new InlineText(text, InlineStyle.of(size)))) ? List.of() : runs;
    }
}
