// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.GregorianCalendar;
import java.util.Objects;
import java.util.TimeZone;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions;
import com.mirakyux.export4j.model.ExecutionContext;

/** Incremental detached CMS signing. Keys, certificate trust and timestamp policy belong to the supplied signer.
 * Stream forms leave the source untouched; atomic path forms also allow in-place replacement.
 * Borrowed stream output must be discarded if signing fails. No network is used here.
 */
public final class PdfSigner {
    private PdfSigner() { }

    /** ISO 32000 DocMDP permissions. These declare permitted subsequent changes to validating readers;
     * they do not encrypt the PDF or prevent applications from modifying bytes.
     */
    public enum CertificationPermission {
        NO_CHANGES(1),
        FORM_FILLING_AND_SIGNING(2),
        ANNOTATIONS_FORM_FILLING_AND_SIGNING(3);

        private final int value;
        CertificationPermission(int value) { this.value = value; }
    }

    @FunctionalInterface
    public interface CmsSigner {
        /** Consumes the borrowed exact PDF ByteRange stream and returns a complete DER detached CMS signature.
         * Stream lifetime ends when this method returns. Do not return a bare digest or raw RSA signature.
         */
        byte[] sign(InputStream content) throws IOException;
    }

    public record Options(String name, String reason, Instant time, int maxCmsBytes) {
        public Options {
            Objects.requireNonNull(name, "name"); Objects.requireNonNull(reason, "reason"); Objects.requireNonNull(time, "time");
            if (name.length() > 256 || reason.length() > 1024 || name.codePoints().anyMatch(Character::isISOControl)
                    || reason.codePoints().anyMatch(Character::isISOControl) || maxCmsBytes < 1024 || maxCmsBytes > 1_048_576) {
                throw new IllegalArgumentException("invalid signature options");
            }
            time.toEpochMilli();
        }
    }

    /** Adds an approval signature to an unencrypted PDF. An existing DocMDP NO_CHANGES declaration
     * is rejected. Existing cryptographic signatures and permitted-change conformance are not validated.
     */
    public static void sign(Path source, OutputStream output, Options options, CmsSigner signer) throws IOException {
        write(source, output, options, signer, null);
    }

    /** Adds the first signature as a DocMDP certification signature. Existing signatures or catalog
     * permissions are rejected before invoking the signer or writing bytes. No trust, revocation,
     * timestamp, PAdES or reader enforcement is implied. Output is borrowed and must be discarded on failure.
     */
    public static void certify(Path source, OutputStream output, Options options,
                               CertificationPermission permission, CmsSigner signer) throws IOException {
        write(source, output, options, signer, Objects.requireNonNull(permission, "permission"));
    }

    /** Atomically replaces the target only after approval signing and closing succeed. The source
     * may equal the target. Requires an existing parent and atomic-move support; no copy fallback.
     */
    public static void sign(Path source, Path target, Options options, CmsSigner signer) throws IOException {
        atomic(source, target, options, signer, null);
    }

    /** Atomic-target form of {@link #certify(Path, OutputStream, Options, CertificationPermission, CmsSigner)}. */
    public static void certify(Path source, Path target, Options options,
                               CertificationPermission permission, CmsSigner signer) throws IOException {
        atomic(source, target, options, signer, Objects.requireNonNull(permission, "permission"));
    }

    private static void atomic(Path source, Path target, Options options, CmsSigner signer,
                               CertificationPermission permission) throws IOException {
        Objects.requireNonNull(source, "source"); Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options"); Objects.requireNonNull(signer, "signer");
        ExecutionContext.checkpoint();
        var absolute = target.toAbsolutePath();
        var temporary = Files.createTempFile(absolute.getParent(), ".export4j-sign-", ".pdf");
        try {
            try (var output = Files.newOutputStream(temporary)) { write(source, output, options, signer, permission); }
            ExecutionContext.checkpoint();
            Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException | Error failure) {
            try { Files.deleteIfExists(temporary); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private static void write(Path source, OutputStream output, Options options, CmsSigner signer,
                              CertificationPermission permission) throws IOException {
        Objects.requireNonNull(source, "source"); Objects.requireNonNull(output, "output");
        Objects.requireNonNull(options, "options"); Objects.requireNonNull(signer, "signer");
        ExecutionContext.progress("SIGN", "", -1, 0, 1);
        try (var document = Loader.loadPDF(source.toFile()); var signatureOptions = new SignatureOptions()) {
            if (document.isEncrypted()) { throw new IOException("signing encrypted input is unsupported"); }
            if (permission != null) {
                if (!document.getSignatureDictionaries().isEmpty()
                        || document.getDocumentCatalog().getCOSObject().containsKey(COSName.PERMS)) {
                    throw new IOException("certification requires an unsigned PDF without catalog permissions");
                }
            } else {
                checkApprovalPermission(document);
            }
            var signature = new PDSignature();
            signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            signature.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);
            signature.setName(options.name()); signature.setReason(options.reason());
            var calendar = new GregorianCalendar(TimeZone.getTimeZone("UTC")); calendar.setTimeInMillis(options.time().toEpochMilli());
            signature.setSignDate(calendar);
            signatureOptions.setPreferredSignatureSize(options.maxCmsBytes());
            document.addSignature(signature, signatureOptions);
            if (permission != null) { certification(document, signature, permission); }
            var external = document.saveIncrementalForExternalSigning(ExecutionContext.output(output));
            byte[] cms;
            try (var content = external.getContent()) { cms = Objects.requireNonNull(signer.sign(content), "CMS signature"); }
            ExecutionContext.checkpoint();
            if (cms.length == 0 || cms.length > options.maxCmsBytes()) { throw new IOException("CMS signature exceeds reserved byte range or is empty"); }
            external.setSignature(cms);
        }
        ExecutionContext.progress("SIGN", "", -1, 1, 1);
    }

    private static void certification(PDDocument document, PDSignature signature, CertificationPermission permission) {
        var parameters = new COSDictionary();
        parameters.setItem(COSName.TYPE, COSName.getPDFName("TransformParams"));
        parameters.setInt(COSName.P, permission.value);
        parameters.setName(COSName.V, "1.2");
        var reference = new COSDictionary();
        reference.setItem(COSName.TYPE, COSName.getPDFName("SigRef"));
        reference.setItem(COSName.getPDFName("TransformMethod"), COSName.DOCMDP);
        reference.setItem(COSName.getPDFName("TransformParams"), parameters);
        reference.setItem(COSName.getPDFName("Data"), document.getDocumentCatalog().getCOSObject());
        var references = new COSArray();
        references.add(reference);
        signature.getCOSObject().setItem(COSName.getPDFName("Reference"), references);
        var permissions = new COSDictionary();
        permissions.setItem(COSName.DOCMDP, signature);
        var catalog = document.getDocumentCatalog().getCOSObject();
        catalog.setItem(COSName.PERMS, permissions);
        catalog.setNeedToBeUpdated(true);
    }

    private static void checkApprovalPermission(PDDocument document) throws IOException {
        var catalog = document.getDocumentCatalog().getCOSObject();
        if (!catalog.containsKey(COSName.PERMS)) { return; }
        if (!(catalog.getDictionaryObject(COSName.PERMS) instanceof COSDictionary permissions)
                || permissions.size() != 1
                || !(permissions.getDictionaryObject(COSName.DOCMDP) instanceof COSDictionary signature)
                || !(signature.getDictionaryObject(COSName.getPDFName("Reference")) instanceof COSArray references)
                || references.size() != 1
                || !(references.getObject(0) instanceof COSDictionary reference)
                || !COSName.DOCMDP.equals(reference.getCOSName(COSName.getPDFName("TransformMethod")))
                || !(reference.getDictionaryObject(COSName.getPDFName("TransformParams")) instanceof COSDictionary parameters)) {
            throw new IOException("unsupported or malformed PDF catalog permissions");
        }
        if (!(parameters.getDictionaryObject(COSName.P) instanceof COSInteger permission)) {
            throw new IOException("malformed certification permission");
        }
        long value = permission.longValue();
        if (value < 2 || value > 3) { throw new IOException("certification does not permit another approval signature"); }
    }
}
