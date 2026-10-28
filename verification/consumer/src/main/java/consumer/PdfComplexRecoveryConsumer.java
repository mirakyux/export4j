// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfOperationException;
import com.mirakyux.export4j.adapter.web.HtmlAdapter;
import com.mirakyux.export4j.adapter.web.MarkdownAdapter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import com.mirakyux.export4j.model.processing.ProcessingReport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Checks explicit whole-page raster recovery through the published coordinates. */
public final class PdfComplexRecoveryConsumer {
    private PdfComplexRecoveryConsumer() { }

    public static void main(String[] args) throws Exception {
        Path fixtures = Path.of("../../build/verification/pdf-complex-fixtures");
        Path output = Path.of(System.getProperty("consumer.outputDirectory")).resolve("pdf-complex-recovery");
        Files.createDirectories(output);
        byte[] font = Files.readAllBytes(Path.of("../../assets/fonts/LiberationSans-Regular.ttf"));
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(TrueTypeFontLoader.load(font)))).build();
        var adapter = new PdfAdapter(); int rejected = 0;
        for (String name : List.of("clip", "blend-alpha", "transparent-form", "soft-mask", "text-clip", "gs-font")) {
            Path source = fixtures.resolve(name + ".pdf");
            Files.copy(source, output.resolve(name + "-source.pdf"), StandardCopyOption.REPLACE_EXISTING);
            var input = adapter.input(source, "");
            boolean failed = false;
            try { adapter.importDocument(input).policy(policy(ProcessingPolicy.Action.ERROR)).prepare(); }
            catch (ProcessingException expected) { require(expected.issue().feature().equals("PDF_STRUCTURE"), "unexpected rejection"); failed = true; }
            require(failed, "complex structure bypassed ERROR"); rejected++;
            for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
                String label = name + "-" + action.name().toLowerCase(Locale.ROOT);
                var prepared = adapter.importDocument(input).rasterDpi(144).policy(policy(action)).prepare();
                var actual = prepared.execute();
                require(prepared.preview().issues().equals(actual.report().issues()), "recovery preview/actual differ");
                require((action == ProcessingPolicy.Action.IGNORE) == actual.report().warnings().isEmpty(), "incorrect warning action");
                var paragraph = (Paragraph) actual.document().sections().getFirst().blocks().getFirst();
                require(actual.document().sections().size() == 1 && paragraph.images().size() == 1
                        && paragraph.text().equals("\uFFFC"), "raster degradation fabricated editable source text");
                Files.write(output.resolve(label + ".png"), paragraph.images().getFirst().image().resource().bytes());
                writeReport(output.resolve(label + ".losses.tsv"), actual.report());
                for (var target : List.of(new DocxAdapter().conversionTarget(), new HtmlAdapter().conversionTarget(),
                        new MarkdownAdapter().conversionTarget(), engine.conversionTarget("PDF", new PdfBackend()))) {
                    var conversion = adapter.importDocument(input).rasterDpi(144).conversion().policy(policy(action)).prepare(target);
                    String extension = target.format().toLowerCase(Locale.ROOT).replace("markdown", "md");
                    Path destination = output.resolve(label + "." + extension);
                    var result = conversion.to(destination);
                    require(conversion.preview().issues().equals(result.report().issues()), "converted loss contract differs");
                }
            }
        }
        for (String name : List.of("invalid-alpha", "unknown-state", "missing-state")) {
            var input = adapter.input(fixtures.resolve(name + ".pdf"), "");
            Path destination = output.resolve(name + "-protected.pdf"); byte[] sentinel = {7, 2, 1}; Files.write(destination, sentinel);
            for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
                boolean failed = false;
                try { adapter.importDocument(input).conversion().policy(policy(action)).prepare(engine.conversionTarget("PDF", new PdfBackend())).to(destination); }
                catch (PdfOperationException expected) {
                    require(expected.code() == (name.equals("unknown-state") ? PdfOperationException.Code.UNSUPPORTED_FEATURE : PdfOperationException.Code.INVALID_PDF), "invalid paint state silently repaired"); failed = true;
                }
                require(failed && Arrays.equals(sentinel, Files.readAllBytes(destination)), "failed conversion replaced existing target"); rejected++;
            }
        }
        Files.writeString(output.resolve("summary.json"), "{\"sources\":6,\"conversions\":48,\"rejections\":" + rejected + "}\n");
        System.out.println("Complex PDF recovery: clipped, transparent and graphics-state pages become explicit-loss flow images");
    }

    private static ProcessingPolicy policy(ProcessingPolicy.Action action) {
        return ProcessingPolicy.builder().defaultRule(action == ProcessingPolicy.Action.ERROR ? ProcessingPolicy.Action.WARN : action, ProcessingPolicy.Treatment.DEGRADE)
                .feature("PDF_STRUCTURE", action, ProcessingPolicy.Treatment.DEGRADE).build();
    }

    private static void writeReport(Path path, ProcessingReport report) throws java.io.IOException {
        Files.writeString(path, "feature\taction\ttreatment\teffects\n" + report.issues().stream()
                .map(issue -> issue.feature() + "\t" + issue.action() + "\t" + issue.treatment() + "\t" + String.join(",", issue.effects()))
                .collect(Collectors.joining("\n")) + "\n");
    }

    private static void require(boolean value, String message) { if (!value) { throw new IllegalStateException(message); } }
}
