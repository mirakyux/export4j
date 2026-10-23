# SPDX-License-Identifier: Apache-2.0
"""Check final streaming media pixels, external actions and independent page renderings."""
import json
from pathlib import Path
import re

from PIL import Image, ImageChops
from pypdf import PdfReader
from verify_advanced_pdf import difference, require
from verify_pdf_raster import references
from verify_production import contact_sheet
from verify_semantic_tables import table_borders
from verify_variable_columns import ink_coverage

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "examples/build/streaming-media"
OUTPUT = ROOT / "build/verification/streaming-media"


def check_links(reader, caller):
    result = []
    for index, page in enumerate(reader.pages):
        width, height = map(float, (page.mediabox.width, page.mediabox.height))
        links = []
        for ref in page.get("/Annots", []):
            annotation = ref.get_object()
            require(annotation["/Subtype"] == "/Link" and annotation["/A"]["/S"] == "/URI", "unexpected active annotation")
            x0, y0, x1, y1 = map(float, annotation["/Rect"])
            require(0 <= x0 < x1 <= width and 0 <= y0 < y1 <= height, "link rectangle leaves its page")
            links.append((annotation["/A"]["/URI"], (x0, height - y1, x1 - x0, y1 - y0)))
        uris = [value[0] for value in links]
        if caller:
            require(uris == ["https://example.com/caller-image", "https://example.com/caller"], "caller text/image destinations differ")
            require(links[0][1][2:] == (16, 8), "caller image link dimensions differ")
        else:
            rows = list(range(1 + index * 3, min(8, 4 + index * 3)))
            expected = ["https://example.com/logo", "https://example.com/header"]
            expected += [uri for row in rows for uri in (f"https://example.com/image/{row:02}", f"https://example.com/row/{row:02}")]
            require(uris == expected, "links were lost, reordered, duplicated or not repeated with headers")
            require(links[0][1][0] == 19 and links[0][1][2:] == (16, 8), "merged header image/padding geometry differs")
            for position in range(2, len(links), 2):
                require(links[position][1][2:] == (12, 8), "body image link dimensions differ")
        result.append(len(links))
    return result


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    require(json.loads((SOURCE / "counts.json").read_text()) == {
        "pages": 3, "rows": 7, "sourceCells": 15, "reads": 8, "closes": 1}, "single-pass source accounting differs")
    caller = Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip()) / "output/consumer-streaming-media.pdf"
    original = [Image.open(SOURCE / name).convert("RGB") for name in ("original.png", "original.jpg")]
    tiles, report = [], {}
    for name, path in (("media", SOURCE / "media.pdf"), ("caller", caller)):
        is_caller = name == "caller"
        reader = PdfReader(path, strict=True)
        require(len(reader.pages) == (1 if is_caller else 3), "media pagination differs")
        if not is_caller:
            text = "\n".join(page.extract_text() for page in reader.pages)
            require(re.findall(r"ROW-\d{2}", text) == [f"ROW-{index:02}" for index in range(1, 8)], "body row source order differs")
            require(len(table_borders(reader)) == 17, "merged header or body borders differ")
            image_count = 0
            for page in reader.pages:
                for item in page.images:
                    actual = item.image.convert("RGB")
                    require(actual.size == (12, 8), "embedded image dimensions differ")
                    require(any(ImageChops.difference(actual, expected).getbbox() is None for expected in original), "embedded PNG/JPEG pixels differ")
                    image_count += 1
            require(image_count == 10, "image draws or repeated headers differ")
        counts = check_links(reader, is_caller)
        first, second = references(path, OUTPUT / name)
        require(len(first) == len(second) == len(reader.pages), "independent renderers disagree on page count")
        comparisons = []
        for index, (a, b) in enumerate(zip(first, second, strict=True)):
            delta = difference(a, b, blur_radius=1)
            delta["uncoveredInkFractions"] = ink_coverage(a, b)
            require(max(delta["uncoveredInkFractions"]) < .001, "independent renderers disagree on visible content")
            comparisons.append(delta)
            tiles.extend([(f"{name} Poppler {index + 1}", a), (f"{name} PDFium {index + 1}", b)])
        report[name] = {"pages": len(reader.pages), "linksPerPage": counts, "renderDifferences": comparisons}
    contact_sheet(tiles, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print("Streaming media: PNG/JPEG pixels, repeated headers, passive links, budgets, caller and dual rendering passed")


if __name__ == "__main__":
    main()
