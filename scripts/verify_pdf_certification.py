# SPDX-License-Identifier: Apache-2.0
"""Independent DocMDP structure, detached CMS integrity and signed-page rendering checks."""
import argparse
import json
from pathlib import Path
import subprocess

from pypdf import PdfReader
from verify_advanced_pdf import difference, require
from verify_pdf_raster import references
from verify_production import contact_sheet
from verify_variable_columns import ink_coverage

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "examples/build/pdf-certification"
OUTPUT = ROOT / "build/verification/pdf-certification"


def verify_cms(signature, raw, openssl, output, expected_end):
    output.mkdir(parents=True, exist_ok=True)
    ranges = list(signature["/ByteRange"])
    require(len(ranges) == 4 and ranges[0] == 0 and 0 < ranges[1] < ranges[2]
            and ranges[2] + ranges[3] == expected_end, "incomplete signed revision")
    payload = raw[:ranges[1]] + raw[ranges[2]:expected_end]
    cms = bytes(signature["/Contents"])
    require(len(cms) > 2 and cms[0] == 0x30, "CMS is not a DER sequence")
    count = cms[1] & 127
    require(cms[1] < 128 or 0 < count <= 4, "invalid DER length")
    end = 2 + cms[1] if cms[1] < 128 else 2 + count + int.from_bytes(cms[2:2 + count], "big")
    require(end <= len(cms) and not any(cms[end:]), "invalid CMS padding")
    signature_path = output / "signature.der"
    content_path = output / "content.bin"
    verified_path = output / "verified.bin"
    signature_path.write_bytes(cms[:end]); content_path.write_bytes(payload)
    command = [str(openssl), "cms", "-verify", "-binary", "-inform", "DER", "-in", str(signature_path),
               "-content", str(content_path), "-noverify", "-out", str(verified_path)]
    result = subprocess.run(command, capture_output=True, timeout=30)
    require(result.returncode == 0 and verified_path.read_bytes() == payload, "CMS integrity verification failed")
    tampered = bytearray(payload); tampered[len(tampered) // 2] ^= 1
    content_path.write_bytes(tampered)
    result = subprocess.run(command, capture_output=True, timeout=30)
    require(result.returncode != 0, "tampered signed bytes were accepted")
    content_path.write_bytes(payload)
    return {"byteRange": ranges, "cmsBytes": end, "integrity": "passed", "tampering": "rejected"}


def inspect(path, permission, approval, openssl):
    reader = PdfReader(path, strict=True)
    raw = path.read_bytes(); source = (FIXTURES / "source.pdf").read_bytes()
    require(raw.startswith(source), "certification changed the original revision")
    fields = reader.trailer["/Root"]["/AcroForm"]["/Fields"]
    signatures = [item.get_object()["/V"] for item in fields if item.get_object().get("/FT") == "/Sig"]
    require(len(signatures) == (2 if approval else 1), "incorrect number of certification/approval signatures")
    root = reader.trailer["/Root"]
    certified = root["/Perms"]["/DocMDP"]
    require(certified.indirect_reference == signatures[0].indirect_reference, "catalog certification points to another signature")
    reference, = certified["/Reference"]
    require(reference["/Type"] == "/SigRef" and reference["/TransformMethod"] == "/DocMDP", "missing DocMDP transform")
    parameters = reference["/TransformParams"]
    require(parameters["/Type"] == "/TransformParams" and parameters["/P"] == permission
            and parameters["/V"] == "/1.2", "certification permission differs")
    require(reference["/Data"].indirect_reference == root.indirect_reference, "certification Data does not name the catalog")
    first_end = len((FIXTURES / f"certified-{permission}.pdf").read_bytes())
    if approval:
        require(raw.startswith((FIXTURES / f"certified-{permission}.pdf").read_bytes()), "approval rewrote certification revision")
        require("/Reference" not in signatures[1], "approval falsely claims a second certification")
    report = []
    for index, signature in enumerate(signatures):
        require(signature["/SubFilter"] == "/adbe.pkcs7.detached", "unexpected signing profile")
        report.append(verify_cms(signature, raw, openssl, OUTPUT / path.stem / f"signature-{index + 1}",
                                 first_end if index == 0 else len(raw)))
    require(len(reader.pages) == 1 and reader.pages[0].extract_text().strip() == "CERTIFICATION-BASE", "signing changed page content")
    return {"permission": permission, "signatures": report}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--openssl", type=Path, required=True)
    args = parser.parse_args()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    report = {"certificateTrust": "not validated; development self-signed certificate",
              "readerPermissionEnforcement": "not validated", "files": {}, "rendering": {}}
    paths = []
    for permission in range(1, 4):
        for approval in ([False] if permission == 1 else [False, True]):
            path = FIXTURES / f"{'approved' if approval else 'certified'}-{permission}.pdf"
            report["files"][path.name] = inspect(path, permission, approval, args.openssl)
            paths.append(path)
    caller = Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip()) / "output"
    require((caller / "consumer-certification-target.bin").read_text() == "old", "caller signing failure replaced target")
    caller_source = caller / "consumer-certification-source.pdf"
    require(PdfReader(caller_source).pages[0].extract_text().strip() == "CERTIFICATION-CALLER", "caller signing source differs")
    paths.extend([FIXTURES / "source.pdf", caller_source])
    tiles = []
    for path in paths:
        first, second = references(path, OUTPUT / path.stem / "render")
        require(len(first) == len(second) == 1, "signature render page count differs")
        delta = difference(first[0], second[0], blur_radius=1)
        delta["uncoveredInkFractions"] = ink_coverage(first[0], second[0])
        require(max(delta["uncoveredInkFractions"]) < .001, "signature output ink differs between renderers")
        report["rendering"][path.name] = delta
        tiles.extend([(f"{path.stem} Poppler", first[0]), (f"{path.stem} PDFium", second[0])])
    contact_sheet(tiles, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print("PDF certification: all DocMDP permissions, seven CMS signatures, tamper rejection, incremental approval, atomic caller and seven pages dual rendered")


if __name__ == "__main__":
    main()
