# SPDX-License-Identifier: Apache-2.0
"""Check parent-relative automatic widths, nested continuation fields, links and OOXML independently."""
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from pypdf import PdfReader
from verify_advanced_pdf import difference, require
from verify_pdf_raster import references
from verify_production import contact_sheet
from verify_semantic_tables import annotations, page_logical, table_borders
from verify_variable_columns import ink_coverage
from verify_variable_tables import logical_pages

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "examples/build/nested-automatic-tables"
OUTPUT = ROOT / "build/verification/nested-automatic-tables"
W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"


def check_docx(path, minimum, maximum):
    with ZipFile(path) as archive:
        root = ET.fromstring(archive.read("word/document.xml"))
    tables = root.findall(".//" + W + "tbl")
    require(len(tables) == 2, "OOXML lost the separate parent and child grids")
    columns = tables[1].find(W + "tblGrid")
    auto = list(columns)[0]
    # Resolve the actual project namespace from explicit attribute local names.
    values = {name.rsplit("}", 1)[-1]: value for name, value in auto.attrib.items()}
    require(float(values["autoMin"]) == minimum and float(values["autoMax"]) == maximum,
            "nested automatic source bounds were lost in OOXML metadata")
    require(len(list(columns)) == 2, "nested automatic grid column count differs")
    return root, tables


def inspect(name, total):
    path = SOURCE / f"{name}.pdf"
    reader = PdfReader(path, strict=True)
    text = logical_pages(path)
    require(len(reader.pages) == len(text) == total, "nested automatic page count differs")
    require(re.findall(r"ITEM-\d{2}", "\n".join(text)) == [f"ITEM-{index:02}" for index in range(1, 7)],
            "nested sources were lost, duplicated or reordered during reflow")
    require(re.findall(r"T-\d{2}", "\n".join(text)) == [f"T-{index:02}" for index in range(1, 7)],
            "completed short cells repeated on continuation")
    for index, page in enumerate(reader.pages):
        expected = f"NESTED P={index+1} T={total}"
        require(page_logical(page).count(expected) == 1 and text[index].count(expected) == 1,
                "nested repeated header fields use an incorrect final physical page")
    boxes = table_borders(reader)
    width = float(reader.pages[0].mediabox.width)
    for box in boxes:
        require(box["x"] >= 15.997 and box["y"] >= 15.997 and box["x"] + box["width"] <= width - 15.997
                and box["y"] + box["height"] <= 224.003, "nested table leaves its parent page region")
    inner = [box for box in boxes if abs(box["x"] - 20) < .003]
    body = [box for box in inner if abs(box["width"] - 40) > .003]
    automatic = min(box["width"] for box in body)
    require(24 <= automatic <= 90, "automatic child column leaves its explicit bounds")
    if name == "narrow":
        require(abs(automatic - 60) < .003, "automatic width used the entire page instead of its parent content area")
    else:
        require(automatic > 60, "wide nested table did not retain its measured preferred width")
    links = annotations(reader)
    for row in range(1, 7):
        selected = [value for value in links if value.get("uri") == f"https://example.com/nested-auto/{row}"]
        require(len(selected) == (2 if name == "wide" else 3), "nested source link rectangles differ from actual wrapped lines")
        require(all(abs(value["x"] - 22) < .003 for value in selected), "nested links lost the parent-cell translation")
    root, tables = check_docx(SOURCE / f"{name}.docx", 24, 90)
    require(len(tables[1].findall(W + "tr")) == 7, "OOXML nested source rows differ")
    require(len(tables[1].findall(".//" + W + "gridSpan")) == 1, "OOXML lost the merged nested header")
    require(not tables[1].findall(".//" + W + "tblHeader"), "native PAGE fixture incorrectly repeats its Word header")
    instructions = [node.get(W + "instr").strip() for node in root.findall(".//" + W + "fldSimple")]
    require(instructions == ["PAGE", "NUMPAGES"], "nested automatic DOCX fields became static text")
    return automatic


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    widths = {name: inspect(name, pages) for name, pages in [("wide", 1), ("narrow", 2)]}
    require(widths["wide"] > widths["narrow"], "automatic child width did not shrink in the narrower parent")
    caller = Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip()) / "output"
    pdf = caller / "consumer-nested-auto.pdf"
    caller_reader = PdfReader(pdf, strict=True)
    # PDFium marks a line-ending hyphen specially; use the original ToUnicode extraction here.
    require(len(caller_reader.pages) == 1 and "".join(caller_reader.pages[0].extract_text().split()) == "CALLER-AUTOTAIL",
            "published nested caller content differs")
    check_docx(caller / "consumer-nested-auto.docx", 24, 60)
    tiles, report = [], {"actualAutomaticWidths": widths}
    for name, path in [("wide", SOURCE / "wide.pdf"), ("narrow", SOURCE / "narrow.pdf"), ("caller", pdf)]:
        first, second = references(path, OUTPUT / name)
        require(len(first) == len(second), "independent renderer page counts differ")
        comparisons = []
        for index, (a, b) in enumerate(zip(first, second, strict=True)):
            delta = difference(a, b, blur_radius=1)
            delta["uncoveredInkFractions"] = ink_coverage(a, b)
            require(max(delta["uncoveredInkFractions"]) < .001, "independent renderers disagree on nested automatic ink")
            comparisons.append(delta)
            tiles.extend([(f"{name} Poppler {index+1}", a), (f"{name} PDFium {index+1}", b)])
        report[name] = {"pages": len(first), "renderDifferences": comparisons}
    contact_sheet(tiles, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print("Nested automatic tables: actual parent widths, merged headers, recursive continuations, fields, links, native DOCX, published caller and dual rendering passed")


if __name__ == "__main__":
    main()
