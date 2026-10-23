# SPDX-License-Identifier: Apache-2.0
"""Independent package bytes, XML structure and optional real Word save/reopen evidence."""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from pypdf import PdfReader
from verify_adr_form import render
from verify_advanced_pdf import difference, require
from verify_production import contact_sheet

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build/verification/docx-package-edit"
SAMPLE = ROOT / "examples/build/docx-package-edit"
W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
XML_SPACE = "{http://www.w3.org/XML/1998/namespace}space"


def package(path):
    with ZipFile(path) as archive:
        return {name: archive.read(name) for name in archive.namelist()}


def shape(element):
    return (element.tag, sorted(element.attrib.items()), element.text or "", element.tail or "", [shape(child) for child in element])


def reconcile(source, edited, replacements):
    require(source.keys() == edited.keys(), "package entries changed")
    changed = []
    for part in source:
        if part not in replacements:
            require(source[part] == edited[part], "untouched package bytes changed: " + part)
            continue
        old, new = ET.fromstring(source[part]), ET.fromstring(edited[part])
        old_texts, new_texts = list(old.iter(W + "t")), list(new.iter(W + "t"))
        require(len(old_texts) == len(new_texts), "text/run nodes deleted")
        for before, after in zip(old_texts, new_texts, strict=True):
            if before.text in replacements[part]:
                require(after.text == replacements[part][before.text], "requested replacement differs")
                after.text = before.text
                if XML_SPACE not in before.attrib: after.attrib.pop(XML_SPACE, None)
                changed.append(part)
        require(shape(old) == shape(new), "unselected XML structure changed: " + part)
    return changed


def main():
    parser = argparse.ArgumentParser(); parser.add_argument("--word", action="store_true"); args = parser.parse_args()
    source, edited = package(SAMPLE / "source.docx"), package(SAMPLE / "edited.docx")
    changed = reconcile(source, edited, {"word/document.xml": {"BODY-OLD": "BODY-READY"}, "word/header1.xml": {"HEADER-OLD": "HEADER-READY"}})
    require(changed == ["word/header1.xml", "word/document.xml"] or set(changed) == {"word/document.xml", "word/header1.xml"}, "expected actual edited parts")
    document = ET.fromstring(edited["word/document.xml"])
    for name, count in (("commentRangeStart", 1), ("commentRangeEnd", 1), ("commentReference", 1), ("ins", 1), ("del", 1), ("sdt", 1), ("fldSimple", 1), ("ffData", 1), ("titlePg", 1)):
        require(len(list(document.iter(W + name))) == count, "preserved OOXML structure: " + name)
    require("TOC-CACHED" in "".join(document.itertext()), "field cache changed despite PRESERVE")
    with (SAMPLE / "result.tsv").open(encoding="utf-8", newline="") as stream: report = list(csv.DictReader(stream, delimiter="\t"))[0]
    require(report["changes"] == "2" and report["issues"] == "1" and report["losses"] == "0", "package edit actual report")
    require(int(report["preservedParts"]) == len(source) - 2, "preserved part count")
    caller = Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip()) / "output"
    reconcile(package(caller / "consumer-package-source.docx"), package(caller / "consumer-package-edited.docx"), {"word/document.xml": {"CALLER-PACKAGE-OLD": "CALLER-PACKAGE-READY"}})
    result = {"packages": 2, "edited_parts": 3, "sample_preserved_parts": len(source) - 2, "unmodeled_structures": "unchanged", "checks": "passed"}
    if args.word:
        report = json.loads((SAMPLE / "word-report.json").read_text(encoding="utf-8-sig"))
        require(report["pdfExported"], "Word export not completed")
        require(report["sourceSha256"] == hashlib.sha256((SAMPLE / "edited.docx").read_bytes()).hexdigest(), "Word source stale")
        if report["savedAndReopened"]:
            require(report["savedSha256"] == hashlib.sha256((SAMPLE / "word-updated.docx").read_bytes()).hexdigest(), "Word saved package stale")
        for stage in (("before", "after") if report["savedAndReopened"] else ("before",)):
            state = report[stage]
            require(state["comments"] == 1 and state["revisions"] == 2 and state["controls"] == 1 and state["forms"] == 1, "Word object preservation: " + stage)
            require("BODY-READY" in state["text"] and "BODY-OLD" not in state["text"], "Word edited text")
            require("HEADER-READY" in state["primaryHeader"] and "FIRST-HEADER-KEEP" in state["firstHeader"] and "EVEN-HEADER-KEEP" in state["evenHeader"], "Word page region variants")
        if report["savedAndReopened"]:
            saved = package(SAMPLE / "word-updated.docx"); main_doc = ET.fromstring(saved["word/document.xml"])
            require(len(list(main_doc.iter(W + "commentRangeStart"))) == 1 and len(list(main_doc.iter(W + "ins"))) == 1 and len(list(main_doc.iter(W + "del"))) == 1, "saved OOXML annotations/revisions")
            require(len(list(main_doc.iter(W + "sdt"))) == 1 and len(list(main_doc.iter(W + "ffData"))) == 1, "saved OOXML forms")
        pdf = PdfReader(SAMPLE / "word.pdf"); text = "".join(page.extract_text() for page in pdf.pages)
        require(text.count("BODY-READY") == 1 and "BODY-OLD" not in text and "FIRST-HEADER-KEEP" in text, "Word PDF content")
        first, second = render(SAMPLE / "word.pdf", OUTPUT / "word"); tiles = []; metrics = []
        for a, b in zip(first, second, strict=True):
            delta = difference(a, b, 2); require(delta["meanAbsoluteChannelError"] < 4 and delta["changedPixelFraction"] < .06, "Word independent render difference")
            metrics.append(delta); tiles.extend([("Word Poppler", a), ("Word PDFium", b)])
        contact_sheet(tiles, OUTPUT)
        result["word"] = {"version": report["wordVersion"], "saved_reopened": report["savedAndReopened"], "pdf_pages": len(pdf.pages), "renderer_difference": metrics}
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / "report.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
