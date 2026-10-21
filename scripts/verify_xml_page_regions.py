# SPDX-License-Identifier: Apache-2.0
"""Independently check XML band geometry, final page fields, actual fonts/colors and every rendered page."""
from __future__ import annotations

import argparse
from importlib import metadata
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

import pypdfium2 as pdfium
from pypdf import PdfReader
from verify_adr_form import render
from verify_advanced_pdf import difference, require
from verify_production import actual_text, contact_sheet
from verify_streaming_rich_text import outlined_paths, painted_spans, physical_fonts, same_color

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build/verification/xml-page-regions"
BLUE, GREEN, PURPLE = (34/255, 68/255, 136/255), (51/255, 102/255, 51/255), (136/255, 51/255, 102/255)
SVG = "{http://www.w3.org/2000/svg}"


def close(actual, expected, label, tolerance=.01):
    require(abs(actual - expected) <= tolerance, f"{label}: {actual} versus {expected}")


def check(path, caller):
    width, height, margin, body_top, body_bottom, footer_top, count = (
        (320, 220, 18, 48, 172, 178, 2) if caller else (420, 360, 24, 72, 304, 312, 3))
    header = "CALLER-HEADER-PUBLIC-API" if caller else "EXPORT4J / XML PAGE REGIONS"
    prefix, expected_rows = ("CALLER-BODY-", 8) if caller else ("BODY-", 17)
    reader = PdfReader(path)
    require(len(reader.pages) == count, "XML band page count differs")
    require(not reader.trailer["/Root"].get("/AcroForm"), "static XML bands gained a form")
    fonts = physical_fonts(reader, {"LiberationSans", "LiberationSans-Bold"})
    spans, outlines = painted_spans(reader), outlined_paths(reader)
    found_rows, logical = [], []
    for index, page in enumerate(reader.pages):
        close(float(page.mediabox.width), width, "page width")
        close(float(page.mediabox.height), height, "page height")
        require(page.get("/Rotate", 0) == 0, "page unexpectedly rotated")
        # Inspect ActualText independently from the nonpainting anchors and glyph paths.
        values = actual_text(PdfReaderPage(page))
        footer = f"PAGE {index + 1} OF {count}"
        require(values.count(footer) == 1, "final page field logical value missing/repeated: " + footer)
        logical.append(footer)
        selected = [span for span in spans if span["page"] == index]
        headers = [span for span in selected if span["text"] == header]
        require(len(headers) == 1, "repeating header missing or duplicated")
        brand = headers[0]
        require("LiberationSans-Bold" in brand["font"] and same_color(brand["color"], BLUE), "header theme font/color lost")
        close(brand["size"], 10 if caller else 11, "header point size")
        close(brand["x"], margin, "header x")
        require(margin < brand["baseline"] < body_top, "header overlaps body")
        if not caller:
            for text, color in (("REPORT-Q4", (0, 0, 0)), ("THEME-OVERRIDE", PURPLE)):
                matching = [span for span in selected if span["text"] == text]
                require(len(matching) == 1 and same_color(matching[0]["color"], color), "local XML reset/override lost")
                require("LiberationSans-Bold" not in matching[0]["font"], "empty role did not select default fonts")
                close(matching[0]["size"], 9, "local reset point size")
        for span in selected:
            if span["text"].startswith(prefix):
                found_rows.extend(re.findall(re.escape(prefix) + r"(\d{2})", span["text"]))
                require(body_top <= span["baseline"] <= body_bottom, "body enters reserved page bands")
                require(margin <= span["x"] < span["right"] <= width - margin + .02, "body crosses horizontal margins")
                require("LiberationSans-Bold" not in span["font"] and same_color(span["color"], (0, 0, 0)), "band theme leaked into body")
        ink = [value for value in outlines if value["page"] == index]
        require(len(ink) == 4, "footer must contain PAGE, number, OF and total painted outline groups")
        for value in ink:
            require(same_color(value["color"], GREEN), "footer field style differs from its paragraph")
            require(footer_top <= value["top"] < value["bottom"] <= height - margin, "footer ink leaves its reserved region")
            require(margin <= value["left"] < value["right"] <= width - margin, "footer crosses horizontal margins")
        if caller:
            require(not page.get("/Annots"), "caller fixture gained interactions")
        elif index == 0:
            links = [ref.get_object() for ref in page.get("/Annots", [])]
            require(len(links) == 1 and links[0]["/Subtype"] == "/Link", "contents link missing/repeated")
            require(links[0]["/Dest"][0] == reader.pages[1].indirect_reference, "contents points to incorrect final page")
    require(found_rows == [f"{index:02d}" for index in range(1, expected_rows + 1)], "body source rows lost, duplicated or reordered")
    # PDFium independently confirms normal visible header/body text, without assuming outline search support.
    with pdfium.PdfDocument(path) as document:
        for index in range(count):
            page = document[index]
            text = page.get_textpage()
            try:
                require(text.get_text_range().count(header) == 1, "PDFium header text differs")
            finally:
                text.close()
                page.close()
    return {"pages": count, "bodyRows": len(found_rows), "finalFields": logical, "embeddedFonts": fonts}


class PdfReaderPage:
    """Single-page view for the existing independent ActualText scanner."""
    def __init__(self, page):
        self.pages = [page]


def check_svg(path):
    root = ET.parse(path).getroot()
    pages = root.findall(SVG + "svg")
    require(len(pages) == 3, "static SVG page count differs")
    for index, page in enumerate(pages):
        require(tuple(map(float, page.get("viewBox", "").split())) == (0, 0, 420, 360), "SVG physical page differs")
        labels = [node.get("aria-label", "") for node in page.iter() if node.get("aria-label")]
        require(labels.count("EXPORT4J / XML PAGE REGIONS") == 1, "SVG repeating header differs")
        require(labels.count(f"PAGE {index+1} OF 3") == 1, "SVG final fields differ")
    return {"pages": len(pages)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--consumer-build-directory", type=Path)
    args = parser.parse_args()
    versions = {name: metadata.version(name) for name in ("pypdf", "pypdfium2", "Pillow")}
    require(versions == {"pypdf": "6.10.0", "pypdfium2": "5.13.0", "Pillow": "12.3.0"}, "use pinned verification tools")
    consumer = args.consumer_build_directory or Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip())
    if not consumer.is_absolute():
        consumer = ROOT / consumer
    OUTPUT.mkdir(parents=True, exist_ok=True)
    report, contacts = {"tools": versions}, []
    for name, path, caller in (("example", ROOT / "examples/build/xml-page-regions/report.pdf", False),
                               ("consumer", consumer / "output/consumer-xml-page-regions.pdf", True)):
        result = check(path, caller)
        first, second = render(path, OUTPUT / name)
        require(len(first) == len(second) == result["pages"], "renderer missed pages")
        metrics = [difference(a, b, 2) for a, b in zip(first, second, strict=True)]
        require(all(value["meanAbsoluteChannelError"] < 4 and value["changedPixelFraction"] < .06 for value in metrics), "independent renderers disagree")
        result["rendererDifferences"] = metrics
        report[name] = result
        contacts.extend((name + " " + str(index), image) for index, image in enumerate(first, 1))
    report["svg"] = check_svg(ROOT / "examples/build/xml-page-regions/report.svg")
    contact_sheet(contacts, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"xmlPageRegions": "passed", "pagesIncludingConsumer": len(contacts)}, indent=2))


if __name__ == "__main__":
    main()
