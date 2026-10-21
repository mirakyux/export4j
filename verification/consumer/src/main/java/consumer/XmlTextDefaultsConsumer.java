// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.template.XmlTemplate;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.model.ParagraphFormat;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Caller-local defaults require no template scripts or implicit font loading. */
final class XmlTextDefaultsConsumer {
    private XmlTextDefaultsConsumer() { }
    static void verify(FontFace regular, Path output) throws IOException {
        var bold = TrueTypeFontLoader.load(Files.readAllBytes(Path.of("../../assets/fonts/LiberationSans-Bold.ttf")));
        var source = XmlTemplate.builder().bodyText(text -> text.fontRole("bold").rgb(0x224488))
                .bodyFormat(ParagraphFormat.builder().fontSize(10).lineHeight(18).spaceBefore(0).spaceAfter(0).build())
                .build().compile("""
                        <document version="1"><page width="320" height="220" margin="18"/>
                          <p>CALLER-DEFAULT <span role="" color="#000000">RESET</span></p>
                          <numbered list-id="checks">CALLER-NUMBER</numbered>
                        </document>
                        """).render(Map.of());
        var result = DocumentEngine.builder(FontGroup.strict(List.of(regular)))
                .fontRole("bold", FontGroup.strict(List.of(bold))).build()
                .export(source).backend(new PdfBackend()).to(output.resolve("consumer-xml-text-defaults.pdf"));
        if (result.display().pages().size() != 1 || !result.contextualDiagnostics().isEmpty()) {
            throw new IllegalStateException("Public XML defaults differ");
        }
    }
}
