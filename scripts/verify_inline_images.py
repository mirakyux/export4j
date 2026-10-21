# SPDX-License-Identifier: Apache-2.0
"""Independent PNG/JPEG inline geometry, OOXML media, source order and rendered output checks."""
from __future__ import annotations

import io
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from zipfile import ZipFile
from docx import Document
from PIL import Image
from pypdf import PdfReader
from verify_advanced_pdf import require, render_poppler, render_pdfium, difference
from verify_production import actual_text, contact_sheet

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "examples/build/inline-images"
OUTPUT = ROOT / "build/verification/inline-images"


def compose(a, b):
    return (a[0]*b[0]+a[2]*b[1], a[1]*b[0]+a[3]*b[1],
            a[0]*b[2]+a[2]*b[3], a[1]*b[2]+a[3]*b[3],
            a[0]*b[4]+a[2]*b[5]+a[4], a[1]*b[4]+a[3]*b[5]+a[5])


def image_boxes(reader):
    result = []
    for index, page in enumerate(reader.pages):
        matrix = (1, 0, 0, 1, 0, 0)
        stack = []
        for operands, operator in page.get_contents().operations:
            if operator == b"q":
                stack.append(matrix)
            elif operator == b"Q":
                matrix = stack.pop()
            elif operator == b"cm":
                matrix = compose(matrix, tuple(float(n) for n in operands))
            elif operator == b"Do":
                resource = page["/Resources"]["/XObject"][operands[0]].get_object()
                require(resource["/Subtype"] == "/Image", "unexpected non-image XObject")
                a, b, c, d, e, f = matrix
                xs, ys = (e, a+e, c+e, a+c+e), (f, b+f, d+f, b+d+f)
                result.append({"page": index, "x": min(xs), "y": float(page.mediabox.height)-max(ys),
                               "width": max(xs)-min(xs), "height": max(ys)-min(ys),
                               "pixels": [resource["/Width"], resource["/Height"]], "alpha": "/SMask" in resource})
    return result


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    expected = json.loads((FIXTURES / "geometry.json").read_text(encoding="utf-8"))
    report = {}
    contacts = []
    rendered = {}
    for name in ("mixed", "roundtrip"):
        path = FIXTURES / f"{name}.pdf"
        reader = PdfReader(path)
        require(len(reader.pages) == expected["pages"] >= 3, "pagination differs")
        images = image_boxes(reader)
        require(len(images) == len(expected["images"]), "image lost or duplicated across pages")
        for actual, wanted in zip(images, expected["images"]):
            require(actual["page"] == wanted["page"] and actual["pixels"] == wanted["pixels"], "image page or decoded dimensions differ")
            for field in ("x", "y", "width", "height"):
                require(abs(actual[field]-wanted[field]) < .002, f"image {field} changed during rendering")
        require(any(image["alpha"] for image in images), "PNG transparency lost")
        logical = "\n".join(actual_text(reader))
        require(logical.count("\ufffc") == len(images), "logical image marker count differs")
        for prefix, count in (("FLOW", 18), ("CELL", 12)):
            require(re.findall(rf"{prefix}-(\d{{2}})", logical) == [f"{i:02d}" for i in range(1, count+1)], "body source lost, duplicated or reordered")
        require("العربية" in logical and "a\u0301" in logical, "bidi or combining source changed")
        links = [(index, ref.get_object()) for index, page in enumerate(reader.pages) for ref in page.get("/Annots", [])]
        require(len(links) == expected["links"], "image hyperlink count differs")
        for page, link in links:
            require(link["/A"]["/URI"] == "https://example.com/inline-image", "image URI changed")
            left, bottom, right, top = (float(n) for n in link["/Rect"])
            require(any(image["page"] == page and abs(image["x"]-left) < .002
                        and abs(image["y"]-(480-top)) < .002 and abs(image["width"]-(right-left)) < .002
                        and abs(image["height"]-(top-bottom)) < .002 for image in images), "image hyperlink misses image bounds")
        poppler = render_poppler(path, OUTPUT / name / "poppler")
        pdfium = render_pdfium(path, OUTPUT / name / "pdfium")
        require(len(poppler) == len(pdfium) == expected["pages"], "independent page counts differ")
        metrics = [difference(a, b, 1) for a, b in zip(poppler, pdfium)]
        require(all(m["meanAbsoluteChannelError"] < 4 and m["changedPixelFraction"] < .06 for m in metrics), "independent renderers disagree")
        rendered[name] = poppler
        contacts.extend((f"{name} page {i+1}", p) for i, p in enumerate(poppler))
        (OUTPUT / f"{name}-actualtext.txt").write_text(logical, encoding="utf-8")
        report[name] = {"pages": len(reader.pages), "images": len(images), "links": len(links), "renderer_differences": metrics}
    require(all(difference(a, b)["meanAbsoluteChannelError"] == 0 for a, b in zip(rendered["mixed"], rendered["roundtrip"])), "DOCX roundtrip changed visible output")
    word = Document(FIXTURES / "mixed.docx")
    require(len(word.inline_shapes) == 36 and len(word.tables) == 1, "python-docx missed standard inline shapes or table")
    with ZipFile(FIXTURES / "mixed.docx") as archive:
        media = [p for p in archive.namelist() if p.startswith("word/media/")]
        require(len(media) == 2, "PNG/JPEG media are not deduplicated")
        require({Image.open(io.BytesIO(archive.read(p))).format for p in media} == {"PNG", "JPEG"}, "encoded resources changed")
        main = ET.fromstring(archive.read("word/document.xml"))
        wp = "{http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing}"
        properties = main.findall(f".//{wp}docPr")
        require(len(properties) == 36 and all(p.get("descr") for p in properties), "alternative descriptions lost")
        require(len({p.get("id") for p in properties}) == 36, "duplicate drawing IDs")
        a = "{http://schemas.openxmlformats.org/drawingml/2006/main}"
        r = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}"
        picture_links = main.findall(f".//{wp}docPr/{a}hlinkClick")
        relationships = ET.fromstring(archive.read("word/_rels/document.xml.rels"))
        targets = {element.get("Id"): element.get("Target") for element in relationships}
        require(len(picture_links) == 19 and all(targets[link.get(r+"id")] == "https://example.com/inline-image" for link in picture_links), "standard image hyperlink relationships lost")
    svg = ET.parse(FIXTURES / "preview.svg").getroot()
    require(len(svg.findall(".//{http://www.w3.org/2000/svg}image")) == len(expected["images"]), "static SVG lost images")
    with ZipFile(FIXTURES / "preview.zip") as archive:
        pages = sorted(archive.namelist())
        require(len(pages) == expected["pages"], "static PNG page count differs")
        raster_metrics = []
        for i, name in enumerate(pages):
            with Image.open(io.BytesIO(archive.read(name))) as source, Image.open(rendered["mixed"][i]) as reference:
                require(source.size == (880, 960), "explicit raster DPI changed")
                resized = source.convert("RGB").resize(reference.size, Image.Resampling.LANCZOS)
                target = OUTPUT / f"raster-{i+1}.png"
                resized.save(target)
            raster_metrics.append(difference(target, rendered["mixed"][i], 1))
        require(all(m["meanAbsoluteChannelError"] < 4 for m in raster_metrics), "static raster differs from PDF")
    report["docx"] = {"inline_shapes": 36, "media": 2, "descriptions": 36, "picture_links": 19, "roundtrip_pixel_difference": 0}
    report["static"] = {"svg_images": len(expected["images"]), "png_pages": expected["pages"], "renderer_differences": raster_metrics}
    contact_sheet(contacts, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
