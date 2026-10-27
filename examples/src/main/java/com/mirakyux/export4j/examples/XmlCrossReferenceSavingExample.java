// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.adapter.template.XmlTemplate;
import com.mirakyux.export4j.adapter.template.XmlTemplateBundle;
import com.mirakyux.export4j.adapter.template.XmlTemplateWriter;
import com.mirakyux.export4j.adapter.web.HtmlAdapter;
import com.mirakyux.export4j.adapter.web.MarkdownAdapter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.model.Caption;
import com.mirakyux.export4j.model.CrossReference;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.DocumentTemplate;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.PageBreak;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Independent reference blocks remain editable across ordinary XML/ZIP saves. */
public final class XmlCrossReferenceSavingExample {
    private static final Path OUTPUT = Path.of("examples/build/xml-cross-reference-saving");
    private static final ParagraphFormat FORMAT = ParagraphFormat.builder().fontSize(11).lineHeight(20)
            .firstLineIndent(4).subsequentLineIndent(6).spaceAfter(6).build();
    private static final String PREFIX = "PAGE-ORIGINAL: this reference wraps across several words before the target page = ";
    private XmlCrossReferenceSavingExample() { }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var source = source();
        var saved = resources(XmlTemplateWriter.from(source)).prepare();
        if (!saved.preview().issues().isEmpty()) { throw new IOException("unexpected reference saving loss"); }
        saved.to(OUTPUT.resolve("original.zip"));
        var bundle = XmlTemplateBundle.read(Files.readAllBytes(OUTPUT.resolve("original.zip")));
        var reopened = bundle.compile().renderComplete(Map.of());
        if (!source.equals(reopened.document())) { throw new IOException("reference model changed"); }
        render(new DocumentTemplate.Rendered(source, List.of()), bundle, "source"); render(reopened, bundle, "reopened");
        for (int cycle = 0; cycle < 3; cycle++) {
            var next = resources(XmlTemplateWriter.from(reopened.document())).prepare().bundle();
            if (!next.source().equals(bundle.source())) { throw new IOException("reference XML changed"); }
            bundle = XmlTemplateBundle.read(next.encode()); reopened = bundle.compile().renderComplete(Map.of());
            if (!source.equals(reopened.document())) { throw new IOException("reference cycle changed"); }
        }
        bundle.write(OUTPUT.resolve("cycle-3.zip")); render(reopened, bundle, "cycle-3");
        var replacement = new CrossReference("ref-page", PREFIX.replace("ORIGINAL", "EDITED"), "figure-a", CrossReference.Kind.PAGE, FORMAT);
        var edit = bundle.conversion().edit(editor -> editor.replaceBlock("ref-page", replacement)).prepare(XmlTemplateWriter.conversionTarget());
        var actual = edit.to(OUTPUT.resolve("edited.zip"));
        if (actual.changes().size() != 1 || !actual.report().issues().isEmpty()
                || !actual.report().issues().equals(edit.preview().issues())) { throw new IOException("retarget report changed"); }
        var edited = resources(XmlTemplateWriter.from(actual.document())).prepare().bundle();
        edited.write(OUTPUT.resolve("edited.zip")); render(edited.compile().renderComplete(Map.of()), edited, "edited");
        dynamic(); web();
        System.out.println("XML reference saving: exact cycles, wrapped PAGE/NUMBER, retargeting, lexical targets and explicit Web losses verified");
    }

    private static SectionedDocument source() {
        var page = PageSettings.builder().size(420, 440).margins(28).build();
        return new SectionedDocument(new DocumentMetadata("XML independent cross references", "Export4j"), List.of(
                new Section("first", page, List.of(new Caption("figure-a", "Figure", "CAPTION-A", FORMAT),
                        NumberedItem.rich("item-a", "steps", List.of(new InlineText("ITEM-A", InlineStyle.of(11))), 4, FORMAT, 1),
                        new CrossReference("ref-page", PREFIX, "figure-b", CrossReference.Kind.PAGE, FORMAT),
                        new CrossReference("ref-number", "NUMBER-TARGET=", "figure-b", CrossReference.Kind.NUMBER, FORMAT),
                        new CrossReference("ref-item", "ITEM-TARGET=", "item-a", CrossReference.Kind.NUMBER, FORMAT), new PageBreak("break"),
                        paragraph("middle", "MIDDLE-PAGE ${literal}"),
                        new CrossReference("ref-back", "BACK-PAGE=", "figure-a", CrossReference.Kind.PAGE, FORMAT)), 3),
                new Section("second", page, List.of(new Caption("figure-b", "Figure", "CAPTION-B", FORMAT), paragraph("target", "TARGET-FINAL")), 12)));
    }

    private static Paragraph paragraph(String id, String text) {
        return Paragraph.mixed(id, List.of(new InlineText(text, InlineStyle.of(11))), FORMAT);
    }
    private static XmlTemplateWriter.Request resources(XmlTemplateWriter.Request request) throws IOException {
        return request.font("Regular.ttf", Files.readAllBytes(Path.of("assets/fonts/LiberationSans-Regular.ttf")))
                .license("LiberationSans-OFL.txt", Files.readString(Path.of("assets/fonts/LiberationSans-OFL.txt")));
    }
    private static void render(DocumentTemplate.Rendered bound, XmlTemplateBundle bundle, String name) throws IOException {
        var font = TrueTypeFontLoader.load(bundle.fonts().get("Regular.ttf"));
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(font))).build();
        engine.export(bound.document()).backend(new PdfBackend()).to(OUTPUT.resolve(name + ".pdf"));
    }
    private static void dynamic() throws IOException {
        var compiler = XmlTemplate.builder().fragment("panel", """
                <fragment version='1'><caption id='c' kind='Figure'>${item}</caption>
                  <cross-reference id='r' kind='NUMBER' target='c'>LOCAL=</cross-reference>
                  <cross-reference id='p' kind='PAGE' target='${finalId}'>DYNAMIC=</cross-reference>
                </fragment>
                """).build();
        var template = compiler.compile("""
                <document version='1'><page width='420' height='440' margin='28'/>
                  <each items='items' as='item'><if test='shown'><then><include ref='panel' prefix='copy'/></then></if></each>
                </document>
                """);
        var bound = template.renderComplete(Map.of("items", List.of("LOOP-A", "LOOP-B"), "shown", true, "finalId", "copy-c@1"));
        var bundle = resources(XmlTemplateWriter.from(bound.document())).policy(ProcessingPolicy.builder()
                .feature("IDENTIFIER", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE).build()).prepare().bundle();
        bundle.write(OUTPUT.resolve("dynamic.zip")); render(bundle.compile().renderComplete(Map.of()), bundle, "dynamic");
    }
    private static void web() throws IOException {
        var bundle = XmlTemplateBundle.builder("""
                <document version='1'><p id='target'>WEB-TARGET</p>
                  <cross-reference id='reference' kind='PAGE' target='target'>WEB-REFERENCE=</cross-reference>
                </document>
                """).build();
        StringBuilder csv = new StringBuilder("format,action,warnings,losses\n");
        for (var target : List.of(new HtmlAdapter().conversionTarget(), new MarkdownAdapter().conversionTarget())) {
            for (var action : ProcessingPolicy.Action.values()) {
                var policy = ProcessingPolicy.builder().feature("CROSS_REFERENCE", action, ProcessingPolicy.Treatment.DEGRADE).build();
                if (action == ProcessingPolicy.Action.ERROR) {
                    try { bundle.conversion().policy(policy).prepare(target); throw new AssertionError("strict unresolved reference accepted"); }
                    catch (ProcessingException expected) {
                        if (!expected.issue().feature().equals("CROSS_REFERENCE")) { throw new IOException("wrong strict reference failure", expected); }
                    }
                    csv.append(target.format()).append(",ERROR,0,0\n"); continue;
                }
                var prepared = bundle.conversion().policy(policy).prepare(target);
                var actual = prepared.to(OUTPUT.resolve("web-" + action + (target.format().equals("HTML") ? ".html" : ".md")));
                if (!prepared.preview().issues().equals(actual.report().issues()) || actual.report().losses().size() != 1) {
                    throw new IOException("reference loss report changed");
                }
                csv.append(target.format()).append(',').append(action).append(',').append(actual.report().warnings().size())
                        .append(',').append(actual.report().losses().size()).append('\n');
            }
        }
        Files.writeString(OUTPUT.resolve("policy.csv"), csv);
    }
}
