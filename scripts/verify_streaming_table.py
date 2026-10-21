# SPDX-License-Identifier: Apache-2.0
"""Check lazy-table final PDF source, repeated headers, geometry and every rendered page.

Expected rows and coordinates are independently declared below. Java counters are checked
against the PDFs; they do not supply expected geometry. No visual baseline is updated.
"""
from __future__ import annotations

import argparse
from importlib import metadata
import json
from pathlib import Path
import re

import pypdfium2 as pdfium
from pypdf import PdfReader

from verify_adr_form import contains, render
from verify_advanced_pdf import difference, require
from verify_production import contact_sheet
from verify_semantic_tables import close, table_borders

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "examples/build/streaming-table"
OUTPUT = ROOT / "build/verification/streaming-table/pdf-checks"
HEADERS = ("ROW-ID", "DESCRIPTION", "VALUE")


def expected_borders(per_page, details=True, header=True):
    expected = []
    row = 1
    for page_index, count in enumerate(per_page):
        top = 24
        heights = ([22] if header else []) + [22 + (16 if details and i % 7 == 0 else 0)
                                               for i in range(row, row + count)]
        row += count
        for height in heights:
            for left, width in ((24, 72), (96, 300), (396, 120)):
                expected.append({"pageIndex": page_index, "x": left, "y": top, "width": width, "height": height})
            top += height
        require(top <= 396, "independent fixture contract exceeds page body")
    return expected


def final_geometry(path, borders, margin=24):
    characters = 0
    with pdfium.PdfDocument(path) as document:
        for index in range(len(document)):
            page = document[index]
            text = page.get_textpage()
            try:
                cells = [cell for cell in borders if cell["pageIndex"] == index]
                for char_index in range(text.count_chars()):
                    if not text.get_text_range(char_index, 1, errors="ignore").strip():
                        continue
                    left, bottom, right, top = text.get_charbox(char_index)
                    box = left, page.get_height() - top, right, page.get_height() - bottom
                    require(margin - .6 <= box[0] <= box[2] <= page.get_width() - margin + .6 and
                            margin - .6 <= box[1] <= box[3] <= page.get_height() - margin + .6,
                            "streaming-table character leaves its physical page margins")
                    require(any(contains(cell, box) for cell in cells), "streaming-table character crosses a cell border")
                    characters += 1
            finally:
                text.close()
                page.close()
    return characters


def check_consumer(path):
    reader = PdfReader(path, strict=True)
    require(len(reader.pages) == 2, "consumer streaming-table page count differs")
    require(not reader.get_fields() and not any(page.get("/Annots") for page in reader.pages),
            "consumer streaming-table contains interactive data")
    expected = []
    source = 1
    for page_index, count in enumerate((8, 7)):
        page = reader.pages[page_index]
        close(page.mediabox.width, 300, "consumer page width differs")
        close(page.mediabox.height, 240, "consumer page height differs")
        text = page.extract_text() or ""
        require(text.count("STREAM-ID") == text.count("STREAM-VALUE") == 1, "consumer repeated headers differ")
        require(re.findall(r"CON-(\d{3})", text) == [f"{i:03}" for i in range(source, source + count)],
                "consumer row order or page allocation differs")
        require(re.findall(r"STREAM-CONSUMER-(\d+)", text) == [str(i) for i in range(source, source + count)],
                "consumer values lost or repeated")
        source += count
        top = 20
        for height in [22] + [21] * count:
            for left, width in ((20, 80), (100, 180)):
                expected.append({"pageIndex": page_index, "x": left, "y": top, "width": width, "height": height})
            top += height
    borders = table_borders(reader)
    require(len(borders) == 34, "consumer table border count differs")
    for actual, wanted in zip(borders, expected, strict=True):
        require(actual["pageIndex"] == wanted["pageIndex"], "consumer border page differs")
        for key in ("x", "y", "width", "height"):
            close(actual[key], wanted[key], "consumer table border coordinate differs: " + key)
    return {"pages": 2, "bodyRows": 15, "bodyRowsPerPage": [8, 7], "headersPerPage": 1,
            "cellBorders": len(borders), "characters": final_geometry(path, borders, margin=20)}


def check(path, per_page, header=True):
    reader = PdfReader(path, strict=True)
    require(len(reader.pages) == len(per_page), "streaming-table physical page count differs")
    require(not reader.get_fields() and not reader.outline and not any(page.get("/Annots") for page in reader.pages),
            "static streaming-table fixture has unexpected interactive data")
    texts = []
    for index, page in enumerate(reader.pages):
        close(page.mediabox.width, 540, "streaming-table page width differs")
        close(page.mediabox.height, 420, "streaming-table page height differs")
        text = page.extract_text() or ""
        texts.append(text)
        for token in HEADERS:
            # VALUE appears as a prefix in body data; match the complete header token.
            matches = re.findall(r"(?<![A-Z0-9-])" + token + r"(?![A-Z0-9-])", text)
            require(len(matches) == int(header), f"header missing/repeated on page {index + 1}: {token}")
    joined = "\n".join(texts)
    rows = sum(per_page)
    require(re.findall(r"ROW-(\d{5})", joined) == [f"{i:05}" for i in range(1, rows + 1)],
            "body row identifiers lost, repeated or reordered")
    require(re.findall(r"VALUE-(\d{5})", joined) == [f"{i:05}" for i in range(1, rows + 1)],
            "body row values lost, repeated or reordered")
    first = 1
    for text, count in zip(texts, per_page, strict=True):
        require(re.findall(r"ROW-(\d{5})", text) == [f"{i:05}" for i in range(first, first + count)],
                "whole-row physical page allocation differs")
        first += count
    compact = "".join(joined.split())
    for i in range(1, rows + 1):
        require(compact.count(f"记录{i:05}：逐行生成，完整行跨页。") == 1,
                "Chinese source description lost or repeated")
    require(re.findall(r"DETAIL-(\d{5})", joined) == [f"{i:05}" for i in range(7, rows + 1, 7)],
            "multi-line row details lost, repeated or reordered")
    borders = table_borders(reader)
    expected = expected_borders(per_page, header=header)
    require(len(borders) == len(expected), "streaming-table border count differs")
    for actual, wanted in zip(borders, expected, strict=True):
        require(actual["pageIndex"] == wanted["pageIndex"], "border physical page differs")
        for key in ("x", "y", "width", "height"):
            close(actual[key], wanted[key], "streaming-table border coordinate differs: " + key)
    result = {"pages": len(reader.pages), "bodyRows": rows, "bodyRowsPerPage": per_page,
              "headersPerPage": int(header), "cellBorders": len(borders), "characters": final_geometry(path, borders)}
    summary = json.loads(path.with_suffix(".json").read_text(encoding="utf-8"))
    source_cells = 3 * (rows + int(header))
    source_points = (sum(map(len, HEADERS)) if header else 0)
    for i in range(1, rows + 1):
        number = f"{i:05}"
        description = f"记录{number}：逐行生成，完整行跨页。" + (f"\nDETAIL-{number}" if i % 7 == 0 else "")
        source_points += len("ROW-" + number) + len(description) + len("VALUE-" + number)
    for key, wanted in {"bodyRows": rows, "pages": len(per_page), "sourceCells": source_cells,
                        "sourceCodePoints": source_points, "outputLines": 3 * (rows + len(per_page) * int(header)) + rows // 7,
                        "diagnostics": 14 * rows, "fontFallbackInfoDiagnostics": 14 * rows,
                        "sourceOpens": 1, "sourceCloses": 1, "sourceNextCalls": rows + 1,
                        "outputBytes": path.stat().st_size}.items():
        require(summary[key] == wanted, "streaming-table summary differs: " + key)
    require(summary["commands"] >= len(borders), "streaming-table command summary is impossible")
    result["summary"] = summary
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--consumer-build-directory", type=Path)
    args = parser.parse_args()
    versions = {name: metadata.version(name) for name in ("pypdf", "pypdfium2", "Pillow")}
    require(versions == {"pypdf": "6.10.0", "pypdfium2": "5.13.0", "Pillow": "12.3.0"},
            "use the pinned project verification environment")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    fixtures = [("streaming", FIXTURES / "streaming.pdf", [14, 14, 14, 13], True, False),
                ("empty-header", FIXTURES / "empty-header.pdf", [0], True, False),
                ("empty-plain", FIXTURES / "empty-plain.pdf", [0], False, False)]
    if args.consumer_build_directory is not None:
        consumer = args.consumer_build_directory
        if not consumer.is_absolute():
            consumer = ROOT / consumer
        fixtures.append(("consumer", consumer / "output/consumer-streaming-table.pdf", [8, 7], True, True))
    report = {"tools": versions, "scope": "all final fixture pages; independent PDFium geometry and Poppler/PDFium rendering",
              "fixtures": {}}
    contacts = []
    for name, path, per_page, header, consumer in fixtures:
        result = check_consumer(path) if consumer else check(path, per_page, header)
        poppler, pdfium_pages = render(path, OUTPUT / name)
        require(len(poppler) == len(pdfium_pages) == result["pages"], "streaming-table renderer page count differs")
        differences = [difference(a, b, 2) for a, b in zip(poppler, pdfium_pages, strict=True)]
        require(all(item["meanAbsoluteChannelError"] < 4 and item["changedPixelFraction"] < .06 for item in differences),
                "streaming-table independent renderers disagree")
        result["rendererDifferences"] = differences
        report["fixtures"][name] = result
        contacts.extend((f"{name} page {index + 1}", page) for index, page in enumerate(poppler))
    contact_sheet(contacts, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
