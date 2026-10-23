# SPDX-License-Identifier: Apache-2.0
"""Independent recursive table, vertical continuation, field, media and rich flow verification."""
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from zipfile import ZipFile
from pypdf import PdfReader
from verify_advanced_pdf import difference, require
from verify_pdf_raster import references
from verify_production import contact_sheet
from verify_inline_images import image_boxes
from verify_semantic_tables import annotations, check_characters, page_logical

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "examples/build/recursive-tables"
OUTPUT = ROOT / "build/verification/recursive-tables"
W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    tiles, report = [], {}
    for name in ("nested", "columns", "rich"):
        path = SOURCE / f"{name}.pdf"
        reader = PdfReader(path, strict=True)
        require(len(reader.pages) > 1, "fixture did not exercise continuation")
        texts = [page.extract_text() + "\n" + "\n".join(page_logical(page)) for page in reader.pages]
        compact = "".join("".join(texts).split())
        tokens = [f"RICH-{line:02d}" for line in range(1, 31)] if name == "rich" else [
            "BLOCK-BEGIN", *[f"ROW-{row:02d}" for row in range(1, 25)], "BLOCK-END", "SIDE-A", "SIDE-B", "DOCUMENT-END"]
        require(all(compact.count(token) == 1 for token in tokens), "source content lost or duplicated")
        ordered = tokens if name == "rich" else tokens[:-4]
        require([compact.index(token) for token in ordered] == sorted(compact.index(token) for token in ordered), "source flow reordered")
        if name != "rich":
            links, images = annotations(reader), image_boxes(reader)
            require({link["uri"] for link in links} == {"https://example.com/recursive", "https://example.com/marker"}, "continuation link targets")
            require(len(images) == 1 and images[0]["width"] == images[0]["height"] == 8, "continuation image lost or repeated")
            ending = next(index for index, text in enumerate(texts) if "BLOCK-END" in text)
            require(re.search(rf"BLOCK-END\s+P=\s*{ending+1}\s+N=\s*{len(reader.pages)}", texts[ending]), "final continuation fields")
            require(compact.count("INNER-HEAD") > 1 and compact.count("MIDDLE-HEAD") > 1, "nested headers did not repeat")
            svg = ET.parse(SOURCE / f"{name}.svg").getroot()
            require(len(list(svg.iter("{http://www.w3.org/2000/svg}image"))) == 1, "SVG continuation media")
        renderings = references(path, OUTPUT / name)
        differences = []
        for index, (first, second) in enumerate(zip(*renderings, strict=True)):
            diff = difference(first, second, blur_radius=1)
            require(diff["meanAbsoluteChannelError"] < 3 and diff["changedPixelFraction"] < .04, "independent render disagreement")
            differences.append(diff)
            tiles.extend([(f"{name} Poppler {index+1}", first), (f"{name} PDFium {index+1}", second)])
        report[name] = {"pages": len(reader.pages), "characters": check_characters(path, 16), "renderDifferences": differences}
    with ZipFile(SOURCE / "nested.docx") as package:
        document = ET.fromstring(package.read("word/document.xml"))
        tables = list(document.iter(W + "tbl"))
        require(len(tables) == 3 and not list(document.iter(W + "cantSplit")), "native recursive table split policy")
        require(len(list(tables[0].iter(W + "tbl"))) == 3, "native table hierarchy")
        merges = [node.get(W + "val", "continue") for node in document.iter(W + "vMerge")]
        require(merges == ["restart", "continue"], "native vertical merge structure")
        require(len([name for name in package.namelist() if name.startswith("word/media/")]) == 1, "DOCX media")
    caller = Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip()) / "output"
    for name in ("consumer-recursive", "consumer-recursive-component", "consumer-rich-flow"):
        path = caller / f"{name}.pdf"
        reader = PdfReader(path, strict=True)
        text = "".join(page.extract_text() + "".join(page_logical(page)) for page in reader.pages)
        tokens = ["ALPHA", "BETA", "GAMMA", "DELTA", "EPSILON"] if name.endswith("rich-flow") else [
            "ONE", "TWO", "THREE", "FOUR", "FIVE", "SIX", "SEVEN", "EIGHT", "CALLER-END"]
        require(all(text.count(token) == 1 for token in tokens), "caller source lost or duplicated")
        require([text.index(token) for token in tokens] == sorted(text.index(token) for token in tokens), "caller source order")
        for index, (first, second) in enumerate(zip(*references(path, OUTPUT / name), strict=True)):
            diff = difference(first, second, blur_radius=1)
            require(diff["meanAbsoluteChannelError"] < 3 and diff["changedPixelFraction"] < .04, "caller render disagreement")
            tiles.extend([(f"{name} Poppler {index+1}", first), (f"{name} PDFium {index+1}", second)])
        report[name] = {"pages": len(reader.pages)}
    with ZipFile(caller / "consumer-recursive.docx") as package:
        document = ET.fromstring(package.read("word/document.xml"))
        require(len(list(document.iter(W + "tbl"))) == 3, "caller native recursive hierarchy")
    contact_sheet(tiles, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print("Recursive tables and rich flow: unique source, nested headers, final fields, links, media, native DOCX and dual rendering passed")


if __name__ == "__main__":
    main()
