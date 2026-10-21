// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.template.XmlTemplate;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** XML multilevel decimal lists, lexical copies and complete native number references. */
public final class XmlNumberingExample {
    private static final Path OUTPUT = Path.of("examples/build/xml-numbering");
    private static final String RECORD = """
            <fragment version="1">
              <numbered id="parent" list-id="checks">INCLUDED-PARENT ${item.code}</numbered>
              <numbered id="child" list-id="checks" level="2" restart="2">INCLUDED-CHILD ${item.code}</numbered>
              <p id="ref">INCLUDED-REF ${item.code} self=<number-reference target="child" width="36"/> global=<number-reference target="global-leaf" width="42"/> <link href="https://example.com/numbering">DETAILS-${item.code}</link></p>
            </fragment>
            """;
    private static final String SOURCE = """
            <document version="1" title="XML multilevel decimal numbering" author="Export4j synthetic example">
              <heading id="report" level="1" size="16" line-height="24" space-before="0" space-after="4">XML-NUMBERING-REPORT</heading>
              <numbered id="global-parent" list-id="global" restart="3">MAIN-PARENT</numbered>
              <numbered id="global-child" list-id="global" level="2" restart="2">MAIN-CHILD</numbered>
              <numbered id="global-leaf" list-id="global" level="3">MAIN-LEAF</numbered>
              <numbered id="global-next-child" list-id="global" level="2">MAIN-NEXT-CHILD</numbered>
              <numbered id="global-next-parent" list-id="global">MAIN-NEXT-PARENT</numbered>
              <numbered id="global-reset-child" list-id="global" level="2">MAIN-RESET-CHILD</numbered>
              <p id="main-ref">MAIN-REF leaf=<number-reference target="global-leaf" width="42"/> child=<number-reference target="global-next-child" width="36"/> parent=<number-reference target="global-parent" width="36"/></p>
              <page-break id="break"/>
              <p id="included-title">INCLUDED-LISTS</p>
              <each items="records" as="item"><include ref="record" prefix="record"/></each>
              <include ref="record" prefix="separate"/>
            </document>
            """;

    private XmlNumberingExample() { }

    /** Writes two source-grounded pages and a native semantic DOCX from explicit offline inputs. */
    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var source = XmlTemplate.builder()
                .pageSettings(PageSettings.builder().size(420, 520).margins(24).build())
                .bodyFormat(ParagraphFormat.builder().fontSize(11).lineHeight(18).spaceBefore(0).spaceAfter(4).build())
                .fragment("record", RECORD).build().compile(SOURCE).render(Map.of(
                        "records", List.of(Map.of("code", "A"), Map.of("code", "B")),
                        "item", Map.of("code", "ROOT")));
        structure(source);
        var font = AuthoringExample.face("LiberationSans-Regular.ttf");
        var result = DocumentEngine.builder(FontGroup.strict(List.of(font))).build()
                .export(source).backend(new PdfBackend()).to(OUTPUT.resolve("report.pdf"));
        if (result.display().pages().size() != 2 || result.bodyPageOffset() != 0
                || result.display().extras().links().size() != 9 || result.display().extras().externalLinks().size() != 3
                || !result.contextualDiagnostics().isEmpty()) {
            throw new IllegalStateException("XML numbering must retain two pages and all final field/text links");
        }
        nativeRoundTrip(source, "report.docx");
        nativeRoundTrip(ninthLevel(), "nine-level.docx");
        Files.writeString(OUTPUT.resolve("numbering.xml"), SOURCE, StandardCharsets.UTF_8);
        Files.writeString(OUTPUT.resolve("record.xml"), RECORD, StandardCharsets.UTF_8);
        System.out.println("XML numbering fixtures: " + OUTPUT.toAbsolutePath());
    }

    private static void structure(Document document) {
        var numbered = document.blocks().stream().filter(NumberedItem.class::isInstance).map(NumberedItem.class::cast).toList();
        if (document.blocks().size() != 19
                || !numbered.stream().map(NumberedItem::level).toList().equals(List.of(1, 2, 3, 2, 1, 2, 1, 2, 1, 2, 1, 2))
                || !numbered.stream().map(NumberedItem::restartAt).toList().equals(List.of(3, 2, 0, 0, 0, 0, 0, 2, 0, 2, 0, 2))) {
            throw new IllegalStateException("XML numbering source levels or restarts were lost");
        }
        targets(document, "main-ref", List.of("global-leaf", "global-next-child", "global-parent"));
        for (int index = 0; index < 2; index++) {
            targets(document, "record-ref@" + index, List.of("record-child@" + index, "global-leaf"));
        }
        targets(document, "separate-ref", List.of("separate-child", "global-leaf"));
    }

    private static void targets(Document document, String id, List<String> expected) {
        var block = document.blocks().stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow();
        if (!(block instanceof Paragraph paragraph)
                || !paragraph.fields().stream().map(value -> value.field().targetId()).toList().equals(expected)) {
            throw new IllegalStateException("Numbering reference escaped its lexical source copy");
        }
    }

    private static Document ninthLevel() {
        var source = new StringBuilder("<document version=\"1\" title=\"XML ninth-level numbering\">\n");
        for (int level = 1; level <= 9; level++) {
            source.append("<numbered id=\"depth-").append(level).append("\" list-id=\"deep\" level=\"").append(level)
                    .append("\" restart=\"").append(level == 9 ? Integer.MAX_VALUE : 1).append("\">DEPTH-")
                    .append(level).append("</numbered>\n");
        }
        source.append("<p id=\"deep-ref\">NINTH-REF <number-reference target=\"depth-9\" width=\"160\"/></p>\n</document>");
        return XmlTemplate.builder()
                .pageSettings(PageSettings.builder().size(420, 520).margins(24).build())
                .bodyFormat(ParagraphFormat.builder().fontSize(10).lineHeight(18).spaceBefore(0).spaceAfter(4).build())
                .build().compile(source.toString()).render(Map.of());
    }

    private static void nativeRoundTrip(Document source, String name) throws IOException {
        var adapter = new DocxAdapter();
        var path = OUTPUT.resolve(name);
        try (var output = Files.newOutputStream(path)) { adapter.write(source, output); }
        try (var input = Files.newInputStream(path)) {
            var result = adapter.read(input, DocxAdapter.Policy.STRICT);
            var imported = result.document();
            if (!result.diagnostics().isEmpty() || imported.sections().size() != 1
                    || !source.metadata().equals(imported.metadata())
                    || !source.pageSettings().equals(imported.sections().getFirst().pageSettings())
                    || !nativeValues(source.blocks()).equals(nativeValues(imported.sections().getFirst().blocks()))) {
                throw new IOException("XML multilevel numbering did not round-trip through native DOCX semantics");
            }
        }
    }

    private static List<Block> nativeValues(List<Block> blocks) {
        // Only the exact omission of one redundant unlinked default run is equivalent.
        // IDs, full formatting, list identity/level/restart, fields and links remain exact.
        return blocks.stream().<Block>map(block -> switch (block) {
            case Heading heading -> new Heading(heading.id(), heading.text(), heading.level(), heading.format(),
                    nativeRuns(heading.inlines(), heading.text(), heading.format().fontSize()));
            case NumberedItem item -> new NumberedItem(item.id(), item.listId(), item.text(), item.restartAt(), item.format(), item.level(),
                    nativeRuns(item.inlines(), item.text(), item.format().fontSize()));
            default -> block;
        }).toList();
    }

    private static List<InlineText> nativeRuns(List<InlineText> runs, String text, double size) {
        return runs.equals(List.of(new InlineText(text, InlineStyle.of(size)))) ? List.of() : runs;
    }
}
