# SPDX-License-Identifier: Apache-2.0
"""Independent glyph, image invocation, reference and two-engine visual verification."""
import argparse
import json
from pathlib import Path
import pypdfium2 as pdfium
from pypdf import PdfReader
from pypdf.generic import ContentStream
from PIL import Image, ImageChops
from verify_pdf_raster import references
from verify_production import contact_sheet
from verify_advanced_pdf import require

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build/verification/pdf-instance-edit"


def glyphs(path, page_index=0):
    with pdfium.PdfDocument(path) as document:
        page = document[page_index]
        try:
            text = page.get_textpage()
            try:
                return text.get_text_range(), [text.get_charbox(i) for i in range(text.count_chars())]
            finally:
                text.close()
        finally:
            page.close()


def images(reader, page):
    found = []
    def walk(content, resources, active):
        for operands, operator in ContentStream(content, reader).operations:
            if operator != b"Do":
                continue
            reference = resources["/XObject"].raw_get(operands[0]); value = reference.get_object()
            if value["/Subtype"] == "/Image":
                found.append(reference)
            else:
                require(reference.idnum not in active, "cyclic fixture")
                walk(value, value.get("/Resources", resources), active | {reference.idnum})
    walk(page.get_contents(), page["/Resources"], set())
    return found


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--consumer-build-directory", type=Path)
    args = parser.parse_args()
    directory = args.consumer_build_directory or Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip())
    caller = directory / "output/pdf-instance-edit"
    OUTPUT.mkdir(parents=True, exist_ok=True)
    results = {}
    for name in ("font", "color", "spacing", "td", "tm", "other-line", "images", "images-alpha"):
        source = caller / (name + "-source.pdf"); original = PdfReader(source)
        tiles = []
        reference = references(source, OUTPUT / (name + "-source"))
        for engine, pages in enumerate(reference):
            tiles.extend((f"{name} source E{engine+1} P{i+1}", path) for i, path in enumerate(pages))
        for action in ("warn", "ignore"):
            target = caller / (name + "-" + action + ".pdf"); actual = PdfReader(target)
            require(len(actual.pages) == 2, "page count changed")
            require(actual.pages[1].get_contents().get_data() == original.pages[1].get_contents().get_data(), "unselected page stream changed")
            if name.startswith("images"):
                original_instances = images(original, original.pages[0]); selected = images(actual, actual.pages[0]); other = images(actual, actual.pages[1])
                require(len(selected) == len(other) == 4, "actual drawing count changed")
                require(selected[0] == selected[1] == selected[3] == other[0] == other[2] and selected[2] != selected[3], "shared image instance was not isolated")
                for i in (0, 1, 3):
                    require(selected[i].get_object().get_data() == original_instances[i].get_object().get_data(), "unmatched pixels changed")
                require(selected[2].get_object()["/Width"] == 3 and "/SMask" in selected[2].get_object(), "replacement alpha lost")
            elif name != "other-line":
                before_text, before_boxes = glyphs(source); after_text, after_boxes = glyphs(target)
                require("HI" in after_text and "SECRET" not in "".join(after_text.split()), "cross-state replacement absent or duplicated")
                left = before_text.index("KEEP"); right = after_text.index("KEEP")
                for i in range(4):
                    require(max(abs(a-b) for a, b in zip(before_boxes[left+i], after_boxes[right+i], strict=True)) < .02, "unmatched glyph position changed")
            else:
                require(actual.pages[0].get_contents().get_data() == original.pages[0].get_contents().get_data(), "different lines created false match")
            rendered = references(target, OUTPUT / (name + "-" + action))
            for engine, pages in enumerate(rendered):
                with Image.open(reference[engine][1]) as a, Image.open(pages[1]) as b:
                    require(ImageChops.difference(a.convert("RGB"), b.convert("RGB")).getbbox() is None, "unselected page pixels changed")
                if name.startswith("images"):
                    with Image.open(pages[0]) as image:
                        red = image.getpixel((40, 140)); blue = image.getpixel((220, 140))
                        require(red[0] > red[2] and blue[2] > blue[0], "wrong image drawing was replaced")
                tiles.extend((f"{name} {action} E{engine+1} P{i+1}", path) for i, path in enumerate(pages))
            if action == "ignore":
                for engine in range(2):
                    for i in range(2):
                        warn = OUTPUT / (name + "-warn") / ("poppler" if engine == 0 else "pdfium") / f"page-{i+1}.png"
                        with Image.open(warn) as a, Image.open(rendered[engine][i]) as b:
                            require(ImageChops.difference(a.convert("RGB"), b.convert("RGB")).getbbox() is None, "WARN and IGNORE produced different content")
        contacts = OUTPUT / (name + "-contacts"); contacts.mkdir(parents=True, exist_ok=True)
        contact_sheet(tiles, contacts)
        results[name] = {"outputs": 2, "pagesRendered": 12}
    (OUTPUT / "report.json").write_text(json.dumps({"cases": results, "pagesRendered": 96}, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"cases": 8, "outputs": 16, "pagesRendered": 96}))


if __name__ == "__main__":
    main()
