// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.template.XmlTemplateBundle;
import com.mirakyux.export4j.adapter.template.XmlTemplateWriter;
import com.mirakyux.export4j.adapter.web.HtmlAdapter;
import com.mirakyux.export4j.adapter.web.MarkdownAdapter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.model.BulletItem;
import com.mirakyux.export4j.model.Caption;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.DocumentTemplate;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.InlineContent;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.Note;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.PageBreak;
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
import java.util.Map;

/** Complete XML note/caption/link persistence with controls, real edits and explicit Web losses. */
public final class XmlSemanticSavingExample {
    private static final Path OUTPUT = Path.of("examples/build/xml-semantic-saving");
    private static final ParagraphFormat FORMAT = ParagraphFormat.builder().fontSize(11).lineHeight(22).spaceAfter(6).build();
    private static final InlineStyle STYLE = new InlineStyle("", 11, 0x224488, 0, false, false);
    private XmlSemanticSavingExample() { }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var source = source();
        var saved = resources(XmlTemplateWriter.from(source.document()).notes(source.notes())).prepare();
        if (!saved.preview().issues().isEmpty()) { throw new IOException("unexpected XML loss"); }
        saved.to(OUTPUT.resolve("original.zip"));
        var bundle = XmlTemplateBundle.read(Files.readAllBytes(OUTPUT.resolve("original.zip")));
        var reopened = bundle.compile().renderComplete(Map.of());
        if (!source.equals(reopened)) { throw new IOException("complete model changed"); }
        render(source, bundle, "source"); render(reopened, bundle, "reopened");
        for (int cycle = 0; cycle < 3; cycle++) {
            var next = resources(XmlTemplateWriter.from(reopened.document()).notes(reopened.notes())).prepare().bundle();
            if (!next.source().equals(bundle.source())) { throw new IOException("XML changed between cycles"); }
            bundle = XmlTemplateBundle.read(next.encode()); reopened = bundle.compile().renderComplete(Map.of());
            if (!source.equals(reopened)) { throw new IOException("complete model cycle changed"); }
        }
        bundle.write(OUTPUT.resolve("cycle-3.zip")); render(reopened, bundle, "cycle-3");
        var edit = bundle.conversion().edit(editor -> editor.replaceText("figure-1", "CAPTION-ORIGINAL", "CAPTION-EDITED"))
                .prepare(XmlTemplateWriter.conversionTarget());
        var result = edit.to(OUTPUT.resolve("edited.zip"));
        if (result.changes().size() != 1 || !result.notes().equals(source.notes()) || !result.report().issues().isEmpty()) { throw new IOException("caption edit changed notes"); }
        var edited = resources(XmlTemplateWriter.from(result.document()).notes(result.notes())).prepare().bundle();
        edited.write(OUTPUT.resolve("edited.zip")); render(edited.compile().renderComplete(Map.of()), edited, "edited");
        checkLosses(bundle);
        System.out.println("XML semantic saving: three exact cycles, complete notes/captions/links and explicit Web losses verified");
    }

    private static DocumentTemplate.Rendered source() {
        var body = paragraph("body", List.of(text("BODY-ORIGINAL "), new InlineField(InlineField.Kind.NOTE_MARKER, "foot-explicit", 12,
                new InlineStyle("", 9, 0x224488, -3, false, false)), text(" then exact anchor text.")));
        var blocks = List.of(Heading.rich("heading", List.of(link("LINK-HEADING", "heading")), 1, FORMAT), body,
                new Caption("figure-1", "Figure", "CAPTION-ORIGINAL ${literal}", FORMAT),
                BulletItem.rich("bullet", List.of(link("LINK-BULLET", "bullet")), "-", 1, FORMAT, STYLE),
                NumberedItem.rich("number", "steps", List.of(link("LINK-NUMBER", "number")), 4, FORMAT, 1, STYLE),
                paragraph("reference-1", List.of(text("FIRST="), reference("figure-1"))), new PageBreak("break"),
                new Caption("figure-2", "Figure", "CAPTION-SECOND", FORMAT), new Caption("table-1", "Table", "TABLE-CAPTION", FORMAT),
                paragraph("reference-2", List.of(text("SECOND="), reference("figure-2"), text(" TABLE="), reference("table-1"))));
        var document = new SectionedDocument(new DocumentMetadata("Complete XML semantic saving", "Export4j"), List.of(
                new Section("main", PageSettings.builder().size(420, 480).margins(26).build(), List.copyOf(blocks), 3)));
        return new DocumentTemplate.Rendered(document, List.of(
                new Note("foot-explicit", "body", "FOOT-EXPLICIT ${literal}", Note.Kind.FOOTNOTE),
                new Note("end", "figure-1", "END-NOTE ${literal}", Note.Kind.ENDNOTE),
                new Note("foot-legacy", "number", "FOOT-LEGACY", Note.Kind.FOOTNOTE)));
    }

    private static Paragraph paragraph(String id, List<InlineContent> content) { return Paragraph.mixed(id, content, FORMAT); }
    private static InlineText text(String value) { return new InlineText(value, STYLE); }
    private static InlineText link(String value, String target) { return new InlineText(value, STYLE.toBuilder().underline(true).build(), "https://example.com/" + target); }
    private static InlineField reference(String id) { return new InlineField(InlineField.Kind.REFERENCE_NUMBER, id, 64, STYLE); }
    private static XmlTemplateWriter.Request resources(XmlTemplateWriter.Request request) throws IOException {
        return request.font("Regular.ttf", Files.readAllBytes(Path.of("assets/fonts/LiberationSans-Regular.ttf")))
                .license("LiberationSans-OFL.txt", Files.readString(Path.of("assets/fonts/LiberationSans-OFL.txt")));
    }
    private static void render(DocumentTemplate.Rendered bound, XmlTemplateBundle bundle, String name) throws IOException {
        var font = TrueTypeFontLoader.load(bundle.fonts().get("Regular.ttf"));
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(font))).build();
        DocumentConversion.from(bound.document(), bound.notes()).prepare(engine.conversionTarget("PDF", new PdfBackend())).to(OUTPUT.resolve(name + ".pdf"));
    }

    private static void checkLosses(XmlTemplateBundle bundle) throws IOException {
        StringBuilder csv = new StringBuilder("format,action,warnings,losses\n");
        for (var target : List.of(new HtmlAdapter().conversionTarget(), new MarkdownAdapter().conversionTarget())) {
            for (var action : ProcessingPolicy.Action.values()) {
                var policy = ProcessingPolicy.builder().defaultRule(action, ProcessingPolicy.Treatment.DEGRADE)
                        .feature("NOTES", action, ProcessingPolicy.Treatment.DISCARD)
                        .feature("METADATA", action, ProcessingPolicy.Treatment.DISCARD)
                        .feature("PAGE_NUMBERING", action, ProcessingPolicy.Treatment.DISCARD).build();
                if (action == ProcessingPolicy.Action.ERROR) {
                    try { bundle.conversion().policy(policy).prepare(target); throw new AssertionError("strict Web notes accepted"); }
                    catch (ProcessingException expected) {
                        if (!expected.issue().feature().equals("NOTES")) { throw new IOException("unexpected strict failure", expected); }
                    }
                    csv.append(target.format()).append(",ERROR,0,0\n"); continue;
                }
                var prepared = bundle.conversion().policy(policy).prepare(target);
                String extension = target.format().equals("HTML") ? ".html" : ".md";
                var result = prepared.to(OUTPUT.resolve("web-" + action + extension));
                if (!prepared.preview().issues().equals(result.report().issues()) || result.report().losses().isEmpty()) { throw new IOException("Web loss mismatch"); }
                csv.append(target.format()).append(',').append(action).append(',').append(result.report().warnings().size())
                        .append(',').append(result.report().losses().size()).append('\n');
            }
        }
        try { bundle.conversion().prepare(new DocxAdapter().conversionTarget()); throw new AssertionError("strict DOCX notes accepted"); }
        catch (ProcessingException expected) {
            if (!expected.issue().feature().equals("NOTES")) { throw new IOException("unexpected strict DOCX failure", expected); }
        }
        Files.writeString(OUTPUT.resolve("policy.csv"), csv);
    }
}
