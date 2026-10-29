# SPDX-License-Identifier: Apache-2.0
"""Check actual Word pages, exported PDF and semantic projection independently."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys

from PIL import Image
from pypdf import PdfReader

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from verify_advanced_pdf import difference, render_pdfium, render_poppler, require
from verify_production import contact_sheet


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--output-directory", type=Path, required=True)
    args = parser.parse_args()
    source = args.directory.resolve()
    output = args.output_directory.resolve()
    output.mkdir(parents=True, exist_ok=True)
    receipt = json.loads((source / "word-acceptance.json").read_text(encoding="utf-8-sig"))
    native = json.loads((source / "word-page-rendering.json").read_text(encoding="utf-8-sig"))
    for flag in ("passed", "actualControlEdit", "actualTextboxEdit", "savedAndReopened",
                 "nativePagesRendered", "legacyCurrentAndDefaultPreserved", "pdfRequested", "pdfExported"):
        require(receipt.get(flag) is True, f"Actual Word action missing: {flag}")
    require(native["sourceSha256"] == digest(source / "word-saved.docx"), "Native page source changed")
    require(receipt["pdfSha256"] == digest(source / "word-rendered.pdf"), "Word PDF changed after export")
    require(native["pages"] == receipt["after"]["pages"] == 4, "Word fixture must exercise four pages")
    require(len(native["files"]) == 4, "Word native renderer omitted a page")
    native_pages = []
    for index, entry in enumerate(native["files"], 1):
        require(entry["page"] == index, "Word native page sequence differs")
        for kind in ("emf", "png"):
            path = source / entry[kind]
            require(path.parent == source, "Page receipt contains a nonlocal artifact")
            require(digest(path) == entry[kind + "Sha256"], "Word native page changed")
        native_pages.append(source / entry["png"])
    report = {"passed": True, "wordVersion": receipt["wordVersion"], "wordBuild": receipt["wordBuild"],
              "savedDocxSha256": native["sourceSha256"], "pdfs": {}, "nativePageComparisons": []}
    contacts = []
    for name in ("word-rendered", "semantic-portable"):
        pdf = source / (name + ".pdf")
        reader = PdfReader(pdf, strict=True)
        texts = [page.extract_text() for page in reader.pages]
        full = "\n".join(texts)
        require(len(texts) == 4, f"{name} fixture page count differs")
        if name == "word-rendered":
            per_page = [
                ("FIRST HEADER", "WORD-EDITED-NAME", "WORD-TEXTBOX-READY", "FORM-READY", "Box table"),
                ("EVEN HEADER", "DEEP-READY", "LEVEL-4 BEFORE", "LEVEL-1 AFTER"),
                ("DEFAULT HEADER", "DEFAULT-BAND-READY"),
                ("FIRST HEADER", "SECTION-TWO-READY"),
            ]
            require(re.search(r"Second section[ .\t]*4(?:\s|$)", texts[0]) is not None, "Word TOC actual target page differs")
            for index, (text, expected) in enumerate(zip(texts, per_page, strict=True), 1):
                for token in expected + (f"Page {index}",):
                    require(token in text, f"Word page {index} missing {token}")
            require(full.count("MOVED-CONTENT") == 1, "Word move duplicated content")
        else:
            for token in ("READY-NAME", "TEXTBOX-READY", "FORM-READY", "DEEP-READY", "DEFAULT-BAND-READY", "SECTION-TWO-READY"):
                require(token in full, f"Portable projection missing {token}")
            for token in ("DELETE-PRIVATE", "INSERTED-PRIVATE"):
                require(token not in full, f"Decided revision text remains: {token}")
            require(full.count("MOVED-CONTENT") == 1, "Portable move duplicated content")
        for token in ("DEEP-OLD", "TEXTBOX-OLD", "NAME-OLD"):
            require(token not in full, f"Replaced source remains in {name}: {token}")
        poppler = render_poppler(pdf, output / name / "poppler")
        pdfium = render_pdfium(pdf, output / name / "pdfium")
        require(len(poppler) == len(pdfium) == len(texts), "Independent renderer page count differs")
        metrics = [difference(a, b, 1) for a, b in zip(poppler, pdfium, strict=True)]
        require(all(item["meanAbsoluteChannelError"] < 4 and item["changedPixelFraction"] < .06 for item in metrics),
                "Independent PDF renderers disagree")
        report["pdfs"][name] = {"pages": len(texts), "sha256": digest(pdf), "differences": metrics}
        for index, (first, second) in enumerate(zip(poppler, pdfium, strict=True), 1):
            contacts.extend(((f"{name} Poppler {index}", first), (f"{name} PDFium {index}", second)))
            if name == "word-rendered":
                normalized = output / f"word-native-normalized-{index}.png"
                with Image.open(first) as target, Image.open(native_pages[index - 1]) as image:
                    image.convert("RGB").resize(target.size, Image.Resampling.LANCZOS).save(normalized)
                comparison = difference(normalized, first, 1)
                require(comparison["meanAbsoluteChannelError"] < 4 and comparison["changedPixelFraction"] < .06,
                        "Word native page and Word PDF visual content disagree")
                report["nativePageComparisons"].append({"page": index, "pngSha256": digest(native_pages[index - 1]),
                                                        "difference": comparison})
                contacts.append((f"Word native {index}", native_pages[index - 1]))
    contact_sheet(contacts, output)
    (output / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
