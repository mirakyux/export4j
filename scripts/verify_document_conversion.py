# SPDX-License-Identifier: Apache-2.0
"""Independent checks of prepared encodings, actual losses, objects and import/edit/export chains."""
from __future__ import annotations

import base64
import csv
import json
from pathlib import Path
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from markdown_it import MarkdownIt
from pypdf import PdfReader
from verify_adr_form import render
from verify_advanced_pdf import difference, require
from verify_production import contact_sheet
from verify_web_exports import Tree, safe

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build/verification/document-conversion"
W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"


def main():
    sample = ROOT / "examples/build/document-conversion"
    caller = Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip()) / "output"
    parser = MarkdownIt("commonmark").enable("table")
    expected = ["Conversion report", "QUOTE-CONVERSION", "READY-CONVERSION", "UNCHANGED-COLOR", "LINK-CONVERSION", "HEAD-CONVERSION", "CELL-CONVERSION", "42"]
    isolated = ["CALLER-CONVERSION", "CALLER-READY", "UNCHANGED-COLOR", "QUOTE-CONVERSION", "LINK-CONVERSION"]
    tiles = []; metrics = {}; pages = 0
    for directory, prefix, tokens in ((sample, "report", expected), (caller, "consumer-conversion", isolated)):
        pdf = PdfReader(directory / (prefix + ".pdf")); require(len(pdf.pages) == 1, "conversion page count")
        with ZipFile(directory / (prefix + ".docx")) as package:
            document = ET.fromstring(package.read("word/document.xml"))
            text = "".join(node.text or "" for node in document.iter(W + "t"))
            for token in tokens: require(text.count(token) == 1, "DOCX conversion content: " + token)
            if directory == sample:
                require(len(list(document.iter(W + "tbl"))) == 1, "converted table")
                media = [name for name in package.namelist() if name.startswith("word/media/")]
                require(len(media) == 1 and package.read(media[0]) == (sample / "logo.png").read_bytes(), "converted media bytes")
                untouched = next(node for node in document.iter(W + "p") if "UNCHANGED-COLOR" in "".join(node.itertext()))
                require(untouched.find(".//" + W + "color").get(W + "val") == "123456", "untouched DOCX color")
        for suffix in ("html", "md"):
            value = (directory / (prefix + "." + suffix)).read_text(encoding="utf-8")
            tree = Tree(value if suffix == "html" else parser.render(value)); safe(tree)
            for token in tokens: require(tree.root.text().count(token) == 1, "Web conversion content: " + token)
            if directory == sample:
                require(base64.b64decode(tree.root.find("img")[0].attrs["src"].split(",", 1)[1]) == (sample / "logo.png").read_bytes(), "Web conversion image bytes")
        for pdf_name, pdf_tokens in ((prefix, tokens), (("from-docx" if directory == sample else "consumer-conversion-docx"), [value.replace("READY-CONVERSION", "DOCX-READY").replace("CALLER-READY", "DOCX-CALLER-READY") for value in tokens])):
            pdf_path = directory / (pdf_name + ".pdf"); pdf = PdfReader(pdf_path); require(len(pdf.pages) == 1, "converted DOCX PDF pages")
            text = pdf.pages[0].extract_text()
            for token in pdf_tokens: require(text.count(token) == 1, "converted PDF text: " + token)
            require("TURN OLD" not in text, "stale source text")
            links = [str(item.get_object().get("/A", {}).get("/URI", "")) for item in pdf.pages[0].get("/Annots", [])]
            require(links == ["https://example.org/conversion"], "converted PDF link")
            first, second = render(pdf_path, OUTPUT / pdf_name)
            for a, b in zip(first, second, strict=True):
                raw = difference(a, b); blurred = difference(a, b, 2)
                require(blurred["meanAbsoluteChannelError"] < 4 and blurred["changedPixelFraction"] < .06, "conversion render mismatch")
                metrics[pdf_name] = {"raw": raw, "blur2": blurred}; pages += 1
                tiles.extend([(pdf_name + " Poppler", a), (pdf_name + " PDFium", b)])
    losses = {}
    for path in sample.glob("*.losses.tsv"):
        with path.open(encoding="utf-8", newline="") as stream: losses[path.name] = list(csv.DictReader(stream, delimiter="\t"))
    md = losses["report.md.losses.tsv"]
    require({row["stage"] for row in md} == {"IMPORT", "EDIT", "EXPORT"}, "three-stage actual loss report")
    require(any(row["feature"] == "TEXT_STYLE" and row["block"] == "untouched" for row in md), "unedited loss omitted")
    ignored = losses["ignored.md.losses.tsv"]
    require(len(ignored) == len(md) and all(row["action"] == "IGNORE" for row in ignored), "IGNORE lost actual losses")
    require((sample / "ignored.md").read_bytes() == (sample / "report.md").read_bytes(), "WARN/IGNORE content differs")
    require([row["stage"] for row in losses["report.pdf.losses.tsv"]] == ["IMPORT", "EDIT"], "PDF stage reports")
    require(not losses["from-docx.pdf.losses.tsv"] and not losses["from-markdown.html.losses.tsv"], "unexpected modeled conversion loss")
    caller_losses = (caller / "consumer-conversion.md.losses.tsv").read_text().splitlines()
    require("IMPORT\tBLOCK_QUOTE\tIGNORE" in caller_losses and "EXPORT\tTEXT_STYLE\tIGNORE" in caller_losses, "isolated conversion actual losses")
    for directory, name, tokens in ((sample, "from-markdown.html", ["Markdown conversion", "MD-READY", "STRONG-CONVERSION"]),
                                     (caller, "consumer-conversion-markdown.html", ["CALLER-MARKDOWN", "MD-CALLER-READY"]),
                                     (sample, "from-docx.html", ["DOCX-READY", "UNCHANGED-COLOR"])):
        tree = Tree((directory / name).read_text(encoding="utf-8")); safe(tree)
        for token in tokens: require(tree.root.text().count(token) == 1, "real conversion factory: " + token)
    contact_sheet(tiles, OUTPUT)
    result = {"pdf_pages": pages, "docx": 2, "web_outputs": 8, "actual_loss_count": sum(map(len, losses.values())), "rendering": metrics, "checks": "passed"}
    (OUTPUT / "report.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
