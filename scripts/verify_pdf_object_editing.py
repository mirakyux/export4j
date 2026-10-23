# SPDX-License-Identifier: Apache-2.0
"""Independently inspects fields/APs, links, outlines, metadata, attachments and flattening."""
from __future__ import annotations

import json
import subprocess
from pathlib import Path
from pypdf import PdfReader
from PIL import Image
import pypdfium2 as pdfium
from verify_advanced_pdf import difference, require
from verify_production import contact_sheet

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build/verification/pdf-object-editing"


def render(path, directory):
    poppler = directory / "poppler"; target = directory / "pdfium"
    poppler.mkdir(parents=True, exist_ok=True); target.mkdir(parents=True, exist_ok=True)
    subprocess.run(["pdftoppm", "-r", "192", "-png", str(path), str(poppler / "page")],
                   check=True, capture_output=True, timeout=60)
    paths = []
    with pdfium.PdfDocument(path) as document:
        document.init_forms()
        for index in range(len(document)):
            page = document[index]
            try:
                bitmap = page.render(scale=192 / 72, may_draw_forms=True)
                try:
                    name = target / f"page-{index + 1}.png"; bitmap.to_pil().convert("RGB").save(name); paths.append(name)
                finally: bitmap.close()
            finally: page.close()
    return sorted(poppler.glob("page-*.png"), key=lambda p: int(p.stem.rsplit("-", 1)[1])), paths


def check_fields(reader, expected):
    fields = reader.get_fields(); require(set(fields) == set(expected), "field tree names")
    widgets = [node.get_object() for page in reader.pages for node in page.get("/Annots", []) if node.get_object()["/Subtype"] == "/Widget"]
    for name, value in expected.items():
        actual = fields[name]["/V"]
        if isinstance(actual, list): actual = actual[0]
        require(str(actual).lstrip("/") == value, "field value: " + name)
    for widget in widgets:
        appearance = widget["/AP"]["/N"].get_object()
        if "/BBox" not in appearance: appearance = appearance[widget["/AS"]].get_object()
        require(len(appearance.get_data()) > 0 and len(appearance["/BBox"]) == 4, "concrete current widget appearance")


def main():
    sample = ROOT / "examples/build/pdf-object-editing"
    consumer = Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip()) / "output"
    edited = PdfReader(sample / "edited.pdf"); flattened = PdfReader(sample / "flattened.pdf")
    check_fields(edited, {"name": "NAME-READY", "status": "Ready", "approved": "Yes", "mode": "Two"})
    require(edited.metadata.title == "Edited object fixture", "edited metadata")
    require(edited.get_destination_page_number(edited.outline[0]) == 0, "root bookmark")
    require(edited.get_destination_page_number(edited.outline[1][0]) == 1, "child bookmark")
    annotations = [node.get_object() for page in edited.pages for node in page.get("/Annots", [])]
    require(any(node["/Subtype"] == "/Text" and node["/Contents"] == "COMMENT-KEEP" for node in annotations), "comment preserved")
    require(any(node.get("/A", {}).get("/URI") == "https://example.org/review" for node in annotations), "external link")
    require(any(node["/Subtype"] == "/Link" and "/Dest" in node for node in annotations), "internal link")
    require(list(edited.attachments["evidence.txt"]) == [b"ATTACHMENT-KEEP"], "attachment exact bytes")
    require(not flattened.get_fields(), "flattened field tree")
    require(all(node.get_object()["/Subtype"] != "/Widget" for page in flattened.pages for node in page.get("/Annots", [])), "flattened widgets removed")
    require("NAME-READY" in flattened.pages[0].extract_text() and "Ready" in flattened.pages[0].extract_text(), "flattened visible values")
    require(list(flattened.attachments["evidence.txt"]) == [b"ATTACHMENT-KEEP"], "flattening retains attachment")
    require((sample / "losses.tsv").read_text().strip() == "FORMS\tFORM_EDITABILITY_REMOVED", "explicit flatten loss")
    caller = PdfReader(consumer / "consumer-object-edited.pdf"); check_fields(caller, {"caller": "CALLER-READY"})
    caller_flat = PdfReader(consumer / "consumer-object-flattened.pdf"); require(not caller_flat.get_fields(), "consumer flattened fields")
    require("CALLER-READY" in caller_flat.pages[0].extract_text(), "consumer flattened visible text")
    metrics = {}; tiles = []; visible = {}
    for path, name in ((sample / "edited.pdf", "edited"), (sample / "flattened.pdf", "flattened"),
                       (consumer / "consumer-object-edited.pdf", "consumer"), (consumer / "consumer-object-flattened.pdf", "consumer-flat")):
        first, second = render(path, OUTPUT / name); visible[name] = second
        metrics[name] = []
        for index, (left, right) in enumerate(zip(first, second, strict=True)):
            delta = difference(left, right, 2)
            require(delta["meanAbsoluteChannelError"] < 4 and delta["changedPixelFraction"] < .06, "object edit renderer difference")
            metrics[name].append(delta); tiles.extend([(f"{name} {index + 1} Poppler", left), (f"{name} {index + 1} PDFium", right)])
    for before, after in (("edited", "flattened"), ("consumer", "consumer-flat")):
        for a, b in zip(visible[before], visible[after], strict=True):
            delta = difference(a, b, 2); require(delta["meanAbsoluteChannelError"] < 1 and delta["changedPixelFraction"] < .01, "flattening changed visible appearance")
    # The text field's blue DA survives filling: count strongly blue ink within its rendered region.
    image = Image.open(visible["edited"][0]).convert("RGB")
    crop = image.crop((int(24 * 192 / 72), int(105 * 192 / 72), int(224 * 192 / 72), int(133 * 192 / 72)))
    require(sum(1 for r, g, b in crop.get_flattened_data() if b > r * 1.5 and b > g * 1.5 and b > 80) > 60, "field foreground color lost")
    contact_sheet(tiles, OUTPUT)
    result = {"pdf_pages": 6, "field_kinds": 4, "opaque_attachment": "exact", "flatten_loss": "explicit", "rendering": metrics, "checks": "passed"}
    (OUTPUT / "report.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8"); print(json.dumps(result, indent=2))


if __name__ == "__main__": main()
