// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.template.XmlTemplate;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.model.DocumentTheme;
import com.mirakyux.export4j.model.InlineStyle;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Readable XML appearance defaults, explicit resets and independently styled list markers. */
public final class XmlTextDefaultsExample {
    private static final Path OUTPUT = Path.of("examples/build/xml-text-defaults");
    private static final String SOURCE = """
            <document version="1" title="XML text defaults">
              <page width="420" height="420" margin="24"/>
              <heading level="1" space-before="0" space-after="0">XML-TEXT-DEFAULTS</heading>
              <p>BODY-DEFAULT <span color="#000000">BLACK</span> <span role="" color="#000000">REGULAR</span> <span style="accent">NAMED</span> <link href="https://example.com/defaults">LINK</link></p>
              <numbered id="parent" list-id="checks" restart="2">NUMBER-BODY <span role="" color="#000000">FIRST-RUN-RESET</span></numbered>
              <p>FIELD-PAGE <page-number width="24"/> COUNT <page-count width="24"/> NUMBER <number-reference target="parent" width="24"/> TARGET <page-reference target="target" width="24"/></p>
              <page-break/>
              <p id="target" role="" color="#000000">PAGE-TWO-REGULAR</p>
              <table id="cells"><columns><column width="320"/></columns>
                <row><cell>CELL-DEFAULT</cell></row>
                <row><cell role="italic" color="#883366"><p>BLOCK-INHERITED</p><p role="" color="#000000">BLOCK-RESET</p><table id="inner"><columns><column width="220"/></columns><row><cell>INNER-CELL-DEFAULT</cell></row></table></cell></row>
              </table>
            </document>
            """;
    private XmlTextDefaultsExample() { }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var theme = DocumentTheme.builder()
                .bodyFormat(format -> format.fontSize(10).lineHeight(18).spaceBefore(0).spaceAfter(0))
                .headingFormat(1, format -> format.fontSize(16).lineHeight(24).spaceBefore(0).spaceAfter(0))
                .cellFormat(format -> format.fontSize(10).lineHeight(18))
                .inlineStyle("accent", InlineStyle.builder().fontRole("bold").fontSize(10).rgb(0x884422).build()).build();
        var source = XmlTemplate.builder().theme(theme)
                .bodyText(text -> text.fontRole("italic").rgb(0x883366))
                .headingText(1, text -> text.fontRole("bold").rgb(0x224488))
                .cellText(text -> text.fontRole("bold").rgb(0x336633))
                .build().compile(SOURCE).render(Map.of());
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Regular.ttf"))))
                .fontRole("bold", FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Bold.ttf"))))
                .fontRole("italic", FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Italic.ttf")))).build();
        var result = engine.export(source).backend(new PdfBackend()).to(OUTPUT.resolve("report.pdf"));
        if (result.display().pages().size() != 2 || !result.contextualDiagnostics().isEmpty()
                || result.display().extras().externalLinks().size() != 1 || result.display().extras().links().size() != 2) {
            throw new IllegalStateException("XML defaults changed source links or pagination");
        }
        // This separate static preview deliberately excludes the links retained and checked in the PDF.
        var preview = new DisplayDocument(result.display().metadata(), result.display().pages());
        try (var output = Files.newOutputStream(OUTPUT.resolve("report.svg"))) { new SvgBackend().write(preview, output); }
        try (var output = Files.newOutputStream(OUTPUT.resolve("report.docx"))) { new DocxAdapter().write(source, output); }
        Files.writeString(OUTPUT.resolve("source.xml"), SOURCE, StandardCharsets.UTF_8);
        System.out.println("XML text defaults sample: 2 pages");
    }
}
