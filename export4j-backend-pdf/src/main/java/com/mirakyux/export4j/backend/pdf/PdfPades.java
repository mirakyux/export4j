// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.GregorianCalendar;
import java.util.Hashtable;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.TimeZone;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.cms.CMSAttributes;
import org.bouncycastle.asn1.cms.Time;
import org.bouncycastle.asn1.ess.ESSCertIDv2;
import org.bouncycastle.asn1.ess.SigningCertificateV2;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.DefaultSignedAttributeTableGenerator;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.SignerInformationStore;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import com.mirakyux.export4j.model.ExecutionContext;

/** Offline PAdES baseline signatures with explicit keys, trust, time and revocation evidence.
 * B/T/LT/LTA use SHA-256, detached CAdES, RFC3161 signature timestamps, DSS and document timestamps.
 * This API never contacts a TSA or certificate server: the application supplies exact timestamp tokens.
 * Path operations are atomic, synchronous and cancellation aware. Keys are borrowed and never encoded.
 */
public final class PdfPades {
    private PdfPades() { }
    public enum Level { NONE, B, T, LT, LTA }
    public record Identity(PrivateKey privateKey, List<byte[]> certificateChain) {
        public Identity { Objects.requireNonNull(privateKey, "privateKey"); certificateChain = PdfTrust.snapshot(certificateChain, 16, 65_536); if (certificateChain.isEmpty()) { throw new IllegalArgumentException("signer certificate is required"); } }
        @Override public List<byte[]> certificateChain() { return certificateChain.stream().map(byte[]::clone).toList(); }
        @Override public String toString() { return "PdfPades.Identity[explicit key and certificate chain]"; }
    }
    @FunctionalInterface public interface TimestampSource {
        /** Returns one DER RFC3161 TimeStampToken for the supplied SHA-256 imprint, not a TimeStampResp.
         * The digest is a borrowed independent value; no PDF content or private key is exposed.
         */
        byte[] timestamp(byte[] sha256Imprint) throws IOException;
        TimestampSource UNAVAILABLE = digest -> { throw new IOException("an explicit trusted timestamp source is required"); };
    }
    public record Options(Level level, String name, String reason, Instant time, int maxInputBytes, int maxCmsBytes, long maxOutputBytes) {
        public Options {
            Objects.requireNonNull(level, "level"); new PdfSigner.Options(name, reason, time, maxCmsBytes);
            if (level == Level.NONE || maxInputBytes <= 0 || maxInputBytes > 256 * 1_048_576 || maxOutputBytes < maxInputBytes) { throw new IllegalArgumentException("invalid PAdES options"); }
        }
        public static Options defaults(Level level, String name, String reason, Instant time) { return new Options(level, name, reason, time, 32 * 1_048_576, 64 * 1024, 64L * 1_048_576); }
    }
    public record Signature(int index, long revisionBytes, boolean documentTimestamp, Level achieved,
                            boolean integrityValid, boolean trusted, Instant trustedTime, String failure) { }
    public record Report(long inputBytes, List<Signature> signatures, PdfDocMdp.Report modifications) {
        public Report { signatures = List.copyOf(signatures); }
        public boolean meets(Level required) { return signatures.stream().anyMatch(item -> !item.documentTimestamp())
                && signatures.stream().allMatch(item -> item.integrityValid() && item.trusted() && (item.documentTimestamp() || item.achieved().ordinal() >= required.ordinal()))
                && modifications.status() != PdfDocMdp.Status.FORBIDDEN && modifications.status() != PdfDocMdp.Status.INDETERMINATE; }
    }

    /** Creates the first PAdES approval signature. Existing signatures/encryption/catalog permissions fail. */
    public static Report sign(Path source, Path target, Options options, Identity identity, PdfTrust.Material trust,
                              PdfTrust.Evidence evidence, TimestampSource timestamps) throws IOException {
        return create(source, target, options, identity, trust, evidence, timestamps, null);
    }
    /** First-signature certification plus the requested PAdES baseline level. */
    public static Report certify(Path source, Path target, Options options, Identity identity, PdfTrust.Material trust,
                                 PdfTrust.Evidence evidence, TimestampSource timestamps, PdfSigner.CertificationPermission permission) throws IOException {
        return create(source, target, options, identity, trust, evidence, timestamps, Objects.requireNonNull(permission, "permission"));
    }

    private static Report create(Path source, Path target, Options options, Identity identity, PdfTrust.Material trust,
                                 PdfTrust.Evidence evidence, TimestampSource timestamps, PdfSigner.CertificationPermission permission) throws IOException {
        Objects.requireNonNull(options, "options"); Objects.requireNonNull(identity, "identity"); Objects.requireNonNull(trust, "trust"); Objects.requireNonNull(evidence, "evidence"); Objects.requireNonNull(timestamps, "timestamps");
        var certificates = PdfTrust.certificates(identity.certificateChain); var signer = certificates.getFirst();
        PdfTrust.validate(signer, certificates, trust, evidence, options.time(), options.level().ordinal() >= Level.LT.ordinal());
        var usage = signer.getKeyUsage(); if (usage != null && !usage[0]) { throw new PdfTrust.ValidationException(PdfTrust.Failure.INVALID_SIGNATURE); }
        var temporaries = new ArrayList<Path>();
        try {
            var first = temporary(target, temporaries);
            incremental(source, first, options, false, permission, content -> cms(content, options, identity, certificates, trust, evidence, timestamps));
            Path result = first;
            if (options.level().ordinal() >= Level.LT.ordinal()) { var next = temporary(target, temporaries); dss(first, next, options, completeEvidence(first, options, trust, evidence)); result = next; }
            if (options.level() == Level.LTA) { var next = temporary(target, temporaries); var exact = result; incremental(exact, next, options, true, null, content -> token(content, timestamps, trust, evidence, true)); result = next; }
            var report = verify(result, limits(options), trust, evidence); if (!report.meets(options.level())) { throw new IOException("final PAdES output did not meet the requested validation level"); }
            cleanupBeforePublish(temporaries, result); ExecutionContext.checkpoint(); Files.move(result, target.toAbsolutePath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); return report;
        } catch (IOException | RuntimeException | Error failure) { cleanupAfterFailure(temporaries, failure); throw failure; }
    }

    /** Adds/replaces explicit DSS evidence, and optionally renews protection with a new document timestamp.
     * Existing B signatures need a signature timestamp before this method; it never invents historic time.
     */
    public static Report extend(Path source, Path target, Options options, PdfTrust.Material trust,
                                PdfTrust.Evidence evidence, TimestampSource timestamps) throws IOException {
        if (options.level() != Level.LT && options.level() != Level.LTA) { throw new IllegalArgumentException("evidence extension requires LT or LTA"); }
        var initial = verify(source, limits(options), trust, evidence); if (!initial.meets(Level.T)) { throw new IOException("evidence extension requires valid trusted signature timestamps"); }
        var temporaries = new ArrayList<Path>();
        try {
            var first = temporary(target, temporaries); dss(source, first, options, completeEvidence(source, options, trust, evidence)); Path result = first;
            if (options.level() == Level.LTA) { var next = temporary(target, temporaries); incremental(first, next, options, true, null, content -> token(content, timestamps, trust, evidence, true)); result = next; }
            var report = verify(result, limits(options), trust, evidence); if (!report.meets(options.level())) { throw new IOException("extended PAdES evidence did not validate"); }
            cleanupBeforePublish(temporaries, result); ExecutionContext.checkpoint(); Files.move(result, target.toAbsolutePath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); return report;
        } catch (IOException | RuntimeException | Error failure) { cleanupAfterFailure(temporaries, failure); throw failure; }
    }

    private static byte[] cms(byte[] content, Options options, Identity identity, List<X509Certificate> certificates,
                              PdfTrust.Material trust, PdfTrust.Evidence evidence, TimestampSource timestamps) throws IOException {
        try {
            var attributes = new Hashtable<ASN1ObjectIdentifier, Attribute>();
            attributes.put(PKCSObjectIdentifiers.id_aa_signingCertificateV2, new Attribute(PKCSObjectIdentifiers.id_aa_signingCertificateV2, new DERSet(new SigningCertificateV2(new ESSCertIDv2(PdfTrust.digest(certificates.getFirst().getEncoded()))))));
            attributes.put(CMSAttributes.signingTime, new Attribute(CMSAttributes.signingTime, new DERSet(new Time(java.util.Date.from(options.time())))));
            String algorithm = switch (identity.privateKey().getAlgorithm()) { case "RSA" -> "SHA256withRSA"; case "EC", "ECDSA" -> "SHA256withECDSA"; default -> throw new IOException("unsupported PAdES private-key algorithm"); };
            var contentSigner = new JcaContentSignerBuilder(algorithm).setProvider(PdfTrust.PROVIDER).build(identity.privateKey());
            var info = new JcaSignerInfoGeneratorBuilder(new JcaDigestCalculatorProviderBuilder().setProvider(PdfTrust.PROVIDER).build())
                    .setSignedAttributeGenerator(new DefaultSignedAttributeTableGenerator(new AttributeTable(attributes))).build(contentSigner, certificates.getFirst());
            var generator = new CMSSignedDataGenerator(); generator.addSignerInfoGenerator(info); generator.addCertificates(new JcaCertStore(certificates));
            var cms = generator.generate(new CMSProcessableByteArray(content), false);
            if (options.level().ordinal() >= Level.T.ordinal()) {
                var signer = cms.getSignerInfos().getSigners().iterator().next(); byte[] token = timestamps.timestamp(PdfTrust.digest(signer.getSignature()));
                PdfTrust.timestamp(token, PdfTrust.digest(signer.getSignature()), trust, evidence, options.level().ordinal() >= Level.LT.ordinal());
                var unsigned = new Attribute(PKCSObjectIdentifiers.id_aa_signatureTimeStampToken, new DERSet(org.bouncycastle.asn1.ASN1Primitive.fromByteArray(token)));
                var updated = SignerInformation.replaceUnsignedAttributes(signer, new AttributeTable(unsigned));
                cms = CMSSignedData.replaceSigners(cms, new SignerInformationStore(updated));
            }
            return cms.getEncoded("DER");
        } catch (IOException failure) { throw failure; }
        catch (Exception failure) { throw new IOException("PAdES CMS generation failed", failure); }
    }

    private static byte[] token(byte[] content, TimestampSource timestamps, PdfTrust.Material trust, PdfTrust.Evidence evidence, boolean revocation) throws IOException {
        byte[] digest = PdfTrust.digest(content); byte[] token = Objects.requireNonNull(timestamps.timestamp(digest.clone()), "timestamp token"); PdfTrust.timestamp(token, digest, trust, evidence, revocation); return token;
    }

    @FunctionalInterface private interface Contents { byte[] sign(byte[] content) throws IOException; }
    private static void incremental(Path source, Path target, Options options, boolean timestamp, PdfSigner.CertificationPermission permission, Contents contents) throws IOException {
        byte[] encoded = read(source, options.maxInputBytes());
        try (var document = Loader.loadPDF(encoded); var reservation = new SignatureOptions(); var output = bounded(Files.newOutputStream(target), options.maxOutputBytes())) {
            if (document.isEncrypted()) { throw new IOException("PAdES rejects encrypted input"); }
            PdfSignatureInputs.passive(document);
            if (!timestamp && (!PdfSignatureInputs.signatures(document).isEmpty() || document.getDocumentCatalog().getCOSObject().containsKey(COSName.PERMS))) { throw new IOException("initial PAdES signing requires an unsigned input"); }
            var signature = new PDSignature(); signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            signature.setSubFilter(COSName.getPDFName(timestamp ? "ETSI.RFC3161" : "ETSI.CAdES.detached"));
            if (timestamp) { signature.getCOSObject().setName(COSName.TYPE, "DocTimeStamp"); }
            signature.setName(options.name()); signature.setReason(options.reason()); var calendar = new GregorianCalendar(TimeZone.getTimeZone("UTC")); calendar.setTimeInMillis(options.time().toEpochMilli()); signature.setSignDate(calendar);
            reservation.setPreferredSignatureSize(options.maxCmsBytes()); document.addSignature(signature, reservation);
            if (timestamp) {
                // Document timestamps have no visual appearance. PDFBox creates an empty widget AP by
                // default; even that AP is an appearance modification under DocMDP level 1.
                var form = document.getDocumentCatalog().getCOSObject().getCOSDictionary(COSName.ACRO_FORM);
                var fields = form == null ? null : form.getCOSArray(COSName.FIELDS);
                if (fields == null) { throw new IOException("document timestamp field was not created"); }
                for (int index = 0; index < fields.size(); index++) {
                    if (fields.getObject(index) instanceof COSDictionary field && field.getDictionaryObject(COSName.V) == signature.getCOSObject()) { field.removeItem(COSName.AP); field.setNeedToBeUpdated(true); }
                }
            }
            if (permission != null) { installCertification(document, signature, permission); }
            var external = document.saveIncrementalForExternalSigning(output); byte[] signed;
            try (var stream = external.getContent()) { signed = stream.readNBytes((int) Math.min(Integer.MAX_VALUE - 1L, options.maxOutputBytes()) + 1); }
            if (signed.length > options.maxOutputBytes()) { throw new IOException("PAdES signed-byte budget exceeded"); }
            byte[] value = contents.sign(signed); if (value.length == 0 || value.length > options.maxCmsBytes()) { throw new IOException("PAdES signature reservation exceeded"); }
            ExecutionContext.checkpoint(); external.setSignature(value);
        }
    }

    private static void installCertification(PDDocument document, PDSignature signature, PdfSigner.CertificationPermission permission) {
        int value = switch (permission) { case NO_CHANGES -> 1; case FORM_FILLING_AND_SIGNING -> 2; case ANNOTATIONS_FORM_FILLING_AND_SIGNING -> 3; };
        var parameters = new COSDictionary(); parameters.setName(COSName.TYPE, "TransformParams"); parameters.setInt(COSName.P, value); parameters.setName(COSName.V, "1.2");
        var reference = new COSDictionary(); reference.setName(COSName.TYPE, "SigRef"); reference.setName(COSName.getPDFName("TransformMethod"), "DocMDP"); reference.setItem(COSName.getPDFName("TransformParams"), parameters); reference.setItem(COSName.getPDFName("Data"), document.getDocumentCatalog());
        var references = new COSArray(); references.add(reference); signature.getCOSObject().setItem(COSName.getPDFName("Reference"), references);
        var permissions = new COSDictionary(); permissions.setItem(COSName.DOCMDP, signature); document.getDocumentCatalog().getCOSObject().setItem(COSName.PERMS, permissions); document.getDocumentCatalog().getCOSObject().setNeedToBeUpdated(true);
    }

    private static PdfTrust.Evidence completeEvidence(Path source, Options options, PdfTrust.Material trust, PdfTrust.Evidence evidence) throws IOException {
        var certificates = new ArrayList<>(evidence.certificates()); certificates.addAll(trust.intermediates());
        var ocsp = new ArrayList<>(evidence.ocspResponses()); var crls = new ArrayList<>(evidence.crls());
        byte[] bytes = read(source, options.maxInputBytes());
        try (var document = Loader.loadPDF(bytes)) {
            var old = evidence(document, PdfTrust.Evidence.EMPTY); certificates.addAll(old.certificates()); ocsp.addAll(old.ocspResponses()); crls.addAll(old.crls());
            for (var signature : PdfSignatureInputs.signatures(document)) {
                var prepared = PdfSignatureVerifier.prepare(signature.getCOSObject(), bytes, limits(options), true);
                try { var cms = new CMSSignedData(prepared.cms()); for (var cert : cms.getCertificates().getMatches(null)) { certificates.add(cert.getEncoded()); }
                    if (!"ETSI.RFC3161".equals(signature.getSubFilter())) {
                        for (var signer : cms.getSignerInfos().getSigners()) { var attrs = signer.getUnsignedAttributes(); if (attrs == null) { continue; } var attribute = attrs.get(PKCSObjectIdentifiers.id_aa_signatureTimeStampToken); if (attribute == null) { continue; }
                            var token = new CMSSignedData(attribute.getAttrValues().getObjectAt(0).toASN1Primitive().getEncoded("DER")); for (var cert : token.getCertificates().getMatches(null)) { certificates.add(cert.getEncoded()); }
                        }
                    }
                } catch (org.bouncycastle.cms.CMSException failure) { throw new IOException("malformed CMS evidence", failure); }
            }
        }
        var unique = new java.util.LinkedHashMap<String, byte[]>(); for (var cert : certificates) { unique.put(HexFormat.of().formatHex(PdfTrust.digest(cert)), cert); }
        return new PdfTrust.Evidence(new ArrayList<>(unique.values()), unique(ocsp), unique(crls));
    }

    private static void dss(Path source, Path target, Options options, PdfTrust.Evidence evidence) throws IOException {
        byte[] bytes = read(source, options.maxInputBytes());
        try (var document = Loader.loadPDF(bytes); var output = bounded(Files.newOutputStream(target), options.maxOutputBytes())) {
            var existing = document.getDocumentCatalog().getCOSObject().getCOSDictionary(COSName.getPDFName("DSS"));
            var dss = existing == null ? new COSDictionary() : new COSDictionary(existing);
            var certs = streams(document, existing == null ? null : existing.getCOSArray(COSName.getPDFName("Certs")), evidence.certificates());
            var ocsp = streams(document, existing == null ? null : existing.getCOSArray(COSName.getPDFName("OCSPs")), evidence.ocspResponses());
            var crls = streams(document, existing == null ? null : existing.getCOSArray(COSName.getPDFName("CRLs")), evidence.crls());
            dss.setItem(COSName.getPDFName("Certs"), certs); dss.setItem(COSName.getPDFName("OCSPs"), ocsp); dss.setItem(COSName.getPDFName("CRLs"), crls);
            var oldVri = existing == null ? null : existing.getCOSDictionary(COSName.getPDFName("VRI"));
            var vris = oldVri == null ? new COSDictionary() : new COSDictionary(oldVri);
            for (var signature : PdfSignatureInputs.signatures(document)) {
                var prepared = PdfSignatureVerifier.prepare(signature.getCOSObject(), bytes, limits(options), true);
                var vri = new COSDictionary(); vri.setItem(COSName.CERT, certs); vri.setItem(COSName.getPDFName("OCSP"), ocsp); vri.setItem(COSName.getPDFName("CRL"), crls);
                try { var key = COSName.getPDFName(HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-1").digest(prepared.cms()))); if (!vris.containsKey(key)) { vris.setItem(key, vri); } }
                catch (GeneralSecurityException failure) { throw new IOException("DSS identifier failed", failure); }
            }
            dss.setItem(COSName.getPDFName("VRI"), vris); var catalog = document.getDocumentCatalog().getCOSObject(); catalog.setItem(COSName.getPDFName("DSS"), dss); catalog.setNeedToBeUpdated(true);
            document.saveIncremental(output);
        }
    }
    private static COSArray streams(PDDocument document, COSArray existing, List<byte[]> values) throws IOException {
        var array = new COSArray(); var known = new java.util.HashSet<String>();
        if (existing != null) { for (int index = 0; index < existing.size(); index++) {
            if (!(existing.getObject(index) instanceof COSStream stream)) { throw new IOException("malformed existing DSS evidence stream"); }
            byte[] encoded; try (var input = stream.createInputStream()) { encoded = input.readNBytes(4 * 1_048_576 + 1); } if (encoded.length > 4 * 1_048_576) { throw new IOException("existing DSS evidence byte budget exceeded"); }
            known.add(HexFormat.of().formatHex(PdfTrust.digest(encoded))); array.add(existing.get(index));
        } }
        for (var value : values) { ExecutionContext.checkpoint(); if (!known.add(HexFormat.of().formatHex(PdfTrust.digest(value)))) { continue; } var stream = document.getDocument().createCOSStream(); try (var output = stream.createOutputStream(COSName.FLATE_DECODE)) { output.write(value); } array.add(stream); } return array;
    }

    /** Validates byte integrity, explicit trust, trusted timestamps, DSS revocation and DocMDP revisions.
     * B/T reports distinguish a valid signature from absent long-term evidence. No trust is inferred from DSS.
     */
    public static Report verify(Path source, PdfSignatureVerifier.Limits limits, PdfTrust.Material trust, PdfTrust.Evidence externalEvidence) throws IOException {
        Objects.requireNonNull(limits, "limits"); Objects.requireNonNull(trust, "trust"); Objects.requireNonNull(externalEvidence, "externalEvidence");
        byte[] bytes = read(source, limits.maxInputBytes()); var results = new ArrayList<Signature>();
        try (var document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted()) { throw new IOException("encrypted PAdES validation is unsupported"); }
            PdfSignatureInputs.passive(document);
            var signatures = new ArrayList<>(PdfSignatureInputs.signatures(document)); signatures.sort(Comparator.comparingLong(item -> { int[] range = item.getByteRange(); return range.length == 4 ? (long) range[2] + range[3] : Long.MAX_VALUE; }));
            if (signatures.size() > limits.maxSignatures()) { throw new IOException("PAdES signature count budget exceeded"); }
            var dss = evidence(document, externalEvidence); long total = 0; long previous = 0; boolean lastTimestamp = false; Instant latestTrusted = Instant.MIN;
            for (var signature : signatures) {
                ExecutionContext.checkpoint(); var prepared = PdfSignatureVerifier.prepare(signature.getCOSObject(), bytes, limits, true);
                if (prepared.end() <= previous) { throw new IOException("PAdES signature revision order is ambiguous"); } previous = prepared.end();
                total += (long) prepared.first() + prepared.end() - prepared.second(); if (total > limits.maxSignedBytes()) { throw new IOException("PAdES signed byte budget exceeded"); }
                byte[] content = signedBytes(bytes, prepared); boolean timestamp = "ETSI.RFC3161".equals(signature.getSubFilter());
                try {
                    if (timestamp) {
                        if (!"DocTimeStamp".equals(signature.getCOSObject().getNameAsString(COSName.TYPE))) { throw new PdfTrust.ValidationException(PdfTrust.Failure.INVALID_TIMESTAMP); }
                        var token = PdfTrust.timestamp(prepared.cms(), PdfTrust.digest(content), trust, dss, true);
                        if (token.time().isBefore(latestTrusted)) { throw new PdfTrust.ValidationException(PdfTrust.Failure.INVALID_TIMESTAMP); } latestTrusted = token.time();
                        lastTimestamp = prepared.end() == bytes.length;
                        results.add(new Signature(results.size(), prepared.end(), true, Level.LTA, true, true, token.time(), ""));
                    } else {
                        if (!"ETSI.CAdES.detached".equals(signature.getSubFilter())) { throw new PdfTrust.ValidationException(PdfTrust.Failure.INVALID_SIGNATURE); }
                        var approved = approval(content, prepared, trust, dss, externalEvidence, results.size()); results.add(approved);
                        if (approved.trustedTime() != null && approved.trustedTime().isAfter(latestTrusted)) { latestTrusted = approved.trustedTime(); }
                    }
                } catch (PdfTrust.ValidationException failure) {
                    boolean integrity = failure.failure() != PdfTrust.Failure.INVALID_SIGNATURE && !(timestamp && failure.failure() == PdfTrust.Failure.INVALID_TIMESTAMP);
                    results.add(new Signature(results.size(), prepared.end(), timestamp, Level.NONE, integrity, false, null, failure.failure().name()));
                }
            }
            if (lastTimestamp) { var updated = new ArrayList<Signature>(); for (var item : results) { updated.add(!item.documentTimestamp() && item.achieved() == Level.LT ? new Signature(item.index(), item.revisionBytes(), false, Level.LTA, item.integrityValid(), item.trusted(), item.trustedTime(), item.failure()) : item); } results = updated; }
        }
        return new Report(bytes.length, results, PdfDocMdp.verify(source, limits));
    }

    private static Signature approval(byte[] content, PdfSignatureVerifier.Prepared prepared, PdfTrust.Material trust,
                                      PdfTrust.Evidence dss, PdfTrust.Evidence external, int index) throws IOException {
        try {
            var cms = new CMSSignedData(new CMSProcessableByteArray(content), prepared.cms());
            if (!cms.isDetachedSignature() || cms.getSignerInfos().size() != 1) { throw new PdfTrust.ValidationException(PdfTrust.Failure.INVALID_SIGNATURE); }
            var signer = cms.getSignerInfos().getSigners().iterator().next();
            if (!"2.16.840.1.101.3.4.2.1".equals(signer.getDigestAlgOID())) { throw new PdfTrust.ValidationException(PdfTrust.Failure.INVALID_SIGNATURE); }
            var matches = cms.getCertificates().getMatches(null).stream().filter(holder -> signer.getSID().match(holder)).toList(); if (matches.size() != 1) { throw new PdfTrust.ValidationException(PdfTrust.Failure.INVALID_SIGNATURE); }
            var leaf = PdfTrust.certificate(matches.getFirst().getEncoded());
            var keyUsage = leaf.getKeyUsage(); if (keyUsage != null && !keyUsage[0]) { throw new PdfTrust.ValidationException(PdfTrust.Failure.INVALID_SIGNATURE); }
            if (!signer.verify(new JcaSimpleSignerInfoVerifierBuilder().setProvider(PdfTrust.PROVIDER).build(leaf))) { throw new PdfTrust.ValidationException(PdfTrust.Failure.INVALID_SIGNATURE); }
            var attrs = signer.getSignedAttributes(); var signingCertificate = attrs == null ? null : attrs.get(PKCSObjectIdentifiers.id_aa_signingCertificateV2);
            if (signingCertificate == null || signingCertificate.getAttrValues().size() != 1) { throw new PdfTrust.ValidationException(PdfTrust.Failure.INVALID_SIGNATURE); }
            var ids = SigningCertificateV2.getInstance(signingCertificate.getAttrValues().getObjectAt(0)).getCerts();
            if (ids.length == 0 || !org.bouncycastle.asn1.nist.NISTObjectIdentifiers.id_sha256.equals(ids[0].getHashAlgorithm().getAlgorithm()) || !MessageDigest.isEqual(ids[0].getCertHash(), PdfTrust.digest(leaf.getEncoded()))) { throw new PdfTrust.ValidationException(PdfTrust.Failure.INVALID_SIGNATURE); }
            Instant time = trust.validationTime(); Level level = Level.B;
            var unsigned = signer.getUnsignedAttributes(); var timestamp = unsigned == null ? null : unsigned.get(PKCSObjectIdentifiers.id_aa_signatureTimeStampToken);
            byte[] token = null;
            if (timestamp != null) {
                if (timestamp.getAttrValues().size() != 1) { throw new PdfTrust.ValidationException(PdfTrust.Failure.INVALID_TIMESTAMP); }
                token = timestamp.getAttrValues().getObjectAt(0).toASN1Primitive().getEncoded("DER"); time = PdfTrust.timestamp(token, PdfTrust.digest(signer.getSignature()), trust, external, false).time(); level = Level.T;
            }
            var certificates = PdfTrust.certificates(cms); PdfTrust.validate(leaf, certificates, trust, external, time, false);
            String failure = "";
            if (token != null && (!dss.ocspResponses().isEmpty() || !dss.crls().isEmpty())) {
                try { PdfTrust.validate(leaf, certificates, trust, dss, time, true); PdfTrust.timestamp(token, PdfTrust.digest(signer.getSignature()), trust, dss, true); level = Level.LT; }
                catch (PdfTrust.ValidationException invalidEvidence) { if (invalidEvidence.failure() == PdfTrust.Failure.REVOKED_CERTIFICATE || invalidEvidence.failure() == PdfTrust.Failure.INVALID_REVOCATION_EVIDENCE) { throw invalidEvidence; } failure = invalidEvidence.failure().name(); }
            }
            return new Signature(index, prepared.end(), false, level, true, true, token == null ? null : time, failure);
        } catch (PdfTrust.ValidationException failure) { throw failure; }
        catch (Exception failure) { throw new PdfTrust.ValidationException(PdfTrust.Failure.INVALID_SIGNATURE, failure); }
    }

    private static PdfTrust.Evidence evidence(PDDocument document, PdfTrust.Evidence external) throws IOException {
        var value = document.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.getPDFName("DSS")); if (value == null) { return PdfTrust.Evidence.EMPTY; }
        if (!(value instanceof COSDictionary dss)) { throw new IOException("malformed DSS dictionary"); }
        var certificates = new ArrayList<>(external.certificates()); certificates.addAll(derStreams(dss, "Certs", 256, 65_536));
        return new PdfTrust.Evidence(certificates, derStreams(dss, "OCSPs", 128, 1_048_576), derStreams(dss, "CRLs", 128, 4 * 1_048_576));
    }
    private static List<byte[]> derStreams(COSDictionary dictionary, String key, int maxCount, int maxBytes) throws IOException {
        var value = dictionary.getDictionaryObject(COSName.getPDFName(key)); if (value == null) { return List.of(); }
        if (!(value instanceof COSArray array) || array.size() > maxCount) { throw new IOException("DSS evidence count budget exceeded"); }
        var result = new ArrayList<byte[]>(); long total = 0;
        for (int index = 0; index < array.size(); index++) { ExecutionContext.checkpoint(); if (!(array.getObject(index) instanceof COSStream stream)) { throw new IOException("DSS evidence is not a stream"); }
            byte[] bytes; try (var input = stream.createInputStream()) { bytes = input.readNBytes(maxBytes + 1); } if (bytes.length > maxBytes || bytes.length > 16L * 1_048_576 - total) { throw new IOException("DSS evidence byte budget exceeded"); } total += bytes.length; result.add(bytes);
        } return result;
    }
    private static byte[] signedBytes(byte[] bytes, PdfSignatureVerifier.Prepared prepared) {
        byte[] result = new byte[prepared.first() + prepared.end() - prepared.second()]; System.arraycopy(bytes, 0, result, 0, prepared.first()); System.arraycopy(bytes, prepared.second(), result, prepared.first(), prepared.end() - prepared.second()); return result;
    }
    private static List<byte[]> unique(List<byte[]> values) throws IOException { var result = new java.util.LinkedHashMap<String, byte[]>(); for (var value : values) { result.put(HexFormat.of().formatHex(PdfTrust.digest(value)), value); } return new ArrayList<>(result.values()); }
    private static PdfSignatureVerifier.Limits limits(Options options) { return new PdfSignatureVerifier.Limits((int) Math.min(Integer.MAX_VALUE - 8L, options.maxOutputBytes()), 64, options.maxCmsBytes(), 512L * 1_048_576); }
    private static byte[] read(Path source, int maximum) throws IOException { ExecutionContext.checkpoint(); byte[] bytes; try (var input = Files.newInputStream(source)) { bytes = input.readNBytes(maximum + 1); } if (bytes.length > maximum) { throw new IOException("PAdES input budget exceeded"); } return bytes; }
    private static Path temporary(Path target, List<Path> values) throws IOException { Objects.requireNonNull(target, "target"); var result = Files.createTempFile(target.toAbsolutePath().getParent(), ".export4j-pades-", ".pdf"); values.add(result); return result; }
    private static void cleanup(List<Path> values) throws IOException { IOException failure = null; for (var value : values) { try { Files.deleteIfExists(value); } catch (IOException error) { if (failure == null) { failure = error; } else { failure.addSuppressed(error); } } } if (failure != null) { throw failure; } }
    private static void cleanupBeforePublish(List<Path> values, Path result) throws IOException { for (var value : values) { if (!value.equals(result)) { Files.deleteIfExists(value); } } }
    private static void cleanupAfterFailure(List<Path> values, Throwable failure) { try { cleanup(values); } catch (IOException cleanup) { failure.addSuppressed(cleanup); } }
    private static OutputStream bounded(OutputStream output, long maximum) {
        return new java.io.FilterOutputStream(output) {
            private long bytes;
            private void accept(int count) throws IOException { ExecutionContext.checkpoint(); if (count > maximum - bytes) { throw new IOException("PAdES output budget exceeded"); } bytes += count; }
            @Override public void write(int value) throws IOException { accept(1); out.write(value); }
            @Override public void write(byte[] values, int offset, int length) throws IOException { accept(length); out.write(values, offset, length); }
        };
    }
}
