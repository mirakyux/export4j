// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.web.ExportPolicy;
import com.mirakyux.export4j.adapter.web.HtmlAdapter;
import com.mirakyux.export4j.adapter.web.MarkdownAdapter;
import com.mirakyux.export4j.adapter.web.MarkdownImporter;
import com.mirakyux.export4j.adapter.web.UnsupportedAction;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.model.edit.DocumentEditor;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class MarkdownImportConsumer {
    private MarkdownImportConsumer() { }
    static void verify(DocumentEngine engine, Path output) throws IOException {
        String source = "# CALLER-IMPORT\n\nTARGET-OLD [LINK-ONLY](https://example.org/import)\n\n| A | B |\n|---|---|\n|CALLER-CELL|2|\n\n> CALLER-QUOTE\n";
        var policy = ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DEGRADE).build();
        var prepared = MarkdownImporter.builder().policy(policy).build().read(source).prepare();
        var imported = prepared.execute();
        if (imported.report().losses().size() != 1 || !imported.report().warnings().isEmpty()
                || !prepared.preview().issues().equals(imported.report().issues())) {
            throw new IllegalStateException("isolated Markdown import reports differ");
        }
        var edited = DocumentEditor.edit(imported.document()).replaceText("TARGET-OLD", "TARGET-READY").execute().document();
        engine.export(edited).backend(new PdfBackend()).to(output.resolve("consumer-markdown-import.pdf"));
        try (var stream = Files.newOutputStream(output.resolve("consumer-markdown-import.docx"))) { new DocxAdapter().write(edited, stream); }
        new HtmlAdapter().export(edited).to(output.resolve("consumer-markdown-import.html"));
        MarkdownAdapter.builder().policy(ExportPolicy.builder().defaultAction(UnsupportedAction.WARN).build()).build()
                .export(edited).to(output.resolve("consumer-markdown-import.md"));
        var issue = imported.report().issues().getFirst();
        Files.writeString(output.resolve("consumer-markdown-import-losses.tsv"), issue.feature() + "\t" + issue.action() + "\t" + issue.treatment() + "\n");
    }
}
