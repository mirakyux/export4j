# SPDX-License-Identifier: Apache-2.0
"""Independent HTML source tree, semantic edit, OOXML/PDF/web output and loss reconciliation."""
from __future__ import annotations

import base64
import csv
import json
from importlib.metadata import version
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
OUTPUT = ROOT / "build/verification/html-import"
W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"


def main():
    versions = {name: version(name) for name in ("pypdf", "pypdfium2", "Pillow", "markdown-it-py")}
    require(versions == {"pypdf": "6.10.0", "pypdfium2": "5.13.0", "Pillow": "12.3.0", "markdown-it-py": "4.0.0"}, "install pinned requirements")
    sample = ROOT / "examples/build/html-import"
    caller = Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip()) / "output"
    source = (sample / "source.html").read_text(encoding="utf-8")
    original = Tree(source).root
    require(len(original.find("table")) == 3, "independent source table count")
    require([node.attrs["rowspan"] for node in original.find("td") if "rowspan" in node.attrs] == ["2"], "source vertical span")
    require([node.attrs["colspan"] for node in original.find("th") if "colspan" in node.attrs] == ["2"], "source horizontal span")
    expected = ["HTML imported report", "TARGET-READY", "${literal}", "STRONG-HTML", "ITALIC-HTML", "BOTH-HTML", "UNDER-HTML", "STRIKE-HTML",
                "LINK-HTML", "HARD-HTML", "STEP-HTML", "NESTED-HTML", "NEXT-HTML", "MERGED-HEAD", "TALL-HTML", "RIGHT-HTML", "LAST-HTML",
                "BEFORE-INNER", "INNER-HTML", "AFTER-INNER", "QUOTE-HTML"]
    caller_expected = ["CALLER-HTML", "TARGET-READY", "LINK-HTML", "CALLER-CELL", "CALLER-QUOTE"]
    parser = MarkdownIt("commonmark").enable("table").enable("strikethrough")
    tiles = []; rendering = {}
    for directory, prefix, markers, table_count in ((sample, "report", expected, 3), (caller, "consumer-html-import", caller_expected, 1)):
        pdf = PdfReader(directory / (prefix + ".pdf")); require(len(pdf.pages) == 1, "HTML sample page count")
        text = pdf.pages[0].extract_text()
        for token in markers:
            require(text.count(token) == 1, "PDF imported text missing/duplicated: " + token)
        require("TARGET-OLD" not in text, "stale imported PDF edit")
        links = [str(value.get_object().get("/A", {}).get("/URI", "")) for value in pdf.pages[0].get("/Annots", [])]
        require(links == ["https://example.org/html"], "HTML imported PDF link")
        with ZipFile(directory / (prefix + ".docx")) as package:
            root = ET.fromstring(package.read("word/document.xml"))
            text = "".join(node.text or "" for node in root.iter(W + "t"))
            for marker in markers:
                require(text.count(marker) == 1, "OOXML import text missing/duplicated: " + marker)
            require(len(list(root.iter(W + "tbl"))) == table_count, "OOXML nested tables lost")
            if directory == sample:
                require([node.get(W + "val") for node in root.iter(W + "gridSpan")] == ["2"], "OOXML colspan")
                require(len(list(root.iter(W + "vMerge"))) == 2, "OOXML rowspan restart/continuation")
                require(len([name for name in package.namelist() if name.startswith("word/media/")]) == 1, "explicit media count")
                intro = next(node for node in root.iter(W + "p") if "TARGET-READY" in "".join(node.itertext()))
                require(intro.find(".//" + W + "color").get(W + "val") == "005522", "important CSS cascade lost")
                require(len(list(root.iter(W + "u"))) >= 1 and len(list(root.iter(W + "strike"))) >= 1, "HTML decorations lost")
        for suffix in ("html", "md"):
            value = (directory / (prefix + "." + suffix)).read_text(encoding="utf-8")
            tree = Tree(parser.render(value) if suffix == "md" else value); safe(tree)
            for marker in markers:
                require(tree.root.text().count(marker) == 1, "web HTML import content missing/duplicated: " + marker)
            require("TARGET-OLD" not in tree.root.text(), "stale imported web edit")
            if directory == sample:
                images = tree.root.find("img"); require(len(images) == 1, "HTML resource missing from web output")
                require(base64.b64decode(images[0].attrs["src"].split(",", 1)[1]) == (sample / "logo.png").read_bytes(), "explicit image bytes differ")
                if suffix == "html":
                    require(images[0].attrs["alt"] == "HTML-ALT", "HTML alt differs")
                    require(len(tree.root.find("table")) == 3, "HTML nested table structure differs")
                else:
                    image_tokens = [child for token in parser.parse(value) for child in token.children or [] if child.type == "image"]
                    require("".join(child.content for child in image_tokens[0].children or []) == "HTML-ALT", "Markdown AST alt differs")
        first, second = render(directory / (prefix + ".pdf"), OUTPUT / prefix)
        for a, b in zip(first, second, strict=True):
            # Use the established table-output check: two-pixel blur separates raster
            # antialiasing from geometry. Preserve raw metrics and inspect both pages.
            raw = difference(a, b); blurred = difference(a, b, 2)
            require(blurred["meanAbsoluteChannelError"] < 4 and blurred["changedPixelFraction"] < .06, "independent HTML PDF render mismatch")
            rendering[prefix] = {"raw": raw, "blur2": blurred}
            tiles.extend([(prefix + " Poppler", a), (prefix + " PDFium", b)])
    with (sample / "losses.tsv").open(encoding="utf-8", newline="") as stream:
        losses = list(csv.DictReader(stream, delimiter="\t"))
    require([row["feature"] for row in losses] == ["CSS_PROPERTY", "BLOCK_QUOTE"], "HTML actual loss set")
    for loss, token in zip(losses, ("float:left", "QUOTE-HTML"), strict=True):
        require(loss["action"] == "WARN" and loss["treatment"] == "DEGRADE", "HTML selected loss action")
        require(token in source[int(loss["start"]):int(loss["end"])], "original HTML loss range")
    require((caller / "consumer-html-import-losses.tsv").read_text().strip() == "BLOCK_QUOTE\tIGNORE\tDEGRADE", "HTML IGNORE loss disappeared")
    contact_sheet(tiles, OUTPUT)
    result = {"versions": versions, "pdf_pages": 2, "docx": 2, "web_outputs": 4, "actual_losses": 3, "rendering": rendering, "checks": "passed"}
    (OUTPUT / "report.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
