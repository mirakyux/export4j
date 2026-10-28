// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfOperationException;
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

/** Checks passive structure opt-in, default View, graph preservation and explicit recovery losses. */
public final class PdfPassiveConsumer {
    private PdfPassiveConsumer() { }

    public static void main(String[] args) throws Exception {
        Path fixtures = Path.of("../../build/verification/pdf-passive-fixtures");
        Path output = Path.of(System.getProperty("consumer.outputDirectory")).resolve("pdf-passive");
        Files.createDirectories(output);
        var strict = new PdfAdapter(); var adapter = PdfAdapter.builder().allowPassiveStructures(true).build();
        byte[] font = Files.readAllBytes(Path.of("../../assets/fonts/LiberationSans-Regular.ttf"));
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(TrueTypeFontLoader.load(font)))).build();
        int rejected = 0;
        for (String name : List.of("layers", "tagged-layers", "tagged-page", "tagged-form", "tagged-object")) {
            boolean layered = name.equals("layers") || name.equals("tagged-layers");
            boolean annotated = name.equals("tagged-object");
            Path source = fixtures.resolve(name + ".pdf"); byte[] original = Files.readAllBytes(source);
            Files.copy(source, output.resolve(name + "-source.pdf"), StandardCopyOption.REPLACE_EXISTING);
            var input = adapter.input(source, "");
            expectUnsupported(() -> strict.readObjects(strict.input(source, ""))); rejected++;
            if (annotated) {
                require(adapter.readObjects(input).annotations().size() == 1, "tagged OBJR annotation was not inspected");
                expectUnsupported(() -> adapter.read(input)); rejected++;
            } else {
                String text = adapter.read(input).pages().getFirst().text();
                require(compact(text).equals(compact(expected(name))), "default View text leaked hidden content or lost visible text");
                Files.writeString(output.resolve(name + ".read.txt"), text);
            }
            try { adapter.render(input.allPages()).prepare(); throw new IllegalStateException("default raster losses were accepted"); }
            catch (ProcessingException expected) {
                String firstFeature = annotated ? "COMMENTS" : layered ? "PDF_LAYERS" : "PDF_TAGS";
                require(firstFeature.equals(expected.issue().feature()), "unexpected strict passive raster loss for " + name + ": " + expected.issue().feature());
            }
            rejected++;
            adapter.editObjects(input).metadata("Title", "Passive structure preserved").prepare().to(output.resolve(name + "-edited.pdf"));
            Path protectedOutput = output.resolve(name + "-selection-protected.pdf"); byte[] sentinel = "existing destination".getBytes(StandardCharsets.UTF_8); Files.write(protectedOutput, sentinel);
            expectUnsupported(() -> adapter.copy(input.allPages(), protectedOutput)); rejected++;
            expectUnsupported(() -> adapter.composeObjects().add(input.allPages(), "source").prepare().to(protectedOutput)); rejected++;
            require(Arrays.equals(sentinel, Files.readAllBytes(protectedOutput)), "rejected passive selection changed old output");
            for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
                String label = name + "-" + action.name().toLowerCase(Locale.ROOT); var policy = policy(action);
                var raster = adapter.render(input.allPages()).dpi(144).policy(policy).prepare();
                require(raster.images().size() == 1, "passive rendering skipped its page");
                raster.images().getFirst().to(output.resolve(label + ".png"));
                var zipped = raster.toZip(output.resolve(label + ".zip")); report(raster.preview(), zipped.report(), action);
                writeReport(output.resolve(label + "-raster.losses.tsv"), zipped.report());
                var imported = adapter.importDocument(input).policy(policy).prepare(); var result = imported.execute();
                report(imported.preview(), result.report(), action); writeReport(output.resolve(label + "-import.losses.tsv"), result.report());
                require(result.report().issues().stream().anyMatch(issue -> issue.feature().equals(layered ? "PDF_LAYERS" : "PDF_TAGS")), "passive recovery lost its explicit diagnostic");
                if (layered) {
                    require(modelText(result.document()).isEmpty(), "layer raster recovery exposed source text");
                    var images = result.document().sections().stream().flatMap(section -> section.blocks().stream()).filter(Paragraph.class::isInstance)
                            .map(Paragraph.class::cast).flatMap(paragraph -> paragraph.images().stream()).toList();
                    require(images.size() == 1, "layer fallback did not recover one visible-page image");
                    Files.write(output.resolve(label + "-recovered.png"), images.getFirst().image().resource().bytes());
                } else { require(compact(modelText(result.document())).equals(compact(expected(name))), "tagged text recovery changed visible source text"); }
                Files.writeString(output.resolve(label + "-import.txt"), modelText(result.document()));
                for (var target : List.of(new DocxAdapter().conversionTarget(), engine.conversionTarget("PDF", new PdfBackend()))) {
                    var converted = adapter.importDocument(input).conversion().policy(policy).prepare(target);
                    String extension = target.format().toLowerCase(Locale.ROOT); Path destination = output.resolve(label + "." + extension);
                    var written = converted.to(destination); report(converted.preview(), written.report(), action);
                    writeReport(destination.resolveSibling(destination.getFileName() + ".losses.tsv"), written.report());
                }
            }
            require(Arrays.equals(original, Files.readAllBytes(source)), "passive operations mutated source bytes");
        }
        for (String name : List.of("invalid-layer-reference", "invalid-parent-tree", "invalid-structure-cycle")) {
            var input = adapter.input(fixtures.resolve(name + ".pdf"), "");
            Path target = output.resolve(name + "-protected.pdf"); byte[] sentinel = "existing destination".getBytes(StandardCharsets.UTF_8); Files.write(target, sentinel);
            for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
                try { adapter.editObjects(input).policy(policy(action)).metadata("Title", "Rejected").prepare().to(target); throw new IllegalStateException("invalid passive input was accepted"); }
                catch (PdfOperationException expected) { require(expected.code() == PdfOperationException.Code.INVALID_PDF, "invalid passive input used an unexpected error code"); }
                require(Arrays.equals(sentinel, Files.readAllBytes(target)), "invalid passive input replaced old output"); rejected++;
            }
        }
        Files.writeString(output.resolve("summary.json"), "{\"acceptedSources\":5,\"rasters\":10,\"imports\":10,\"conversions\":20,\"preservedEdits\":5,\"rejections\":" + rejected + "}\n");
        System.out.println("Passive PDF structures: default View, tags, preserved edits, losses and rejected remapping verified");
    }
    private static ProcessingPolicy policy(ProcessingPolicy.Action action) {
        return ProcessingPolicy.builder().defaultRule(action, ProcessingPolicy.Treatment.DEGRADE)
                .feature("PDF_TAGS", action, ProcessingPolicy.Treatment.DISCARD)
                .feature("DOCUMENT_METADATA", action, ProcessingPolicy.Treatment.DISCARD)
                .feature("ANNOTATIONS", action, ProcessingPolicy.Treatment.DISCARD)
                .feature("PDF_VECTOR_GRAPHICS", action, ProcessingPolicy.Treatment.DISCARD).build();
    }
    private static String expected(String name) {
        return switch (name) {
            case "layers", "tagged-layers" -> "LAYER TITLE VISIBLE ON VISIBLE ANY ON VISIBLE ANY OFF VISIBLE EXPRESSION";
            case "tagged-page" -> "TAGGED BODY";
            case "tagged-form" -> "TAGGED FORM";
            case "tagged-object" -> "TAGGED OBJECT";
            default -> throw new IllegalArgumentException("unknown fixture");
        };
    }
    private static String modelText(SectionedDocument document) {
        return document.sections().stream().flatMap(section -> section.blocks().stream()).map(block -> block instanceof Paragraph paragraph ? paragraph.text() : ((Heading) block).text())
                .collect(Collectors.joining("\n")).replace("\ufffc", "");
    }
    private static String compact(String value) { return value.replaceAll("\\s+", ""); }
    private static void report(ProcessingReport preview, ProcessingReport actual, ProcessingPolicy.Action action) {
        require(preview.phase() == ProcessingReport.Phase.PREVIEW && actual.phase() == ProcessingReport.Phase.ACTUAL && preview.issues().equals(actual.issues()), "passive preview and actual losses differ");
        require(actual.issues().stream().allMatch(issue -> issue.action() == action), "passive loss action changed");
        require((action == ProcessingPolicy.Action.IGNORE) == actual.warnings().isEmpty(), "passive warning visibility changed");
    }
    private static void writeReport(Path path, ProcessingReport report) throws IOException {
        Files.writeString(path, "stage\tfeature\taction\ttreatment\teffects\n" + report.issues().stream().map(issue -> issue.stage() + "\t" + issue.feature() + "\t" + issue.action() + "\t" + issue.treatment() + "\t" + String.join(",", issue.effects())).collect(Collectors.joining("\n")) + "\n");
    }
    @FunctionalInterface private interface Operation { void run() throws IOException; }
    private static void expectUnsupported(Operation operation) throws IOException {
        try { operation.run(); }
        catch (PdfOperationException expected) { require(expected.code() == PdfOperationException.Code.UNSUPPORTED_FEATURE, "unexpected passive rejection code"); return; }
        throw new IllegalStateException("unsupported passive operation was accepted");
    }
    private static void require(boolean condition, String message) { if (!condition) { throw new IllegalStateException(message); } }
}
