// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.web.HtmlAdapter;
import com.mirakyux.export4j.adapter.web.MarkdownAdapter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.edit.DocumentEditor;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class SemanticEditingConsumer {
    private SemanticEditingConsumer() { }
    static void verify(DocumentEngine engine, Path output) throws IOException {
        var normal = InlineStyle.of(12); var color = normal.toBuilder().rgb(0x2456a0).build();
        var source = Document.builder().add(new Paragraph("p", List.of(new InlineText("CALLER-", color),
                new InlineText("OLD", normal)), ParagraphFormat.of(12))).paragraph("tail", "UNCHANGED-ONLY").build();
        var policy = ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DEGRADE).build();
        var prepared = DocumentEditor.edit(source).policy(policy).replaceText("CALLER-OLD", "CALLER-EDITED").prepare();
        var result = prepared.execute();
        if (result.report().losses().size() != 1 || !result.report().warnings().isEmpty()
                || !result.report().issues().equals(prepared.preview().issues())
                || !DocumentEditor.edit(result.document()).find("CALLER-EDITED").matches().getFirst().uniformStyle()) {
            throw new IllegalStateException("isolated editing policy/report mismatch");
        }
        engine.export(result.document()).backend(new PdfBackend()).to(output.resolve("consumer-semantic-editing.pdf"));
        try (var stream = Files.newOutputStream(output.resolve("consumer-semantic-editing.docx"))) { new DocxAdapter().write(result.document(), stream); }
        new HtmlAdapter().export(result.document()).to(output.resolve("consumer-semantic-editing.html"));
        MarkdownAdapter.builder().policy(com.mirakyux.export4j.adapter.web.ExportPolicy.builder()
                .defaultAction(com.mirakyux.export4j.adapter.web.UnsupportedAction.WARN).build()).build()
                .export(result.document()).to(output.resolve("consumer-semantic-editing.md"));
    }
}
