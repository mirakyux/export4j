// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.pdf.PdfSigner;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.ParagraphFormat;

/** Explicit offline development signer. The library supplies neither private keys nor certificate trust. */
public final class PdfCertificationExample {
    private static final Path OUTPUT = Path.of("examples/build/pdf-certification");
    private PdfCertificationExample() { }

    public static void main(String[] args) throws IOException {
        if (args.length != 3) { throw new IllegalArgumentException("provide OpenSSL, certificate and private-key paths"); }
        Files.createDirectories(OUTPUT);
        var font = TrueTypeFontLoader.load(Files.readAllBytes(Path.of("assets/fonts/LiberationSans-Regular.ttf")));
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(font))).build();
        var source = OUTPUT.resolve("source.pdf");
        engine.writeAtomically(Document.builder(new DocumentMetadata("Certification sample", ""), PageSettings.A4)
                .paragraph("body", "CERTIFICATION-BASE", ParagraphFormat.of(14)).build(), new PdfBackend(), source);
        var options = new PdfSigner.Options("Development certificate", "Offline certification verification",
                Instant.parse("2026-10-23T00:00:00Z"), 16_384);
        PdfSigner.CmsSigner signer = content -> cms(content, Path.of(args[0]), Path.of(args[1]), Path.of(args[2]));
        int value = 0;
        for (var permission : PdfSigner.CertificationPermission.values()) {
            var certified = OUTPUT.resolve("certified-" + (++value) + ".pdf");
            PdfSigner.certify(source, certified, options, permission, signer);
            if (permission != PdfSigner.CertificationPermission.NO_CHANGES) {
                PdfSigner.sign(certified, OUTPUT.resolve("approved-" + value + ".pdf"), options, signer);
            }
        }
    }

    private static byte[] cms(InputStream content, Path openssl, Path certificate, Path key) throws IOException {
        var data = Files.createTempFile(OUTPUT, "content-", ".bin");
        var signature = Files.createTempFile(OUTPUT, "signature-", ".der");
        var log = Files.createTempFile(OUTPUT, "signing-", ".log");
        try {
            Files.copy(content, data, StandardCopyOption.REPLACE_EXISTING);
            var process = new ProcessBuilder(openssl.toString(), "cms", "-sign", "-binary", "-in", data.toString(),
                    "-signer", certificate.toString(), "-inkey", key.toString(), "-outform", "DER", "-out", signature.toString(),
                    "-md", "sha256").redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                if (!process.waitFor(30, TimeUnit.SECONDS)) {
                    process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS);
                    throw new IOException("development signer timed out");
                }
                if (process.exitValue() != 0) { throw new IOException("development signer failed; inspect local certificate configuration"); }
            } catch (InterruptedException failure) {
                process.destroyForcibly(); Thread.currentThread().interrupt(); throw new IOException("development signer interrupted", failure);
            }
            return Files.readAllBytes(signature);
        } finally { Files.deleteIfExists(data); Files.deleteIfExists(signature); Files.deleteIfExists(log); }
    }
}
