# SPDX-License-Identifier: Apache-2.0
"""Inspect edited PDF, standard OOXML and independently parsed HTML/Markdown; render every new PDF page."""
from __future__ import annotations

import csv
from importlib.metadata import version
import json
from pathlib import Path
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from pypdf import PdfReader
from verify_adr_form import render
from verify_advanced_pdf import difference, require
from verify_production import contact_sheet
from verify_web_exports import Tree
from markdown_it import MarkdownIt

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build/verification/semantic-editing"
W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"


def inspect_pdf(path, expected, absent):
    reader = PdfReader(path)
    require(len(reader.pages) == 1, "editing sample must stay on one page")
    text = reader.pages[0].extract_text()
    for token in expected:
        require(text.count(token) == 1, "missing/duplicated edited PDF content: " + token)
    for token in absent:
        require(token not in text, "stale edited PDF content: " + token)
    urls = [str(item.get_object().get("/A", {}).get("/URI", "")) for item in reader.pages[0].get("/Annots", [])]
    if "EDITED" in expected:
        require(sorted(urls) == ["https://example.org/edit", "https://example.org/keep"], "edited or untouched PDF link changed")
    return text


def inspect_docx(path, expected, caller=False):
    with ZipFile(path) as package:
        document = ET.fromstring(package.read("word/document.xml"))
        text = "".join(node.text or "" for node in document.iter(W + "t"))
        for token in expected:
            if token.startswith("HEADER"):
                continue
            require(text.count(token) == 1, "standard OOXML content differs: " + token)
        require("OLD" not in text and "EDITME" not in text, "stale OOXML source")
        if not caller:
            require(len(list(document.iter(W + "tbl"))) == 2, "nested table structure lost")
            headers = [ET.fromstring(package.read(name)) for name in package.namelist() if name.startswith("word/header") and name.endswith(".xml")]
            footers = [ET.fromstring(package.read(name)) for name in package.namelist() if name.startswith("word/footer") and name.endswith(".xml")]
            require(len(headers) == len(footers) == 1, "repeating regions lost")
            require("".join(node.text or "" for node in headers[0].iter(W + "t")) == "HEADER-READY", "header edit lost")
            require([node.get(W + "instr") for node in footers[0].iter(W + "fldSimple")] == ["PAGE"], "native page field lost")
            edited = [run for run in document.iter(W + "r") if "".join(node.text or "" for node in run.iter(W + "t")) == "EDITED"]
            require(len(edited) == 1 and edited[0].find(W + "rPr/" + W + "color").get(W + "val").lower() == "2456a0", "replacement first-run style differs")


def main():
    versions = {name: version(name) for name in ("pypdf", "pypdfium2", "Pillow", "markdown-it-py")}
    require(versions == {"pypdf": "6.10.0", "pypdfium2": "5.13.0", "Pillow": "12.3.0", "markdown-it-py": "4.0.0"}, "install pinned verification requirements")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    sample = ROOT / "examples/build/semantic-editing"
    caller = Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip()) / "output"
    expected = ["EDITED", "KEEP-LINK", "INNER-HEADER", "INNER-READY", "CELL-READY", "BEFORE-NESTED", "AFTER-NESTED", "INSERTED-ONCE", "TAIL-UNCHANGED", "HEADER-READY"]
    inspect_pdf(sample / "before.pdf", ["EDITME", "INNER-OLD", "CELL-OLD", "HEADER-OLD", "TAIL-UNCHANGED"], ["INSERTED-ONCE"])
    inspect_pdf(sample / "edited.pdf", expected, ["EDITME", "OLD"])
    inspect_pdf(caller / "consumer-semantic-editing.pdf", ["CALLER-EDITED", "UNCHANGED-ONLY"], ["CALLER-OLD"])
    inspect_docx(sample / "edited.docx", expected)
    inspect_docx(caller / "consumer-semantic-editing.docx", ["CALLER-EDITED", "UNCHANGED-ONLY"], True)
    for directory, prefix, tokens in ((sample, "edited", expected), (caller, "consumer-semantic-editing", ["CALLER-EDITED", "UNCHANGED-ONLY"])):
        for suffix in ("html", "md"):
            raw = (directory / (prefix + "." + suffix)).read_text(encoding="utf-8")
            html = MarkdownIt("commonmark").enable("table").render(raw) if suffix == "md" else raw
            tree = Tree(html).root
            for token in tokens:
                require(tree.text().count(token) == 1, "web edit content differs: " + token)
            require("OLD" not in tree.text() and "EDITME" not in tree.text(), "stale web content")
            if directory == sample:
                require("https://example.org/keep" in [link.attrs.get("href") for link in tree.find("a")], "untouched web link lost")
    with (sample / "losses.tsv").open(encoding="utf-8", newline="") as source:
        losses = list(csv.DictReader(source, delimiter="\t"))
    require(losses == [{"phase": "ACTUAL", "feature": "MIXED_RUN_STYLE", "action": "WARN", "treatment": "DEGRADE", "block": "intro", "start": "0", "end": "6"}], "actual loss report mismatch")
    tiles = []
    for label, path in (("source", sample / "before.pdf"), ("edited", sample / "edited.pdf"), ("consumer", caller / "consumer-semantic-editing.pdf")):
        poppler, pdfium = render(path, OUTPUT / label)
        require(len(poppler) == len(pdfium) == 1, "rendered page count mismatch")
        for first, second in zip(poppler, pdfium, strict=True):
            metrics = difference(first, second)
            require(metrics["meanAbsoluteChannelError"] < 1.5, "independent renderer difference exceeded threshold")
            tiles.extend([(label + " Poppler", first), (label + " PDFium", second)])
    contact_sheet(tiles, OUTPUT)
    report = {"versions": versions, "pdf_pages": 3, "docx": 2, "web_outputs": 4, "actual_losses": len(losses), "checks": "passed"}
    (OUTPUT / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
