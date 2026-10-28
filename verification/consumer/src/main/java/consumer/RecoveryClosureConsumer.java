// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfRecoveryCodec;
import com.mirakyux.export4j.adapter.template.XmlModelRecovery;
import com.mirakyux.export4j.adapter.template.XmlTemplateWriter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.model.DocumentNavigation;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.Note;
import com.mirakyux.export4j.model.PageDocument;
import com.mirakyux.export4j.model.edit.DocumentEditor;
import com.mirakyux.export4j.model.processing.DocumentConversion;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Uses only published coordinates: recovered navigation drives output without runtime reconstruction. */
public final class RecoveryClosureConsumer {
    private RecoveryClosureConsumer() { }
    public static void main(String[] args) throws IOException {
        var output = Path.of(System.getProperty("consumer.outputDirectory")); Files.createDirectories(output);
        var request = XmlModelRecovery.openEmbedded("PDF", Files.readAllBytes(Path.of(
                "../../examples/build/recovery-closure/pdf/embedded-original.pdf")), new PdfRecoveryCodec());
        var original = request.prepare(); var resources = original.resources().orElseThrow();
        var firstSection = original.document().sections().getFirst();
        var narrow = new Document(original.document().metadata(), firstSection.pageSettings(), List.of(firstSection.blocks().getFirst()));
        try {
            DocumentEditor.edit(narrow).navigation(original.document().navigation()).execute();
            throw new IOException("published narrow document edit silently discarded navigation");
        } catch (IllegalArgumentException expected) {
            if (!expected.getMessage().contains("SectionedDocument")) { throw expected; }
        }
        try {
            DocumentEditor.edit(new PageDocument(narrow, firstSection.pageRegions())).navigation(original.document().navigation()).execute();
            throw new IOException("published page document edit silently discarded navigation");
        } catch (IllegalArgumentException expected) {
            if (!expected.getMessage().contains("SectionedDocument")) { throw expected; }
        }
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(TrueTypeFontLoader.load(resources.fonts().get("Regular.ttf")))))
                .fontRole("bold", FontGroup.strict(List.of(TrueTypeFontLoader.load(resources.fonts().get("Bold.ttf"))))).build();
        var notes = original.notes().stream().map(n -> new Note(n.id(), n.paragraphId(), n.text().replace("ORIGINAL", "CALLER"), n.kind())).toList();
        var converted = request.conversion().edit(edit -> edit.notes(notes).replaceText("ORIGINAL", "CALLER"))
                .prepare(engine.conversionTarget("PDF", new PdfBackend()));
        var saved = XmlModelRecovery.capture(converted).resources(resources).prepare();
        var file = output.resolve("consumer-closure.pdf"); saved.writeEmbedded(new PdfRecoveryCodec(), file);
        var recovered = XmlModelRecovery.openEmbedded("PDF", Files.readAllBytes(file), new PdfRecoveryCodec()).prepare();
        if (!recovered.document().equals(converted.candidate()) || !recovered.notes().equals(notes)
                || !recovered.document().navigation().equals(original.document().navigation())
                || recovered.resources().orElseThrow().source().contains("ORIGINAL") || !recovered.report().issues().isEmpty()) {
            throw new IOException("published combined model/navigation/notes edit did not recover exactly");
        }
        var layout = engine.layout(recovered.document(), recovered.document().sections().stream()
                .map(section -> com.mirakyux.export4j.components.PageRegionTemplates.sectionFrom(section.pageRegions())).toList(),
                com.mirakyux.export4j.components.Navigation.EMPTY, notes);
        if (!layout.display().extras().bookmarks().getFirst().title().contains("CALLER") || layout.bodyPageOffset() != 1) {
            throw new IOException("recovered declaration did not automatically resolve the current title");
        }
        var policy = ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DEGRADE)
                .feature("NAVIGATION", ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DISCARD)
                .feature("NOTES", ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DISCARD)
                .feature("PAGE_OVERLAYS", ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DISCARD).build();
        var docx = XmlModelRecovery.capture(DocumentConversion.from(recovered.document(), recovered.notes()).policy(policy)
                .prepare(new DocxAdapter().conversionTarget())).resources(resources).policy(policy).reimportOnLoss((bytes, shared) -> {
                    var actual = new DocxAdapter().conversion(bytes).policy(shared).prepare(XmlTemplateWriter.conversionTarget()).to(OutputStream.nullOutputStream());
                    return new DocumentConversion.Input(actual.document(), actual.notes(), actual.report());
                }).prepare();
        var target = output.resolve("consumer-closure.docx"); docx.writeEmbedded(target);
        var lossy = XmlModelRecovery.openEmbedded("DOCX", Files.readAllBytes(target)); var current = lossy.prepare();
        if (!current.document().navigation().equals(DocumentNavigation.NONE) || !current.notes().isEmpty()
                || current.report().losses().isEmpty() || !current.report().warnings().isEmpty()
                || current.resources().orElseThrow().source().contains("FOOT-CALLER")) {
            throw new IOException("lossy published target revived discarded declarations/notes or lost its report");
        }
        lossy.conversion().prepare(engine.conversionTarget("PDF", new PdfBackend())).to(output.resolve("consumer-closure-lossy.pdf"));
        System.out.println("Maven combined recovery: automatic persisted navigation, actual edits and loss-aware DOCX recovery verified");
    }
}
