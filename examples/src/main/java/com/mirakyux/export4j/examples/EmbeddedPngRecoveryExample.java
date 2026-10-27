// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.adapter.template.XmlModelRecovery;
import com.mirakyux.export4j.adapter.template.XmlTemplateBundle;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.raster.RasterBackend;
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
import java.util.Arrays;
import java.util.List;

/** One ordinary PNG with explicit model/resources; arbitrary pixels are never inferred as text. */
public final class EmbeddedPngRecoveryExample {
    private static final Path OUTPUT = Path.of("examples/build/embedded-png-recovery");
    private EmbeddedPngRecoveryExample() { }
    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        if (args.length > 0 && args[0].equals("--external")) { external(); return; }
        var resources = XmlTemplateBundle.builder("<document version='1'><p>UNUSED-SOURCE</p></document>")
                .font("Regular.ttf", Files.readAllBytes(Path.of("assets/fonts/LiberationSans-Regular.ttf")))
                .license("Font.txt", Files.readString(Path.of("assets/fonts/LiberationSans-OFL.txt"))).build();
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(TrueTypeFontLoader.load(resources.fonts().get("Regular.ttf"))))).build();
        var target = engine.conversionTarget("PNG", new RasterBackend(96).singlePage());
        var source = new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("s", PageSettings.A4,
                List.of(paragraph("first", "ORIGINAL EMBEDDED-PNG"), paragraph("second", "UNCHANGED-CONTROL")), 0)));
        var saved = XmlModelRecovery.capture(DocumentConversion.from(source, List.of()).prepare(target)).resources(resources).prepare();
        saved.writeDocument(OUTPUT.resolve("plain.png")); saved.writeEmbedded(OUTPUT.resolve("original.png"));
        byte[] first = saved.embeddedBytes();
        for (int round = 0; round < 3; round++) {
            var request = XmlModelRecovery.openEmbedded("PNG", saved.embeddedBytes()); var opened = request.prepare();
            require(opened.recovered() && opened.document().equals(source) && opened.resources().orElseThrow().licenses().equals(resources.licenses()), "single PNG model/resources changed");
            saved = XmlModelRecovery.capture(request.conversion().prepare(target)).resources(opened.resources().orElseThrow()).prepare();
        }
        require(Arrays.equals(first, saved.embeddedBytes()), "three rounds changed PNG bytes");
        saved.writeEmbedded(OUTPUT.resolve("cycle-3.png"));
        var request = XmlModelRecovery.openEmbedded("PNG", saved.embeddedBytes());
        request.conversion().prepare(engine.conversionTarget("PDF", new PdfBackend())).to(OUTPUT.resolve("control.pdf"));
        var edited = XmlModelRecovery.capture(request.conversion().edit(editor -> editor.replaceText("ORIGINAL", "EDITED")).prepare(target)).resources(resources).prepare();
        edited.writeEmbedded(OUTPUT.resolve("edited.png"));
        XmlModelRecovery.openEmbedded("PNG", edited.embeddedBytes()).conversion().prepare(engine.conversionTarget("PDF", new PdfBackend())).to(OUTPUT.resolve("edited.pdf"));
        System.out.println("Embedded PNG: ordinary pixels, three exact rounds, explicit resources and real edits verified");
    }
    private static void external() throws IOException {
        var rows = new StringBuilder("file,action,reason,recovered\n");
        for (String name : List.of("external.png", "stripped.png")) {
            byte[] bytes = Files.readAllBytes(OUTPUT.resolve(name));
            String reason = name.equals("external.png") ? "STALE_OR_CONFLICTING_COMPANION" : "MISSING_COMPANION";
            for (var action : ProcessingPolicy.Action.values()) {
                var request = XmlModelRecovery.openEmbedded("PNG", bytes).policy(ProcessingPolicy.builder()
                        .feature(XmlModelRecovery.FEATURE, action, ProcessingPolicy.Treatment.DEGRADE).build());
                try { request.prepare(); throw new IOException("external pixels restored old model"); }
                catch (ProcessingException expected) { require(action == ProcessingPolicy.Action.ERROR && expected.issue().reason().equals(reason), "unexpected recovery rejection"); }
                catch (IllegalArgumentException expected) { require(action != ProcessingPolicy.Action.ERROR && expected.getMessage().contains("fallback"), "unexpected missing-reader rejection"); }
                rows.append(name).append(',').append(action).append(',').append(reason).append(",false\n");
            }
        }
        Files.writeString(OUTPUT.resolve("external.csv"), rows);
        System.out.println("External PNG: changed pixels and removed recovery reject old models under all three actions");
    }
    private static Paragraph paragraph(String id, String text) { return Paragraph.mixed(id, List.of(new InlineText(text, InlineStyle.of(12))), ParagraphFormat.of(12)); }
    private static void require(boolean value, String message) throws IOException { if (!value) { throw new IOException(message); } }
}
