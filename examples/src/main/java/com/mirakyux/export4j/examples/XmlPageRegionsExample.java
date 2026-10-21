// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.adapter.template.XmlTemplate;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.Navigation;
import com.mirakyux.export4j.components.TableOfContents;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.model.DocumentTheme;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.text.ParagraphStyle;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/** Repeating XML bands, independent theme defaults and page fields after front contents insertion. */
public final class XmlPageRegionsExample {
    private static final Path OUTPUT = Path.of("examples/build/xml-page-regions");
    private XmlPageRegionsExample() { }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var theme = DocumentTheme.builder()
                .bodyFormat(format -> format.fontSize(10).lineHeight(20).spaceAfter(0))
                .headerStyle(style -> style.fontRole("bold").rgb(0x224488)
                        .format(format -> format.fontSize(11).lineHeight(18)))
                .footerStyle(style -> style.rgb(0x336633)
                        .format(format -> format.fontSize(10).lineHeight(18).alignment(ParagraphAlignment.RIGHT))).build();
        String lines = IntStream.rangeClosed(1, 17).mapToObj(index -> String.format(java.util.Locale.ROOT,
                "BODY-%02d  Reserved page regions keep this line intact.", index)).collect(java.util.stream.Collectors.joining("\n"));
        String xml = """
                <document version="1" title="XML page regions">
                  <page width="420" height="360" margin="24"/>
                  <header height="40" gap="8"><p>EXPORT4J / XML PAGE REGIONS</p><p size="9" role="" color="#000000">REPORT-${reportId} <span color="#883366">THEME-OVERRIDE</span></p></header>
                  <footer height="24" gap="8"><p>PAGE <page-number width="18"/> OF <page-count width="18"/></p></footer>
                  <p id="body">${lines}</p>
                </document>
                """;
        var document = XmlTemplate.builder().theme(theme).build().compile(xml)
                .renderWithPageRegions(Map.of("reportId", "Q4", "lines", lines));
        var navigation = Navigation.builder().bookmark("Report body", "body", 1)
                .contents(new TableOfContents(true, "Contents", ParagraphStyle.of(10))).build();
        var result = DocumentEngine.builder(FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Regular.ttf"))))
                .fontRole("bold", FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Bold.ttf"))))
                .navigation(navigation).build().export(document).backend(new PdfBackend()).to(OUTPUT.resolve("report.pdf"));
        if (result.display().pages().size() != 3 || result.bodyPageOffset() != 1 || !result.contextualDiagnostics().isEmpty()) {
            throw new IllegalStateException("XML page bands or final numbering differ");
        }
        // The PDF retains its contents link. This explicit static preview contains only the painted pages.
        try (var output = Files.newOutputStream(OUTPUT.resolve("report.svg"))) {
            new SvgBackend().write(new DisplayDocument(result.display().metadata(), result.display().pages()), output);
        }
        Files.writeString(OUTPUT.resolve("source.xml"), xml, StandardCharsets.UTF_8);
        System.out.println("XML page regions sample: 3 pages including contents");
    }
}
