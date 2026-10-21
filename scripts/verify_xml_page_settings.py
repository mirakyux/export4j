# SPDX-License-Identifier: Apache-2.0
"""Independently verify XML paper geometry in PDF, SVG and standard OOXML; render every page."""
from __future__ import annotations

import argparse
from importlib import metadata
import json
import math
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from zipfile import ZipFile

import pypdfium2 as pdfium
from pypdf import PdfReader
from verify_adr_form import render
from verify_advanced_pdf import difference, require
from verify_production import contact_sheet
from verify_semantic_tables import table_borders
from verify_streaming_rich_text import painted_spans, physical_fonts, same_color

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "examples/build/xml-page-settings"
OUTPUT = ROOT / "build/verification/xml-page-settings"
W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
SVG = "{http://www.w3.org/2000/svg}"
MM = 72 / 25.4
# All expectations are independent source contracts, not Java geometry manifests.
CASES = {
    "a4": (210 * MM, 297 * MM, (20 * MM, 15 * MM, 18 * MM, 22 * MM), 36, 2),
    "letter-landscape": (792, 612, (18 * MM, 14 * MM, 14 * MM, 20 * MM), 8, 1),
    "custom": (148 * MM, 210 * MM, (12 * MM, 12 * MM, 14 * MM, 12 * MM), 22, 2),
    "consumer": (360, 288, (36, 18, 18, 27), 4, 1),
}


def close(actual, expected, message, tolerance=.003):
    require(abs(actual - expected) <= tolerance, f"{message}: {actual} versus {expected}")


def native_twips(value, points, message):
    require(value is not None and re.fullmatch(r"[0-9]+", value) is not None,
            message + ": native value must use integer twips")
    require(int(value) == math.floor(points * 20 + .5), message + ": rounded native twips differ")


def native_page(path, width, height, margins, rows):
    with ZipFile(path) as archive:
        root = ET.fromstring(archive.read("word/document.xml"))
    sections = list(root.iter(W + "sectPr"))
    require(len(sections) == 1, "XML page DOCX gained extra sections")
    size = sections[0].find(W + "pgSz")
    margin = sections[0].find(W + "pgMar")
    require(size is not None and margin is not None, "native OOXML paper declaration missing")
    for key, expected in (("w", width), ("h", height)):
        native_twips(size.get(W + key), expected, "native paper " + key)
    for key, expected in zip(("top", "right", "bottom", "left"), margins, strict=True):
        native_twips(margin.get(W + key), expected, "native margin " + key)
    text = " ".join(node.text or "" for node in root.iter(W + "t"))
    require(re.findall(r"ROW-\d{3}", text) == [f"ROW-{index:03d}" for index in range(1, rows + 1)],
            "native DOCX paper report rows lost/reordered")
    require(text.count("PAGE-SETTINGS-END") == 1, "native final paragraph lost")
    return {"paperPoints": [width, height], "marginsTopRightBottomLeft": list(margins), "rows": rows}


def svg_page(path, width, height, pages, rows):
    root = ET.parse(path).getroot()
    require(root.tag == SVG + "svg", "SVG root namespace/tag differs")
    nested = root.findall(SVG + "svg")
    require(len(nested) == pages, "SVG paper page count differs")
    close(float(root.get("width").removesuffix("pt")), width, "SVG outer width differs", .00001)
    stacked_height = height * pages + 20 * (pages - 1)
    close(float(root.get("height").removesuffix("pt")), stacked_height, "SVG stack height differs", .00001)
    svg_view_box(root, width, stacked_height)
    for index, page in enumerate(nested):
        require(page.get("data-page") == str(index + 1), "SVG physical page number differs")
        close(float(page.get("x")), 0, "SVG page horizontal origin differs", .00001)
        close(float(page.get("width")), width, "SVG page width differs", .00001)
        close(float(page.get("height")), height, "SVG page height differs", .00001)
        close(float(page.get("y")), index * (height + 20), "SVG page offset differs", .00001)
        svg_view_box(page, width, height)
    text = " ".join(value.get("aria-label", "") for value in root.iter())
    require(re.findall(r"ROW-\d{3}", text) == [f"ROW-{index:03d}" for index in range(1, rows + 1)],
            "SVG paper report rows lost/reordered")
    require(text.count("PAGE-SETTINGS-END") == 1, "SVG final paragraph lost")


def svg_view_box(element, width, height):
    value = element.get("viewBox", "").split()
    require(len(value) == 4, "SVG paper viewBox is missing or malformed")
    for actual, expected in zip(map(float, value), (0, 0, width, height), strict=True):
        close(actual, expected, "SVG paper viewBox differs", .00001)


def page_row_counts(height, margins, rows, pages):
    """Allocate this fixture's complete 28 pt rows from independently declared page/body geometry."""
    top, _right, bottom, _left = margins
    remaining, counts = rows, []
    for index in range(pages):
        table_top = top + (56 if index == 0 else 0)
        capacity = math.floor((height - bottom - table_top) / 28) - 1
        require(capacity > 0, "fixture paper cannot hold a complete header and body row")
        count = min(remaining, capacity)
        require(count > 0, "fixture unexpectedly allocates a page without body rows")
        counts.append(count)
        remaining -= count
    require(remaining == 0, "fixture paper does not fit the declared source rows/pages")
    return counts


def character_bounds(path, width, height, margins):
    top_margin, right_margin, bottom_margin, left_margin = margins
    count = 0
    with pdfium.PdfDocument(path) as document:
        for index in range(len(document)):
            page = document[index]
            text = page.get_textpage()
            try:
                for offset in range(text.count_chars()):
                    if not text.get_text_range(offset, 1, errors="ignore").strip():
                        continue
                    left, bottom, right, top = text.get_charbox(offset)
                    require(left_margin - .6 <= left <= right <= width - right_margin + .6
                            and bottom_margin - .6 <= bottom <= top <= height - top_margin + .6,
                            "XML paper character leaves its asymmetric body rectangle")
                    count += 1
            finally:
                text.close()
                page.close()
    return count


def check_pdf(path, config, caller=False):
    width, height, margins, rows, pages = config
    top, right, bottom, left = margins
    reader = PdfReader(path)
    require(len(reader.pages) == pages, "XML paper pagination differs")
    texts = [page.extract_text() or "" for page in reader.pages]
    row_counts = page_row_counts(height, margins, rows, pages)
    all_text = " ".join(texts)
    require(re.findall(r"ROW-\d{3}", all_text) == [f"ROW-{index:03d}" for index in range(1, rows + 1)],
            "PDF paper report rows lost/reordered/repeated")
    require(all_text.count("PAGE-SETTINGS-END") == 1 and "PAGE-SETTINGS-END" in texts[-1], "PDF final paragraph misplaced")
    next_row = 1
    for page, text, count in zip(reader.pages, texts, row_counts, strict=True):
        close(float(page.mediabox.left), 0, "PDF MediaBox horizontal origin differs", .0001)
        close(float(page.mediabox.bottom), 0, "PDF MediaBox vertical origin differs", .0001)
        close(float(page.mediabox.width), width, "PDF MediaBox width differs", .0001)
        close(float(page.mediabox.height), height, "PDF MediaBox height differs", .0001)
        require(page.get("/Rotate", 0) == 0, "XML orientation must change physical dimensions, not PDF rotation")
        require(text.count("CODE") == 1 and text.count("DESCRIPTION") == 1, "repeated table header missing/duplicated")
        wanted = [f"ROW-{row:03d}" for row in range(next_row, next_row + count)]
        require(re.findall(r"ROW-\d{3}", text) == wanted, "PDF rows occupy the wrong physical page")
        require(text.index("CODE") < text.index(wanted[0]) and text.index("DESCRIPTION") < text.index(wanted[0]),
                "repeated header follows the page's first body row")
        next_row += count
        require(not page.get("/Annots"), "static paper fixture gained interactivity")
    spans = painted_spans(reader)
    heading = [value for value in spans if value["text"].startswith("XML-PAGE-")]
    require(len(heading) == 1 and heading[0]["page"] == 0, "paper heading source differs")
    close(heading[0]["x"], left, "paper left margin did not reach actual text", .003)
    close(heading[0]["size"], 16, "paper heading font size differs")
    for value in spans:
        require(left - .003 <= value["x"] <= value["right"] <= width - right + .003,
                "PDF text advance leaves paper body width")
    fonts = physical_fonts(reader, {"LiberationSans"} if caller else {"LiberationSans", "LiberationSans-Bold"})
    if not caller:
        accent = [value for value in spans if value["text"] == "REGISTERED-BOLD"]
        require(len(accent) == 1 and accent[0]["font"].endswith("LiberationSans-Bold")
                and same_color(accent[0]["color"], (34 / 255, 68 / 255, 136 / 255)), "explicit physical font role lost")
    borders = table_borders(reader)
    require(len(borders) == (rows + pages) * 2, "table row borders differ from repeated headers and source rows")
    for value in borders:
        require(left - .003 <= value["x"] and value["x"] + value["width"] <= width - right + .003
                and top - .003 <= value["y"] and value["y"] + value["height"] <= height - bottom + .003,
                "table border leaves XML paper body rectangle")
    for index, count in enumerate(row_counts):
        positions = [value for value in borders if value["pageIndex"] == index]
        require(len(positions) == (count + 1) * 2, "table header/body border count differs on physical page")
        expected = []
        table_top = top + (56 if index == 0 else 0)
        for row in range(count + 1):
            y = table_top + row * 28
            expected.extend(((left, y, 72, 28), (left + 72, y, width - right - left - 72, 28)))
        for actual, geometry in zip(positions, expected, strict=True):
            for key, wanted in zip(("x", "y", "width", "height"), geometry, strict=True):
                close(actual[key], wanted, "table cell paper geometry differs: " + key, .003)
    return {"pages": pages, "rows": rows, "charactersWithinBody": character_bounds(path, width, height, margins),
            "rowsPerPage": row_counts, "physicalFonts": fonts, "borders": len(borders)}


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
    for name, config in CASES.items():
        path = directory / "output/consumer-xml-page-settings.pdf" if name == "consumer" else FIXTURES / (name + ".pdf")
        width, height, margins, rows, pages = config
        checked = check_pdf(path, config, name == "consumer")
        checked["nativeDocx"] = native_page(path.with_suffix(".docx"), width, height, margins, rows)
        svg_page(path.with_suffix(".svg"), width, height, pages, rows)
        first, second = render(path, OUTPUT / name)
        require(len(first) == len(second) == pages, "independent renderer missed a complete paper page")
        values = [difference(a, b, 2) for a, b in zip(first, second, strict=True)]
        require(all(value["meanAbsoluteChannelError"] < 4 and value["changedPixelFraction"] < .06 for value in values),
                "independent paper renderers disagree")
        checked["rendererDifferences"] = values
        report[name] = checked
        contacts.extend((name + " " + str(index), page) for index, page in enumerate(first, 1))
    require(len(contacts) == 6, "complete XML paper fixture/caller page count differs")
    contact_sheet(contacts, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"xmlPageSettings": "passed", "pagesIncludingConsumer": len(contacts)}, indent=2))


if __name__ == "__main__":
    main()
