# SPDX-License-Identifier: Apache-2.0
"""Check fixed XML overlays with independent PDF/OOXML/SVG and visible occlusion."""
import io
import json
from pathlib import Path
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from PIL import Image
from pypdf import PdfReader
from verify_advanced_pdf import difference, require
from verify_inline_images import image_boxes
from verify_pdf_raster import references
from verify_production import actual_text, contact_sheet
from verify_streaming_rich_text import outlined_paths, painted_spans, physical_fonts, same_color

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "examples/build/page-overlays"
OUTPUT = ROOT / "build/verification/page-overlays"
SVG = "{http://www.w3.org/2000/svg}"
W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
BLUE, GREEN, PURPLE, WHITE = (34, 68, 136), (68, 136, 34), (136, 34, 68), (255, 255, 255)
BODIES = ["BODY-ONE", "BODY-TWO", "BODY-THREE", "BODY-FOUR"]


def visible(path, page):
    with Image.open(path) as image:
        require(image.size == (720, 520), "overlay physical page dimensions")
        pixels = image.convert("RGB")
        expected = (PURPLE if page < 3 else GREEN, GREEN, BLUE if page != 2 else WHITE)
        for x, wanted in zip((30, 80, 120), expected, strict=True):
            actual = pixels.getpixel((x * 2, 52 * 2))
            require(max(abs(a-b) for a, b in zip(actual, wanted, strict=True)) <= 1,
                    "background/body/foreground visible occlusion differs")


def structures():
    reader = PdfReader(SOURCE / "overlays.pdf", strict=True)
    require(len(reader.pages) == 4 and not any(page.get("/Annots") for page in reader.pages), "overlay page/interaction contract")
    fonts = physical_fonts(reader, {"LiberationSans", "LiberationSans-Bold"})
    spans = painted_spans(reader)
    for index, body in enumerate(BODIES):
        page = [span for span in spans if span["page"] == index]
        require([span["text"] for span in page if span["text"].startswith("BODY-")] == [body], "body source lost or duplicated")
        at = next(span for span in page if span["text"] == body)
        require(at["x"] == 20 and 72 <= at["baseline"] <= 104 and at["size"] == 11
                and same_color(at["color"], (0, 0, 0)) and "LiberationSans-Bold" not in at["font"], "body geometry or explicit style changed")
        background = [span for span in page if span["text"] == "BACKGROUND-BOUND"]
        require(len(background) == (0 if index == 2 else 1), "inherited/cleared background differs")
        table = [span for span in page if span["text"] == "OVERLAY-TABLE"]
        require(len(table) == (0 if index == 3 else 1), "inherited/cleared foreground table differs")
        for span in background + table:
            require("LiberationSans-Bold" in span["font"] and same_color(span["color"], tuple(value/255 for value in BLUE)), "overlay physical theme differs")
        require(all(span["x"] == 20 and 110 <= span["baseline"] <= 142 for span in background), "background placement differs")
        require(all(span["x"] == 22 and 145 <= span["baseline"] <= 177 for span in table), "foreground table padding differs")
        require(sum(span["text"] == "EVEN-HEADER" for span in page) == (1 if index % 2 == 0 else 0), "band variant lost beside overlays")
    fields = [text for text in actual_text(reader) if text.startswith("FINAL")]
    require(fields == ["FINAL 8 OF 4", "FINAL 9 OF 4", "FINAL 10 OF 4"], "overlay fields use wrong final numbering")
    outlines = [path for path in outlined_paths(reader) if path["top"] > 210]
    require(outlines and {path["page"] for path in outlines} == {0, 1, 2}
            and all(path["bottom"] <= 242 for path in outlines), "overlay fields lack fixed visible ink")
    boxes = image_boxes(reader)
    widths = [[120, 80, 40], [120, 80, 40], [80, 40], [120, 80]]
    for index, wanted in enumerate(widths):
        page = [box for box in boxes if box["page"] == index]
        require([box["width"] for box in page] == wanted, "image paint order differs")
        require(all(box["x"] == 20 and box["y"] == 40 and box["height"] == 24 and box["pixels"] == [4, 4] for box in page), "overlay image geometry differs")
    root = ET.parse(SOURCE / "overlays.svg").getroot()
    pages = root.findall(SVG + "svg")
    require(len(pages) == 4 and len(list(root.iter(SVG + "image"))) == 10, "SVG page/image count differs")
    for index, page in enumerate(pages):
        labels = [node.get("aria-label") for node in page.iter() if node.get("aria-label")]
        require(labels.count(BODIES[index]) == 1, "SVG body differs")
        require(labels.count("BACKGROUND-BOUND") == (0 if index == 2 else 1), "SVG background inheritance differs")
        require(labels.count(f"FINAL {index + 8} OF 4") == (0 if index == 3 else 1), "SVG overlay fields differ")
    return {"fonts": fonts, "images": boxes, "fields": fields}


def conversion():
    with ZipFile(SOURCE / "discarded-overlays.docx") as package:
        main = ET.fromstring(package.read("word/document.xml"))
        text = "".join(node.text or "" for node in main.iter(W + "t"))
        require(all(body in text for body in BODIES), "explicit overlay discard lost body")
        require("BACKGROUND-BOUND" not in text and "OVERLAY-TABLE" not in text and "FINAL" not in text, "discarded overlay content survived")
        require(len(list(main.iter(W + "drawing"))) == 4, "DOCX body images lost by overlay discard")
    html = (SOURCE / "overlays.html").read_text(encoding="utf-8")
    markdown = (SOURCE / "overlays.md").read_text(encoding="utf-8").replace("\\-", "-")
    for text in (html, markdown):
        require(all(body in text for body in BODIES), "flow conversion lost body")
        require(text.count("BACKGROUND-BOUND") == 2 and text.count("OVERLAY-TABLE") == 2, "flow conversion lost layer sources")
    require(html.count('data-page-layer="BACKGROUND"') == 4
            and html.count('data-page-layer="FOREGROUND"') == 6, "HTML overlay flow placement count")
    counts = dict(line.split("=") for line in (SOURCE / "loss-counts.txt").read_text().splitlines())
    require(int(counts["DOCX"]) == 10 and int(counts["HTML"]) > 10 and int(counts["MARKDOWN"]) > 10, "conversion losses not recorded")


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    report = structures(); conversion()
    caller = Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip()) / "output/consumer-page-overlays.pdf"
    reader = PdfReader(caller, strict=True)
    require(len(reader.pages) == 1 and "CALLER-BACKGROUND" in reader.pages[0].extract_text()
            and "CALLER-BODY" in reader.pages[0].extract_text() and "CALLER-FINAL-1" in actual_text(reader), "isolated overlay caller differs")
    tiles, metrics = [], {}
    with ZipFile(SOURCE / "overlays-png.zip") as bundle:
        require(len(bundle.namelist()) == 4, "PNG ZIP page count")
        for index, name in enumerate(sorted(bundle.namelist())):
            require("/" not in name and ".." not in name, "unsafe generated PNG filename")
            target = OUTPUT / f"raster-{index + 1}.png"
            with Image.open(io.BytesIO(bundle.read(name))) as image:
                image.save(target)
            visible(target, index)
    for name, path in (("overlays", SOURCE / "overlays.pdf"), ("caller", caller)):
        metrics[name] = []
        for index, (left, right) in enumerate(zip(*references(path, OUTPUT / name), strict=True)):
            delta = difference(left, right, 1)
            require(delta["meanAbsoluteChannelError"] < 3 and delta["changedPixelFraction"] < .04, "independent overlay render disagreement")
            metrics[name].append(delta); tiles.extend([(f"{name} Poppler {index+1}", left), (f"{name} PDFium {index+1}", right)])
            if name == "overlays":
                visible(left, index); visible(right, index)
                raster = OUTPUT / f"raster-{index + 1}.png"
                rendered = difference(raster, left, 1)
                require(rendered["meanAbsoluteChannelError"] < 3 and rendered["changedPixelFraction"] < .04, "PNG differs from independently rendered PDF")
                tiles.append((f"Export4j PNG {index+1}", raster))
    contact_sheet(tiles, OUTPUT)
    report["renderDifferences"] = metrics
    (OUTPUT / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print("Page overlays: geometry, theme, paint order, final fields, layer inheritance, explicit losses, PNG and five-page dual rendering passed")


if __name__ == "__main__":
    main()
