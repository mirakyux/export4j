# SPDX-License-Identifier: Apache-2.0
"""Independent final-PDF source/column geometry and all-page PDF/SVG/PNG checks; no baseline updates."""
from __future__ import annotations

import io
import json
from pathlib import Path
import struct
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from PIL import Image
from pypdf import PdfReader
from verify_advanced_pdf import difference, render_pdfium, render_poppler, require
from verify_multi_floats import pdf_geometry, point
from verify_production import contact_sheet

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "examples/build/continuous-columns"
OUTPUT = ROOT / "build/verification/continuous-columns"
TOLERANCE = .003
# Explicit fixture expectations, independent of the Java slicing algorithm: (page, column, rows).
ASSIGNMENTS = {
    "partial": [(0, 0, 14), (0, 1, 14), (1, 0, 14)],
    "short": [(0, 0, 6)],
    "moved": [(1, 0, 5)],
    "three": [(0, 0, 5), (0, 1, 5), (0, 2, 5), (1, 0, 9)],
    "shaped": [(0, 0, 14), (0, 1, 14), (1, 0, 8)],
}


def font_ascent(path):
    """Read the explicit fixture font's hhea/head values without Java or an extra dependency."""
    data = path.read_bytes()
    count = struct.unpack_from(">H", data, 4)[0]
    tables = {}
    for index in range(count):
        tag, _, offset, _ = struct.unpack_from(">4sIII", data, 12 + index * 16)
        tables[tag] = offset
    return max(0, struct.unpack_from(">h", data, tables[b"hhea"] + 4)[0]) / struct.unpack_from(">H", data, tables[b"head"] + 18)[0]


def glyph_lines(reader, ascent):
    result = []
    for page_index, page in enumerate(reader.pages):
        position = None
        def operand(operator, operands, cm, tm):
            nonlocal position
            if operator in (b"TJ", b"Tj"):
                # pypdf may flush right-cell text after resetting Tm while synthesizing a space.
                # Capture the actual text-show matrix at the operator, before that extraction step.
                position = point(cm, tm[4], tm[5])
        def visitor(text, cm, tm, font, size):
            if text.strip():
                require(position is not None, "glyph text has no text-show coordinates")
                x, y = position
                result.append({"page": page_index, "source": text.strip(), "x": x,
                               "y": float(page.mediabox.height) - y - ascent * size})
        page.extract_text(visitor_text=visitor, visitor_operand_before=operand)
    return result


def check_geometry(reader, fixture, config):
    name = fixture["name"]
    source = (FIXTURES / f"{name}.txt").read_text(encoding="utf-8").splitlines()
    assignments = ASSIGNMENTS[name]
    margin, step = config["margin"], config["lineHeight"]
    width = (config["pageWidth"] - 2 * margin - fixture["gap"] * (fixture["columns"] - 1)) / fixture["columns"]
    ascent = max(font_ascent(ROOT / "assets/fonts" / font) for font in (
        "LiberationSans-Regular.ttf", "NotoNaskhArabic-Regular.ttf", "LXGWWenKaiTC-Regular.ttf"))
    glyphs = glyph_lines(reader, ascent)
    _, outlines, clips = pdf_geometry(reader, step)
    lines = outlines if fixture["shaped"] else [line for line in glyphs if line["source"] in set(source)]
    require([line["source"] for line in lines] == source, f"{name}: original lines lost, duplicated or reordered")
    require(len(reader.pages) == fixture["pages"] == assignments[-1][0] + 1, f"{name}: incorrect page count")
    require(clips == len(assignments), f"{name}: occupied column clipping differs")
    expected = [(page, column, row) for page, column, rows in assignments for row in range(rows)]
    require(len(lines) == len(expected), f"{name}: unexpected wrapping or blank rows")
    max_error = 0.0
    for line, (page, column, row) in zip(lines, expected, strict=True):
        x = margin + column * (width + fixture["gap"])
        y = margin + (fixture["prefixHeight"] if page == 0 else 0) + row * step
        require(line["page"] == page, f"{name}: body on wrong page")
        error = max(abs(line["x"] - x), abs(line["y"] - y))
        max_error = max(max_error, error)
        require(error < TOLERANCE, f"{name}: column/row position differs: {line} vs {(x, y)}")
        require(y + step <= config["pageHeight"] - margin + TOLERANCE, f"{name}: line leaves body viewport")
        if fixture["shaped"]:
            require(line["width"] <= width + TOLERANCE, f"{name}: outline leaves column width")
    tails = [line for line in glyphs if line["source"] == f"TAIL / {name}"]
    require(len(tails) == 1, f"{name}: following block lost or repeated")
    last_page = assignments[-1][0]
    last_rows = max(rows for page, _, rows in assignments if page == last_page)
    tail_y = margin + (fixture["prefixHeight"] if last_page == 0 else 0) + last_rows * step
    require(tails[0]["page"] == last_page and abs(tails[0]["y"] - tail_y) < TOLERANCE,
            f"{name}: following body did not start at actual last-column height: {tails[0]}")
    require(abs(tails[0]["x"] - margin) < TOLERANCE, f"{name}: following body did not restore full width")
    return {"bodyLines": len(lines), "occupiedColumns": len(assignments), "maxPositionErrorPt": max_error,
            "assignments": assignments, "tailTopPt": tail_y}


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    config = json.loads((FIXTURES / "geometry.json").read_text(encoding="utf-8"))
    report, contacts = {}, []
    for fixture in config["fixtures"]:
        name = fixture["name"]
        path = FIXTURES / f"{name}.pdf"
        reader = PdfReader(path)
        require(all(abs(float(p.mediabox.width) - config["pageWidth"]) < .001
                    and abs(float(p.mediabox.height) - config["pageHeight"]) < .001 for p in reader.pages), "page settings differ")
        geometry = check_geometry(reader, fixture, config)
        svg = ET.parse(FIXTURES / f"{name}.svg").getroot()
        ns = "{http://www.w3.org/2000/svg}"
        require(len(svg.findall(f"{ns}svg")) == len(reader.pages), f"{name}: SVG pages differ")
        source = (FIXTURES / f"{name}.txt").read_text(encoding="utf-8").splitlines()
        # Both glyph and explicitly shaped commands become labeled outlines in the static backend.
        candidates = [g.get("aria-label") for g in svg.iter(f"{ns}g")]
        require([text for text in candidates if text in set(source)] == source, f"{name}: SVG source order differs")
        target = OUTPUT / name
        poppler = render_poppler(path, target / "poppler")
        pdfium = render_pdfium(path, target / "pdfium")
        require(len(poppler) == len(pdfium) == len(reader.pages), "independent renderer page counts differ")
        metrics = [difference(a, b, 1) for a, b in zip(poppler, pdfium, strict=True)]
        require(all(m["meanAbsoluteChannelError"] < 4 and m["changedPixelFraction"] < .06 for m in metrics), "renderers disagree")
        raster_metrics = []
        with ZipFile(FIXTURES / f"{name}.zip") as archive:
            pages = sorted(archive.namelist())
            require(len(pages) == len(reader.pages), "PNG page count differs")
            for index, page in enumerate(pages):
                with Image.open(io.BytesIO(archive.read(page))) as image, Image.open(poppler[index]) as reference:
                    require(image.size == (880, 960), "144 DPI raster size differs")
                    rendered = target / f"raster-{index + 1}.png"
                    image.convert("RGB").resize(reference.size, Image.Resampling.LANCZOS).save(rendered)
                raster_metrics.append(difference(rendered, poppler[index], 1))
        require(all(m["meanAbsoluteChannelError"] < 4 and m["changedPixelFraction"] < .06 for m in raster_metrics), "raster disagrees with PDF")
        contacts.extend((f"{name} {index + 1}", page) for index, page in enumerate(poppler))
        report[name] = {"pages": len(reader.pages), **geometry, "rendererDifferences": metrics, "rasterDifferences": raster_metrics}
    contact_sheet(contacts, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
