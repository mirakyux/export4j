# SPDX-License-Identifier: Apache-2.0
"""Check final single-pass fields, source order, link/image geometry and independent renderers."""
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
SOURCE = ROOT / "examples/build/streaming-fields"
OUTPUT = ROOT / "build/verification/streaming-fields"


def inspect(path, total, header):
    reader = PdfReader(path, strict=True)
    text = logical_pages(path)
    require(len(reader.pages) == len(text) == total, "streaming field page count differs")
    for index, page in enumerate(reader.pages):
        expected = header(index + 1)
        logical = "\n".join(page_logical(page))
        require(logical.count(expected) == 1 and text[index].count(expected) == 1,
                "final field ink or ActualText uses an incorrect physical page or total")
        require("\ufffc" not in logical.replace("\ufffc P=", " P="), "an unresolved field escaped to output")
    for box in table_borders(reader):
        require(box["x"] >= 9.997 and box["y"] >= 9.997 and box["x"] + box["width"] <= 190.003
                and box["y"] + box["height"] <= 90.003, "field table escapes page margins")
    return reader, text


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    reader, text = inspect(SOURCE / "fields.pdf", 3, lambda page: f" P={page} T=3")
    require(re.findall(r"R\d{2}", "\n".join(text)) == [f"R{index:02}" for index in range(1, 8)],
            "moved source rows were lost, repeated or reordered")
    for page in range(3):
        expected = range(page * 3 + 1, min(page * 3 + 4, 8))
        require(re.findall(r"R\d{2} P=\d+", text[page]) == [f"R{index:02} P={page + 1}" for index in expected],
                "body field was resolved before its row moved to its actual page")
    links = annotations(reader)
    require(len(links) == 10 and [len(page.images) for page in reader.pages] == [1, 1, 1],
            "repeated image or unique body link count differs")
    for page in range(3):
        picture = [value for value in links if value.get("uri") == "https://example.com/fields/logo" and value["pageIndex"] == page]
        require(len(picture) == 1 and abs(picture[0]["width"] - 12) < .003 and abs(picture[0]["height"] - 8) < .003,
                "repeated field header image link has incorrect geometry")
    for row in range(1, 8):
        selected = [value for value in links if value.get("uri") == f"https://example.com/fields/row/{row}"]
        require(len(selected) == 1 and selected[0]["pageIndex"] == (row - 1) // 3,
                "body text link did not follow actual page placement")
    split, split_text = inspect(SOURCE / "split.pdf", 2, lambda page: f" P={page} T=2")
    require("SPLIT P=" not in split_text[0] and split_text[1].count("SPLIT P=2") == 1,
            "continued field used the source row's first page")
    require([len(page.images) for page in split.pages] == [1, 1], "split header image repetition differs")
    logical = [value for page in split.pages for value in page_logical(page)]
    require([value for value in logical if value in ("A", "B", "C")] == ["A", "B", "C"],
            "split source lines were lost or repeated")
    _, unknown_text = inspect(SOURCE / "unknown.pdf", 2, lambda page: f"UNKNOWN P={page}")
    require(re.findall(r"UNKNOWN-\d", "\n".join(unknown_text)) == [f"UNKNOWN-{index}" for index in range(1, 5)],
            "unknown-total source order differs")
    expected_counts = {
        "fields": {"pages": 3, "rows": 7, "sourceCells": 8, "outputLines": 10, "reads": 8, "closes": 1},
        "split": {"pages": 2, "rows": 1, "sourceCells": 2, "outputLines": 6, "reads": 2, "closes": 1},
        "unknown": {"pages": 2, "rows": 4, "sourceCells": 5, "outputLines": 6, "reads": 5, "closes": 1},
    }
    for name, expected in expected_counts.items():
        require(json.loads((SOURCE / f"{name}-counts.json").read_text()) == expected,
                "streaming source lifecycle or presentation counters differ")
    caller = Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip()) / "output/consumer-streaming-fields.pdf"
    _, caller_text = inspect(caller, 2, lambda page: f"CALLER P={page} T=2")
    require(re.findall(r"CALLER-\d", "\n".join(caller_text)) == [f"CALLER-{index}" for index in range(1, 5)],
            "published caller source order differs")
    tiles, report = [], {}
    for name, path in [(name, SOURCE / f"{name}.pdf") for name in expected_counts] + [("caller", caller)]:
        first, second = references(path, OUTPUT / name)
        require(len(first) == len(second), "independent renderer page counts differ")
        comparisons = []
        for index, (a, b) in enumerate(zip(first, second, strict=True)):
            delta = difference(a, b, blur_radius=1)
            delta["uncoveredInkFractions"] = ink_coverage(a, b)
            require(max(delta["uncoveredInkFractions"]) < .001, "independent renderers disagree on final field ink")
            comparisons.append(delta)
            tiles.extend([(f"{name} Poppler {index+1}", a), (f"{name} PDFium {index+1}", b)])
        report[name] = {"pages": len(first), "renderDifferences": comparisons}
    contact_sheet(tiles, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print("Streaming fields: actual pages/totals, split fields, unique sources, bounded lifecycle, links/images, published caller and dual rendering passed")


if __name__ == "__main__":
    main()
