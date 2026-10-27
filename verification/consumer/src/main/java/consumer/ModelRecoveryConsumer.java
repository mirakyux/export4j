// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.template.XmlModelRecovery;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Uses published coordinates only to recover, edit and recapture a standard PDF with a ZIP companion. */
public final class ModelRecoveryConsumer {
    private ModelRecoveryConsumer() { }
    public static void main(String[] args) throws IOException {
        Path output = Path.of(System.getProperty("consumer.outputDirectory")); Files.createDirectories(output);
        Path source = Path.of("../../examples/build/model-recovery");
        byte[] pdf = Files.readAllBytes(source.resolve("original.pdf"));
        byte[] companion = Files.readAllBytes(source.resolve("original.pdf.recovery.zip"));
        var request = XmlModelRecovery.open("PDF", pdf).companion(companion);
        var original = request.prepare(); var resources = original.resources().orElseThrow();
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(TrueTypeFontLoader.load(resources.fonts().get("Regular.ttf"))))).build();
        var edited = request.conversion().edit(editor -> editor.replaceText("ORIGINAL", "CALLER"))
                .prepare(engine.conversionTarget("PDF", new PdfBackend()));
        if (!edited.exportReport().issues().isEmpty() || !edited.preview().issues().isEmpty()) { throw new IOException("unexpected caller loss"); }
        var saved = XmlModelRecovery.capture(edited).resources(resources).prepare();
        saved.writeDocument(output.resolve("consumer-model-recovery.pdf")); saved.writeCompanion(output.resolve("consumer-model-recovery.pdf.recovery.zip"));
        var reopened = XmlModelRecovery.open("PDF", saved.documentBytes()).companion(saved.companionBytes().orElseThrow());
        var control = XmlModelRecovery.capture(reopened.conversion().prepare(engine.conversionTarget("PDF", new PdfBackend()))).resources(resources).prepare();
        control.writeDocument(output.resolve("consumer-model-recovery-control.pdf"));
        control.writeCompanion(output.resolve("consumer-model-recovery-control.pdf.recovery.zip"));
        if (!reopened.prepare().recovered() || !reopened.prepare().document().equals(edited.candidate())
                || !reopened.prepare().resources().orElseThrow().licenses().equals(resources.licenses())) { throw new IOException("caller recovery changed model/resources"); }
        System.out.println("Maven model recovery: standard PDF, exact model/resources, edit and no-edit recapture verified");
    }
}
