# SPDX-License-Identifier: Apache-2.0
"""Independently inspect downloads produced by frontend/tests/studio.test.mjs.

Run after :export4j-designer:installDist and the frontend browser test, using the
pinned verification environment and Poppler. All outputs stay under build/.
"""
import argparse
import io
import json
from pathlib import Path
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from PIL import Image
from pypdf import PdfReader
import resvg_py
from verify_advanced_pdf import difference, require
from verify_pdf_raster import references
from verify_production import contact_sheet
from verify_style_defaults import painted_spans

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SOURCE = ROOT / "build/verification/designer-studio/browser"
DEFAULT_OUTPUT = ROOT / "build/verification/designer-studio/independent"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-directory", type=Path, default=DEFAULT_SOURCE,
                        help="Browser test downloads from this run")
    parser.add_argument("--output-directory", type=Path, default=DEFAULT_OUTPUT,
                        help="Independent rendering and report directory")
    arguments = parser.parse_args()
    SOURCE, OUTPUT = arguments.source_directory.resolve(), arguments.output_directory.resolve()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    reader = PdfReader(SOURCE / "fixed.pdf", strict=True)
    require(len(reader.pages) == 2, "physical target page was lost")
    texts = [page.extract_text() for page in reader.pages]
    require(all(token in texts[0] for token in ("Hello", "mixed", "Ada", "Cell A", "Cell B")), "flow content lost")
    require("Second paragraph" not in texts[0] and texts[1].count("Second paragraph") == 1, "fixed content duplicated or on wrong page")
    require(all(text.count("Header") == 1 and text.count("Footer") == 1 for text in texts), "page bands lost or repeated")
    placed = [span for span in painted_spans(reader) if span["text"] == "Second paragraph"]
    require(len(placed) == 1 and placed[0]["page"] == 1 and placed[0]["fontSize"] == 12, "conversion changed body font size")
    require(abs(placed[0]["x"] - 30) < .01 and 78 < placed[0]["baselineY"] < 96, "fixed paint geometry differs")
    hits = json.loads((SOURCE / "fixed-hits.json").read_text())
    require(len(hits) == 1 and hits[0]["index"] == 1 and hits[0]["width"] == 390 and hits[0]["height"] == 18, "adaptive frame geometry differs")
    poppler, pdfium = references(SOURCE / "fixed.pdf", OUTPUT)
    tiles, metrics = [], []
    with ZipFile(SOURCE / "fixed.png.zip") as rasters, ZipFile(SOURCE / "fixed.svg.zip") as vectors:
        require(len(rasters.namelist()) == len(vectors.namelist()) == 2, "export page count differs")
        for index, (png_name, svg_name) in enumerate(zip(sorted(rasters.namelist()), sorted(vectors.namelist()), strict=True)):
            require("/" not in png_name + svg_name and ".." not in png_name + svg_name, "unsafe page filename")
            with Image.open(io.BytesIO(rasters.read(png_name))) as image:
                require(image.size == (560, 800), "96-DPI export extent differs")
                raster = OUTPUT / f"raster-{index + 1}.png"
                image.convert("RGB").resize((840, 1200), Image.Resampling.LANCZOS).save(raster)
            tree = ET.fromstring(vectors.read(svg_name))
            require(tuple(map(float, tree.get("viewBox", "").split())) == (0, 0, 420, 600), "SVG physical extent differs")
            # resvg-py expects pixel extents when explicit output dimensions are supplied.
            tree.set("width", "840"); tree.set("height", "1200")
            vector = OUTPUT / f"resvg-{index + 1}.png"
            vector.write_bytes(resvg_py.svg_to_bytes(svg_string=ET.tostring(tree, encoding="unicode"),
                              width=840, height=1200, background="#ffffff", skip_system_fonts=True))
            values = {name: difference(pdfium[index], other, 1) for name, other in
                      (("poppler", poppler[index]), ("raster", raster), ("resvg", vector))}
            require(all(value["meanAbsoluteChannelError"] < 4 and value["changedPixelFraction"] < .06
                        for value in values.values()), "independent painted output differs")
            metrics.append(values)
            tiles.extend([(f"Page {index + 1} {name}", file) for name, file in
                          (("Poppler", poppler[index]), ("PDFium", pdfium[index]), ("resvg", vector), ("PNG", raster))])
    obstacle = json.loads((SOURCE / "avoidance-hits.json").read_text())
    avoided = PdfReader(SOURCE / "avoidance.pdf", strict=True)
    require(len(avoided.pages) == 1, "avoidance fixture pagination differs")
    avoidance_text = avoided.pages[0].extract_text()
    require(all(avoidance_text.count(token) == 1 for token in ("Second paragraph", "Cell A", "Cell B")),
            "avoidance duplicated or removed source content")
    avoidance_spans = painted_spans(avoided)
    fixed = [span for span in avoidance_spans if span["text"] == "Second paragraph"]
    cells = [span for span in avoidance_spans if span["text"].strip() in ("Cell A", "Cell B")]
    require(len(fixed) == 1 and len(cells) == 2, "avoidance painted text missing")
    require(abs(fixed[0]["x"] - obstacle["x"]) < .01
            and obstacle["y"] <= fixed[0]["baselineY"] <= obstacle["y"] + obstacle["height"],
            "avoidance changed fixed component coordinates")
    require(all(span["baselineY"] - span["fontSize"] >= obstacle["y"] + obstacle["height"] - .01
                for span in cells), "PDF body text still intersects avoided component")
    avoided_poppler, avoided_pdfium = references(SOURCE / "avoidance.pdf", OUTPUT / "avoidance")
    avoidance_metrics = difference(avoided_poppler[0], avoided_pdfium[0], 1)
    require(avoidance_metrics["meanAbsoluteChannelError"] < 4
            and avoidance_metrics["changedPixelFraction"] < .06, "avoidance independent rendering differs")
    tiles.extend([("Avoidance Poppler", avoided_poppler[0]), ("Avoidance PDFium", avoided_pdfium[0])])
    with ZipFile(SOURCE / "continuous-template.zip") as bundle:
        source = bundle.read("template.xml").decode("utf-8")
        tree = ET.fromstring(source)
        paragraphs = tree.findall("p")
        require(len(paragraphs) == 1 and paragraphs[0].get("id") == "alpha",
                "continuous paragraph join was not retained in the saved template")
        require(source.count("${name}") == 1 and len(list(tree.iter("page-number"))) == 1,
                "continuous editing lost or duplicated a variable or page field")
        require(all(word in "".join(paragraphs[0].itertext()) for word in ("Alpha", "Beta", "strong")),
                "continuous editing removed paragraph text")
        require(any(span.get("role") == "bold" for span in tree.iter("span"))
                and any(span.get("underline") == "true" for span in tree.iter("span")),
                "continuous editing lost existing or selected text formatting")
    copied = PdfReader(SOURCE / "dynamic-copy.pdf", strict=True)
    copied_text = "\n".join(page.extract_text() for page in copied.pages)
    require(all(copied_text.count(text) == 2 for text in ("Record Alpha", "Record Beta")),
            "copied bound loop did not render both records exactly twice")
    links = [annotation.get_object() for page in copied.pages for annotation in page.get("/Annots", [])
             if annotation.get_object().get("/Subtype") == "/Link"]
    require(len(links) == 4, "copied loop page references are missing")
    destinations = []
    for link in links:
        destination = link.get("/Dest")
        if destination is None and link.get("/A"):
            destination = link["/A"].get_object().get("/D")
        require(destination is not None, "copied loop link lacks an internal destination")
        if isinstance(destination, str):
            require(destination in copied.named_destinations, "copied loop named destination is missing")
            destination = copied.named_destinations[destination].dest_array
        destinations.append(str(destination))
    require(len(set(destinations)) == 4, "copied references still point to the original loop instances")
    copied_poppler, copied_pdfium = references(SOURCE / "dynamic-copy.pdf", OUTPUT / "dynamic-copy")
    copied_metrics = [difference(first, second, 1) for first, second in zip(copied_poppler, copied_pdfium, strict=True)]
    require(all(value["meanAbsoluteChannelError"] < 4 and value["changedPixelFraction"] < .06
                for value in copied_metrics), "copied loop independent rendering differs")
    for index, (first, second) in enumerate(zip(copied_poppler, copied_pdfium, strict=True)):
        tiles.extend([(f"Copied loop {index + 1} Poppler", first), (f"Copied loop {index + 1} PDFium", second)])
    with ZipFile(SOURCE / "resource-lock-template.zip") as bundle:
        source = bundle.read("template.xml").decode("utf-8")
        tree = ET.fromstring(source)
        paragraph = next(node for node in tree.iter("p") if node.get("id") == "second")
        require("".join(paragraph.itertext()) == "Second paragraph resource draft resumed"
                and "blocked input" not in source and "${name}" in source,
                "resource upload lost the original draft or retained blocked text")
        resources = [name for name in bundle.namelist() if "studio_lock" in name]
        require(len(resources) == 1, "uploaded resource is missing from the saved bundle")
        with Image.open(io.BytesIO(bundle.read(resources[0]))) as pixels:
            require(pixels.size == (8, 6) and pixels.convert("RGB").getextrema()
                    == ((68, 68), (170, 170), (136, 136)), "uploaded resource pixels changed")
    with ZipFile(SOURCE / "edited-template.zip") as bundle:
        names = bundle.namelist()
        source = bundle.read("template.xml").decode("utf-8")
        require("${name}" in source and "Cell A" in source, "template expressions or table source lost")
        tree = ET.fromstring(source)
        image = next(node for node in tree.iter("image") if node.get("ref", "").startswith("pasted_"))
        resources = [name for name in names if image.get("ref") in name]
        require(len(resources) == 1 and float(image.get("width")) > 60, "resized clipboard image resource lost")
        with Image.open(io.BytesIO(bundle.read(resources[0]))) as pixels:
            require(pixels.size == (80, 40) and pixels.convert("RGB").getpixel((40, 20)) == (192, 68, 51), "clipboard pixels changed")
    contact_sheet(tiles, OUTPUT)
    report = {"checks": "passed", "pages": 2, "fixedFontSize": 12, "rendering": metrics,
              "templateExpressions": "retained", "clipboardResource": "retained",
              "continuousEditing": "saved source and inline objects retained",
              "resourceUpload": "draft, resumed input and uploaded pixels retained",
              "boundLoopCopy": {"checks": "passed", "internalReferences": len(links), "rendering": copied_metrics},
              "fixedBodyAvoidance": {"checks": "passed", "rendering": avoidance_metrics}}
    (OUTPUT / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
