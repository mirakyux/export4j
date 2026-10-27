// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfRecoveryCodec;
import com.mirakyux.export4j.adapter.template.XmlModelRecovery;
import com.mirakyux.export4j.adapter.template.XmlTemplateWriter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.Navigation;
import com.mirakyux.export4j.components.TableOfContents;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.model.CrossReference;
import com.mirakyux.export4j.model.Note;
import com.mirakyux.export4j.model.processing.DocumentConversion;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import com.mirakyux.export4j.text.ParagraphStyle;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Public-coordinate note editing and explicit contents reconstruction from current recovered headings. */
public final class NavigationRecoveryConsumer {
    private NavigationRecoveryConsumer() { }
    public static void main(String[] args) throws IOException {
        var output = Path.of(System.getProperty("consumer.outputDirectory")); Files.createDirectories(output);
        var request = XmlModelRecovery.openEmbedded("PDF", Files.readAllBytes(Path.of(
                "../../examples/build/navigation-recovery/pdf/embedded-original.pdf")), new PdfRecoveryCodec());
        var original = request.prepare(); var assets = original.resources().orElseThrow();
        var fonts = FontGroup.strict(List.of(TrueTypeFontLoader.load(assets.fonts().get("Regular.ttf"))));
        var notes = original.notes().stream().map(note -> new Note(note.id(), note.paragraphId(),
                note.text().replace("ORIGINAL", "CALLER"), note.kind())).toList();
        var reference = original.document().sections().stream().flatMap(section -> section.blocks().stream())
                .filter(CrossReference.class::isInstance).map(CrossReference.class::cast)
                .filter(block -> block.id().equals("cross-page")).findFirst().orElseThrow();
        var editing = request.conversion().edit(editor -> editor.notes(notes)
                .replaceText("heading-a", "ORIGINAL", "CALLER").replaceText("body", "ORIGINAL", "CALLER")
                .replaceText("figure", "ORIGINAL", "CALLER").replaceBlock("cross-page",
                        new CrossReference("cross-page", "CROSS-PAGE=", "heading-a", CrossReference.Kind.PAGE,
                                reference.format())));
        var prepared = editing.prepare(pdf(fonts));
        var saved = XmlModelRecovery.capture(prepared).resources(assets).prepare();
        var file = output.resolve("consumer-navigation.pdf"); saved.writeEmbedded(new PdfRecoveryCodec(), file);
        var recovered = XmlModelRecovery.openEmbedded("PDF", Files.readAllBytes(file), new PdfRecoveryCodec());
        var current = recovered.prepare();
        if (!current.document().equals(prepared.candidate()) || !current.notes().equals(notes)
                || current.resources().orElseThrow().source().contains("ORIGINAL") || !current.report().losses().isEmpty()) {
            throw new IOException("published note/reference edit changed model, notes or reports");
        }
        recovered.conversion().prepare(DocumentEngine.builder(fonts).build().conversionTarget("PDF", new PdfBackend()))
                .to(output.resolve("consumer-navigation-plain.pdf"));
        var policy = ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DEGRADE)
                .feature("NOTES", ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DISCARD).build();
        var docx = XmlModelRecovery.capture(recovered.conversion().policy(policy).prepare(new DocxAdapter().conversionTarget()))
                .resources(assets).policy(policy).reimportOnLoss((bytes, shared) -> {
                    var result = new DocxAdapter().conversion(bytes).policy(shared).prepare(XmlTemplateWriter.conversionTarget())
                            .to(OutputStream.nullOutputStream());
                    return new DocumentConversion.Input(result.document(), result.notes(), result.report());
                }).prepare();
        var docxFile = output.resolve("consumer-navigation.docx"); docx.writeEmbedded(docxFile);
        var finalModel = XmlModelRecovery.openEmbedded("DOCX", Files.readAllBytes(docxFile));
        var finalState = finalModel.prepare();
        if (!finalState.notes().isEmpty() || finalState.report().losses().isEmpty() || !finalState.report().warnings().isEmpty()
                || finalState.resources().orElseThrow().source().contains("FOOT-CALLER")) {
            throw new IOException("discarded note text returned or loss report disappeared");
        }
        finalModel.conversion().prepare(pdf(fonts)).to(output.resolve("consumer-navigation-lossy.pdf"));
        var history = XmlModelRecovery.capture(finalModel.conversion().prepare(XmlTemplateWriter.conversionTarget())).resources(assets).prepare();
        if (!history.preview().issues().equals(finalState.report().issues())) { throw new IOException("loss history changed in strict XML control"); }
        System.out.println("Maven navigation recovery: note edits, current-heading contents, retargeting and actual DOCX degradation verified");
    }
    private static DocumentConversion.Target pdf(FontGroup fonts) {
        return new DocumentConversion.Target("PDF", (document, notes, policy, output) -> {
            var navigation = new Navigation(Navigation.fromHeadings(document).bookmarks(), TableOfContents.builder()
                    .title("RECOVERY-CONTENTS").style(ParagraphStyle.of(10)).build(), List.of(), List.of());
            return DocumentEngine.builder(fonts).navigation(navigation).build().conversionTarget("PDF", new PdfBackend())
                    .writer().write(document, notes, policy, output);
        });
    }
}
