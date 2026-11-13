# SPDX-License-Identifier: Apache-2.0
"""Inspect public math outputs independently, render every PDF page and compare SVG/PNG pixels."""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
from importlib import metadata
import json
from pathlib import Path
import subprocess
from zipfile import ZipFile
import xml.etree.ElementTree as ET

from PIL import Image
from pypdf import PdfReader
from pypdf.generic import ContentStream
import pypdfium2 as pdfium
import resvg_py

from verify_advanced_pdf import difference, require
from verify_production import actual_text, contact_sheet

ROOT = Path(__file__).resolve().parents[1]
TEX = (r"x_1^2 + x_2^2 = r^2", r"x = \frac{-b \pm \sqrt{b^2-4ac}}{2a}",
       r"\frac{a+b}{c}", r"\sqrt[3]{x}", "x^2", "x^2", r"\alpha + \beta = \gamma")
CARRIERS = ("Inline equation: " + TEX[0] + " stays with surrounding text.", TEX[1], TEX[2], TEX[3],
            "Repeated occurrences: x^2 and x^2 have separate anchors.",
            "显式中文正文与数学公式 " + TEX[6] + " 混排。")
NS = {"w": "http://schemas.openxmlformats.org/wordprocessingml/2006/main",
      "m": "http://schemas.openxmlformats.org/officeDocument/2006/math"}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def check_docx(path, expected):
    with ZipFile(path) as archive:
        root = ET.fromstring(archive.read("word/document.xml"))
        equations = root.findall(".//m:oMath", NS)
        require(len(equations) == expected, "missing or duplicate editable native equations")
        require(not root.findall(".//w:drawing", NS), "equations unexpectedly became raster images")
        text = "".join(root.itertext())
        require("equation-slot" not in text and "export4j-formula:" not in text,
                "a temporary native binding slot leaked into DOCX")
        require(not root.findall(".//w:t[.='\ufffc']", NS), "unbound object marker leaked into native DOCX")
        require(root.findall(".//m:f", NS) and root.findall(".//m:rad", NS)
                and (root.findall(".//m:sSup", NS) or root.findall(".//m:sSubSup", NS)),
                "fraction/root/script OMML structures are missing")
        require(all(not member.startswith("word/media/") for member in archive.namelist()),
                "native equation DOCX embeds unexpected media")
        return {"equations": len(equations), "fractions": len(root.findall(".//m:f", NS)),
                "radicals": len(root.findall(".//m:rad", NS)), "media": 0}


def render_pdf(path, output):
    poppler = output / "poppler"
    poppler.mkdir(parents=True, exist_ok=True)
    subprocess.run(["pdftoppm", "-png", "-r", "144", str(path), str(poppler / "page")],
                   check=True, capture_output=True, timeout=120)
    first = sorted(poppler.glob("page-*.png"))
    second = []
    with pdfium.PdfDocument(path) as document:
        for index in range(len(document)):
            page = document[index]
            try:
                bitmap = page.render(scale=2)
                try:
                    target = output / f"pdfium-{index + 1}.png"
                    with bitmap.to_pil() as image:
                        image.convert("RGB").save(target)
                    second.append(target)
                finally:
                    bitmap.close()
            finally:
                page.close()
    require(len(first) == len(second) == 1, "expected one completely rendered formula specimen page")
    return first[0], second[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-directory", type=Path, default=ROOT / "examples/build/math")
    parser.add_argument("--consumer-build-directory", type=Path)
    parser.add_argument("--output-directory", type=Path, default=ROOT / "build/verification/math")
    args = parser.parse_args()
    source, output = args.source_directory.resolve(), args.output_directory.resolve()
    output.mkdir(parents=True, exist_ok=True)
    versions = {name: metadata.version(name) for name in ("pypdf", "pypdfium2", "Pillow", "resvg-py")}
    require(versions == {"pypdf": "6.10.0", "pypdfium2": "5.13.0", "Pillow": "12.3.0", "resvg-py": "0.5.0"},
            "use the pinned project verification environment")
    pdf = source / "formulas.pdf"
    with pdf.open("rb") as stream:
        reader = PdfReader(stream, strict=True)
        require(len(reader.pages) == 1, "public math specimen page count changed")
        carriers = actual_text(reader)
        # ActualText belongs to complete logical lines, including ordinary text around atomic formulas.
        require(tuple(value.strip() for value in carriers) == CARRIERS, "TeX ActualText lines or reading order changed")
        operations = Counter()
        for page in reader.pages:
            for reference in page["/Resources"].get("/XObject", {}).values():
                require(reference.get_object().get("/Subtype") != "/Image", "PDF mathematics rasterized")
            operations.update(operator.decode("ascii") for _, operator in ContentStream(page.get_contents(), reader).operations)
        require(operations["c"] > 100 and operations["f"] >= 7, "native curved formula outlines are missing")
        ordinary = reader.pages[0].extract_text()
        require(all(text in ordinary for text in ("Export4j optional mathematics", "Kind", "Fraction", "Root")),
                "ordinary native text is missing")
    with pdfium.PdfDocument(pdf) as document:
        page = document[0]
        try:
            textpage = page.get_textpage()
            try:
                extracted = textpage.get_text_range()
                require(all(tex in extracted for tex in TEX) and extracted.count("x^2") == 2
                        and "Repeated occurrences" in extracted and "显式中文正文与数学公式" in extracted,
                        "PDFium logical extraction lost formula/source text")
            finally:
                textpage.close()
        finally:
            page.close()
    svg = ET.parse(source / "formulas.svg").getroot()
    labels = [element.get("aria-label") for element in svg.iter() if "aria-label" in element.attrib]
    require(tuple(value for value in labels if any(tex in value for tex in TEX)) == CARRIERS,
            "SVG formula reading lines differ from their original order")
    require(not svg.findall(".//{http://www.w3.org/2000/svg}image"), "SVG formulas rasterized")
    native = check_docx(source / "formulas.docx", 7)
    poppler, second = render_pdf(pdf, output)
    with Image.open(second) as image:
        width, height = image.size
    svg_png = output / "resvg.png"
    svg_png.write_bytes(resvg_py.svg_to_bytes(svg_path=str(source / "formulas.svg"), width=width,
                                           height=height, dpi=96, background="#ffffff", skip_system_fonts=True))
    comparisons = {}
    for name, left in (("popplerPdfium", poppler), ("pngPdfium", source / "formulas.png"), ("resvgPdfium", svg_png)):
        value = difference(left, second, 1)
        require(value["meanAbsoluteChannelError"] < 4 and value["changedPixelFraction"] < .06,
                f"independent formula rendering changed: {name}: {value}")
        comparisons[name] = value
    contact_sheet([("Poppler", poppler), ("PDFium", second), ("RasterBackend", source / "formulas.png"),
                   ("resvg", svg_png)], output)
    consumer = None
    if args.consumer_build_directory:
        consumer_source = args.consumer_build_directory.resolve() / "output/math"
        consumer = check_docx(consumer_source / "formulas.docx", 4)
        consumer_output = output / "consumer"
        consumer_output.mkdir(exist_ok=True)
        render_pdf(consumer_source / "formulas.pdf", consumer_output)
        consumer["artifacts"] = {path.name: digest(path) for path in consumer_source.iterdir() if path.is_file()}
    report = {"checks": "passed", "pages": 1, "formulaOccurrences": 7, "nativeOmml": native,
              "formulaImageObjects": 0, "rendering": comparisons, "consumer": consumer,
              "tools": versions, "artifacts": {name: digest(source / name) for name in
              ("formulas.pdf", "formulas.svg", "formulas.png", "formulas.docx", "source.md")},
              "visualReview": "independent renders created; visual inspection recorded separately"}
    (output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
