# SPDX-License-Identifier: Apache-2.0
"""Independent final-PDF style inheritance, source, geometry and all-page rendering checks.

Run after :examples:styleDefaults and verify_local_consumer.ps1 in the pinned project
Python environment. Expected sizes, colors, source numbers and geometry below are
declared contracts, rather than values copied from a generated Java manifest.
No visual baselines are created or updated.
"""
from __future__ import annotations

import argparse
from importlib import metadata
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

from pypdf import PdfReader
from static_forms_geometry import form_marks
from verify_adr_form import render
from verify_advanced_pdf import difference, require
from verify_multi_floats import pdf_geometry, point
from verify_production import actual_text, contact_sheet
from verify_semantic_tables import check_characters, close, table_borders

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "examples/build/style-defaults"
OUTPUT = ROOT / "build/verification/style-defaults"
BLACK, RED, GREEN, BLUE = (0, 0, 0), (.75, .1, .1), (.1, .5, .2), (.1, .2, .75)
STYLE_CONTRACTS = {
    "STYLE-REPORT": (10, BLACK), "DEFAULT-BODY": (10, BLACK),
    "NESTED-BODY": (10, BLACK), "FIXED-BODY": (10, BLACK),
    "LABEL": (10, BLACK), "VALUE": (10, BLACK),
    "COLOR-INHERIT": (10, RED), "PIN12": (12, RED),
    "BLUE-EXPLICIT": (10, BLUE), "LOCAL14": (14, GREEN),
    "GRID-BODY": (10, BLACK), "GRID-CUSTOM": (10, BLACK),
    "GRID9": (9, GREEN), "GRID-CUSTOM9": (9, GREEN),
    "SNAPSHOT11": (11, BLACK), "MERGED": (10, BLACK),
    "STYLE-HEADER": (10, BLACK), "LABEL-ONCE": (10, BLACK),
    "STYLE-TAIL": (10, BLACK),
}


def painted_spans(reader):
    """Use decoded PDF text visitors, final font sizes, fill colors and graphics/text matrices.

    Every generated painted glyph run has its own BT/ET scope. Outlined form lines
    have nonpainting 1 pt anchors and are verified separately using logical/vector data.
    """
    result = []
    for page_index, page in enumerate(reader.pages):
        color, stack = BLACK, []
        show = None
        font_size = 0

        def before(operator, operands, cm, tm):
            nonlocal color, show, font_size
            if operator == b"q":
                stack.append(color)
            elif operator == b"Q":
                color = stack.pop()
            elif operator == b"rg":
                color = tuple(map(float, operands))
            elif operator == b"g":
                color = (float(operands[0]),) * 3
            elif operator == b"Tf":
                font_size = float(operands[1])
            elif operator in (b"Tj", b"TJ"):
                # Text visitors may flush after resetting their current Tm at a later BT.
                # Capture the actual text-show operator's state for final painted geometry.
                show = (tuple(cm), tuple(tm), font_size, color)

        def text(value, _cm, _tm, _font, _font_size):
            if not value.strip() or show is None or show[2] <= 1:
                return
            cm, tm, size, ink = show
            x, y = point(cm, tm[4], tm[5])
            result.append({"page": page_index, "text": value.rstrip("\n"),
                           "fontSize": size, "color": ink,
                           "x": x, "baselineY": float(page.mediabox.height) - y})

        page.extract_text(visitor_operand_before=before, visitor_text=text)
        require(not stack, "painted text color scopes are unbalanced")
    return result


def token_spans(spans, token):
    pattern = re.compile(r"(?<![A-Z0-9-])" + re.escape(token) + r"(?![A-Z0-9-])")
    return [span for span in spans if pattern.search(span["text"])]


def check_styles(spans, contracts, repeated=()):
    checked = 0
    for token, (size, color) in contracts.items():
        found = token_spans(spans, token)
        require(len(found) == (2 if token in repeated else 1), f"painted token missing/repeated: {token}")
        for span in found:
            close(span["fontSize"], size, f"{token} did not use expected explicit/inherited font size")
            require(all(abs(a - b) < .00001 for a, b in zip(span["color"], color, strict=True)),
                    f"{token} did not use expected explicit/inherited color: {span['color']}")
            checked += 1
    return checked


def check_example(path):
    reader = PdfReader(path)
    require(len(reader.pages) == 3, "style report must occupy one summary and two merged-grid pages")
    require(not reader.get_fields() and not any(page.get("/Annots") for page in reader.pages),
            "static style fixture unexpectedly contains interactive data")
    for page in reader.pages:
        close(page.mediabox.width, 400, "style fixture page width changed")
        close(page.mediabox.height, 360, "style fixture page height changed")
    spans = painted_spans(reader)
    checked = check_styles(spans, STYLE_CONTRACTS, ("MERGED", "STYLE-HEADER"))
    text = "\n".join(span["text"] for span in spans)
    summary_order = ["STYLE-REPORT", "DEFAULT-BODY", "NESTED-BODY", "FIXED-BODY", "LABEL", "VALUE",
                     "COLOR-INHERIT", "PIN12", "BLUE-EXPLICIT", "LOCAL14", "GRID-BODY", "GRID-CUSTOM",
                     "GRID9", "GRID-CUSTOM9", "SNAPSHOT11"]
    summary_positions = [spans.index(token_spans(spans, token)[0]) for token in summary_order]
    require(summary_positions == sorted(summary_positions), "summary source components changed order")
    require(re.findall(r"STYLE-LINE-(\d{2})", text) == [f"{n:02}" for n in range(1, 27)],
            "merged source lines lost, repeated or reordered")
    source_lines = []
    for number in range(1, 27):
        found = token_spans(spans, f"STYLE-LINE-{number:02}")
        require(len(found) == 1, "merged source token lost or repeated")
        span = found[0]
        close(span["fontSize"], 10, "merged-cell inherited size differs")
        require(span["color"] == BLACK, "merged-cell inherited color differs")
        require(span["page"] == (1 if number <= 14 else 2), "merged-cell line has wrong physical page")
        close(span["x"], 113, "merged-cell padding/column geometry differs")
        source_lines.append(span)
    for first, last in ((0, 14), (14, 26)):
        lines = source_lines[first:last]
        require(49 <= lines[0]["baselineY"] <= 69, "merged-cell first baseline leaves inherited 20 pt line frame")
        for previous, current in zip(lines, lines[1:]):
            close(current["baselineY"] - previous["baselineY"], 20,
                  "merged-cell inherited fixed baseline step differs")
    expected_borders = [(0, 20, 260, 180, 26), (0, 200, 260, 180, 26),
                        (0, 20, 286, 180, 24), (0, 200, 286, 180, 24)]
    # A non-final merged slice fills the available 320 - 26 pt region, including
    # the unconsumed final-row remainder; the final slice uses 12 * 20 + 6 pt.
    for page, body_height in ((1, 294), (2, 246)):
        expected_borders.extend([(page, 20, 20, 90, 26), (page, 110, 20, 270, 26),
                                 (page, 20, 46, 90, body_height), (page, 110, 46, 270, body_height)])
    borders = table_borders(reader)
    require(len(borders) == len(expected_borders), "grid cell borders lost or repeated")
    for actual, expected in zip(borders, expected_borders, strict=True):
        for key, value in zip(("pageIndex", "x", "y", "width", "height"), expected, strict=True):
            close(actual[key], value, f"inherited grid cell {key} differs")
    logical = actual_text(reader)
    require(logical == ["FORM-BODY ☑ Yes □ No", "☑ CHOICE-YES  □ CHOICE-NO"],
            "static form source/state lost, duplicated or reordered")
    marks = form_marks(reader)
    require(len(marks) == 4 and sum(mark["selected"] for mark in marks) == 2,
            "inherited static control vectors differ from logical source")
    for mark in marks:
        close(mark["size"], 7.2, "static form marks did not inherit the 10 pt font size")
        require(mark["page"] == 0, "static form moved off summary page")
    _, anchors, _ = pdf_geometry(reader, 20)
    require([line["source"] for line in anchors] == logical, "form logical anchor order differs")
    for anchor, y in zip(anchors, (110, 132), strict=True):
        close(anchor["y"], y, "static form did not inherit the 20 pt line frame")
    require(token_spans(spans, "SNAPSHOT11")[0]["page"] == 0,
            "body snapshot changed earlier component geometry or exceeded summary page")
    require(token_spans(spans, "STYLE-TAIL")[0]["page"] == 2, "tail moved off final grid page")
    svg = ET.parse(FIXTURES / "style-defaults.svg").getroot()
    ns = "{http://www.w3.org/2000/svg}"
    require(len(svg.findall(f"{ns}svg")) == 3, "SVG page count differs")
    labels = [g.get("aria-label", "") for g in svg.iter(f"{ns}g")]
    svg_text = "\n".join(labels)
    require(re.findall(r"STYLE-LINE-(\d{2})", svg_text) == [f"{n:02}" for n in range(1, 27)],
            "SVG merged source lines lost or reordered")
    require(all(value in labels for value in logical), "SVG form logical state differs")
    return {"pages": 3, "fontAndColorPresentationsChecked": checked + 26,
            "mergedSourceLines": 26, "bodyLinesPerGridPage": [14, 12],
            "paintedCellBorders": len(borders), "vectorMarks": len(marks),
            "characters": check_characters(path, 20), "staticSvgChecked": True}


def check_consumer(path):
    reader = PdfReader(path)
    require(len(reader.pages) == 1 and not reader.get_fields(), "consumer style fixture must remain one static page")
    spans = painted_spans(reader)
    checked = check_styles(spans, {"CONSUMER-STYLE": (10, BLACK), "CONSUMER-GRID": (10, BLACK),
                                   "CONSUMER-PIN12": (12, BLACK), "CONSUMER-RED": (10, (1, 0, 0))})
    for page in reader.pages:
        close(page.mediabox.width, 320, "consumer style page width differs")
        close(page.mediabox.height, 240, "consumer style page height differs")
    require(actual_text(reader) == ["☑ Checked □ Empty"], "consumer inherited static form logical state differs")
    marks = form_marks(reader)
    require(len(marks) == 2 and sum(mark["selected"] for mark in marks) == 1,
            "consumer static form vectors differ")
    for mark in marks:
        close(mark["size"], 7.2, "consumer form mark did not inherit 10 pt size")
    return {"pages": 1, "fontAndColorPresentationsChecked": checked,
            "characters": check_characters(path, 16)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--consumer-build-directory", type=Path)
    args = parser.parse_args()
    versions = {name: metadata.version(name) for name in ("pypdf", "pypdfium2", "Pillow")}
    require(versions == {"pypdf": "6.10.0", "pypdfium2": "5.13.0", "Pillow": "12.3.0"},
            "use the pinned project Python environment from requirements-verification.txt")
    consumer_directory = args.consumer_build_directory
    if consumer_directory is None:
        pointer = ROOT / "build/verification/consumer-build-directory.txt"
        require(pointer.is_file(), "run verify_local_consumer.ps1 or pass --consumer-build-directory")
        consumer_directory = Path(pointer.read_text(encoding="utf-8").strip())
    if not consumer_directory.is_absolute():
        consumer_directory = ROOT / consumer_directory
    OUTPUT.mkdir(parents=True, exist_ok=True)
    report = {"tools": versions, "consumerBuildDirectory": str(consumer_directory),
              "renderComparison": {"dpi": 192, "blurPixels": 2, "meanLimit": 4, "changedFractionLimit": .06}}
    contacts = []
    for name, path, check in (("styles", FIXTURES / "style-defaults.pdf", check_example),
                              ("consumer", consumer_directory / "output/consumer-style-defaults.pdf", check_consumer)):
        require(path.is_file(), f"expected style fixture does not exist: {name}")
        result = check(path)
        poppler, pdfium = render(path, OUTPUT / name)
        require(len(poppler) == len(pdfium) == result["pages"], "independent style renderer omitted pages")
        comparisons = [difference(a, b, 2) for a, b in zip(poppler, pdfium, strict=True)]
        require(all(item["meanAbsoluteChannelError"] < 4 and item["changedPixelFraction"] < .06
                    for item in comparisons), "independent style PDF renderers disagree")
        result["rendererDifferences"] = comparisons
        report[name] = result
        contacts.extend((f"{name} page {index + 1}", image) for index, image in enumerate(poppler))
    contact_sheet(contacts, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
