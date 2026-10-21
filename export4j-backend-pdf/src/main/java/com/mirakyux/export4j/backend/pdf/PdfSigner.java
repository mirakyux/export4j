// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.GregorianCalendar;
import java.util.Objects;
import java.util.TimeZone;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions;
import com.mirakyux.export4j.model.ExecutionContext;

/** Incremental detached CMS signing. Keys, certificate trust and timestamp policy belong to the supplied signer.
 * Source remains untouched; output is caller-owned and must be discarded if signing fails. No network is used here.
 */
public final class PdfSigner {
    private PdfSigner() { }

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

    /** Adds one approval signature to an unencrypted PDF. Certification, PAdES-LT/LTA and validation of existing signatures are not supplied. */
    public static void sign(Path source, OutputStream output, Options options, CmsSigner signer) throws IOException {
        Objects.requireNonNull(source, "source"); Objects.requireNonNull(output, "output");
        Objects.requireNonNull(options, "options"); Objects.requireNonNull(signer, "signer");
        ExecutionContext.progress("SIGN", "", -1, 0, 1);
        try (var document = Loader.loadPDF(source.toFile()); var signatureOptions = new SignatureOptions()) {
            if (document.isEncrypted()) { throw new IOException("signing encrypted input is unsupported"); }
            var signature = new PDSignature();
            signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            signature.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);
            signature.setName(options.name()); signature.setReason(options.reason());
            var calendar = new GregorianCalendar(TimeZone.getTimeZone("UTC")); calendar.setTimeInMillis(options.time().toEpochMilli());
            signature.setSignDate(calendar);
            signatureOptions.setPreferredSignatureSize(options.maxCmsBytes());
            document.addSignature(signature, signatureOptions);
            var external = document.saveIncrementalForExternalSigning(ExecutionContext.output(output));
            byte[] cms;
            try (var content = external.getContent()) { cms = Objects.requireNonNull(signer.sign(content), "CMS signature"); }
            ExecutionContext.checkpoint();
            if (cms.length == 0 || cms.length > options.maxCmsBytes()) { throw new IOException("CMS signature exceeds reserved byte range or is empty"); }
            external.setSignature(cms);
        }
        ExecutionContext.progress("SIGN", "", -1, 1, 1);
    }
}
