# SPDX-License-Identifier: Apache-2.0
"""Verify general Markdown input independently, then inspect edited PDF/DOCX/HTML/Markdown and actual losses."""
from __future__ import annotations

import base64
import csv
from importlib.metadata import version
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
OUTPUT = ROOT / "build/verification/markdown-import"
W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"


def pdf(path, expected, with_image):
    reader = PdfReader(path)
    require(len(reader.pages) == 1, "import sample page count differs")
    text = reader.pages[0].extract_text()
    for token in expected:
        require(text.count(token) == 1, "missing/duplicated imported PDF text: " + token)
    require("TARGET-OLD" not in text, "stale edit target")
    resources = reader.pages[0]["/Resources"]
    fonts = [str(reference.get_object().get("/BaseFont")) for reference in resources.get("/Font", {}).values()]
    if with_image:
        require(any("BoldItalic" in face for face in fonts) and any("-Italic" in face for face in fonts), "physical emphasis variants missing")
        images = [value.get_object() for value in resources.get("/XObject", {}).values() if value.get_object().get("/Subtype") == "/Image"]
        require(len(images) == 1 and int(images[0]["/Width"]) == 12 and int(images[0]["/Height"]) == 12, "explicit image missing")
    links = [str(value.get_object().get("/A", {}).get("/URI", "")) for value in reader.pages[0].get("/Annots", [])]
    require(links == ["https://example.org/import"], "imported PDF link changed")


def docx(path, expected, with_image):
    with ZipFile(path) as package:
        root = ET.fromstring(package.read("word/document.xml"))
        text = "".join(node.text or "" for node in root.iter(W + "t"))
        for token in expected:
            require(text.count(token) == 1, "missing/duplicated imported OOXML content: " + token)
        require("TARGET-OLD" not in text, "stale OOXML target")
        require(len(list(root.iter(W + "tbl"))) == 1, "semantic GFM table missing")
        if with_image:
            require(len([name for name in package.namelist() if name.startswith("word/media/")]) == 1, "registered OOXML media missing")
            levels = [node.get(W + "val") for node in root.findall(".//" + W + "numPr/" + W + "ilvl")]
            require(levels == ["0", "1", "0", "0"], "ordered/bullet nested levels changed")
            styles = ET.fromstring(package.read("word/styles.xml"))
            by_id = {node.get(W + "styleId"): node for node in styles.iter(W + "style")}
            require(by_id["bold"].find(W + "rPr/" + W + "b") is not None, "strong style lost")
            require(by_id["italic"].find(W + "rPr/" + W + "i") is not None, "italic style lost")
            require(any(run.find(W + "rPr/" + W + "strike") is not None for run in root.iter(W + "r")), "GFM strike style lost")


def main():
    versions = {name: version(name) for name in ("pypdf", "pypdfium2", "Pillow", "markdown-it-py")}
    require(versions == {"pypdf": "6.10.0", "pypdfium2": "5.13.0", "Pillow": "12.3.0", "markdown-it-py": "4.0.0"}, "install pinned verification requirements")
    sample = ROOT / "examples/build/markdown-import"
    caller = Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip()) / "output"
    source = (sample / "source.md").read_text(encoding="utf-8")
    parser = MarkdownIt("commonmark").enable("table").enable("strikethrough")
    tokens = parser.parse(source)
    require(sum(node.type == "fence" for node in tokens) == sum(node.type == "blockquote_open" for node in tokens) == 1, "source code/quote corpus differs")
    require(sum(node.type == "table_open" for node in tokens) == 1, "independent source table parse")
    original = Tree(parser.render(source)).root
    require(len(original.find("strong")) == 2 and len(original.find("em")) == 2 and len(original.find("s")) == 1, "source inline grammar differs")
    require([node.attrs.get("start") for node in original.find("ol")] == ["3", None], "source nested numbering differs")
    expected = ["Imported report", "STRONG-ONLY", "ITALIC-ONLY", "BOTH-ONLY", "STRIKE-ONLY", "LINK-ONLY", "TARGET-READY", "${untouched}",
                "SOFT-ONLY", "HARD-ONLY", "STEP-ONLY", "NESTED-ONLY", "NEXT-ONLY", "BULLET-ONLY", "CELL-ONLY", "VALUE-ONLY", "QUOTE-ONLY", "CODE-ONLY <tag> & value;"]
    caller_expected = ["CALLER-IMPORT", "TARGET-READY", "LINK-ONLY", "CALLER-CELL", "CALLER-QUOTE"]
    pdf(sample / "report.pdf", expected, True)
    pdf(caller / "consumer-markdown-import.pdf", caller_expected, False)
    docx(sample / "report.docx", expected, True)
    docx(caller / "consumer-markdown-import.docx", caller_expected, False)
    for directory, prefix, markers in ((sample, "report", expected), (caller, "consumer-markdown-import", caller_expected)):
        for suffix in ("html", "md"):
            value = (directory / (prefix + "." + suffix)).read_text(encoding="utf-8")
            parsed = Tree(parser.render(value) if suffix == "md" else value)
            safe(parsed); tree = parsed.root
            for marker in markers:
                require(tree.text().count(marker) == 1, "missing/duplicated web import text: " + marker)
            require("TARGET-OLD" not in tree.text(), "stale web edit target")
            if directory == sample:
                images = tree.find("img"); require(len(images) == 1, "web image count")
                if suffix == "md":
                    # markdown-it-py 4.0.0's HTML renderer omits escaped text_special
                    # children from image alt. Inspect the independent parser's AST.
                    image_tokens = [child for token in parser.parse(value) for child in token.children or [] if child.type == "image"]
                    require(len(image_tokens) == 1 and "".join(child.content for child in image_tokens[0].children or []) == "IMAGE-ALT", "imported Markdown image alt changed")
                else:
                    require(images[0].attrs["alt"] == "IMAGE-ALT", "imported image alt changed")
                require(base64.b64decode(images[0].attrs["src"].split(",", 1)[1]) == (sample / "logo.png").read_bytes(), "resource bytes changed")
    with (sample / "losses.tsv").open(encoding="utf-8", newline="") as stream:
        losses = list(csv.DictReader(stream, delimiter="\t"))
    require([row["feature"] for row in losses] == ["BLOCK_QUOTE", "CODE_BLOCK"], "actual losses differ")
    for loss in losses:
        require(loss["action"] == "WARN" and loss["treatment"] == "DEGRADE", "import treatment differs")
        selected = source[int(loss["start"]):int(loss["end"])]
        require(("QUOTE-ONLY" if loss["feature"] == "BLOCK_QUOTE" else "CODE-ONLY") in selected, "loss source range differs")
    require((caller / "consumer-markdown-import-losses.tsv").read_text().strip() == "BLOCK_QUOTE\tIGNORE\tDEGRADE", "IGNORE actual loss disappeared")
    tiles = []
    for label, path in (("example", sample / "report.pdf"), ("consumer", caller / "consumer-markdown-import.pdf")):
        poppler, pdfium = render(path, OUTPUT / label)
        for first, second in zip(poppler, pdfium, strict=True):
            require(difference(first, second)["meanAbsoluteChannelError"] < 1.5, "independent renderer mismatch")
            tiles.extend([(label + " Poppler", first), (label + " PDFium", second)])
    contact_sheet(tiles, OUTPUT)
    report = {"versions": versions, "pdf_pages": 2, "docx": 2, "web_outputs": 4, "actual_losses": 3, "checks": "passed"}
    (OUTPUT / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
