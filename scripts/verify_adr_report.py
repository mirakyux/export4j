# SPDX-License-Identifier: Apache-2.0
"""Verify the Java/XML ADR examples with independent PDF parsers and renderers.

Run after :examples:adrReport :examples:adrReportXml. Requires the pinned
requirements-verification.txt environment and Poppler on PATH. This checks the
fictional three-page sample; it does not claim regulatory form conformance.
"""
from __future__ import annotations

import argparse
import hashlib
from importlib import metadata
import json
import math
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

import pypdfium2 as pdfium
from pypdf import PdfReader

from verify_advanced_pdf import difference, require
from verify_inline_images import compose
from verify_multi_floats import pdf_geometry, point
from verify_production import actual_text, contact_sheet

ROOT = Path(__file__).resolve().parents[1]
TOLERANCE = .003  # pt: PDF decimal serialization precision, not visual tolerance.


def compact(text):
    return re.sub(r"\s+", "", text)


def embedded_fonts(reader):
    names = set()
    for page in reader.pages:
        for reference in page["/Resources"].get("/Font", {}).values():
            font = reference.get_object()
            names.add(str(font.get("/BaseFont")))
            descendants = font.get("/DescendantFonts", [reference])
            for descendant in descendants:
                descriptor = descendant.get_object().get("/FontDescriptor")
                require(descriptor is not None, "font descriptor missing")
                descriptor = descriptor.get_object()
                programs = [descriptor[key].get_object().get_data()
                            for key in ("/FontFile", "/FontFile2", "/FontFile3") if key in descriptor]
                require(programs and all(programs), "font program is not embedded")
    require(names, "PDF contains no fonts")
    return sorted(names)


def cell_rectangles(reader):
    """Read stroked cell contours, including CTMs, independently of model/IR geometry.

    Semantic tables paint borders as closed four-segment paths. Filled rectangles
    alone only describe the shaded headers and cannot prove the physical grid.
    """
    rectangles = []
    for index, page in enumerate(reader.pages):
        matrix, line_width, stack = (1, 0, 0, 1, 0, 0), 1, []
        subpaths, path, closed = [], [], False
        for operands, operator in page.get_contents().operations:
            if operator == b"q":
                stack.append((matrix, line_width))
            elif operator == b"Q":
                require(stack, "unbalanced cell graphics transform")
                matrix, line_width = stack.pop()
            elif operator == b"cm":
                matrix = compose(matrix, tuple(float(value) for value in operands))
            elif operator == b"w":
                line_width = float(operands[0])
            elif operator == b"m":
                if path:
                    subpaths.append((path, closed))
                path, closed = [point(matrix, *map(float, operands))], False
            elif operator == b"l":
                path.append(point(matrix, *map(float, operands)))
            elif operator == b"h":
                closed = True
            elif operator == b"re":
                x, y, width, height = map(float, operands)
                if path:
                    subpaths.append((path, closed))
                    path, closed = [], False
                subpaths.append(([point(matrix, xx, yy) for xx, yy in
                                  ((x, y), (x + width, y), (x + width, y + height), (x, y + height))], True))
            elif operator in (b"c", b"v", b"y"):
                # Curves cannot represent one of this sample's straight cell contours.
                path.append(None)
            elif operator in (b"S", b"s", b"B", b"B*", b"b", b"b*", b"f", b"f*", b"n"):
                if path:
                    subpaths.append((path, closed or operator in (b"s", b"b", b"b*")))
                if operator in (b"S", b"s", b"B", b"B*", b"b", b"b*"):
                    for vertices, is_closed in subpaths:
                        if not is_closed or len(vertices) != 4 or None in vertices:
                            continue
                        if not all(abs(a[0] - b[0]) < TOLERANCE or abs(a[1] - b[1]) < TOLERANCE
                                   for a, b in zip(vertices, vertices[1:] + vertices[:1], strict=True)):
                            continue
                        xs, ys = zip(*vertices)
                        width, height = max(xs) - min(xs), max(ys) - min(ys)
                        if width > TOLERANCE and height > TOLERANCE:
                            # Borders are inset by half their width; recover the painted outer
                            # cell edge rather than relaxing adjacency/rowspan tolerances.
                            radius_x = line_width * math.hypot(matrix[0], matrix[2]) / 2
                            radius_y = line_width * math.hypot(matrix[1], matrix[3]) / 2
                            rectangles.append({"page": index, "x": min(xs) - radius_x,
                                               "y": float(page.mediabox.height) - max(ys) - radius_y,
                                               "width": width + 2 * radius_x, "height": height + 2 * radius_y})
                subpaths, path, closed = [], [], False
        require(not stack, "unbalanced final cell graphics transform")
    return rectangles


def medication_grid(rectangles):
    """Find the 18 data columns and validate the 3/2-column header merges above them."""
    first_page = [r for r in rectangles if r["page"] == 0]
    groups = {}
    for rectangle in first_page:
        groups.setdefault(round(rectangle["y"], 3), []).append(rectangle)
    data_rows = [sorted(row, key=lambda r: r["x"]) for row in groups.values() if len(row) == 18]
    require(len(data_rows) == 1, "suspected-medication table must have one complete 18-column data row")
    cells = data_rows[0]
    for left, right in zip(cells, cells[1:]):
        require(abs(left["x"] + left["width"] - right["x"]) < TOLERANCE,
                "medication columns are not contiguous")
        require(abs(left["height"] - right["height"]) < TOLERANCE,
                "medication data cells have inconsistent row heights")
    table_top = 480.2  # pt, matched dense-form sample heading/preceding-content geometry.
    header = [r for r in first_page if table_top - TOLERANCE <= r["y"] < cells[0]["y"] - TOLERANCE]
    require(len(header) == 20, "two-level medication header lost or split a merged cell")
    for start, end in ((9, 12), (12, 14)):
        x, width = cells[start]["x"], sum(r["width"] for r in cells[start:end])
        merged = [r for r in header if abs(r["x"] - x) < TOLERANCE
                  and abs(r["width"] - width) < TOLERANCE]
        require(len(merged) == 1 and abs(merged[0]["y"] - table_top) < TOLERANCE,
                "medication usage/date group does not span its physical columns")
        for cell in cells[start:end]:
            require(any(abs(r["x"] - cell["x"]) < TOLERANCE
                        and abs(r["width"] - cell["width"]) < TOLERANCE
                        and abs(r["y"] - merged[0]["y"] - merged[0]["height"]) < TOLERANCE
                        and abs(r["y"] + r["height"] - cell["y"]) < TOLERANCE for r in header),
                    "second-level medication header does not meet its data column")
    for column in (*range(9), *range(14, 18)):
        cell = cells[column]
        require(any(abs(r["x"] - cell["x"]) < TOLERANCE
                    and abs(r["width"] - cell["width"]) < TOLERANCE
                    and abs(r["y"] - table_top) < TOLERANCE
                    and abs(r["y"] + r["height"] - cell["y"]) < TOLERANCE for r in header),
                "medication row-spanning header is not aligned with its data column")
    return {"columns": 18, "headerCells": len(header), "tableTopPt": table_top,
            "dataTopPt": cells[0]["y"], "dataHeightPt": cells[0]["height"]}


def final_geometry(path, rectangles):
    """Read final glyph coordinates with PDFium, independent of Java layout objects."""
    result = []
    with pdfium.PdfDocument(path) as document:
        for index in range(len(document)):
            page = document[index]
            try:
                text = page.get_textpage()
                try:
                    for char_index in range(text.count_chars()):
                        character = text.get_text_range(char_index, 1, errors="ignore")
                        if not character.strip():
                            continue
                        left, bottom, right, top = text.get_charbox(char_index)
                        box = left, page.get_height() - top, right, page.get_height() - bottom
                        require(27.7 <= left <= right <= 566.4 and 41.9 <= box[1] <= box[3] <= 814.5,
                                "visible glyph leaves the form's page regions")
                        if box[1] < 794.2:
                            cells = [r for r in rectangles if r["page"] == index]
                            require(any(r["x"] - .6 <= box[0] and r["y"] - .6 <= box[1]
                                        and box[2] <= r["x"] + r["width"] + .6
                                        and box[3] <= r["y"] + r["height"] + .6 for r in cells)
                                    or index == 0 and box[3] < 183,
                                    "visible glyph crosses a table cell border")
                        result.append((index, character, *box))
                finally:
                    text.close()
            finally:
                page.close()
    require(result, "PDF has no visible glyph coordinates")
    for index, first in enumerate(rectangles):
        require(28.3 - TOLERANCE <= first["x"]
                and first["x"] + first["width"] <= 565.8 + TOLERANCE
                and 42.5 - TOLERANCE <= first["y"]
                and first["y"] + first["height"] <= 794.2 + TOLERANCE,
                "table cell leaves the reserved body region")
        for second in rectangles[index + 1:]:
            if first["page"] != second["page"]:
                continue
            overlap_x = min(first["x"] + first["width"], second["x"] + second["width"]) - max(first["x"], second["x"])
            overlap_y = min(first["y"] + first["height"], second["y"] + second["height"]) - max(first["y"], second["y"])
            require(overlap_x < TOLERANCE or overlap_y < TOLERANCE, "table cells overlap")
    return result


def render(path, directory):
    """Same dense-grid comparison as verify_adr_form: 192 DPI and blur radius 2."""
    poppler_directory, pdfium_directory = directory / "poppler", directory / "pdfium"
    poppler_directory.mkdir(parents=True, exist_ok=True)
    pdfium_directory.mkdir(parents=True, exist_ok=True)
    # Reject stale pages from earlier longer output instead of counting them as this run.
    for target_directory in (poppler_directory, pdfium_directory):
        for image in target_directory.glob("page-*.png"):
            image.unlink()
    subprocess.run(["pdftoppm", "-r", "192", "-png", str(path), str(poppler_directory / "page")],
                   check=True, capture_output=True, timeout=120)
    images = []
    with pdfium.PdfDocument(path) as document:
        for index in range(len(document)):
            page = document[index]
            try:
                bitmap = page.render(scale=192 / 72)
                try:
                    target = pdfium_directory / f"page-{index + 1}.png"
                    with bitmap.to_pil() as original:
                        with original.convert("RGB") as image:
                            image.save(target)
                    images.append(target)
                finally:
                    bitmap.close()
            finally:
                page.close()
    return sorted(poppler_directory.glob("page-*.png"), key=lambda p: int(p.stem.rsplit("-", 1)[1])), images


def check_pdf(path):
    reader = PdfReader(path)
    require(len(reader.pages) == 3, "default fictional case should paginate naturally into three pages")
    require(not reader.get_fields(), "this static report must not acquire interactive form fields")
    texts = [compact(page.extract_text() or "") for page in reader.pages]
    joined = "".join(texts)
    for token in ("上市许可持有人药品不良反应报告表", "示例患者甲", "DEMO-001",
                  "虚构批准文号DEMO000001", "示例皮疹", "示例报告人",
                  "患者因示例疾病甲使用示例胶囊甲后出现散在皮疹，停药后经对症处理好转。此处为虚构经过，仅供模板演示。",
                  "所有病例、药品、机构和时间均为虚构，仅展示可编辑表单版式。"):
        require(joined.count(compact(token)) == 1, f"case content lost or duplicated: {token}")
    require(joined.count("示例胶囊甲") == 2, "medication name missing from table or narrative")
    require(joined.count("用法用量") == 2, "suspected/concomitant multi-level headers missing")
    for index, tokens in enumerate((("患者信息", "怀疑用药（可重复）", "虚构批准文号DEMO000001"),
                                    ("合并用药（可重复）", "不良反应（可重复）", "初始报告人姓名*"),
                                    ("备注", "所有病例、药品、机构和时间均为虚构"))):
        require(all(compact(token) in texts[index] for token in tokens),
                f"form sections shifted from expected page {index + 1}")
    for index, page in enumerate(reader.pages):
        require(abs(float(page.mediabox.width) - 595.3) < .01
                and abs(float(page.mediabox.height) - 841.9) < .01, "A4 page dimensions changed")
    # pypdf exposes the carrier glyph for outlined fields rather than /ActualText.
    require(actual_text(reader) == [f"- {index + 1} -" for index in range(3)],
            "resolved page footer logical text is missing or stale")
    with pdfium.PdfDocument(path) as document:
        for index in range(len(document)):
            page = document[index]
            try:
                text = page.get_textpage()
                try:
                    require(compact(text.get_text_bounded(0, 0, page.get_width(), 48)) == f"-{index + 1}-",
                            "visible page footer is missing, misplaced or stale")
                finally:
                    text.close()
            finally:
                page.close()
    rectangles = cell_rectangles(reader)
    _, anchors, _ = pdf_geometry(reader, 16)
    grid = medication_grid(rectangles)
    glyphs = final_geometry(path, rectangles)
    return {"pages": 3, "fonts": embedded_fonts(reader), "cells": len(rectangles),
            "cellsPerPage": [sum(r["page"] == i for r in rectangles) for i in range(3)],
            "visibleGlyphs": len(glyphs), "medicationGrid": grid,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}, (texts, rectangles, anchors, glyphs, actual_text(reader))


def equal_geometry(first, second):
    require(first[0] == second[0] and first[4] == second[4], "Java/XML final text content or order differs")
    for label, left, right in (("cell", first[1], second[1]), ("logical anchor", first[2], second[2])):
        require(len(left) == len(right), f"Java/XML {label} counts differ")
        for a, b in zip(left, right, strict=True):
            require(a.keys() == b.keys(), f"Java/XML {label} coordinate fields differ")
            for key in a:
                require(abs(a[key] - b[key]) < TOLERANCE if isinstance(a[key], float) else a[key] == b[key],
                        f"Java/XML {label} differs at {key}: {a} vs {b}")
    require(len(first[3]) == len(second[3]), "Java/XML visible glyph counts differ")
    for a, b in zip(first[3], second[3], strict=True):
        require(a[:2] == b[:2] and all(abs(x - y) < TOLERANCE for x, y in zip(a[2:], b[2:], strict=True)),
                "Java/XML visible glyph content or coordinates differ")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-directory", type=Path, default=ROOT / "examples/build/adr-report")
    parser.add_argument("--render-directory", type=Path, default=ROOT / "build/verification/adr-report")
    args = parser.parse_args()
    args.render_directory.mkdir(parents=True, exist_ok=True)
    template = ROOT / "examples/src/main/resources/templates/adverse-reaction.xml"
    require(not any(element.tag in ("page-break", "pageBreak") for element in ET.parse(template).iter()),
            "XML report uses explicit page breaks instead of natural pagination")
    source = ROOT / "examples/src/main/java/com/mirakyux/export4j/examples/AdrReportExample.java"
    require(not re.search(r"\b(?:pageBreak\s*\(|new\s+PageBreak\b)", source.read_text(encoding="utf-8")),
            "Java report uses explicit page breaks instead of natural pagination")
    report = {"tools": {name: metadata.version(name) for name in ("pypdf", "pypdfium2", "Pillow")},
              "renderComparison": {"dpi": 192, "blurPixels": 2, "meanLimit": 4, "changedFractionLimit": .06}}
    geometries, rendered, contacts = {}, {}, []
    for name in ("java", "xml"):
        path = args.output_directory / f"{name}.pdf"
        result, geometries[name] = check_pdf(path)
        poppler, pdfium_pages = render(path, args.render_directory / name)
        require(len(poppler) == len(pdfium_pages) == 3, "independent renderer omitted a page")
        differences = [difference(a, b, 2) for a, b in zip(poppler, pdfium_pages, strict=True)]
        require(all(d["meanAbsoluteChannelError"] < 4 and d["changedPixelFraction"] < .06 for d in differences),
                "independent renderers differ")
        result["renderDifferences"] = differences
        report[name] = result
        rendered[name] = poppler
        contacts.extend((f"{name}-{index + 1}", image) for index, image in enumerate(poppler))
    equal_geometry(geometries["java"], geometries["xml"])
    pair_differences = [difference(a, b) for a, b in zip(rendered["java"], rendered["xml"], strict=True)]
    require(all(d["meanAbsoluteChannelError"] == 0 and d["changedPixelFraction"] == 0 for d in pair_differences),
            "Java/XML pages have different visible pixels")
    report["javaXmlComparison"] = {"matchingTextAndGeometry": True, "pagePixels": pair_differences}
    contact_sheet(contacts, args.render_directory)
    (args.render_directory / "verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=True))


if __name__ == "__main__":
    main()
