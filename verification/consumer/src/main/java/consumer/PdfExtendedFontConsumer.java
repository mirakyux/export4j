// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.pdf.PdfAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfFont;
import com.mirakyux.export4j.adapter.pdf.PdfLimits;
import com.mirakyux.export4j.adapter.pdf.PdfOperationException;
import com.mirakyux.export4j.model.Paragraph;
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

/** Exercises independent simple/CID/Type1/Type3 fixtures through published APIs. */
public final class PdfExtendedFontConsumer {
    private PdfExtendedFontConsumer() { }
    @FunctionalInterface private interface CheckedAction { void run() throws IOException; }

    public static void main(String[] args) throws Exception {
        Path fixtures = Path.of("../../build/verification/pdf-extended-font-fixtures");
        Path output = Path.of(System.getProperty("consumer.outputDirectory")).resolve("pdf-extended-fonts"); Files.createDirectories(output);
        try (var paths = Files.list(fixtures)) {
            for (var path : paths.filter(item -> item.toString().endsWith(".pdf")).toList()) {
                Files.copy(path, output.resolve(path.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        byte[] fontBytes = Files.readAllBytes(Path.of("../../assets/fonts/LiberationSans-Regular.ttf")); var font = PdfFont.of(fontBytes);
        var adapter = new PdfAdapter(); int rejected = 0;
        for (String name : List.of("differences", "cid-identity", "cid-custom")) {
            Path sourcePath = fixtures.resolve(name + ".pdf"); byte[] source = Files.readAllBytes(sourcePath); var input = adapter.input(source, "");
            String sourceName = name.equals("differences") ? "ExternalSimple" : "ExternalCID";
            expectFeature("RENDER_FONT_MAPPING", () -> adapter.render(input.allPages()).sourceFont(sourceName, font).prepare()); rejected++;
            expectFeature("RENDER_FONT", () -> adapter.render(input.allPages()).sourceFont("WrongExactName", font).policy(mapping(ProcessingPolicy.Action.WARN)).prepare()); rejected++;
            for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
                String label = name + "-" + action.name().toLowerCase(Locale.ROOT);
                var prepared = adapter.render(input.allPages()).sourceFont(sourceName, font).dpi(144).policy(mapping(action)).prepare();
                require(prepared.images().size() == 1, "explicit source mapping lost its page");
                prepared.images().getFirst().to(output.resolve(label + ".png"));
                var actual = prepared.toZip(output.resolve(label + ".zip")); report(prepared.preview(), actual.report(), action);
                require(actual.report().issues().size() == 1 && actual.report().issues().getFirst().feature().equals("RENDER_FONT_MAPPING"), "font mapping loss changed");
                losses(output.resolve(label + ".losses.tsv"), actual.report());
                var redacted = adapter.redact(input).sourceFont(sourceName, font).dpi(144).fill(0xFFFFFF).area(0, 0, 10, 240, 45).policy(redaction(action)).prepare();
                var result = redacted.to(output.resolve(label + "-redacted.pdf")); report(redacted.preview(), result.report(), action);
                losses(output.resolve(label + "-redacted.losses.tsv"), result.report());
            }
            require(Arrays.equals(source, Files.readAllBytes(sourcePath)), "mapping changed input bytes");
        }
        for (String name : List.of("type1", "type3")) {
            var input = adapter.input(fixtures.resolve(name + ".pdf"), "");
            var prepared = adapter.render(input.allPages()).dpi(144).prepare();
            require(prepared.preview().issues().isEmpty(), "embedded static font unexpectedly substituted");
            prepared.images().getFirst().to(output.resolve(name + ".png"));
            adapter.redact(input).dpi(144).fill(0xFFFFFF).area(0, 0, 10, 240, 45).policy(redaction(ProcessingPolicy.Action.WARN)).prepare()
                    .to(output.resolve(name + "-redacted.pdf"));
        }
        var sharedPath = fixtures.resolve("cid-shared.pdf"); byte[] sharedBytes = Files.readAllBytes(sharedPath); var shared = adapter.input(sharedBytes, "");
        expectFeature("RENDER_FONT_MAPPING", () -> adapter.render(shared.allPages()).sourceFont("ExternalCID", font).sourceFont("ExternalSecond", font).prepare()); rejected++;
        for (String only : List.of("ExternalCID", "ExternalSecond")) {
            expectFeature("RENDER_FONT", () -> adapter.render(shared.allPages()).sourceFont(only, font).policy(mapping(ProcessingPolicy.Action.WARN)).prepare()); rejected++;
        }
        for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
            String label = "cid-shared-" + action.name().toLowerCase(Locale.ROOT);
            var prepared = adapter.render(shared.allPages()).sourceFont("ExternalCID", font).sourceFont("ExternalSecond", font).dpi(144).policy(mapping(action)).prepare();
            prepared.images().getFirst().to(output.resolve(label + ".png")); var actual = prepared.toZip(output.resolve(label + ".zip")); report(prepared.preview(), actual.report(), action);
            require(actual.report().issues().size() == 2 && actual.report().issues().stream().allMatch(issue -> issue.feature().equals("RENDER_FONT_MAPPING")), "shared CID roots did not report each explicit mapping");
            losses(output.resolve(label + ".losses.tsv"), actual.report());
            var redacted = adapter.redact(shared).sourceFont("ExternalCID", font).sourceFont("ExternalSecond", font).dpi(144).fill(0xFFFFFF).area(0, 0, 10, 240, 45).policy(redaction(action)).prepare();
            var result = redacted.to(output.resolve(label + "-redacted.pdf")); report(redacted.preview(), result.report(), action); losses(output.resolve(label + "-redacted.losses.tsv"), result.report());
        }
        require(Arrays.equals(sharedBytes, Files.readAllBytes(sharedPath)), "mapping shared CID roots changed source bytes");
        var type3 = adapter.input(fixtures.resolve("type3.pdf"), "");
        for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
            var policy = ProcessingPolicy.builder().defaultRule(action, ProcessingPolicy.Treatment.DEGRADE).build();
            var prepared = adapter.importDocument(type3).rasterDpi(144).policy(policy).prepare(); var result = prepared.execute(); report(prepared.preview(), result.report(), action);
            require(result.document().sections().size() == 1 && result.document().sections().getFirst().blocks().size() == 1, "Type3 fallback did not preserve a whole page");
            var block = result.document().sections().getFirst().blocks().getFirst();
            require(block instanceof Paragraph paragraph && paragraph.images().size() == 1 && paragraph.text().equals("\uFFFC"), "Type3 fallback claimed editable Unicode text");
            var paragraph = (Paragraph) block;
            require(result.report().issues().stream().anyMatch(issue -> issue.feature().equals("PDF_STRUCTURE") && issue.treatment() == ProcessingPolicy.Treatment.DEGRADE), "Type3 raster recovery lacks its actual loss");
            String label = "type3-recovered-" + action.name().toLowerCase(Locale.ROOT);
            Files.write(output.resolve(label + ".png"), paragraph.images().getFirst().image().resource().bytes()); losses(output.resolve(label + ".losses.tsv"), result.report());
        }
        for (String name : List.of("malformed-differences", "malformed-cid-width", "missing-cid-glyph", "malformed-type1-length", "missing-type3-charproc", "malformed-type3-encoding", "malformed-cid-codespace", "malformed-cid-prefix")) {
            var input = adapter.input(fixtures.resolve(name + ".pdf"), "");
            for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
                Path destination = output.resolve(name + "-" + action.name().toLowerCase(Locale.ROOT) + "-protected.png"); byte[] sentinel = "old output".getBytes(StandardCharsets.UTF_8); Files.write(destination, sentinel);
                boolean failed = false;
                try {
                    adapter.render(input.allPages()).sourceFont("ExternalSimple", font).sourceFont("ExternalCID", font).policy(mapping(action)).prepare().images().getFirst().to(destination);
                } catch (PdfOperationException expected) {
                    require(expected.code() == PdfOperationException.Code.INVALID_PDF || name.equals("missing-cid-glyph") && expected.code() == PdfOperationException.Code.UNSUPPORTED_FEATURE, "invalid source reached an unrelated failure"); failed = true;
                }
                require(failed && Arrays.equals(sentinel, Files.readAllBytes(destination)), "malformed font bypassed policy or overwrote old output"); rejected++;
            }
        }
        var conflicting = adapter.input(fixtures.resolve("conflicting-cid-code-lengths.pdf"), "");
        expectFeature("RENDER_FONT", () -> adapter.render(conflicting.allPages()).sourceFont("ExternalCID", font).policy(mapping(ProcessingPolicy.Action.WARN)).prepare()); rejected++;
        for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
            var policy = ProcessingPolicy.builder().feature("RENDER_FONT", action, ProcessingPolicy.Treatment.SKIP_BLOCK).build();
            var skipped = adapter.render(conflicting.allPages()).sourceFont("ExternalCID", font).policy(policy).prepare();
            require(skipped.images().isEmpty() && skipped.preview().issues().size() == 1 && skipped.preview().issues().getFirst().feature().equals("RENDER_FONT"), "conflicting code lengths painted an ambiguous glyph");
            require((action == ProcessingPolicy.Action.IGNORE) == skipped.preview().warnings().isEmpty(), "conflicting CMap warning presentation changed");
            losses(output.resolve("conflicting-cid-" + action.name().toLowerCase(Locale.ROOT) + ".losses.tsv"), skipped.preview());
        }
        byte[] composite = Files.readAllBytes(fixtures.resolve("cid-identity.pdf"));
        for (var limits : List.of(PdfLimits.DEFAULT.toBuilder().maxTotalInputBytes(composite.length + fontBytes.length - 1L).build(),
                PdfLimits.DEFAULT.toBuilder().maxContentOperators(1000).build())) {
            var bounded = PdfAdapter.builder().limits(limits).build(); boolean failed = false;
            try { bounded.render(bounded.input(composite, "").allPages()).sourceFont("ExternalCID", font).policy(mapping(ProcessingPolicy.Action.IGNORE)).prepare(); }
            catch (PdfOperationException expected) { require(expected.code() == (limits.maxContentOperators() == 1000 ? PdfOperationException.Code.OPERATOR_LIMIT : PdfOperationException.Code.INPUT_LIMIT), "font budget failure changed"); failed = true; }
            require(failed, "font source or CMap work budget was bypassed"); rejected++;
        }
        var glyphBounded = PdfAdapter.builder().limits(PdfLimits.DEFAULT.toBuilder().maxTextCharacters(1).build()).build(); boolean failed = false;
        try { glyphBounded.render(glyphBounded.input(fixtures.resolve("type3.pdf"), "").allPages()).prepare(); }
        catch (PdfOperationException expected) { require(expected.code() == PdfOperationException.Code.TEXT_LIMIT, "Type3 glyph budget failure changed"); failed = true; }
        require(failed, "Type3 rendering did not charge glyph work"); rejected++;
        Files.writeString(output.resolve("summary.json"), "{\"mappedSources\":4,\"mappedImages\":8,\"embeddedImages\":2,\"redactions\":10,\"rasterRecoveries\":2,\"skippedCmapPages\":2,\"rejections\":" + rejected + "}\n");
        System.out.println("Extended PDF fonts: source encodings, CID metrics, static programs, loss actions and bounded failures verified");
    }
    private static ProcessingPolicy mapping(ProcessingPolicy.Action action) { return ProcessingPolicy.builder().feature("RENDER_FONT_MAPPING", action, ProcessingPolicy.Treatment.DEGRADE).build(); }
    private static ProcessingPolicy redaction(ProcessingPolicy.Action action) { return ProcessingPolicy.builder().feature("RENDER_FONT_MAPPING", action, ProcessingPolicy.Treatment.DEGRADE).feature("RASTERIZATION", action, ProcessingPolicy.Treatment.DEGRADE).build(); }
    private static void report(ProcessingReport preview, ProcessingReport actual, ProcessingPolicy.Action action) {
        require(preview.phase() == ProcessingReport.Phase.PREVIEW && actual.phase() == ProcessingReport.Phase.ACTUAL && preview.issues().equals(actual.issues()), "font preview/actual losses differ");
        require(!actual.issues().isEmpty() && actual.issues().stream().allMatch(issue -> issue.action() == action), "font action or actual loss missing");
        require((action == ProcessingPolicy.Action.IGNORE) == actual.warnings().isEmpty(), "font warning presentation changed");
    }
    private static void losses(Path file, ProcessingReport report) throws IOException {
        Files.writeString(file, "stage\tfeature\taction\ttreatment\teffects\n" + report.issues().stream().map(issue -> issue.stage() + "\t" + issue.feature() + "\t" + issue.action() + "\t" + issue.treatment() + "\t" + String.join(",", issue.effects())).collect(Collectors.joining("\n")) + "\n");
    }
    private static void expectFeature(String feature, CheckedAction action) throws IOException {
        try { action.run(); } catch (ProcessingException expected) { require(expected.issue().feature().equals(feature), "wrong font rejection feature"); return; }
        throw new IllegalStateException("missing strict font mapping rejection");
    }
    private static void require(boolean condition, String message) { if (!condition) { throw new IllegalStateException(message); } }
}
