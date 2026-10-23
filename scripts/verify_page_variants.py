# SPDX-License-Identifier: Apache-2.0
"""Check semantic first/even page choice, native OOXML, round-trip layout and all rendered pages."""
import json
from pathlib import Path
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from pypdf import PdfReader
from verify_advanced_pdf import difference, require
from verify_inline_images import image_boxes
from verify_pdf_raster import references
from verify_production import contact_sheet
from verify_semantic_tables import check_characters, page_logical
from verify_streaming_rich_text import outlined_paths, painted_spans, physical_fonts, same_color

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "examples/build/page-variants"
OUTPUT = ROOT / "build/verification/page-variants"
W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
R = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}"
P = "{http://schemas.openxmlformats.org/package/2006/relationships}"
SVG = "{http://www.w3.org/2000/svg}"
BLUE, GREEN = (34/255, 68/255, 136/255), (68/255, 102/255, 34/255)
BODIES = ["A-ONE", "A-TWO", "A-THREE", "B-ONE", "B-TWO", "C-ONE", "C-TWO"]
BANDS = ["FIRST", "ODD", "EVEN", "EVEN", "ODD", "", "ODD"]
NUMBERS = [2, 3, 4, 8, 9, 10, 11]


def check_pdf(path):
    reader = PdfReader(path, strict=True)
    require(len(reader.pages) == 7, "variant sample page count")
    fonts = physical_fonts(reader, {"LiberationSans", "LiberationSans-Bold"})
    spans, outlines = painted_spans(reader), outlined_paths(reader)
    for index, (page, body, band, number) in enumerate(zip(reader.pages, BODIES, BANDS, NUMBERS, strict=True)):
        selected = [value for value in spans if value["page"] == index]
        require([value["text"] for value in selected if value["text"] in BODIES] == [body], "body order/uniqueness")
        content = next(value for value in selected if value["text"] == body)
        require(72 <= content["baseline"] <= 206, "body overlaps maximum reserved bands")
        require([value["text"] for value in selected if value["text"].endswith("-H")] == ([band+"-H"] if band else []), "first/displayed parity header selection")
        require([value["text"] for value in selected if value["text"].endswith("-F")] == ([band+"-F"] if band else []), "footer selection or explicit blank")
        logical = page_logical(page)
        require(logical.count(f"PAGE {number} OF 7") == (2 if band else 0), "final displayed page/physical count fields")
        for value in selected:
            if value["text"].endswith("-H"):
                require("LiberationSans-Bold" in value["font"] and same_color(value["color"], BLUE), "header physical role/color")
            if value["text"].endswith("-F"):
                require("LiberationSans-Bold" not in value["font"] and same_color(value["color"], GREEN), "footer physical role/color")
        ink = [value for value in outlines if value["page"] == index]
        require(len(ink) == (8 if band else 0), "field outline groups")
        for value in ink:
            require(same_color(value["color"], BLUE if value["top"] < 72 else GREEN), "field color")
            require(value["bottom"] <= 68 or 210 <= value["top"] < value["bottom"] <= 240, "field ink leaves chosen band")
    images = image_boxes(reader)
    require([value["page"] for value in images] == [2, 3], "variant image repeated on wrong pages")
    return spans, {"fonts": fonts, "images": len(images), "characters": check_characters(path, 16)}


def native():
    with ZipFile(SOURCE / "variants.docx") as package:
        main = ET.fromstring(package.read("word/document.xml"))
        settings = ET.fromstring(package.read("word/settings.xml"))
        rels = {node.get("Id"): "word/" + node.get("Target") for node in ET.fromstring(package.read("word/_rels/document.xml.rels")).findall(P+"Relationship")}
        require(settings.find(W+"evenAndOddHeaders") is not None, "standard even/odd switch")
        sections = list(main.iter(W+"sectPr"))
        require(len(sections) == 3, "native section count")
        for index, section in enumerate(sections):
            require((section.find(W+"titlePg") is not None) == (index != 1), "native first-page switch")
            for kind in ("header", "footer"):
                refs = {node.get(W+"type"): rels[node.get(R+"id")] for node in section.findall(W+kind+"Reference")}
                require(set(refs) == ({"default", "first", "even"} if index != 1 else {"default", "even"}), "native variant references")
                for variant, part in refs.items():
                    root = ET.fromstring(package.read(part))
                    text = "".join(node.text or "" for node in root.iter(W+"t"))
                    if index == 2 and variant == "first":
                        require(text == "" and not list(root.iter(W+"drawing")), "blank first-page native content")
                    else:
                        prefix = {"default": "ODD", "first": "FIRST", "even": "EVEN"}[variant]
                        require(prefix + ("-H" if kind == "header" else "-F") in text, "native band content")
                        require([node.get(W+"instr").split()[0] for node in root.iter(W+"fldSimple")] == ["PAGE", "NUMPAGES"], "native page fields")
        require(sum(node.get(W+"type") == "page" for node in main.iter(W+"br")) == 4, "native explicit page breaks")


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    original, report = check_pdf(SOURCE / "variants.pdf")
    back, report_back = check_pdf(SOURCE / "roundtrip.pdf")
    require(len(original) == len(back), "native round trip span count")
    for left, right in zip(original, back, strict=True):
        require(all(left[key] == right[key] for key in ("page", "text", "size", "color")), "native round trip text/style differs")
        require(all(abs(left[key] - right[key]) < .01 for key in ("x", "baseline", "right")), "native round trip placement differs")
    root = ET.parse(SOURCE / "variants.svg").getroot()
    pages = root.findall(SVG+"svg")
    require(len(pages) == 7, "SVG pages")
    for index, page in enumerate(pages):
        labels = [node.get("aria-label") for node in page.iter() if node.get("aria-label")]
        require(labels.count(BODIES[index]) == 1, "SVG body source")
        require(labels.count(f"PAGE {NUMBERS[index]} OF 7") == (2 if BANDS[index] else 0), "SVG final variant fields")
    native()
    caller = Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip()) / "output/consumer-page-variants.pdf"
    reader = PdfReader(caller, strict=True)
    require(len(reader.pages) == 2 and "CALLER-EVEN" in reader.pages[0].extract_text()
            and "CALLER-ODD" in reader.pages[1].extract_text(), "caller first-even fallback")
    tiles, differences = [], {}
    for name, path in (("variants", SOURCE / "variants.pdf"), ("roundtrip", SOURCE / "roundtrip.pdf"), ("caller", caller)):
        differences[name] = []
        for index, (left, right) in enumerate(zip(*references(path, OUTPUT / name), strict=True)):
            diff = difference(left, right, blur_radius=1)
            require(diff["meanAbsoluteChannelError"] < 3 and diff["changedPixelFraction"] < .04, "independent render disagreement")
            differences[name].append(diff)
            tiles.extend([(f"{name} Poppler {index+1}", left), (f"{name} PDFium {index+1}", right)])
    contact_sheet(tiles, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps({"source": report, "roundtrip": report_back, "renderDifferences": differences}, indent=2)+"\n")
    print("Page variants: first/even/blank selection, final fields, images, native OOXML, round-trip placement and 16-page dual rendering passed")


if __name__ == "__main__":
    main()
