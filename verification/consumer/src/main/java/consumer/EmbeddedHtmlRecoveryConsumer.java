// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.template.XmlModelRecovery;
import com.mirakyux.export4j.adapter.web.HtmlAdapter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Exercises single-file recovery using published Maven coordinates. */
public final class EmbeddedHtmlRecoveryConsumer {
    private EmbeddedHtmlRecoveryConsumer() { }
    public static void main(String[] args) throws IOException {
        var output = Path.of(System.getProperty("consumer.outputDirectory")); Files.createDirectories(output);
        var opened = XmlModelRecovery.openEmbeddedHtml(Files.readAllBytes(Path.of("../../examples/build/embedded-html-recovery/original.html")));
        var resources = opened.prepare().resources().orElseThrow();
        var saved = XmlModelRecovery.capture(opened.conversion().edit(editor -> editor.replaceText("ORIGINAL", "CALLER"))
                .prepare(new HtmlAdapter().conversionTarget())).resources(resources).prepare();
        saved.writeEmbeddedHtml(output.resolve("consumer-embedded-html.html"));
        var request = XmlModelRecovery.openEmbeddedHtml(saved.embeddedHtmlBytes());
        if (!request.prepare().recovered() || !request.prepare().resources().orElseThrow().licenses().equals(resources.licenses())) { throw new IOException("caller model/resources changed"); }
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(TrueTypeFontLoader.load(resources.fonts().get("Regular.ttf"))))).build();
        request.conversion().prepare(engine.conversionTarget("PDF", new PdfBackend())).to(output.resolve("consumer-embedded-html.pdf"));
        System.out.println("Maven embedded HTML: reopen, edit, fonts/resources and PDF export verified");
    }
}
