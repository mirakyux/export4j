// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfFont;
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
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.Caption;
import com.mirakyux.export4j.model.CrossReference;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.DocumentNavigation;
import com.mirakyux.export4j.model.DocumentTemplate;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.LineBreakPolicy;
import com.mirakyux.export4j.model.Note;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.PageOverlay;
import com.mirakyux.export4j.model.PageRegions;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.processing.DocumentConversion;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Combined GAP-24 acceptance: portable declarations, current-file losses, original edits and reader saves. */
public final class RecoveryClosureExample {
    private static final Path OUTPUT = Path.of("examples/build/recovery-closure");
    private static final List<String> FORMATS = List.of("PDF", "DOCX", "HTML", "MARKDOWN");
    private static final ParagraphFormat FORMAT = ParagraphFormat.builder().fontSize(10).lineHeight(18).build();
    private static final InlineStyle STYLE = InlineStyle.of(10);
    private RecoveryClosureExample() { }
    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var assets = XmlTemplateBundle.builder("<document version='1'><p>UNUSED-CLOSURE-SOURCE</p></document>")
                .font("Regular.ttf", Files.readAllBytes(Path.of("assets/fonts/LiberationSans-Regular.ttf")))
                .font("Bold.ttf", Files.readAllBytes(Path.of("assets/fonts/LiberationSans-Bold.ttf")))
                .license("Font.txt", Files.readString(Path.of("assets/fonts/LiberationSans-OFL.txt"))).build();
        var fonts = FontGroup.strict(List.of(TrueTypeFontLoader.load(assets.fonts().get("Regular.ttf"))));
        var engine = DocumentEngine.builder(fonts).fontRole("bold", FontGroup.strict(List.of(TrueTypeFontLoader.load(assets.fonts().get("Bold.ttf"))))).build();
        if (args.length > 0 && args[0].equals("external")) { external(assets, engine); return; }
        var source = source(); var policy = policy(ProcessingPolicy.Action.WARN);
        var rows = new StringBuilder("format,action,losses,warnings,sections,notes,navigation\n");
        for (String format : FORMATS) {
            var first = capture(DocumentConversion.from(source.document(), source.notes()), format, assets, engine, policy);
            publish(first, "original");
            var baseline = reopen(first).prepare();
            require(exact(source, baseline) == format.equals("PDF"), "combined native boundary differs");
            require(baseline.document().navigation().equals(source.document().navigation()) == format.equals("PDF"), "discarded navigation returned");
            var saved = first;
            for (int cycle = 0; cycle < 3; cycle++) {
                var beforeControl = reopen(saved).prepare();
                var control = XmlModelRecovery.capture(reopen(saved).conversion().prepare(XmlTemplateWriter.conversionTarget())).resources(assets).prepare();
                var xml = reopen(control).prepare();
                require(xml.document().equals(beforeControl.document()) && xml.notes().equals(beforeControl.notes())
                        && xml.report().issues().equals(beforeControl.report().issues()), "XML control changed current model/history: " + format + ":" + cycle);
                int history = saved.preview().losses().size();
                saved = capture(reopen(saved).conversion(), format, assets, engine, policy);
                require(saved.preview().losses().size() >= history, "cycle lost history");
                if (format.equals("PDF")) { require(exact(source, reopen(saved).prepare()), "exact combined cycle changed"); }
            }
            publish(saved, "cycle-3");
            var current = reopen(saved).prepare();
            var notes = current.notes().stream().map(n -> new Note(n.id(), n.paragraphId(), n.text().replace("ORIGINAL", "EDITED"), n.kind())).toList();
            var edited = capture(reopen(saved).conversion().edit(edit -> edit.notes(notes).replaceText("ORIGINAL", "EDITED")), format, assets, engine, policy);
            publish(edited, "edited");
            var revised = reopen(edited).prepare();
            require(!revised.resources().orElseThrow().source().contains("ORIGINAL"), "removed source text returned");
            pdf(baseline, engine, directory(format).resolve("model-control.pdf"));
            pdf(revised, engine, directory(format).resolve("model-edited.pdf"));
            report(rows, format, "WARN", first, baseline);
            if (!format.equals("PDF")) {
                try { DocumentConversion.from(source.document(), source.notes()).prepare(target(format, engine)); throw new IOException("strict loss accepted"); }
                catch (ProcessingException expected) { rows.append(format).append(",ERROR,0,0,0,0,false\n"); }
                var ignored = capture(DocumentConversion.from(source.document(), source.notes()), format, assets, engine, policy(ProcessingPolicy.Action.IGNORE));
                require(Arrays.equals(first.documentBytes(), ignored.documentBytes()) && ignored.preview().warnings().isEmpty(), "IGNORE changed bytes/warnings");
                require(reopen(ignored).prepare().document().equals(baseline.document()), "action changed current model");
                report(rows, format, "IGNORE", ignored, reopen(ignored).prepare());
            }
        }
        Files.writeString(OUTPUT.resolve("policy.csv"), rows);
        var matrix = new StringBuilder("a,b,first_losses,b_losses,final_losses,exact_model\n");
        for (String a : FORMATS) {
            var first = capture(DocumentConversion.from(source.document(), source.notes()), a, assets, engine, policy);
            for (String b : FORMATS) {
                var middle = capture(reopen(first).conversion(), b, assets, engine, policy);
                var last = capture(reopen(middle).conversion(), a, assets, engine, policy);
                require(exact(source, reopen(last).prepare()) == (a.equals("PDF") && b.equals("PDF")), "combined A-B-A boundary differs");
                require(first.preview().losses().size() <= middle.preview().losses().size()
                        && middle.preview().losses().size() <= last.preview().losses().size(), "combined history shrank");
                matrix.append(a).append(',').append(b).append(',').append(first.preview().losses().size()).append(',')
                        .append(middle.preview().losses().size()).append(',').append(last.preview().losses().size()).append(',').append(exact(source, reopen(last).prepare())).append('\n');
            }
        }
        Files.writeString(OUTPUT.resolve("matrix.csv"), matrix);
        for (var backend : List.of(engine.conversionTarget("SVG", new SvgBackend()), engine.conversionTarget("PNG_ZIP", new RasterBackend(72)))) {
            try { DocumentConversion.from(source.document(), source.notes()).prepare(backend); throw new IOException("interactive static output accepted"); }
            catch (IllegalArgumentException expected) { require(expected.getMessage().contains("interactive"), "wrong static refusal"); }
        }
        originalEdits(assets);
        System.out.println("GAP-24 combined recovery: declaration/notes/sections/layers/tables/images/references, three cycles, real edits and 16 A-B-A cases verified");
    }
    private static DocumentTemplate.Rendered source() throws IOException {
        var base = SectionRecoveryExample.source(); var sections = new ArrayList<Section>();
        var first = new ArrayList<Block>();
        first.add(Heading.rich("chapter-a", List.of(text("CHAPTER-ORIGINAL")), 1, FORMAT));
        for (var block : base.sections().getFirst().blocks()) {
            if (block.id().equals("a1")) {
                first.add(Paragraph.mixed("a1", List.of(text("BODY-A1 ${literal} "), field(InlineField.Kind.NOTE_MARKER, "foot"), text(" BODY-ORIGINAL")), FORMAT));
                first.add(new Caption("figure", "Figure", "CAPTION-ORIGINAL", FORMAT));
                first.add(NumberedItem.rich("step", "steps", List.of(text("STEP-CONTROL")), 4, FORMAT, 1));
            } else { first.add(block); }
        }
        first.add(Paragraph.mixed("inline", List.of(text("TARGET-PAGE="), field(InlineField.Kind.REFERENCE_PAGE, "chapter-b"),
                text(" TARGET-NUMBER="), field(InlineField.Kind.REFERENCE_NUMBER, "step")), FORMAT));
        first.add(new CrossReference("cross-page", "CROSS-PAGE=", "chapter-b", CrossReference.Kind.PAGE, FORMAT));
        first.add(new CrossReference("cross-number", "CROSS-NUMBER=", "figure", CrossReference.Kind.NUMBER, FORMAT));
        var a = base.sections().getFirst(); sections.add(new Section(a.id(), a.pageSettings(), first, a.firstPageNumber(), noteClearance(a.pageRegions())));
        var b = base.sections().get(1); var second = new ArrayList<Block>();
        second.add(Heading.rich("chapter-b", List.of(text("TARGET-CHAPTER")), 1, FORMAT)); second.addAll(b.blocks());
        var layer = b.pageRegions().background().getFirst().content().blocks().getFirst();
        var image = ((Paragraph) layer).images().getFirst().image();
        second.add(Paragraph.mixed("picture", List.of(text("PICTURE-CONTROL "), new InlineImage(image.resource(), 24, 12, 0, "INLINE-PICTURE")), FORMAT));
        sections.add(new Section(b.id(), b.pageSettings(), second, b.firstPageNumber(), noteClearance(b.pageRegions())));
        var contents = new DocumentNavigation.Contents(true, "GAP24-CONTENTS", DocumentNavigation.TextStyle.of(10),
                Map.of(DocumentNavigation.Part.TITLE, new DocumentNavigation.TextStyle(16, 24, 0, 0, ParagraphAlignment.CENTER, LineBreakPolicy.STANDARD)),
                Map.of(DocumentNavigation.Part.TITLE, 0, DocumentNavigation.Part.ENTRY, 0x224488));
        var navigation = DocumentNavigation.builder().headings(true).maxHeadingLevel(3).contents(contents).build();
        var document = new SectionedDocument(new DocumentMetadata("Combined recovery", "Export4j"), sections, navigation);
        return new DocumentTemplate.Rendered(document, List.of(new Note("foot", "a1", "FOOT-ORIGINAL ${literal}", Note.Kind.FOOTNOTE),
                new Note("end", "b1", "END-ORIGINAL ${literal}", Note.Kind.ENDNOTE)));
    }
    private static PageRegions noteClearance(PageRegions value) {
        // Keep the deliberately overlapping image layers above the new footnote separator.
        return value.toBuilder().background(value.background().stream()
                .map(box -> new PageOverlay(box.x(), box.y() - 16, box.width(), box.content())).toList())
                .foreground(value.foreground().stream()
                        .map(box -> new PageOverlay(box.x(), box.y() - 16, box.width(), box.content())).toList()).build();
    }
    private static void originalEdits(XmlTemplateBundle assets) throws IOException {
        var adapter = new DocxAdapter(); byte[] source = Files.readAllBytes(directory("DOCX").resolve("original.docx"));
        var copy = new ByteArrayOutputStream(); adapter.editPackage(source).prepare().to(copy);
        require(Arrays.equals(source, copy.toByteArray()), "complex no-op package bytes changed");
        adapter.editPackage(source).replaceText("BODY-ORIGINAL", "PACKAGE-EDITED").prepare().to(OUTPUT.resolve("package-edited.docx"));
        var pdf = new PdfAdapter();
        // Page-preserving text editing has its own supported structures; interactivity is not silently removed.
        var plain = source().document().withNavigation(DocumentNavigation.NONE);
        plain = new SectionedDocument(plain.metadata(), plain.sections().stream().map(s -> new Section(s.id(), s.pageSettings(),
                s.blocks().stream().filter(block -> !(block instanceof CrossReference) && !block.id().equals("inline"))
                        .map(block -> block.id().equals("a1") ? (Block) new Paragraph("a1", "BODY-ORIGINAL", FORMAT) : block).toList(),
                s.firstPageNumber(), s.pageRegions())).toList());
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(TrueTypeFontLoader.load(assets.fonts().get("Regular.ttf"))))).build();
        var original = capture(DocumentConversion.from(plain, List.of()), "PDF", assets, engine, ProcessingPolicy.STRICT);
        original.writeDocument(OUTPUT.resolve("page-original.pdf")); original.writeCompanion(OUTPUT.resolve("page-original.pdf.recovery.zip"));
        pdf.editText(pdf.input(original.documentBytes(), "")).replace(0, "BODY-ORIGINAL", "PAGE-EDITED", PdfFont.of(assets.fonts().get("Regular.ttf")))
                .policy(ProcessingPolicy.builder().feature("TEXT_FONT", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE).build()).prepare()
                .to(OUTPUT.resolve("page-edited.pdf"));
        try { XmlModelRecovery.open("PDF", Files.readAllBytes(OUTPUT.resolve("page-edited.pdf"))).companion(original.companionBytes().orElseThrow()).prepare();
            throw new IOException("original-page edit revived stale model"); }
        catch (ProcessingException expected) { require(expected.issue().reason().equals("STALE_OR_CONFLICTING_COMPANION"), "wrong stale edit refusal"); }
    }
    private static void external(XmlTemplateBundle assets, DocumentEngine engine) throws IOException {
        Path word = OUTPUT.resolve("word/word-resaved.docx"); require(Files.isRegularFile(word), "run generated Word resave verification first");
        byte[] bytes = Files.readAllBytes(word); var rows = new StringBuilder("action,recovered,losses,warnings\n");
        for (var action : ProcessingPolicy.Action.values()) {
            var adapter = DocxAdapter.builder().regionGeometry(new DocxAdapter.RegionGeometry(24, 4, 24, 4)).build();
            var request = XmlModelRecovery.openEmbedded("DOCX", bytes).policy(policy(action)).fallback((current, shared) -> readDocx(adapter, current, shared));
            if (action == ProcessingPolicy.Action.ERROR) {
                try { request.prepare(); throw new IOException("Word resave accepted stale/missing recovery"); }
                catch (ProcessingException expected) { require(expected.issue().feature().equals(XmlModelRecovery.FEATURE), "wrong reader-save failure"); }
                rows.append("ERROR,false,0,0\n"); continue;
            }
            var opened = request.prepare(); require(!opened.recovered(), "Word resave revived old model");
            var prepared = request.conversion().prepare(XmlTemplateWriter.conversionTarget());
            var control = XmlModelRecovery.capture(prepared).resources(assets).policy(policy(action)).reimportOnLoss((current, shared) -> {
                var rendered = XmlTemplateBundle.read(current).compile().renderComplete(Map.of());
                return new DocumentConversion.Input(rendered.document(), rendered.notes(),
                        new com.mirakyux.export4j.model.processing.ProcessingReport(
                                com.mirakyux.export4j.model.processing.ProcessingReport.Phase.ACTUAL, List.of()));
            }).prepare();
            var source = reopen(control).prepare().resources().orElseThrow().source();
            require(source.contains("WORD-EDITED") && !source.contains("ORIGINAL") && !source.contains("GAP24-CONTENTS"), "reader-save fallback returned stale contents");
            control.writeDocument(OUTPUT.resolve("word/current-" + action + ".xml.zip"));
            pdf(opened, engine, OUTPUT.resolve("word/current-" + action + ".pdf"));
            rows.append(action).append(",false,").append(opened.report().losses().size()).append(',').append(opened.report().warnings().size()).append('\n');
        }
        Files.writeString(OUTPUT.resolve("word/policy.csv"), rows);
        System.out.println("Real Word edit/save/reopen: strict missing/stale rejection and current-file WARN/IGNORE fallback verified");
    }
    private static ProcessingPolicy policy(ProcessingPolicy.Action action) {
        var builder = ProcessingPolicy.builder().defaultRule(action, ProcessingPolicy.Treatment.DEGRADE);
        for (String feature : List.of("NAVIGATION", "NOTES", "METADATA", "PAGE_NUMBERING", "HTML_METADATA", "PAGE_OVERLAYS")) {
            builder.feature(feature, action, ProcessingPolicy.Treatment.DISCARD);
        }
        return builder.build();
    }
    private static XmlModelRecovery.Saved capture(DocumentConversion.Request request, String format, XmlTemplateBundle assets,
            DocumentEngine engine, ProcessingPolicy policy) throws IOException {
        var save = XmlModelRecovery.capture(request.policy(policy).prepare(target(format, engine))).resources(assets).policy(policy);
        if (!format.equals("PDF")) { save.reimportOnLoss((bytes, shared) -> read(format, bytes, shared)); }
        return save.prepare();
    }
    private static DocumentConversion.Input read(String format, byte[] bytes, ProcessingPolicy policy) throws IOException {
        if (format.equals("DOCX")) {
            return readDocx(new DocxAdapter(), bytes, policy);
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        var result = format.equals("HTML") ? HtmlImporter.builder().policy(policy).build().read(text).execute()
                : MarkdownImporter.builder().policy(policy).build().read(text).execute();
        return DocumentConversion.Input.of(result.document(), result.report());
    }
    private static DocumentConversion.Input readDocx(DocxAdapter adapter, byte[] bytes, ProcessingPolicy policy) throws IOException {
        var result = adapter.conversion(bytes).policy(policy).prepare(XmlTemplateWriter.conversionTarget()).to(OutputStream.nullOutputStream());
        return new DocumentConversion.Input(result.document(), result.notes(), result.report());
    }
    private static DocumentConversion.Target target(String format, DocumentEngine engine) {
        return switch (format) {
            case "PDF" -> engine.conversionTarget(format, new PdfBackend()); case "DOCX" -> new DocxAdapter().conversionTarget();
            case "HTML" -> new HtmlAdapter().conversionTarget(); case "MARKDOWN" -> new MarkdownAdapter().conversionTarget();
            default -> throw new IllegalArgumentException("unknown closure format");
        };
    }
    private static void pdf(XmlModelRecovery.Opened opened, DocumentEngine engine, Path path) throws IOException {
        DocumentConversion.from(opened.document(), opened.notes()).prepare(target("PDF", engine)).to(path);
    }
    private static boolean exact(DocumentTemplate.Rendered original, XmlModelRecovery.Opened current) {
        return original.document().equals(current.document()) && original.notes().equals(current.notes());
    }
    private static void publish(XmlModelRecovery.Saved saved, String name) throws IOException {
        String file = name + "." + (saved.format().equals("MARKDOWN") ? "md" : saved.format().toLowerCase(Locale.ROOT));
        var path = directory(saved.format()); saved.writeDocument(path.resolve(file)); saved.writeCompanion(path.resolve(file + ".recovery.zip"));
        if (saved.format().equals("PDF")) { saved.writeEmbedded(new PdfRecoveryCodec(), path.resolve("embedded-" + file)); }
        else { saved.writeEmbedded(path.resolve("embedded-" + file)); }
    }
    private static void report(StringBuilder rows, String format, String action, XmlModelRecovery.Saved saved, XmlModelRecovery.Opened opened) {
        rows.append(format).append(',').append(action).append(',').append(saved.preview().losses().size()).append(',')
                .append(saved.preview().warnings().size()).append(',').append(opened.document().sections().size()).append(',')
                .append(opened.notes().size()).append(',').append(!opened.document().navigation().equals(DocumentNavigation.NONE)).append('\n');
    }
    private static Path directory(String format) throws IOException { var path = OUTPUT.resolve(format.toLowerCase(Locale.ROOT)); Files.createDirectories(path); return path; }
    private static XmlModelRecovery.OpenRequest reopen(XmlModelRecovery.Saved saved) { return XmlModelRecovery.open(saved.format(), saved.documentBytes()).companion(saved.companionBytes().orElseThrow()); }
    private static InlineText text(String value) { return new InlineText(value, STYLE); }
    private static InlineField field(InlineField.Kind kind, String target) { return new InlineField(kind, target, 24, STYLE); }
    private static void require(boolean condition, String message) throws IOException { if (!condition) { throw new IOException(message); } }
}
