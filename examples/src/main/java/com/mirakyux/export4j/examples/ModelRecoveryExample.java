// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfFont;
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
import com.mirakyux.export4j.model.processing.ProcessingReport;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Standard outputs stay standard; explicit ordinary ZIP companions permit bounded model recovery. */
public final class ModelRecoveryExample {
    private static final Path OUTPUT = Path.of("examples/build/model-recovery");
    private static final DocxAdapter DOCX = new DocxAdapter();
    private static final ProcessingPolicy FALLBACK = ProcessingPolicy.builder()
            .defaultRule(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
            .feature("METADATA", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD)
            .feature("HTML_METADATA", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD)
            .feature("DOCUMENT_METADATA", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD)
            .feature("ENCRYPTION_REMOVED", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE).build();
    private ModelRecoveryExample() { }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        if (args.length > 0 && args[0].equals("--external")) { external(); return; }
        var resources = XmlTemplateBundle.builder("<document version='1'><p>UNUSED-SOURCE</p></document>")
                .font("Regular.ttf", Files.readAllBytes(Path.of("assets/fonts/LiberationSans-Regular.ttf")))
                .license("Font.txt", Files.readString(Path.of("assets/fonts/LiberationSans-OFL.txt"))).build();
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(TrueTypeFontLoader.load(resources.fonts().get("Regular.ttf"))))).build();
        var targets = List.of(engine.conversionTarget("PDF", new PdfBackend()), DOCX.conversionTarget(), new HtmlAdapter().conversionTarget(),
                new MarkdownAdapter().conversionTarget(), engine.conversionTarget("SVG", new SvgBackend()),
                engine.conversionTarget("PNG_ZIP", new RasterBackend(96)), XmlTemplateWriter.conversionTarget(resources));
        var source = source(); var matrix = new StringBuilder("from,to,rounds,model,resources,edited\n");
        for (var target : targets) {
            var saved = capture(DocumentConversion.from(source, List.of()).prepare(target), resources);
            publish(saved, "original");
            for (int round = 1; round <= 3; round++) {
                var opened = reopen(saved).prepare(); exact(source, opened, resources);
                saved = capture(reopen(saved).conversion().prepare(target), opened.resources().orElseThrow());
                if (round == 3) { publish(saved, "cycle-3"); }
            }
            var edited = capture(reopen(saved).conversion().edit(editor -> editor.replaceText("ORIGINAL", "EDITED"))
                    .prepare(target), resources);
            require(text(reopen(edited).prepare().document()).contains("EDITED"), "real edit was not recovered");
            require(!text(reopen(edited).prepare().document()).contains("ORIGINAL"), "old text returned after edit");
            publish(edited, "edited"); matrix.append(target.format()).append(',').append(target.format()).append(",3,true,true,true\n");
            for (var other : targets) {
                var bridge = capture(reopen(saved).conversion().prepare(other), resources);
                var back = capture(reopen(bridge).conversion().prepare(target), resources);
                exact(source, reopen(back).prepare(), resources);
                publish(back, "bridge-" + target.format() + "-" + other.format());
                matrix.append(target.format()).append(',').append(other.format()).append(",1,true,true,false\n");
            }
        }
        Files.writeString(OUTPUT.resolve("matrix.csv"), matrix);
        localEdits(resources);
        lossyWeb(resources, engine);
        System.out.println("Model recovery: seven formats, three rounds, 49 A-B-A controls, real edits and stale-file rejection verified");
    }

    private static void localEdits(XmlTemplateBundle resources) throws IOException {
        var original = Files.readAllBytes(OUTPUT.resolve("original.docx"));
        var copy = new ByteArrayOutputStream(); DOCX.editPackage(original).prepare().to(copy);
        require(java.util.Arrays.equals(original, copy.toByteArray()), "no-edit package bytes changed");
        DOCX.editPackage(original).replaceText("ORIGINAL", "PACKAGE-EDITED").prepare().to(OUTPUT.resolve("package-edited.docx"));
        checkCurrent("DOCX", "package-edited.docx", "PACKAGE-EDITED", resources);
        var pdf = new PdfAdapter(); var input = pdf.input(Files.readAllBytes(OUTPUT.resolve("original.pdf")), "");
        pdf.editText(input).replace(0, "ORIGINAL", "PDF-EDIT", PdfFont.of(resources.fonts().get("Regular.ttf")))
                .policy(ProcessingPolicy.builder().feature("TEXT_FONT", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE).build()).prepare()
                .to(OUTPUT.resolve("page-edited.pdf"));
        checkCurrent("PDF", "page-edited.pdf", "PDF-EDIT", resources);
        pdf.redact(input).area(0, 48, 45, 300, 28).dpi(96).policy(FALLBACK).prepare().to(OUTPUT.resolve("redacted.pdf"));
        var rejected = false;
        try { XmlModelRecovery.open("PDF", Files.readAllBytes(OUTPUT.resolve("redacted.pdf")))
                .companion(Files.readAllBytes(OUTPUT.resolve("original.pdf.recovery.zip"))).prepare(); }
        catch (ProcessingException expected) { rejected = expected.issue().reason().equals("STALE_OR_CONFLICTING_COMPANION"); }
        require(rejected, "redaction restored a stale model");
    }

    private static void lossyWeb(XmlTemplateBundle resources, DocumentEngine engine) throws IOException {
        var rich = new SectionedDocument(new DocumentMetadata("Loss metadata", "Export4j"), List.of(new Section("section",
                PageSettings.builder().size(420, 480).margins(24).build(), source().sections().getFirst().blocks(), 9)));
        var rows = new StringBuilder("format,action,losses,warnings,recovery\n");
        for (var target : List.of(new HtmlAdapter().conversionTarget(), new MarkdownAdapter().conversionTarget())) {
            for (var action : ProcessingPolicy.Action.values()) {
                var policy = ProcessingPolicy.builder().defaultRule(action, ProcessingPolicy.Treatment.DEGRADE)
                        .feature("METADATA", action, ProcessingPolicy.Treatment.DISCARD)
                        .feature("PAGE_NUMBERING", action, ProcessingPolicy.Treatment.DISCARD)
                        .feature(XmlModelRecovery.FEATURE, action, ProcessingPolicy.Treatment.DISCARD).build();
                if (action == ProcessingPolicy.Action.ERROR) {
                    try { DocumentConversion.from(rich, List.of()).policy(policy).prepare(target); throw new IOException("strict lossy export accepted"); }
                    catch (ProcessingException expected) { rows.append(target.format()).append(",ERROR,0,0,false\n"); }
                    continue;
                }
                var prepared = DocumentConversion.from(rich, List.of()).policy(policy).prepare(target);
                var saved = XmlModelRecovery.capture(prepared).resources(resources).policy(policy).prepare();
                require(saved.companionBytes().isEmpty(), "lossy target contains richer recovery data");
                saved.writeDocument(OUTPUT.resolve("lossy-" + action + "." + extension(target.format())));
                rows.append(target.format()).append(',').append(action).append(',').append(saved.preview().losses().size()).append(',')
                        .append(saved.preview().warnings().size()).append(",false\n");
            }
        }
        // Loss history survives subsequent strict, lossless capture without being counted again as a current export loss.
        var imported = MarkdownImporter.builder().policy(FALLBACK).build().read("> QUOTE-LOSS\n\nHISTORY-CURRENT\n").execute();
        var first = DocumentConversion.from("MARKDOWN", ignored -> DocumentConversion.Input.of(imported.document(), imported.report()))
                .prepare(engine.conversionTarget("PDF", new PdfBackend()));
        var saved = capture(first, resources); int count = saved.preview().losses().size(); require(count > 0, "missing historical loss");
        for (int round = 0; round < 3; round++) { saved = capture(reopen(saved).conversion().prepare(engine.conversionTarget("PDF", new PdfBackend())), resources); }
        require(saved.preview().losses().size() == count, "loss history was dropped or duplicated");
        publish(saved, "history");
        var restored = reopen(saved).prepare(); var firstParagraph = (Paragraph) restored.document().sections().getFirst().blocks().getFirst();
        var current = reopen(saved).conversion().policy(FALLBACK)
                .edit(editor -> editor.paragraphFormat(firstParagraph.id(), firstParagraph.format().toBuilder().firstLineIndent(7).build()))
                .prepare(new MarkdownAdapter().conversionTarget());
        var accumulated = XmlModelRecovery.capture(current).policy(ProcessingPolicy.builder()
                .feature(XmlModelRecovery.FEATURE, ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD).build()).prepare();
        require(!current.exportReport().losses().isEmpty() && accumulated.preview().losses().size() == count + current.exportReport().losses().size() + 1,
                "new export loss did not accumulate with history and missing recovery");
        require(accumulated.companionBytes().isEmpty(), "new lossy output retained an older rich model");
        accumulated.writeDocument(OUTPUT.resolve("history-lossy.md"));
        Files.writeString(OUTPUT.resolve("history.csv"), "prior,current,recovery,total\n" + count + "," + current.exportReport().losses().size() + ",1," + accumulated.preview().losses().size() + "\n");
        Files.writeString(OUTPUT.resolve("policy.csv"), rows);
    }

    private static void external() throws IOException {
        var rows = new StringBuilder("format,current,recovered,losses\n");
        for (var format : List.of("DOCX", "PDF", "HTML", "MARKDOWN", "SVG", "PNG_ZIP", "XML_TEMPLATE")) {
            Path file = OUTPUT.resolve("external." + extension(format));
            require(Files.isRegularFile(file), "external rewrite fixture missing");
            byte[] companion = Files.readAllBytes(OUTPUT.resolve("original." + extension(format) + ".recovery.zip"));
            var opened = XmlModelRecovery.open(format, Files.readAllBytes(file)).companion(companion);
            try { opened.prepare(); throw new IOException("external rewrite accepted stale recovery"); }
            catch (ProcessingException expected) { require(expected.issue().reason().equals("STALE_OR_CONFLICTING_COMPANION"), "unexpected recovery failure"); }
            if (List.of("DOCX", "PDF", "HTML", "MARKDOWN", "XML_TEMPLATE").contains(format)) {
                var fallback = opened.policy(FALLBACK).fallback((current, policy) -> readCurrent(format, current, policy)).prepare();
                require(!fallback.recovered(), "stale recovery returned old model");
                require(text(fallback.document()).contains("ORIGINAL"), "current file was not read");
                rows.append(format).append(',').append(file.getFileName()).append(",false,").append(fallback.report().losses().size()).append('\n');
            } else { rows.append(format).append(',').append(file.getFileName()).append(",false,1\n"); }
        }
        Files.writeString(OUTPUT.resolve("external.csv"), rows);
    }

    private static void checkCurrent(String format, String name, String expected, XmlTemplateBundle resources) throws IOException {
        byte[] current = Files.readAllBytes(OUTPUT.resolve(name)); byte[] companion = Files.readAllBytes(OUTPUT.resolve("original." + extension(format) + ".recovery.zip"));
        for (var action : ProcessingPolicy.Action.values()) {
            var policy = ProcessingPolicy.builder().defaultRule(action, ProcessingPolicy.Treatment.DEGRADE)
                    .feature("DOCUMENT_METADATA", action, ProcessingPolicy.Treatment.DISCARD).build();
            var request = XmlModelRecovery.open(format, current).companion(companion).policy(policy)
                    .fallback((bytes, shared) -> readCurrent(format, bytes, shared));
            if (action == ProcessingPolicy.Action.ERROR) {
                try { request.prepare(); throw new IOException("edited output restored stale recovery"); }
                catch (ProcessingException expectedFailure) { require(expectedFailure.issue().feature().equals(XmlModelRecovery.FEATURE), "unexpected edit failure"); }
            } else {
                var opened = request.prepare(); require(!opened.recovered(), "edited output used stale model");
                require(text(opened.document()).contains(expected) && !text(opened.document()).contains("ORIGINAL"), "deleted text was recovered");
                require(action == ProcessingPolicy.Action.WARN || opened.report().warnings().isEmpty(), "ignored warning was emitted");
                var replacement = XmlModelRecovery.capture(request.conversion().prepare(XmlTemplateWriter.conversionTarget())).resources(resources).prepare();
                replacement.writeDocument(OUTPUT.resolve(name + ".current.zip")); replacement.writeCompanion(OUTPUT.resolve(name + ".current.recovery.zip"));
            }
        }
    }

    private static DocumentConversion.Input readCurrent(String format, byte[] bytes, ProcessingPolicy policy) throws IOException {
        if (format.equals("DOCX")) {
            var result = DOCX.read(new ByteArrayInputStream(bytes), DocxAdapter.Policy.STRICT);
            return new DocumentConversion.Input(result.document(), List.of(), new ProcessingReport(ProcessingReport.Phase.ACTUAL, List.of()));
        }
        if (format.equals("PDF")) {
            var adapter = new PdfAdapter(); var result = adapter.importDocument(adapter.input(bytes, "")).policy(policy).prepare().execute();
            return new DocumentConversion.Input(result.document(), List.of(), result.report());
        }
        if (format.equals("XML_TEMPLATE")) {
            var result = XmlTemplateBundle.read(bytes).compile().renderComplete(java.util.Map.of());
            return new DocumentConversion.Input(result.document(), result.notes(), new ProcessingReport(ProcessingReport.Phase.ACTUAL, List.of()));
        }
        var result = format.equals("HTML") ? HtmlImporter.builder().policy(policy).build().read(new String(bytes, StandardCharsets.UTF_8)).execute()
                : MarkdownImporter.builder().policy(policy).build().read(new String(bytes, StandardCharsets.UTF_8)).execute();
        return DocumentConversion.Input.of(result.document(), result.report());
    }
    private static SectionedDocument source() {
        return new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("section", PageSettings.A4, List.of(
                paragraph("first", "ORIGINAL RECOVERY-CONTROL"), paragraph("second", "UNCHANGED-CONTROL")), 0)));
    }
    private static Paragraph paragraph(String id, String text) { return Paragraph.mixed(id, List.of(new InlineText(text, InlineStyle.of(12))), ParagraphFormat.of(12)); }
    private static String text(SectionedDocument document) {
        return document.sections().stream().flatMap(section -> section.blocks().stream()).filter(Paragraph.class::isInstance)
                .map(Paragraph.class::cast).map(Paragraph::text).collect(java.util.stream.Collectors.joining("\n"));
    }
    private static XmlModelRecovery.Saved capture(DocumentConversion.Prepared prepared, XmlTemplateBundle resources) throws IOException {
        return XmlModelRecovery.capture(prepared).resources(resources).prepare();
    }
    private static XmlModelRecovery.OpenRequest reopen(XmlModelRecovery.Saved saved) {
        return XmlModelRecovery.open(saved.format(), saved.documentBytes()).companion(saved.companionBytes().orElseThrow());
    }
    private static void publish(XmlModelRecovery.Saved saved, String name) throws IOException {
        String file = name + "." + extension(saved.format()); saved.writeDocument(OUTPUT.resolve(file)); saved.writeCompanion(OUTPUT.resolve(file + ".recovery.zip"));
    }
    private static String extension(String format) {
        return switch (format) { case "PDF" -> "pdf"; case "DOCX" -> "docx"; case "HTML" -> "html"; case "MARKDOWN" -> "md";
            case "SVG" -> "svg"; case "PNG_ZIP" -> "png.zip"; case "XML_TEMPLATE" -> "xml.zip"; default -> throw new IllegalArgumentException("unknown sample format"); };
    }
    private static void exact(SectionedDocument source, XmlModelRecovery.Opened opened, XmlTemplateBundle resources) throws IOException {
        require(opened.recovered() && source.equals(opened.document()) && opened.notes().isEmpty(), "round trip model mismatch");
        var assets = opened.resources().orElseThrow();
        require(java.util.Arrays.equals(assets.fonts().get("Regular.ttf"), resources.fonts().get("Regular.ttf")) && assets.licenses().equals(resources.licenses()), "round trip resource mismatch");
    }
    private static void require(boolean condition, String message) throws IOException { if (!condition) { throw new IOException(message); } }
}
