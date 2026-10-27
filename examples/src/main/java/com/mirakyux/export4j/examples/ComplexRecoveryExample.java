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
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.model.BulletItem;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.processing.DocumentConversion;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import com.mirakyux.export4j.model.processing.ProcessingReport;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;

/** Complex standard-file recovery, actual lossy reimport and A-B-A with explicit diagnostics. */
public final class ComplexRecoveryExample {
    private static final Path OUTPUT = Path.of("examples/build/complex-recovery");
    private static final ParagraphFormat FORMAT = ParagraphFormat.builder().fontSize(12).lineHeight(24).spaceAfter(5).build();
    private static final List<String> FORMATS = List.of("PDF", "DOCX", "HTML", "MARKDOWN", "SVG", "PNG", "PNG_ZIP");
    private ComplexRecoveryExample() { }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var resources = XmlTemplateBundle.builder("<document version='1'><p>UNUSED-OLD-SOURCE</p></document>")
                .font("Regular.ttf", Files.readAllBytes(Path.of("assets/fonts/LiberationSans-Regular.ttf")))
                .font("Bold.ttf", Files.readAllBytes(Path.of("assets/fonts/LiberationSans-Bold.ttf")))
                .license("Font.txt", Files.readString(Path.of("assets/fonts/LiberationSans-OFL.txt"))).build();
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(TrueTypeFontLoader.load(resources.fonts().get("Regular.ttf")))))
                .fontRole("bold", FontGroup.strict(List.of(TrueTypeFontLoader.load(resources.fonts().get("Bold.ttf"))))).build();
        var source = source(false);
        var rows = new StringBuilder("format,action,export_losses,import_losses,total,warnings,images\n");
        for (String format : FORMATS) {
            var target = target(format, engine);
            var policy = policy(ProcessingPolicy.Action.WARN);
            var saved = capture(DocumentConversion.from(source, List.of()).policy(policy), target, resources, policy);
            publish(saved, "original");
            var baseline = reopen(saved).prepare();
            listBodies(baseline.document());
            if (!web(format)) { require(source.equals(baseline.document()), "complex model did not recover exactly: " + format); }
            else {
                var imported = readWeb(format, saved.documentBytes(), policy);
                // Existing XML normalizes empty/plain text to explicit runs; compare its stable representation.
                String expectedXml = XmlTemplateWriter.from(imported.document()).notes(imported.notes()).prepare().bundle().source();
                require(expectedXml.equals(baseline.resources().orElseThrow().source()) && imported.notes().equals(baseline.notes()), "recovery contains richer source");
                require(baseline.resources().orElseThrow().images().isEmpty(), "unregistered image survived current-file import");
                require(baseline.report().issues().stream().anyMatch(issue -> issue.feature().equals("IMAGE_RESOURCE") && issue.stage().equals("IMPORT")), "image import loss missing");
            }
            var history = baseline.report().issues();
            // Three lossless XML control cycles must neither duplicate history nor reconstruct lost properties.
            var control = saved;
            for (int round = 0; round < 3; round++) {
                control = XmlModelRecovery.capture(reopen(control).conversion().prepare(XmlTemplateWriter.conversionTarget()))
                        .resources(resources).prepare();
                var current = reopen(control).prepare();
                require(current.document().equals(baseline.document()) && current.report().issues().equals(history), "complex XML control/history changed");
            }
            control.writeDocument(directory(format).resolve("cycle-3.xml.zip"));
            control.writeCompanion(directory(format).resolve("cycle-3.xml.zip.recovery.zip"));
            // Three actual target cycles exercise any additional export/import losses as new history.
            for (int round = 0; round < 3; round++) {
                saved = capture(reopen(saved).conversion().policy(policy), target, resources, policy);
                listBodies(reopen(saved).prepare().document());
                if (!web(format)) { require(reopen(saved).prepare().document().equals(source), "complex target cycle changed"); }
            }
            publish(saved, "cycle-3");
            var edited = capture(reopen(saved).conversion().policy(policy).edit(editor -> editor.replaceText("ORIGINAL", "EDITED")), target, resources, policy);
            publish(edited, "edited");
            var current = reopen(edited).prepare();
            var model = current.resources().orElseThrow();
            require(!model.source().contains("ORIGINAL") && model.source().contains("EDITED"), "complex edit restored deleted text");
            require(!model.source().contains("UNUSED-OLD-SOURCE"), "old resource source leaked");
            require(model.licenses().equals(resources.licenses()) && Arrays.equals(model.fonts().get("Regular.ttf"), resources.fonts().get("Regular.ttf")), "complex resources changed");
            DocumentConversion.from(baseline.document(), baseline.notes()).prepare(engine.conversionTarget("PDF", new PdfBackend()))
                    .to(directory(format).resolve("model-control.pdf"));
            DocumentConversion.from(current.document(), current.notes()).prepare(engine.conversionTarget("PDF", new PdfBackend()))
                    .to(directory(format).resolve("model-edited.pdf"));
            report(rows, format, ProcessingPolicy.Action.WARN, baseline.report(), baseline.resources().orElseThrow().images().size());
            if (web(format)) {
                var ignore = policy(ProcessingPolicy.Action.IGNORE);
                var ignored = capture(DocumentConversion.from(source, List.of()).policy(ignore), target, resources, ignore);
                require(Arrays.equals(ignored.documentBytes(), Files.readAllBytes(directory(format).resolve("original." + extension(format)))), "WARN and IGNORE differ in output");
                var ignoredModel = reopen(ignored).prepare();
                require(ignoredModel.document().equals(baseline.document()) && ignoredModel.report().warnings().isEmpty(), "IGNORE changed model/warnings");
                report(rows, format, ProcessingPolicy.Action.IGNORE, ignoredModel.report(), ignoredModel.resources().orElseThrow().images().size());
                publish(ignored, "ignored");
                try { DocumentConversion.from(source, List.of()).prepare(target); throw new IOException("strict complex Web accepted losses"); }
                catch (ProcessingException expected) { rows.append(format).append(",ERROR,0,0,0,0,0\n"); }
            }
        }
        Files.writeString(OUTPUT.resolve("policy.csv"), rows);
        matrix(source, resources, engine);
        references(resources, engine);
        System.out.println("Complex recovery: seven targets, three target/XML cycles, edits, actual Web reimport, three actions and 49 A-B-A cases verified");
    }

    private static void references(XmlTemplateBundle resources, DocumentEngine engine) throws IOException {
        var source = source(true); var policy = policy(ProcessingPolicy.Action.WARN);
        for (String format : List.of("PDF", "DOCX", "HTML", "MARKDOWN")) {
            var saved = capture(DocumentConversion.from(source, List.of()).policy(policy), target(format, engine), resources, policy);
            publish(saved, "reference");
            var opened = reopen(saved).prepare();
            require(web(format) || opened.document().equals(source), "native reference model changed");
            DocumentConversion.from(opened.document(), opened.notes()).prepare(engine.conversionTarget("PDF", new PdfBackend()))
                    .to(directory(format).resolve("reference-model.pdf"));
        }
        // A reference generates an interactive destination; static backends keep their explicit rejection.
        for (String format : List.of("SVG", "PNG", "PNG_ZIP")) {
            try { DocumentConversion.from(source, List.of()).prepare(target(format, engine)); throw new IOException("static reference extras silently discarded"); }
            catch (IllegalArgumentException expected) { require(expected.getMessage().contains("interactive extras"), "wrong static reference rejection"); }
        }
    }

    private static void matrix(SectionedDocument source, XmlTemplateBundle resources, DocumentEngine engine) throws IOException {
        var rows = new StringBuilder("a,b,first_losses,b_losses,final_losses,exact_model\n");
        var policy = policy(ProcessingPolicy.Action.WARN);
        for (String a : FORMATS) {
            var first = capture(DocumentConversion.from(source, List.of()).policy(policy), target(a, engine), resources, policy);
            for (String b : FORMATS) {
                var middle = capture(reopen(first).conversion().policy(policy), target(b, engine), resources, policy);
                var last = capture(reopen(middle).conversion().policy(policy), target(a, engine), resources, policy);
                var opened = reopen(last).prepare();
                listBodies(opened.document());
                if (!web(a) && !web(b)) { require(opened.document().equals(source), "complex lossless A-B-A changed model"); }
                require(opened.report().losses().size() >= reopen(middle).prepare().report().losses().size(), "matrix dropped loss history");
                rows.append(a).append(',').append(b).append(',').append(first.preview().losses().size()).append(',')
                        .append(middle.preview().losses().size()).append(',').append(last.preview().losses().size()).append(',')
                        .append(source.equals(opened.document())).append('\n');
            }
        }
        Files.writeString(OUTPUT.resolve("matrix.csv"), rows);
    }
    private static XmlModelRecovery.Saved capture(DocumentConversion.Request request, DocumentConversion.Target target,
            XmlTemplateBundle resources, ProcessingPolicy policy) throws IOException {
        var prepared = request.prepare(target);
        var save = XmlModelRecovery.capture(prepared).resources(resources).policy(policy);
        if (web(target.format())) { save.reimportOnLoss((bytes, shared) -> readWeb(target.format(), bytes, shared)); }
        return save.prepare();
    }
    private static DocumentConversion.Input readWeb(String format, byte[] bytes, ProcessingPolicy policy) throws IOException {
        String text = new String(bytes, StandardCharsets.UTF_8);
        var imported = format.equals("HTML") ? HtmlImporter.builder().policy(policy).build().read(text).execute()
                : MarkdownImporter.builder().policy(policy).build().read(text).execute();
        return DocumentConversion.Input.of(imported.document(), imported.report());
    }
    private static ProcessingPolicy policy(ProcessingPolicy.Action action) {
        return ProcessingPolicy.builder().defaultRule(action, ProcessingPolicy.Treatment.DEGRADE)
                .feature("METADATA", action, ProcessingPolicy.Treatment.DISCARD)
                .feature("PAGE_NUMBERING", action, ProcessingPolicy.Treatment.DISCARD)
                .feature("NOTES", action, ProcessingPolicy.Treatment.DISCARD).build();
    }
    private static DocumentConversion.Target target(String format, DocumentEngine engine) {
        return switch (format) {
            case "DOCX" -> new DocxAdapter().conversionTarget();
            case "HTML" -> new HtmlAdapter().conversionTarget();
            case "MARKDOWN" -> new MarkdownAdapter().conversionTarget();
            case "PDF" -> engine.conversionTarget(format, new PdfBackend());
            case "SVG" -> engine.conversionTarget(format, new SvgBackend());
            case "PNG" -> engine.conversionTarget(format, new RasterBackend(72).singlePage());
            case "PNG_ZIP" -> engine.conversionTarget(format, new RasterBackend(72));
            default -> throw new IllegalArgumentException("unknown complex target");
        };
    }
    private static SectionedDocument source(boolean reference) throws IOException {
        var pixels = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        var bytes = new ByteArrayOutputStream();
        try {
            for (int y = 0; y < 8; y++) { for (int x = 0; x < 8; x++) { pixels.setRGB(x, y, x < 4 ? 0x224488 : 0xEE8833); } }
            if (!ImageIO.write(pixels, "png", bytes)) { throw new IOException("PNG writer missing"); }
        } finally { pixels.flush(); }
        var image = new InlineImage(ImageResource.load(bytes.toByteArray()), 18, 18, 0, "IMAGE-ALT");
        var table = Table.builder("table").headerCells(cell("HEADER-A"), cell("HEADER-B"))
                .row(cell("CELL-ORIGINAL"), cell("CELL-UNCHANGED"))
                .row(cell("MERGED-CELL").toBuilder().columnSpan(2).build()).build();
        return new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("main", PageSettings.builder().margins(48).build(), List.of(
                Heading.rich("heading", List.of(text("COMPLEX-ORIGINAL")), 1, FORMAT),
                Paragraph.mixed("body", List.of(text("BODY-CONTROL ${literal}")), FORMAT),
                BulletItem.rich("bullet", List.of(text("BULLET-CONTROL")), "-", 1, FORMAT),
                NumberedItem.rich("number", "steps", List.of(text("NUMBER-CONTROL")), 1, FORMAT, 1),
                table, Paragraph.mixed("picture", List.of(text("PICTURE "), image), FORMAT),
                reference ? Paragraph.mixed("reference", List.of(text("REFERENCE-CONTROL="),
                        new InlineField(InlineField.Kind.REFERENCE_NUMBER, "number", 24, InlineStyle.of(12))), FORMAT)
                        : Paragraph.mixed("reference", List.of(text("REFERENCE-CONTROL=1")), FORMAT)), 0)));
    }
    private static InlineText text(String value) { return new InlineText(value, InlineStyle.of(12)); }
    private static void listBodies(SectionedDocument document) throws IOException {
        var blocks = document.sections().getFirst().blocks();
        var bullet = blocks.stream().filter(BulletItem.class::isInstance).map(BulletItem.class::cast).toList();
        var numbered = blocks.stream().filter(NumberedItem.class::isInstance).map(NumberedItem.class::cast).toList();
        require(bullet.size() == 1 && bullet.getFirst().text().equals("BULLET-CONTROL")
                && numbered.size() == 1 && numbered.getFirst().text().equals("NUMBER-CONTROL"), "list marker was duplicated into source body");
    }
    private static TableCell cell(String value) { return TableCell.builder().content(List.of(text(value))).build(); }
    private static boolean web(String format) { return format.equals("HTML") || format.equals("MARKDOWN"); }
    private static String extension(String format) { return switch (format) {
        case "MARKDOWN" -> "md"; case "PNG_ZIP" -> "png.zip"; default -> format.toLowerCase(java.util.Locale.ROOT);
    }; }
    private static Path directory(String format) throws IOException { Path directory = OUTPUT.resolve(format.toLowerCase(java.util.Locale.ROOT)); Files.createDirectories(directory); return directory; }
    private static XmlModelRecovery.OpenRequest reopen(XmlModelRecovery.Saved saved) { return XmlModelRecovery.open(saved.format(), saved.documentBytes()).companion(saved.companionBytes().orElseThrow()); }
    private static void publish(XmlModelRecovery.Saved saved, String name) throws IOException {
        Path directory = directory(saved.format()); String file = name + "." + extension(saved.format());
        saved.writeDocument(directory.resolve(file)); saved.writeCompanion(directory.resolve(file + ".recovery.zip"));
        if (saved.format().equals("PDF")) { saved.writeEmbedded(new PdfRecoveryCodec(), directory.resolve("embedded-" + file)); }
        else { saved.writeEmbedded(directory.resolve("embedded-" + file)); }
    }
    private static void report(StringBuilder rows, String format, ProcessingPolicy.Action action, ProcessingReport report, int images) {
        rows.append(format).append(',').append(action).append(',')
                .append(report.losses().stream().filter(issue -> issue.stage().equals("EXPORT") && !issue.feature().equals(XmlModelRecovery.FEATURE)).count()).append(',')
                .append(report.losses().stream().filter(issue -> issue.stage().equals("IMPORT")).count()).append(',')
                .append(report.losses().size()).append(',').append(report.warnings().size()).append(',').append(images).append('\n');
    }
    private static void require(boolean condition, String message) throws IOException { if (!condition) { throw new IOException(message); } }
}
