# SPDX-License-Identifier: Apache-2.0
"""Independently verify semantic table region continuation and all final PDF pages.

Run after :examples:semanticTableFlow in the pinned project Python environment. Expected
source, page/column geometry and final values are declared here; Java manifests only
cross-check those contracts. No visual baseline is created or updated.
"""
from __future__ import annotations

import argparse
from importlib import metadata
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

from pypdf import PdfReader
from verify_adr_form import render
from verify_advanced_pdf import difference, require
from verify_inline_images import compose, image_boxes
from verify_multi_floats import pdf_geometry, point
from verify_production import actual_text, contact_sheet
from verify_semantic_tables import annotations, check_characters, close, page_logical, table_borders

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "examples/build/semantic-table-flow"
OUTPUT = ROOT / "build/verification/semantic-table-flow"
TOLERANCE = .003
HEADERS = {prefix: f"H-{prefix} P=\ufffc N=\ufffc TO=\ufffc" for prefix in ("REG", "COL", "SPL")}
# Physical page, x, y, width, height, body source numbers. Header and body rows are 24 pt.
REGIONS = [(1, 36, 128, 348, 192, list(range(1, 8))),
           (2, 36, 36, 348, 288, list(range(8, 19)))]
COLUMNS = [(1, 27, 147, 182, 240, list(range(1, 10))),
           (1, 231, 147, 182, 240, list(range(10, 19))),
           (2, 27, 27, 182, 144, list(range(19, 24)))]
SPLIT = [(0, 29, 101, 362, 228, list(range(1, 12))),
         (1, 29, 29, 362, 300, list(range(12, 27))),
         (2, 29, 29, 362, 210, list(range(27, 37)))]


def split_source_lines():
    lines = []
    for number in range(1, 37):
        line = f"SPL-{number:02}"
        if number in (4, 14, 29):
            line += " P=\ufffc TO=\ufffc"
        if number in (12, 28):
            line += f" OPEN{number} \ufffc"
        if number == 22:
            line += " a\u0301"
        lines.append(line)
    return lines


def resolved_split(number, page):
    line = split_source_lines()[number - 1]
    if number in (4, 14, 29):
        line = line.replace(" P=\ufffc TO=\ufffc", f" P={page + 1} TO=1")
    return line


def tokens(reader):
    """Simple text remains PDF text; shaped/field text retains marked logical lines."""
    result = []
    for page in reader.pages:
        result.append((page.extract_text() or "") + "\n" + "\n".join(page_logical(page)))
    return result


def expected_fields(prefix, fixtures, pages, target):
    lines = []
    anchors = []
    for page, x, y, _, _, body in fixtures:
        header = f"H-{prefix} P={page + 1} N={pages} TO={target}"
        lines.append(header)
        anchors.append((page, x + 3, y + 3, header))
        if prefix == "SPL":
            for index, number in enumerate(body):
                source = resolved_split(number, page)
                lines.append(source)
                anchors.append((page, x + 3, y + 27 + index * 18, source))
    return lines, anchors


def check_borders(reader, prefix, fixtures):
    expected = []
    for page, x, y, width, height, body in fixtures:
        expected.append((page, x, y, width, 24))
        if prefix == "SPL":
            expected.append((page, x, y + 24, width, height - 24))
        else:
            expected.extend((page, x, y + 24 + index * 24, width, 24) for index in range(len(body)))
    borders = table_borders(reader)
    require(len(borders) == len(expected), "cell borders lost, duplicated or painted before continuation")
    for actual, wanted in zip(borders, expected, strict=True):
        for key, value in zip(("pageIndex", "x", "y", "width", "height"), wanted, strict=True):
            close(actual[key], value, f"final PDF table cell {key} differs")
    return borders


def check_references(reader, prefix, fixtures, target_page, target_number, pages):
    links = annotations(reader)
    internal = [item for item in links if "targetPageIndex" in item]
    contents = [item for item in internal if prefix != "SPL" and item["pageIndex"] == 0]
    expected_count = len(fixtures) + (3 if prefix == "SPL" else 2)
    require(len(internal) == expected_count, "reference or contents link lost or repeated")
    if prefix != "SPL":
        require([item["targetPageIndex"] for item in contents] == [1, target_page], "contents link offset differs")
        close(contents[0]["destinationX"], fixtures[0][1], "contents table destination x differs")
        close(contents[0]["destinationY"], fixtures[0][2], "contents table destination y differs")
        outlines = [item for item in reader.outline if isinstance(item, dict)]
        require(len(outlines) == 2 and [reader.get_destination_page_number(item) for item in outlines] == [1, target_page],
                "bookmarks do not use first presentation and final section offset")
    references = [item for item in internal if item not in contents]
    wanted_lines = [(page, x + 3, y + 3, width - 6) for page, x, y, width, _, _ in fixtures]
    if prefix == "SPL":
        for page, x, y, width, _, body in fixtures:
            wanted_lines.extend((page, x + 3, y + 27 + body.index(number) * 18, width - 6)
                                for number in (4, 14, 29) if number in body)
    for page, x, y, width in wanted_lines:
        found = [link for link in references if link["pageIndex"] == page and abs(link["y"] - y) < TOLERANCE
                 and x < link["x"] < x + width]
        require(len(found) == 1, "reference field is not on its actual repeated/split source line")
        require(found[0]["targetPageIndex"] == target_page, "reference field has wrong physical target")
        close(found[0]["destinationX"], 24, "reference target x differs")
        close(found[0]["destinationY"], 24, "reference target y differs")
        close(found[0]["width"], 14, "reference reserve width differs")
        close(found[0]["height"], 18, "reference line box differs")
        require(found[0]["x"] > x, "reference link was placed at the whole cell origin")
    expected, anchors = expected_fields(prefix, fixtures, pages, target_number)
    require(actual_text(reader) == expected, "final header/body fields lost, reordered or resolved before final page offset")
    _, logical, _ = pdf_geometry(reader, 18)
    require([line["source"] for line in logical] == expected, "painted field anchor order differs")
    for actual, (page, x, y, source) in zip(logical, anchors, strict=True):
        require(actual["page"] == page and actual["source"] == source, "logical anchor uses wrong page/source")
        close(actual["x"], x, "field/cell anchor x differs")
        close(actual["y"], y, "field/cell anchor y differs")
    return links, len(expected)


def blue_ink(reader):
    """Bounds of the original hyperlink-colored painted paths, independently of annotations."""
    result = []
    blue = (.133333, .266667, .533333)
    for page_index, page in enumerate(reader.pages):
        matrix, color, stack, path = (1, 0, 0, 1, 0, 0), (0, 0, 0), [], []
        ink = []
        for operands, operator in page.get_contents().operations:
            if operator == b"q":
                stack.append((matrix, color))
            elif operator == b"Q":
                matrix, color = stack.pop()
            elif operator == b"cm":
                matrix = compose(matrix, tuple(map(float, operands)))
            elif operator == b"rg":
                color = tuple(map(float, operands))
            elif operator in (b"m", b"l", b"c"):
                coordinates = list(map(float, operands))
                path.extend(point(matrix, *coordinates[index:index + 2]) for index in range(0, len(coordinates), 2))
            elif operator == b"re":
                x, y, width, height = map(float, operands)
                path.extend(point(matrix, xx, yy) for xx in (x, x + width) for yy in (y, y + height))
            elif operator in (b"f", b"f*"):
                if all(abs(a - b) < .00001 for a, b in zip(color, blue, strict=True)):
                    ink.extend(path)
                path = []
            elif operator in (b"n", b"S", b"B", b"B*"):
                path = []
        require(not stack, "unbalanced colored ink graphics state")
        if ink:
            xs, ys = zip(*ink, strict=True)
            result.append({"pageIndex": page_index, "x": min(xs), "right": max(xs),
                           "y": float(page.mediabox.height) - max(ys),
                           "bottom": float(page.mediabox.height) - min(ys)})
    return result


def check_external(reader, prefix, links):
    images = image_boxes(reader)
    external = [link for link in links if "uri" in link]
    if prefix != "SPL":
        require(not external and not images, "ordinary fixture unexpectedly has external links or images")
        return []
    require(len(external) == 4 and len(images) == 2, "split-cell text/image link or image lost or repeated")
    painted = blue_ink(reader)
    require(len(painted) == 2, "colored source hyperlink ink missing or duplicated")
    for number, image in zip((12, 28), images, strict=True):
        expected_page = 1 if number == 12 else 2
        require(image["page"] == expected_page and image["pixels"] == [8, 8], "split-cell image page or pixels differ")
        close(image["width"], 12, "inline image width differs")
        close(image["height"], 12, "inline image height differs")
        picture = [link for link in external if link["uri"] == f"https://example.com/flow-image-{number}"]
        text = [link for link in external if link["uri"] == f"https://example.com/flow-text-{number}"]
        require(len(picture) == len(text) == 1, "source URI link missing or duplicated")
        require(picture[0]["pageIndex"] == text[0]["pageIndex"] == expected_page, "link did not follow source fragment")
        for key in ("x", "y", "width", "height"):
            close(picture[0][key], image[key], f"image link differs from final image {key}")
        ink = [item for item in painted if item["pageIndex"] == expected_page]
        require(len(ink) == 1, "source hyperlink ink on wrong physical page")
        close(text[0]["x"], ink[0]["x"], "text link misses final painted ink left")
        close(text[0]["x"] + text[0]["width"], ink[0]["right"], "text link misses final underline right")
        close(text[0]["height"], 18, "source text link no longer uses fixed line box")
        require(text[0]["y"] <= ink[0]["y"] and ink[0]["bottom"] <= text[0]["y"] + 18,
                "text hyperlink does not contain its final printed ink")
        require(text[0]["x"] + text[0]["width"] <= image["x"] + TOLERANCE,
                "source text hyperlink overlaps adjacent image")
    return images


def source_contract(prefix, count):
    source = [HEADERS[prefix]]
    if prefix == "SPL":
        source.append("\n".join(split_source_lines()))
    else:
        source.extend(f"{prefix}-{index:02}" for index in range(1, count + 1))
    return source


def check_manifest(name, prefix, fixtures, pages, offset, count):
    manifest = json.loads((FIXTURES / f"{name}-results.json").read_text(encoding="utf-8"))
    sources = json.loads((FIXTURES / f"{name}-source.json").read_text(encoding="utf-8"))
    expected_source = source_contract(prefix, count)
    require([item["source"] for item in sources] == expected_source, "manifest differs from independent original source")
    require([item["row"] for item in sources] == list(range(len(sources))), "source row identity differs")
    table_id = {"REG": "region-table", "COL": "column-table", "SPL": "split-table"}[prefix]
    for cell in sources:
        require(cell["tableId"] == table_id and cell["column"] == 0, "original table/column identity differs")
        require(cell["sourceUtf16Length"] == len(cell["source"].encode("utf-16-le")) // 2, "original UTF-16 size differs")
        objects = [index for index, value in enumerate(cell["source"]) if value == "\ufffc"]
        image_offsets = []
        if prefix == "SPL" and cell["row"] == 1:
            image_offsets = [match.end() for match in re.finditer(r"OPEN(?:12|28) ", cell["source"])]
        require(cell["imageIndices"] == image_offsets and cell["fieldIndices"] == [index for index in objects if index not in image_offsets],
                "original field/image UTF-16 positions differ")
    require(manifest["pages"] == pages and manifest["bodyPageOffset"] == offset, "manifest page/offset differs")
    require(len(manifest["placements"]) == len(fixtures), "semantic fragment count differs")
    for index, (actual, fixture) in enumerate(zip(manifest["placements"], fixtures, strict=True)):
        page, x, y, width, height, body = fixture
        require(actual["tableId"] == table_id and actual["fragmentIndex"] == index
                and actual["fragmentCount"] == len(fixtures) and actual["pageIndex"] == page,
                "source presentation identity or page differs")
        for key, value in zip(("x", "y", "width", "height"), (x, y, width, height), strict=True):
            close(actual[key], value, f"manifest placement {key} differs from fixed PDF contract")
        expected_rows = [0, 1] if prefix == "SPL" else [0, *body]
        require([row["row"] for row in actual["rows"]] == expected_rows, "source rows lost, repeated or reordered")
        for row_index, row in enumerate(actual["rows"]):
            require(row["header"] == (row_index == 0) and row["repeatedHeader"] == (row_index == 0 and index > 0),
                    "header repetition flag differs")
            require(row["continuedRow"] == (prefix == "SPL" and row_index == 1 and index > 0), "split source continuation flag differs")
            cell = row["cells"][0]
            expected_first = body[0] - 1 if prefix == "SPL" and row_index == 1 else 0
            require(cell["column"] == 0 and cell["firstLineIndex"] == expected_first, "first complete source line differs")
            if prefix == "SPL" and row_index == 1:
                starts, position = [], 0
                for line in split_source_lines():
                    starts.append(position)
                    position += len(line) + 1
                require(len(cell["lines"]) == len(body), "complete split line count differs")
                for line, number in zip(cell["lines"], body, strict=True):
                    start = starts[number - 1]
                    end = start + len(split_source_lines()[number - 1])
                    require(line["start"] == start and line["end"] in (end, end + int(number < 36)),
                            "split-cell original UTF-16 line range changed")
                    require(line["logical"] == resolved_split(number, page), "split-cell logical source changed")
            else:
                require(len(cell["lines"]) == 1 and cell["lines"][0]["start"] == 0
                        and cell["lines"][0]["end"] == len(expected_source[row["row"]]), "whole-row UTF-16 range changed")
    return sum(len(row["cells"]) for item in manifest["placements"] for row in item["rows"])


def check_svg(name, reader, count_images):
    svg = ET.parse(FIXTURES / f"{name}-preview.svg").getroot()
    ns = "{http://www.w3.org/2000/svg}"
    require(len(svg.findall(f"{ns}svg")) == len(reader.pages), "static SVG page count differs")
    logical = [item.get("aria-label") for item in svg.iter(f"{ns}g") if item.get("aria-label") is not None]
    marked = actual_text(reader)
    require([value for value in logical if value in set(marked)] == marked, "static SVG final field/source order differs")
    require(len(svg.findall(f".//{ns}image")) == count_images, "static SVG image count differs")
    require(not svg.findall(f".//{ns}a"), "static SVG preview unexpectedly retains interactions")


def check_fixture(name, prefix, fixtures, pages, width, height, offset, count, target_page, target_number):
    path = FIXTURES / f"{name}.pdf"
    reader = PdfReader(path)
    require(len(reader.pages) == pages and not reader.get_fields(), "page count or static-document contract differs")
    for page in reader.pages:
        close(page.mediabox.width, width, "physical page width differs")
        close(page.mediabox.height, height, "physical page height differs")
    contents = tokens(reader)
    text = "\n".join(contents)
    found = re.findall(rf"(?<!H-){prefix}-(\d{{2}})", text)
    require(found == [f"{number:02}" for number in range(1, count + 1)], "original body rows/lines lost, repeated or reordered")
    for page, _, _, _, _, body in fixtures:
        for number in body:
            require(contents[page].count(f"{prefix}-{number:02}") == 1, "body source moved to wrong page or column")
    for label in {"REG": ("REGION-PREFIX", "REGION-TARGET"), "COL": ("COLUMN-PREFIX", "COLUMN-TARGET", "COLUMN-END"),
                  "SPL": ("SPLIT-PREFIX", "SPLIT-END")}[prefix]:
        require(text.count(label) == 1, "surrounding content missing or duplicated")
    borders = check_borders(reader, prefix, fixtures)
    links, lines = check_references(reader, prefix, fixtures, target_page, target_number, pages)
    images = check_external(reader, prefix, links)
    source_cells = check_manifest(name, prefix, fixtures, pages, offset, count)
    require(source_cells == len(borders), "semantic source cells differ from independently painted borders")
    check_svg(name, reader, len(images))
    return {"pages": pages, "bodyPageOffset": offset, "semanticFragments": len(fixtures), "sourceNumbers": count,
            "headers": len(fixtures), "repeatedHeaders": len(fixtures) - 1, "resolvedLogicalLines": lines,
            "sourceCellPresentations": source_cells, "paintedCellBorders": len(borders),
            "referenceAndContentsLinks": len([link for link in links if "targetPageIndex" in link]),
            "externalLinks": len([link for link in links if "uri" in link]), "images": images,
            "characters": check_characters(path, 24), "utf16RangesChecked": True, "staticSvgChecked": True}


def check_consumer(path):
    reader = PdfReader(path)
    require(len(reader.pages) == 6, "consumer must retain six complete five-body-row regions")
    require(not reader.get_fields(), "consumer unexpectedly contains editable fields")
    expected = [f"CONSUMER-FLOW P={page + 1} T=6" for page in range(6)]
    require(actual_text(reader) == expected, "consumer repeated fields do not use actual final page values")
    plain = "\n".join(page.extract_text() or "" for page in reader.pages)
    require(re.findall(r"CFT(\d{3})", plain) == [f"{index:03}" for index in range(1, 31)],
            "consumer body rows lost, duplicated or reordered")
    borders = table_borders(reader)
    require(len(borders) == 36, "consumer repeated header/body cell borders differ")
    for page_index, page in enumerate(reader.pages):
        close(page.mediabox.width, 320, "consumer page width differs")
        close(page.mediabox.height, 240, "consumer page height differs")
        body = re.findall(r"CFT(\d{3})", page.extract_text() or "")
        require(body == [f"{index:03}" for index in range(page_index * 5 + 1, page_index * 5 + 6)],
                "consumer body row did not use available region")
        cells = [cell for cell in borders if cell["pageIndex"] == page_index]
        require(len(cells) == 6, "consumer region must repeat one header and five body rows")
        for row, cell in enumerate(cells):
            for key, value in (("x", 23), ("y", 23 + row * 28), ("width", 274), ("height", 28)):
                close(cell[key], value, f"consumer cell {key} differs from declared nested padding")
    _, logical, _ = pdf_geometry(reader, 20)
    require([line["source"] for line in logical] == expected, "consumer final logical field anchors differ")
    for index, line in enumerate(logical):
        require(line["page"] == index, "consumer header on wrong physical page")
        close(line["x"], 27, "consumer header origin x differs")
        close(line["y"], 27, "consumer header origin y differs")
    require(not annotations(reader) and not image_boxes(reader), "consumer fixture unexpectedly has interactions/images")
    return {"pages": 6, "headers": 6, "repeatedHeaders": 5, "sourceNumbers": 30,
            "paintedCellBorders": len(borders), "characters": check_characters(path, 16)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--consumer-build-directory", type=Path)
    args = parser.parse_args()
    versions = {name: metadata.version(name) for name in ("pypdf", "pypdfium2", "Pillow")}
    require(versions == {"pypdf": "6.10.0", "pypdfium2": "5.13.0", "Pillow": "12.3.0"},
            "use the pinned project Python environment from requirements-verification.txt")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    report = {"tools": versions, "renderComparison": {"dpi": 192, "blurPixels": 2, "meanLimit": 4, "changedFractionLimit": .06}}
    contacts = []
    contracts = [("regions", "REG", REGIONS, 4, 420, 360, 1, 18, 3, 7),
                 ("columns", "COL", COLUMNS, 4, 440, 420, 1, 23, 3, 9),
                 ("split", "SPL", SPLIT, 3, 420, 360, 0, 36, 0, 1)]
    for contract in contracts:
        name = contract[0]
        result = check_fixture(*contract)
        poppler, pdfium = render(FIXTURES / f"{name}.pdf", OUTPUT / name)
        require(len(poppler) == len(pdfium) == result["pages"], "independent renderer omitted a page")
        comparisons = [difference(a, b, 2) for a, b in zip(poppler, pdfium, strict=True)]
        require(all(item["meanAbsoluteChannelError"] < 4 and item["changedPixelFraction"] < .06 for item in comparisons),
                "independent PDF renderers disagree")
        result["rendererDifferences"] = comparisons
        report[name] = result
        contacts.extend((f"{name} page {index + 1}", page) for index, page in enumerate(poppler))
    consumer_directory = args.consumer_build_directory
    if consumer_directory is None:
        pointer = ROOT / "build/verification/consumer-build-directory.txt"
        require(pointer.is_file(), "run verify_local_consumer.ps1 or pass --consumer-build-directory")
        consumer_directory = Path(pointer.read_text(encoding="utf-8").strip())
    if not consumer_directory.is_absolute():
        consumer_directory = ROOT / consumer_directory
    path = consumer_directory / "output/consumer-semantic-table-flow.pdf"
    require(path.is_file(), "successful isolated consumer semantic-flow PDF is missing")
    result = check_consumer(path)
    poppler, pdfium = render(path, OUTPUT / "consumer")
    require(len(poppler) == len(pdfium) == result["pages"], "independent consumer renderer omitted a page")
    comparisons = [difference(a, b, 2) for a, b in zip(poppler, pdfium, strict=True)]
    require(all(item["meanAbsoluteChannelError"] < 4 and item["changedPixelFraction"] < .06 for item in comparisons),
            "independent consumer PDF renderers disagree")
    result["rendererDifferences"] = comparisons
    report["consumerBuildDirectory"] = str(consumer_directory)
    report["consumer"] = result
    contacts.extend((f"consumer page {index + 1}", page) for index, page in enumerate(poppler))
    contact_sheet(contacts, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
