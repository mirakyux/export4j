// SPDX-License-Identifier: Apache-2.0
package consumer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import com.mirakyux.export4j.backend.pdf.PdfPades;
import com.mirakyux.export4j.backend.pdf.PdfSignatureVerifier;
import com.mirakyux.export4j.backend.pdf.PdfSigner;
import com.mirakyux.export4j.backend.pdf.PdfTrust;

/** Real private-key/CMS/OCSP/CRL/RFC3161 operations against original offline fixtures. */
final class PdfPadesConsumer {
    private PdfPadesConsumer() { }
    private static final Path FIXTURES = Path.of("../../build/verification/pdf-standards-fixtures");
    private static final Instant TIME = Instant.parse("2026-01-01T00:00:00Z");
    static void verify(Path output) throws Exception {
        Path source = output.resolve("source.pdf"); byte[] root = bytes("root.der"), signer = bytes("signer.der"), tsa = bytes("tsa.der");
        var key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes("signer-key.pk8")));
        var identity = new PdfPades.Identity(key, List.of(signer, root));
        var trust = new PdfTrust.Material(List.of(root), List.of(), TIME.plus(Duration.ofDays(1)), Duration.ofDays(7), Duration.ofMinutes(1));
        var evidence = new PdfTrust.Evidence(List.of(signer, tsa, root), List.of(bytes("signer-good.ocsp"), bytes("tsa-good.ocsp")), List.of());
        var crlEvidence = new PdfTrust.Evidence(List.of(signer, tsa, root), List.of(), List.of(bytes("good.crl")));
        for (var level : List.of(PdfPades.Level.B, PdfPades.Level.T, PdfPades.Level.LT, PdfPades.Level.LTA)) {
            var options = PdfPades.Options.defaults(level, "Fixture signer", "Offline standards validation", TIME);
            var report = PdfPades.sign(source, output.resolve("pades-" + level.name().toLowerCase(java.util.Locale.ROOT) + ".pdf"), options, identity, trust, evidence, imprint -> timestamp(imprint, "2026-01-01T00:01:00+00:00"));
            require(report.meets(level), "requested PAdES baseline level did not validate");
        }
        int permissionIndex = 0;
        for (var permission : PdfSigner.CertificationPermission.values()) {
            permissionIndex++;
            var options = PdfPades.Options.defaults(PdfPades.Level.LTA, "Certified fixture signer", "Certified long-term validation", TIME);
            require(PdfPades.certify(source, output.resolve("pades-certified-" + permissionIndex + "-lta.pdf"), options, identity, trust, evidence,
                    imprint -> timestamp(imprint, "2026-01-01T00:01:00+00:00"), permission).meets(PdfPades.Level.LTA), "certified PAdES LTA level failed");
        }
        var ltOptions = PdfPades.Options.defaults(PdfPades.Level.LT, "Fixture signer", "CRL validation", TIME);
        require(PdfPades.sign(source, output.resolve("pades-lt-crl.pdf"), ltOptions, identity, trust, crlEvidence, imprint -> timestamp(imprint, "2026-01-01T00:01:00+00:00")).meets(PdfPades.Level.LT), "CRL-only LT validation failed");
        var renewalTrust = new PdfTrust.Material(List.of(root), List.of(), TIME.plus(Duration.ofDays(4)), Duration.ofDays(7), Duration.ofMinutes(1));
        var renewal = PdfPades.Options.defaults(PdfPades.Level.LTA, "Fixture TSA", "Evidence renewal", TIME.plus(Duration.ofDays(2)));
        require(PdfPades.extend(output.resolve("pades-lta.pdf"), output.resolve("pades-lta-renewed.pdf"), renewal, renewalTrust, evidence,
                imprint -> timestamp(imprint, "2026-01-03T00:01:00+00:00")).meets(PdfPades.Level.LTA), "LTA evidence renewal failed");
        var limits = PdfSignatureVerifier.Limits.DEFAULT;
        var revoked = new PdfTrust.Evidence(List.of(signer, tsa, root), List.of(bytes("signer-revoked.ocsp"), bytes("tsa-good.ocsp")), List.of());
        var unknown = new PdfTrust.Evidence(List.of(signer, tsa, root), List.of(bytes("signer-unknown.ocsp"), bytes("tsa-good.ocsp")), List.of());
        var stale = new PdfTrust.Evidence(List.of(signer, tsa, root), List.of(bytes("signer-stale.ocsp"), bytes("tsa-stale.ocsp")), List.of());
        require(!PdfPades.verify(output.resolve("pades-b.pdf"), limits, trust, revoked).meets(PdfPades.Level.B), "revoked signer was trusted");
        require(!PdfPades.verify(output.resolve("pades-b.pdf"), limits, trust, unknown).meets(PdfPades.Level.B), "unknown revocation status was trusted");
        require(!PdfPades.verify(output.resolve("pades-b.pdf"), limits, trust, stale).meets(PdfPades.Level.B), "expired evidence was trusted");
        var untrusted = new PdfTrust.Material(List.of(tsa), List.of(), trust.validationTime(), Duration.ofDays(7), Duration.ZERO);
        require(!PdfPades.verify(output.resolve("pades-t.pdf"), limits, untrusted, PdfTrust.Evidence.EMPTY).meets(PdfPades.Level.B), "untrusted certificate path was accepted");
        byte[] altered = Files.readAllBytes(output.resolve("pades-b.pdf")); altered[7] = altered[7] == '5' ? (byte) '6' : (byte) '5';
        Files.write(output.resolve("pades-tampered.pdf"), altered);
        require(!PdfPades.verify(output.resolve("pades-tampered.pdf"), limits, trust, PdfTrust.Evidence.EMPTY).meets(PdfPades.Level.B), "changed signed bytes were accepted");
        Path rejected = output.resolve("pades-rejected.pdf"); Files.writeString(rejected, "OLD TARGET");
        try { PdfPades.extend(output.resolve("pades-t.pdf"), rejected, ltOptions, trust, PdfTrust.Evidence.EMPTY, PdfPades.TimestampSource.UNAVAILABLE); throw new AssertionError("missing revocation evidence created LT output"); }
        catch (IOException expected) { require(Files.readString(rejected).equals("OLD TARGET"), "failed evidence update replaced old target"); }
        var expiredIdentity = new PdfPades.Identity(KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes("expired-key.pk8"))), List.of(bytes("expired.der"), root));
        try { PdfPades.sign(source, rejected, PdfPades.Options.defaults(PdfPades.Level.B, "Expired", "Rejected", TIME), expiredIdentity, trust, PdfTrust.Evidence.EMPTY, PdfPades.TimestampSource.UNAVAILABLE); throw new AssertionError("expired signer created a valid signature"); }
        catch (PdfTrust.ValidationException expected) { require(expected.failure() == PdfTrust.Failure.EXPIRED_CERTIFICATE && Files.readString(rejected).equals("OLD TARGET"), "expired certificate did not preserve target"); }
        try { PdfPades.sign(source, rejected, PdfPades.Options.defaults(PdfPades.Level.T, "Invalid timestamp", "Wrong imprint", TIME), identity, trust, evidence,
                imprint -> timestamp(new byte[32], "2026-01-01T00:01:00+00:00")); throw new AssertionError("wrong timestamp imprint was accepted"); }
        catch (PdfTrust.ValidationException expected) { require(expected.failure() == PdfTrust.Failure.INVALID_TIMESTAMP && Files.readString(rejected).equals("OLD TARGET"), "wrong-imprint timestamp replaced target"); }
        try { PdfPades.sign(source, rejected, PdfPades.Options.defaults(PdfPades.Level.T, "Invalid timestamp", "Future time", TIME), identity, trust, evidence,
                imprint -> timestamp(imprint, "2026-02-01T00:01:00+00:00")); throw new AssertionError("future timestamp was accepted"); }
        catch (PdfTrust.ValidationException expected) { require(expected.failure() == PdfTrust.Failure.FUTURE_TIMESTAMP && Files.readString(rejected).equals("OLD TARGET"), "future timestamp replaced target"); }
        Files.writeString(output.resolve("pades-checks.txt"), "B,T,LT,LTA,CRL_LT,LTA_RENEWAL,CERTIFIED_P1_LTA,CERTIFIED_P2_LTA,CERTIFIED_P3_LTA,REVOKED,UNKNOWN,STALE,UNTRUSTED,TAMPERED,MISSING_EVIDENCE,EXPIRED_CERTIFICATE,WRONG_TIMESTAMP_IMPRINT,FUTURE_TIMESTAMP,ATOMIC_TARGET\n");
    }
    private static byte[] bytes(String name) throws IOException { return Files.readAllBytes(FIXTURES.resolve(name)); }
    private static byte[] timestamp(byte[] imprint, String time) throws IOException {
        Path target = Files.createTempFile(FIXTURES, "timestamp-", ".der");
        try {
            String python = System.getProperty("consumer.verificationPython", "../../build/verification/venv/Scripts/python.exe");
            var process = new ProcessBuilder(python, "../../scripts/create_pdf_standards_fixtures.py", "--timestamp", HexFormat.of().formatHex(imprint), "--output", target.toString(), "--time", time).redirectErrorStream(true).start();
            try {
                if (!process.waitFor(60, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("offline fixture TSA exceeded its timeout"); }
                if (process.exitValue() != 0) { throw new IOException("offline fixture TSA failed"); }
            } catch (InterruptedException failure) { process.destroyForcibly(); Thread.currentThread().interrupt(); throw new IOException("offline fixture TSA interrupted", failure); }
            return Files.readAllBytes(target);
        } finally { Files.deleteIfExists(target); }
    }
    private static void require(boolean value, String message) { if (!value) { throw new AssertionError(message); } }
}
