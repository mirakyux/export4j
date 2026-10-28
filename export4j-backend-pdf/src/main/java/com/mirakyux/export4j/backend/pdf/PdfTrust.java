// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.ocsp.BasicOCSPResp;
import org.bouncycastle.cert.ocsp.CertificateStatus;
import org.bouncycastle.cert.ocsp.OCSPResp;
import org.bouncycastle.cert.ocsp.RespID;
import org.bouncycastle.cert.ocsp.RevokedStatus;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.tsp.TimeStampToken;
import com.mirakyux.export4j.model.ExecutionContext;

/** Explicit offline trust inputs for PDF signatures. Neither AIA, OCSP/CRL URLs, system trust stores nor
 * network discovery are consulted. DER collections are bounded, immutable snapshots.
 */
public final class PdfTrust {
    private PdfTrust() { }
    static final BouncyCastleProvider PROVIDER = new BouncyCastleProvider();

    public record Material(List<byte[]> anchors, List<byte[]> intermediates, Instant validationTime,
                           Duration maxEvidenceAge, Duration clockSkew) {
        public Material {
            anchors = snapshot(anchors, 64, 65_536); intermediates = snapshot(intermediates, 256, 65_536);
            Objects.requireNonNull(validationTime, "validationTime"); Objects.requireNonNull(maxEvidenceAge, "maxEvidenceAge"); Objects.requireNonNull(clockSkew, "clockSkew");
            if (anchors.isEmpty() || maxEvidenceAge.isNegative() || maxEvidenceAge.isZero() || maxEvidenceAge.compareTo(Duration.ofDays(366)) > 0
                    || clockSkew.isNegative() || clockSkew.compareTo(Duration.ofMinutes(5)) > 0) { throw new IllegalArgumentException("invalid explicit PDF trust policy"); }
        }
        @Override public List<byte[]> anchors() { return copy(anchors); }
        @Override public List<byte[]> intermediates() { return copy(intermediates); }
    }
    /** Full DER OCSPResponse objects and full direct issuer CRLs; delta/indirect CRLs are unsupported. */
    public record Evidence(List<byte[]> certificates, List<byte[]> ocspResponses, List<byte[]> crls) {
        public static final Evidence EMPTY = new Evidence(List.of(), List.of(), List.of());
        public Evidence { certificates = snapshot(certificates, 256, 65_536); ocspResponses = snapshot(ocspResponses, 128, 1_048_576); crls = snapshot(crls, 128, 4 * 1_048_576); }
        @Override public List<byte[]> certificates() { return copy(certificates); }
        @Override public List<byte[]> ocspResponses() { return copy(ocspResponses); }
        @Override public List<byte[]> crls() { return copy(crls); }
    }
    public enum Failure { UNTRUSTED_CHAIN, EXPIRED_CERTIFICATE, UNSUPPORTED_KEY, INVALID_SIGNATURE, REVOKED_CERTIFICATE,
        MISSING_REVOCATION_EVIDENCE, STALE_REVOCATION_EVIDENCE, INVALID_REVOCATION_EVIDENCE, INVALID_TIMESTAMP, FUTURE_TIMESTAMP }
    public static final class ValidationException extends IOException {
        private static final long serialVersionUID = 1L;
        private final Failure failure;
        ValidationException(Failure failure) { super("PDF trust validation failed: " + failure); this.failure = failure; }
        ValidationException(Failure failure, Throwable cause) { super("PDF trust validation failed: " + failure, cause); this.failure = failure; }
        public Failure failure() { return failure; }
    }
    public record Chain(List<String> certificateSha256, Instant evaluatedAt, boolean revocationChecked) {
        public Chain { certificateSha256 = List.copyOf(certificateSha256); }
    }
    record Timestamp(Instant time, Chain chain) { }

    static List<byte[]> snapshot(List<byte[]> values, int maxCount, int maxEach) {
        Objects.requireNonNull(values, "DER resources"); if (values.size() > maxCount) { throw new IllegalArgumentException("DER resource count budget exceeded"); }
        var result = new ArrayList<byte[]>(); long total = 0;
        for (var value : values) { Objects.requireNonNull(value, "DER resource"); if (value.length == 0 || value.length > maxEach || value.length > 16L * 1_048_576 - total) { throw new IllegalArgumentException("DER resource byte budget exceeded"); } total += value.length; result.add(value.clone()); }
        return List.copyOf(result);
    }
    private static List<byte[]> copy(List<byte[]> values) { return values.stream().map(byte[]::clone).toList(); }
    static X509Certificate certificate(byte[] bytes) throws IOException {
        try {
            var input = new ByteArrayInputStream(bytes); var cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
            if (input.available() != 0 || !Arrays.equals(bytes, cert.getEncoded())) { throw new IOException("certificate is not one DER object"); } return cert;
        } catch (GeneralSecurityException failure) { throw new IOException("invalid DER certificate", failure); }
    }
    static List<X509Certificate> certificates(List<byte[]> values) throws IOException { var result = new ArrayList<X509Certificate>(); for (var value : values) { result.add(certificate(value)); } return result; }
    static List<X509Certificate> certificates(CMSSignedData cms) throws IOException {
        var result = new ArrayList<X509Certificate>();
        for (var value : cms.getCertificates().getMatches(null)) { if (result.size() >= 256) { throw new IOException("CMS certificate budget exceeded"); } result.add(certificate(value.getEncoded())); } return result;
    }

    static Chain validate(X509Certificate leaf, List<X509Certificate> supplied, Material material, Evidence evidence, Instant at, boolean revocation) throws IOException {
        ExecutionContext.checkpoint();
        var pool = new ArrayList<>(supplied); pool.addAll(certificates(material.intermediates)); pool.addAll(certificates(evidence.certificates));
        var anchors = certificates(material.anchors); pool.addAll(anchors);
        List<X509Certificate> path = path(leaf, pool, anchors, new ArrayList<>(), new int[1]);
        if (path == null) { throw new ValidationException(Failure.UNTRUSTED_CHAIN); }
        try {
            for (var cert : path) { cert.checkValidity(Date.from(at)); key(cert); }
            var trusted = path.getLast();
            var parameters = new PKIXParameters(Set.of(new TrustAnchor(trusted, null))); parameters.setRevocationEnabled(false); parameters.setDate(Date.from(at));
            var certPath = CertificateFactory.getInstance("X.509").generateCertPath(path.subList(0, path.size() - 1));
            CertPathValidator.getInstance("PKIX").validate(certPath, parameters);
        } catch (java.security.cert.CertificateExpiredException | java.security.cert.CertificateNotYetValidException failure) { throw new ValidationException(Failure.EXPIRED_CERTIFICATE, failure); }
        catch (GeneralSecurityException failure) { throw new ValidationException(Failure.UNTRUSTED_CHAIN, failure); }
        boolean checked = revocation || !evidence.ocspResponses.isEmpty() || !evidence.crls.isEmpty();
        if (checked) { for (int index = 0; index < path.size() - 1; index++) { revocation(path.get(index), path.get(index + 1), material, evidence, at); } }
        var fingerprints = new ArrayList<String>();
        try { for (var cert : path) { fingerprints.add(HexFormat.of().formatHex(digest(cert.getEncoded()))); } }
        catch (GeneralSecurityException failure) { throw new IOException("certificate fingerprint failed", failure); }
        return new Chain(fingerprints, at, checked);
    }

    private static List<X509Certificate> path(X509Certificate current, List<X509Certificate> pool, List<X509Certificate> anchors,
                                             List<X509Certificate> previous, int[] attempts) throws IOException {
        ExecutionContext.checkpoint(); if (++attempts[0] > 4096) { throw new IOException("certificate path search budget exceeded"); } if (previous.size() >= 16 || previous.contains(current)) { return null; }
        var candidate = new ArrayList<>(previous); candidate.add(current);
        if (anchors.contains(current)) { return candidate; }
        for (var issuer : pool) {
            if (!current.getIssuerX500Principal().equals(issuer.getSubjectX500Principal()) || current.equals(issuer)) { continue; }
            try { current.verify(issuer.getPublicKey()); }
            catch (GeneralSecurityException failure) { continue; }
            var result = path(issuer, pool, anchors, candidate, attempts); if (result != null) { return result; }
        }
        return null;
    }

    static void key(X509Certificate certificate) throws ValidationException {
        var key = certificate.getPublicKey();
        if (!(key instanceof java.security.interfaces.RSAPublicKey rsa && rsa.getModulus().bitLength() >= 2048
                || key instanceof java.security.interfaces.ECPublicKey ec && ec.getParams().getCurve().getField().getFieldSize() >= 256) || !strongSignature(certificate.getSigAlgOID())) { throw new ValidationException(Failure.UNSUPPORTED_KEY); }
    }

    static byte[] digest(byte[] data) throws IOException {
        try { return MessageDigest.getInstance("SHA-256").digest(data); } catch (GeneralSecurityException failure) { throw new IOException("SHA-256 is unavailable", failure); }
    }

    static Timestamp timestamp(byte[] encoded, byte[] expectedDigest, Material trust, Evidence evidence, boolean revocation) throws IOException {
        if (encoded.length == 0 || encoded.length > 1_048_576) { throw new ValidationException(Failure.INVALID_TIMESTAMP); }
        try {
            var token = new TimeStampToken(new CMSSignedData(encoded)); var info = token.getTimeStampInfo();
            if (!Arrays.equals(token.getEncoded("DER"), encoded)) { throw new ValidationException(Failure.INVALID_TIMESTAMP); }
            if (token.toCMSSignedData().getSignerInfos().getSigners().stream().anyMatch(signer -> !"2.16.840.1.101.3.4.2.1".equals(signer.getDigestAlgOID()))) { throw new ValidationException(Failure.INVALID_TIMESTAMP); }
            if (!NISTObjectIdentifiers.id_sha256.equals(info.getMessageImprintAlgOID()) || !MessageDigest.isEqual(expectedDigest, info.getMessageImprintDigest())) { throw new ValidationException(Failure.INVALID_TIMESTAMP); }
            Instant time = info.getGenTime().toInstant();
            if (time.isAfter(trust.validationTime.plus(trust.clockSkew))) { throw new ValidationException(Failure.FUTURE_TIMESTAMP); }
            var matches = token.getCertificates().getMatches(null).stream().filter(holder -> token.getSID().match(holder)).toList(); if (matches.size() != 1) { throw new ValidationException(Failure.INVALID_TIMESTAMP); }
            var holder = matches.getFirst(); var cert = certificate(holder.getEncoded());
            if (!List.of("1.3.6.1.5.5.7.3.8").equals(cert.getExtendedKeyUsage()) || cert.getCriticalExtensionOIDs() == null || !cert.getCriticalExtensionOIDs().contains("2.5.29.37")) { throw new ValidationException(Failure.INVALID_TIMESTAMP); }
            token.validate(new JcaSimpleSignerInfoVerifierBuilder().setProvider(PROVIDER).build(cert));
            var certificates = new ArrayList<X509Certificate>(); for (var value : token.getCertificates().getMatches(null)) { certificates.add(certificate(value.getEncoded())); }
            return new Timestamp(time, validate(cert, certificates, trust, evidence, time, revocation));
        } catch (ValidationException failure) { throw failure; }
        catch (Exception failure) { throw new ValidationException(Failure.INVALID_TIMESTAMP, failure); }
    }

    private static void revocation(X509Certificate certificate, X509Certificate issuer, Material material, Evidence evidence, Instant at) throws IOException {
        boolean good = false, stale = false, invalid = false;
        try {
            var calculators = new JcaDigestCalculatorProviderBuilder().setProvider(PROVIDER).build(); var issuerHolder = new JcaX509CertificateHolder(issuer);
            for (var encoded : evidence.ocspResponses) {
                ExecutionContext.checkpoint();
                var response = new OCSPResp(encoded); if (!Arrays.equals(response.getEncoded(), encoded) || response.getStatus() != OCSPResp.SUCCESSFUL || !(response.getResponseObject() instanceof BasicOCSPResp basic)) { invalid = true; continue; }
                for (var single : basic.getResponses()) {
                    if (!single.getCertID().getSerialNumber().equals(certificate.getSerialNumber()) || !single.getCertID().matchesIssuer(issuerHolder, calculators)) { continue; }
                    if (!strongSignature(basic.getSignatureAlgOID().getId()) || !authorized(basic, issuer, at) || !basic.getCriticalExtensionOIDs().isEmpty() || basic.getProducedAt().toInstant().isAfter(material.validationTime.plus(material.clockSkew))
                            || basic.getProducedAt().toInstant().isBefore(single.getThisUpdate().toInstant().minus(material.clockSkew))) { invalid = true; continue; }
                    var status = single.getCertStatus();
                    if (status instanceof RevokedStatus revoked && !revoked.getRevocationTime().toInstant().isAfter(at)) { throw new ValidationException(Failure.REVOKED_CERTIFICATE); }
                    if (!fresh(single.getThisUpdate(), single.getNextUpdate(), at, material)) { stale = true; continue; }
                    if (status == CertificateStatus.GOOD || status instanceof RevokedStatus) { good = true; } else { invalid = true; }
                }
            }
            for (var encoded : evidence.crls) {
                ExecutionContext.checkpoint(); var input = new ByteArrayInputStream(encoded);
                var crl = (X509CRL) CertificateFactory.getInstance("X.509").generateCRL(input);
                if (!crl.getIssuerX500Principal().equals(issuer.getSubjectX500Principal())) { continue; }
                if (input.available() != 0 || !Arrays.equals(crl.getEncoded(), encoded) || crl.getExtensionValue("2.5.29.27") != null || crl.getExtensionValue("2.5.29.28") != null || crl.hasUnsupportedCriticalExtension() || !strongSignature(crl.getSigAlgOID())) { invalid = true; continue; }
                try { crl.verify(issuer.getPublicKey()); } catch (GeneralSecurityException failure) { invalid = true; continue; }
                var usage = issuer.getKeyUsage(); if (usage != null && (usage.length <= 6 || !usage[6])) { invalid = true; continue; }
                var entry = crl.getRevokedCertificate(certificate); if (entry != null && !entry.getRevocationDate().toInstant().isAfter(at)) { throw new ValidationException(Failure.REVOKED_CERTIFICATE); }
                if (!fresh(crl.getThisUpdate(), crl.getNextUpdate(), at, material)) { stale = true; continue; } good = true;
            }
        } catch (ValidationException failure) { throw failure; }
        catch (Exception failure) { throw new ValidationException(Failure.INVALID_REVOCATION_EVIDENCE, failure); }
        if (!good) { throw new ValidationException(invalid ? Failure.INVALID_REVOCATION_EVIDENCE : stale ? Failure.STALE_REVOCATION_EVIDENCE : Failure.MISSING_REVOCATION_EVIDENCE); }
    }

    private static boolean fresh(Date thisUpdate, Date nextUpdate, Instant at, Material material) {
        return thisUpdate != null && nextUpdate != null && nextUpdate.after(thisUpdate) && !thisUpdate.toInstant().isAfter(at.plus(material.clockSkew))
                && !nextUpdate.toInstant().isBefore(at.minus(material.clockSkew)) && !thisUpdate.toInstant().isBefore(at.minus(material.maxEvidenceAge));
    }
    private static boolean strongSignature(String oid) { return Set.of("1.2.840.113549.1.1.11", "1.2.840.113549.1.1.12", "1.2.840.113549.1.1.13", "1.2.840.10045.4.3.2", "1.2.840.10045.4.3.3", "1.2.840.10045.4.3.4").contains(oid); }

    private static boolean authorized(BasicOCSPResp response, X509Certificate issuer, Instant at) throws Exception {
        var candidates = new ArrayList<X509Certificate>(); candidates.add(issuer);
        var converter = new JcaX509CertificateConverter().setProvider(PROVIDER);
        for (var cert : response.getCerts()) { candidates.add(converter.getCertificate(cert)); }
        for (var cert : candidates) {
            var holder = new JcaX509CertificateHolder(cert);
            var byName = new RespID(holder.getSubject());
            var calculator = new JcaDigestCalculatorProviderBuilder().setProvider(PROVIDER).build().get(RespID.HASH_SHA1);
            var byKey = new RespID(holder.getSubjectPublicKeyInfo(), calculator);
            if (!response.getResponderId().equals(byName) && !response.getResponderId().equals(byKey)) { continue; }
            try {
                cert.checkValidity(Date.from(at));
                key(cert); if (cert.hasUnsupportedCriticalExtension()) { continue; }
                if (!cert.equals(issuer)) {
                    if (!cert.getIssuerX500Principal().equals(issuer.getSubjectX500Principal()) || cert.getExtendedKeyUsage() == null || !cert.getExtendedKeyUsage().contains("1.3.6.1.5.5.7.3.9")) { continue; }
                    cert.verify(issuer.getPublicKey()); var usage = cert.getKeyUsage(); if (usage != null && !usage[0]) { continue; }
                }
                if (response.isSignatureValid(new JcaContentVerifierProviderBuilder().setProvider(PROVIDER).build(cert))) { return true; }
            } catch (GeneralSecurityException failure) { continue; }
        }
        return false;
    }
}
