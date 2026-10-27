// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.template.XmlTemplateBundle;
import com.mirakyux.export4j.adapter.template.XmlTemplateWriter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Maven-only complete XML binding, loss-free editing and note-aware PDF export. */
public final class XmlSemanticSavingConsumer {
    private XmlSemanticSavingConsumer() { }
    public static void main(String[] args) throws IOException {
        Path output = Path.of(System.getProperty("consumer.outputDirectory")); Files.createDirectories(output);
        var original = XmlTemplateBundle.read(Files.readAllBytes(Path.of("../../examples/build/xml-semantic-saving/original.zip")));
        var before = original.compile().renderComplete(Map.of());
        var prepared = original.conversion().edit(editor -> editor.replaceText("figure-1", "CAPTION-ORIGINAL", "CAPTION-CALLER"))
                .prepare(XmlTemplateWriter.conversionTarget());
        var actual = prepared.to(output.resolve("consumer-semantics.zip"));
        if (actual.changes().size() != 1 || !actual.notes().equals(before.notes()) || !actual.report().issues().isEmpty()
                || !actual.report().issues().equals(prepared.preview().issues())) { throw new IOException("complete edit reports changed"); }
        var saved = XmlTemplateBundle.read(Files.readAllBytes(output.resolve("consumer-semantics.zip")));
        var resources = saved.toBuilder(); original.fonts().forEach(resources::font); original.licenses().forEach(resources::license);
        saved = resources.build(); saved.write(output.resolve("consumer-semantics.zip"));
        var restored = saved.compile().renderComplete(Map.of());
        if (!restored.document().equals(actual.document()) || !restored.notes().equals(before.notes())) { throw new IOException("complete model changed"); }
        var font = TrueTypeFontLoader.load(saved.fonts().get("Regular.ttf"));
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(font))).build();
        saved.conversion().prepare(engine.conversionTarget("PDF", new PdfBackend())).to(output.resolve("consumer-semantics.pdf"));
        System.out.println("Maven XML notes/captions/links save/reopen/edit and PDF verified");
    }
}
