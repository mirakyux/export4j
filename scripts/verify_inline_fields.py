# SPDX-License-Identifier: Apache-2.0
"""Independent final-field values, actual marker-page notes, PDF links and all-page rendering checks."""
from __future__ import annotations

import io
import json
from pathlib import Path
import xml.etree.ElementTree as ET
from zipfile import ZipFile
from PIL import Image
from pypdf import PdfReader
from verify_advanced_pdf import difference, render_pdfium, render_poppler, require
from verify_inline_images import image_boxes
from verify_multi_floats import pdf_geometry
from verify_production import actual_text, contact_sheet

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "examples/build/inline-fields"
OUTPUT = ROOT / "build/verification/inline-fields"


def check_fields(reader):
    require(len(reader.pages) == 4, "field fixture must include contents, two body pages and a restarted section")
    expected = ["A current=2 / total=4", "B target page=7 ; number=Figure 1", "C current=3 / total=4", "D current=7 / total=4"]
    require(actual_text(reader) == expected, "final inline values lost, reordered or resolved before final page offsets")
    _, lines, _ = pdf_geometry(reader, 22)
    require([(line["page"], line["source"]) for line in lines] == list(zip([1, 1, 2, 3], expected)), "field source page differs")
    for line, y in zip(lines, [46, 68, 24, 46], strict=True):
        require(abs(line["x"] - 24) < .003 and abs(line["y"] - y) < .003, "field line moved after resolution")
    annotations = [annotation.get_object() for annotation in reader.pages[1].get("/Annots", [])]
    require(len(annotations) == 2, "inline cross-reference link count differs")
    for annotation, width in zip(annotations, [28, 70], strict=True):
        destination = annotation["/Dest"]
        require(destination[0].idnum == reader.pages[3].indirect_reference.idnum, "inline reference targets wrong physical page")
        require(abs(float(destination[2]) - 24) < .003 and abs(float(destination[3]) - 296) < .003, "inline destination point differs")
        x0, y0, x1, y1 = map(float, annotation["/Rect"])
        require(abs(x1 - x0 - width) < .003 and abs(y1 - y0 - 22) < .003, "inline link dimensions differ")
        require(abs(320 - y1 - 68) < .003 and 24 <= x0 < x1 <= 396, "inline link is outside its source line")
    require(len(reader.pages[0].get("/Annots", [])) == 1, "contents link missing")
    return {"pages": 4, "inlineLinks": 2, "contentsLinks": 1, "bodyLines": 4}


def check_notes(reader):
    require(len(reader.pages) == 3, "inline note fixture pagination differs")
    expected = ["Opening \ufffc[1] sentence", *[f"NOTE-BODY-{i:02d}" for i in range(1, 14)], "Closing [2] paragraph"]
    require(actual_text(reader) == expected, "note markers or paragraph text duplicated, lost or reordered")
    _, lines, _ = pdf_geometry(reader, 22)
    require([line["page"] for line in lines] == [0] * 5 + [1] * 5 + [2] * 5, "inline note body pages differ")
    for index, line in enumerate(lines):
        require(abs(line["x"] - 20) < .003 and abs(line["y"] - (20 + index % 5 * 22)) < .003, "inline note line geometry differs")
    footers = [page.extract_text() for page in reader.pages]
    require("[1] Early note stays with the opening line." in footers[0], "early note did not follow opening marker")
    require("Early note" not in footers[1] + footers[2], "early note incorrectly follows paragraph end")
    require("[2] Late note follows the closing line." in footers[2], "late note on wrong page")
    require("Late note" not in footers[0] + footers[1], "late note appeared before its marker")
    images = image_boxes(reader)
    require(len(images) == 1 and images[0]["page"] == 0, "inline image lost, duplicated or moved")
    require(abs(images[0]["width"] - 12) < .003 and abs(images[0]["height"] - 12) < .003, "inline image dimensions differ")
    return {"pages": 3, "bodyLines": 15, "noteMarkerPages": [0, 2], "images": 1}


def check_tables(reader):
    require(len(reader.pages) == 4, "table fields must retain contents, two split-cell pages and restarted target")
    expected = ["HEADER page=2 total=4", "TARGET=7 Figure 1",
                *[f"CELL-{i:02d} page=2" for i in range(1, 7)], "\ufffc MEDIA",
                "HEADER page=3 total=4", "TARGET=7 Figure 1",
                *[f"CELL-{i:02d} page=3" + (" LINK" if i == 12 else "") for i in range(7, 13)]]
    require(actual_text(reader) == expected, "table field values, repeated headers or split-cell source differ")
    _, lines, _ = pdf_geometry(reader, 22)
    geometry = [(1, 23, 45), (1, 198, 45), *[(1, 23, 73 + i * 22) for i in range(6)], (1, 198, 73),
                (2, 23, 23), (2, 198, 23), *[(2, 23, 51 + i * 22) for i in range(6)]]
    require(len(lines) == len(geometry), "unexpected table line count or extra wrapping")
    for line, (page, x, y) in zip(lines, geometry, strict=True):
        require(line["page"] == page and abs(line["x"] - x) < .003 and abs(line["y"] - y) < .003,
                "table line moved after final field resolution")
    for page_index, top in [(1, 45), (2, 23)]:
        annotations = [item.get_object() for item in reader.pages[page_index].get("/Annots", [])]
        internal = [item for item in annotations if "/Dest" in item]
        require(len(internal) == 2, "repeated header reference links missing")
        for item, width in zip(internal, [24, 70], strict=True):
            destination = item["/Dest"]
            require(destination[0].idnum == reader.pages[3].indirect_reference.idnum, "table reference targets wrong physical page")
            require(abs(float(destination[2]) - 20) < .003 and abs(float(destination[3]) - 220) < .003, "table reference destination point differs")
            x0, y0, x1, y1 = map(float, item["/Rect"])
            require(abs(x1 - x0 - width) < .003 and abs(y1 - y0 - 22) < .003 and abs(240 - y1 - top) < .003,
                    "table field link bounds differ")
            require(198 <= x0 < x1 <= 397, "table reference link outside owning cell")
    external = [item.get_object() for item in reader.pages[2].get("/Annots", []) if "/A" in item.get_object()]
    require(len(external) == 1 and external[0]["/A"]["/URI"] == "https://example.com/table-field", "cell external link lost after field replacement")
    x0, y0, x1, y1 = map(float, external[0]["/Rect"])
    require(x0 >= 23 + 108.398 - .003 and x1 <= 192 and abs(240 - y1 - 161) < .003 and abs(y1 - y0 - 22) < .003,
            "external link uses generated-value offsets instead of original marker geometry")
    images = image_boxes(reader)
    require(len(images) == 1 and images[0]["page"] == 1 and abs(images[0]["width"] - 14) < .003
            and abs(images[0]["height"] - 14) < .003, "cell image duplicated or lost on continuation")
    require(len(reader.pages[0].get("/Annots", [])) == 1, "table contents link missing")
    return {"pages": 4, "bodyLines": 17, "headerOccurrences": 2, "cellLines": 12, "inlineLinks": 4, "externalLinks": 1, "images": 1}


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    report, contacts = {}, []
    for name, page_count in [("fields", 4), ("notes", 3), ("bidi", 1), ("tables", 4)]:
        path = FIXTURES / f"{name}.pdf"
        reader = PdfReader(path)
        require(len(reader.pages) == page_count, "fixture page count differs")
        if name == "fields":
            result = check_fields(reader)
        elif name == "notes":
            result = check_notes(reader)
        elif name == "tables":
            result = check_tables(reader)
        else:
            require(actual_text(reader) == ["中文 العربية 1 a\u0301 / 1 fin"], "bidi/combining source or field values differ")
            result = {"pages": 1, "bodyLines": 1}
        svg = ET.parse(FIXTURES / f"{name}.svg").getroot()
        ns = "{http://www.w3.org/2000/svg}"
        logical = [g.get("aria-label") for g in svg.iter(f"{ns}g")]
        require([v for v in logical if v in set(actual_text(reader))] == actual_text(reader), "SVG resolved logical text differs")
        require(len(svg.findall(f"{ns}svg")) == page_count, "SVG page count differs")
        target = OUTPUT / name
        poppler = render_poppler(path, target / "poppler")
        pdfium = render_pdfium(path, target / "pdfium")
        require(len(poppler) == len(pdfium) == page_count, "renderer page count differs")
        metrics = [difference(a, b, 1) for a, b in zip(poppler, pdfium, strict=True)]
        require(all(m["meanAbsoluteChannelError"] < 4 and m["changedPixelFraction"] < .06 for m in metrics), "independent PDF renderers disagree")
        raster_metrics = []
        with ZipFile(FIXTURES / f"{name}.zip") as archive:
            entries = sorted(archive.namelist())
            require(len(entries) == page_count, "PNG ZIP page count differs")
            for index, entry in enumerate(entries):
                with Image.open(io.BytesIO(archive.read(entry))) as image, Image.open(poppler[index]) as reference:
                    rendered = target / f"raster-{index + 1}.png"
                    require(image.size == tuple(round(float(v) * 2) for v in (reader.pages[index].mediabox.width, reader.pages[index].mediabox.height)), "144 DPI raster dimensions differ")
                    image.convert("RGB").resize(reference.size, Image.Resampling.LANCZOS).save(rendered)
                raster_metrics.append(difference(rendered, poppler[index], 1))
        require(all(m["meanAbsoluteChannelError"] < 4 and m["changedPixelFraction"] < .06 for m in raster_metrics), "raster output disagrees with PDF")
        result.update(rendererDifferences=metrics, rasterDifferences=raster_metrics)
        report[name] = result
        contacts.extend((f"{name} {index + 1}", page) for index, page in enumerate(poppler))
    contact_sheet(contacts, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
