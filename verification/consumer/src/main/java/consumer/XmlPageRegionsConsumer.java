// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.template.XmlTemplate;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.model.DocumentTheme;
import com.mirakyux.export4j.model.ParagraphAlignment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Exercises the published template/model/component bridge with explicit caller-owned fonts. */
final class XmlPageRegionsConsumer {
    private XmlPageRegionsConsumer() { }
    static void verify(FontFace regular, Path output) throws IOException {
        var bold = TrueTypeFontLoader.load(Files.readAllBytes(Path.of("../../assets/fonts/LiberationSans-Bold.ttf")));
        var theme = DocumentTheme.builder().bodyFormat(format -> format.fontSize(10).lineHeight(20).spaceAfter(0))
                .headerStyle(style -> style.fontRole("bold").rgb(0x224488))
                .footerStyle(style -> style.rgb(0x336633).format(format -> format.alignment(ParagraphAlignment.RIGHT))).build();
        var document = XmlTemplate.builder().theme(theme).build().compile("""
                <document version="1"><page width="320" height="220" margin="18"/>
                  <header height="24" gap="6"><p>CALLER-HEADER-${name}</p></header>
                  <footer height="24" gap="6"><p>PAGE <page-number width="18"/> OF <page-count width="18"/></p></footer>
                  <p>CALLER-BODY-01\nCALLER-BODY-02\nCALLER-BODY-03\nCALLER-BODY-04\nCALLER-BODY-05\nCALLER-BODY-06\nCALLER-BODY-07\nCALLER-BODY-08</p>
                </document>
                """).renderWithPageRegions(Map.of("name", "PUBLIC-API"));
        var result = DocumentEngine.builder(FontGroup.strict(List.of(regular)))
                .fontRole("bold", FontGroup.strict(List.of(bold))).build().export(document)
                .backend(new PdfBackend()).to(output.resolve("consumer-xml-page-regions.pdf"));
        if (result.display().pages().size() != 2 || !result.contextualDiagnostics().isEmpty()) {
            throw new IllegalStateException("Public XML page regions differ");
        }
    }
}
