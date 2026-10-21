# SPDX-License-Identifier: Apache-2.0
"""Verify final PDF property inheritance, continuation, short regions and public-coordinate use.

Expected sizes, positions, line spacing and page allocation are fixed contracts, not a Java
layout manifest. All pages are rendered with two independent engines; no baseline is refreshed.
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
from verify_production import actual_text, contact_sheet
from verify_semantic_tables import check_characters, close, table_borders
from verify_style_defaults import BLACK, BLUE, check_styles, painted_spans, token_spans
from verify_text_corpus import reader_evidence

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "examples/build/property-styles"
OUTPUT = ROOT / "build/verification/property-styles"


def span(spans, token):
    matches = token_spans(spans, token)
    require(len(matches) == 1, f"missing/repeated painted token: {token}")
    return matches[0]


def pair(spans, prefix, size, step, first_x, next_x):
    first, second = span(spans, prefix + "-A"), span(spans, prefix + "-B")
    require(first["page"] == second["page"], f"unexpected split in {prefix}")
    for item, x in ((first, first_x), (second, next_x)):
        close(item["fontSize"], size, f"{prefix} font size differs")
        close(item["x"], x, f"{prefix} explicit/inherited indent differs")
    close(second["baselineY"] - first["baselineY"], step, f"{prefix} inherited line step differs")


def geometry(reader, pages, width, height):
    require(len(reader.pages) == pages, "property fixture page count differs")
    require(not reader.get_fields() and not any(page.get("/Annots") for page in reader.pages),
            "static property fixture contains interactive data")
    for page in reader.pages:
        close(page.mediabox.width, width, "page width differs")
        close(page.mediabox.height, height, "page height differs")


def check_summary(path):
    reader = PdfReader(path)
    geometry(reader, 1, 480, 600)
    spans = painted_spans(reader)
    contracts = {token: (size, BLACK) for token, size in (
        ("SIZE14-A", 14), ("SIZE14-B", 14), ("ZERO-A", 10), ("ZERO-B", 10),
        ("RIGHT-A", 10), ("RIGHT-B", 10), ("RESET-A", 10), ("RESET-B", 10),
        ("SIBLING10", 10), ("FULL12", 12), ("GRID9", 9), ("FACTORY11", 11),
        ("LOCAL9", 9), ("CHILD12", 12), ("GRID13", 13), ("FACTORY-SNAPSHOT", 11), ("FIXED11", 11))}
    contracts.update({"NEST-A": (14, BLUE), "NEST-B": (14, BLUE)})
    checked = check_styles(spans, contracts)
    pair(spans, "SIZE14", 14, 24, 38, 26)
    pair(spans, "ZERO", 10, 24, 20, 20)
    pair(spans, "RESET", 10, 28, 38, 26)
    pair(spans, "NEST", 14, 30, 38, 26)
    close(span(spans, "SIBLING10")["x"], 38, "nested scope leaked into sibling")
    close(span(spans, "FIXED11")["x"], 38, "fixed text lost inherited first indent")
    for token in ("RIGHT-A", "RIGHT-B", "FULL12"):
        require(span(spans, token)["x"] > 390, "right alignment did not resolve")
        selections = [match for page in reader_evidence(path, token) for match in page["search"]]
        require(len(selections) == 1 and selections[0]["selectedText"] == token, "aligned token selection differs")
        right = max(rect[2] for rect in selections[0]["rectangles"])
        require(458 <= right <= 461, "right aligned painted glyphs leave the expected 460 pt edge")
    close(span(spans, "RIGHT-B")["baselineY"] - span(spans, "RIGHT-A")["baselineY"], 24,
          "alignment override reset inherited line height")
    borders = table_borders(reader)
    require(len(borders) == 6, "grid snapshot cell borders differ")
    for actual, expected in zip(borders, ((20, 322, 220, 30), (240, 322, 220, 30),
                                        (20, 352, 220, 34), (240, 352, 220, 34),
                                        (20, 386, 220, 30), (240, 386, 220, 30)), strict=True):
        for key, value in zip(("x", "y", "width", "height"), expected, strict=True):
            close(actual[key], value, f"partial-style grid {key} differs")
    require(actual_text(reader) == ["FORM11 ☑ Yes □ No"], "partial form source differs")
    marks = form_marks(reader)
    require(len(marks) == 2 and sum(mark["selected"] for mark in marks) == 1, "form vectors differ")
    for mark in marks:
        close(mark["size"], 7.92, "form did not use local 11 pt size")
    return {"pages": 1, "fontAndColorPresentations": checked, "gridBorders": 6,
            "characters": check_characters(path, 20), "vectorMarks": 2}


def check_continuation(path):
    reader = PdfReader(path)
    geometry(reader, 2, 400, 320)
    spans = painted_spans(reader)
    check_styles(spans, {"MERGED": (11, BLACK), "PROPERTY-HEAD": (9, BLACK),
                         "LABEL-ONCE": (9, BLACK), "PROPERTY-TAIL": (10, BLACK)},
                 ("MERGED", "PROPERTY-HEAD"))
    text = "\n".join(item["text"] for item in spans)
    require(re.findall(r"PROP-LINE-(\d{2})", text) == [f"{i:02}" for i in range(1, 23)],
            "merged property source lost/repeated/reordered")
    lines = [span(spans, f"PROP-LINE-{i:02}") for i in range(1, 23)]
    for i, item in enumerate(lines):
        require(item["page"] == (0 if i < 12 else 1), "merged source page differs")
        close(item["fontSize"], 11, "merged child property override differs")
        close(item["x"], 117 if i == 0 else 115, "merged source indent reset across continuation")
    for first, end in ((0, 12), (12, 22)):
        for previous, current in zip(lines[first:end], lines[first + 1:end]):
            close(current["baselineY"] - previous["baselineY"], 20, "merged inherited line step differs")
    require(span(spans, "PROPERTY-TAIL")["page"] == 1, "tail page differs")
    borders = table_borders(reader)
    require(len(borders) == 8, "continuation borders missing/repeated")
    return {"pages": 2, "sourceLines": 22, "bodyLinesPerPage": [12, 10],
            "characters": check_characters(path, 20), "gridBorders": len(borders)}


def check_regions(path):
    reader = PdfReader(path)
    geometry(reader, 2, 400, 360)
    spans = painted_spans(reader)
    lines = [span(spans, f"REGION-{i:02}") for i in range(1, 13)]
    require(re.findall(r"REGION-(\d{2})", "\n".join(s["text"] for s in spans)) ==
            [f"{i:02}" for i in range(1, 13)], "short region source differs")
    for i, item in enumerate(lines):
        require(item["page"] == (0 if i < 10 else 1), "short region page allocation differs")
        close(item["fontSize"], 11, "short region font size differs")
        close(item["x"], 24 if i == 0 else 208 if 5 <= i < 10 else 22,
              "short region indent or column offset differs")
    for first, end in ((0, 5), (5, 10), (10, 12)):
        for previous, current in zip(lines[first:end], lines[first + 1:end]):
            close(current["baselineY"] - previous["baselineY"], 24, "scoped short region line step differs")
    close(lines[0]["baselineY"], lines[5]["baselineY"], "first short columns did not share top edge")
    require(220 < lines[0]["baselineY"] < 240 and 20 < lines[10]["baselineY"] < 40,
            "short/full continuation top edge differs")
    require(span(spans, "REGION-TAIL")["page"] == 1, "short region tail moved off final page")
    return {"pages": 2, "sourceLines": 12, "firstShortColumns": [5, 5],
            "characters": check_characters(path, 20)}


def check_consumer(path):
    reader = PdfReader(path)
    geometry(reader, 1, 360, 280)
    spans = painted_spans(reader)
    checked = check_styles(spans, {token: (size, BLACK) for token, size in (
        ("CP14-A", 14), ("CP14-B", 14), ("CP11", 11), ("CP13-A", 13), ("CP13-B", 13),
        ("CP-GRID9", 9), ("CP-FACTORY11", 11), ("CP-GRID12", 12), ("CP-SNAPSHOT12", 12))})
    checked += check_styles(spans, {"CP-SCOPE14": (14, (1, 0, 0))})
    pair(spans, "CP14", 14, 24, 28, 20)
    pair(spans, "CP13", 13, 24, 16, 16)
    close(span(spans, "CP11")["x"], 28, "consumer fixed text indent differs")
    return {"pages": 1, "fontAndColorPresentations": checked, "characters": check_characters(path, 16)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--consumer-build-directory", type=Path)
    args = parser.parse_args()
    versions = {name: metadata.version(name) for name in ("pypdf", "pypdfium2", "Pillow")}
    require(versions == {"pypdf": "6.10.0", "pypdfium2": "5.13.0", "Pillow": "12.3.0"},
            "use the pinned project verification environment")
    consumer = args.consumer_build_directory
    if consumer is None:
        consumer = Path((ROOT / "build/verification/consumer-build-directory.txt").read_text(encoding="utf-8").strip())
    if not consumer.is_absolute():
        consumer = ROOT / consumer
    OUTPUT.mkdir(parents=True, exist_ok=True)
    report = {"tools": versions, "consumerBuildDirectory": str(consumer), "fixtures": {}}
    contacts = []
    for name, path, check in (("properties", FIXTURES / "properties.pdf", check_summary),
                              ("continuation", FIXTURES / "continuation.pdf", check_continuation),
                              ("regions", FIXTURES / "regions.pdf", check_regions),
                              ("consumer", consumer / "output/consumer-property-styles.pdf", check_consumer)):
        result = check(path)
        if name != "consumer":
            svg = ET.parse(FIXTURES / (name + ".svg")).getroot()
            require(len(svg.findall("{http://www.w3.org/2000/svg}svg")) == result["pages"], "SVG page count differs")
            labels = "\n".join(node.get("aria-label", "") for node in svg.iter())
            prefix, count = ("PROP-LINE", 22) if name == "continuation" else ("REGION", 12) if name == "regions" else ("", 0)
            if prefix:
                require(re.findall(prefix + r"-(\d{2})", labels) == [f"{i:02}" for i in range(1, count + 1)],
                        "SVG continuation source order differs")
        poppler, pdfium = render(path, OUTPUT / name)
        require(len(poppler) == len(pdfium) == result["pages"], "renderer page count differs")
        differences = [difference(a, b, 2) for a, b in zip(poppler, pdfium, strict=True)]
        require(all(d["meanAbsoluteChannelError"] < 4 and d["changedPixelFraction"] < .06 for d in differences),
                "property style renderers disagree")
        result["rendererDifferences"] = differences
        report["fixtures"][name] = result
        contacts.extend((f"{name} page {i + 1}", image) for i, image in enumerate(poppler))
    contact_sheet(contacts, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
