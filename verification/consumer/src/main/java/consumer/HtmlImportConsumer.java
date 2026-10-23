// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.web.HtmlAdapter;
import com.mirakyux.export4j.adapter.web.ExportPolicy;
import com.mirakyux.export4j.adapter.web.HtmlImporter;
import com.mirakyux.export4j.adapter.web.MarkdownAdapter;
import com.mirakyux.export4j.adapter.web.UnsupportedAction;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.model.edit.DocumentEditor;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class HtmlImportConsumer {
    private HtmlImportConsumer() { }
    static void verify(DocumentEngine engine, Path output) throws IOException {
        String source = "<h1>CALLER-HTML</h1><p id=p style='color:#123456'>TARGET-OLD <a href='https://example.org/html'>LINK-HTML</a></p>"
                + "<table><tr><td>CALLER-CELL</td><td>2</td></tr></table><blockquote><p>CALLER-QUOTE</p></blockquote>";
        var policy = ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DEGRADE).build();
        var prepared = HtmlImporter.builder().policy(policy).build().read(source).prepare(); var imported = prepared.execute();
        if (imported.report().losses().size() != 1 || !imported.report().warnings().isEmpty()
                || !prepared.preview().issues().equals(imported.report().issues())) { throw new IllegalStateException("isolated HTML loss report differs"); }
        var edited = DocumentEditor.edit(imported.document()).replaceText("TARGET-OLD", "TARGET-READY").execute().document();
        engine.export(edited).backend(new PdfBackend()).to(output.resolve("consumer-html-import.pdf"));
        try (var stream = Files.newOutputStream(output.resolve("consumer-html-import.docx"))) { new DocxAdapter().write(edited, stream); }
        new HtmlAdapter().export(edited).to(output.resolve("consumer-html-import.html"));
        MarkdownAdapter.builder().policy(ExportPolicy.builder().defaultAction(UnsupportedAction.WARN).build()).build()
                .export(edited).to(output.resolve("consumer-html-import.md"));
        var issue = imported.report().issues().getFirst();
        Files.writeString(output.resolve("consumer-html-import-losses.tsv"), issue.feature() + "\t" + issue.action() + "\t" + issue.treatment() + "\n");
    }
}
