# SPDX-License-Identifier: Apache-2.0
"""Independently decode semantic-grid PDF text, gutters, row pagination and editable ZIP resources."""
from __future__ import annotations

import argparse
import hashlib
from importlib import metadata
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from pypdf import PdfReader

from verify_adr_form import render
from verify_advanced_pdf import difference, require
from verify_inline_images import image_boxes
from verify_continuous_columns import font_ascent
from verify_production import contact_sheet
from verify_semantic_tables import check_characters, table_borders
from verify_streaming_rich_text import painted_spans

MARKERS = re.compile(r"\b(?:GRID-END|PREFIX-PAGING|TABLE-END|GRID-[A-H]|EDIT-A|BEFORE-[AB]|AFTER-[AB])\b")


def compact(value):
    return re.sub(r"\s", "", value)


def texts(element):
    if element.tag in ("p", "cell"):
        return ["".join(element.itertext())]
    return [value for child in element for value in texts(child)]


def check_case(directory, name, edited):
    key = name + ("-edited" if edited else "")
    path = directory / f"{key}.pdf"
    reader = PdfReader(path)
    spans = painted_spans(reader)
    logical = "".join(span["text"] for span in spans)
    with ZipFile(directory / f"{key}.zip") as bundle:
        source = ET.fromstring(bundle.read("template.xml"))
        grid = source.find("grid")
        require(grid is not None, f"{key}: grid was flattened in the saved ZIP")
        require(compact(logical) == compact("".join(texts(source))), f"{key}: full source text lost, repeated or reordered")
        original_images = {entry: bundle.read(entry) for entry in bundle.namelist() if entry.startswith("images/")}
        require(len(original_images) == 1, f"{key}: original image resource missing")
    if edited:
        with ZipFile(directory / f"{name}.zip") as bundle:
            require(original_images == {entry: bundle.read(entry) for entry in bundle.namelist() if entry.startswith("images/")},
                    f"{key}: edited ZIP changed the original image bytes")
    tracks = int(grid.attrib["tracks"])
    gap = float(grid.attrib["column-gap"])
    row_gap = float(grid.attrib["row-gap"])
    width = 420
    track = (width - (tracks - 1) * gap) / tracks
    items = list(grid)
    require(all(item.tag == "item" for item in items), f"{key}: model writer left dynamic grid nodes")
    marker_positions = {}
    for index, span in enumerate(spans):
        for marker in MARKERS.findall(span["text"]):
            require(marker not in marker_positions, f"{key}: duplicate marker {marker}")
            marker_positions[marker] = index
    expected_edit = "EDIT-A" if edited else "GRID-A"
    unexpected_edit = "GRID-A" if edited else "EDIT-A"
    require(expected_edit in marker_positions and unexpected_edit not in marker_positions,
            f"{key}: the saved text edit did not reach the final PDF")
    item_markers = [MARKERS.search(" ".join(texts(item))).group() for item in items]
    last_marker = "GRID-END" if name == "paging" else "AFTER-A"
    groups = []
    for index, (item, marker) in enumerate(zip(items, item_markers)):
        start = marker_positions[marker]
        end = marker_positions[item_markers[index + 1]] if index + 1 < len(items) else marker_positions[last_marker]
        content = spans[start:end]
        left = 30 + (int(item.attrib["column-start"]) - 1) * (track + gap)
        item_width = int(item.attrib["column-span"]) * track + (int(item.attrib["column-span"]) - 1) * gap
        require(content, f"{key}: empty physical grid item")
        require(all(abs(span["size"] - 10) < .001 for span in content), f"{key}: item font changed")
        require(all(left - .003 <= span["x"] <= span["right"] <= left + item_width + .003 for span in content),
                f"{key}: continuation text leaves its declared tracks")
        require(len({span["page"] for span in content}) == 1, f"{key}: atomic grid item split over pages")
        if item.find("table") is None:
            require(abs(content[0]["x"] - left) < .003, f"{key}: item starts at the wrong track")
        groups.append({"id": item.attrib["id"], "row": int(item.attrib["row"]), "page": content[0]["page"],
                       "left": left, "width": item_width, "firstBaseline": content[0]["baseline"], "lines": content})
    for row in {group["row"] for group in groups}:
        physical = [group for group in groups if group["row"] == row]
        require(len({group["page"] for group in physical}) == 1, f"{key}: one grid row was split across pages")
        if len(physical) > 1:
            require(max(group["firstBaseline"] for group in physical) - min(group["firstBaseline"] for group in physical) < .003,
                    f"{key}: parallel item tops no longer align")
    images = image_boxes(reader)
    expected_count = 3 if name == "paging" else 1
    require(len(images) == expected_count, f"{key}: grid image lost or duplicated")
    image_items = [group for group, item in zip(groups, items) if item.find(".//image") is not None]
    ascent = font_ascent(Path(__file__).resolve().parents[1] / "assets/fonts/LiberationSans-Regular.ttf")
    for actual, item in zip(images, image_items):
        expected_width, expected_height = (180, 84) if name == "paging" else (120, 56)
        require(actual["page"] == item["page"] and abs(actual["x"] - item["left"]) < .003
                and abs(actual["width"] - expected_width) < .003 and abs(actual["height"] - expected_height) < .003
                and actual["pixels"] == [360, 168], f"{key}: image moved, shrank or left its item")
        require(actual["x"] + actual["width"] <= item["left"] + item["width"] + .003, f"{key}: image crosses a grid track")
        require(actual["y"] >= item["firstBaseline"], f"{key}: image overlaps its preceding label")
        later_rows = [group for group in groups if group["page"] == item["page"] and group["row"] > item["row"]]
        next_top = min((group["firstBaseline"] - ascent * 10 for group in later_rows),
                       default=float(reader.pages[item["page"]].mediabox.height) - 30)
        require(actual["y"] + actual["height"] <= next_top + .003, f"{key}: image crosses the following row or page margin")
    if name == "paging":
        require(len(reader.pages) == 3 and [group["page"] for group in groups] == [1, 1, 1, 1, 2, 2],
                f"{key}: short first region or atomic row pagination changed")
        # 14 pt label + its saved 6 pt paragraph spacing + 88 pt image line + 12 pt row gap.
        require(abs(groups[2]["firstBaseline"] - groups[0]["firstBaseline"] - 120) < .003,
                f"{key}: same-page row gap/height differs")
        offsets = [actual["y"] - item["firstBaseline"] for actual, item in zip(images, image_items)]
        require(max(offsets) - min(offsets) < .003, f"{key}: paginated row image offsets differ")
    else:
        require(len(reader.pages) == 1, f"{key}: unnecessary page break around columns/grid")
        before = spans[marker_positions["BEFORE-A"]:marker_positions[item_markers[0]]]
        after = spans[marker_positions["AFTER-A"]:marker_positions["GRID-END"]]
        require(min(group["firstBaseline"] for group in groups) >= max(span["baseline"] for span in before) + 14 - .003,
                f"{key}: grid overlaps the preceding continuous columns")
        require(after[0]["baseline"] >= max(span["baseline"] for group in groups for span in group["lines"]) + 14 - .003,
                f"{key}: following columns overlap grid text")
        require(all(image["y"] + image["height"] < after[0]["baseline"] for image in images),
                f"{key}: following columns overlap a grid image")
    if name == "mixed":
        borders = table_borders(reader)
        offset = groups[-1]
        require(borders and abs(min(border["x"] for border in borders) - offset["left"]) < .003
                and abs(max(border["x"] + border["width"] for border in borders) - offset["left"] - offset["width"]) < .003,
                f"{key}: table does not use the declared offset/span")
        require(groups[0]["width"] == (276 if edited else 204), f"{key}: edited first-item span did not change width")
    if name == "gaps":
        require([group["row"] for group in groups] == [1, 1, 5], f"{key}: explicit sparse row labels changed")
        row_one_bottom = max(span["baseline"] for group in groups[:2] for span in group["lines"]) + 14
        require(abs(groups[2]["firstBaseline"] - row_one_bottom - row_gap) < .003,
                f"{key}: skipped row numbers created blank physical rows")
    check_characters(path, 30)
    svg_path = directory / f"{name}.svg"
    if not edited:
        svg = ET.parse(svg_path).getroot()
        ns = "{http://www.w3.org/2000/svg}"
        require(len(svg.findall(f"{ns}svg")) == len(reader.pages), f"{key}: SVG physical page count differs")
        require(len(svg.findall(f".//{ns}image")) == len(images), f"{key}: SVG image count differs")
        svg_text = " ".join(group.get("aria-label", "") for group in svg.iter(f"{ns}g"))
        require(MARKERS.findall(svg_text) == list(marker_positions), f"{key}: SVG source order changed")
    return {"pages": len(reader.pages), "sourceMarkers": len(marker_positions),
            "items": [{field: value for field, value in group.items() if field != "lines"} for group in groups],
            "images": images, "pdfSha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def checked_render(path, directory, contacts):
    poppler, pdfium = render(path, directory)
    require(len(poppler) == len(pdfium) == len(PdfReader(path).pages), "Independent renderer page count differs")
    comparisons = []
    for index, (first, second) in enumerate(zip(poppler, pdfium)):
        diff = difference(first, second, blur_radius=2)
        require(diff["meanAbsoluteChannelError"] < 4 and diff["changedPixelFraction"] < .06,
                f"Independent renderers disagree: {path.name}: {diff}")
        comparisons.append(diff)
        contacts.append((f"{path.stem} p{index + 1}", first))
    return comparisons


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--consumer-build-directory", required=True, type=Path)
    parser.add_argument("--output-directory", type=Path, default=Path("build/verification/grid-layout"))
    parser.add_argument("--designer-output-directory", type=Path)
    args = parser.parse_args()
    directory = args.consumer_build_directory / "output/grid-layout"
    output = args.output_directory
    output.mkdir(parents=True, exist_ok=True)
    versions = {name: metadata.version(name) for name in ("pypdf", "pypdfium2", "Pillow")}
    require(versions == {"pypdf": "6.10.0", "pypdfium2": "5.13.0", "Pillow": "12.3.0"}, "Use the pinned verification runtime")
    report, contacts = {"tools": versions, "cases": {}}, []
    for name in ("mixed", "thirds", "gaps", "paging"):
        for edited in (False, True):
            key = name + ("-edited" if edited else "")
            result = check_case(directory, name, edited)
            result["renderComparisons"] = checked_render(directory / f"{key}.pdf", output / key, contacts)
            report["cases"][key] = result
    contact_sheet(contacts, output)
    if args.designer_output_directory:
        report["designer"] = {}
        designer_contacts = []
        designer_directory = output / "designer"
        designer_directory.mkdir(parents=True, exist_ok=True)
        for key in ("grid-editor", "grid-editor-sample"):
            path = args.designer_output_directory / f"{key}.pdf"
            check_characters(path, 36)
            result = {"pages": len(PdfReader(path).pages), "images": image_boxes(PdfReader(path)),
                      "pdfSha256": hashlib.sha256(path.read_bytes()).hexdigest()}
            require(result["images"], f"{key}: grid image missing from actual designer download")
            result["renderComparisons"] = checked_render(path, designer_directory / key, designer_contacts)
            report["designer"][key] = result
        contact_sheet(designer_contacts, designer_directory)
    (output / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps({"cases": len(report["cases"]), "pages": sum(case["pages"] for case in report["cases"].values())}))


if __name__ == "__main__":
    main()
