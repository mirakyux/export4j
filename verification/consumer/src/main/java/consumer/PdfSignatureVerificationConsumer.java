// SPDX-License-Identifier: Apache-2.0
package consumer;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import com.mirakyux.export4j.backend.pdf.PdfSignatureVerifier;
import com.mirakyux.export4j.backend.pdf.PdfSigner;

/** Exercises the public rejection channel using deliberately invalid CMS, never reporting it as valid. */
final class PdfSignatureVerificationConsumer {
    private PdfSignatureVerificationConsumer() { }
    static void verify(Path output) throws IOException {
        var source = output.resolve("consumer-auto-spans.pdf");
        var unsigned = PdfSignatureVerifier.verify(source, PdfSignatureVerifier.Limits.DEFAULT,
                (content, cms) -> { throw new AssertionError("unsigned callback"); });
        if (unsigned.allSignedBytesValid() || unsigned.currentFileCovered() || !unsigned.signatures().isEmpty()) {
            throw new IOException("unsigned caller input was authenticated");
        }
        var rejected = output.resolve("consumer-rejected-signature.pdf");
        PdfSigner.sign(source, rejected, new PdfSigner.Options("Invalid CMS fixture", "Rejection only",
                Instant.parse("2026-10-23T00:00:00Z"), 1024), content -> {
            content.transferTo(java.io.OutputStream.nullOutputStream()); return new byte[]{0x30, 3, 2, 1, 1};
        });
        var report = PdfSignatureVerifier.verify(rejected, PdfSignatureVerifier.Limits.DEFAULT, (content, cms) -> {
            content.transferTo(java.io.OutputStream.nullOutputStream()); return false;
        });
        if (report.allSignedBytesValid() || !report.currentFileCovered() || report.signatures().size() != 1
                || report.signatures().getFirst().integrityValid()) {
            throw new IOException("caller rejection or byte coverage was misreported");
        }
    }
}
