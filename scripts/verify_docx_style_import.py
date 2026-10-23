# SPDX-License-Identifier: Apache-2.0
"""Checks inherited OOXML source and concrete edited PDF, DOCX and HTML values independently."""
from __future__ import annotations

import json
from pathlib import Path
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from pypdf import PdfReader
from verify_adr_form import render
from verify_advanced_pdf import difference, require
from verify_production import contact_sheet
from verify_web_exports import Tree

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build/verification/docx-style-import"
W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"


def main():
    sample = ROOT / "examples/build/docx-style-import"
    caller = Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip()) / "output"
    with ZipFile(sample / "source.docx") as archive:
        source_styles = ET.fromstring(archive.read("word/styles.xml"))
        require(len(list(source_styles.iter(W + "basedOn"))) == 2, "source inheritance graph")
        require(len(list(source_styles.iter(W + "docDefaults"))) == 1, "source defaults")
        source_main = ET.fromstring(archive.read("word/document.xml"))
        require(len(list(source_main.iter(W + "sz"))) == 0, "fixture must derive font sizes from styles")
    tiles = []; metrics = {}
    for directory, prefix, markers, size, color in ((sample, "report", ["Styled import", "STYLE-READY", "ACCENT-KEEP", "STYLE-UNCHANGED", "HEADER-STYLE"], 12, "224466"),
                                                   (caller, "consumer-style", ["CALLER-STYLE-READY"], 13, "235711")):
        pdf = PdfReader(directory / (prefix + ".pdf")); require(len(pdf.pages) == 1, "styled import page count")
        text = pdf.pages[0].extract_text()
        for marker in markers: require(text.count(marker) == 1, "styled PDF text: " + marker)
        require("STYLE-OLD" not in text, "stale style import text")
        runs = []
        def visit(value, cm, tm, font, font_size):
            if value.strip(): runs.append((value.strip(), font_size, str(font.get("/BaseFont", "")) if font else ""))
        pdf.pages[0].extract_text(visitor_text=visit)
        require(any("STYLE-READY" in value and abs(font_size - size) < .01 for value, font_size, _ in runs), "inherited PDF size")
        if directory == sample:
            require(any("Styled import" in value and abs(font_size - 18) < .01 and "Bold" in font for value, font_size, font in runs), "physical bold title font")
            require(any("ACCENT-KEEP" in value and "Italic" in font for value, _, font in runs), "physical italic character style")
        with ZipFile(directory / (prefix + ".docx")) as archive:
            main_doc = ET.fromstring(archive.read("word/document.xml"))
            edited = next(node for node in main_doc.iter(W + "r") if "STYLE-READY" in "".join(node.itertext()))
            require(edited.find(".//" + W + "sz").get(W + "val") == str(size * 2), "materialized OOXML size")
            require(edited.find(".//" + W + "color").get(W + "val") == color, "materialized OOXML color")
            if directory == sample:
                header = ET.fromstring(archive.read("word/header1.xml"))
                require(header.find(".//" + W + "sz").get(W + "val") == "20", "header inherited font size")
                require(len(list(main_doc.iter(W + "u"))) == 1, "character underline")
        html = Tree((directory / (prefix + ".html")).read_text(encoding="utf-8"))
        for marker in markers: require(html.root.text().count(marker) == 1, "styled HTML text: " + marker)
        require("#" + color in (directory / (prefix + ".html")).read_text(), "styled HTML color")
        first, second = render(directory / (prefix + ".pdf"), OUTPUT / prefix)
        for a, b in zip(first, second, strict=True):
            delta = difference(a, b, 2); require(delta["meanAbsoluteChannelError"] < 4 and delta["changedPixelFraction"] < .06, "style import render mismatch")
            metrics[prefix] = delta; tiles.extend([(prefix + " Poppler", a), (prefix + " PDFium", b)])
    require((sample / "pdf-losses.tsv").read_text().count("IMPORT\tOOXML_MARKUP") == 1, "style graph actual loss")
    contact_sheet(tiles, OUTPUT)
    result = {"pdf_pages": 2, "docx": 2, "html": 2, "source_graph": "independently checked", "rendering": metrics, "checks": "passed"}
    (OUTPUT / "report.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8"); print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
