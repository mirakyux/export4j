// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.pdf.PdfAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfFont;
import com.mirakyux.export4j.adapter.pdf.PdfOperationException;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import com.mirakyux.export4j.model.processing.ProcessingReport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Exercises shared Form editing using only the published Export4j API. */
public final class PdfFormEditConsumer {
    private PdfFormEditConsumer() { }

    public static void main(String[] args) throws Exception {
        Path fixtures = Path.of("../../build/verification/pdf-form-edit-fixtures");
        Path output = Path.of(System.getProperty("consumer.outputDirectory")).resolve("pdf-form-edit");
        Files.createDirectories(output);
        var adapter = new PdfAdapter();
        var font = PdfFont.of(Files.readAllBytes(Path.of("../../assets/fonts/LiberationSans-Regular.ttf")));
        byte[] sentinel = "existing destination".getBytes(StandardCharsets.UTF_8);
        int rejections = 0, outputs = 0, changes = 0;
        for (String name : List.of("shared", "nested", "inherited", "shadow", "spacing", "transparent", "marked")) {
            Path source = fixtures.resolve(name + ".pdf"); byte[] original = Files.readAllBytes(source);
            Files.copy(source, output.resolve(name + "-source.pdf"), StandardCopyOption.REPLACE_EXISTING);
            var input = adapter.input(original, "");
            Path protectedOutput = output.resolve(name + "-protected.pdf"); Files.write(protectedOutput, sentinel);
            boolean rejected = false;
            try { adapter.editText(input).replace(0, "SECRET", "HI", font).prepare().to(protectedOutput); }
            catch (ProcessingException expected) {
                String feature = switch (name) { case "spacing" -> "TEXT_SHAPING"; case "transparent" -> "TEXT_STRUCTURE"; case "marked" -> "MARKED_TEXT"; default -> "TEXT_FONT"; };
                require(expected.issue().feature().equals(feature), "strict Form edit rejected the wrong feature"); rejected = true;
            }
            require(rejected && Arrays.equals(sentinel, Files.readAllBytes(protectedOutput)), "strict Form edit changed its target"); rejections++;
            int expectedChanges = switch (name) { case "spacing" -> 1; case "transparent", "marked" -> 0; default -> 2; };
            for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
                var prepared = adapter.editText(input).replace(0, "SECRET", "HI", font).policy(policy(action)).prepare();
                String label = name + "-" + action.name().toLowerCase(Locale.ROOT);
                Path destination = output.resolve(label + ".pdf"); var actual = prepared.to(destination);
                require(actual.changes() == expectedChanges, "Form result did not count actual invocation matches");
                require(prepared.preview().phase() == ProcessingReport.Phase.PREVIEW && actual.report().phase() == ProcessingReport.Phase.ACTUAL
                        && prepared.preview().issues().equals(actual.report().issues()), "Form preview/actual report mismatch");
                require(!actual.report().issues().isEmpty() && actual.report().issues().stream().allMatch(issue -> issue.action() == action), "Form loss action disappeared");
                require((action == ProcessingPolicy.Action.IGNORE) == actual.report().warnings().isEmpty(), "Form warning visibility changed");
                writeReport(destination.resolveSibling(destination.getFileName() + ".losses.tsv"), actual.report());
                var read = adapter.read(adapter.input(destination, ""));
                require(read.pages().size() == 2 && read.pages().get(1).text().contains("SECRET KEEP"), "Form edit changed an unselected page");
                int expectedSecrets = name.equals("spacing") ? 1 : expectedChanges == 0 ? 2 : 0;
                require(occurrences(read.pages().getFirst().text(), "SECRET") == expectedSecrets, "Form selected-page content differs");
                changes += actual.changes(); outputs++;
            }
            require(Arrays.equals(original, Files.readAllBytes(source)), "Form editing modified its source file");
        }
        var chained = adapter.editText(adapter.input(fixtures.resolve("shared.pdf"), "")).replace(0, "SECRET", "PUBLIC", font)
                .replace(0, "PUBLIC", "HI", font).policy(policy(ProcessingPolicy.Action.WARN)).prepare();
        var chainedResult = chained.to(output.resolve("shared-chained.pdf"));
        require(chainedResult.changes() == 4 && chained.preview().issues().equals(chainedResult.report().issues()), "chained Form edits lost embedded fonts or changes");
        writeReport(output.resolve("shared-chained.pdf.losses.tsv"), chainedResult.report()); changes += chainedResult.changes(); outputs++;
        for (String name : List.of("malformed-matrix", "malformed-resource")) {
            Path destination = output.resolve(name + "-protected.pdf"); Files.write(destination, sentinel);
            for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
                boolean failed = false;
                try { adapter.editText(adapter.input(fixtures.resolve(name + ".pdf"), "")).replace(0, "SECRET", "HI", font).policy(policy(action)).prepare().to(destination); }
                catch (PdfOperationException expected) { require(expected.code() == PdfOperationException.Code.INVALID_PDF, "malformed Form edit did not hard-fail"); failed = true; }
                require(failed && Arrays.equals(sentinel, Files.readAllBytes(destination)), "malformed Form changed its old target"); rejections++;
            }
        }
        Files.writeString(output.resolve("summary.json"), "{\"sources\":7,\"outputs\":" + outputs + ",\"changes\":" + changes + ",\"rejections\":" + rejections + "}\n");
        System.out.println("PDF Form editing: shared-instance isolation, nested resource scope, state, three actions and protected failures verified");
    }

    private static ProcessingPolicy policy(ProcessingPolicy.Action action) {
        return ProcessingPolicy.builder().feature("TEXT_FONT", action, ProcessingPolicy.Treatment.DEGRADE)
                .feature("TEXT_SHAPING", action, ProcessingPolicy.Treatment.SKIP_MATCH)
                .feature("TEXT_STRUCTURE", action, ProcessingPolicy.Treatment.SKIP_BLOCK)
                .feature("MARKED_TEXT", action, ProcessingPolicy.Treatment.SKIP_MATCH).build();
    }
    private static int occurrences(String text, String value) { return (text.length() - text.replace(value, "").length()) / value.length(); }
    private static void writeReport(Path path, ProcessingReport report) throws Exception {
        Files.writeString(path, "feature\taction\ttreatment\teffects\n" + report.issues().stream()
                .map(issue -> issue.feature() + "\t" + issue.action() + "\t" + issue.treatment() + "\t" + String.join(",", issue.effects()))
                .collect(Collectors.joining("\n")) + "\n");
    }
    private static void require(boolean condition, String message) { if (!condition) { throw new IllegalStateException(message); } }
}
