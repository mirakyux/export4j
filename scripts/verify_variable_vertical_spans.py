# SPDX-License-Identifier: Apache-2.0
"""Independently inspect vertical source owners continued through real unequal-width columns."""
import argparse
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
OUTPUT = ROOT / "build/verification/variable-vertical-spans"


def inspect(path, prefix, count, widths, bounds):
    reader = PdfReader(path, strict=True)
    texts = logical_pages(path)
    joined = "\n".join(texts)
    compact = re.sub(r"\s+", "", joined)
    # Repeated headers may occur between two source fragments of the same cell.
    # Their final physical page values are checked separately below.
    compact = re.sub(r"PAGE=\d+OF=\d+", "", compact)
    require(len(reader.pages) > 1, "vertical owner must continue over real physical pages")
    require(re.findall(prefix + r"\d{3}", joined) == [f"{prefix}{i:03}" for i in range(1, count + 1)],
            "vertical owner source was lost, repeated or reordered")
    boxes = table_borders(reader)
    require(set(widths).issubset({round(box["width"], 3) for box in boxes}), "table did not use both actual column widths")
    left, top, right, bottom = bounds
    for box in boxes:
        require(box["x"] >= left - .003 and box["y"] >= top - .003
                and box["x"] + box["width"] <= right + .003
                and box["y"] + box["height"] <= bottom + .003, "vertical owner crosses actual page bounds")
    require(compact.count("END") == compact.count("AFTER") == 1, "following independent row was lost or repeated")
    return reader, texts, compact


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--consumer-build-directory", type=Path)
    args = parser.parse_args()
    consumer = args.consumer_build_directory or Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip())
    fixtures = []
    report = {}
    for name in ("vertical", "vertical-balanced"):
        source = ROOT / "examples/build/variable-split-tables" / (name + ".pdf")
        reader, texts, joined = inspect(source, "V", 36, (112.0, 228.0), (22, 22, 378, 198))
        require(re.findall(r"N\d{3}", joined) == [f"N{i:03}" for i in range(1, 13)], "neighbouring vertical owner changed")
        require(joined.count("RIGHT-A") == joined.count("BEGIN") == joined.count("FINISH") == 1,
                "covered rows repeated completed neighbours")
        require(sum(len(page.images) for page in reader.pages) == 1, "vertical owner repeated or lost its image")
        picture = next(image for page in reader.pages for image in page.images).image.convert("RGB")
        require(picture.size == (12, 8) and picture.tobytes() == bytes((34, 119, 170)) * 96, "owner image pixels changed")
        for index, page in enumerate(reader.pages):
            logical = page_logical(page)
            headers = [value for value in logical if value.startswith("PAGE=")]
            require(headers and all(value == f"PAGE={index + 1} OF={len(reader.pages)}" for value in headers),
                    "header field resolved against a column instead of its final physical page")
        field_pages = [index + 1 for index, text in enumerate(texts) if "OP=" in re.sub(r"\s+", "", text)]
        require(len(field_pages) == 1 and f"OP={field_pages[0]}" in joined, "owner field lost original source or final page")
        links = annotations(reader)
        require(sum(link.get("uri") == "https://example.com/picture" for link in links) == 1, "owner picture link changed")
        require(sum(link.get("uri") == "https://example.com/vertical-owner" for link in links) > 15, "owner text links missing")
        fixtures.append((name, source))
    caller = consumer / "output/consumer-variable-vertical-spans.pdf"
    reader, _, joined = inspect(caller, "C", 40, (89.333, 178.667), (20, 20, 300, 160))
    require(joined.count("SIDE-A") == joined.count("SIDE-B") == 1, "published caller changed vertical neighbour sources")
    require(len(annotations(reader)) > 15, "published caller owner hyperlinks missing")
    fixtures.append(("caller", caller))
    tiles = []
    for name, source in fixtures:
        first, second = references(source, OUTPUT / name)
        require(len(first) == len(second), "independent renderer page counts differ")
        metrics = []
        for index, (left, right) in enumerate(zip(first, second, strict=True)):
            metric = difference(left, right, blur_radius=1)
            metric["uncoveredInkFractions"] = ink_coverage(left, right)
            require(max(metric["uncoveredInkFractions"]) < .001, "independent rendering lost visible table ink")
            metrics.append(metric)
            tiles.extend([(f"{name} Poppler {index + 1}", left), (f"{name} PDFium {index + 1}", right)])
        report[name] = {"pages": len(first), "renderDifferences": metrics}
    contact_sheet(tiles, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print("Variable vertical owners: source/anchor uniqueness, actual widths, final fields, images/links and dual rendering passed")


if __name__ == "__main__":
    main()
