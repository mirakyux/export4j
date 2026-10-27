// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfFont;
import com.mirakyux.export4j.adapter.web.HtmlAdapter;
import com.mirakyux.export4j.adapter.web.MarkdownAdapter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Exercises externally generated CFF PDFs using only published Export4j APIs. */
public final class PdfFontCompatibilityConsumer {
    private PdfFontCompatibilityConsumer() { }
    public static void main(String[] args) throws Exception {
        Path fixtures = Path.of("../../build/verification/pdf-font-fixtures");
        Path output = Path.of(System.getProperty("consumer.outputDirectory")).resolve("pdf-fonts"); Files.createDirectories(output);
        byte[] fontBytes = Files.readAllBytes(Path.of("../../assets/fonts/LiberationSans-Regular.ttf"));
        var font = TrueTypeFontLoader.load(fontBytes);
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(font))).build(); var adapter = new PdfAdapter();
        for (String name : List.of("type1c", "opentype-cff", "cid-cff", "split-shows")) {
            Path source = fixtures.resolve(name + ".pdf"); Files.copy(source, output.resolve(name + "-source.pdf"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            var input = adapter.input(source, "");
            adapter.render(input.allPages()).dpi(144).prepare().images().getFirst().to(output.resolve(name + ".png"));
            String search = name.equals("cid-cff") ? "A" : "SECRET";
            var editing = ProcessingPolicy.builder().feature("TEXT_FONT", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
                    .feature("TEXT_SPACING", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE).build();
            var edited = adapter.editText(input).replace(0, search, "I", PdfFont.of(fontBytes)).policy(editing).prepare();
            var editedResult = edited.to(output.resolve(name + "-edited.pdf"));
            if (editedResult.changes() != 1 || !edited.preview().issues().equals(editedResult.report().issues())) { throw new IllegalStateException("CFF source match count or report mismatch"); }
            Files.writeString(output.resolve(name + "-edited.losses.tsv"), editedResult.report().issues().stream()
                    .map(issue -> issue.feature() + "\t" + issue.treatment()).collect(java.util.stream.Collectors.joining("\n")) + "\n");
            var redaction = ProcessingPolicy.builder().feature("RASTERIZATION", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE).build();
            adapter.redact(input).area(0, 18, 25, name.equals("cid-cff") ? 10 : 114, 20).policy(redaction).prepare().to(output.resolve(name + "-redacted.pdf"));
            boolean rejected = false;
            try { adapter.importDocument(input).prepare(); } catch (ProcessingException expected) { rejected = true; }
            if (!rejected) { throw new IllegalStateException("strict PDF inference must require explicit loss acceptance"); }
            for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
                var policy = ProcessingPolicy.builder().defaultRule(action, ProcessingPolicy.Treatment.DEGRADE).build();
                for (var target : List.of(new DocxAdapter().conversionTarget(), new HtmlAdapter().conversionTarget(),
                        new MarkdownAdapter().conversionTarget(), engine.conversionTarget("PDF", new PdfBackend()))) {
                    var prepared = adapter.importDocument(input).conversion().policy(policy).edit(editor -> editor.replaceText(search, "READY")).prepare(target);
                    Path path = output.resolve(name + "-" + action.name().toLowerCase(Locale.ROOT) + "." + target.format().toLowerCase(Locale.ROOT).replace("markdown", "md"));
                    var result = prepared.to(path);
                    if (!prepared.preview().issues().equals(result.report().issues()) || result.report().losses().isEmpty()
                            || (action == ProcessingPolicy.Action.IGNORE) != result.report().warnings().isEmpty()) {
                        throw new IllegalStateException("CFF conversion loss reconciliation failed");
                    }
                    Files.writeString(path.resolveSibling(path.getFileName() + ".losses.tsv"), result.report().issues().stream()
                            .map(issue -> issue.stage() + "\t" + issue.feature() + "\t" + issue.action() + "\t" + issue.treatment())
                            .collect(java.util.stream.Collectors.joining("\n")) + "\n");
                }
            }
        }
    }
}
