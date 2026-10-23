# SPDX-License-Identifier: Apache-2.0
"""Verify single-pass horizontal spans using final cell borders, glyph advances and dual rendering."""
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

from pypdf import PdfReader
from verify_advanced_pdf import difference, require
from verify_pdf_raster import references
from verify_production import contact_sheet
from verify_semantic_tables import table_borders
from verify_streaming_rich_text import decoration, painted_spans, same_color, strokes
from verify_variable_columns import ink_coverage

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "examples/build/streaming-column-spans"
OUTPUT = ROOT / "build/verification/streaming-column-spans"
ROWS = [["ANCHOR-01 rich", "TAIL-01"], ["COLA-02", "COLB-02", "COLC-02"],
        ["FULL-03 A WIDE MERGED CELL"], ["ANCHOR-04 rich", "TAIL-04"], ["COLA-05", "COLB-05", "COLC-05"]]


def check_geometry(reader, caller=False):
    expected = []
    if caller:
        expected.append((0, 20, 20, 180, 20))
        for row in range(3): expected.extend([(0, 20, 40 + row * 20, 110, 20), (0, 130, 40 + row * 20, 70, 20)])
    else:
        for page, indices in enumerate(([0, 1], [2, 3], [4])):
            expected.extend([(page, 16, 16, 328, 22), (page, 16, 38, 200, 22), (page, 216, 38, 128, 22)])
            for offset, row in enumerate(indices):
                widths = [200, 128] if row % 3 == 0 else [68, 132, 128] if row % 3 == 1 else [328]
                x = 16
                for width in widths:
                    expected.append((page, x, 60 + offset * 22, width, 22)); x += width
    boxes = table_borders(reader)
    require(len(boxes) == len(expected), "merged cells have missing, duplicated or synthetic internal borders")
    for box, values in zip(boxes, expected, strict=True):
        actual = tuple(box[key] for key in ("pageIndex", "x", "y", "width", "height"))
        require(all(abs(a - b) < .003 for a, b in zip(actual, values, strict=True)), "physical merged column or padding geometry differs")
    for span in painted_spans(reader):
        matches = [box for box in boxes if box["pageIndex"] == span["page"]
                   and box["y"] < span["baseline"] < box["y"] + box["height"]
                   and box["x"] < span["x"] < box["x"] + box["width"]]
        require(len(matches) == 1, "painted text has no unique owning cell")
        box = matches[0]; padding = 2 if caller else 3
        require(span["x"] >= box["x"] + padding - .003
                and span["right"] <= box["x"] + box["width"] - padding + .003, "text ignores one-time merged-cell padding")
    return len(boxes)


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    counts = json.loads((SOURCE / "counts.json").read_text())
    expected_points = sum(map(len, ["SPANNING TABLE", "MERGED-HEADER", "STATUS"] + [cell for row in ROWS for cell in row]))
    require(counts == {"pages": 3, "bodyRows": 5, "sourceCells": 14, "sourceCodePoints": expected_points,
                      "outputLines": 20, "readsAtPages": [3, 5, 6], "closes": 1}, "source ownership, anchor counts or repeated-header accounting differs")
    caller = Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip()) / "output/consumer-streaming-spans.pdf"
    tiles, report = [], {}
    for name, path in (("spans", SOURCE / "spans.pdf"), ("caller", caller)):
        reader = PdfReader(path, strict=True); is_caller = name == "caller"
        require(len(reader.pages) == (1 if is_caller else 3), "unexpected streaming page count")
        text = "\n".join(page.extract_text() for page in reader.pages)
        pattern = r"CALL-\d|TAIL-\d" if is_caller else r"(?:ANCHOR|TAIL|COLA|COLB|COLC|FULL)-\d{2}"
        expected = [value for index in range(1, 4) for value in (f"CALL-{index}", f"TAIL-{index}")] if is_caller else [
            re.match(pattern, cell).group() for row in ROWS for cell in row]
        require(re.findall(pattern, text) == expected, "source anchors lost, duplicated or reordered")
        require(all(("CALLER-HEADER" in page.extract_text()) if is_caller else all(
            page.extract_text().count(header) == 1 for header in ("SPANNING TABLE", "MERGED-HEADER", "STATUS")) for page in reader.pages), "header repetition differs")
        borders = check_geometry(reader, is_caller)
        if not is_caller:
            spans = painted_spans(reader); lines = strokes(reader)
            for index in (1, 4):
                run = next(value for value in spans if value["text"] == f"ANCHOR-{index:02d}")
                require(run["font"].endswith("LiberationSans-Bold") and same_color(run["color"], (34/255, 68/255, 136/255)), "merged rich run lost its physical font or color")
                decoration(run, lines, 1)
            for index in range(1, 4): require(ET.parse(SOURCE / f"page-{index}.svg").getroot().tag.endswith("svg"), "invalid streaming SVG page")
        first, second = references(path, OUTPUT / name)
        require(len(first) == len(second) == len(reader.pages), "independent renderer page counts differ")
        comparisons = []
        for index, (a, b) in enumerate(zip(first, second, strict=True)):
            delta = difference(a, b, blur_radius=1); delta["uncoveredInkFractions"] = ink_coverage(a, b)
            require(max(delta["uncoveredInkFractions"]) < .001, "independent renderers disagree on visible strokes")
            comparisons.append(delta); tiles.extend([(f"{name} Poppler {index+1}", a), (f"{name} PDFium {index+1}", b)])
        report[name] = {"pages": len(reader.pages), "cells": borders, "renderDifferences": comparisons}
    contact_sheet(tiles, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print("Streaming horizontal spans: exact coverage, physical columns, source counts, repeated headers, rich styles, caller and dual rendering passed")


if __name__ == "__main__":
    main()
