# SPDX-License-Identifier: Apache-2.0
"""Independently check actual-width table geometry, final fields, links, sources and both renderers."""
import json
from pathlib import Path
import re

import pypdfium2 as pdfium
from pypdf import PdfReader
from verify_advanced_pdf import difference, require
from verify_pdf_raster import references
from verify_production import contact_sheet
from verify_semantic_tables import annotations, page_logical, table_borders
from verify_variable_columns import ink_coverage

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "examples/build/variable-tables"
OUTPUT = ROOT / "build/verification/variable-tables"


def logical_pages(path):
    document = pdfium.PdfDocument(path)
    try:
        result = []
        for index in range(len(document)):
            page = document[index]
            try:
                text = page.get_textpage()
                try:
                    result.append(text.get_text_bounded())
                finally:
                    text.close()
            finally:
                page.close()
        return result
    finally:
        document.close()


def inspect(name, pages, prefix, count):
    path = SOURCE / (name + ".pdf")
    reader = PdfReader(path, strict=True)
    text = logical_pages(path)
    require(len(reader.pages) == len(text) == pages, "variable table page count differs")
    require(re.findall(prefix + r"-\d{2}", "\n".join(text)) == [f"{prefix}-{i:02}" for i in range(1, count + 1)],
            "original table source was lost, repeated or reordered")
    boxes = table_borders(reader)
    for index, page in enumerate(reader.pages):
        headers = [value for value in page_logical(page) if value.startswith("PAGE=")]
        expected = f"PAGE={index + 1} OF={pages}"
        require(headers and all(value == expected for value in headers), "table fields use a logical column as a physical page")
        require(text[index].count(expected) == len(headers), "actual extracted field values differ from marked logical text")
    for box in boxes:
        require(box["x"] >= 22 - .003 and box["y"] >= 22 - .003
                and box["x"] + box["width"] <= 378.003 and box["y"] + box["height"] <= 198.003,
                "semantic table leaves its padded page region")
        if box["x"] < 140:
            require(box["x"] + box["width"] <= 134.003, "narrow table overlaps the column gap")
        else:
            require(box["x"] >= 150 - .003, "wide table overlaps the column gap")
    headers = [box for box in boxes if abs(box["y"] - 22) < .003]
    require({round(box["width"], 3) for box in headers} == {112.0, 228.0}, "table widths were scaled or reused across columns")
    return reader, text, boxes


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    rows, row_text, row_boxes = inspect("rows", 3, "ROW", 18)
    require([len([box for box in row_boxes if box["pageIndex"] == i and box["x"] < 140 and box["y"] > 22.003])
             for i in range(3)] == [3, 3, 1], "narrow row wrapping or final atomic picture differs")
    require([len([box for box in row_boxes if box["pageIndex"] == i and box["x"] > 140 and box["y"] > 22.003])
             for i in range(3)] == [6, 6, 0], "wide rows were not reflowed at their actual width")
    links = annotations(rows)
    for row in range(1, 19):
        selected = [link for link in links if link.get("uri") == f"https://example.com/row/{row}"]
        narrow = row <= 3 or 10 <= row <= 12
        require(len(selected) == (2 if narrow else 1), "link geometry does not follow actual wrapped source lines")
        require(all((link["x"] < 140) == narrow for link in selected), "link moved to the wrong physical column")
    pictures = [link for link in links if link.get("uri") == "https://example.com/picture"]
    require(len(pictures) == 1 and pictures[0]["pageIndex"] == 2, "atomic picture link is missing or repeated")
    require(abs(pictures[0]["width"] - 12) < .003 and abs(pictures[0]["height"] - 8) < .003,
            "picture link no longer matches the actual image")
    require([len(page.images) for page in rows.pages] == [0, 0, 1], "picture object is missing or repeated")
    require(sum(value.count("PICTURE") for value in row_text) == 1, "mixed-cell text is missing or repeated")

    merged, merged_text, merged_boxes = inspect("merged", 2, "MERGE", 12)
    require(re.findall(r"\bG[1-6]\b", "\n".join(merged_text)) == [f"G{i}" for i in range(1, 7)],
            "vertical source owners were duplicated or reordered")
    owners = [box for box in merged_boxes if abs(box["width"] - 28) < .003]
    require(len(owners) == 6 and [round(box["height"]) for box in owners] == [120, 48, 48, 48, 120, 48],
            "merged owner heights did not follow each physical column's wrapping")
    require(all(box["x"] == (22 if i in (0, 4) else 150) for i, box in enumerate(owners)),
            "transitive merged groups crossed region boundaries")

    nested, nested_text, nested_boxes = inspect("nested", 1, "LEAF", 5)
    require(re.findall(r"INNER-\d{2}", "\n".join(nested_text)) == [f"INNER-{i:02}" for i in range(1, 6)],
            "nested original table leaves differ")
    inner = [box for box in nested_boxes if round(box["width"]) in (106, 222)]
    require([round(box["width"]) for box in inner] == [106, 106, 222, 222, 222], "nested tables ignore actual parent padding or width")

    caller = Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip()) / "output/consumer-variable-tables.pdf"
    caller_text = logical_pages(caller)
    require(re.findall(r"CALL-\d{2}", "\n".join(caller_text)) == [f"CALL-{i:02}" for i in range(1, 11)],
            "isolated caller source order differs")
    caller_boxes = table_borders(PdfReader(caller, strict=True))
    require({round(box["width"], 3) for box in caller_boxes} == {89.333, 178.667}, "isolated caller did not use distinct real widths")

    tiles, report = [], {}
    for name, path in [(name, SOURCE / (name + ".pdf")) for name in ("rows", "merged", "nested")] + [("caller", caller)]:
        first, second = references(path, OUTPUT / name)
        require(len(first) == len(second), "independent renderer page counts differ")
        differences = []
        for index, (a, b) in enumerate(zip(first, second, strict=True)):
            delta = difference(a, b, blur_radius=1)
            delta["uncoveredInkFractions"] = ink_coverage(a, b)
            require(max(delta["uncoveredInkFractions"]) < .001, "independent renderers disagree on visible ink")
            differences.append(delta)
            tiles.extend([(f"{name} Poppler {index+1}", a), (f"{name} PDFium {index+1}", b)])
        report[name] = {"pages": len(first), "renderDifferences": differences}
    contact_sheet(tiles, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print("Variable tables: actual widths, sources, final fields, links, merged owners, nested content, caller and dual rendering passed")


if __name__ == "__main__":
    main()
