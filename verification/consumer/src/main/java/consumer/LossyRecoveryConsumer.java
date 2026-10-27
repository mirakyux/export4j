// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.template.XmlModelRecovery;
import com.mirakyux.export4j.adapter.web.MarkdownAdapter;
import com.mirakyux.export4j.adapter.web.MarkdownImporter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.model.processing.DocumentConversion;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Published-coordinate caller: complex DOCX recovery to lossy Markdown, current model to DOCX/PDF. */
public final class LossyRecoveryConsumer {
    private LossyRecoveryConsumer() { }
    public static void main(String[] args) throws IOException {
        Path output = Path.of(System.getProperty("consumer.outputDirectory")); Files.createDirectories(output);
        Path source = Path.of("../../examples/build/complex-recovery/docx/original.docx");
        var opened = XmlModelRecovery.open("DOCX", Files.readAllBytes(source))
                .companion(Files.readAllBytes(source.resolveSibling("original.docx.recovery.zip")));
        var resources = opened.prepare().resources().orElseThrow();
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(TrueTypeFontLoader.load(resources.fonts().get("Regular.ttf"))))).build();
        for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
            var policy = ProcessingPolicy.builder().defaultRule(action, ProcessingPolicy.Treatment.DEGRADE)
                    .feature("METADATA", action, ProcessingPolicy.Treatment.DISCARD)
                    .feature("PAGE_NUMBERING", action, ProcessingPolicy.Treatment.DISCARD).build();
            var prepared = opened.conversion().policy(policy).prepare(new MarkdownAdapter().conversionTarget());
            var saved = XmlModelRecovery.capture(prepared).resources(resources).policy(policy)
                    .reimportOnLoss((bytes, shared) -> {
                        var current = MarkdownImporter.builder().policy(shared).build().read(new String(bytes, StandardCharsets.UTF_8)).execute();
                        return DocumentConversion.Input.of(current.document(), current.report());
                    }).prepare();
            Path markdown = output.resolve("consumer-lossy-" + action + ".md"); saved.writeEmbedded(markdown);
            var request = XmlModelRecovery.openEmbedded("MARKDOWN", Files.readAllBytes(markdown));
            var current = request.prepare();
            if (current.resources().orElseThrow().images().size() != 0 || current.report().issues().stream()
                    .noneMatch(issue -> issue.feature().equals(XmlModelRecovery.FEATURE) && issue.effects().contains("CURRENT_FILE_REIMPORT"))
                    || current.report().losses().isEmpty() || action == ProcessingPolicy.Action.IGNORE && !current.report().warnings().isEmpty()) {
                throw new IOException("published lossy recovery model/report differs");
            }
            var edited = request.conversion().edit(editor -> editor.replaceText("ORIGINAL", "CALLER"))
                    .prepare(new DocxAdapter().conversionTarget());
            var docx = XmlModelRecovery.capture(edited).resources(current.resources().orElseThrow()).prepare();
            Path docxFile = output.resolve("consumer-lossy-" + action + ".docx"); docx.writeEmbedded(docxFile);
            var finalModel = XmlModelRecovery.openEmbedded("DOCX", Files.readAllBytes(docxFile)).prepare();
            if (finalModel.resources().orElseThrow().source().contains("ORIGINAL")
                    || !finalModel.resources().orElseThrow().source().contains("CALLER")
                    || !finalModel.report().issues().equals(current.report().issues())) {
                throw new IOException("published edit changed loss history or restored removed content");
            }
            DocumentConversion.from(finalModel.document(), finalModel.notes()).prepare(engine.conversionTarget("PDF", new PdfBackend()))
                    .to(output.resolve("consumer-lossy-" + action + ".pdf"));
        }
        System.out.println("Maven lossy recovery: actual Markdown reimport, explicit actions, edits and DOCX/PDF verified");
    }
}
