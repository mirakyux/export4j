// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.adapter.template.XmlModelRecovery;
import com.mirakyux.export4j.adapter.template.XmlTemplateBundle;
import com.mirakyux.export4j.adapter.web.MarkdownAdapter;
import com.mirakyux.export4j.adapter.web.MarkdownImporter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** Standard Markdown and SVG stay readable while carrying explicit bounded model recovery. */
public final class EmbeddedCommentRecoveryExample {
    private static final Path OUTPUT = Path.of("examples/build/embedded-comment-recovery");
    private EmbeddedCommentRecoveryExample() { }

    public static void main(String[] args) throws IOException {
        var resources = XmlTemplateBundle.builder("<document version='1'><p>UNUSED-SOURCE</p></document>")
                .font("Regular.ttf", Files.readAllBytes(Path.of("assets/fonts/LiberationSans-Regular.ttf")))
                .license("Font.txt", Files.readString(Path.of("assets/fonts/LiberationSans-OFL.txt"))).build();
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(TrueTypeFontLoader.load(resources.fonts().get("Regular.ttf"))))).build();
        var source = new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("s", PageSettings.A4,
                List.of(paragraph("first", "ORIGINAL EMBEDDED-COMMENT"), paragraph("second", "UNCHANGED-CONTROL")), 0)));
        var rows = new StringBuilder("format,action,recovered,losses,warnings,fallback\n");
        for (var target : List.of(new MarkdownAdapter().conversionTarget(), engine.conversionTarget("SVG", new SvgBackend()))) {
            String format = target.format(), extension = format.equals("MARKDOWN") ? "md" : "svg";
            Path directory = OUTPUT.resolve(extension); Files.createDirectories(directory);
            var saved = XmlModelRecovery.capture(DocumentConversion.from(source, List.of()).prepare(target)).resources(resources).prepare();
            saved.writeDocument(directory.resolve("plain." + extension)); saved.writeEmbedded(directory.resolve("original." + extension));
            byte[] first = saved.embeddedBytes();
            for (int round = 0; round < 3; round++) {
                var request = XmlModelRecovery.openEmbedded(format, saved.embeddedBytes()); var opened = request.prepare();
                require(opened.recovered() && opened.document().equals(source), "single-file model changed");
                require(opened.resources().orElseThrow().licenses().equals(resources.licenses()), "licenses changed");
                saved = XmlModelRecovery.capture(request.conversion().prepare(target)).resources(opened.resources().orElseThrow()).prepare();
            }
            require(Arrays.equals(first, saved.embeddedBytes()), "three rounds changed the single file");
            saved.writeEmbedded(directory.resolve("cycle-3." + extension));
            var request = XmlModelRecovery.openEmbedded(format, saved.embeddedBytes());
            request.conversion().prepare(engine.conversionTarget("PDF", new PdfBackend())).to(directory.resolve("control.pdf"));
            var edited = XmlModelRecovery.capture(request.conversion().edit(editor -> editor.replaceText("ORIGINAL", "EDITED"))
                    .prepare(target)).resources(resources).prepare();
            edited.writeEmbedded(directory.resolve("edited." + extension));
            XmlModelRecovery.openEmbedded(format, edited.embeddedBytes()).conversion().prepare(engine.conversionTarget("PDF", new PdfBackend())).to(directory.resolve("edited.pdf"));
            String current = new String(first, StandardCharsets.UTF_8);
            current = format.equals("MARKDOWN") ? current.replace("ORIGINAL", "EXTERNAL") : current.replace("fill=\"rgb(0.0% 0.0% 0.0%)\"", "fill=\"rgb(0% 0% 100%)\"");
            require(!current.equals(new String(first, StandardCharsets.UTF_8)), "external control did not change current output");
            byte[] external = current.getBytes(StandardCharsets.UTF_8); Files.write(directory.resolve("external." + extension), external);
            try { XmlModelRecovery.openEmbedded(format, external).prepare(); throw new IOException("stale recovery was accepted"); }
            catch (ProcessingException expected) { require(expected.issue().reason().equals("STALE_OR_CONFLICTING_COMPANION"), "unexpected stale rejection"); }
            rows.append(format).append(",ERROR,false,0,0,false\n");
            for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
                var policy = ProcessingPolicy.builder().defaultRule(action, ProcessingPolicy.Treatment.DEGRADE)
                        .feature("DOCUMENT_METADATA", action, ProcessingPolicy.Treatment.DISCARD).build();
                var fallback = XmlModelRecovery.openEmbedded(format, external).policy(policy);
                if (format.equals("SVG")) {
                    try { fallback.prepare(); throw new IOException("SVG fabricated a current-file importer"); }
                    catch (IllegalArgumentException expected) { require(expected.getMessage().contains("fallback"), "unexpected SVG rejection"); }
                    rows.append(format).append(',').append(action).append(",false,0,0,false\n");
                } else {
                    fallback.fallback((bytes, shared) -> {
                        var result = MarkdownImporter.builder().policy(shared).build().read(new String(bytes, StandardCharsets.UTF_8)).execute();
                        return DocumentConversion.Input.of(result.document(), result.report());
                    });
                    var result = fallback.prepare(); require(!result.recovered(), "Markdown used stale model");
                    var next = XmlModelRecovery.capture(fallback.conversion().prepare(target)).resources(resources).prepare();
                    next.writeEmbedded(directory.resolve("fallback-" + action + ".md"));
                    require(!XmlModelRecovery.openEmbedded(format, next.embeddedBytes()).prepare().resources().orElseThrow().source().contains("ORIGINAL"), "old text survived fallback");
                    rows.append(format).append(',').append(action).append(",false,").append(result.report().losses().size()).append(',').append(result.report().warnings().size()).append(",true\n");
                }
            }
        }
        Files.writeString(OUTPUT.resolve("policy.csv"), rows);
        System.out.println("Embedded Markdown/SVG: three rounds, resources, actual edits, current-file fallback and SVG rejection verified");
    }
    private static Paragraph paragraph(String id, String value) { return Paragraph.mixed(id, List.of(new InlineText(value, InlineStyle.of(12))), ParagraphFormat.of(12)); }
    private static void require(boolean value, String message) throws IOException { if (!value) { throw new IOException(message); } }
}
