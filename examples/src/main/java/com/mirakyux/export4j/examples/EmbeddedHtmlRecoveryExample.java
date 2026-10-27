// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.adapter.template.XmlModelRecovery;
import com.mirakyux.export4j.adapter.template.XmlTemplateBundle;
import com.mirakyux.export4j.adapter.web.HtmlAdapter;
import com.mirakyux.export4j.adapter.web.HtmlImporter;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Single standard HTML files carry the same bounded XML/resources without a sidecar. */
public final class EmbeddedHtmlRecoveryExample {
    private static final Path OUTPUT = Path.of("examples/build/embedded-html-recovery");
    private static final HtmlAdapter HTML = new HtmlAdapter();
    private EmbeddedHtmlRecoveryExample() { }
    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var resources = XmlTemplateBundle.builder("<document version='1'><p>UNUSED-SOURCE</p></document>")
                .font("Regular.ttf", Files.readAllBytes(Path.of("assets/fonts/LiberationSans-Regular.ttf")))
                .license("Font.txt", Files.readString(Path.of("assets/fonts/LiberationSans-OFL.txt"))).build();
        var source = new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("s", PageSettings.A4, List.of(
                paragraph("first", "ORIGINAL EMBEDDED-HTML"), paragraph("second", "UNCHANGED-CONTROL")), 0)));
        var saved = XmlModelRecovery.capture(DocumentConversion.from(source, List.of()).prepare(HTML.conversionTarget())).resources(resources).prepare();
        saved.writeDocument(OUTPUT.resolve("plain.html")); saved.writeEmbeddedHtml(OUTPUT.resolve("original.html"));
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(TrueTypeFontLoader.load(resources.fonts().get("Regular.ttf"))))).build();
        for (int round = 0; round < 3; round++) {
            var request = XmlModelRecovery.openEmbeddedHtml(saved.embeddedHtmlBytes()); var opened = request.prepare();
            require(opened.recovered() && opened.document().equals(source) && opened.resources().orElseThrow().licenses().equals(resources.licenses()), "single-file model/resources changed");
            saved = XmlModelRecovery.capture(request.conversion().prepare(HTML.conversionTarget())).resources(opened.resources().orElseThrow()).prepare();
        }
        saved.writeEmbeddedHtml(OUTPUT.resolve("cycle-3.html"));
        var request = XmlModelRecovery.openEmbeddedHtml(saved.embeddedHtmlBytes());
        request.conversion().prepare(engine.conversionTarget("PDF", new PdfBackend())).to(OUTPUT.resolve("control.pdf"));
        var edited = XmlModelRecovery.capture(request.conversion().edit(editor -> editor.replaceText("ORIGINAL", "EDITED"))
                .prepare(HTML.conversionTarget())).resources(resources).prepare();
        edited.writeEmbeddedHtml(OUTPUT.resolve("edited.html"));
        XmlModelRecovery.openEmbeddedHtml(edited.embeddedHtmlBytes()).conversion().prepare(engine.conversionTarget("PDF", new PdfBackend())).to(OUTPUT.resolve("edited.pdf"));
        byte[] current = new String(saved.embeddedHtmlBytes(), StandardCharsets.UTF_8).replace("ORIGINAL EMBEDDED-HTML", "EXTERNAL EMBEDDED-HTML").getBytes(StandardCharsets.UTF_8);
        Files.write(OUTPUT.resolve("external.html"), current);
        try { XmlModelRecovery.openEmbeddedHtml(current).prepare(); throw new IOException("external edit restored the old model"); }
        catch (ProcessingException expected) { require(expected.issue().reason().equals("STALE_OR_CONFLICTING_COMPANION"), "unexpected rejection"); }
        var rows = new StringBuilder("action,recovered,losses,warnings\nERROR,false,0,0\n");
        for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
            var policy = ProcessingPolicy.builder().defaultRule(action, ProcessingPolicy.Treatment.DEGRADE)
                    .feature("HTML_METADATA", action, ProcessingPolicy.Treatment.DISCARD).build();
            var fallback = XmlModelRecovery.openEmbeddedHtml(current).policy(policy).fallback((bytes, shared) -> {
                var imported = HtmlImporter.builder().policy(shared).build().read(new String(bytes, StandardCharsets.UTF_8)).execute();
                return DocumentConversion.Input.of(imported.document(), imported.report());
            });
            var imported = fallback.prepare(); require(!imported.recovered(), "fallback used embedded source");
            var next = XmlModelRecovery.capture(fallback.conversion().prepare(HTML.conversionTarget())).resources(resources).prepare();
            next.writeEmbeddedHtml(OUTPUT.resolve("fallback-" + action + ".html"));
            require(!XmlModelRecovery.openEmbeddedHtml(next.embeddedHtmlBytes()).prepare().resources().orElseThrow().source().contains("ORIGINAL"), "old text survived fallback");
            rows.append(action).append(",false,").append(imported.report().losses().size()).append(',').append(imported.report().warnings().size()).append('\n');
        }
        Files.writeString(OUTPUT.resolve("policy.csv"), rows);
        System.out.println("Embedded HTML: three single-file rounds, explicit fonts, real edits and current-file policy fallback verified");
    }
    private static Paragraph paragraph(String id, String value) { return Paragraph.mixed(id, List.of(new InlineText(value, InlineStyle.of(12))), ParagraphFormat.of(12)); }
    private static void require(boolean value, String message) throws IOException { if (!value) { throw new IOException(message); } }
}
