// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfRecoveryCodec;
import com.mirakyux.export4j.adapter.template.XmlModelRecovery;
import com.mirakyux.export4j.adapter.template.XmlTemplateWriter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.raster.RasterBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.model.processing.DocumentConversion;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Published-coordinate section recovery, shared-source editing and explicit DOCX overlay loss. */
public final class SectionRecoveryConsumer {
    private SectionRecoveryConsumer() { }
    public static void main(String[] args) throws IOException {
        Path output = Path.of(System.getProperty("consumer.outputDirectory")); Files.createDirectories(output);
        var original = XmlModelRecovery.openEmbedded("PDF",
                Files.readAllBytes(Path.of("../../examples/build/section-recovery/pdf/embedded-original.pdf")), new PdfRecoveryCodec());
        var resources = original.prepare().resources().orElseThrow();
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(TrueTypeFontLoader.load(resources.fonts().get("Regular.ttf")))))
                .fontRole("bold", FontGroup.strict(List.of(TrueTypeFontLoader.load(resources.fonts().get("Bold.ttf"))))).build();
        var edited = original.conversion().edit(editor -> editor.replaceText("shared-header", "ORIGINAL", "CALLER")
                .replaceText("INNER-ORIGINAL", "INNER-CALLER"))
                .prepare(engine.conversionTarget("PNG_ZIP", new RasterBackend(72)));
        var saved = XmlModelRecovery.capture(edited).resources(resources).prepare();
        Path pages = output.resolve("consumer-section.png.zip"); saved.writeEmbedded(pages);
        var reopened = XmlModelRecovery.openEmbedded("PNG_ZIP", Files.readAllBytes(pages));
        var current = reopened.prepare();
        if (!current.document().equals(edited.candidate()) || current.document().sections().size() != 2
                || current.resources().orElseThrow().images().size() != 2 || !current.report().losses().isEmpty()
                || current.resources().orElseThrow().source().contains("ORIGINAL")) {
            throw new IOException("published section recovery lost model, resources or shared edit");
        }
        reopened.conversion().prepare(engine.conversionTarget("PDF", new PdfBackend())).to(output.resolve("consumer-section.pdf"));
        var policy = ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DEGRADE)
                .feature("PAGE_OVERLAYS", ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DISCARD).build();
        var converted = reopened.conversion().policy(policy).prepare(new DocxAdapter().conversionTarget());
        var docx = XmlModelRecovery.capture(converted).policy(policy).resources(resources)
                .reimportOnLoss((bytes, shared) -> {
                    var result = new DocxAdapter().conversion(bytes).policy(shared).prepare(XmlTemplateWriter.conversionTarget())
                            .to(OutputStream.nullOutputStream());
                    return new DocumentConversion.Input(result.document(), result.notes(), result.report());
                }).prepare();
        Path file = output.resolve("consumer-section.docx"); docx.writeEmbedded(file);
        var finalModel = XmlModelRecovery.openEmbedded("DOCX", Files.readAllBytes(file));
        var finalState = finalModel.prepare();
        if (finalState.document().sections().size() != 2 || !finalState.resources().orElseThrow().images().isEmpty()
                || finalState.document().sections().stream().anyMatch(section -> !section.pageRegions().background().isEmpty() || !section.pageRegions().foreground().isEmpty())
                || finalState.report().losses().isEmpty() || !finalState.report().warnings().isEmpty()) {
            throw new IOException("published DOCX overlay discard restored hidden resources or lost reports");
        }
        var history = XmlModelRecovery.capture(finalModel.conversion().prepare(XmlTemplateWriter.conversionTarget()))
                .resources(resources).prepare();
        if (!history.preview().issues().equals(finalState.report().issues())) { throw new IOException("published loss history changed"); }
        System.out.println("Maven section recovery: shared edits, nested tables, four pages and actual DOCX overlay loss verified");
    }
}
