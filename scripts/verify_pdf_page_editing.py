# SPDX-License-Identifier: Apache-2.0
"""Independent page geometry, retained streams, text, font and raster verification."""
from __future__ import annotations

import json
import subprocess
from pathlib import Path
from pypdf import PdfReader
from verify_adr_form import render
from verify_advanced_pdf import difference, require
from verify_production import contact_sheet

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build/verification/pdf-page-editing"


def main():
    sample = ROOT / "examples/build/pdf-page-editing"
    consumer = Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip()) / "output"
    source = PdfReader(sample / "source.pdf")
    edited = PdfReader(sample / "edited.pdf")
    require(len(edited.pages) == 2, "edited physical pages")
    require(list(edited.pages[0].cropbox) == [10, 10, 310, 230], "explicit crop box")
    require(list(edited.pages[1].mediabox) == [0, 0, 240, 180], "scaled media box")
    require(edited.pages[1].rotation == 90, "viewer rotation")
    require(edited.metadata.title == source.metadata.title, "document metadata preserved")
    for index, markers in enumerate((["PAGE-A SOURCE-CONTENT", "WATERMARK 1 / 2"], ["PAGE-B SCALE-AND-ROTATE", "PAGE 2 / 2"])):
        text = edited.pages[index].extract_text()
        for marker in markers: require(text.count(marker) == 1, "edited text: " + marker)
        original = source.pages[index].get_contents().get_data()
        require(original in edited.pages[index].get_contents().get_data(), "source operators changed")
    fonts = edited.pages[0]["/Resources"]["/Font"].get_object()
    require(any(font.get_object().get("/Subtype") == "/Type0" for font in fonts.values()), "explicit embedded stamp font")
    require(len(edited.pages[0].images) == 1, "image stamp resource")
    require((sample / "losses.tsv").read_text().strip() == "PAGE_CROP\tOUTSIDE_CROP_HIDDEN_NOT_REMOVED", "actual crop loss")
    caller = PdfReader(consumer / "consumer-page-edited.pdf")
    require(caller.pages[0].rotation == 90, "consumer rotation")
    for marker in ("CALLER-STAMP", "CALLER-SOURCE-KEEP"): require(caller.pages[0].extract_text().count(marker) == 1, "consumer source/stamp")
    tiles = []; metrics = {}
    for path, name in ((sample / "edited.pdf", "report"), (consumer / "consumer-page-edited.pdf", "consumer")):
        a, b = render(path, OUTPUT / name); require(len(a) == (2 if name == "report" else 1), "rendered pages")
        # PDFium renders CropBox by default; request the same visible region from Poppler.
        subprocess.run(["pdftoppm", "-cropbox", "-r", "192", "-png", str(path), str(OUTPUT / name / "poppler/page")], check=True, capture_output=True)
        metrics[name] = []
        for index, (left, right) in enumerate(zip(a, b, strict=True)):
            delta = difference(left, right, 2)
            require(delta["meanAbsoluteChannelError"] < 4 and delta["changedPixelFraction"] < .06, "page edit renderer disagreement")
            metrics[name].append(delta); tiles.extend([(f"{name} {index + 1} Poppler", left), (f"{name} {index + 1} PDFium", right)])
    contact_sheet(tiles, OUTPUT)
    result = {"pages": 3, "retained_streams": 2, "crop_loss": "explicit", "rendering": metrics, "checks": "passed"}
    (OUTPUT / "report.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8"); print(json.dumps(result, indent=2))


if __name__ == "__main__": main()
