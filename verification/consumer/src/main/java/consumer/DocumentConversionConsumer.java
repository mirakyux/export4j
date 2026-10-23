// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.web.HtmlAdapter;
import com.mirakyux.export4j.adapter.web.HtmlImporter;
import com.mirakyux.export4j.adapter.web.MarkdownAdapter;
import com.mirakyux.export4j.adapter.web.MarkdownImporter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

final class DocumentConversionConsumer {
    private DocumentConversionConsumer() { }
    static void verify(DocumentEngine engine, Path output) throws IOException {
        String source = "<h1>CALLER-CONVERSION</h1><p id=edited>OLD</p><p id=untouched style='color:#123456'>UNCHANGED-COLOR</p>"
                + "<blockquote><p>QUOTE-CONVERSION</p></blockquote><p><a href='https://example.org/conversion'>LINK-CONVERSION</a></p>";
        var policy = ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DEGRADE).build();
        var docx = new DocxAdapter();
        for (var target : List.of(engine.conversionTarget("PDF", new PdfBackend()), docx.conversionTarget(), new HtmlAdapter().conversionTarget(), new MarkdownAdapter().conversionTarget())) {
            var prepared = new HtmlImporter().conversion(source).policy(policy).edit(editor -> editor.replaceText("OLD", "CALLER-READY")).prepare(target);
            Path file = output.resolve("consumer-conversion." + target.format().toLowerCase(Locale.ROOT).replace("markdown", "md"));
            var result = prepared.to(file);
            if (!result.report().warnings().isEmpty() || !prepared.preview().issues().equals(result.report().issues()) || result.bytesWritten() != Files.size(file)) {
                throw new IllegalStateException("isolated conversion reconciliation failed");
            }
            Files.writeString(file.resolveSibling(file.getFileName() + ".losses.tsv"), result.report().losses().stream()
                    .map(issue -> issue.stage() + "\t" + issue.feature() + "\t" + issue.action()).collect(java.util.stream.Collectors.joining("\n")) + "\n");
        }
        docx.conversion(Files.readAllBytes(output.resolve("consumer-conversion.docx"))).edit(editor -> editor.replaceText("CALLER-READY", "DOCX-CALLER-READY"))
                .prepare(engine.conversionTarget("PDF", new PdfBackend())).to(output.resolve("consumer-conversion-docx.pdf"));
        new MarkdownImporter().conversion("# CALLER-MARKDOWN\n\nOLD").edit(editor -> editor.replaceText("OLD", "MD-CALLER-READY"))
                .prepare(new HtmlAdapter().conversionTarget()).to(output.resolve("consumer-conversion-markdown.html"));
    }
}
