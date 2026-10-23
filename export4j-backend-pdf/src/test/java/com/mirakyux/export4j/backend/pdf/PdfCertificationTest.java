// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;

class PdfCertificationTest {
    private static final PdfSigner.Options OPTIONS = new PdfSigner.Options("Test", "Certification",
            Instant.parse("2026-10-23T00:00:00Z"), 4096);
    // Structural unit tests only. Independent OpenSSL verification uses real detached CMS fixtures.
    private static final PdfSigner.CmsSigner STRUCTURAL = content -> { content.transferTo(java.io.OutputStream.nullOutputStream()); return new byte[] {48, 0}; };
    @TempDir Path directory;

    private Path source(String name) throws IOException {
        var source = directory.resolve(name);
        try (var document = new PDDocument()) { document.addPage(new PDPage()); document.save(source.toFile()); }
        return source;
    }

    @Test void eachPermissionUsesTheSameSignatureInCatalogAndDocMdpTransform() throws IOException {
        var source = source("source.pdf");
        byte[] original = Files.readAllBytes(source);
        int permissionValue = 0;
        for (var permission : PdfSigner.CertificationPermission.values()) {
            var output = new ByteArrayOutputStream() {
                @Override public void close() { throw new AssertionError("borrowed output closed"); }
            };
            PdfSigner.certify(source, output, OPTIONS, permission, STRUCTURAL);
            byte[] bytes = output.toByteArray();
            assertArrayEquals(original, java.util.Arrays.copyOf(bytes, original.length));
            try (var pdf = Loader.loadPDF(bytes)) {
                var signature = pdf.getSignatureDictionaries().getFirst();
                var catalog = pdf.getDocumentCatalog().getCOSObject();
                var permissions = (COSDictionary) catalog.getDictionaryObject(COSName.PERMS);
                assertSame(signature.getCOSObject(), permissions.getDictionaryObject(COSName.DOCMDP));
                var references = (COSArray) signature.getCOSObject().getDictionaryObject(COSName.getPDFName("Reference"));
                var reference = (COSDictionary) references.getObject(0);
                assertEquals(COSName.DOCMDP, reference.getCOSName(COSName.getPDFName("TransformMethod")));
                var parameters = (COSDictionary) reference.getDictionaryObject(COSName.getPDFName("TransformParams"));
                assertEquals(++permissionValue, parameters.getInt(COSName.P));
                assertSame(catalog, reference.getDictionaryObject(COSName.getPDFName("Data")));
                int[] range = signature.getByteRange();
                assertEquals(bytes.length, range[2] + range[3]);
            }
        }
        assertArrayEquals(original, Files.readAllBytes(source));
    }

    @Test void certificationMustBeFirstAndCannotReplaceExistingPermissions() throws IOException {
        var source = source("source.pdf");
        var signed = directory.resolve("signed.pdf");
        PdfSigner.sign(source, signed, OPTIONS, STRUCTURAL);
        var output = new ByteArrayOutputStream();
        var callback = new AtomicBoolean();
        assertThrows(IOException.class, () -> PdfSigner.certify(signed, output, OPTIONS,
                PdfSigner.CertificationPermission.NO_CHANGES, content -> { callback.set(true); return new byte[] {1}; }));
        assertEquals(0, output.size()); assertTrue(!callback.get());
        try (var pdf = Loader.loadPDF(source.toFile())) {
            pdf.getDocumentCatalog().getCOSObject().setItem(COSName.PERMS, new COSDictionary());
            pdf.save(directory.resolve("permissions.pdf").toFile());
        }
        assertThrows(IOException.class, () -> PdfSigner.certify(directory.resolve("permissions.pdf"), output, OPTIONS,
                PdfSigner.CertificationPermission.NO_CHANGES, STRUCTURAL));
        assertThrows(IOException.class, () -> PdfSigner.sign(directory.resolve("permissions.pdf"), output, OPTIONS, STRUCTURAL));
        assertEquals(0, output.size());
    }

    @Test void noChangesRejectsApprovalAndPermissiveCertificationsAllowAnIncrementalApproval() throws IOException {
        var source = source("source.pdf");
        for (var permission : PdfSigner.CertificationPermission.values()) {
            var certified = directory.resolve(permission.name() + ".pdf");
            PdfSigner.certify(source, certified, OPTIONS, permission, STRUCTURAL);
            var approval = new ByteArrayOutputStream();
            if (permission == PdfSigner.CertificationPermission.NO_CHANGES) {
                assertThrows(IOException.class, () -> PdfSigner.sign(certified, approval, OPTIONS, STRUCTURAL));
                assertEquals(0, approval.size());
            } else {
                PdfSigner.sign(certified, approval, OPTIONS, STRUCTURAL);
                try (var pdf = Loader.loadPDF(approval.toByteArray())) { assertEquals(2, pdf.getSignatureDictionaries().size()); }
                byte[] original = Files.readAllBytes(certified);
                assertArrayEquals(original, java.util.Arrays.copyOf(approval.toByteArray(), original.length));
            }
            assertThrows(IOException.class, () -> PdfSigner.certify(certified, approval, OPTIONS, permission, STRUCTURAL));
        }
    }

    @Test void malformedFractionalCertificationPermissionCannotBeTruncatedIntoAnAllowedValue() throws IOException {
        var source = source("source.pdf");
        var certified = directory.resolve("certified.pdf");
        PdfSigner.certify(source, certified, OPTIONS, PdfSigner.CertificationPermission.FORM_FILLING_AND_SIGNING, STRUCTURAL);
        var malformed = directory.resolve("malformed.pdf");
        try (var pdf = Loader.loadPDF(certified.toFile())) {
            var signature = pdf.getSignatureDictionaries().getFirst().getCOSObject();
            var references = (COSArray) signature.getDictionaryObject(COSName.getPDFName("Reference"));
            var parameters = (COSDictionary) ((COSDictionary) references.getObject(0))
                    .getDictionaryObject(COSName.getPDFName("TransformParams"));
            parameters.setItem(COSName.P, new org.apache.pdfbox.cos.COSFloat(2.5f));
            pdf.save(malformed.toFile());
        }
        var output = new ByteArrayOutputStream();
        var invoked = new AtomicBoolean();
        assertThrows(IOException.class, () -> PdfSigner.sign(malformed, output, OPTIONS, content -> {
            invoked.set(true); return new byte[] {48, 0};
        }));
        assertEquals(0, output.size()); assertTrue(!invoked.get());
    }

    @Test void atomicSigningPreservesTargetOnCallbackCancellationAndOutputLimitAndThenRecovers() throws IOException {
        var source = source("source.pdf");
        var target = directory.resolve("target.pdf"); Files.writeString(target, "old");
        var rejected = new IOException("signer rejected");
        assertSame(rejected, assertThrows(IOException.class, () -> PdfSigner.certify(source, target, OPTIONS,
                PdfSigner.CertificationPermission.NO_CHANGES, content -> { throw rejected; })));
        assertEquals("old", Files.readString(target));
        var cancelled = new AtomicBoolean();
        assertEquals(ExecutionException.Code.CANCELLED, assertThrows(ExecutionException.class,
                () -> ExecutionContext.execute(ExecutionOptions.builder().cancelled(cancelled::get).build(), () -> {
                    PdfSigner.sign(source, target, OPTIONS, content -> { cancelled.set(true); return new byte[] {48, 0}; }); return null;
                })).code());
        assertEquals("old", Files.readString(target));
        assertEquals(ExecutionException.Code.OUTPUT_LIMIT, assertThrows(ExecutionException.class,
                () -> ExecutionContext.execute(ExecutionOptions.builder().maxOutputBytes(10).build(), () -> {
                    PdfSigner.sign(source, target, OPTIONS, STRUCTURAL); return null;
                })).code());
        assertEquals("old", Files.readString(target));
        try (var files = Files.list(directory)) { assertEquals(2, files.count()); }
        PdfSigner.certify(source, target, OPTIONS, PdfSigner.CertificationPermission.NO_CHANGES, STRUCTURAL);
        try (var pdf = Loader.loadPDF(target.toFile())) { assertEquals(1, pdf.getSignatureDictionaries().size()); }
        PdfSigner.sign(source, source, OPTIONS, STRUCTURAL);
        try (var pdf = Loader.loadPDF(source.toFile())) { assertEquals(1, pdf.getSignatureDictionaries().size()); }
        try (var files = Files.list(directory)) { assertEquals(2, files.count()); }
    }
}
