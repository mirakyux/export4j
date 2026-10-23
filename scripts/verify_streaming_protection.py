# SPDX-License-Identifier: Apache-2.0
"""Inspect actual encrypted and static archival one-pass PDF outputs using independent tools."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
import pypdfium2 as pdfium
from pypdf import PdfReader
from verify_advanced_pdf import difference, require
from verify_pdf_raster import references
from verify_production import actual_text, contact_sheet
from verify_variable_columns import ink_coverage

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "examples/build/streaming-protection"
OUTPUT = ROOT / "build/verification/streaming-protection"


def extracted(path, password=None):
    values = []
    with pdfium.PdfDocument(path, password=password) as document:
        for index in range(len(document)):
            page = document[index]
            try:
                text = page.get_textpage()
                try: values.append(text.get_text_bounded())
                finally: text.close()
            finally: page.close()
    return "\n".join(values)


def encrypted(path, caller=False):
    reader = PdfReader(path, strict=True)
    require(reader.is_encrypted and reader.decrypt("wrong-password") == 0, "encrypted PDF accepts a wrong password")
    require(reader.decrypt("fixture-reader") != 0, "encrypted PDF rejects its reader password")
    encryption = reader.trailer["/Encrypt"]
    require(encryption["/Length"] == 256 and encryption["/V"] == 5 and encryption["/R"] >= 5, "output is not AES-256")
    require(int(encryption["/P"]) & (4 | 8 | 16 | 32 | 256 | 1024 | 2048) == 0, "read-only permission bits differ")
    owner = PdfReader(path)
    require(owner.decrypt("fixture-owner").value == 2, "owner password is not an owner credential")
    require(len(reader.pages) == (1 if caller else 2), "encrypted page count differs")
    text = extracted(path, "fixture-reader")
    if caller:
        require("CALLER PROTECTED" in text, "encrypted caller text differs")
    else:
        require(re.findall(r"PROTECTED-\d", text) == [f"PROTECTED-{i}" for i in range(1, 8)], "encrypted source lost or duplicated")
        require(actual_text(reader) == [f"PROTECTED-{i} \ufffc" for i in range(1, 8)], "encrypted original logical text differs")
        require(sum(len(page.images) for page in reader.pages) == 7, "encrypted image count differs")
        links = [ref.get_object()["/A"]["/URI"] for page in reader.pages for ref in page.get("/Annots", [])]
        require(links == [f"https://example.com/protected/{i}" for i in range(1, 8)], "encrypted passive links differ")
    return reader


def encrypted_render(path, directory):
    poppler, second = directory / "poppler", directory / "pdfium"
    poppler.mkdir(parents=True, exist_ok=True); second.mkdir(parents=True, exist_ok=True)
    subprocess.run(["pdftoppm", "-upw", "fixture-reader", "-r", "144", "-png", str(path), str(poppler / "page")],
                   check=True, capture_output=True, timeout=60)
    images = []
    with pdfium.PdfDocument(path, password="fixture-reader") as document:
        for index in range(len(document)):
            page = document[index]
            try:
                bitmap = page.render(scale=2)
                try:
                    target = second / f"page-{index+1}.png"; bitmap.to_pil().convert("RGB").save(target); images.append(target)
                finally: bitmap.close()
            finally: page.close()
    return sorted(poppler.glob("page-*.png"), key=lambda p: int(p.stem.rsplit("-", 1)[1])), images


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--verapdf-report", type=Path, required=True)
    args = parser.parse_args()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    caller = Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip()) / "output"
    paths = [("encrypted", SOURCE / "encrypted.pdf", True), ("archive", SOURCE / "archive.pdf", False),
             ("caller-encrypted", caller / "consumer-streaming-encrypted.pdf", True),
             ("caller-archive", caller / "consumer-streaming-archive.pdf", False)]
    compliance = ET.parse(args.verapdf_report)
    jobs = compliance.findall(".//job")
    require(len(jobs) == 2 and len(compliance.findall(".//validationReport")) == 2
            and all(node.get("isCompliant") == "true" for node in compliance.findall(".//validationReport")), "two PDF/A-2b validations did not pass")
    verified_files = {Path(job.findtext("item/name")).resolve() for job in jobs}
    require(verified_files == {(SOURCE / "archive.pdf").resolve(), (caller / "consumer-streaming-archive.pdf").resolve()}, "PDF/A report names different files")
    report, tiles = {}, []
    for name, path, protected in paths:
        reader = encrypted(path, name.startswith("caller")) if protected else PdfReader(path, strict=True)
        if not protected:
            require(not reader.is_encrypted and args.verapdf_report.stat().st_mtime >= path.stat().st_mtime, "stale or encrypted archival fixture")
            require(len(reader.trailer["/Root"]["/OutputIntents"]) == 1, "archival output intent missing")
            require(all(not page.get("/Annots") for page in reader.pages), "static archival output contains interactions")
            text = extracted(path)
            require(("CALLER PROTECTED" in text) if name.startswith("caller") else
                    re.findall(r"PROTECTED-\d", text) == [f"PROTECTED-{i}" for i in range(1, 8)], "archival text differs")
            if not name.startswith("caller"):
                require(sum(len(page.images) for page in reader.pages) == 7, "archival image count differs")
                require(actual_text(reader) == [f"PROTECTED-{i} \ufffc" for i in range(1, 8)], "archival logical text differs")
        first, second = encrypted_render(path, OUTPUT / name) if protected else references(path, OUTPUT / name)
        require(len(first) == len(second) == len(reader.pages), "protected renderer page counts differ")
        comparisons = []
        for index, (a, b) in enumerate(zip(first, second, strict=True)):
            delta = difference(a, b, blur_radius=1); delta["uncoveredInkFractions"] = ink_coverage(a, b)
            require(max(delta["uncoveredInkFractions"]) < .001, "protected renderers disagree on visible ink")
            comparisons.append(delta); tiles.extend([(f"{name} Poppler {index+1}", a), (f"{name} PDFium {index+1}", b)])
        report[name] = {"pages": len(reader.pages), "renderDifferences": comparisons}
    contact_sheet(tiles, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print("Streaming protection: AES-256, password/permission checks, source/images/links, two veraPDF validations, callers and six pages dual rendered")


if __name__ == "__main__":
    main()
