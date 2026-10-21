# SPDX-License-Identifier: Apache-2.0
"""Independent mixed-column source, image/table geometry and all-page static rendering checks."""
from __future__ import annotations

import io
import json
from pathlib import Path
import xml.etree.ElementTree as ET
from zipfile import ZipFile
from PIL import Image
from pypdf import PdfReader
from verify_advanced_pdf import difference, render_pdfium, render_poppler, require
from verify_continuous_columns import font_ascent, glyph_lines
from verify_inline_images import image_boxes
from verify_multi_floats import pdf_geometry
from verify_production import contact_sheet

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "examples/build/mixed-columns"
OUTPUT = ROOT / "build/verification/mixed-columns"
TOLERANCE = .003


def expectations(name):
    """Explicit input-derived fixture positions, independent of Java region allocation."""
    glyphs, outlines, images = [], [], []
    def add(text, page, x, y):
        glyphs.append({"source": text, "page": page, "x": x, "y": y})
    def table_group(page, x, y, rows, cell_width):
        for side, xx in (("L", x), ("R", x + cell_width)):
            add("HEAD-" + side, page, xx, y)
        for offset, row in enumerate(rows, 1):
            for side, xx in (("L", x), ("R", x + cell_width)):
                add(f"ROW-{row:02d}-{side}", page, xx, y + offset * 26)
    if name == "mixed":
        add("ARTICLE", 0, 32, 288)
        images.append({"page": 0, "x": 32, "y": 318, "width": 176, "height": 66})
        for page, x, y, rows in ((0, 32, 392, range(1, 3)), (0, 232, 288, range(3, 10)), (1, 32, 32, range(10, 25))):
            for offset, row in enumerate(rows):
                add(f"MIX-{row:02d} Flow text", page, x, y + offset * 22)
        add("INSIDE-END", 1, 32, 370)
        table_group(1, 234, 34, [1], 88)
        return glyphs, outlines, images, 396, 4
    if name == "table":
        table_group(0, 30, 286, range(1, 6), 92)
        table_group(0, 230, 286, range(6, 11), 92)
        table_group(1, 30, 30, range(11, 22), 92)
        return glyphs, outlines, images, 340, 3
    if name == "merged":
        add("HEAD-L", 1, 30, 30); add("HEAD-R", 1, 122, 30)
        images.append({"page": 1, "x": 30, "y": 56, "width": 80, "height": 48})
        add("MERGE-A", 1, 122, 56); add("MERGE-B", 1, 122, 82)
        for row in range(3, 6):
            add(f"MERGE-{row:02d}-L", 1, 30, 108 + (row - 3) * 26)
            add(f"MERGE-{row:02d}-R", 1, 122, 108 + (row - 3) * 26)
        return glyphs, outlines, images, 184, 1
    require(name == "nested", "unknown fixture")
    add("INNER", 0, 32, 312)
    for page, x, y, rows in ((0, 32, 340, range(1, 5)), (0, 232, 312, range(5, 11)), (1, 32, 32, range(11, 25))):
        for offset, row in enumerate(rows):
            outlines.append({"source": f"NEST-{row:02d} 中文 العربية a\u0301", "page": page, "x": x, "y": y + offset * 22})
    add("INNER-END", 1, 32, 346)
    return glyphs, outlines, images, 372, 3


def positions_match(actual, expected, label):
    require([p.get("source") for p in actual] == [p.get("source") for p in expected], f"{label}: source lost, duplicated or reordered")
    require(len(actual) == len(expected), f"{label}: element count differs")
    max_error = 0.0
    for got, wanted in zip(actual, expected, strict=True):
        require(got["page"] == wanted["page"], f"{label}: element on wrong page")
        for key in ("x", "y", "width", "height"):
            if key in wanted:
                error = abs(got[key] - wanted[key])
                max_error = max(max_error, error)
                require(error < TOLERANCE, f"{label}: {key} differs: {got} vs {wanted}")
    return max_error


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    config = json.loads((FIXTURES / "geometry.json").read_text(encoding="utf-8"))
    ascent = max(font_ascent(ROOT / "assets/fonts" / name) for name in (
        "LiberationSans-Regular.ttf", "NotoNaskhArabic-Regular.ttf", "LXGWWenKaiTC-Regular.ttf"))
    report, contacts = {}, []
    for fixture in config["fixtures"]:
        name = fixture["name"]
        path = FIXTURES / f"{name}.pdf"
        reader = PdfReader(path)
        require(len(reader.pages) == fixture["pages"] == 2, f"{name}: page count differs")
        require(all(abs(float(p.mediabox.width) - 440) < .001 and abs(float(p.mediabox.height) - 480) < .001 for p in reader.pages), "page dimensions differ")
        expected_glyphs, expected_outlines, expected_images, tail_y, columns = expectations(name)
        glyphs = glyph_lines(reader, ascent)
        # Every visible fixture body label is included, so extra/repeated table or text labels fail too.
        labels = ("ARTICLE", "MIX-", "INSIDE-END", "HEAD-", "ROW-", "MERGE-", "INNER")
        body = [p for p in glyphs if p["source"].startswith(labels)]
        _, outlines, clips = pdf_geometry(reader, config["lineHeight"])
        error = max(positions_match(body, expected_glyphs, name + " glyphs"),
                    positions_match(outlines, expected_outlines, name + " outlines"),
                    positions_match(image_boxes(reader), expected_images, name + " images"))
        require(clips == columns + len(expected_images), f"{name}: column/image viewport clipping differs")
        tails = [p for p in glyphs if p["source"] == f"TAIL / {name}"]
        positions_match(tails, [{"source": f"TAIL / {name}", "page": 1, "x": 28, "y": tail_y}], name + " tail")
        source = (FIXTURES / f"{name}.txt").read_text(encoding="utf-8").splitlines()
        if name == "table":
            require([p["source"].removesuffix("-L") for p in body if p["source"].startswith("ROW-") and p["source"].endswith("-L")] == source, "table body row coverage differs")
        else:
            actual_source = outlines if name == "nested" else [p for p in body if p["source"].startswith("MIX-" if name == "mixed" else "MERGE-")]
            require([p["source"] for p in actual_source] == source, "original body lines differ")
        svg = ET.parse(FIXTURES / f"{name}.svg").getroot()
        ns = "{http://www.w3.org/2000/svg}"
        require(len(svg.findall(f"{ns}svg")) == 2, "SVG page count differs")
        logical = [g.get("aria-label") for g in svg.iter(f"{ns}g")]
        ordered = [expected_glyphs[0], *expected_outlines, expected_glyphs[-1]] if name == "nested" else expected_glyphs
        all_expected = [p["source"] for p in ordered]
        require([v for v in logical if v in set(all_expected)] == all_expected, "SVG body/header order or coverage differs")
        require(len(svg.findall(f".//{ns}image")) == len(expected_images), "SVG image lost or repeated")
        target = OUTPUT / name
        poppler = render_poppler(path, target / "poppler")
        pdfium = render_pdfium(path, target / "pdfium")
        require(len(poppler) == len(pdfium) == 2, "renderer page count differs")
        metrics = [difference(a, b, 1) for a, b in zip(poppler, pdfium, strict=True)]
        require(all(m["meanAbsoluteChannelError"] < 4 and m["changedPixelFraction"] < .06 for m in metrics), "renderers disagree")
        raster_metrics = []
        with ZipFile(FIXTURES / f"{name}.zip") as archive:
            pages = sorted(archive.namelist())
            require(len(pages) == 2, "PNG page count differs")
            for index, page in enumerate(pages):
                with Image.open(io.BytesIO(archive.read(page))) as image, Image.open(poppler[index]) as reference:
                    require(image.size == (880, 960), "144 DPI raster size differs")
                    rendered = target / f"raster-{index + 1}.png"
                    image.convert("RGB").resize(reference.size, Image.Resampling.LANCZOS).save(rendered)
                raster_metrics.append(difference(rendered, poppler[index], 1))
        require(all(m["meanAbsoluteChannelError"] < 4 and m["changedPixelFraction"] < .06 for m in raster_metrics), "raster disagrees with PDF")
        contacts.extend((f"{name} {index + 1}", page) for index, page in enumerate(poppler))
        report[name] = {"pages": 2, "bodyCommands": len(body) + len(outlines), "images": len(expected_images),
                        "occupiedColumns": columns, "maxPositionErrorPt": error, "tailTopPt": tail_y,
                        "rendererDifferences": metrics, "rasterDifferences": raster_metrics}
    contact_sheet(contacts, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
