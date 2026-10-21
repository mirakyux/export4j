# SPDX-License-Identifier: Apache-2.0
"""Check actual fixed-region fonts, positions and final numbering through two PDF engines."""
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
from verify_continuous_columns import font_ascent
from verify_multi_floats import compose, point
from verify_production import contact_sheet
from verify_streaming_rich_text import painted_spans, physical_fonts, same_color

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build/verification/page-region-fonts"
SVG = "{http://www.w3.org/2000/svg}"
REGIONS = ("HEADER", "FOOTER", "BACKGROUND", "FOREGROUND")
COLORS = ((.12, .24, .48), (.16, .40, .24), (.56, .32, .12), (.48, .16, .40))


def close(actual, expected, message, tolerance=.003):
    require(abs(actual - expected) <= tolerance, f"{message}: {actual} versus {expected}")


def expectations(caller):
    if caller:
        return 320, 260, 18, 284, (18, 202, 120, 166), 3, 222, 50, 196, "CALLER-"
    return 420, 520, 28, 364, (28, 452, 220, 392), 7, 472, 72, 440, "REGION-"


def check_pdf(path, caller):
    width, height, left, span_width, tops, first, number_top, body_top, body_bottom, prefix = expectations(caller)
    reader = PdfReader(path)
    require(len(reader.pages) == 2, "fixed-region page count differs")
    require(not reader.trailer["/Root"].get("/AcroForm"), "static fixture gained editable form")
    fonts = physical_fonts(reader, {"LiberationSans", "LiberationSans-Bold"})
    regular_ascent = font_ascent(ROOT / "assets/fonts/LiberationSans-Regular.ttf")
    bold_ascent = font_ascent(ROOT / "assets/fonts/LiberationSans-Bold.ttf")
    spans = painted_spans(reader)
    expected = []
    for index, page in enumerate(reader.pages):
        close(float(page.mediabox.left), 0, "page x origin")
        close(float(page.mediabox.bottom), 0, "page y origin")
        close(float(page.mediabox.width), width, "page width")
        close(float(page.mediabox.height), height, "page height")
        require(page.get("/Rotate", 0) == 0 and not page.get("/Annots"), "static fixture page changed")
        rendering_mode, stack = 0, []
        for operands, operator in page.get_contents().operations:
            if operator == b"q": stack.append(rendering_mode)
            elif operator == b"Q": rendering_mode = stack.pop()
            elif operator == b"Tr": rendering_mode = int(operands[0])
            elif operator in (b"Tj", b"TJ"):
                require(rendering_mode == 0, "fixture text is not visibly filled")
            elif operator == b"gs":
                raise AssertionError("fixture gained an unexpected opacity/graphics state")
        require(not stack, "fixture rendering modes are unbalanced")
        text = page.extract_text()
        selected = [value for value in spans if value["page"] == index]
        for region, top, color in zip(REGIONS, tops, COLORS, strict=True):
            marker = f"{prefix}{region} I{index} N{first + index} TOTAL2"
            require(text.count(marker) == 1, "fixed-region final marker missing or duplicated: " + marker)
            hits = [value for value in selected if value["text"] == marker]
            require(len(hits) == 1, "region marker not one complete painted line")
            value = hits[0]
            require(value["font"].endswith("+LiberationSans-Bold"), "region did not use registered physical bold")
            close(value["size"], 10, "region inherited font size")
            require(same_color(value["color"], COLORS[0] if caller else color), "region inherited color differs")
            close(value["x"], left, "region left edge")
            close(value["baseline"], top + 10 * bold_ascent, "region exact baseline")
            require(top <= value["baseline"] <= top + 16 and value["right"] <= left + span_width + .003,
                    "fixed-region text leaves its declared frame")
            expected.append((index, marker, (left, top, left + span_width, top + 16)))
        number = f"PAGE {first + index} OF 2"
        hit = [value for value in selected if value["text"] == number]
        require(len(hit) == 1 and hit[0]["font"].endswith("+LiberationSans"), "default page-number font changed")
        close(hit[0]["x"], left, "page-number left edge")
        close(hit[0]["size"], 10, "page-number font size")
        close(hit[0]["baseline"], number_top + 10 * regular_ascent, "page-number exact baseline")
        require(same_color(hit[0]["color"], (0, 0, 0)), "page-number default color changed")
        expected.append((index, number, (left, number_top, left + span_width, number_top + 16)))
        body_marker = ("BODY-CALLER-" if caller else "BODY-") + ("FIRST" if index == 0 else "SECOND")
        body = [value for value in selected if value["text"].startswith(body_marker)]
        require(len(body) == 1, "body source marker missing or duplicated")
        close(body[0]["baseline"], body_top + 11 * regular_ascent, "reserved body first baseline")
        for value in selected:
            if value["text"].startswith(prefix) or value["text"].startswith("PAGE "):
                continue
            require(value["font"].endswith("+LiberationSans") and value["size"] == 11,
                    "registered page role leaked into body defaults")
            require(body_top <= value["baseline"] <= body_bottom and left - .003 <= value["x"]
                    and value["right"] <= left + span_width + .003, "body leaves reserved page bands")
    # Independently locate every complete marker and verify actual visible glyph boxes.
    with pdfium.PdfDocument(path) as document:
        for index, marker, bounds in expected:
            page = document[index]
            text = page.get_textpage()
            try:
                search = text.search(marker)
                try:
                    match = search.get_next()
                    require(match is not None and search.get_next() is None, "PDFium marker search differs")
                    start, count = match
                finally:
                    search.close()
                for offset in range(start, start + count):
                    if not text.get_text_range(offset, 1, errors="ignore").strip():
                        continue
                    x0, y0, x1, y1 = text.get_charbox(offset)
                    require(bounds[0] - .6 <= x0 <= x1 <= bounds[2] + .6
                            and bounds[1] - .6 <= height - y1 <= height - y0 <= bounds[3] + .6,
                            "visible page-region glyph leaves declared frame")
            finally:
                text.close()
                page.close()
    return {"pages": 2, "fixedRoleMarkers": 8, "defaultPageNumbers": 2, "physicalFonts": fonts}


def check_svg(path, caller):
    width, height, left, span_width, tops, first, number_top, _body_top, _body_bottom, prefix = expectations(caller)
    root = ET.parse(path).getroot()
    require(root.tag == SVG + "svg", "SVG root namespace differs")
    require(all(root.get(key) is None for key in ("transform", "opacity", "fill-opacity", "display", "visibility", "style")),
            "SVG root gained hidden or indirect paint state")
    close(float(root.get("width").removesuffix("pt")), width, "SVG root width")
    close(float(root.get("height").removesuffix("pt")), height * 2 + 20, "SVG root height")
    require(list(map(float, root.get("viewBox", "").split())) == [0, 0, width, height * 2 + 20], "SVG root viewBox differs")
    pages = root.findall(SVG + "svg")
    require(len(pages) == 2, "SVG fixed-region page count differs")
    for index, page in enumerate(pages):
        require(page.get("data-page") == str(index + 1), "SVG physical page index differs")
        close(float(page.get("width")), width, "SVG page width")
        close(float(page.get("height")), height, "SVG page height")
        close(float(page.get("x")), 0, "SVG page x")
        close(float(page.get("y")), index * (height + 20), "SVG page stack y")
        require(list(map(float, page.get("viewBox", "").split())) == [0, 0, width, height], "SVG page viewBox differs")
        labels = [node.get("aria-label", "") for node in page.iter()]
        wanted = {}
        for region in REGIONS:
            marker = f"{prefix}{region} I{index} N{first + index} TOTAL2"
            require(labels.count(marker) == 1, "SVG fixed-region final marker lost")
            position = REGIONS.index(region)
            wanted[marker] = (tops[position], COLORS[0] if caller else COLORS[position])
        number = f"PAGE {first + index} OF 2"
        require(labels.count(number) == 1, "SVG default page number lost")
        wanted[number] = (number_top, (0, 0, 0))

        def walk(node, matrix):
            require(all(node.get(key) is None for key in ("opacity", "fill-opacity", "display", "visibility", "style")),
                    "SVG fixture gained hidden or indirect paint state")
            transform = node.get("transform")
            if transform is not None:
                match = re.fullmatch(r"matrix\(([^)]+)\)", transform)
                require(match is not None, "unexpected SVG transform syntax")
                values = tuple(map(float, match[1].split()))
                require(len(values) == 6, "SVG transform lacks six coordinates")
                matrix = compose(matrix, values)
            marker = node.get("aria-label")
            if marker in wanted:
                top, color = wanted[marker]
                for actual, expected in zip(matrix, (1, 0, 0, 1, left, top), strict=True):
                    close(actual, expected, "SVG marker placement matrix")
                paths = node.findall(SVG + "path")
                require(paths, "SVG logical marker has no painted glyph path")
                for shape in paths:
                    require(shape.get("transform") is None, "SVG marker glyph gained a transform")
                    match = re.fullmatch(r"rgb\(([^)]+)\)", shape.get("fill", ""))
                    require(match is not None, "SVG marker glyph lacks RGB fill")
                    require(same_color(tuple(float(value.removesuffix("%")) / 100 for value in match[1].split()), color),
                            "SVG marker glyph color differs")
                    coordinates = list(map(float, re.findall(r"[-+]?(?:\d*\.\d+|\d+\.?\d*)(?:[eE][-+]?\d+)?", shape.get("d", ""))))
                    require(len(coordinates) >= 6 and len(coordinates) % 2 == 0, "SVG marker glyph path empty/malformed")
                    for offset in range(0, len(coordinates), 2):
                        x, y = point(matrix, coordinates[offset], coordinates[offset + 1])
                        require(left - .6 <= x <= left + span_width + .6 and top - .6 <= y <= top + 16 + .6,
                                "SVG marker glyph leaves declared frame")
            for child in node: walk(child, matrix)
        walk(page, (1, 0, 0, 1, 0, 0))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--consumer-build-directory", type=Path)
    args = parser.parse_args()
    versions = {name: metadata.version(name) for name in ("pypdf", "pypdfium2", "Pillow")}
    require(versions == {"pypdf": "6.10.0", "pypdfium2": "5.13.0", "Pillow": "12.3.0"}, "use pinned verification tools")
    directory = args.consumer_build_directory
    if directory is None:
        directory = Path((ROOT / "build/verification/consumer-build-directory.txt").read_text(encoding="utf-8").strip())
    if not directory.is_absolute(): directory = ROOT / directory
    OUTPUT.mkdir(parents=True, exist_ok=True)
    report, contacts = {"tools": versions, "consumerBuildDirectory": str(directory)}, []
    for name, path, caller in (("example", ROOT / "examples/build/page-region-fonts/report.pdf", False),
                               ("consumer", directory / "output/consumer-page-region-fonts.pdf", True)):
        checked = check_pdf(path, caller)
        check_svg(path.with_suffix(".svg"), caller)
        first, second = render(path, OUTPUT / name)
        require(len(first) == len(second) == 2, "renderer missed complete fixed-region pages")
        differences = [difference(a, b, 2) for a, b in zip(first, second, strict=True)]
        require(all(value["meanAbsoluteChannelError"] < 4 and value["changedPixelFraction"] < .06 for value in differences),
                "independent fixed-region renderers disagree")
        checked["rendererDifferences"] = differences
        report[name] = checked
        contacts.extend((name + " " + str(index), page) for index, page in enumerate(first, 1))
    contact_sheet(contacts, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"pageRegionFonts": "passed", "pagesIncludingConsumer": len(contacts)}, indent=2))


if __name__ == "__main__":
    main()
