// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
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

/** Optional single-file recovery in standard DOCX and PNG page ZIP containers. */
public final class EmbeddedZipRecoveryExample {
    private static final Path OUTPUT = Path.of("examples/build/embedded-zip-recovery");
    private EmbeddedZipRecoveryExample() { }
    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        if (args.length > 0 && args[0].equals("--external")) { external(); return; }
        var resources = XmlTemplateBundle.builder("<document version='1'><p>UNUSED-SOURCE</p></document>")
                .font("Regular.ttf", Files.readAllBytes(Path.of("assets/fonts/LiberationSans-Regular.ttf")))
                .license("Font.txt", Files.readString(Path.of("assets/fonts/LiberationSans-OFL.txt"))).build();
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(TrueTypeFontLoader.load(resources.fonts().get("Regular.ttf"))))).build();
        var source = new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("s", PageSettings.A4,
                List.of(paragraph("first", "ORIGINAL EMBEDDED-ZIP"), paragraph("second", "UNCHANGED-CONTROL")), 0)));
        for (var target : List.of(new DocxAdapter().conversionTarget(), engine.conversionTarget("PNG_ZIP", new RasterBackend(96)))) {
            String extension = target.format().equals("DOCX") ? "docx" : "png.zip";
            var directory = OUTPUT.resolve(target.format().toLowerCase()); Files.createDirectories(directory);
            var saved = XmlModelRecovery.capture(DocumentConversion.from(source, List.of()).prepare(target)).resources(resources).prepare();
            saved.writeDocument(directory.resolve("plain." + extension)); saved.writeEmbedded(directory.resolve("original." + extension));
            byte[] first = saved.embeddedBytes();
            for (int round = 0; round < 3; round++) {
                var request = XmlModelRecovery.openEmbedded(target.format(), saved.embeddedBytes()); var opened = request.prepare();
                require(opened.recovered() && opened.document().equals(source) && opened.resources().orElseThrow().licenses().equals(resources.licenses()), "ZIP model/resources changed");
                saved = XmlModelRecovery.capture(request.conversion().prepare(target)).resources(opened.resources().orElseThrow()).prepare();
            }
            require(Arrays.equals(first, saved.embeddedBytes()), "three rounds changed embedded ZIP bytes");
            saved.writeEmbedded(directory.resolve("cycle-3." + extension));
            var request = XmlModelRecovery.openEmbedded(target.format(), saved.embeddedBytes());
            request.conversion().prepare(engine.conversionTarget("PDF", new PdfBackend())).to(directory.resolve("control.pdf"));
            var edited = XmlModelRecovery.capture(request.conversion().edit(editor -> editor.replaceText("ORIGINAL", "EDITED"))
                    .prepare(target)).resources(resources).prepare();
            edited.writeEmbedded(directory.resolve("edited." + extension));
            XmlModelRecovery.openEmbedded(target.format(), edited.embeddedBytes()).conversion()
                    .prepare(engine.conversionTarget("PDF", new PdfBackend())).to(directory.resolve("edited.pdf"));
        }
        System.out.println("Embedded ZIP: DOCX and PNG page ZIP, exact rounds, resources and edits verified");
    }
    private static void external() throws IOException {
        var rows = new StringBuilder("format,file,action,reason,recovered\n");
        for (String format : List.of("DOCX", "PNG_ZIP")) {
            String extension = format.equals("DOCX") ? "docx" : "png.zip";
            var directory = OUTPUT.resolve(format.toLowerCase());
            for (String name : List.of("external", "stripped")) {
                byte[] bytes = Files.readAllBytes(directory.resolve(name + "." + extension));
                String reason = name.equals("external") ? "STALE_OR_CONFLICTING_COMPANION" : "MISSING_COMPANION";
                for (var action : ProcessingPolicy.Action.values()) {
                    var request = XmlModelRecovery.openEmbedded(format, bytes).policy(ProcessingPolicy.builder()
                            .feature(XmlModelRecovery.FEATURE, action, ProcessingPolicy.Treatment.DEGRADE).build());
                    try { request.prepare(); throw new IOException("external ZIP restored old model"); }
                    catch (ProcessingException expected) { require(action == ProcessingPolicy.Action.ERROR && expected.issue().reason().equals(reason), "unexpected ZIP recovery rejection"); }
                    catch (IllegalArgumentException expected) { require(action != ProcessingPolicy.Action.ERROR && expected.getMessage().contains("fallback"), "unexpected ZIP fallback rejection"); }
                    rows.append(format).append(',').append(name).append(',').append(action).append(',').append(reason).append(",false\n");
                }
            }
        }
        Files.writeString(OUTPUT.resolve("external.csv"), rows);
        System.out.println("External ZIP: changed members and removed recovery reject old models under all actions");
    }
    private static Paragraph paragraph(String id, String text) { return Paragraph.mixed(id, List.of(new InlineText(text, InlineStyle.of(12))), ParagraphFormat.of(12)); }
    private static void require(boolean value, String message) throws IOException { if (!value) { throw new IOException(message); } }
}
