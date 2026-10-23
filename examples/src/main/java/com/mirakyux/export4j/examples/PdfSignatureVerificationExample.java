// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.TimeUnit;
import com.mirakyux.export4j.backend.pdf.PdfSignatureVerifier;

/** Explicit offline CMS integrity callback; -noverify intentionally does not establish certificate trust. */
public final class PdfSignatureVerificationExample {
    private static final Path INPUT = Path.of("examples/build/pdf-certification");
    private static final Path OUTPUT = Path.of("examples/build/pdf-signature-verification");
    private PdfSignatureVerificationExample() { }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) { throw new IllegalArgumentException("provide the offline OpenSSL executable"); }
        Files.createDirectories(OUTPUT);
        var openssl = Path.of(args[0]);
        var entries = new java.util.ArrayList<String>();
        for (String name : List.of("source", "certified-1", "certified-2", "certified-3", "approved-2", "approved-3")) {
            var source = INPUT.resolve(name + ".pdf");
            byte[] original = Files.readAllBytes(source);
            var report = verify(source, openssl);
            if (name.equals("source") ? report.allSignedBytesValid() || report.currentFileCovered()
                    : !report.allSignedBytesValid() || !report.currentFileCovered()) {
                throw new IOException("offline signature verification result differs");
            }
            if (!java.util.Arrays.equals(original, Files.readAllBytes(source))) { throw new IOException("verification modified its input"); }
            entries.add(json(name, report));
        }
        var approved = Files.readAllBytes(INPUT.resolve("approved-2.pdf"));
        var appended = OUTPUT.resolve("appended.pdf");
        Files.write(appended, approved);
        Files.writeString(appended, "\n% unsigned append\n", StandardCharsets.US_ASCII, java.nio.file.StandardOpenOption.APPEND);
        var historical = verify(appended, openssl);
        if (!historical.allSignedBytesValid() || historical.currentFileCovered()) { throw new IOException("unsigned append was authenticated"); }
        entries.add(json("appended", historical));

        int position = new String(approved, StandardCharsets.ISO_8859_1).lastIndexOf("Development certificate");
        if (position < 0) { throw new IOException("development signature metadata was not found"); }
        approved[position] = 'X';
        var tampered = OUTPUT.resolve("tampered.pdf");
        Files.write(tampered, approved);
        var rejected = verify(tampered, openssl);
        if (rejected.allSignedBytesValid() || !rejected.currentFileCovered() || rejected.signatures().size() != 2
                || !rejected.signatures().getFirst().integrityValid() || rejected.signatures().getLast().integrityValid()) {
            throw new IOException("changed latest signed revision was accepted");
        }
        entries.add(json("tampered", rejected));
        Files.writeString(OUTPUT.resolve("report.json"), "[\n" + String.join(",\n", entries) + "\n]\n");
    }

    private static PdfSignatureVerifier.Report verify(Path source, Path openssl) throws IOException {
        return PdfSignatureVerifier.verify(source, PdfSignatureVerifier.Limits.DEFAULT,
                (content, cms) -> verifyCms(content, cms, openssl));
    }

    private static String json(String name, PdfSignatureVerifier.Report report) {
        var signatures = report.signatures().stream().map(value -> "{\"revisionBytes\":" + value.revisionBytes()
                + ",\"contentsOffset\":" + value.contentsOffset() + ",\"contentsEnd\":" + value.contentsEnd()
                + ",\"cmsBytes\":" + value.cmsBytes() + ",\"coversCurrentFile\":" + value.coversCurrentFile()
                + ",\"integrityValid\":" + value.integrityValid() + "}").toList();
        return "{\"name\":\"" + name + "\",\"inputBytes\":" + report.inputBytes() + ",\"allSignedBytesValid\":"
                + report.allSignedBytesValid() + ",\"currentFileCovered\":" + report.currentFileCovered()
                + ",\"signatures\":[" + String.join(",", signatures) + "]}";
    }

    private static boolean verifyCms(InputStream content, byte[] cms, Path openssl) throws IOException {
        var data = Files.createTempFile(OUTPUT, "content-", ".bin");
        var signature = Files.createTempFile(OUTPUT, "signature-", ".der");
        var verified = Files.createTempFile(OUTPUT, "verified-", ".bin");
        var log = Files.createTempFile(OUTPUT, "verification-", ".log");
        try {
            Files.copy(content, data, StandardCopyOption.REPLACE_EXISTING); Files.write(signature, cms);
            var process = new ProcessBuilder(openssl.toString(), "cms", "-verify", "-binary", "-inform", "DER",
                    "-in", signature.toString(), "-content", data.toString(), "-noverify", "-out", verified.toString())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                if (!process.waitFor(30, TimeUnit.SECONDS)) {
                    process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS);
                    throw new IOException("offline verifier timed out");
                }
                return process.exitValue() == 0 && Files.mismatch(data, verified) == -1;
            } catch (InterruptedException failure) {
                process.destroyForcibly(); Thread.currentThread().interrupt(); throw new IOException("offline verifier interrupted", failure);
            }
        } finally {
            Files.deleteIfExists(data); Files.deleteIfExists(signature); Files.deleteIfExists(verified); Files.deleteIfExists(log);
        }
    }
}
