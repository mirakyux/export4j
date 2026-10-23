# SPDX-License-Identifier: Apache-2.0
"""Independent exact resources, object preservation, sample dimensions and two-engine pixels."""
import csv
import json
from pathlib import Path
from pypdf import PdfReader
from verify_advanced_pdf import require, difference
from verify_production import contact_sheet
from verify_pdf_raster import references

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build/verification/pdf-optimization"


def main():
    sample = ROOT / "examples/build/pdf-optimization"
    source = ROOT / "examples/build/pdf-page-editing/edited.pdf"
    passive = ROOT / "examples/build/pdf-object-editing/edited.pdf"
    consumer = Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip()) / "output"
    rows = {row["mode"]: row for row in csv.DictReader((sample / "statistics.tsv").open(), delimiter="\t")}
    tiles = []; metrics = {}
    for mode, original, output in [(name, source, sample / f"{name}.pdf") for name in ("lossless", "sampled", "jpeg")] + [
            ("objects", passive, sample / "objects.pdf"), ("consumer", consumer / "consumer-page-edited.pdf", consumer / "consumer-optimized.pdf")]:
        before = PdfReader(original); after = PdfReader(output)
        require(len(before.pages) == len(after.pages), "optimization page count")
        def fields(reader):
            return {name: {key: str(field.get(key)) for key in ("/FT", "/V", "/DV", "/Ff", "/Opt")}
                    for name, field in (reader.get_fields() or {}).items()}
        require(before.metadata == after.metadata and fields(before) == fields(after), "metadata and field values changed")
        require(dict(before.attachments) == dict(after.attachments), "attachment bytes changed")
        if mode != "consumer":
            row = rows[mode]; require(int(row["input"]) == original.stat().st_size and int(row["output"]) == output.stat().st_size, "measured byte counts")
            require(int(row["losses"]) == {"lossless": 0, "sampled": 1, "jpeg": 2, "objects": 0}[mode], "loss accounting")
        for left, right in zip(before.pages, after.pages, strict=True):
            require(left.mediabox == right.mediabox and left.cropbox == right.cropbox and left.rotation == right.rotation, "geometry changed")
            require(left.get_contents().get_data() == right.get_contents().get_data(), "decoded drawing commands changed")
            images_before = [obj.get_object() for obj in left.get("/Resources", {}).get("/XObject", {}).values() if obj.get_object().get("/Subtype") == "/Image"]
            images_after = [obj.get_object() for obj in right.get("/Resources", {}).get("/XObject", {}).values() if obj.get_object().get("/Subtype") == "/Image"]
            require(len(images_before) == len(images_after), "image resources lost")
            for a, b in zip(images_before, images_after, strict=True):
                if mode in ("sampled", "jpeg"):
                    require((b["/Width"], b["/Height"]) == (15, 10), "resampling dimensions")
                    require(b["/Filter"] == ("/DCTDecode" if mode == "jpeg" else "/FlateDecode"), "explicit image encoding")
                else: require(a.get_data() == b.get_data(), "lossless decoded image bytes changed")
        original_renders = references(original, OUTPUT / mode / "before")
        output_renders = references(output, OUTPUT / mode / "after"); metrics[mode] = []
        for engine, (previous, current) in enumerate(zip(original_renders, output_renders, strict=True)):
            for index, (left, right) in enumerate(zip(previous, current, strict=True)):
                delta = difference(left, right, 0)
                if mode in ("sampled", "jpeg"): require(delta["meanAbsoluteChannelError"] < .5 and delta["changedPixelFraction"] < .025, "resampled flat-color image differs outside quality contract")
                else: require(delta["meanAbsoluteChannelError"] == 0, "lossless optimization changed pixels")
                metrics[mode].append(delta); tiles.append((f"{mode} engine {engine+1} page {index+1}", right))
    contact_sheet(tiles, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps({"checks": "passed", "pages": 9, "rendering": metrics}, indent=2) + "\n")
    print("PDF optimization: 9 pages, preserved objects/resources, exact byte statistics and dual rendering passed")


if __name__ == "__main__": main()
