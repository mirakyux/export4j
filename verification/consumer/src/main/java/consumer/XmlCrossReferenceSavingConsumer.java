// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.template.XmlTemplateBundle;
import com.mirakyux.export4j.adapter.template.XmlTemplateWriter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.model.CrossReference;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Maven artifacts preserve and edit independent references without a project dependency. */
public final class XmlCrossReferenceSavingConsumer {
    private XmlCrossReferenceSavingConsumer() { }
    public static void main(String[] args) throws IOException {
        Path output = Path.of(System.getProperty("consumer.outputDirectory")); Files.createDirectories(output);
        var original = XmlTemplateBundle.read(Files.readAllBytes(Path.of("../../examples/build/xml-cross-reference-saving/original.zip")));
        var before = original.compile().renderComplete(Map.of());
        var reference = (CrossReference) before.document().sections().getFirst().blocks().get(2);
        var replacement = new CrossReference(reference.id(), reference.prefix().replace("ORIGINAL", "CALLER"),
                "figure-a", reference.kind(), reference.format());
        var prepared = original.conversion().edit(editor -> editor.replaceBlock(reference.id(), replacement)).prepare(XmlTemplateWriter.conversionTarget());
        var actual = prepared.to(output.resolve("consumer-cross-reference.zip"));
        if (actual.changes().size() != 1 || !actual.report().issues().isEmpty()
                || !actual.report().issues().equals(prepared.preview().issues())) { throw new IOException("reference caller edit failed"); }
        var request = XmlTemplateWriter.from(actual.document()); original.fonts().forEach(request::font); original.licenses().forEach(request::license);
        var saved = request.prepare().bundle(); saved.write(output.resolve("consumer-cross-reference.zip"));
        if (!saved.compile().renderComplete(Map.of()).document().equals(actual.document())) { throw new IOException("reference caller model changed"); }
        var font = TrueTypeFontLoader.load(saved.fonts().get("Regular.ttf"));
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(font))).build();
        saved.conversion().prepare(engine.conversionTarget("PDF", new PdfBackend())).to(output.resolve("consumer-cross-reference.pdf"));
        System.out.println("Maven independent XML references: save/reopen/retarget and PDF verified");
    }
}
