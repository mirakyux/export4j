# SPDX-License-Identifier: Apache-2.0
"""Create original offline PKI fixtures and deterministic-time RFC3161 responses in ignored build output."""
import argparse
from datetime import datetime, timezone, timedelta
from hashlib import sha1, sha256
import json
from pathlib import Path
from asn1crypto import algos, cms, core, ocsp, tsp, x509 as asn1x509
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.x509.oid import NameOID, ExtendedKeyUsageOID

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build/verification/pdf-standards-fixtures"
UTC = timezone.utc
NOW = datetime(2026, 1, 1, tzinfo=UTC)


def certificate(name, serial, key, issuer, issuer_key, ca=False, tsa=False, expired=False):
    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, name)])
    builder = (x509.CertificateBuilder().subject_name(subject).issuer_name(issuer.subject if issuer else subject)
               .public_key(key.public_key()).serial_number(serial).not_valid_before(datetime(2025, 1, 1, tzinfo=UTC))
               .not_valid_after(datetime(2025, 6, 1, tzinfo=UTC) if expired else datetime(2030, 1, 1, tzinfo=UTC))
               .add_extension(x509.BasicConstraints(ca=ca, path_length=1 if ca else None), critical=True)
               .add_extension(x509.KeyUsage(digital_signature=not ca, content_commitment=not ca,
                    key_encipherment=False, data_encipherment=False, key_agreement=False,
                    key_cert_sign=ca, crl_sign=ca, encipher_only=False, decipher_only=False), critical=True)
               .add_extension(x509.SubjectKeyIdentifier.from_public_key(key.public_key()), critical=False)
               .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(issuer_key.public_key()), critical=False))
    if tsa:
        builder = builder.add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.TIME_STAMPING]), critical=True)
    return builder.sign(issuer_key, hashes.SHA256())


def ocsp_response(cert, issuer, key, status="good", stale=False):
    issuer_asn = asn1x509.Certificate.load(issuer.public_bytes(serialization.Encoding.DER))
    identifier = {"hash_algorithm": {"algorithm": "sha1"},
                  "issuer_name_hash": sha1(issuer_asn.subject.dump()).digest(),
                  "issuer_key_hash": sha1(issuer_asn.public_key["public_key"].contents[1:]).digest(),
                  "serial_number": cert.serial_number}
    this_update = NOW - timedelta(days=40 if stale else 1)
    next_update = NOW - timedelta(days=30) if stale else NOW + timedelta(days=31)
    cert_status = {"revoked": {"revocation_time": NOW - timedelta(days=2), "revocation_reason": "key_compromise"}} if status == "revoked" else {status: None}
    data = ocsp.ResponseData({"responder_id": {"by_name": issuer_asn.subject}, "produced_at": NOW,
                "responses": [{"cert_id": identifier, "cert_status": cert_status,
                               "this_update": this_update, "next_update": next_update}]})
    signature = key.sign(data.dump(), padding.PKCS1v15(), hashes.SHA256())
    basic = ocsp.BasicOCSPResponse({"tbs_response_data": data, "signature_algorithm": {"algorithm": "sha256_rsa"},
                                    "signature": signature, "certs": [issuer_asn]})
    return ocsp.OCSPResponse({"response_status": "successful", "response_bytes": {
        "response_type": "basic_ocsp_response", "response": basic}}).dump()


def create():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    ca_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    ca = certificate("Export4j Fixture Root", 1, ca_key, None, ca_key, ca=True)
    identities = {}
    for label, serial, tsa, expired in [("signer", 10, False, False), ("tsa", 20, True, False), ("expired", 30, False, True)]:
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        cert = certificate(f"Export4j Fixture {label}", serial, key, ca, ca_key, tsa=tsa, expired=expired)
        (OUTPUT / f"{label}-key.pk8").write_bytes(key.private_bytes(serialization.Encoding.DER, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        (OUTPUT / f"{label}.der").write_bytes(cert.public_bytes(serialization.Encoding.DER))
        identities[label] = (key, cert)
    (OUTPUT / "root.der").write_bytes(ca.public_bytes(serialization.Encoding.DER))
    for label in ["signer", "tsa"]:
        cert = identities[label][1]
        (OUTPUT / f"{label}-good.ocsp").write_bytes(ocsp_response(cert, ca, ca_key))
        (OUTPUT / f"{label}-stale.ocsp").write_bytes(ocsp_response(cert, ca, ca_key, stale=True))
    (OUTPUT / "signer-revoked.ocsp").write_bytes(ocsp_response(identities["signer"][1], ca, ca_key, status="revoked"))
    (OUTPUT / "signer-unknown.ocsp").write_bytes(ocsp_response(identities["signer"][1], ca, ca_key, status="unknown"))
    for label, revoked, stale in [("good", False, False), ("revoked", True, False), ("stale", False, True)]:
        builder = (x509.CertificateRevocationListBuilder().issuer_name(ca.subject)
                   .last_update(NOW - timedelta(days=40 if stale else 1))
                   .next_update(NOW - timedelta(days=30) if stale else NOW + timedelta(days=31))
                   .add_extension(x509.CRLNumber(1), critical=False))
        if revoked:
            builder = builder.add_revoked_certificate(x509.RevokedCertificateBuilder().serial_number(identities["signer"][1].serial_number)
                    .revocation_date(NOW - timedelta(days=2)).build())
        (OUTPUT / f"{label}.crl").write_bytes(builder.sign(ca_key, hashes.SHA256()).public_bytes(serialization.Encoding.DER))
    manifest = {p.name: sha256(p.read_bytes()).hexdigest() for p in OUTPUT.iterdir() if p.suffix != ".pk8" and p.name != "manifest.json"}
    (OUTPUT / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Created {len(manifest)} public PKI fixtures; private fixture keys remain in ignored build output")


def timestamp(imprint, output, time):
    digest = bytes.fromhex(imprint)
    if len(digest) != 32:
        raise ValueError("SHA-256 imprint must be exactly 32 bytes")
    cert = asn1x509.Certificate.load((OUTPUT / "tsa.der").read_bytes())
    root = asn1x509.Certificate.load((OUTPUT / "root.der").read_bytes())
    key = serialization.load_der_private_key((OUTPUT / "tsa-key.pk8").read_bytes(), password=None)
    info = tsp.TSTInfo({"version": "v1", "policy": "1.3.6.1.4.1.55555.1", "message_imprint": {
        "hash_algorithm": {"algorithm": "sha256"}, "hashed_message": digest},
        "serial_number": int.from_bytes(digest[:16], "big"), "gen_time": time})
    attrs = cms.CMSAttributes([
        cms.CMSAttribute({"type": "content_type", "values": ["tst_info"]}),
        cms.CMSAttribute({"type": "message_digest", "values": [sha256(info.dump()).digest()]}),
        cms.CMSAttribute({"type": "signing_certificate_v2", "values": [{"certs": [{"cert_hash": sha256(cert.dump()).digest()}]}]})])
    signature = key.sign(attrs.dump(), padding.PKCS1v15(), hashes.SHA256())
    signer = cms.SignerInfo({"version": "v1", "sid": {"issuer_and_serial_number": {"issuer": cert.issuer, "serial_number": cert.serial_number}},
        "digest_algorithm": {"algorithm": "sha256"}, "signed_attrs": attrs,
        "signature_algorithm": {"algorithm": "sha256_rsa"}, "signature": signature})
    signed = cms.SignedData({"version": "v3", "digest_algorithms": [{"algorithm": "sha256"}],
        "encap_content_info": {"content_type": "tst_info", "content": info}, "certificates": [cert, root], "signer_infos": [signer]})
    output.write_bytes(cms.ContentInfo({"content_type": "signed_data", "content": signed}).dump())


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--timestamp")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--time", default="2026-01-01T00:01:00+00:00")
    args = parser.parse_args()
    if args.timestamp:
        if not args.output:
            parser.error("--output is required with --timestamp")
        timestamp(args.timestamp, args.output, datetime.fromisoformat(args.time))
    else:
        create()


if __name__ == "__main__":
    main()
