// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfOperationException;
import com.mirakyux.export4j.adapter.pdf.PreparedPdfImport;
import com.mirakyux.export4j.adapter.web.HtmlAdapter;
import com.mirakyux.export4j.adapter.web.MarkdownAdapter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.model.Block;
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
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Exercises ordinary Form recovery and protected failures through the published Maven API. */
public final class PdfFormImportConsumer {
    private PdfFormImportConsumer() { }

    public static void main(String[] args) throws Exception {
        Path fixtures = Path.of("../../build/verification/pdf-form-fixtures");
        Path output = Path.of(System.getProperty("consumer.outputDirectory")).resolve("pdf-form-import");
        Files.createDirectories(output);
        byte[] font = Files.readAllBytes(Path.of("../../assets/fonts/LiberationSans-Regular.ttf"));
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(TrueTypeFontLoader.load(font)))).build();
        var adapter = new PdfAdapter();
        int rejections = 0;
        for (String name : List.of("form-matrix", "form-nested", "form-inherited", "form-unused", "form-hidden")) {
            Path source = fixtures.resolve(name + ".pdf");
            byte[] original = Files.readAllBytes(source);
            Files.copy(source, output.resolve(name + "-source.pdf"), StandardCopyOption.REPLACE_EXISTING);
            var input = adapter.input(source, "");
            expectFeature("PDF_LAYOUT", () -> adapter.importDocument(input).prepare());
            rejections++;
            for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
                String label = name + "-" + action.name().toLowerCase(Locale.ROOT);
                var prepared = adapter.importDocument(input).policy(policy(action, false)).prepare();
                var result = prepared.execute();
                report(prepared.preview(), result.report(), action);
                require(text(result.document()).equals(expected(name)), "Form model lost, duplicated or reordered text: " + name);
                require(result.report().issues().stream().noneMatch(issue -> issue.feature().equals("PDF_STRUCTURE")), "accepted Form page was skipped");
                require(result.document().sections().size() == 1, "accepted Form page identity changed");
                writeModel(output, label, result);
                writeReport(output.resolve(label + ".losses.tsv"), result.report());
                if (name.equals("form-hidden")) {
                    require(result.report().issues().stream().anyMatch(issue -> issue.feature().equals("PDF_HIDDEN_TEXT")
                            && issue.treatment() == ProcessingPolicy.Treatment.DISCARD), "hidden Form text lacks discard diagnostic");
                    require(result.report().issues().stream().anyMatch(issue -> issue.feature().equals("PDF_IMAGE_TRANSFORM")
                            && issue.treatment() == ProcessingPolicy.Treatment.SKIP_MATCH), "hidden Form image lacks skip diagnostic");
                }
                for (var target : List.of(new DocxAdapter().conversionTarget(), new HtmlAdapter().conversionTarget(),
                        new MarkdownAdapter().conversionTarget(), engine.conversionTarget("PDF", new PdfBackend()))) {
                    var conversion = adapter.importDocument(input).conversion().policy(policy(action, false))
                            .edit(editor -> editor.replaceText("BODY", "READY")).prepare(target);
                    String extension = target.format().toLowerCase(Locale.ROOT).replace("markdown", "md");
                    Path destination = output.resolve(label + "." + extension);
                    var written = conversion.to(destination);
                    report(conversion.preview(), written.report(), action);
                    writeReport(destination.resolveSibling(destination.getFileName() + ".losses.tsv"), written.report());
                }
            }
            require(Arrays.equals(original, Files.readAllBytes(source)), "Form import changed source bytes");
        }
        for (String kind : List.of("partial", "transparent", "clip")) {
            String name = "form-" + kind;
            var input = adapter.input(fixtures.resolve(name + ".pdf"), "");
            expectFeature("PDF_STRUCTURE", () -> adapter.importDocument(input).policy(policy(ProcessingPolicy.Action.WARN, false)).prepare());
            rejections++;
            for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
                String label = name + "-" + action.name().toLowerCase(Locale.ROOT);
                var prepared = adapter.importDocument(input).policy(policy(action, true)).prepare();
                var result = prepared.execute();
                report(prepared.preview(), result.report(), action);
                require(text(result.document()).equals("SAFE PAGE"), "unsupported Form page leaked partial or hidden content");
                require(result.sources().size() == 1 && result.sources().getFirst().page() == 0, "skipped Form retained source maps");
                require(result.report().issues().stream().filter(issue -> issue.feature().equals("PDF_STRUCTURE")
                        && issue.treatment() == ProcessingPolicy.Treatment.SKIP_BLOCK).count() == 1, "Form page skip lacks exact loss");
                writeModel(output, label, result);
                writeReport(output.resolve(label + ".losses.tsv"), result.report());
            }
        }
        for (String kind : List.of("bad-matrix", "missing-do", "late-invalid")) {
            var input = adapter.input(fixtures.resolve("form-" + kind + ".pdf"), "");
            Path destination = output.resolve("form-" + kind + "-protected.pdf");
            byte[] sentinel = "existing destination".getBytes(StandardCharsets.UTF_8);
            Files.write(destination, sentinel);
            for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
                boolean rejected = false;
                try {
                    adapter.importDocument(input).conversion().policy(policy(action, true))
                            .prepare(engine.conversionTarget("PDF", new PdfBackend())).to(destination);
                } catch (PdfOperationException expected) {
                    require(expected.code() == PdfOperationException.Code.INVALID_PDF, "invalid Form did not fail as malformed input");
                    rejected = true;
                }
                require(rejected && Arrays.equals(sentinel, Files.readAllBytes(destination)), "invalid Form bypassed policy or changed old target");
                rejections++;
            }
        }
        Files.writeString(output.resolve("summary.json"), "{\"acceptedSources\":5,\"conversions\":40,\"skips\":6,\"rejections\":" + rejections + "}\n");
        System.out.println("PDF Form import: matrices, scoped resources, repeated instances, clipping losses and protected failures verified");
    }

    private static ProcessingPolicy policy(ProcessingPolicy.Action action, boolean skipStructure) {
        return ProcessingPolicy.builder().defaultRule(action, ProcessingPolicy.Treatment.DEGRADE)
                .feature("PDF_STRUCTURE", skipStructure ? action : ProcessingPolicy.Action.ERROR, ProcessingPolicy.Treatment.SKIP_BLOCK)
                .feature("PDF_HIDDEN_TEXT", action, ProcessingPolicy.Treatment.DISCARD)
                .feature("PDF_IMAGE_TRANSFORM", action, ProcessingPolicy.Treatment.SKIP_MATCH).build();
    }

    private static String expected(String name) {
        return switch (name) {
            case "form-matrix" -> "PAGE BEFORE\nFORM BODY\nPAGE AFTER";
            case "form-nested" -> "PAGE BEFORE\nNESTED ITEM\nNESTED ITEM\nPAGE AFTER";
            case "form-inherited" -> "PAGE BEFORE\nINHERITED BODY\nPAGE AFTER";
            case "form-unused" -> "PAGE BEFORE\nPAGE AFTER";
            case "form-hidden" -> "PAGE BEFORE\nVISIBLE BODY\nPAGE AFTER";
            default -> throw new IllegalArgumentException("unknown original fixture");
        };
    }

    private static Paragraph paragraph(Block block) {
        if (block instanceof Paragraph value) { return value; }
        if (block instanceof Heading value) { return value.paragraph(); }
        throw new IllegalStateException("unexpected recovered block");
    }

    private static String text(SectionedDocument document) {
        return document.sections().stream().flatMap(section -> section.blocks().stream()).map(PdfFormImportConsumer::paragraph)
                .map(Paragraph::text).map(value -> value.replace("\ufffc", "")).filter(value -> !value.isEmpty()).collect(Collectors.joining("\n"));
    }

    private static void writeModel(Path output, String label, PreparedPdfImport.Result result) throws IOException {
        Map<String, Block> blocks = result.document().sections().stream().flatMap(section -> section.blocks().stream())
                .collect(Collectors.toMap(Block::id, Function.identity()));
        var rows = new StringBuilder("text\tpage\tx\ty\twidth\theight\tsize\tcolor\n");
        int imageIndex = 0;
        for (var source : result.sources()) {
            var paragraph = paragraph(blocks.get(source.blockId()));
            boolean image = !paragraph.images().isEmpty();
            var style = paragraph.inlines().getFirst().style();
            rows.append(image ? "[IMAGE]" : paragraph.text()).append('\t').append(source.page()).append('\t').append(source.x()).append('\t')
                    .append(source.y()).append('\t').append(source.width()).append('\t').append(source.height()).append('\t')
                    .append(style.fontSize()).append('\t').append(String.format(Locale.ROOT, "%06X", style.rgb())).append('\n');
            for (var span : paragraph.images()) {
                Files.write(output.resolve(label + "-image-" + (++imageIndex) + ".png"), span.image().resource().bytes());
            }
        }
        Files.writeString(output.resolve(label + ".model.tsv"), rows);
        Files.writeString(output.resolve(label + ".text.txt"), text(result.document()) + "\n");
    }

    private static void report(ProcessingReport preview, ProcessingReport actual, ProcessingPolicy.Action action) {
        require(preview.phase() == ProcessingReport.Phase.PREVIEW && actual.phase() == ProcessingReport.Phase.ACTUAL
                && preview.issues().equals(actual.issues()) && !actual.issues().isEmpty(), "preview/actual Form losses differ");
        require(actual.issues().stream().allMatch(issue -> issue.action() == action), "Form loss action changed");
        require((action == ProcessingPolicy.Action.IGNORE) == actual.warnings().isEmpty(), "Form warnings differ from selected action");
    }

    private static void writeReport(Path path, ProcessingReport report) throws IOException {
        Files.writeString(path, "stage\tfeature\taction\ttreatment\teffects\n" + report.issues().stream()
                .map(issue -> issue.stage() + "\t" + issue.feature() + "\t" + issue.action() + "\t" + issue.treatment() + "\t" + String.join(",", issue.effects()))
                .collect(Collectors.joining("\n")) + "\n");
    }

    @FunctionalInterface private interface CheckedAction { void run() throws IOException; }

    private static void expectFeature(String feature, CheckedAction operation) throws IOException {
        try { operation.run(); }
        catch (ProcessingException expected) { require(expected.issue().feature().equals(feature), "unexpected Form rejection feature"); return; }
        throw new IllegalStateException("expected Form rejection");
    }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message); }
    }
}
