# SPDX-License-Identifier: Apache-2.0
"""Check fixed nested-table semantics, final PDF links and every independently rendered page.

Run after :examples:semanticTables and verify_local_consumer.ps1 in the project's pinned Python
environment. The Java manifest only cross-checks independently specified source and geometry.
No baselines are created or updated; the SVG is an explicitly static preview without extras.
"""
from __future__ import annotations

import argparse
from importlib import metadata
import json
from pathlib import Path
import xml.etree.ElementTree as ET

import pypdfium2 as pdfium
from pypdf import PdfReader
from verify_adr_form import render
from verify_advanced_pdf import difference, require
from verify_inline_images import compose, image_boxes
from verify_multi_floats import pdf_geometry, point
from verify_production import actual_text, contact_sheet

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "examples/build/semantic-tables"
OUTPUT = ROOT / "build/verification/semantic-tables"
TOLERANCE = .003
BODY_SOURCE = "PAGE=\ufffc TOTAL=\ufffc\nREF=\ufffc\nOPEN \ufffc"
ROW_SOURCE = "ROW PAGE=\ufffc\nTO=\ufffc"
EXAMPLE_LINES = ["PAGE=2 TOTAL=3", "REF=7", "OPEN \ufffc", "ROW PAGE=2", "TO=7",
                 "PAGE=7 TOTAL=3", "REF=2", "OPEN \ufffc"]
EXAMPLE_TABLES = [
    {"tableId": "nested-a", "pageIndex": 1, "x": 36, "y": 60, "width": 348, "height": 120,
     "cells": [(0, "NESTED-A", "NESTED-A", 42, 66),
               (1, BODY_SOURCE, "PAGE=2 TOTAL=3\nREF=7\nOPEN \ufffc", 42, 102)]},
    {"tableId": "nested-row", "pageIndex": 1, "x": 214, "y": 194, "width": 172, "height": 60,
     "cells": [(0, ROW_SOURCE, "ROW PAGE=2\nTO=7", 220, 200)]},
    {"tableId": "nested-b", "pageIndex": 2, "x": 219, "y": 51, "width": 174, "height": 120,
     "cells": [(0, "NESTED-B", "NESTED-B", 225, 57),
               (1, BODY_SOURCE, "PAGE=7 TOTAL=3\nREF=2\nOPEN \ufffc", 225, 93)]},
]


def close(actual, expected, message):
    require(abs(float(actual) - expected) < TOLERANCE, message)


def page_logical(page):
    """Extract marked logical lines independently for one physical PDF page."""
    values = []
    for operands, operator in page.get_contents().operations:
        if operator != b"BDC" or len(operands) < 2:
            continue
        properties = operands[1]
        if not isinstance(properties, dict):
            properties = page["/Resources"].get("/Properties", {}).get(properties, {})
        if properties.get("/ActualText") is not None:
            values.append(str(properties["/ActualText"]))
    return values


def annotations(reader):
    result = []
    page_ids = {page.indirect_reference.idnum: index for index, page in enumerate(reader.pages)}
    for page_index, page in enumerate(reader.pages):
        for reference in page.get("/Annots", []):
            annotation = reference.get_object()
            require(annotation.get("/Subtype") == "/Link", "unexpected non-link annotation")
            left, bottom, right, top = map(float, annotation["/Rect"])
            require(0 <= left < right <= float(page.mediabox.width)
                    and 0 <= bottom < top <= float(page.mediabox.height), "link leaves its PDF page")
            item = {"pageIndex": page_index, "x": left, "y": float(page.mediabox.height) - top,
                    "width": right - left, "height": top - bottom}
            if "/Dest" in annotation:
                destination = annotation["/Dest"]
                require(destination[1] == "/XYZ" and destination[0].idnum in page_ids,
                        "internal link has an invalid or non-XYZ destination")
                target = page_ids[destination[0].idnum]
                item.update(targetPageIndex=target, destinationX=float(destination[2]),
                            destinationY=float(reader.pages[target].mediabox.height) - float(destination[3]))
            else:
                action = annotation.get("/A", {}).get_object()
                require(action.get("/S") == "/URI", "unexpected external action")
                item["uri"] = str(action["/URI"])
            result.append(item)
    return result


def table_borders(reader):
    """Read closed four-edge strokes, including the half-stroke inset, from final PDF CTMs."""
    result = []
    for index, page in enumerate(reader.pages):
        matrix, stroke, stack, vertices = (1, 0, 0, 1, 0, 0), 1, [], []
        closed = False
        for operands, operator in page.get_contents().operations:
            if operator == b"q":
                stack.append((matrix, stroke))
            elif operator == b"Q":
                matrix, stroke = stack.pop()
            elif operator == b"cm":
                matrix = compose(matrix, tuple(map(float, operands)))
            elif operator == b"w":
                stroke = float(operands[0])
            elif operator == b"m":
                vertices, closed = [point(matrix, *map(float, operands))], False
            elif operator == b"l":
                vertices.append(point(matrix, *map(float, operands)))
            elif operator == b"h":
                closed = True
            elif operator == b"S":
                if closed and len(vertices) == 4:
                    close(stroke, .5, "semantic table border stroke changed")
                    xs, ys = zip(*vertices, strict=True)
                    result.append({"pageIndex": index, "x": min(xs) - stroke / 2,
                                   "y": float(page.mediabox.height) - max(ys) - stroke / 2,
                                   "width": max(xs) - min(xs) + stroke,
                                   "height": max(ys) - min(ys) + stroke})
                vertices, closed = [], False
            elif operator in (b"n", b"f", b"f*", b"B", b"B*"):
                vertices, closed = [], False
        require(not stack, "unbalanced table border graphics state")
    return result


def check_characters(path, margin):
    count = 0
    with pdfium.PdfDocument(path) as document:
        for index in range(len(document)):
            page = document[index]
            text = page.get_textpage()
            try:
                for character in range(text.count_chars()):
                    if not text.get_text_range(character, 1, errors="ignore").strip():
                        continue
                    left, bottom, right, top = text.get_charbox(character)
                    require(margin - .6 <= left <= right <= page.get_width() - margin + .6
                            and margin - .6 <= bottom <= top <= page.get_height() - margin + .6,
                            f"printed character leaves page margins on physical page {index + 1}")
                    count += 1
            finally:
                text.close()
                page.close()
    return count


def inline_ink_bounds(reader, logical):
    """Read filled paths following the nonpainting logical anchor; exclude images and border strokes."""
    result = []
    for index, page in enumerate(reader.pages):
        matrix, stack, scopes, path, active = (1, 0, 0, 1, 0, 0), [], [], [], None

        def save_ink():
            if active is not None:
                require(active, "hyperlinked logical text has no painted path")
                xs = [coordinates[0] for coordinates in active]
                result.append({"pageIndex": index, "x": min(xs), "right": max(xs)})

        for operands, operator in page.get_contents().operations:
            if operator == b"q":
                stack.append(matrix)
            elif operator == b"Q":
                matrix = stack.pop()
            elif operator == b"cm":
                matrix = compose(matrix, tuple(map(float, operands)))
            elif operator == b"BDC":
                save_ink()
                active = None
                properties = operands[1]
                if not isinstance(properties, dict):
                    properties = page["/Resources"].get("/Properties", {}).get(properties, {})
                scopes.append(str(properties.get("/ActualText", "")))
            elif operator == b"BMC":
                scopes.append("")
            elif operator == b"EMC":
                scope = scopes.pop()
                if scope == logical:
                    active = []
            elif operator in (b"m", b"l", b"c"):
                coordinates = list(map(float, operands))
                for offset in range(0, len(coordinates), 2):
                    path.append(point(matrix, coordinates[offset], coordinates[offset + 1]))
            elif operator in (b"f", b"f*"):
                if active is not None and not scopes:
                    active.extend(path)
                path = []
            elif operator in (b"n", b"S", b"B", b"B*"):
                path = []
        save_ink()
        require(not stack and not scopes, "unbalanced painted logical text scope")
    return result


def check_picture_links(reader, links, text_uri, image_uri, pages, pixels, text_origins):
    images = image_boxes(reader)
    ink = inline_ink_bounds(reader, "OPEN \ufffc")
    require(len(images) == len(pages), "nested image lost or repeated")
    require(len(ink) == len(pages), "hyperlinked text ink lost or repeated")
    require([image["page"] for image in images] == pages, "nested image moved to a wrong physical page")
    for image, (text_x, text_y) in zip(images, text_origins, strict=True):
        require(image["pixels"] == pixels, "embedded source pixel dimensions changed")
        close(image["width"], 14, "nested image width changed")
        close(image["height"], 14, "nested image height changed")
        picture = [link for link in links if link.get("uri") == image_uri and link["pageIndex"] == image["page"]]
        label = [link for link in links if link.get("uri") == text_uri and link["pageIndex"] == image["page"]]
        require(len(picture) == len(label) == 1, "image or text hyperlink missing or duplicated")
        for key in ("x", "y", "width", "height"):
            close(picture[0][key], image[key], f"image hyperlink misses actual image {key}")
        text_ink = [box for box in ink if box["pageIndex"] == image["page"]]
        require(len(text_ink) == 1 and text_ink[0]["x"] >= text_x, "source text ink leaves its original line")
        close(label[0]["x"], text_ink[0]["x"], "text hyperlink misses original painted ink x")
        close(label[0]["x"] + label[0]["width"], text_ink[0]["right"], "text hyperlink misses painted ink right edge")
        close(label[0]["y"], text_y, "text hyperlink misses original source text y")
        close(label[0]["height"], 24, "text hyperlink no longer uses its fixed line box")
        require(text_ink[0]["right"] <= image["x"] + TOLERANCE, "text hyperlink overlaps the adjacent image")
    return images


def check_example(path):
    reader = PdfReader(path)
    require(len(reader.pages) == 3, "example must retain one contents page and two section pages")
    require(not reader.get_fields(), "semantic table fixture unexpectedly contains editable fields")
    for page in reader.pages:
        close(page.mediabox.width, 420, "example page width changed")
        close(page.mediabox.height, 360, "example page height changed")
    require(actual_text(reader) == EXAMPLE_LINES, "nested final fields lost, duplicated, reordered or resolved before offset")
    require([page_logical(page) for page in reader.pages] == [[], EXAMPLE_LINES[:5], EXAMPLE_LINES[5:]],
            "nested semantic lines moved to an incorrect physical page")
    plain = [page.extract_text() or "" for page in reader.pages]
    require("Contents" in plain[0] and "Nested A" in plain[0] and "Nested B" in plain[0], "contents labels missing")
    for index, tokens in [(1, ["NESTED-A", "FIRST-END", "Atomic row / semantic table"]),
                          (2, ["NESTED-B", "TARGET-B / displayed page 7", "SECOND-END"])]:
        for token in tokens:
            require(plain[index].count(token) == 1, f"plain source {token} missing, repeated or on wrong page")
    _, lines, _ = pdf_geometry(reader, 24)
    require([line["source"] for line in lines] == EXAMPLE_LINES, "final logical anchor order differs")
    coordinates = [(1, 42, 102), (1, 42, 126), (1, 42, 150), (1, 220, 200), (1, 220, 224),
                   (2, 225, 93), (2, 225, 117), (2, 225, 141)]
    for line, (page, x, y) in zip(lines, coordinates, strict=True):
        require(line["page"] == page, "semantic anchor page differs")
        close(line["x"], x, "semantic anchor x changed after final resolution")
        close(line["y"], y, "semantic anchor y changed after final resolution")
    expected_borders = [(1, 36, 60, 348, 36), (1, 36, 96, 348, 84), (1, 214, 194, 172, 60),
                        (2, 219, 51, 174, 36), (2, 219, 87, 174, 84)]
    borders = table_borders(reader)
    require(len(borders) == len(expected_borders), "semantic source cell border count differs")
    for border, values in zip(borders, expected_borders, strict=True):
        for key, value in zip(("pageIndex", "x", "y", "width", "height"), values, strict=True):
            close(border[key], value, f"final semantic table cell {key} differs")
    links = annotations(reader)
    internal = [link for link in links if "targetPageIndex" in link]
    external = [link for link in links if "uri" in link]
    require(len(internal) == 5 and len(external) == 4, "example contents/field/external link count differs")
    contents = [link for link in internal if link["pageIndex"] == 0]
    require([link["targetPageIndex"] for link in contents] == [1, 2], "contents links ignore final body offset")
    for link, target in zip(contents, [EXAMPLE_TABLES[0], EXAMPLE_TABLES[2]], strict=True):
        close(link["destinationX"], target["x"], "contents destination x changed")
        close(link["destinationY"], target["y"], "contents destination y changed")
    expected_refs = [(1, 126, 2, 24, 24), (1, 224, 2, 219, 51), (2, 117, 1, 36, 60)]
    references = [link for link in internal if link["pageIndex"] != 0]
    for page, y, target, destination_x, destination_y in expected_refs:
        matching = [link for link in references if link["pageIndex"] == page and abs(link["y"] - y) < TOLERANCE]
        require(len(matching) == 1, "nested reference link on wrong source line")
        link = matching[0]
        require(link["targetPageIndex"] == target, "nested reference targets wrong physical page")
        for key, value in [("width", 18), ("height", 24), ("destinationX", destination_x), ("destinationY", destination_y)]:
            close(link[key], value, f"nested reference {key} differs")
    outlines = [item for item in reader.outline if isinstance(item, dict)]
    require([str(item["/Title"]) for item in outlines] == ["Nested A", "Nested B"], "nested bookmarks missing")
    require([reader.get_destination_page_number(item) for item in outlines] == [1, 2], "bookmarks ignore final page offset")
    images = check_picture_links(reader, external, "https://example.com/semantic-text", "https://example.com/semantic-image",
                                 [1, 2], [8, 8], [(42, 150), (225, 141)])
    check_manifest(links)
    check_svg()
    return {"pages": 3, "bodyPageOffset": 1, "semanticLines": 8, "nestedTables": 3, "contentsLinks": 2,
            "referenceLinks": 3, "externalLinks": 4, "images": images, "bookmarks": 2,
            "sourceCells": 5, "paintedCellBorders": len(borders), "svgLogicalLines": 20,
            "characters": check_characters(path, 24), "staticSvgChecked": True}


def check_manifest(links):
    manifest = json.loads((FIXTURES / "results.json").read_text(encoding="utf-8"))
    require(manifest["pages"] == 3 and manifest["bodyPageOffset"] == 1, "manifest disagrees with fixed page contract")
    placements = manifest["placements"]
    require([table["tableId"] for table in placements] == [table["tableId"] for table in EXAMPLE_TABLES],
            "manifest nested table source order differs")
    for actual, expected in zip(placements, EXAMPLE_TABLES, strict=True):
        require(actual["pageIndex"] == expected["pageIndex"], "manifest physical page differs")
        for key in ("x", "y", "width", "height"):
            close(actual[key], expected[key], f"manifest table {key} differs from independently specified geometry")
        require(len(actual["cells"]) == len(expected["cells"]), "manifest source cell count differs")
        for cell, (row, source, logical, x, y) in zip(actual["cells"], expected["cells"], strict=True):
            require(cell["row"] == row and cell["column"] == 0 and cell["source"] == source
                    and cell["logical"] == logical, "source row/column or original/resolved cell text changed")
            require(cell["sourceUtf16Length"] == len(source.encode("utf-16-le")) // 2, "source UTF-16 length changed")
            objects = [index for index, value in enumerate(source) if value == "\ufffc"]
            expected_images = objects[-1:] if source == BODY_SOURCE else []
            require(cell["imageIndices"] == expected_images and cell["fieldIndices"] == [index for index in objects if index not in expected_images],
                    "original field/image positions changed during final resolution")
            close(cell["textX"], x, "manifest text x differs")
            close(cell["textY"], y, "manifest text y differs")
    for key, kind in [("externalLinks", "uri"), ("links", "targetPageIndex")]:
        expected = [link for link in links if kind in link]
        require(len(manifest[key]) == len(expected), "manifest/PDF link count differs")
        for link in manifest[key]:
            matching = [actual for actual in expected if actual["pageIndex"] == link["pageIndex"]
                        and actual[kind] == link[kind] and abs(actual["x"] - link["x"]) < TOLERANCE
                        and abs(actual["y"] - link["y"]) < TOLERANCE]
            require(len(matching) == 1, "manifest link does not match independently parsed PDF object")
            for field in ("width", "height") + (() if kind == "uri" else ("destinationX", "destinationY")):
                close(matching[0][field], link[field], f"manifest/PDF link {field} differs")


def check_svg():
    svg = ET.parse(FIXTURES / "semantic-tables-preview.svg").getroot()
    ns = "{http://www.w3.org/2000/svg}"
    require(len(svg.findall(f"{ns}svg")) == 3, "static SVG preview page count differs")
    logical = [element.get("aria-label") for element in svg.iter(f"{ns}g") if element.get("aria-label") is not None]
    expected = ["Contents", "Nested A", "2", "Nested B", "7", "Nested semantic tables / final fields and links",
                "NESTED-A", *EXAMPLE_LINES[:3], "Atomic row / semantic table", *EXAMPLE_LINES[3:5], "FIRST-END",
                "TARGET-B / displayed page 7", "NESTED-B", *EXAMPLE_LINES[5:], "SECOND-END"]
    require(logical == expected, "static SVG resolved semantic and surrounding source text order differs")
    require(len(svg.findall(f".//{ns}image")) == 2, "static SVG lost or repeated nested images")
    require(not svg.findall(f".//{ns}a"), "static preview unexpectedly includes interaction data")


def check_consumer(path):
    reader = PdfReader(path)
    require(len(reader.pages) == 2, "consumer nested table should occupy two pages")
    expected = ["CONSUMER-NESTED PAGE=1 TOTAL=2", "TARGET=2", "OPEN \ufffc", "BACK=1"]
    require(actual_text(reader) == expected, "isolated consumer final semantic table fields differ")
    require([page_logical(page) for page in reader.pages] == [expected[:3], expected[3:]], "consumer field physical pages differ")
    require((reader.pages[1].extract_text() or "").count("CONSUMER-NESTED-TARGET") == 1, "consumer target paragraph missing")
    for page in reader.pages:
        close(page.mediabox.width, 320, "consumer page width differs")
        close(page.mediabox.height, 220, "consumer page height differs")
    borders = table_borders(reader)
    require(len(borders) == 1, "consumer nested table source border missing or repeated")
    for key, value in [("pageIndex", 0), ("x", 26), ("y", 26), ("width", 268), ("height", 84)]:
        close(borders[0][key], value, f"consumer nested cell {key} differs")
    links = annotations(reader)
    internal = [link for link in links if "targetPageIndex" in link]
    external = [link for link in links if "uri" in link]
    require(len(internal) == len(external) == 2, "consumer lost internal or external links")
    require([(link["pageIndex"], link["targetPageIndex"]) for link in internal] == [(0, 1), (1, 0)],
            "consumer cross-page links do not include reverse reference to nested source")
    for link, (x, y, height) in zip(internal, [(16, 16, 24), (26, 26, 15)], strict=True):
        close(link["destinationX"], x, "consumer reference destination x differs")
        close(link["destinationY"], y, "consumer reference destination y differs")
        close(link["width"], 18, "consumer reference reserve width differs")
        close(link["height"], height, "consumer reference line height differs")
    images = check_picture_links(reader, external, "https://example.com/consumer-text", "https://example.com/consumer-image",
                                 [0], [2, 2], [(32, 80)])
    return {"pages": 2, "semanticLines": 4, "referenceLinks": 2, "externalLinks": 2, "images": images,
            "paintedCellBorders": 1, "characters": check_characters(path, 16)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--consumer-build-directory", type=Path)
    args = parser.parse_args()
    versions = {name: metadata.version(name) for name in ("pypdf", "pypdfium2", "Pillow")}
    require(versions == {"pypdf": "6.10.0", "pypdfium2": "5.13.0", "Pillow": "12.3.0"},
            "use the project Python environment pinned by requirements-verification.txt")
    consumer_directory = args.consumer_build_directory
    if consumer_directory is None:
        pointer = ROOT / "build/verification/consumer-build-directory.txt"
        require(pointer.is_file(), "run verify_local_consumer.ps1 first or pass --consumer-build-directory")
        consumer_directory = Path(pointer.read_text(encoding="utf-8").strip())
    if not consumer_directory.is_absolute():
        consumer_directory = ROOT / consumer_directory
    example = FIXTURES / "semantic-tables.pdf"
    consumer = consumer_directory / "output/consumer-semantic-tables.pdf"
    require(example.is_file() and consumer.is_file(), "current example or successful isolated consumer PDF is missing")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    report = {"tools": versions, "consumerBuildDirectory": str(consumer_directory),
              "renderComparison": {"dpi": 192, "blurPixels": 2, "meanLimit": 4, "changedFractionLimit": .06}}
    contacts = []
    for name, path, check in [("example", example, check_example), ("consumer", consumer, check_consumer)]:
        result = check(path)
        poppler, pdfium_pages = render(path, OUTPUT / name)
        require(len(poppler) == len(pdfium_pages) == result["pages"], "independent renderer omitted a page")
        comparisons = [difference(a, b, 2) for a, b in zip(poppler, pdfium_pages, strict=True)]
        require(all(item["meanAbsoluteChannelError"] < 4 and item["changedPixelFraction"] < .06 for item in comparisons),
                "independent PDF renderers disagree")
        result["rendererDifferences"] = comparisons
        report[name] = result
        contacts.extend((f"{name} page {index + 1}", page) for index, page in enumerate(poppler))
    contact_sheet(contacts, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
