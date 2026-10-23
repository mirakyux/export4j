// SPDX-License-Identifier: Apache-2.0
package consumer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.pdf.PdfSigner;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.ParagraphFormat;

final class PdfCertificationConsumer {
    private PdfCertificationConsumer() { }
    static void verify(DocumentEngine engine, Path output) throws IOException {
        var source = output.resolve("consumer-certification-source.pdf");
        engine.writeAtomically(Document.builder(new DocumentMetadata("Signing caller", ""), PageSettings.A4)
                .paragraph("body", "CERTIFICATION-CALLER", ParagraphFormat.of(12)).build(), new PdfBackend(), source);
        var target = output.resolve("consumer-certification-target.bin");
        Files.writeString(target, "old");
        var invoked = new AtomicBoolean();
        var rejected = new IOException("caller signer rejected");
        try {
            PdfSigner.certify(source, target, new PdfSigner.Options("Caller", "Failure recovery",
                    Instant.parse("2026-10-23T00:00:00Z"), 4096), PdfSigner.CertificationPermission.NO_CHANGES, content -> {
                        if (content.readNBytes(16).length != 16) { throw new AssertionError("missing signed content"); }
                        invoked.set(true); throw rejected;
                    });
            throw new AssertionError("signer failure was accepted");
        } catch (IOException failure) { if (failure != rejected) { throw failure; } }
        if (!invoked.get() || !Files.readString(target).equals("old")) { throw new AssertionError("atomic signing caller contract"); }
    }
}
