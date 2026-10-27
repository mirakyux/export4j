// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfRecoveryCodec;
import com.mirakyux.export4j.adapter.template.XmlModelRecovery;
import com.mirakyux.export4j.adapter.template.XmlTemplateBundle;
import com.mirakyux.export4j.adapter.template.XmlTemplateWriter;
import com.mirakyux.export4j.adapter.web.HtmlAdapter;
import com.mirakyux.export4j.adapter.web.HtmlImporter;
import com.mirakyux.export4j.adapter.web.MarkdownAdapter;
import com.mirakyux.export4j.adapter.web.MarkdownImporter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.raster.RasterBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.Navigation;
import com.mirakyux.export4j.components.TableOfContents;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.Caption;
import com.mirakyux.export4j.model.CrossReference;
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
import com.mirakyux.export4j.model.PageRegion;
import com.mirakyux.export4j.model.PageRegions;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.processing.DocumentConversion;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import com.mirakyux.export4j.text.ParagraphStyle;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Actual note/reference recovery with an explicitly rebuilt, current-heading contents configuration. */
public final class NavigationRecoveryExample {
    private static final Path OUTPUT = Path.of("examples/build/navigation-recovery");
    private static final List<String> FORMATS = List.of("PDF", "DOCX", "HTML", "MARKDOWN");
    private static final ParagraphFormat FORMAT = ParagraphFormat.builder().fontSize(10).lineHeight(18).build();
    private static final InlineStyle STYLE = InlineStyle.of(10);
    private NavigationRecoveryExample() { }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var resources = XmlTemplateBundle.builder("<document version='1'><p>UNUSED-NAVIGATION-SOURCE</p></document>")
                .font("Regular.ttf", Files.readAllBytes(Path.of("assets/fonts/LiberationSans-Regular.ttf")))
                .license("Font.txt", Files.readString(Path.of("assets/fonts/LiberationSans-OFL.txt"))).build();
        var fonts = FontGroup.strict(List.of(TrueTypeFontLoader.load(resources.fonts().get("Regular.ttf"))));
        var source = source(); var policy = policy(ProcessingPolicy.Action.WARN);
        var rows = new StringBuilder("format,action,export_losses,import_losses,total,warnings,notes\n");
        for (String format : FORMATS) {
            var first = capture(DocumentConversion.from(source.document(), source.notes()), format, resources, fonts, policy);
            publish(first, "original");
            var baseline = reopen(first).prepare();
            require(source.document().equals(baseline.document()) == format.equals("PDF"), "native semantic boundary differs: " + format);
            require(baseline.notes().equals(source.notes()) == format.equals("PDF"), "discarded notes returned");
            var control = first;
            for (int cycle = 0; cycle < 3; cycle++) {
                control = XmlModelRecovery.capture(reopen(control).conversion().prepare(XmlTemplateWriter.conversionTarget())).resources(resources).prepare();
                var result = reopen(control).prepare();
                require(result.document().equals(baseline.document()) && result.notes().equals(baseline.notes())
                        && result.report().issues().equals(baseline.report().issues()), "XML control changed semantic model/history");
            }
            control.writeDocument(directory(format).resolve("cycle-3.xml.zip"));
            var saved = first;
            for (int cycle = 0; cycle < 3; cycle++) {
                int history = saved.preview().losses().size();
                saved = capture(reopen(saved).conversion(), format, resources, fonts, policy);
                require(saved.preview().losses().size() >= history, "target cycle dropped history");
                if (format.equals("PDF")) {
                    var result = reopen(saved).prepare();
                    require(result.document().equals(source.document()) && result.notes().equals(source.notes()), "PDF semantic cycle changed");
                }
            }
            publish(saved, "cycle-3");
            var before = reopen(saved).prepare();
            var revisedNotes = before.notes().stream().map(note -> new Note(note.id(), note.paragraphId(),
                    note.text().replace("ORIGINAL", "EDITED"), note.kind())).toList();
            var editing = reopen(saved).conversion().edit(editor -> {
                editor.notes(revisedNotes);
                for (var section : before.document().sections()) {
                    for (var block : section.blocks()) {
                        String contents = block instanceof Heading h ? h.text() : block instanceof Paragraph p ? p.text()
                                : block instanceof Caption c ? c.text() : "";
                        if (contents.contains("ORIGINAL")) { editor.replaceText(block.id(), "ORIGINAL", "EDITED"); }
                    }
                }
                if (format.equals("PDF")) {
                    editor.replaceBlock("cross-page", new CrossReference("cross-page", "CROSS-PAGE=", "heading-a", CrossReference.Kind.PAGE, FORMAT));
                }
            });
            var changed = capture(editing, format, resources, fonts, policy); publish(changed, "edited");
            var current = reopen(changed).prepare();
            String xml = current.resources().orElseThrow().source();
            require(!xml.contains("ORIGINAL") && !xml.contains("UNUSED-NAVIGATION-SOURCE"), "semantic edit returned old content");
            render(baseline, fonts, directory(format).resolve("model-control.pdf"), true);
            render(current, fonts, directory(format).resolve("model-edited.pdf"), true);
            report(rows, format, "WARN", first, baseline);
            if (!format.equals("PDF")) {
                var ignored = capture(DocumentConversion.from(source.document(), source.notes()), format, resources, fonts, policy(ProcessingPolicy.Action.IGNORE));
                require(Arrays.equals(first.documentBytes(), ignored.documentBytes()), "WARN/IGNORE changed bytes");
                var state = reopen(ignored).prepare();
                require(state.document().equals(baseline.document()) && state.notes().isEmpty() && state.report().warnings().isEmpty(), "IGNORE changed recovery result");
                publish(ignored, "ignored"); report(rows, format, "IGNORE", ignored, state);
                try { DocumentConversion.from(source.document(), source.notes()).prepare(target(format, fonts)); throw new IOException("strict lossy target accepted"); }
                catch (ProcessingException expected) { rows.append(format).append(",ERROR,0,0,0,0,0\n"); }
            }
        }
        Files.writeString(OUTPUT.resolve("policy.csv"), rows);
        var original = XmlModelRecovery.openEmbedded("PDF", Files.readAllBytes(directory("PDF").resolve("embedded-original.pdf")), new PdfRecoveryCodec());
        render(original.prepare(), fonts, OUTPUT.resolve("without-runtime-navigation.pdf"), false);
        var engine = DocumentEngine.builder(fonts).build();
        for (var target : List.of(engine.conversionTarget("SVG", new SvgBackend()), engine.conversionTarget("PNG_ZIP", new RasterBackend(72)))) {
            try { original.conversion().policy(policy).prepare(target); throw new IOException("static backend accepted interactive reference data"); }
            catch (IllegalArgumentException expected) { require(expected.getMessage().contains("interactive"), "wrong static target refusal"); }
        }
        var matrix = new StringBuilder("a,b,first_losses,b_losses,final_losses,exact_model\n");
        for (String a : FORMATS) {
            var first = capture(DocumentConversion.from(source.document(), source.notes()), a, resources, fonts, policy);
            for (String b : FORMATS) {
                var middle = capture(reopen(first).conversion(), b, resources, fonts, policy);
                var last = capture(reopen(middle).conversion(), a, resources, fonts, policy);
                var result = reopen(last).prepare();
                boolean exact = source.document().equals(result.document()) && source.notes().equals(result.notes());
                require(exact == (a.equals("PDF") && b.equals("PDF")), "semantic A-B-A boundary differs");
                require(first.preview().losses().size() <= middle.preview().losses().size()
                        && middle.preview().losses().size() <= last.preview().losses().size(), "A-B-A history shrank");
                matrix.append(a).append(',').append(b).append(',').append(first.preview().losses().size()).append(',')
                        .append(middle.preview().losses().size()).append(',').append(last.preview().losses().size()).append(',').append(exact).append('\n');
            }
        }
        Files.writeString(OUTPUT.resolve("matrix.csv"), matrix);
        System.out.println("Navigation recovery: current-heading contents, notes, references, three cycles, actual edits and 16 A-B-A cases verified");
    }

    private static XmlModelRecovery.Saved capture(DocumentConversion.Request request, String format, XmlTemplateBundle resources,
            FontGroup fonts, ProcessingPolicy policy) throws IOException {
        var prepared = request.policy(policy).prepare(target(format, fonts));
        var save = XmlModelRecovery.capture(prepared).resources(resources).policy(policy);
        if (!format.equals("PDF")) { save.reimportOnLoss((bytes, shared) -> read(format, bytes, shared)); }
        return save.prepare();
    }
    private static DocumentConversion.Input read(String format, byte[] bytes, ProcessingPolicy policy) throws IOException {
        if (format.equals("DOCX")) {
            var result = new DocxAdapter().conversion(bytes).policy(policy).prepare(XmlTemplateWriter.conversionTarget()).to(OutputStream.nullOutputStream());
            return new DocumentConversion.Input(result.document(), result.notes(), result.report());
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        var result = format.equals("HTML") ? HtmlImporter.builder().policy(policy).build().read(text).execute()
                : MarkdownImporter.builder().policy(policy).build().read(text).execute();
        return DocumentConversion.Input.of(result.document(), result.report());
    }
    private static DocumentConversion.Target target(String format, FontGroup fonts) {
        return switch (format) {
            case "PDF" -> new DocumentConversion.Target("PDF", (document, notes, policy, output) ->
                    engine(document, fonts, true).conversionTarget("PDF", new PdfBackend()).writer().write(document, notes, policy, output));
            case "DOCX" -> new DocxAdapter().conversionTarget(); case "HTML" -> new HtmlAdapter().conversionTarget();
            case "MARKDOWN" -> new MarkdownAdapter().conversionTarget();
            default -> throw new IllegalArgumentException("unknown navigation target");
        };
    }
    private static DocumentEngine engine(SectionedDocument document, FontGroup fonts, boolean contents) {
        var builder = DocumentEngine.builder(fonts);
        if (contents) {
            var bookmarks = Navigation.fromHeadings(document).bookmarks();
            builder.navigation(new Navigation(bookmarks, TableOfContents.builder().title("RECOVERY-CONTENTS")
                    .style(ParagraphStyle.of(10)).build(), List.of(), List.of()));
        }
        return builder.build();
    }
    private static void render(XmlModelRecovery.Opened opened, FontGroup fonts, Path path, boolean contents) throws IOException {
        DocumentConversion.from(opened.document(), opened.notes()).prepare(contents ? target("PDF", fonts)
                : engine(opened.document(), fonts, false).conversionTarget("PDF", new PdfBackend())).to(path);
    }
    private static ProcessingPolicy policy(ProcessingPolicy.Action action) {
        return ProcessingPolicy.builder().defaultRule(action, ProcessingPolicy.Treatment.DEGRADE)
                .feature("NOTES", action, ProcessingPolicy.Treatment.DISCARD)
                .feature("METADATA", action, ProcessingPolicy.Treatment.DISCARD)
                .feature("PAGE_NUMBERING", action, ProcessingPolicy.Treatment.DISCARD)
                .feature("HTML_METADATA", action, ProcessingPolicy.Treatment.DISCARD).build();
    }
    private static DocumentTemplate.Rendered source() {
        var page = PageSettings.builder().size(420, 480).margins(28).build();
        var bands = PageRegions.builder().footer(new PageRegion(22, 2, List.of(paragraph("footer", List.of(text("FINAL="),
                field(InlineField.Kind.PAGE, "", 20), text("/"), field(InlineField.Kind.PAGES, "", 20)))))).build();
        var first = List.<Block>of(Heading.rich("heading-a", List.of(text("CHAPTER-ORIGINAL")), 1, FORMAT),
                paragraph("body", List.of(text("BODY-ORIGINAL ${literal}"), field(InlineField.Kind.NOTE_MARKER, "foot", 16), text(" ANCHOR-END"))),
                new Caption("figure", "Figure", "CAPTION-ORIGINAL", FORMAT),
                NumberedItem.rich("step", "steps", List.of(text("STEP-CONTROL")), 4, FORMAT, 1),
                paragraph("inline", List.of(text("PAGE-TARGET="), field(InlineField.Kind.REFERENCE_PAGE, "heading-b", 24),
                        text(" NUMBER-TARGET="), field(InlineField.Kind.REFERENCE_NUMBER, "step", 24), text(" TOTAL="), field(InlineField.Kind.PAGES, "", 18))),
                new PageBreak("break"), Heading.rich("detail", List.of(text("DETAIL-CHAPTER")), 2, FORMAT),
                paragraph("legacy", List.of(text("ANCHOR-SECOND"))),
                new CrossReference("cross-page", "CROSS-PAGE=", "heading-b", CrossReference.Kind.PAGE, FORMAT),
                new CrossReference("cross-number", "CROSS-NUMBER=", "figure", CrossReference.Kind.NUMBER, FORMAT));
        var document = new SectionedDocument(new DocumentMetadata("Navigation recovery", "Export4j"), List.of(
                new Section("a", page, first, 3, bands), new Section("b", page, List.of(
                        Heading.rich("heading-b", List.of(text("TARGET-CHAPTER")), 1, FORMAT),
                        paragraph("target", List.of(text("TARGET-FINAL")))), 12, bands)));
        return new DocumentTemplate.Rendered(document, List.of(new Note("foot", "body", "FOOT-ORIGINAL ${literal}", Note.Kind.FOOTNOTE),
                new Note("end", "figure", "END-ORIGINAL ${literal}", Note.Kind.ENDNOTE), new Note("legacy-note", "legacy", "FOOT-LEGACY", Note.Kind.FOOTNOTE)));
    }
    private static InlineText text(String value) { return new InlineText(value, STYLE); }
    private static InlineField field(InlineField.Kind kind, String target, double width) { return new InlineField(kind, target, width, STYLE); }
    private static Paragraph paragraph(String id, List<InlineContent> content) { return Paragraph.mixed(id, content, FORMAT); }
    private static Path directory(String format) throws IOException { var path = OUTPUT.resolve(format.toLowerCase(Locale.ROOT)); Files.createDirectories(path); return path; }
    private static String extension(String format) { return format.equals("MARKDOWN") ? "md" : format.toLowerCase(Locale.ROOT); }
    private static XmlModelRecovery.OpenRequest reopen(XmlModelRecovery.Saved saved) { return XmlModelRecovery.open(saved.format(), saved.documentBytes()).companion(saved.companionBytes().orElseThrow()); }
    private static void publish(XmlModelRecovery.Saved saved, String stem) throws IOException {
        var path = directory(saved.format()); String file = stem + "." + extension(saved.format());
        saved.writeDocument(path.resolve(file)); saved.writeCompanion(path.resolve(file + ".recovery.zip"));
        if (saved.format().equals("PDF")) { saved.writeEmbedded(new PdfRecoveryCodec(), path.resolve("embedded-" + file)); }
        else { saved.writeEmbedded(path.resolve("embedded-" + file)); }
    }
    private static void report(StringBuilder rows, String format, String action, XmlModelRecovery.Saved saved, XmlModelRecovery.Opened opened) {
        var issues = saved.preview().losses();
        rows.append(format).append(',').append(action).append(',').append(issues.stream().filter(i -> i.stage().equals("EXPORT") && !i.feature().equals(XmlModelRecovery.FEATURE)).count()).append(',')
                .append(issues.stream().filter(i -> i.stage().equals("IMPORT")).count()).append(',').append(issues.size()).append(',')
                .append(saved.preview().warnings().size()).append(',').append(opened.notes().size()).append('\n');
    }
    private static void require(boolean condition, String message) throws IOException { if (!condition) { throw new IOException(message); } }
}
