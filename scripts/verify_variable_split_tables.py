# SPDX-License-Identifier: Apache-2.0
"""Check real unequal-width cell continuation, physical page fields, source uniqueness and visible output."""
import json
from pathlib import Path
import re

from pypdf import PdfReader
from verify_advanced_pdf import difference, require
from verify_pdf_raster import references
from verify_production import contact_sheet
from verify_semantic_tables import annotations, page_logical, table_borders
from verify_variable_columns import ink_coverage
from verify_variable_tables import logical_pages

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "examples/build/variable-split-tables"
OUTPUT = ROOT / "build/verification/variable-split-tables"


def inspect(path, prefix, count, widths, content_bounds):
    reader = PdfReader(path, strict=True)
    texts = logical_pages(path)
    require(len(texts) == len(reader.pages) > 1, "continued table must use multiple real pages")
    require(re.findall(prefix + r"\d{3}", "\n".join(texts)) == [f"{prefix}{i:03}" for i in range(1, count + 1)],
            "cell source was lost, repeated or reordered during actual-width continuation")
    boxes = table_borders(reader)
    require(set(widths).issubset({round(box["width"], 3) for box in boxes}), "actual narrow/wide column widths differ")
    left, top, right, bottom = content_bounds
    for box in boxes:
        require(box["x"] >= left - .003 and box["y"] >= top - .003
                and box["x"] + box["width"] <= right + .003 and box["y"] + box["height"] <= bottom + .003,
                "continued table crosses its actual padded page bounds")
    for index, page in enumerate(reader.pages):
        expected = f"PAGE={index + 1} OF={len(reader.pages)}"
        headers = [value for value in page_logical(page) if value.startswith("PAGE=")]
        require(headers and all(value == expected for value in headers), "header uses a logical column instead of the physical page")
        require(texts[index].count(expected) == len(headers), "visible header field values differ from logical text")
    return reader, texts, boxes


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    continued, texts, boxes = inspect(SOURCE / "continued.pdf", "T", 72, [112.0, 228.0], (22, 22, 378, 198))
    source = "\n".join(texts)
    require(source.count("KEY") == source.count("END") == source.count("AFTER") == 1,
            "completed neighbour cells were repeated or the next row was lost")
    require(source.count("alpha") == source.count("beta") == 72, "unlabelled source words changed")
    require(source.count("BEGIN") == source.count("FINISH") == 1, "padded mixed flow lost or repeated its neighbours")
    require(f"TAIL PAGE={len(continued.pages)}" in texts[-1], "body field was resolved before its final page was known")
    require(sum(len(page.images) for page in continued.pages) == 1, "inline picture was lost or repeated")
    picture = next(image for page in continued.pages for image in page.images).image.convert("RGB")
    require(picture.size == (12, 8) and picture.tobytes() == bytes((34, 119, 170)) * 96, "inline picture pixels changed")
    links = annotations(continued)
    image_links = [link for link in links if link.get("uri") == "https://example.com/picture"]
    require(len(image_links) == 1 and abs(image_links[0]["width"] - 12) < .003 and abs(image_links[0]["height"] - 8) < .003,
            "continued picture link does not match the real picture bounds")
    text_links = [link for link in links if link.get("uri") == "https://example.com/continued"]
    require(len(text_links) > 20 and any(link["x"] < 140 for link in text_links) and any(link["x"] > 140 for link in text_links),
            "source hyperlinks did not continue through both actual widths")
    for link in text_links:
        left, right = (58, 134) if link["x"] < 140 else (186, 378)
        require(link["x"] >= left - .003 and link["x"] + link["width"] <= right + .003,
                "link geometry uses a previous column's width")
    require({36.0, 76.0, 192.0}.issubset({round(box["width"], 3) for box in boxes}), "merged inline columns were scaled or flattened")

    balanced, balanced_text, _ = inspect(SOURCE / "balanced.pdf", "B", 48, [112.0, 228.0], (22, 22, 378, 198))
    require(sum(value.count("gamma") for value in balanced_text) == sum(value.count("delta") for value in balanced_text) == 48,
            "balanced remeasurement changed source content")
    caller = Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip()) / "output/consumer-variable-split-tables.pdf"
    caller_reader, _, _ = inspect(caller, "C", 40, [89.333, 178.667], (20, 20, 300, 160))
    require(all(link.get("uri") == "https://example.com/caller" for link in annotations(caller_reader)), "published caller hyperlinks changed")
    require(len(annotations(caller_reader)) > 10, "published caller hyperlinks are missing")

    tiles, report = [], {}
    for name, path in [("continued", SOURCE / "continued.pdf"), ("balanced", SOURCE / "balanced.pdf"), ("caller", caller)]:
        first, second = references(path, OUTPUT / name)
        require(len(first) == len(second), "independent renderer page counts differ")
        deltas = []
        for index, (a, b) in enumerate(zip(first, second, strict=True)):
            delta = difference(a, b, blur_radius=1)
            delta["uncoveredInkFractions"] = ink_coverage(a, b)
            require(max(delta["uncoveredInkFractions"]) < .001, "independent renderers disagree on visible ink")
            deltas.append(delta)
            tiles.extend([(f"{name} Poppler {index + 1}", a), (f"{name} PDFium {index + 1}", b)])
        report[name] = {"pages": len(first), "renderDifferences": deltas}
    contact_sheet(tiles, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print("Variable split tables: original sources, actual widths, continued merged cells, final fields, links, picture pixels, published caller and dual rendering passed")


if __name__ == "__main__":
    main()
