// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests byte extraction and protocol boundaries. The DER digest fixture is not a cryptographic CMS;
 * real detached CMS and tamper rejection are exercised by the offline OpenSSL example/independent check.
 */
class PdfSignatureVerifierTest {
    @TempDir Path temporary;
    private static final PdfSignatureVerifier.Limits LIMITS = PdfSignatureVerifier.Limits.DEFAULT;
    private static final PdfSigner.Options OPTIONS = new PdfSigner.Options("VERIFIER-NAME", "Fixture",
            Instant.parse("2026-10-23T00:00:00Z"), 1024);

    @Test void extractsEveryExactSignedRevisionAndRemovesOnlyReservationPadding() throws IOException {
        var source = signed(2);
        var original = Files.readAllBytes(source);
        var report = PdfSignatureVerifier.verify(source, LIMITS, PdfSignatureVerifierTest::verifyDigest);
        assertEquals(2, report.signatures().size());
        assertTrue(report.allSignedBytesValid());
        assertTrue(report.currentFileCovered());
        assertFalse(report.signatures().getFirst().coversCurrentFile());
        assertTrue(report.signatures().getLast().coversCurrentFile());
        assertEquals(original.length, report.signatures().getLast().revisionBytes());
        assertTrue(report.signatures().getFirst().revisionBytes() < report.signatures().getLast().revisionBytes());
        assertTrue(report.signatures().stream().allMatch(signature -> signature.cmsBytes() == 36));
        assertArrayEquals(original, Files.readAllBytes(source));
    }

    @Test void unsignedAndUnsignedAppendsDoNotAuthenticateTheCurrentFile() throws IOException {
        var unsigned = PdfSignatureVerifier.verify(base(), LIMITS, (content, cms) -> { throw new AssertionError("unsigned callback"); });
        assertTrue(unsigned.signatures().isEmpty());
        assertFalse(unsigned.allSignedBytesValid()); assertFalse(unsigned.currentFileCovered());
        var source = signed(1);
        Files.writeString(source, "\n% unsigned appended bytes\n", StandardCharsets.US_ASCII, java.nio.file.StandardOpenOption.APPEND);
        var report = PdfSignatureVerifier.verify(source, LIMITS, PdfSignatureVerifierTest::verifyDigest);
        assertTrue(report.allSignedBytesValid()); assertFalse(report.currentFileCovered());
        assertTrue(report.signatures().getFirst().revisionBytes() < report.inputBytes());
    }

    @Test void changedSignedMetadataReturnsIntegrityFailureEvenWhenCoverageIsComplete() throws IOException {
        var source = signed(1);
        var bytes = Files.readAllBytes(source);
        int name = new String(bytes, StandardCharsets.ISO_8859_1).indexOf("VERIFIER-NAME");
        assertTrue(name > 0);
        bytes[name] = 'X'; Files.write(source, bytes);
        var report = PdfSignatureVerifier.verify(source, LIMITS, PdfSignatureVerifierTest::verifyDigest);
        assertTrue(report.currentFileCovered());
        assertFalse(report.allSignedBytesValid());
        assertFalse(report.signatures().getFirst().integrityValid());
    }

    @Test void incompleteOrFractionalByteRangesNeverReachVerifier() throws IOException {
        for (String replacement : new String[]{"1", "0.5"}) {
            var source = signed(1);
            String encoded = Files.readString(source, StandardCharsets.ISO_8859_1);
            var pattern = Pattern.compile("/ByteRange\\s*\\[0(?=\\s)");
            var matcher = pattern.matcher(encoded);
            assertTrue(matcher.find());
            int position = matcher.end() - 1;
            Files.writeString(source, encoded.substring(0, position) + replacement + encoded.substring(position + 1), StandardCharsets.ISO_8859_1);
            assertThrows(IOException.class, () -> PdfSignatureVerifier.verify(source, LIMITS,
                    (content, cms) -> { throw new AssertionError("invalid byte range reached callback"); }));
        }
    }

    @Test void nonzeroPaddingAndInvalidDerFailAllPreparationBeforeAnyCallback() throws IOException {
        for (boolean padding : new boolean[]{true, false}) {
            var source = signed(2);
            var bytes = Files.readAllBytes(source);
            var report = PdfSignatureVerifier.verify(source, LIMITS, PdfSignatureVerifierTest::verifyDigest);
            var last = report.signatures().getLast();
            bytes[Math.toIntExact(padding ? last.contentsEnd() - 2 : last.contentsOffset() + 1)] = padding ? (byte) '1' : (byte) '0';
            Files.write(source, bytes);
            assertThrows(IOException.class, () -> PdfSignatureVerifier.verify(source, LIMITS,
                    (content, cms) -> { throw new AssertionError("malformed later signature reached callback"); }));
        }
    }

    @Test void byteAndSignatureBudgetsFailBeforeCallbacks() throws IOException {
        var source = signed(2);
        int size = Math.toIntExact(Files.size(source));
        var constraints = new PdfSignatureVerifier.Limits[]{
                new PdfSignatureVerifier.Limits(size - 1, 2, 1024, 1_000_000),
                new PdfSignatureVerifier.Limits(size, 1, 1024, 1_000_000),
                new PdfSignatureVerifier.Limits(size, 2, 1023, 1_000_000),
                new PdfSignatureVerifier.Limits(size, 2, 1024, 1)
        };
        for (var constraint : constraints) {
            assertThrows(IOException.class, () -> PdfSignatureVerifier.verify(source, constraint,
                    (content, cms) -> { throw new AssertionError("budget failure reached callback"); }));
        }
        assertTrue(PdfSignatureVerifier.verify(source, LIMITS, PdfSignatureVerifierTest::verifyDigest).allSignedBytesValid());
    }

    @Test void callbacksCannotAcceptUnreadContentOrKeepBorrowedStreamsAlive() throws IOException {
        var source = signed(1);
        assertThrows(IOException.class, () -> PdfSignatureVerifier.verify(source, LIMITS, (content, cms) -> true));
        InputStream[] retained = {null};
        var report = PdfSignatureVerifier.verify(source, LIMITS, (content, cms) -> {
            retained[0] = content;
            return verifyDigest(content, cms);
        });
        assertTrue(report.allSignedBytesValid());
        assertThrows(IOException.class, () -> retained[0].read());
        var failure = new IOException("external verifier failed");
        assertSame(failure, assertThrows(IOException.class, () -> PdfSignatureVerifier.verify(source, LIMITS,
                (content, cms) -> { throw failure; })));
    }

    @Test void cancellationAfterOneCallbackStopsTheRemainingSignaturesAndNextCallRecovers() throws IOException {
        var source = signed(2);
        var cancelled = new AtomicBoolean();
        var calls = new AtomicInteger();
        var options = ExecutionOptions.builder().cancelled(cancelled::get).build();
        var failure = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(options,
                () -> PdfSignatureVerifier.verify(source, LIMITS, (content, cms) -> {
                    calls.incrementAndGet();
                    boolean valid = verifyDigest(content, cms);
                    cancelled.set(true);
                    return valid;
                })));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code()); assertEquals(1, calls.get());
        assertTrue(PdfSignatureVerifier.verify(source, LIMITS, PdfSignatureVerifierTest::verifyDigest).allSignedBytesValid());
    }

    private Path base() throws IOException {
        var path = temporary.resolve("base.pdf");
        try (var document = new PDDocument()) { document.addPage(new PDPage()); document.save(path.toFile()); }
        return path;
    }

    private Path signed(int count) throws IOException {
        Path source = base();
        for (int index = 1; index <= count; index++) {
            var target = temporary.resolve("signed-" + index + ".pdf");
            PdfSigner.sign(source, target, OPTIONS, content -> {
                var result = new byte[36]; result[0] = 0x30; result[1] = 34; result[2] = 4; result[3] = 32;
                System.arraycopy(digest(content.readAllBytes()), 0, result, 4, 32); return result;
            });
            source = target;
        }
        return source;
    }

    private static boolean verifyDigest(InputStream content, byte[] cms) throws IOException {
        return cms.length == 36 && MessageDigest.isEqual(digest(content.readAllBytes()), Arrays.copyOfRange(cms, 4, 36));
    }

    private static byte[] digest(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
