// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfFont;
import com.mirakyux.export4j.adapter.pdf.PdfOperationException;
import com.mirakyux.export4j.adapter.pdf.PreparedPdfImport;
import com.mirakyux.export4j.adapter.web.HtmlAdapter;
import com.mirakyux.export4j.adapter.web.MarkdownAdapter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import com.mirakyux.export4j.model.processing.ProcessingReport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Verifies explicit rendering fonts and bounded graphics-state import through published APIs. */
public final class PdfCompatibilityConsumer {
    private PdfCompatibilityConsumer() { }

    public static void main(String[] args) throws Exception {
        Path fixtures = Path.of("../../build/verification/pdf-compatibility-fixtures");
        Path output = Path.of(System.getProperty("consumer.outputDirectory")).resolve("pdf-compatibility");
        Files.createDirectories(output);
        byte[] fontBytes = Files.readAllBytes(Path.of("../../assets/fonts/LiberationSans-Regular.ttf"));
        var font = PdfFont.of(fontBytes);
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(TrueTypeFontLoader.load(fontBytes)))).build();
        var adapter = new PdfAdapter();
        int rejections = mappedFonts(adapter, font, fixtures, output);
        rejections += graphicsStates(adapter, engine, fixtures, output);
        Files.writeString(output.resolve("summary.json"), "{\"mappedSources\":2,\"mappedImages\":4,\"redactions\":4,\"stateConversions\":16,\"stateSkips\":8,\"rejections\":" + rejections + "}\n");
        System.out.println("PDF compatibility: explicit rendering fonts, source-metric controls, graphics states, loss actions and protected failures verified");
    }

    private static int mappedFonts(PdfAdapter adapter, PdfFont font, Path fixtures, Path output) throws IOException {
        int rejected = 0;
        for (String name : List.of("mapped-standard", "mapped-widths")) {
            String baseFont = name.equals("mapped-standard") ? "Helvetica" : "ExternalFixture";
            Path source = fixtures.resolve(name + ".pdf");
            byte[] original = Files.readAllBytes(source);
            Files.copy(source, output.resolve(name + "-source.pdf"), StandardCopyOption.REPLACE_EXISTING);
            var input = adapter.input(source, "");
            Path protectedOutput = output.resolve(name + "-protected.png");
            byte[] sentinel = "existing destination".getBytes(StandardCharsets.UTF_8);
            Files.write(protectedOutput, sentinel);
            expectFeature("RENDER_FONT_MAPPING", () -> adapter.render(input.allPages()).sourceFont(baseFont, font)
                    .prepare().images().getFirst().to(protectedOutput));
            rejected++;
            expectFeature("RENDER_FONT", () -> adapter.render(input.allPages()).sourceFont("WrongExactName", font)
                    .policy(mappingPolicy(ProcessingPolicy.Action.WARN)).prepare().images().getFirst().to(protectedOutput));
            rejected++;
            require(Arrays.equals(sentinel, Files.readAllBytes(protectedOutput)), "rejected mapping changed the old destination");
            for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
                String label = name + "-" + action.name().toLowerCase(Locale.ROOT);
                var prepared = adapter.render(input.allPages()).sourceFont(baseFont, font).dpi(144).policy(mappingPolicy(action)).prepare();
                require(prepared.images().size() == 1 && prepared.images().getFirst().sourcePages().equals(List.of(0)), "mapped rendering page identity");
                prepared.images().getFirst().to(output.resolve(label + ".png"));
                var written = prepared.toZip(output.resolve(label + ".zip"));
                report(prepared.preview(), written.report(), action);
                require(written.report().issues().stream().anyMatch(issue -> issue.feature().equals("RENDER_FONT_MAPPING")
                        && issue.treatment() == ProcessingPolicy.Treatment.DEGRADE
                        && issue.effects().contains("EXPLICIT_SOURCE_FONT_SUBSTITUTION")), "explicit source substitution loss missing");
                writeReport(output.resolve(label + ".losses.tsv"), written.report());
                var redacted = adapter.redact(input).sourceFont(baseFont, font).area(0, 18, 24, 205, 22).dpi(144)
                        .policy(mappingPolicy(action)).prepare();
                var result = redacted.to(output.resolve(label + "-redacted.pdf"));
                report(redacted.preview(), result.report(), action);
                require(result.pageCount() == 1 && result.changes() == 1, "mapped redaction did not sanitize one page");
                writeReport(output.resolve(label + "-redacted.losses.tsv"), result.report());
            }
            require(Arrays.equals(original, Files.readAllBytes(source)), "mapping altered the source PDF");
        }
        return rejected;
    }

    private static int graphicsStates(PdfAdapter adapter, DocumentEngine engine, Path fixtures, Path output) throws IOException {
        int rejected = 0;
        for (String name : List.of("gs-normal", "gs-compatible")) {
            Path source = fixtures.resolve(name + ".pdf");
            Files.copy(source, output.resolve(name + "-source.pdf"), StandardCopyOption.REPLACE_EXISTING);
            var input = adapter.input(source, "");
            for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
                var policy = importPolicy(action, false);
                var preparedImport = adapter.importDocument(input).policy(policy).prepare();
                var imported = preparedImport.execute();
                report(preparedImport.preview(), imported.report(), action);
                require(modelText(imported.document()).equals("GS BEFORE\nGS BODY\nGS AFTER"), "ordinary graphics-state import changed content/order");
                require(imported.report().issues().stream().noneMatch(issue -> issue.feature().equals("PDF_STRUCTURE")), "ordinary graphics state was skipped");
                String label = name + "-" + action.name().toLowerCase(Locale.ROOT);
                writeSources(output.resolve(label + ".sources.tsv"), imported);
                for (var target : List.of(new DocxAdapter().conversionTarget(), new HtmlAdapter().conversionTarget(),
                        new MarkdownAdapter().conversionTarget(), engine.conversionTarget("PDF", new PdfBackend()))) {
                    var prepared = adapter.importDocument(input).conversion().policy(policy)
                            .edit(editor -> editor.replaceText("GS BODY", "GS EDITED")).prepare(target);
                    String extension = target.format().toLowerCase(Locale.ROOT).replace("markdown", "md");
                    Path destination = output.resolve(label + "." + extension);
                    var result = prepared.to(destination);
                    report(prepared.preview(), result.report(), action);
                    writeReport(destination.resolveSibling(destination.getFileName() + ".losses.tsv"), result.report());
                }
            }
        }
        for (String kind : List.of("transparent", "blend", "font", "unknown")) {
            var input = adapter.input(fixtures.resolve("gs-" + kind + ".pdf"), "");
            expectFeature("PDF_STRUCTURE", () -> adapter.importDocument(input).policy(importPolicy(ProcessingPolicy.Action.WARN, false)).prepare());
            rejected++;
            for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
                var prepared = adapter.importDocument(input).policy(importPolicy(action, true)).prepare();
                var result = prepared.execute();
                report(prepared.preview(), result.report(), action);
                require(modelText(result.document()).equals("SAFE PAGE") && result.document().sections().size() == 1, "unsupported graphics-state page was partially recovered");
                require(result.report().issues().stream().anyMatch(issue -> issue.feature().equals("PDF_STRUCTURE")
                        && issue.treatment() == ProcessingPolicy.Treatment.SKIP_BLOCK), "skipped graphics state lacks its actual loss");
                String label = "gs-" + kind + "-" + action.name().toLowerCase(Locale.ROOT);
                Files.writeString(output.resolve(label + ".text.txt"), modelText(result.document()) + "\n");
                writeReport(output.resolve(label + ".losses.tsv"), result.report());
                writeSources(output.resolve(label + ".sources.tsv"), result);
            }
        }
        for (String kind : List.of("missing", "malformed")) {
            var input = adapter.input(fixtures.resolve("gs-" + kind + ".pdf"), "");
            Path destination = output.resolve("gs-" + kind + "-protected.pdf");
            byte[] sentinel = "existing destination".getBytes(StandardCharsets.UTF_8);
            Files.write(destination, sentinel);
            for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
                boolean failed = false;
                try {
                    adapter.importDocument(input).conversion().policy(importPolicy(action, true))
                            .prepare(engine.conversionTarget("PDF", new PdfBackend())).to(destination);
                } catch (PdfOperationException expected) {
                    require(expected.code() == PdfOperationException.Code.INVALID_PDF, "malformed graphics state did not fail as invalid input");
                    failed = true;
                }
                require(failed && Arrays.equals(sentinel, Files.readAllBytes(destination)), "malformed graphics state bypassed policy or changed the old target");
                rejected++;
            }
        }
        return rejected;
    }

    private static ProcessingPolicy mappingPolicy(ProcessingPolicy.Action action) {
        return ProcessingPolicy.builder().feature("RENDER_FONT_MAPPING", action, ProcessingPolicy.Treatment.DEGRADE)
                .feature("RASTERIZATION", action, ProcessingPolicy.Treatment.DEGRADE).build();
    }

    private static ProcessingPolicy importPolicy(ProcessingPolicy.Action action, boolean skipStructure) {
        return ProcessingPolicy.builder().defaultRule(action, ProcessingPolicy.Treatment.DEGRADE)
                .feature("PDF_VECTOR_GRAPHICS", action, ProcessingPolicy.Treatment.DISCARD)
                .feature("PDF_STRUCTURE", skipStructure ? action : ProcessingPolicy.Action.ERROR, ProcessingPolicy.Treatment.SKIP_BLOCK).build();
    }

    private static String modelText(SectionedDocument document) {
        return document.sections().stream().flatMap(section -> section.blocks().stream()).map(block -> {
            if (block instanceof Paragraph paragraph) { return paragraph.text(); }
            if (block instanceof Heading heading) { return heading.text(); }
            throw new IllegalStateException("unexpected recovered block");
        }).collect(Collectors.joining("\n"));
    }

    private static void report(ProcessingReport preview, ProcessingReport actual, ProcessingPolicy.Action action) {
        require(preview.phase() == ProcessingReport.Phase.PREVIEW && actual.phase() == ProcessingReport.Phase.ACTUAL
                && preview.issues().equals(actual.issues()) && !actual.issues().isEmpty(), "preview/actual loss reconciliation");
        require(actual.issues().stream().allMatch(issue -> issue.action() == action), "loss action was not retained");
        require((action == ProcessingPolicy.Action.IGNORE) == actual.warnings().isEmpty(), "warning visibility differs from the selected action");
    }

    private static void writeReport(Path path, ProcessingReport report) throws IOException {
        Files.writeString(path, "stage\tfeature\taction\ttreatment\teffects\n" + report.issues().stream()
                .map(issue -> issue.stage() + "\t" + issue.feature() + "\t" + issue.action() + "\t" + issue.treatment() + "\t" + String.join(",", issue.effects()))
                .collect(Collectors.joining("\n")) + "\n");
    }

    private static void writeSources(Path path, PreparedPdfImport.Result result) throws IOException {
        Files.writeString(path, "block\tpage\tx\ty\twidth\theight\n" + result.sources().stream()
                .map(item -> item.blockId() + "\t" + item.page() + "\t" + item.x() + "\t" + item.y() + "\t" + item.width() + "\t" + item.height())
                .collect(Collectors.joining("\n")) + "\n");
    }

    @FunctionalInterface private interface CheckedAction { void run() throws IOException; }

    private static void expectFeature(String feature, CheckedAction operation) throws IOException {
        try { operation.run(); }
        catch (ProcessingException expected) { require(expected.issue().feature().equals(feature), "unexpected rejection feature"); return; }
        throw new IllegalStateException("expected unsupported-feature rejection");
    }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message); }
    }
}
