# SPDX-License-Identifier: Apache-2.0
"""Checks actual text replacement and absence of original objects/pixels after raster redaction."""
import json
from pathlib import Path
import pypdfium2 as pdfium
from pypdf import PdfReader
from pypdf.generic import IndirectObject
from verify_advanced_pdf import require, difference
from verify_production import contact_sheet
from verify_pdf_raster import references

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build/verification/pdf-content-editing"


def keep_boxes(path):
    with pdfium.PdfDocument(path) as document:
        page = document[0]
        try:
            text = page.get_textpage()
            try:
                value = text.get_text_range(); index = value.index("KEEP")
                return [text.get_charbox(index+i) for i in range(4)]
            finally: text.close()
        finally: page.close()


def sanitize(path):
    raw = path.read_bytes(); require(b"SECRET" not in raw and raw.count(b"%%EOF") == 1, "original bytes/history retained")
    reader = PdfReader(path)
    require(not reader.metadata and not reader.attachments and not reader.get_fields(), "source nonvisual objects retained")
    require("/Names" not in reader.trailer["/Root"] and "/Metadata" not in reader.trailer["/Root"], "source catalog data retained")
    for generation, objects in reader.xref.items():
        for number in objects:
            if not number: continue
            obj = reader.get_object(IndirectObject(number, generation, reader))
            if hasattr(obj, "get_data"): require(b"SECRET" not in obj.get_data(), "original decoded stream retained")
    for page in reader.pages:
        require(not page.extract_text() and "/Font" not in page["/Resources"] and not page.get("/Annots"), "text/font/annotation survived redaction")
        images = list(page["/Resources"]["/XObject"].values())
        require(len(images) == 1 and images[0].get_object()["/Subtype"] == "/Image" and "/SMask" not in images[0].get_object(), "sanitized image-only graph")


def main():
    sample = ROOT / "examples/build/pdf-content-editing"
    consumer = Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip()) / "output"
    source = sample / "source.pdf"; replaced = sample / "replaced.pdf"; redacted = sample / "redacted.pdf"
    require("SECRET" in PdfReader(source).pages[0].extract_text(), "original match absent")
    edited = PdfReader(replaced).pages[0].extract_text()
    require("PUBLIC" in edited and "SECRET" not in edited and "Visible outside" in edited, "replacement is not real text")
    for a, b in zip(keep_boxes(source), keep_boxes(replaced), strict=True):
        require(max(abs(x-y) for x,y in zip(a,b,strict=True)) < .015, "following text moved")
    caller_text = PdfReader(consumer / "consumer-text-replaced.pdf").pages[0].extract_text()
    # Shorter replacement keeps the following glyphs in place; extractors may infer a space in that gap.
    require("".join(caller_text.split()) == "CALLER-PUBLIC-KEEP", "isolated stream edit missing")
    for path in (redacted, sample / "image-redacted.pdf", consumer / "consumer-redacted.pdf"): sanitize(path)
    tiles = []
    for name, path in (("source", source), ("replaced", replaced), ("redacted", redacted),
                       ("image-redacted", sample / "image-redacted.pdf"), ("consumer-text", consumer / "consumer-text-replaced.pdf"), ("consumer-redacted", consumer / "consumer-redacted.pdf")):
        renders = references(path, OUTPUT / name)
        for engine, pages in enumerate(renders):
            tiles.extend([(f"{name} engine {engine+1} page {index+1}", page) for index,page in enumerate(pages)])
    from PIL import Image
    for engine in ("poppler", "pdfium"):
        with Image.open(OUTPUT / "source" / engine / "page-1.png") as a, Image.open(OUTPUT / "redacted" / engine / "page-1.png") as b:
            require(a.size == b.size, "redaction view size changed")
            # Configured pt region becomes [36,36,140,76] at 144 dpi, enlarged by one pixel.
            require(set(b.convert("RGB").crop((36,36,140,76)).get_flattened_data()) == {(0,0,0)}, "sensitive region pixels survived")
            # Source vector text is rasterized by PDFBox before encoding. Poppler/PDFium antialiasing
            # is a separate renderer; apply the established raster cross-engine quality bound.
            left = OUTPUT / f"{engine}-outside-before.png"; right = OUTPUT / f"{engine}-outside-after.png"
            a.convert("RGB").crop((0,82,a.width,a.height)).save(left); b.convert("RGB").crop((0,82,b.width,b.height)).save(right)
            delta = difference(left, right, 1)
            require(delta["meanAbsoluteChannelError"] < 4 and delta["changedPixelFraction"] < .06, "unselected visible content changed")
    features = {line.split("\t")[1] for line in (sample / "losses.tsv").read_text().splitlines()}
    require(features == {"TEXT_FONT", "RASTERIZATION", "DOCUMENT_METADATA", "ATTACHMENTS"}, "content losses are not reconciled")
    contact_sheet(tiles, OUTPUT)
    for engine in ("poppler", "pdfium"):
        with Image.open(OUTPUT / "image-redacted" / engine / "page-1.png") as image:
            require(set(image.convert("RGB").crop((420,260,540,340)).get_flattened_data()) == {(0,0,0)}, "source image pixels survived redaction")
    (OUTPUT / "report.json").write_text(json.dumps({"checks": "passed", "pages": 7, "sensitive_pixels": "removed", "original_object_graph": "absent"}, indent=2)+"\n")
    print("PDF content editing: actual replacement, fixed following positions, sanitized fresh graph and dual rendering passed")


if __name__ == "__main__": main()
