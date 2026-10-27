// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.adapter.pdf.PdfRecoveryCodec;
import com.mirakyux.export4j.adapter.template.XmlModelRecovery;
import com.mirakyux.export4j.adapter.template.XmlTemplateBundle;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.processing.DocumentConversion;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Opt-in PDF attachment recovery with exact round trips and actual model edits. */
public final class EmbeddedPdfRecoveryExample {
    private static final Path OUTPUT = Path.of("examples/build/embedded-pdf-recovery");
    private static final PdfRecoveryCodec CODEC = new PdfRecoveryCodec();
    private EmbeddedPdfRecoveryExample() { }
    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        if (args.length > 0 && args[0].equals("--external")) { external(); return; }
        var resources = XmlTemplateBundle.builder("<document version='1'><p>UNUSED-SOURCE</p></document>")
                .font("Regular.ttf", Files.readAllBytes(Path.of("assets/fonts/LiberationSans-Regular.ttf")))
                .license("Font.txt", Files.readString(Path.of("assets/fonts/LiberationSans-OFL.txt"))).build();
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(TrueTypeFontLoader.load(resources.fonts().get("Regular.ttf"))))).build();
        var target = engine.conversionTarget("PDF", new PdfBackend());
        var source = new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("s", PageSettings.A4,
                List.of(paragraph("first", "ORIGINAL EMBEDDED-PDF"), paragraph("second", "UNCHANGED-CONTROL")), 0)));
        var saved = XmlModelRecovery.capture(DocumentConversion.from(source, List.of()).prepare(target)).resources(resources).prepare();
        saved.writeDocument(OUTPUT.resolve("plain.pdf")); saved.writeEmbedded(CODEC, OUTPUT.resolve("original.pdf"));
        for (int round = 0; round < 3; round++) {
            var request = XmlModelRecovery.openEmbedded("PDF", saved.embeddedBytes(CODEC), CODEC); var opened = request.prepare();
            require(opened.recovered() && opened.document().equals(source) && opened.resources().orElseThrow().licenses().equals(resources.licenses()), "PDF model/resources changed");
            saved = XmlModelRecovery.capture(request.conversion().prepare(target)).resources(opened.resources().orElseThrow()).prepare();
        }
        saved.writeEmbedded(CODEC, OUTPUT.resolve("cycle-3.pdf"));
        var edited = XmlModelRecovery.capture(XmlModelRecovery.openEmbedded("PDF", saved.embeddedBytes(CODEC), CODEC)
                .conversion().edit(editor -> editor.replaceText("ORIGINAL", "EDITED")).prepare(target)).resources(resources).prepare();
        edited.writeEmbedded(CODEC, OUTPUT.resolve("edited.pdf"));
        require(XmlModelRecovery.openEmbedded("PDF", edited.embeddedBytes(CODEC), CODEC).prepare().recovered(), "edited PDF did not recover");
        System.out.println("Embedded PDF: attachment, three model/resource rounds and edit verified");
    }
    private static void external() throws IOException {
        var rows = new StringBuilder("file,action,reason,recovered\n");
        for (String name : List.of("external", "stripped")) {
            byte[] bytes = Files.readAllBytes(OUTPUT.resolve(name + ".pdf"));
            String reason = name.equals("external") ? "STALE_OR_CONFLICTING_COMPANION" : "MISSING_COMPANION";
            for (var action : ProcessingPolicy.Action.values()) {
                var request = XmlModelRecovery.openEmbedded("PDF", bytes, CODEC).policy(ProcessingPolicy.builder()
                        .feature(XmlModelRecovery.FEATURE, action, ProcessingPolicy.Treatment.DEGRADE).build());
                try { request.prepare(); throw new IOException("external PDF restored old model"); }
                catch (ProcessingException expected) { require(action == ProcessingPolicy.Action.ERROR && expected.issue().reason().equals(reason), "unexpected PDF recovery rejection"); }
                catch (IllegalArgumentException expected) { require(action != ProcessingPolicy.Action.ERROR && expected.getMessage().contains("fallback"), "unexpected PDF fallback rejection"); }
                rows.append(name).append(',').append(action).append(',').append(reason).append(",false\n");
            }
        }
        Files.writeString(OUTPUT.resolve("external.csv"), rows);
        System.out.println("External PDF: changed current file and removed recovery reject old models under all actions");
    }
    private static Paragraph paragraph(String id, String text) { return Paragraph.mixed(id, List.of(new InlineText(text, InlineStyle.of(12))), ParagraphFormat.of(12)); }
    private static void require(boolean value, String message) throws IOException { if (!value) { throw new IOException(message); } }
}
