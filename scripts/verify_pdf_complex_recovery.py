# SPDX-License-Identifier: Apache-2.0
"""Independently verify clipped/transparency recovery pixels, conversions and actual losses."""
import argparse
import base64
import csv
from hashlib import sha256
from io import BytesIO
import json
from pathlib import Path
import re
from zipfile import ZipFile
from PIL import Image
from pypdf import PdfReader
from verify_advanced_pdf import difference, require
from verify_pdf_font_compatibility import compact, converted_text
from verify_pdf_passive import recovered_pdf_image
from verify_pdf_raster import references
from verify_production import contact_sheet

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "build/verification/pdf-complex-fixtures"
OUTPUT = ROOT / "build/verification/pdf-complex-recovery"


def same_pixels(left, right):
    require(left.size == right.size and left.convert("RGB").tobytes() == right.convert("RGB").tobytes(),
            "conversion changed the actual recovered image pixels")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--consumer-build-directory", type=Path)
    args = parser.parse_args()
    consumer = args.consumer_build_directory or Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip())
    if not consumer.is_absolute():
        consumer = ROOT / consumer
    directory = consumer / "output/pdf-complex-recovery"
    OUTPUT.mkdir(parents=True, exist_ok=True)
    summary = json.loads((directory / "summary.json").read_text())
    require(summary == {"sources": 6, "conversions": 48, "rejections": 12}, "complex consumer matrix incomplete")
    manifest = json.loads((FIXTURES / "manifest.json").read_text())
    tiles, metrics, pages = [], {}, 0
    for name, record in manifest.items():
        source = FIXTURES / (name + ".pdf")
        require(sha256(source.read_bytes()).hexdigest() == record["sha256"], "complex source bytes changed")
        if not record["accepted"]:
            require((directory / (name + "-protected.pdf")).read_bytes() == bytes((7, 2, 1)), "rejected complex conversion changed old target")
            continue
        require((directory / (name + "-source.pdf")).read_bytes() == source.read_bytes(), "consumer source snapshot changed")
        source_images = [paths[0] for paths in references(source, OUTPUT / (name + "-source"))]
        pages += 1
        tiles.extend((name + " source " + str(index), path) for index, path in enumerate(source_images, 1))
        metrics[name] = []
        reports = []
        for action in ("warn", "ignore"):
            label = name + "-" + action
            raster = directory / (label + ".png")
            with Image.open(raster) as actual:
                require(actual.size == (600, 480), "recovery ignored the explicit physical DPI")
                require(actual.convert("L").getextrema()[0] < 100, "complex page became blank")
                for reference in source_images:
                    delta = difference(raster, reference, 1)
                    require(delta["meanAbsoluteChannelError"] < 4 and delta["changedPixelFraction"] < .06,
                            "complex recovery differs from independent clipping/transparency appearance: " + name)
                    metrics[name].append(delta)
                for extension in ("pdf", "docx", "html", "md"):
                    path = directory / (label + "." + extension)
                    text = compact(converted_text(path))
                    require(text == ("\ufffc" if extension == "pdf" else ""), "raster conversion fabricated searchable source text")
                    if extension == "pdf":
                        reader = PdfReader(path)
                        require(len(reader.pages) == 1 and len(reader.pages[0].images) == 1, "whole-page flow image overflowed or duplicated")
                        with recovered_pdf_image(path) as image:
                            same_pixels(actual, image)
                        for index, rendered in enumerate(references(path, OUTPUT / (label + "-converted")), 1):
                            require(len(rendered) == 1, "converted complex page count differs")
                            tiles.append((label + " converted " + str(index), rendered[0]))
                        pages += 1
                    elif extension == "docx":
                        with ZipFile(path) as archive:
                            media = [key for key in archive.namelist() if key.startswith("word/media/")]
                            require(len(media) == 1, "DOCX lost or duplicated whole-page image")
                            with Image.open(BytesIO(archive.read(media[0]))) as image:
                                same_pixels(actual, image)
                    else:
                        encoded = re.findall(r"data:image/png;base64,([A-Za-z0-9+/=]+)", path.read_text(encoding="utf-8"))
                        require(len(encoded) == 1, "Web conversion lost or duplicated page image")
                        with Image.open(BytesIO(base64.b64decode(encoded[0], validate=True))) as image:
                            same_pixels(actual, image)
            tiles.append((label + " recovered", raster))
            with (directory / (label + ".losses.tsv")).open(encoding="utf-8", newline="") as source_report:
                report = list(csv.DictReader(source_report, delimiter="\t"))
            require({item["feature"] for item in report} == {"PDF_LAYOUT", "PDF_STRUCTURE"}
                    and all(item["action"] == action.upper() and item["treatment"] == "DEGRADE" for item in report),
                    "complex recovery did not declare exact actual losses")
            require(any("TEXT_VECTORS_AND_STRUCTURE_NOT_EDITABLE" in item["effects"] for item in report), "lost editability is absent from report")
            reports.append([{key: value for key, value in item.items() if key != "action"} for item in report])
        require(reports[0] == reports[1], "IGNORE concealed actual raster recovery losses")
    sheets = OUTPUT / "contact-sheets"
    sheets.mkdir(exist_ok=True)
    contact_sheet(tiles, sheets)
    report = {"checks": "passed", **summary, "fixtureFiles": len(manifest), "renderedPdfPages": pages,
              "independentPageImages": pages * 2, "contactTiles": len(tiles), "appearance": metrics}
    (OUTPUT / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in report.items() if key != "appearance"}))


if __name__ == "__main__":
    main()
