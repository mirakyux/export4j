# SPDX-License-Identifier: Apache-2.0
"""Check external CFF inputs, edited bytes/positions, loss policies and four real conversions."""
import csv
import json
from pathlib import Path
import re
from zipfile import ZipFile
import xml.etree.ElementTree as ET
from PIL import Image
from pypdf import PdfReader
from pypdf.filters import FlateDecode
import pypdfium2 as pdfium
from markdown_it import MarkdownIt
from verify_advanced_pdf import difference, require
from verify_pdf_raster import references
from verify_production import contact_sheet
from verify_web_exports import Tree

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build/verification/pdf-font-compatibility"
W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"


def compact(value):
    return re.sub(r"\s+", "", value)


def pdf_text(path, marker=None):
    with pdfium.PdfDocument(path) as document:
        require(len(document) == 1, "CFF fixture must remain one page")
        page = document[0]
        try:
            textpage = page.get_textpage()
            try:
                text = textpage.get_text_range()
                if marker is None:
                    return text
                start = text.index(marker)
                return [textpage.get_charbox(i) for i in range(start, start + len(marker))]
            finally:
                textpage.close()
        finally:
            page.close()


def converted_text(path):
    if path.suffix == ".pdf":
        return pdf_text(path)
    if path.suffix == ".docx":
        with ZipFile(path) as package:
            document = ET.fromstring(package.read("word/document.xml"))
            return "".join(node.text or "" for node in document.iter(W + "t"))
    value = path.read_text(encoding="utf-8")
    if path.suffix == ".md":
        value = MarkdownIt("commonmark").render(value)
    tree = Tree(value)
    return "".join(node.text() for node in tree.root.find("p"))


def main():
    directory = Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip()) / "output/pdf-fonts"
    OUTPUT.mkdir(parents=True, exist_ok=True)
    tiles = []; metrics = {}; counts = {"sources": 0, "conversions": 0, "edited": 0, "redacted": 0}
    for name in ("type1c", "opentype-cff", "cid-cff", "split-shows"):
        source = directory / (name + "-source.pdf")
        original = "AB" if name == "cid-cff" else "CFF SECRET KEEP CFF BODY"
        search = "A" if name == "cid-cff" else "SECRET"
        expected = original.replace(search, "READY")
        require(compact(pdf_text(source)) == compact(original), "source Unicode is not independently recoverable")
        reader = PdfReader(source); page = reader.pages[0]
        font = page["/Resources"]["/Font"]["/Cff"].get_object()
        descriptor = font["/DescendantFonts"][0].get_object()["/FontDescriptor"] if name == "cid-cff" else font["/FontDescriptor"]
        program = descriptor.get_object()["/FontFile3"].get_object()
        require(program["/Subtype"] == {"type1c": "/Type1C", "opentype-cff": "/OpenType", "cid-cff": "/CIDFontType0C", "split-shows": "/Type1C"}[name], "incorrect CFF source declaration")
        require(program.get_data().startswith(b"OTTO" if name == "opentype-cff" else b"\x01\x00"), "incorrect embedded CFF container")
        counts["sources"] += 1
        actual = directory / (name + ".png")
        metrics[name] = []
        for engine, pages in enumerate(references(source, OUTPUT / (name + "-source"))):
            require(len(pages) == 1, "source rendering page count")
            delta = difference(actual, pages[0], 1)
            require(delta["meanAbsoluteChannelError"] < 4 and delta["changedPixelFraction"] < .06, "CFF raster differs from independent embedded glyphs")
            metrics[name].append(delta); tiles.append((f"{name} source engine {engine+1}", pages[0]))
        tiles.append((name + " Export4j", actual))
        edited = directory / (name + "-edited.pdf")
        require(compact(pdf_text(edited)) == compact(original.replace(search, "I")), "CFF replacement lost or retained incorrect source text")
        marker = "B" if name == "cid-cff" else "KEEP"
        for before, after in zip(pdf_text(source, marker), pdf_text(edited, marker), strict=True):
            require(max(abs(a - b) for a, b in zip(before, after, strict=True)) < .02, "unmatched CFF glyph coordinates changed")
        counts["edited"] += 1
        losses = (directory / (name + "-edited.losses.tsv")).read_text(encoding="utf-8")
        require(("TEXT_SPACING" in losses) == (name == "split-shows"), "cross-show kerning loss is not explicit")
        redacted = directory / (name + "-redacted.pdf"); redacted_page = PdfReader(redacted).pages[0]
        require(not pdf_text(redacted).strip() and not redacted_page.extract_text().strip(), "redacted CFF text remains extractable")
        require(not redacted_page["/Resources"].get("/Font") and len(list(redacted_page.images)) == 1, "redaction copied source font objects")
        with Image.open(actual) as original_image:
            # Resolve indirect predictor parameters explicitly (pypdf 6.10's image helper misses
            # this case). Keep exact pixel checks using its independent Flate/predictor decoder.
            stream = next(value.get_object() for value in redacted_page["/Resources"]["/XObject"].values())
            require(stream["/Filter"] == "/FlateDecode" and stream["/ColorSpace"] == "/DeviceRGB" and stream["/BitsPerComponent"] == 8, "redaction image coding changed")
            pixels = FlateDecode.decode(stream._data, stream["/DecodeParms"].get_object())
            require(len(pixels) == stream["/Width"] * stream["/Height"] * 3, "redaction decoded pixel length")
            image = Image.frombytes("RGB", (stream["/Width"], stream["/Height"]), pixels)
            original_pixels = original_image.convert("RGB")
            require(image.size == original_pixels.size, "redaction raster geometry changed")
            right = 56 if name == "cid-cff" else 264
            require(set(image.crop((36, 50, right, 90)).get_flattened_data()) == {(0, 0, 0)}, "CFF redaction did not erase every selected pixel")
            require(image.crop((0, 95, 480, 240)).tobytes() == original_pixels.crop((0, 95, 480, 240)).tobytes(), "unselected CFF raster rows changed")
        counts["redacted"] += 1
        for suffix in ("pdf", "docx", "html", "md"):
            reports = []
            for action in ("warn", "ignore"):
                path = directory / f"{name}-{action}.{suffix}"
                require(compact(converted_text(path)) == compact(expected), "CFF semantic conversion changed visible text: " + path.name)
                with path.with_name(path.name + ".losses.tsv").open(encoding="utf-8") as file:
                    rows = list(csv.reader(file, delimiter="\t"))
                require(rows and all(row[2] == action.upper() for row in rows), "loss action is not retained")
                require(any(row[1] == "PDF_LAYOUT" for row in rows) and any(row[1] == "PDF_FONT_MAPPING" for row in rows), "PDF inference losses missing")
                reports.append([(row[0], row[1], row[3]) for row in rows]); counts["conversions"] += 1
            require(reports[0] == reports[1], "IGNORE silently lost diagnostics")
        for variant in ("edited", "redacted", "warn", "ignore"):
            path = directory / f"{name}-{variant}.pdf"
            for engine, pages in enumerate(references(path, OUTPUT / (name + "-" + variant))):
                require(len(pages) == 1, "new CFF processing result pagination")
                tiles.append((f"{name} {variant} engine {engine+1}", pages[0]))
    sheets = OUTPUT / "contact-sheets"; sheets.mkdir(exist_ok=True)
    contact_sheet(tiles, sheets)
    (OUTPUT / "report.json").write_text(json.dumps({"counts": counts, "rasterMetrics": metrics}, indent=2), encoding="utf-8")
    print(json.dumps(counts))


if __name__ == "__main__":
    main()
