# SPDX-License-Identifier: Apache-2.0
"""Independent PDF float/anchor geometry, logical source order and PDF/SVG/PNG rendering checks."""
from __future__ import annotations

import io
import json
from pathlib import Path
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from PIL import Image
from pypdf import PdfReader
from verify_advanced_pdf import difference, render_pdfium, render_poppler, require
from verify_inline_images import compose, image_boxes
from verify_production import actual_text, contact_sheet

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "examples/build/multi-floats"
OUTPUT = ROOT / "build/verification/multi-floats"
TOLERANCE = .003


def point(matrix, x, y):
    return matrix[0] * x + matrix[2] * y + matrix[4], matrix[1] * x + matrix[3] * y + matrix[5]


def rectangle(matrix, operands, page_height):
    x, y, width, height = (float(value) for value in operands)
    points = [point(matrix, xx, yy) for xx in (x, x + width) for yy in (y, y + height)]
    xs, ys = zip(*points)
    return {"x": min(xs), "y": page_height - max(ys), "width": max(xs) - min(xs), "height": max(ys) - min(ys)}


def pdf_geometry(reader, step):
    """Read final CTMs and the backend's nonpainting ActualText anchors without importing Java layout code."""
    rectangles, lines = [], []
    clips = 0
    for page_index, page in enumerate(reader.pages):
        matrix = (1, 0, 0, 1, 0, 0)
        scaling = 100
        text_matrix = None
        stack, marked, pending = [], [], []
        for operands, operator in page.get_contents().operations:
            if operator == b"q":
                stack.append((matrix, scaling, text_matrix))
            elif operator == b"Q":
                matrix, scaling, text_matrix = stack.pop()
            elif operator == b"cm":
                matrix = compose(matrix, tuple(float(value) for value in operands))
            elif operator == b"BDC":
                properties = operands[1]
                if not isinstance(properties, dict):
                    properties = page["/Resources"].get("/Properties", {}).get(properties, {})
                marked.append(properties.get("/ActualText"))
            elif operator == b"BMC":
                marked.append(None)
            elif operator == b"EMC":
                marked.pop()
            elif operator == b"Tm":
                text_matrix = tuple(float(value) for value in operands)
            elif operator == b"Tz":
                scaling = float(operands[0])
            elif operator == b"Tj" and marked and marked[-1] is not None:
                require(text_matrix is not None, "logical text anchor has no matrix")
                x, y = point(matrix, text_matrix[4], text_matrix[5])
                lines.append({"page": page_index, "source": str(marked[-1]), "x": x,
                              "y": float(page.mediabox.height) - y - step * .8, "width": scaling / 200, "height": step})
            elif operator == b"re":
                pending.append(rectangle(matrix, operands, float(page.mediabox.height)))
            elif operator in (b"f", b"f*"):
                if not marked:
                    rectangles.extend({"page": page_index, **bounds} for bounds in pending)
                pending.clear()
            elif operator == b"W":
                clips += 1
            elif operator == b"n":
                pending.clear()
        require(not stack and not marked, "unbalanced graphics or logical text scope")
    return rectangles, lines, clips


def check_geometry(reader, fixture, config):
    step = config["lineHeight"]
    margin = config["margin"]
    width = config["pageWidth"] - 2 * margin
    height = config["pageHeight"] - 2 * margin
    extent = max(box["top"] + box["height"] for box in fixture["floats"])
    first_page = int(extent > height - fixture["prefixHeight"] or height - fixture["prefixHeight"] < step)
    origin = margin + (fixture["prefixHeight"] if first_page == 0 else 0)
    rectangles, lines, clips = pdf_geometry(reader, step)
    require(len(rectangles) == len(fixture["floats"]), "float lost, duplicated or split across pages")
    require(clips >= len(rectangles), "float viewport clipping missing")
    for actual, box in zip(rectangles, fixture["floats"]):
        require(actual["page"] == first_page, "float group starts on incorrect page")
        expected = {"x": margin + (0 if box["side"] == "LEFT" else width - box["width"]),
                    "y": origin + box["top"], "width": box["width"], "height": box["height"]}
        for key, value in expected.items():
            require(abs(actual[key] - value) < TOLERANCE, f"float {key} differs: {actual} vs {expected}")
    require(lines, "body text missing")
    require([line["source"] for line in lines] == actual_text(reader), "ActualText anchor order differs")
    for line in lines:
        page_origin = origin if line["page"] == first_page else margin
        require(line["page"] >= first_page, "body text appears before float group")
        require(line["y"] >= page_origin - TOLERANCE and line["y"] + step <= config["pageHeight"] - margin + TOLERANCE, "line leaves content region")
        row = (line["y"] - page_origin) / step
        require(abs(row - round(row)) < .0002, "line no longer follows the fixed line grid")
        left, right = margin, margin + width
        if line["page"] == first_page:
            top = line["y"] - origin
            for box in fixture["floats"]:
                if top < box["top"] + box["height"] - TOLERANCE and box["top"] < top + step - TOLERANCE:
                    if box["side"] == "LEFT":
                        left = max(left, margin + box["width"] + box["gap"])
                    else:
                        right = min(right, margin + width - box["width"] - box["gap"])
        require(line["x"] >= left - TOLERANCE and line["x"] + line["width"] <= right + TOLERANCE, "text intersects active float clearance")
        if line["page"] > first_page:
            require(abs(line["x"] - margin) < TOLERANCE, "continued page did not restore full width")
    if fixture["name"] == "blocked":
        require(abs(lines[0]["y"] - margin - 44) < TOLERANCE, "closed line bands were not skipped")
    if fixture["name"] == "float-only":
        require(lines[0]["page"] == 1 and len(reader.pages) == 2, "full-height float must make progress before body continuation")
    return {"firstFloatPage": first_page, "floatRectangles": rectangles, "bodyLines": len(lines), "clips": clips}


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    config = json.loads((FIXTURES / "geometry.json").read_text(encoding="utf-8"))
    report, contacts = {}, []
    for fixture in config["fixtures"]:
        name = fixture["name"]
        path = FIXTURES / f"{name}.pdf"
        reader = PdfReader(path)
        require(len(reader.pages) == fixture["pages"], "PDF page count differs")
        require(all(abs(float(p.mediabox.width) - 440) < .001 and abs(float(p.mediabox.height) - 480) < .001 for p in reader.pages), "page settings changed")
        expected_source = (FIXTURES / f"{name}.txt").read_text(encoding="utf-8").replace("\n", "")
        logical = actual_text(reader)
        require("".join(logical) == expected_source, "body source lost, duplicated or reordered")
        geometry = check_geometry(reader, fixture, config)
        images = image_boxes(reader)
        require(len(images) == int(name in ("staggered", "float-only")), "float image lost or repeated")
        svg = ET.parse(FIXTURES / f"{name}.svg").getroot()
        ns = "{http://www.w3.org/2000/svg}"
        require(len(svg.findall(f"{ns}svg")) == len(reader.pages), "SVG pages differ")
        svg_source = [g.get("aria-label") for g in svg.iter(f"{ns}g") if g.get("aria-label") in set(logical)]
        require(svg_source == logical, "SVG logical body order differs from PDF")
        require(len(svg.findall(f".//{ns}image")) == len(images), "SVG float image differs")
        target = OUTPUT / name
        poppler = render_poppler(path, target / "poppler")
        pdfium = render_pdfium(path, target / "pdfium")
        require(len(poppler) == len(pdfium) == len(reader.pages), "independent renderer page counts differ")
        metrics = [difference(a, b, 1) for a, b in zip(poppler, pdfium)]
        require(all(m["meanAbsoluteChannelError"] < 4 and m["changedPixelFraction"] < .06 for m in metrics), "independent renderers disagree")
        raster_metrics = []
        with ZipFile(FIXTURES / f"{name}.zip") as archive:
            pages = sorted(archive.namelist())
            require(len(pages) == len(reader.pages), "PNG page count differs")
            for index, page in enumerate(pages):
                with Image.open(io.BytesIO(archive.read(page))) as image, Image.open(poppler[index]) as reference:
                    require(image.size == (880, 960), "144 DPI raster geometry differs")
                    rendered = target / f"raster-{index + 1}.png"
                    image.convert("RGB").resize(reference.size, Image.Resampling.LANCZOS).save(rendered)
                raster_metrics.append(difference(rendered, poppler[index], 1))
        require(all(m["meanAbsoluteChannelError"] < 4 and m["changedPixelFraction"] < .06 for m in raster_metrics), "static raster disagrees with PDF")
        contacts.extend((f"{name} {i + 1}", page) for i, page in enumerate(poppler))
        report[name] = {"pages": len(reader.pages), **geometry, "images": len(images), "rendererDifferences": metrics, "rasterDifferences": raster_metrics}
    contact_sheet(contacts, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
