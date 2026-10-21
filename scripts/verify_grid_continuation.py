# SPDX-License-Identifier: Apache-2.0
"""Independent source, cell/glyph/image geometry and all-page cross-renderer checks."""
from __future__ import annotations

import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import pypdfium2 as pdfium
from pypdf import PdfReader
from verify_advanced_pdf import difference, render_pdfium, render_poppler, require
from verify_inline_images import image_boxes
from verify_multi_floats import pdf_geometry
from verify_production import actual_text, contact_sheet

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "examples/build/grid-continuation"
OUTPUT = ROOT / "build/verification/grid-continuation"
NS = "{http://www.w3.org/2000/svg}"


def check(path, name, prefix, count, margin, images=0, headers=True):
    reader = PdfReader(path)
    logical = "".join(actual_text(reader))
    glyphs = "\n".join(page.extract_text() or "" for page in reader.pages)
    source = logical if name == "shaped" else glyphs
    require(re.findall(prefix + r"(\d{3})", source) == [f"{index:03d}" for index in range(1, count + 1)],
            f"{name}: lines lost, duplicated, truncated or reordered")
    if name == "merged":
        require(glyphs.count("中文长单元格续页验证。") == 120, "merged: Chinese source lost or replaced")
    if name == "shaped":
        require(logical.count("中文 العربية a\u0301") == 26, "shaped: logical clusters or mixed scripts lost")
    if headers:
        require(glyphs.count("SIDE-A") == glyphs.count("SIDE-B") == 1, f"{name}: neighbor duplicated or missing")
        for page in reader.pages:
            text = page.extract_text() or ""
            # Continuous columns repeat headers per occupied column; a final tail-only page needs none.
            if re.search(prefix + r"\d{3}", text) or (name == "shaped" and page.get_contents()):
                require(text.count("HEAD-A") == text.count("HEAD-B") >= 1, f"{name}: missing repeated headers")
    boxes = image_boxes(reader)
    require(len(boxes) == images, f"{name}: image lost or duplicated")
    if name == "mixed":
        require(glyphs.count("AFTER-IMAGE") == glyphs.count("TAIL-MIXED") == 1, "mixed: tail lost")
    if name == "columns":
        require(glyphs.count("TAIL-COLUMNS") == 1, "columns: following paragraph lost")
        require(glyphs.count("HEAD-A") > len(reader.pages), "columns: headers must repeat by column")
    if name == "nested":
        require(glyphs.count("FIXED-HEADING") == glyphs.count("TAIL-NESTED") == 1, "nested: fixed text lost")
    rectangles, anchors, _ = pdf_geometry(reader, 22 if name == "shaped" else 16)
    if headers:
        require(rectangles, f"{name}: cell rectangles missing")
        for index, a in enumerate(rectangles):
            page = reader.pages[a["page"]]
            require(a["x"] >= margin - .003 and a["y"] >= margin - .003
                    and a["x"] + a["width"] <= float(page.mediabox.width) - margin + .003
                    and a["y"] + a["height"] <= float(page.mediabox.height) - margin + .003,
                    f"{name}: cell leaves content area: {a}")
            for b in rectangles[index + 1:]:
                if a["page"] != b["page"]:
                    continue
                overlap_x = min(a["x"] + a["width"], b["x"] + b["width"]) - max(a["x"], b["x"])
                overlap_y = min(a["y"] + a["height"], b["y"] + b["height"]) - max(a["y"], b["y"])
                require(overlap_x < .003 or overlap_y < .003, f"{name}: overlapping cell interiors")
        for image in boxes:
            require(any(a["page"] == image["page"] and a["x"] <= image["x"] + .003 and a["y"] <= image["y"] + .003
                        and a["x"] + a["width"] >= image["x"] + image["width"] - .003
                        and a["y"] + a["height"] >= image["y"] + image["height"] - .003 for a in rectangles),
                    f"{name}: image not contained in a cell")
        for anchor in anchors:
            require(any(a["page"] == anchor["page"] and a["x"] <= anchor["x"] + .003 and a["y"] <= anchor["y"] + .003
                        and a["x"] + a["width"] >= anchor["x"] + anchor["width"] - .003
                        and a["y"] + a["height"] >= anchor["y"] + anchor["height"] - .003 for a in rectangles),
                    f"{name}: complete shaped line leaves cell")
    # A different PDF parser measures final glyph positions, including fractional continuation offsets.
    glyph_count = 0
    with pdfium.PdfDocument(path) as document:
        for page_index in range(len(document)):
            page = document[page_index]
            textpage = page.get_textpage()
            try:
                for index in range(textpage.count_chars()):
                    char = textpage.get_text_range(index, 1)
                    if not char or char.isspace() or char == "\x02":
                        continue
                    x1, y1, x2, y2 = textpage.get_charbox(index)
                    y = page.get_height() - y2
                    glyph_count += 1
                    require(x1 >= margin - 2 and y >= margin - 2
                            and x2 <= page.get_width() - margin + 2 and page.get_height() - y1 <= page.get_height() - margin + 2,
                            f"{name}: glyph leaves page margins")
                    if headers and name != "shaped" and not char.isspace():
                        in_cell = any(a["page"] == page_index and a["x"] - 1 <= x1 and a["y"] - 1 <= y
                                      and a["x"] + a["width"] + 1 >= x2 and a["y"] + a["height"] + 1 >= page.get_height() - y1
                                      for a in rectangles)
                        # Following semantic paragraphs are outside the table by design.
                        below_cells = rectangles and y >= max((a["y"] + a["height"] for a in rectangles if a["page"] == page_index), default=0) - 1
                        require(in_cell or (name in ("mixed", "columns", "consumer") and below_cells), f"{name}: glyph leaves its cell")
            finally:
                textpage.close()
                page.close()
    if name != "shaped":
        require(glyph_count > 0, f"{name}: independent parser found no glyphs")
    return {"pages": len(reader.pages), "bodyLines": count, "cells": len(rectangles), "images": len(boxes), "glyphs": glyph_count}


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    cases = {"merged": ("ROW", 120, 36, 0, True), "mixed": ("MIX", 17, 20, 1, True),
             "columns": ("COL", 46, 24, 0, True), "shaped": ("SHP", 26, 20, 0, True),
             "nested": ("TXT", 40, 20, 0, False)}
    config = json.loads((FIXTURES / "results.json").read_text(encoding="utf-8"))
    report, contacts = {}, []
    for fixture in config["fixtures"]:
        name = fixture["name"]
        path = FIXTURES / f"{name}.pdf"
        report[name] = check(path, name, *cases[name])
        require(report[name]["pages"] == fixture["pages"], f"{name}: reported page count differs")
        svg = ET.parse(FIXTURES / f"{name}.svg").getroot()
        labels = "".join(g.get("aria-label", "") for g in svg.iter(f"{NS}g"))
        prefix, count, *_ = cases[name]
        # Glyph text is represented by ordinary SVG text; shaped text uses aria-label.
        svg_text = labels if name == "shaped" else "".join(svg.itertext())
        require(re.findall(prefix + r"(\d{3})", svg_text) == [f"{index:03d}" for index in range(1, count + 1)],
                f"{name}: SVG body order differs")
        poppler = render_poppler(path, OUTPUT / name / "poppler")
        pdfium_pages = render_pdfium(path, OUTPUT / name / "pdfium")
        require(len(poppler) == len(pdfium_pages) == report[name]["pages"], f"{name}: renderer pages differ")
        comparisons = [difference(a, b, blur_radius=1) for a, b in zip(poppler, pdfium_pages, strict=True)]
        require(all(d["meanAbsoluteChannelError"] < 4 and d["changedPixelFraction"] < .06 for d in comparisons),
                f"{name}: independent renderers disagree")
        report[name]["crossRendererBlur1"] = comparisons
        contacts.extend((f"{name} / {index + 1}", image) for index, image in enumerate(poppler))
    pointer = ROOT / "build/verification/consumer-build-directory.txt"
    require(pointer.exists(), "run verify_local_consumer.ps1 first")
    consumer = Path(pointer.read_text(encoding="utf-8").strip()) / "output/consumer-grid-continuation.pdf"
    report["consumer"] = check(consumer, "consumer", "CON", 24, 16)
    consumer_poppler = render_poppler(consumer, OUTPUT / "consumer/poppler")
    consumer_pdfium = render_pdfium(consumer, OUTPUT / "consumer/pdfium")
    comparisons = [difference(a, b, blur_radius=1) for a, b in zip(consumer_poppler, consumer_pdfium, strict=True)]
    require(len(consumer_poppler) == len(consumer_pdfium) == report["consumer"]["pages"], "consumer: renderer pages differ")
    require(all(d["meanAbsoluteChannelError"] < 4 and d["changedPixelFraction"] < .06 for d in comparisons),
            "consumer: independent renderers disagree")
    report["consumer"]["crossRendererBlur1"] = comparisons
    contacts.extend((f"consumer / {index + 1}", image) for index, image in enumerate(consumer_poppler))
    contact_sheet(contacts, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
