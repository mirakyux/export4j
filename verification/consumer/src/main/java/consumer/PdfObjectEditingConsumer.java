// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.pdf.PdfAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfFont;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DocumentExtras;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class PdfObjectEditingConsumer {
    private PdfObjectEditingConsumer() { }
    static void verify(FontFace font, Path output) throws IOException {
        var fields = List.<DocumentExtras.FormField>of(new DocumentExtras.TextField(0, new Rect(20, 30, 180, 30), "caller", "OLD", false));
        var document = new DisplayDocument(DocumentMetadata.EMPTY, List.of(new DisplayPage(new PageSize(240, 120), List.of())), new DocumentExtras(List.of(), List.of(), List.of(), fields));
        Path source = output.resolve("consumer-object-source.pdf"); try (var stream = Files.newOutputStream(source)) { new PdfBackend().write(document, stream); }
        var adapter = new PdfAdapter(); var edited = output.resolve("consumer-object-edited.pdf");
        adapter.editObjects(adapter.input(source, "")).fillText("caller", "CALLER-READY", PdfFont.of(font.data().bytes()), 12).prepare().to(edited);
        if (!adapter.readObjects(adapter.input(edited, "")).fields().getFirst().value().equals("CALLER-READY")) { throw new IllegalStateException("isolated PDF object fill failed"); }
        adapter.editObjects(adapter.input(edited, "")).policy(ProcessingPolicy.builder().feature("FORMS", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD).build())
                .flattenForms().prepare().to(output.resolve("consumer-object-flattened.pdf"));
        adapter.composeObjects().add(adapter.input(edited, "").allPages(), "first").add(adapter.input(edited, "").allPages(), "second")
                .policy(ProcessingPolicy.builder().feature("DOCUMENT_METADATA", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD).build())
                .prepare().to(output.resolve("consumer-object-composed.pdf"));
    }
}
