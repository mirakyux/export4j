# SPDX-License-Identifier: Apache-2.0
"""Independently inspect automatic merged widths and streaming row continuations."""
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from pypdf import PdfReader
from verify_advanced_pdf import difference, require
from verify_pdf_raster import references
from verify_production import contact_sheet
from verify_semantic_tables import table_borders
from verify_variable_columns import ink_coverage

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "examples/build/advanced-tables"
OUTPUT = ROOT / "build/verification/advanced-tables"


def automatic(path, available):
    reader = PdfReader(path, strict=True)
    require(len(reader.pages) == 1, "automatic merged table unexpectedly paginated")
    boxes = table_borders(reader)
    require(len(boxes) == 10, "automatic merged table has missing or synthetic borders")
    require(abs(boxes[0]["width"] - available) < .003, "fractional remainder did not fill the table width")
    final = boxes[-4:]
    require(abs(final[0]["width"] - 40) < .003, "fixed column width changed")
    require(all(20 - .003 <= box["width"] <= 80 + .003 for box in final[1:3]), "automatic width leaves its explicit bounds")
    require(abs(final[1]["width"] - final[2]["width"]) < .003, "equal automatic capacities received unequal constraints")
    merged = boxes[2]
    require(abs(merged["width"] - final[1]["width"] - final[2]["width"]) < .003, "merged rectangle ignores physical automatic widths")
    require(abs(merged["x"] - final[1]["x"]) < .003, "vertical owner moved to an incorrect column")
    require(abs(merged["y"] + merged["height"] - final[0]["y"]) < .003, "vertical owner does not cover both original rows")
    text = reader.pages[0].extract_text()
    require(re.findall(r"R0[123]", text) == ["R01", "R02", "R03"], "automatic table source rows differ")
    require(all(text.count(f"T-{index:02}") == 1 for index in range(1, 4)), "automatic table lost or repeated a tail cell")
    return reader, final[1]["width"]


def streaming(path):
    reader = PdfReader(path, strict=True)
    require(len(reader.pages) == 3, "split streaming page count differs")
    text = "\n".join(page.extract_text() for page in reader.pages)
    require(re.findall(r"LINE-\d{2}", text) == [f"LINE-{index:02}" for index in range(1, 12)], "split row source text was lost, repeated or reordered")
    require(all(page.extract_text().count("SPLIT HEADER") == 1 for page in reader.pages), "split row header repetition differs")
    require(all(text.count(f"AFTER-{letter}") == 1 for letter in "ABC"), "following source row was lost or duplicated")
    boxes = table_borders(reader)
    require(len(boxes) == 12, "split row anchor/border count differs")
    images, link_counts = 0, []
    for index, page in enumerate(reader.pages):
        require(re.findall(r"LINE-\d{2}", page.extract_text()) == [f"LINE-{value:02}" for value in
                (range(1, 6) if index == 0 else range(6, 11) if index == 1 else [11])], "whole lines split at an incorrect boundary")
        image_count = len(page.images); images += image_count
        require(image_count == (1 if index == 0 else 0), "atomic image repeated on continuation")
        annotations = [ref.get_object() for ref in page.get("/Annots", [])]
        uris = [annotation["/A"]["/URI"] for annotation in annotations]
        require(uris.count("https://example.com/once") == (1 if index == 0 else 0), "image link repeated or omitted")
        require(uris.count("https://example.com/lines") == (5 if index < 2 else 1), "text link lines lost or repeated")
        for box in [value for value in boxes if value["pageIndex"] == index]:
            require(box["x"] >= 16 - .003 and box["y"] >= 16 - .003
                    and box["x"] + box["width"] <= 304.003 and box["y"] + box["height"] <= 144.003,
                    "continued row escapes page margins")
        link_counts.append(len(uris))
    require(images == 1 and link_counts == [6, 5, 1], "continued media or link counts differ")
    return reader


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    wide, width = automatic(SOURCE / "auto-340.pdf", 300)
    narrow, narrow_width = automatic(SOURCE / "auto-150.pdf", 110)
    require(width > narrow_width and abs(narrow_width - 20) < .003, "automatic columns did not shrink toward minimum")
    require(json.loads((SOURCE / "split-counts.json").read_text()) == {
        "pages": 3, "rows": 2, "sourceCells": 6, "outputLines": 20, "readsAtPages": [1, 1, 3], "closes": 1}, "split row source lifecycle or output accounting differs")
    streaming(SOURCE / "split.pdf")
    ns = {"w": "http://schemas.openxmlformats.org/wordprocessingml/2006/main"}
    with ZipFile(SOURCE / "auto.docx") as package:
        xml = ET.fromstring(package.read("word/document.xml"))
        require(any(node.attrib.get("{" + ns["w"] + "}val") == "2" for node in xml.findall(".//w:gridSpan", ns)), "DOCX lost horizontal automatic merge")
        require(len(xml.findall(".//w:vMerge", ns)) == 2, "DOCX lost vertical owner or continuation")
    caller = Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip()) / "output"
    caller_auto = PdfReader(caller / "consumer-auto-spans.pdf", strict=True)
    caller_split = PdfReader(caller / "consumer-streaming-split.pdf", strict=True)
    require(len(caller_auto.pages) == 1 and len(caller_split.pages) == 2, "isolated caller page counts differ")
    text = "\n".join(page.extract_text() for page in caller_split.pages)
    require(re.findall(r"CALLER-\d", text) == [f"CALLER-{index}" for index in range(1, 6)], "isolated split caller source differs")
    boxes = table_borders(caller_auto)
    require(len(boxes) == 3 and abs(boxes[0]["width"] - boxes[1]["width"] - boxes[2]["width"]) < .003, "isolated automatic caller geometry differs")
    tiles, report = [], {}
    for name, path in (("wide", SOURCE / "auto-340.pdf"), ("narrow", SOURCE / "auto-150.pdf"),
                       ("split", SOURCE / "split.pdf"), ("caller-auto", caller / "consumer-auto-spans.pdf"),
                       ("caller-split", caller / "consumer-streaming-split.pdf")):
        first, second = references(path, OUTPUT / name)
        require(len(first) == len(second), "independent renderer page counts differ")
        comparisons = []
        for index, (a, b) in enumerate(zip(first, second, strict=True)):
            delta = difference(a, b, blur_radius=1); delta["uncoveredInkFractions"] = ink_coverage(a, b)
            require(max(delta["uncoveredInkFractions"]) < .001, "independent renderers disagree on visible ink")
            comparisons.append(delta); tiles.extend([(f"{name} Poppler {index+1}", a), (f"{name} PDFium {index+1}", b)])
        report[name] = {"pages": len(first), "renderDifferences": comparisons}
    contact_sheet(tiles, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print("Advanced tables: automatic bounds, merged owners, whole-line streaming, unique media, links, DOCX, callers and dual rendering passed")


if __name__ == "__main__":
    main()
