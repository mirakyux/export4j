// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v2CRLBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cert.ocsp.CertificateID;
import org.bouncycastle.cert.ocsp.CertificateStatus;
import org.bouncycastle.cert.ocsp.OCSPRespBuilder;
import org.bouncycastle.cert.ocsp.RespID;
import org.bouncycastle.cert.ocsp.jcajce.JcaBasicOCSPRespBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class PdfTrustTest {
    private static final Instant AT = Instant.parse("2026-11-11T00:00:00Z");
    private static final Instant VALID_UNTIL = Instant.parse("2028-01-01T00:00:00Z");
    private static KeyPair rootKey, intermediateKey;
    private static X509Certificate root, expired, valid, nonCa, weak, leaf;

    @BeforeAll static void certificates() throws Exception {
        rootKey = pair(); intermediateKey = pair();
        root = certificate("Root", rootKey, "Root", rootKey, 1, true, VALID_UNTIL, "SHA256withECDSA");
        expired = certificate("Intermediate", intermediateKey, "Root", rootKey, 2, true, AT.minus(Duration.ofDays(1)), "SHA256withECDSA");
        valid = certificate("Intermediate", intermediateKey, "Root", rootKey, 3, true, VALID_UNTIL, "SHA256withECDSA");
        nonCa = certificate("Intermediate", intermediateKey, "Root", rootKey, 4, false, VALID_UNTIL, "SHA256withECDSA");
        weak = certificate("Intermediate", intermediateKey, "Root", rootKey, 6, true, VALID_UNTIL, "SHA1withECDSA");
        leaf = certificate("Signer", pair(), "Intermediate", intermediateKey, 5, false, VALID_UNTIL, "SHA256withECDSA");
    }

    @Test void allCandidatePathsAreFullyValidatedRegardlessOfInvalidIntermediateOrder() throws Exception {
        for (var rejected : List.of(expired, nonCa, weak)) {
            for (var ordered : List.of(List.of(rejected, valid), List.of(valid, rejected))) {
                var chain = PdfTrust.validate(leaf, List.of(), material(ordered), PdfTrust.Evidence.EMPTY, AT, false);
                assertEquals(fingerprints(leaf, valid, root), chain.certificateSha256());
                assertEquals(AT, chain.evaluatedAt()); assertFalse(chain.revocationChecked());
            }
        }
    }

    @Test void suppliedAndEvidenceCertificatesAndAlternativeAnchorsParticipateInTheSearch() throws Exception {
        var evidence = new PdfTrust.Evidence(encoded(List.of(valid)), List.of(), List.of());
        var chain = PdfTrust.validate(leaf, List.of(nonCa), material(List.of(expired)), evidence, AT, false);
        assertEquals(fingerprints(leaf, valid, root), chain.certificateSha256());
        var expiredRoot = certificate("Root", rootKey, "Root", rootKey, 7, true, AT.minusSeconds(1), "SHA256withECDSA");
        var trust = new PdfTrust.Material(encoded(List.of(expiredRoot, root)), encoded(List.of(valid)), AT, Duration.ofDays(7), Duration.ZERO);
        assertEquals(fingerprints(leaf, valid, root), PdfTrust.validate(leaf, List.of(), trust, PdfTrust.Evidence.EMPTY, AT, false).certificateSha256());
    }

    @Test void noValidPathPreservesTheExistingFailureCategories() throws Exception {
        assertFailure(PdfTrust.Failure.EXPIRED_CERTIFICATE, List.of(expired), PdfTrust.Evidence.EMPTY, false);
        assertFailure(PdfTrust.Failure.UNTRUSTED_CHAIN, List.of(nonCa), PdfTrust.Evidence.EMPTY, false);
        assertFailure(PdfTrust.Failure.UNSUPPORTED_KEY, List.of(weak), PdfTrust.Evidence.EMPTY, false);
        assertFailure(PdfTrust.Failure.UNTRUSTED_CHAIN, List.of(), PdfTrust.Evidence.EMPTY, false);
        assertFailure(PdfTrust.Failure.EXPIRED_CERTIFICATE, List.of(expired, nonCa), PdfTrust.Evidence.EMPTY, false);
        assertFailure(PdfTrust.Failure.UNTRUSTED_CHAIN, List.of(nonCa, expired), PdfTrust.Evidence.EMPTY, false);
    }

    @Test void revocationIsEvaluatedForEachCandidateUsingItsActualCertificateSerialsAndIssuers() throws Exception {
        var revoked = certificate("Intermediate", intermediateKey, "Root", rootKey, 8, true, VALID_UNTIL, "SHA256withECDSA");
        var evidence = new PdfTrust.Evidence(List.of(), List.of(), List.of(
                crl(root, rootKey, List.of(revoked), AT.minusSeconds(60), AT.plusSeconds(60)),
                crl(valid, intermediateKey, List.of(), AT.minusSeconds(60), AT.plusSeconds(60))));
        var chain = PdfTrust.validate(leaf, List.of(), material(List.of(revoked, valid)), evidence, AT, false);
        assertEquals(fingerprints(leaf, valid, root), chain.certificateSha256()); assertTrue(chain.revocationChecked());
        var revokedLeaf = new PdfTrust.Evidence(List.of(), List.of(), List.of(
                crl(root, rootKey, List.of(), AT.minusSeconds(60), AT.plusSeconds(60)),
                crl(valid, intermediateKey, List.of(leaf), AT.minusSeconds(60), AT.plusSeconds(60))));
        assertFailure(PdfTrust.Failure.REVOKED_CERTIFICATE, List.of(revoked, valid), revokedLeaf, true);
        assertFailure(PdfTrust.Failure.MISSING_REVOCATION_EVIDENCE, List.of(valid), PdfTrust.Evidence.EMPTY, true);
        var stale = new PdfTrust.Evidence(List.of(), List.of(), List.of(
                crl(valid, intermediateKey, List.of(), AT.minus(Duration.ofDays(9)), AT.minus(Duration.ofDays(8)))));
        assertFailure(PdfTrust.Failure.STALE_REVOCATION_EVIDENCE, List.of(valid), stale, true);
        var invalid = new PdfTrust.Evidence(List.of(), List.of(), List.of(new byte[]{1, 2, 3}));
        assertFailure(PdfTrust.Failure.INVALID_REVOCATION_EVIDENCE, List.of(valid), invalid, true);
    }

    @Test void ocspEvidenceStillAuthenticatesBothCertificatesOnTheSelectedPath() throws Exception {
        var evidence = new PdfTrust.Evidence(List.of(), List.of(ocsp(leaf, valid, intermediateKey), ocsp(valid, root, rootKey)), List.of());
        var chain = PdfTrust.validate(leaf, List.of(), material(List.of(expired, valid)), evidence, AT, true);
        assertEquals(fingerprints(leaf, valid, root), chain.certificateSha256()); assertTrue(chain.revocationChecked());
    }

    @Test void cyclicAlternativesCannotExceedTheSharedSearchBudget() throws Exception {
        var cycle = new ArrayList<X509Certificate>();
        for (int serial = 20; serial < 32; serial++) {
            cycle.add(certificate("Cycle", intermediateKey, "Cycle", intermediateKey, serial, true, VALID_UNTIL, "SHA256withECDSA"));
        }
        var start = certificate("Cycle signer", pair(), "Cycle", intermediateKey, 40, false, VALID_UNTIL, "SHA256withECDSA");
        var failure = assertThrows(IOException.class, () -> PdfTrust.validate(start, List.of(), material(cycle), PdfTrust.Evidence.EMPTY, AT, false));
        assertEquals("certificate path search budget exceeded", failure.getMessage());
    }

    @Test void unrelatedCandidatesConsumeTheSharedSearchBudget() throws Exception {
        var related = new ArrayList<X509Certificate>();
        for (int serial = 100; serial < 132; serial++) {
            related.add(certificate("Intermediate", intermediateKey, "Root", rootKey, serial, true,
                    AT.minusSeconds(1), "SHA256withECDSA"));
        }
        related.add(valid);
        assertEquals(fingerprints(leaf, valid, root),
                PdfTrust.validate(leaf, List.of(), material(related), PdfTrust.Evidence.EMPTY, AT, false).certificateSha256());
        var noisy = new ArrayList<X509Certificate>();
        for (int serial = 200; serial < 296; serial++) {
            noisy.add(certificate("Unrelated", intermediateKey, "Root", rootKey, serial, true,
                    VALID_UNTIL, "SHA256withECDSA"));
        }
        noisy.addAll(related);
        // Both pools fit the DER count limit; enumerating unrelated issuers must share the path budget.
        var failure = assertThrows(IOException.class,
                () -> PdfTrust.validate(leaf, List.of(), material(noisy), PdfTrust.Evidence.EMPTY, AT, false));
        assertEquals("certificate path search budget exceeded", failure.getMessage());
    }

    @Test void cancellationDuringAlternativeSearchIsPropagatedAndTheNextCallRemainsUsable() throws Exception {
        var checks = new AtomicInteger();
        var options = ExecutionOptions.builder().cancelled(() -> checks.incrementAndGet() > 20).build();
        var trust = material(List.of(expired, valid));
        var failure = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(options,
                () -> PdfTrust.validate(leaf, List.of(), trust, PdfTrust.Evidence.EMPTY, AT, false)));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code());
        assertEquals(fingerprints(leaf, valid, root), PdfTrust.validate(leaf, List.of(), trust, PdfTrust.Evidence.EMPTY, AT, false).certificateSha256());
    }

    @Test void cancellationAtEverySearchAndEvidenceCheckpointKeepsItsExecutionError() throws Exception {
        var trust = material(List.of(expired, valid));
        var evidence = new PdfTrust.Evidence(List.of(), List.of(ocsp(leaf, valid, intermediateKey), ocsp(valid, root, rootKey)), List.of());
        boolean completed = false;
        for (int allowance = 0; allowance < 256; allowance++) {
            int limit = allowance; var checks = new AtomicInteger();
            var options = ExecutionOptions.builder().cancelled(() -> checks.incrementAndGet() > limit).build();
            try {
                ExecutionContext.execute(options, () -> PdfTrust.validate(leaf, List.of(), trust, evidence, AT, true));
                completed = true; break;
            } catch (ExecutionException failure) { assertEquals(ExecutionException.Code.CANCELLED, failure.code()); }
        }
        assertTrue(completed, "bounded checkpoint injection must eventually reach an uninterrupted validation");
    }

    private static void assertFailure(PdfTrust.Failure expected, List<X509Certificate> candidates, PdfTrust.Evidence evidence, boolean revocation) throws Exception {
        var trust = material(candidates);
        assertEquals(expected, assertThrows(PdfTrust.ValidationException.class,
                () -> PdfTrust.validate(leaf, List.of(), trust, evidence, AT, revocation)).failure());
    }

    private static PdfTrust.Material material(List<X509Certificate> candidates) throws Exception {
        return new PdfTrust.Material(encoded(List.of(root)), encoded(candidates), AT, Duration.ofDays(7), Duration.ZERO);
    }

    private static List<byte[]> encoded(List<X509Certificate> certificates) throws Exception {
        var result = new ArrayList<byte[]>(); for (var cert : certificates) { result.add(cert.getEncoded()); } return result;
    }

    private static List<String> fingerprints(X509Certificate... certificates) throws Exception {
        var result = new ArrayList<String>();
        for (var cert : certificates) { result.add(HexFormat.of().formatHex(PdfTrust.digest(cert.getEncoded()))); }
        return result;
    }

    private static KeyPair pair() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC"); generator.initialize(new ECGenParameterSpec("secp256r1")); return generator.generateKeyPair();
    }

    private static X509Certificate certificate(String subject, KeyPair pair, String issuer, KeyPair signer, int serial,
                                               boolean ca, Instant until, String algorithm) throws Exception {
        var builder = new JcaX509v3CertificateBuilder(new X500Name("CN=" + issuer), BigInteger.valueOf(serial),
                Date.from(Instant.parse("2025-01-01T00:00:00Z")), Date.from(until), new X500Name("CN=" + subject), pair.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(ca ? KeyUsage.keyCertSign | KeyUsage.cRLSign : KeyUsage.digitalSignature));
        return new JcaX509CertificateConverter().setProvider(PdfTrust.PROVIDER).getCertificate(builder.build(
                new JcaContentSignerBuilder(algorithm).setProvider(PdfTrust.PROVIDER).build(signer.getPrivate())));
    }

    private static byte[] crl(X509Certificate issuer, KeyPair signer, List<X509Certificate> revoked, Instant thisUpdate, Instant nextUpdate) throws Exception {
        var builder = new X509v2CRLBuilder(new X500Name(issuer.getSubjectX500Principal().getName()), Date.from(thisUpdate));
        builder.setNextUpdate(Date.from(nextUpdate));
        for (var cert : revoked) { builder.addCRLEntry(cert.getSerialNumber(), Date.from(AT.minusSeconds(120)), 1); }
        return builder.build(new JcaContentSignerBuilder("SHA256withECDSA").setProvider(PdfTrust.PROVIDER).build(signer.getPrivate())).getEncoded();
    }

    private static byte[] ocsp(X509Certificate certificate, X509Certificate issuer, KeyPair signer) throws Exception {
        var calculators = new JcaDigestCalculatorProviderBuilder().setProvider(PdfTrust.PROVIDER).build();
        var builder = new JcaBasicOCSPRespBuilder(issuer.getPublicKey(), calculators.get(RespID.HASH_SHA1));
        var id = new CertificateID(calculators.get(CertificateID.HASH_SHA1), new JcaX509CertificateHolder(issuer), certificate.getSerialNumber());
        builder.addResponse(id, CertificateStatus.GOOD, Date.from(AT.minusSeconds(60)), Date.from(AT.plusSeconds(60)), null);
        var basic = builder.build(new JcaContentSignerBuilder("SHA256withECDSA").setProvider(PdfTrust.PROVIDER).build(signer.getPrivate()), new X509CertificateHolder[0], Date.from(AT));
        return new OCSPRespBuilder().build(OCSPRespBuilder.SUCCESSFUL, basic).getEncoded();
    }
}
