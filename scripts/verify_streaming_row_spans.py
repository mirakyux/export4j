# SPDX-License-Identifier: Apache-2.0
"""Independently verify bounded row-group output, ownership, geometry and both PDF renderers."""
import argparse
import json
from pathlib import Path

from pypdf import PdfReader
from verify_advanced_pdf import difference, require
from verify_pdf_raster import references
from verify_production import contact_sheet
from verify_semantic_tables import annotations, table_borders
from verify_variable_tables import logical_pages

ROOT = Path(__file__).resolve().parents[1]


def box_exists(boxes, page, x, y, width, height):
    return any(box["pageIndex"] == page and all(abs(box[key] - value) < .003 for key, value in
               (("x", x), ("y", y), ("width", width), ("height", height))) for box in boxes)


def inspect(source):
    counts = json.loads((source / "counts.json").read_text())
    require(counts == {"pages": 3, "rows": 7, "sourceCells": 12, "outputLines": 16,
                       "reads": 8, "closes": 1, "lateFailureProtected": True},
            "streaming row group counters or late atomic failure differ")
    path = source / "groups.pdf"
    reader = PdfReader(path, strict=True)
    text = logical_pages(path)
    require(len(reader.pages) == len(text) == 3, "row groups have incorrect physical pagination")
    for value in text:
        require(value.count("LEFT") == value.count("RIGHT") == 1, "header not repeated exactly once")
    require("PRE-A" in text[0] and "PRE-B" in text[0] and "OWNER" not in text[0],
            "connected owner started before its group moved")
    require(all(value in text[1] for value in ("OWNER", "B3 P=2", "B4", "A5", "B5")),
            "group content or field did not follow its actual page")
    require("FULL P=3" in text[2], "fully covered row lost its owner or actual page value")
    joined = "\n".join(text)
    for token in ("PRE-A", "PRE-B", "OWNER", "B3 P=2", "B4", "A5", "B5", "FULL P=3"):
        require(joined.count(token) == 1, "owner source was lost or duplicated")
    boxes = table_borders(reader)
    for args in ((1, 10, 25, 50, 30), (1, 60, 25, 50, 15), (1, 60, 40, 50, 15),
                 (1, 10, 55, 50, 15), (2, 10, 25, 100, 15)):
        require(box_exists(boxes, *args), "merged owner or covered physical column has incorrect geometry")
    for box in boxes:
        require(box["x"] >= 9.997 and box["y"] >= 9.997 and box["x"] + box["width"] <= 110.003
                and box["y"] + box["height"] <= 70.003, "row group leaves page margins")
    links = annotations(reader)
    owner = [link for link in links if link["uri"] == "https://example.com/groups/owner"]
    picture = [link for link in links if link["uri"] == "https://example.com/groups/image"]
    require(len(owner) == len(picture) == 1 and owner[0]["pageIndex"] == 1 and picture[0]["pageIndex"] == 2,
            "owner or image link was duplicated or bound to its pre-move page")
    require(abs(picture[0]["width"] - 8) < .003 and abs(picture[0]["height"] - 8) < .003,
            "merged image link does not match final inline image bounds")
    require([len(page.images) for page in reader.pages] == [0, 0, 1], "merged image source repeated or disappeared")
    image = reader.pages[2].images[0].image.convert("RGB")
    require(image.size == (2, 2) and set(image.get_flattened_data()) == {(34, 102, 170)},
            "merged image pixels differ from explicit resource")
    chained = PdfReader(source / "transitive.pdf", strict=True)
    require(len(chained.pages) == 1 and all(logical_pages(source / "transitive.pdf")[0].count(value) == 1
            for value in ("R1", "R2", "L3")), "transitive group lost physical row owners")
    require(box_exists(table_borders(chained), 0, 10, 25, 50, 15)
            and box_exists(table_borders(chained), 0, 60, 40, 50, 15), "transitive owner height distribution differs")
    return counts


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--consumer-build-directory", type=Path)
    args = parser.parse_args()
    build = args.consumer_build_directory or Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip())
    source = build / "output/streaming-row-spans"
    output = ROOT / "build/verification/streaming-row-spans"
    output.mkdir(parents=True, exist_ok=True)
    counts = inspect(source)
    tiles, metrics = [], {}
    for name, pages in (("groups", 3), ("transitive", 1)):
        first, second = references(source / f"{name}.pdf", output / name)
        require(len(first) == len(second) == pages, "independent row-group renderer page counts differ")
        comparisons = []
        for index, (a, b) in enumerate(zip(first, second, strict=True)):
            delta = difference(a, b, blur_radius=1)
            require(delta["meanAbsoluteChannelError"] < 3 and delta["changedPixelFraction"] < .07,
                    "independent row-group renderers disagree")
            comparisons.append(delta)
            tiles.extend([(f"{name} Poppler {index + 1}", a), (f"{name} PDFium {index + 1}", b)])
        metrics[name] = comparisons
    capacity = []
    for path in sorted(source.glob("capacity-*.json")):
        result = json.loads(path.read_text())
        require(result["reads"] == result["rows"] + 1 and result["closes"] == 1 and result["maxBufferedRows"] == 3,
                "capacity source lifecycle or bounded group differs")
        capacity.append(result)
    contact_sheet(tiles, output)
    report = {"counts": counts, "pages": 4, "rendering": metrics, "capacityRuns": capacity, "checks": "passed"}
    (output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
