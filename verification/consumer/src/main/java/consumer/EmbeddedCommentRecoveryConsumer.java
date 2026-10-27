// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.template.XmlModelRecovery;
import com.mirakyux.export4j.adapter.web.MarkdownAdapter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Exercises Markdown/SVG single-file recovery from published Maven coordinates. */
public final class EmbeddedCommentRecoveryConsumer {
    private EmbeddedCommentRecoveryConsumer() { }
    public static void main(String[] args) throws IOException {
        Path output = Path.of(System.getProperty("consumer.outputDirectory")); Files.createDirectories(output);
        for (String format : List.of("MARKDOWN", "SVG")) {
            String extension = format.equals("MARKDOWN") ? "md" : "svg";
            var request = XmlModelRecovery.openEmbedded(format, Files.readAllBytes(Path.of("../../examples/build/embedded-comment-recovery/" + extension + "/original." + extension)));
            var resources = request.prepare().resources().orElseThrow();
            var engine = DocumentEngine.builder(FontGroup.strict(List.of(TrueTypeFontLoader.load(resources.fonts().get("Regular.ttf"))))).build();
            var target = format.equals("MARKDOWN") ? new MarkdownAdapter().conversionTarget() : engine.conversionTarget("SVG", new SvgBackend());
            var saved = XmlModelRecovery.capture(request.conversion().edit(editor -> editor.replaceText("ORIGINAL", "CALLER")).prepare(target)).resources(resources).prepare();
            saved.writeEmbedded(output.resolve("consumer-embedded-comment." + extension));
            var reopened = XmlModelRecovery.openEmbedded(format, saved.embeddedBytes());
            if (!reopened.prepare().recovered() || !reopened.prepare().resources().orElseThrow().licenses().equals(resources.licenses())) { throw new IOException("caller model/resources changed"); }
            reopened.conversion().prepare(engine.conversionTarget("PDF", new PdfBackend())).to(output.resolve("consumer-embedded-comment-" + extension + ".pdf"));
        }
        System.out.println("Maven Markdown/SVG: single-file reopen, edit, resources and PDF export verified");
    }
}
