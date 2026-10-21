// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.template.XmlTemplate;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.model.DocumentTheme;
import com.mirakyux.export4j.model.ImageResource;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/** Integrated offline XML sections, repeated image/table bands and native DOCX header/footer parts. */
public final class XmlSectionsExample {
    private static final Path OUTPUT = Path.of("examples/build/xml-sections");
    private XmlSectionsExample() { }
    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var icon = new BufferedImage(24, 24, BufferedImage.TYPE_INT_RGB);
        var graphics = icon.createGraphics();
        try {
            graphics.setColor(new Color(34, 68, 136)); graphics.fillRect(0, 0, 24, 24);
            graphics.setColor(Color.WHITE); graphics.fillRect(6, 4, 4, 16); graphics.fillRect(10, 4, 9, 4); graphics.fillRect(10, 11, 7, 3);
        } finally { graphics.dispose(); }
        var bytes = new ByteArrayOutputStream(); ImageIO.write(icon, "png", bytes); icon.flush();
        Files.write(OUTPUT.resolve("logo.png"), bytes.toByteArray());
        var theme = DocumentTheme.builder().bodyFormat(value -> value.fontSize(10).lineHeight(20).spaceAfter(0))
                .cellFormat(value -> value.fontSize(10).lineHeight(16))
                .tableFormat(value -> value.padding(4).spaceAfter(0))
                .headerStyle(value -> value.fontRole("bold").rgb(0x224488).format(format -> format.fontSize(11).lineHeight(26))).build();
        String xml = """
                <document version="1" title="XML section report">
                  <page width="360" height="260" margin="20"/>
                  <header height="34" gap="6"><p id="brand"><image ref="logo" width="18" height="18" alt="Export4j example mark"/> EXPORT4J / SECTION REPORT</p></header>
                  <footer height="32" gap="6"><table id="footer-table"><columns><column weight="1"/><column weight="1"/></columns>
                    <row><cell color="#336633">PAGE <page-number width="18"/></cell><cell align="RIGHT" color="#336633">TOTAL <page-count width="18"/></cell></row>
                  </table></footer>
                  <section id="report" first-page-number="1"><p id="rows">${rows}</p></section>
                  <section id="appendix" first-page-number="8"><page width="420" height="300" orientation="LANDSCAPE"/>
                    <p id="appendix-body">APPENDIX-01  Landscape section, restarted at page 8.\nAPPENDIX-02  Header image and footer table remain inherited.</p>
                  </section>
                  <section id="closing"><header height="0"/><footer height="0"/><p id="closing-body">CLOSING-01  Both inherited page regions explicitly cleared.</p></section>
                </document>
                """;
        String rows = java.util.stream.IntStream.rangeClosed(1, 9).mapToObj(index -> String.format(java.util.Locale.ROOT,
                "REPORT-%02d  Every source line appears exactly once.", index)).collect(java.util.stream.Collectors.joining("\n"));
        var document = XmlTemplate.builder().theme(theme).image("logo", ImageResource.load(bytes.toByteArray())).build()
                .compile(xml).renderSections(Map.of("rows", rows));
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Regular.ttf"))))
                .fontRole("bold", FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Bold.ttf")))).build();
        var result = engine.export(document).backend(new PdfBackend()).to(OUTPUT.resolve("report.pdf"));
        if (result.display().pages().size() != 4 || !result.contextualDiagnostics().isEmpty()) { throw new IllegalStateException("section output differs"); }
        var adapter = new DocxAdapter();
        try (var output = Files.newOutputStream(OUTPUT.resolve("report.docx"))) { adapter.write(document, output); }
        try (var input = Files.newInputStream(OUTPUT.resolve("report.docx"))) {
            var imported = adapter.read(input, DocxAdapter.Policy.STRICT);
            if (!imported.diagnostics().isEmpty() || !document.sections().stream().map(value -> value.pageRegions()).toList()
                    .equals(imported.document().sections().stream().map(value -> value.pageRegions()).toList())) {
                throw new IllegalStateException("DOCX page regions changed");
            }
            engine.export(imported.document()).backend(new PdfBackend()).to(OUTPUT.resolve("imported.pdf"));
        }
        Files.writeString(OUTPUT.resolve("source.xml"), xml, StandardCharsets.UTF_8);
        System.out.println("XML sections: 4 pages, image/table regions, native DOCX strict round trip");
    }
}
