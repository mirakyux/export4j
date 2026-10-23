// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.template.XmlTemplate;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

final class PageOverlaysConsumer {
    private PageOverlaysConsumer() { }
    static void verify(DocumentEngine engine, Path output) throws IOException {
        var source = XmlTemplate.builder().build().compile("""
                <document version='1'><page width='240' height='180' margin='20'/>
                  <background><box x='20' y='80' width='180' height='20'><p>CALLER-BACKGROUND</p></box></background>
                  <foreground><box x='20' y='140' width='180' height='20'><p>CALLER-FINAL-<page-count width='20'/></p></box></foreground>
                  <p>CALLER-BODY</p>
                </document>
                """).renderWithPageRegions(Map.of());
        engine.export(source).backend(new PdfBackend()).to(output.resolve("consumer-page-overlays.pdf"));
    }
}
