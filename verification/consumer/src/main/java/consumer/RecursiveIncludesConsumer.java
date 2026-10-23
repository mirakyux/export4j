// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.template.XmlTemplate;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.model.PageSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

final class RecursiveIncludesConsumer {
    private RecursiveIncludesConsumer() { }

    static void verify(DocumentEngine engine, Path output) throws IOException {
        var template = XmlTemplate.builder().pageSettings(PageSettings.builder().size(300, 200).margins(16).build())
                .fragment("parent", "<fragment version='1'><include ref='child' prefix='child'/></fragment>")
                .fragment("child", """
                        <fragment version='1'><p id='target'>CALLER-NESTED</p>
                        <p><link href='${url}'>CALLER-LINK</link><page-reference target='${target}' width='16'/></p></fragment>
                        """)
                .build().compile("<document version='1'><include ref='parent' prefix='outer'/></document>");
        var document = template.render(Map.of("url", "https://example.com/caller", "target", "outer-child-target"));
        engine.write(document, new PdfBackend(), output.resolve("consumer-includes.pdf"));
        try (var stream = Files.newOutputStream(output.resolve("consumer-includes.docx"))) { new DocxAdapter().write(document, stream); }
    }
}
