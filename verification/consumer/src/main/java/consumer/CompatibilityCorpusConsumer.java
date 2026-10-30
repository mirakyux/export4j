// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfAdapter;
import com.mirakyux.export4j.adapter.template.XmlTemplate;
import com.mirakyux.export4j.adapter.web.HtmlAdapter;
import com.mirakyux.export4j.adapter.web.HtmlImporter;
import com.mirakyux.export4j.adapter.web.MarkdownAdapter;
import com.mirakyux.export4j.adapter.web.MarkdownImporter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.processing.DocumentConversion;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fixed cross-format and failure corpus using only the independently published coordinates. */
public final class CompatibilityCorpusConsumer {
    private static final String HTML = "<p>CORPUS-ORIGINAL</p><p style='color:#123456'>CORPUS-KEEP</p>";
    private static final String MARKDOWN = "CORPUS-ORIGINAL\n\nCORPUS-KEEP\n";
    private CompatibilityCorpusConsumer() { }
    @FunctionalInterface private interface Operation { void run() throws IOException; }
    private static void require(boolean value, String reason) { if (!value) { throw new IllegalStateException(reason); } }
    private static void fails(Class<? extends Exception> expected, Operation operation) throws IOException {
        try { operation.run(); }
        catch (IOException | RuntimeException failure) {
            if (expected.isInstance(failure)) { return; }
            throw failure;
        }
        throw new IllegalStateException("Expected rejection: " + expected.getSimpleName());
    }
    private static ProcessingPolicy policy(ProcessingPolicy.Action action) {
        return ProcessingPolicy.builder().defaultRule(action, ProcessingPolicy.Treatment.DEGRADE)
                .feature("METADATA", action, ProcessingPolicy.Treatment.DISCARD)
                .feature("DOCUMENT_METADATA", action, ProcessingPolicy.Treatment.DISCARD).build();
    }
    private static DocumentConversion.Request source(String format, byte[] docx, byte[] pdf) throws IOException {
        return switch (format) {
            case "HTML" -> new HtmlImporter().conversion(HTML);
            case "MARKDOWN" -> new MarkdownImporter().conversion(MARKDOWN);
            case "DOCX" -> new DocxAdapter().conversion(docx);
            case "PDF" -> { var adapter = new PdfAdapter(); yield adapter.importDocument(adapter.input(pdf, "")).conversion(); }
            default -> throw new IllegalArgumentException("unknown corpus format");
        };
    }
    public static void main(String[] args) throws Exception {
        Path fixtures = Path.of(System.getProperty("consumer.compatibilityInputs", "../../build/verification/compatibility-fixtures"));
        Path output = Path.of(System.getProperty("consumer.outputDirectory", "build/output")).resolve("compatibility-corpus");
        Files.createDirectories(output);
        var font = TrueTypeFontLoader.load(Files.readAllBytes(Path.of("../../assets/fonts/LiberationSans-Regular.ttf")));
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(font))).build();
        Path seed = output.resolve("source.pdf");
        engine.export(new HtmlImporter().read(HTML).execute().document()).backend(new PdfBackend()).to(seed);
        byte[] pdf = Files.readAllBytes(seed), docx = Files.readAllBytes(fixtures.resolve("word-deflated.docx"));
        var targets = List.of(new HtmlAdapter().conversionTarget(), new MarkdownAdapter().conversionTarget(),
                new DocxAdapter().conversionTarget(), engine.conversionTarget("PDF", new PdfBackend()));
        var index = new ArrayList<String>(); index.add("source\ttarget\taction\tfile\tlosses\twarnings");
        for (String from : List.of("HTML", "MARKDOWN", "DOCX", "PDF")) {
            for (var target : targets) {
                for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
                    var prepared = source(from, docx, pdf).policy(policy(action)).edit(editor -> editor.replaceText("CORPUS-ORIGINAL", "CORPUS-EDITED")).prepare(target);
                    String extension = target.format().equals("MARKDOWN") ? "md" : target.format().toLowerCase(Locale.ROOT);
                    String name = from.toLowerCase(Locale.ROOT) + "-to-" + target.format().toLowerCase(Locale.ROOT) + "-" + action.name().toLowerCase(Locale.ROOT) + "." + extension;
                    var result = prepared.to(output.resolve(name));
                    require(prepared.preview().issues().equals(result.report().issues()), "preview differs from actual losses");
                    require(Files.size(output.resolve(name)) == result.bytesWritten(), "encoded size differs from actual file");
                    require(action != ProcessingPolicy.Action.IGNORE || result.report().warnings().isEmpty(), "IGNORE emitted warnings");
                    var losses = new ArrayList<String>(); losses.add("stage\tfeature\taction\ttreatment\toperation\tblock\teffects");
                    for (var issue : result.report().issues()) {
                        losses.add(String.join("\t", issue.stage(), issue.feature(), issue.action().name(), issue.treatment().name(),
                                issue.operation(), issue.location().blockId(), String.join(",", issue.effects())));
                    }
                    Files.write(output.resolve(name + ".losses.tsv"), losses, StandardCharsets.UTF_8);
                    index.add(String.join("\t", from, target.format(), action.name(), name,
                            Integer.toString(result.report().losses().size()), Integer.toString(result.report().warnings().size())));
                }
            }
        }
        var guards = new ArrayList<String>(); guards.add("case\tchecks");
        var adapter = new DocxAdapter();
        for (String name : List.of("word-deflated.docx", "word-stored.docx")) {
            var read = adapter.readSemantic(new ByteArrayInputStream(Files.readAllBytes(fixtures.resolve(name))), DocxAdapter.Policy.STRICT);
            require(read.diagnostics().isEmpty(), "standard external OOXML produced unexpected loss"); guards.add(name + "\t1");
        }
        try (var paths = Files.list(fixtures)) {
            for (Path invalid : paths.filter(path -> path.getFileName().toString().startsWith("bad-")).sorted().toList()) {
                byte[] bytes = Files.readAllBytes(invalid);
                for (var action : DocxAdapter.Policy.values()) {
                    fails(IOException.class, () -> adapter.read(new ByteArrayInputStream(bytes), action));
                    fails(IOException.class, () -> adapter.readSemantic(new ByteArrayInputStream(bytes), action));
                }
                for (var action : List.of(ProcessingPolicy.Action.ERROR, ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
                    fails(IOException.class, () -> adapter.conversion(bytes).policy(policy(action)).prepare(adapter.conversionTarget()));
                }
                Path target = output.resolve("protected.docx"); byte[] old = {1, 2, 3}; Files.write(target, old);
                fails(IOException.class, () -> adapter.editPackage(bytes).replaceText("CORPUS-ORIGINAL", "CHANGED").prepare().to(target));
                require(Arrays.equals(old, Files.readAllBytes(target)), "invalid package replaced old target");
                guards.add(invalid.getFileName() + "\t8");
            }
        }
        Path protectedOutput = output.resolve("protected.md"); byte[] sentinel = {4, 5, 6}; Files.write(protectedOutput, sentinel);
        fails(ProcessingException.class, () -> new HtmlImporter().conversion(HTML).prepare(new MarkdownAdapter().conversionTarget()).to(protectedOutput));
        require(Arrays.equals(sentinel, Files.readAllBytes(protectedOutput)), "strict loss replaced old output");
        guards.add("strict-loss-protects-target\t1");
        fails(IllegalArgumentException.class, () -> new MarkdownImporter().conversion(MARKDOWN)
                .limits(new DocumentConversion.Limits(1, 10, 1)).prepare(new HtmlAdapter().conversionTarget()));
        guards.add("output-byte-budget\t1");
        var cancelled = new AtomicBoolean();
        var prepared = new MarkdownImporter().conversion(MARKDOWN).execution(ExecutionOptions.builder().cancelled(cancelled::get).build()).prepare(new HtmlAdapter().conversionTarget());
        cancelled.set(true);
        fails(ExecutionException.class, () -> prepared.to(protectedOutput));
        require(Arrays.equals(sentinel, Files.readAllBytes(protectedOutput)), "cancelled output replaced old target");
        guards.add("cancelled-output-protects-target\t1");
        for (var action : ProcessingPolicy.Action.values()) {
            fails(IllegalArgumentException.class, () -> HtmlImporter.builder().policy(policy(action)).build()
                    .read("<p style='background-image:url(https://example.invalid/image)'>TEXT</p>").execute());
        }
        guards.add("offline-css-under-all-actions\t3");
        fails(IllegalArgumentException.class, () -> XmlTemplate.builder().build().compile("<!DOCTYPE document [<!ENTITY value SYSTEM 'https://example.invalid/entity'>]><document version='1'><p>&value;</p></document>"));
        guards.add("xml-external-entity\t1");
        try (var files = Files.list(output)) { require(files.noneMatch(path -> path.getFileName().toString().startsWith(".export4j-")), "temporary files leaked"); }
        Files.write(output.resolve("cases.tsv"), index, StandardCharsets.UTF_8);
        Files.write(output.resolve("guards.tsv"), guards, StandardCharsets.UTF_8);
        System.out.println("Compatibility corpus: " + (index.size() - 1) + " outputs; " + (guards.size() - 1) + " guard groups");
    }
}
