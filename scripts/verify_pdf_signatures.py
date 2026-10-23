# SPDX-License-Identifier: Apache-2.0
"""Independently reconstruct PDF signed revisions and reconcile explicit integrity/coverage results."""
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
INPUT = ROOT / "examples/build/pdf-certification"
SOURCE = ROOT / "examples/build/pdf-signature-verification"
OUTPUT = ROOT / "build/verification/pdf-signatures"


def signatures(path):
    reader = PdfReader(path, strict=True)
    form = reader.trailer["/Root"].get("/AcroForm", {})
    if hasattr(form, "get_object"):
        form = form.get_object()
    values = [field.get_object()["/V"] for field in form.get("/Fields", [])
              if field.get_object().get("/FT") == "/Sig" and "/V" in field.get_object()]
    return sorted(values, key=lambda value: sum(list(value["/ByteRange"])[2:]))


def cms_result(path, value, openssl, directory):
    raw = path.read_bytes()
    ranges = list(value["/ByteRange"])
    require(len(ranges) == 4 and ranges[0] == 0 and 0 < ranges[1] < ranges[2]
            and ranges[2] + ranges[3] <= len(raw), "malformed independently read byte range")
    padded = bytes(value["/Contents"])
    require(padded[0] == 0x30, "CMS is not a DER sequence")
    count = padded[1] & 127
    size = 2 + padded[1] if padded[1] < 128 else 2 + count + int.from_bytes(padded[2:2 + count], "big")
    require(size <= len(padded) and not any(padded[size:]), "CMS has nonzero reservation padding")
    directory.mkdir(parents=True, exist_ok=True)
    payload = raw[:ranges[1]] + raw[ranges[2]:ranges[2] + ranges[3]]
    (directory / "content.bin").write_bytes(payload)
    (directory / "signature.der").write_bytes(padded[:size])
    result = subprocess.run([str(openssl), "cms", "-verify", "-binary", "-inform", "DER", "-in",
                             str(directory / "signature.der"), "-content", str(directory / "content.bin"),
                             "-noverify", "-out", str(directory / "verified.bin")], capture_output=True, timeout=30)
    valid = result.returncode == 0
    if valid:
        require((directory / "verified.bin").read_bytes() == payload, "CMS verified different bytes")
    return {"revisionBytes": ranges[2] + ranges[3], "contentsOffset": ranges[1], "contentsEnd": ranges[2],
            "cmsBytes": size, "coversCurrentFile": ranges[2] + ranges[3] == len(raw), "integrityValid": valid}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--openssl", default="openssl")
    args = parser.parse_args()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    reports = json.loads((SOURCE / "report.json").read_text())
    names = ["source", "certified-1", "certified-2", "certified-3", "approved-2", "approved-3", "appended", "tampered"]
    require([report["name"] for report in reports] == names, "missing or duplicated verification cases")
    checked = 0
    for report in reports:
        name = report["name"]
        path = (SOURCE if name in ("appended", "tampered") else INPUT) / (name + ".pdf")
        values = signatures(path)
        expected = [cms_result(path, value, args.openssl, OUTPUT / name / str(index)) for index, value in enumerate(values)]
        checked += len(expected)
        require(expected == report["signatures"], "Java signature geometry or verification result differs from independent reconstruction")
        require(report["inputBytes"] == path.stat().st_size, "input snapshot byte count differs")
        require(report["allSignedBytesValid"] == (bool(expected) and all(value["integrityValid"] for value in expected)),
                "aggregate integrity accepts absent or rejected signatures")
        require(report["currentFileCovered"] == any(value["coversCurrentFile"] for value in expected), "byte coverage is conflated with integrity")
        wanted = [] if name == "source" else [True, False] if name == "tampered" else [True] * (2 if name.startswith("approved") or name == "appended" else 1)
        require([value["integrityValid"] for value in expected] == wanted, "cryptographic tamper or historical revision behavior differs")
        require(report["currentFileCovered"] == (name not in ("source", "appended")), "unsigned append was authenticated")
    require(checked == 11, "real CMS signature count differs")
    original = (INPUT / "approved-2.pdf").read_bytes()
    require((SOURCE / "appended.pdf").read_bytes() == original + b"\n% unsigned append\n", "append fixture changed signed bytes")
    tampered = (SOURCE / "tampered.pdf").read_bytes()
    require(len(tampered) == len(original) and sum(a != b for a, b in zip(original, tampered, strict=True)) == 1,
            "tamper fixture did not isolate a signed metadata byte")

    caller = Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip()) / "output/consumer-rejected-signature.pdf"
    caller_values = signatures(caller)
    require(len(caller_values) == 1 and not cms_result(caller, caller_values[0], args.openssl, OUTPUT / "caller-cms")["integrityValid"],
            "deliberately invalid isolated-caller CMS was accepted")
    tiles, render_report = [], {}
    for name, path in [("appended", SOURCE / "appended.pdf"), ("tampered", SOURCE / "tampered.pdf"), ("caller", caller)]:
        first, second = references(path, OUTPUT / (name + "-render"))
        require(len(first) == len(second) == 1, "signature processing changed page counts")
        deltas = []
        for index, (a, b) in enumerate(zip(first, second, strict=True)):
            delta = difference(a, b, blur_radius=1); delta["uncoveredInkFractions"] = ink_coverage(a, b)
            require(max(delta["uncoveredInkFractions"]) < .001, "independent rendering disagrees after signature processing")
            deltas.append(delta); tiles.extend([(name + " Poppler", a), (name + " PDFium", b)])
        render_report[name] = deltas
    contact_sheet(tiles, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps({"cmsChecks": checked, "rendering": render_report}, indent=2) + "\n")
    print("PDF signature verification: 11 real CMS checks, historical coverage, signed-byte tamper rejection, isolated caller and dual rendering passed")


if __name__ == "__main__":
    main()
