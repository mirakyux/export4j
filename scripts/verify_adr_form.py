# SPDX-License-Identifier: Apache-2.0
"""Independent final-PDF cell/tick/text geometry and all-page rendering for the ADR authoring probe."""
from __future__ import annotations

from importlib import metadata
import json
from pathlib import Path
import re
import subprocess

import pypdfium2 as pdfium
from pypdf import PdfReader
from verify_advanced_pdf import difference, require
from verify_multi_floats import pdf_geometry
from verify_production import actual_text, contact_sheet
from static_forms_geometry import form_marks

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "examples/build/adr-form"
OUTPUT = ROOT / "build/verification/adr-form"
NARRATIVE = "本页为虚构的排版能力验证数据，不用于医疗判断。示例患者服药后报告皮肤瘙痒，停药后症状好转；相关信息记录于本表。"


def render(path, directory):
    """192 DPI resolves dense 0.5 pt grid lines; blur2 has the same 0.75 pt radius as existing 96 DPI blur1 checks."""
    poppler, pdfium_paths = directory / "poppler", directory / "pdfium"
    poppler.mkdir(parents=True, exist_ok=True)
    pdfium_paths.mkdir(parents=True, exist_ok=True)
    subprocess.run(["pdftoppm", "-r", "192", "-png", str(path), str(poppler / "page")], check=True, capture_output=True)
    paths = []
    with pdfium.PdfDocument(path) as document:
        for index in range(len(document)):
            page = document[index]
            try:
                bitmap = page.render(scale=192 / 72)
                try:
                    target = pdfium_paths / f"page-{index + 1}.png"
                    bitmap.to_pil().convert("RGB").save(target)
                    paths.append(target)
                finally:
                    bitmap.close()
            finally:
                page.close()
    return sorted(poppler.glob("page-*.png"), key=lambda p: int(p.stem.rsplit("-", 1)[1])), paths


def contains(rect, box, tolerance=.6):
    return (rect["x"] - tolerance <= box[0] and rect["y"] - tolerance <= box[1]
            and box[2] <= rect["x"] + rect["width"] + tolerance
            and box[3] <= rect["y"] + rect["height"] + tolerance)


def ticks(reader, rectangles):
    marks = form_marks(reader)
    for mark in marks:
        box = mark["x"], mark["y"], mark["x"] + mark["size"], mark["y"] + mark["size"]
        cells = [r for r in rectangles if r["page"] == mark["page"]]
        require(any(contains(r, box) for r in cells) or mark["page"] == 0 and box[3] < min(r["y"] for r in cells),
                "static mark leaves its form region")
    require(sum(m["selected"] for m in marks) == 16, "selected vector check marks lost or repeated")
    require(sum(m["selected"] for m in marks) == "".join(actual_text(reader)).count("☑"), "logical and vector checks differ")
    return sum(m["selected"] for m in marks)


def check_geometry(path, rectangles):
    characters = 0
    with pdfium.PdfDocument(path) as document:
        for index in range(len(document)):
            page = document[index]
            text = page.get_textpage()
            try:
                height = page.get_height()
                cells = [r for r in rectangles if r["page"] == index]
                first_row = min(r["y"] for r in cells)
                for char_index in range(text.count_chars()):
                    character = text.get_text_range(char_index, 1, errors="ignore")
                    if not character.strip():
                        continue
                    left, bottom, right, top = text.get_charbox(char_index)
                    box = left, height - top, right, height - bottom
                    require(27 <= left <= right <= 566.4 and 42 <= box[1] <= box[3] <= 814,
                            "printed character leaves page margins")
                    if box[1] < 792:
                        require(any(contains(r, box) for r in cells) or index == 0 and box[3] < first_row,
                                "printed character crosses a cell border")
                    characters += 1
            finally:
                text.close()
                page.close()
    return characters


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    report = {"tools": {name: metadata.version(name) for name in ("pypdf", "pypdfium2", "Pillow")}}
    contacts = []
    for name, pages, repeats, per_page in (
            ("adr-form", 3, 1, [74, 38, 2]), ("adr-form-expanded", 4, 12, [74, 30, 8, 2])):
        path = FIXTURES / f"{name}.pdf"
        reader = PdfReader(path)
        require(len(reader.pages) == pages, "incorrect compact/expanded page count")
        text = re.sub(r"\s+", "", "".join(p.extract_text() or "" for p in reader.pages) + "".join(actual_text(reader)))
        for token in ("上市许可持有人药品不良反应报告表", "DEMO-2025-000001", "DEMO-H00001",
                      "国药准字DEMO0001", "其他需说明的情况：", "本表全部病例数据均为虚构"):
            require(text.count(token) == 1, "case text lost, repeated or reordered")
        require(text.count(NARRATIVE) == repeats, "narrative lost or repeated during pagination")
        require(text.count("用法用量") == 2, "multi-level medication headers lost")
        require(not reader.get_fields(), "static form sample unexpectedly has editable fields")
        for index, page in enumerate(reader.pages):
            require(abs(float(page.mediabox.width) - 595.3) < .01
                    and abs(float(page.mediabox.height) - 841.9) < .01, "incorrect paper size")
            require(f"-{index + 1}-" in re.sub(r"\s+", "", page.extract_text() or ""), "footer lost or stale")
        rectangles, _, _ = pdf_geometry(reader, 16)
        require([sum(r["page"] == i for r in rectangles) for i in range(pages)] == per_page,
                "merged cells lost, split or assigned to a wrong page")
        for index, first in enumerate(rectangles):
            require(28 - .01 <= first["x"] and first["x"] + first["width"] <= 565.3 + .01
                    and 42.5 - .01 <= first["y"] and first["y"] + first["height"] <= 791.4 + .01,
                    "cell leaves the reserved body region")
            for second in rectangles[index + 1:]:
                if first["page"] != second["page"]:
                    continue
                overlap_x = min(first["x"] + first["width"], second["x"] + second["width"]) - max(first["x"], second["x"])
                overlap_y = min(first["y"] + first["height"], second["y"] + second["height"]) - max(first["y"], second["y"])
                require(overlap_x < .01 or overlap_y < .01, "form cells overlap")
        result = {"pages": pages, "cells": len(rectangles), "ticks": ticks(reader, rectangles),
                  "characters": check_geometry(path, rectangles), "narrativeOccurrences": repeats}
        poppler, pdfium_pages = render(path, OUTPUT / name)
        require(len(poppler) == len(pdfium_pages) == pages, "independent renderer omitted a page")
        differences = [difference(a, b, 2) for a, b in zip(poppler, pdfium_pages, strict=True)]
        require(all(d["meanAbsoluteChannelError"] < 4 and d["changedPixelFraction"] < .06 for d in differences),
                "independent renderers differ")
        result["renderDifferences"] = differences
        report[name] = result
        contacts.extend((f"{name}-{index + 1}", path) for index, path in enumerate(poppler))
    require((FIXTURES / "oversized-cell.txt").read_text(encoding="utf-8") == "COMPONENT_LAYOUT_FAILED",
            "oversized component cell did not fail explicitly")
    source = (ROOT / "examples/src/main/java/com/mirakyux/export4j/examples/AdrFormExample.java").read_text(encoding="utf-8").splitlines()
    report["sourceLines"] = len(source)
    report["nonblankSourceLines"] = sum(bool(line.strip()) for line in source)
    report["renderComparison"] = {"dpi": 192, "blurPixels": 2, "meanLimit": 4, "changedFractionLimit": .06}
    results = dict(line.split("=", 1) for line in (FIXTURES / "results.txt").read_text(encoding="utf-8").splitlines())
    report["headerOrderProbe"] = {key: float(results[key]) for key in ("headerHeight", "spansFirstHeight")}
    require(float(results["headerHeight"]) == float(results["spansFirstHeight"]),
            "merged row geometry depends on cell declaration order")
    contact_sheet(contacts, OUTPUT)
    (OUTPUT / "verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=True))


if __name__ == "__main__":
    main()
