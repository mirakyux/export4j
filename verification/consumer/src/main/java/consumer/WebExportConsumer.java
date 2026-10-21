// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.web.ExportFeature;
import com.mirakyux.export4j.adapter.web.ExportPolicy;
import com.mirakyux.export4j.adapter.web.HtmlAdapter;
import com.mirakyux.export4j.adapter.web.MarkdownAdapter;
import com.mirakyux.export4j.adapter.web.UnsupportedAction;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.PageBreak;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class WebExportConsumer {
    private WebExportConsumer() { }
    static void verify(Path output) throws IOException {
        var document = Document.builder().heading("Consumer web output", 2)
                .paragraph("CALLER-ONLY <script> & | 中文")
                .bullet("CALLER-ITEM").table(List.of("First", "Second"), List.of(List.of("CALLER-CELL", "1 | 2")))
                .add(new PageBreak("break")).build();
        new HtmlAdapter().export(document).to(output.resolve("consumer-web.html"));
        var policy = ExportPolicy.builder().on(ExportFeature.PAGE_BREAK, UnsupportedAction.WARN).build();
        var result = MarkdownAdapter.builder().policy(policy).build().export(document).to(output.resolve("consumer-web.md"));
        if (result.diagnostics().size() != 1 || result.diagnostics().getFirst().feature() != ExportFeature.PAGE_BREAK) {
            throw new IOException("web export policy diagnostics differ");
        }
        var ignored = new ByteArrayOutputStream();
        var ignoredResult = MarkdownAdapter.builder().policy(ExportPolicy.builder().on(ExportFeature.PAGE_BREAK, UnsupportedAction.IGNORE).build())
                .build().export(document).to(ignored);
        if (!ignoredResult.diagnostics().isEmpty() || !java.util.Arrays.equals(Files.readAllBytes(output.resolve("consumer-web.md")), ignored.toByteArray())) {
            throw new IOException("WARN/IGNORE fallback outputs differ");
        }
    }
}
