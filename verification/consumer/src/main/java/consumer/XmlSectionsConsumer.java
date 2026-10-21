// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.template.XmlTemplate;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.model.DocumentTheme;
import com.mirakyux.export4j.model.ImageResource;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/** Public Maven APIs for semantic sections and image/table page bands, including DOCX read/write. */
final class XmlSectionsConsumer {
    private XmlSectionsConsumer() { }
    static void verify(FontFace regular, Path output) throws IOException {
        var bitmap = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 4; y++) { for (int x = 0; x < 4; x++) { bitmap.setRGB(x, y, 0x224488); } }
        var bytes = new ByteArrayOutputStream(); ImageIO.write(bitmap, "png", bytes); bitmap.flush();
        var theme = DocumentTheme.builder().bodyFormat(value -> value.fontSize(10).lineHeight(20).spaceAfter(0))
                .cellFormat(value -> value.fontSize(10).lineHeight(16)).tableFormat(value -> value.padding(3).spaceAfter(0)).build();
        var document = XmlTemplate.builder().theme(theme).image("logo", ImageResource.load(bytes.toByteArray())).build().compile("""
                <document version="1"><page width="320" height="220" margin="18"/>
                  <header height="24" gap="6"><p line-height="22"><image ref="logo" width="12" height="12"/> CALLER / SECTION REPORT</p></header>
                  <footer height="26" gap="6"><table><columns><column weight="1"/><column weight="1"/></columns>
                    <row><cell>PAGE <page-number width="18"/></cell><cell align="RIGHT">TOTAL <page-count width="18"/></cell></row></table></footer>
                  <section id="first"><p id="rows">${rows}</p></section>
                  <section id="last" first-page-number="7"><page width="360" height="240"/><header height="0"/><p id="last-row">CALLER-LAST-01</p></section>
                </document>
                """).renderSections(Map.of("rows", java.util.stream.IntStream.rangeClosed(1, 8)
                        .mapToObj(index -> String.format(java.util.Locale.ROOT, "CALLER-ROW-%02d", index)).collect(java.util.stream.Collectors.joining("\n"))));
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(regular))).build();
        var result = engine.export(document).backend(new PdfBackend()).to(output.resolve("consumer-xml-sections.pdf"));
        if (result.display().pages().size() != 3 || !result.contextualDiagnostics().isEmpty()) { throw new IllegalStateException("caller section output differs"); }
        var adapter = new DocxAdapter();
        try (var stream = Files.newOutputStream(output.resolve("consumer-xml-sections.docx"))) { adapter.write(document, stream); }
        try (var stream = Files.newInputStream(output.resolve("consumer-xml-sections.docx"))) {
            var imported = adapter.read(stream, DocxAdapter.Policy.STRICT);
            if (!imported.diagnostics().isEmpty() || !document.sections().stream().map(value -> value.pageRegions()).toList()
                    .equals(imported.document().sections().stream().map(value -> value.pageRegions()).toList())) {
                throw new IllegalStateException("caller DOCX page regions differ");
            }
            engine.export(imported.document()).backend(new PdfBackend()).to(output.resolve("consumer-xml-sections-imported.pdf"));
        }
    }
}
