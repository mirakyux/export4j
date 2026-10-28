# SPDX-License-Identifier: Apache-2.0
"""Independently verify vertical source order, em-cell geometry and PDF/SVG/PNG ink."""
import argparse
import csv
import json
from pathlib import Path
import xml.etree.ElementTree as ET

from PIL import Image, ImageChops
from pypdf import PdfReader

from verify_advanced_pdf import difference, require
from verify_pdf_raster import references
from verify_production import contact_sheet
from verify_semantic_tables import page_logical
from verify_text_corpus import compact, reader_evidence

ROOT = Path(__file__).resolve().parents[1]
EXPECTED = {
    "upright": "天地玄黃宇宙洪荒",
    "mixed": "一I字B竪1排2",
    "punctuation": "「天地」，玄黃。",
    "left-to-right": "天地\r\n玄黃",
}


def inspect(source, target):
    target.mkdir(parents=True, exist_ok=True)
    report = {}
    images = []
    for name, text in EXPECTED.items():
        reader = PdfReader(source / f"{name}.pdf", strict=True)
        require(len(reader.pages) == 1, "vertical atomic component page count")
        page = reader.pages[0]
        require(float(page.mediabox.width) == 240 and float(page.mediabox.height) == 180,
                "vertical output page geometry")
        require("".join(page_logical(page)) == text, "PDF logical text was changed or reordered")
        evidence = reader_evidence(source / f"{name}.pdf", compact(text))
        require(compact(evidence[0]["text"]) == compact(text), "PDFium extracted vertical source is missing or reordered")
        matches = evidence[0]["search"]
        require(len(matches) == 1 and compact(matches[0]["selectedText"]) == compact(text),
                "PDFium vertical search/selected text differs from the original source")
        svg = ET.parse(source / f"{name}.svg").getroot()
        # XML normalizes attribute line endings; title text preserves the intended line break.
        labels = [next(iter(item)).text or "" for item in svg.iter() if "aria-label" in item.attrib]
        require("".join(labels).replace("\r\n", "\n") == text.replace("\r\n", "\n"),
                "SVG logical text was changed or reordered")
        with (source / f"{name}.tsv").open(encoding="utf-8", newline="") as stream:
            cells = list(csv.DictReader(stream, delimiter="\t"))
        ranges = [(int(cell["start"]), int(cell["end"])) for cell in cells]
        require(ranges[0][0] == 0 and ranges[-1][1] == len(text.encode("utf-16-le")) // 2,
                "vertical original UTF-16 endpoints")
        require(all(left[1] == right[0] for left, right in zip(ranges, ranges[1:])),
                "vertical source ranges are missing, duplicated or reordered")
        columns = [int(cell["column"]) for cell in cells]
        if name != "left-to-right":
            require(columns == [0] * 4 + [1] * 4, "vertical top-to-bottom capacity and column wrap")
        else:
            require(columns == [0, 0, 0, 1, 1], "explicit CRLF column transition")
        for cell in cells:
            column = int(cell["column"])
            expected_x = 20 + (column if name == "left-to-right" else 1 - column) * 36
            require(float(cell["x"]) == expected_x and float(cell["width"]) == 36
                    and float(cell["height"]) == 32, "vertical column direction or em-cell size")
        if name == "mixed":
            require([cell["sideways"] == "true" for cell in cells] == [False, True] * 4,
                    "ASCII was not individually turned while CJK stayed upright")
        if name == "punctuation":
            require([int(cell["rendered"]) for cell in cells] ==
                    [0xfe41, ord("天"), ord("地"), 0xfe42, 0xfe10, ord("玄"), ord("黃"), 0xfe12],
                    "explicit vertical presentation forms")
        first, second = references(source / f"{name}.pdf", target / name)
        png = source / f"{name}.png"
        differences = [difference(png, other, 1) for other in (first[0], second[0])]
        require(all(delta["meanAbsoluteChannelError"] < 4 and delta["changedPixelFraction"] < .06
                    for delta in differences), "vertical backend ink differs from independent PDF renderers")
        with Image.open(png) as image:
            image = image.convert("RGB")
            require(image.size == (480, 360), "vertical PNG physical pixel geometry")
            for cell_index, cell in enumerate(cells):
                if int(cell["rendered"]) == -1:
                    continue
                x = round(float(cell["x"]) * 2)
                y = round(float(cell["y"]) * 2)
                tile = image.crop((x, y, x + 72, y + 64))
                ink = ImageChops.difference(tile, Image.new("RGB", tile.size, "white"))
                require(ink.getbbox() is not None, "vertical cell is blank or was drawn outside its original cell")
                if name == "mixed" and cell_index < 2:
                    left, top, right, bottom = ink.getbbox()
                    require(right - left > (bottom - top) * 2,
                            "CJK horizontal stroke was turned, or ASCII vertical stem was not turned")
        images.extend([(f"{name} PNG", png), (f"{name} Poppler", first[0]), (f"{name} PDFium", second[0])])
        report[name] = {"cells": len(cells), "columns": max(columns) + 1, "rendering": differences,
                        "pdfium": evidence,
                        "selectionScope": "logical anchor rectangles, not complete outline coverage"}
    contact_sheet(images, target)
    summary = {"fixtures": len(EXPECTED), "checks": "passed", "cases": report}
    (target / "report.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, indent=2))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--consumer-build-directory", type=Path)
    args = parser.parse_args()
    build = args.consumer_build_directory or Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip())
    inspect(build / "output/vertical-text", ROOT / "build/verification/gap08-vertical-text")


if __name__ == "__main__":
    main()
