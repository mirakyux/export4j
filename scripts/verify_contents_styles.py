# SPDX-License-Identifier: Apache-2.0
"""Check final contents typography, the fixed label column and exact physical-page destinations.

Expectations come from the two authored fixtures, not a Java result manifest. Every new
report/caller page is rendered by Poppler and PDFium; no visual baseline is updated.
"""
from __future__ import annotations

import argparse
from importlib import metadata
import json
from pathlib import Path

from pypdf import PdfReader
from verify_adr_form import render
from verify_advanced_pdf import difference, require
from verify_continuous_columns import font_ascent
from verify_production import contact_sheet
from verify_semantic_tables import check_characters, close
from verify_streaming_rich_text import painted_spans, physical_fonts, same_color

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "examples/build/contents-styles"
OUTPUT = ROOT / "build/verification/contents-styles"
BLACK, BLUE, GREEN, RED = (0, 0, 0), (34 / 255, 68 / 255, 136 / 255), (0, .4, .2), (.8, .1, .1)


def text_style(span, expected, size, color, top, ascent):
    require(span["text"] == expected, "contents/body source text missing, duplicated or reordered")
    close(span["size"], size, "contents/body actual point size differs: " + expected)
    require(same_color(span["color"], color), "contents/body actual RGB differs: " + expected)
    require(span["font"].endswith("+LiberationSans"), "contents/body physical font differs")
    close(span["baseline"], top + ascent * size, "contents/body actual line origin differs: " + expected)


def check_links(reader, margin, row_tops, row_heights, target_tops):
    first, body = reader.pages
    require(not body.get("/Annots"), "contents fixture body gained annotations")
    annotations = first.get("/Annots", [])
    require(len(annotations) == len(row_tops) == 2, "contents row link count differs")
    width, height = float(first.mediabox.width), float(first.mediabox.height)
    targets = []
    for reference, row_top, row_height, target_top in zip(annotations, row_tops, row_heights, target_tops, strict=True):
        link = reference.get_object()
        require(link["/Subtype"] == "/Link" and "/A" not in link and "/Dest" in link,
                "contents row is not a passive internal link")
        left, bottom, right, top = map(float, link["/Rect"])
        close(left, margin, "contents link left differs")
        close(right, width - margin, "contents link right differs")
        close(height - top, row_top, "contents link does not cover its final row")
        close(top - bottom, row_height, "contents link row height ignores selected label typography")
        destination = link["/Dest"]
        require(destination[1] == "/XYZ" and destination[0].idnum == body.indirect_reference.idnum,
                "contents link does not target the final physical body page")
        close(destination[2], margin, "contents destination left differs")
        close(destination[3], height - target_top, "contents destination does not reach its semantic heading")
        require(len(destination) == 5 and destination[4] == 0, "contents destination zoom differs")
        targets.append({"physicalPageIndex": 1, "x": margin, "top": target_top})
    return targets


def check_outline(reader, titles, levels, margin, target_tops):
    found, actual_levels = [], []
    def walk(items, level=1):
        for item in items:
            if isinstance(item, list): walk(item, level + 1)
            else:
                found.append(item)
                actual_levels.append(level)
    walk(reader.outline)
    require([str(item["/Title"]) for item in found] == titles, "contents outline source/hierarchy differs")
    require(actual_levels == levels, "contents outline hierarchy differs")
    require([reader.get_destination_page_number(item) for item in found] == [1, 1],
            "contents outline ignores the final front-page offset")
    for item, top in zip(found, target_tops, strict=True):
        require(item["/Type"] == "/XYZ", "contents outline destination type differs")
        close(item["/Left"], margin, "contents outline destination left differs")
        close(item["/Top"], float(reader.pages[1].mediabox.height) - top, "contents outline destination top differs")


def check_pdf(path, consumer=False):
    reader = PdfReader(path, strict=True)
    require(len(reader.pages) == 2 and not reader.get_fields(), "contents fixture page/form contract differs")
    width, height, margin = (300, 260, 20) if consumer else (420, 360, 24)
    for page in reader.pages:
        close(page.mediabox.width, width, "contents page width differs")
        close(page.mediabox.height, height, "contents page height differs")
    spans = painted_spans(reader)
    contents, body = ([span for span in spans if span["page"] == index] for index in range(2))
    prefix = "CONSUMER" if consumer else "REPORT"
    titles = [prefix + "-FIRST", prefix + "-SECOND" + ("" if consumer else " details alpha beta gamma delta epsilon zeta")]
    require(len(contents) == (5 if consumer else 6) and len(body) == (3 if consumer else 4),
            "contents fixture gained/lost painted text runs")
    ascent = font_ascent(ROOT / "assets/fonts/LiberationSans-Regular.ttf")
    title_size, entry_size, label_size = (18, 10, 12) if consumer else (22, 11, 14)
    row_tops, row_heights = ([62, 92], [22, 22]) if consumer else ([72, 106], [26, 40])
    text_style(contents[0], prefix + "-CONTENTS", title_size, BLUE, margin, ascent)
    close((contents[0]["x"] + contents[0]["right"]) / 2, width / 2,
          "contents title did not use the selected CENTER alignment")
    entry_color = BLACK if consumer else GREEN
    for index, row_top in enumerate(row_tops):
        if not consumer and index == 1:
            entries, label = contents[3:5], contents[5]
            lines = ["REPORT-SECOND details alpha beta gamma delta epsilon", "zeta"]
        else:
            entries, label = [contents[1 + 2 * index]], contents[2 + 2 * index]
            lines = [titles[index]]
        for line, (entry, expected) in enumerate(zip(entries, lines, strict=True)):
            text_style(entry, expected, entry_size, entry_color, row_top + line * 20, ascent)
            indent = 0 if consumer or index == 0 else 14
            close(entry["x"], margin + indent, "contents entry hierarchy indent differs")
            require(entry["right"] <= width - margin - 48, "contents entry entered the fixed 48-point label column")
        text_style(label, "2", label_size, RED, row_top, ascent)
        require(width - margin - 48 <= label["x"] < label["right"] <= width - margin + .003,
                "contents page label left its fixed 48-point column")
        close(label["right"], width - margin, "contents page label did not use RIGHT alignment")
    body_text = [titles[0], "CONSUMER-BODY", titles[1]] if consumer else [
        titles[0], "REPORT-BODY-ONE", titles[1], "REPORT-BODY-TWO"]
    body_tops = [20, 44, 65] if consumer else [24, 48, 69, 93]
    for index, (span, text, top) in enumerate(zip(body, body_text, body_tops, strict=True)):
        text_style(span, text, 12 if index % 2 == 0 else 10, BLACK, top, ascent)
        close(span["x"], margin, "contents appearance changed semantic body position")
    target_tops = [body_tops[0], body_tops[2]]
    links = check_links(reader, margin, row_tops, row_heights, target_tops)
    check_outline(reader, titles, [1, 1] if consumer else [1, 2], margin, target_tops)
    require("".join(reader.pages[0].extract_text().split()) == "".join((prefix + "-CONTENTS" + titles[0] + "2" + titles[1] + "2").split()),
            "contents final text/physical labels differ")
    require("".join(reader.pages[1].extract_text().split()) == "".join("".join(body_text).split()), "semantic body original text differs")
    return {"pages": 2, "titlePointSize": title_size, "entryPointSize": entry_size,
            "pageLabelPointSize": label_size, "pageLabelColumnPoints": 48, "contentsEntryLines": [1, 1] if consumer else [1, 2], "links": links,
            "characters": check_characters(path, margin), "fonts": physical_fonts(reader, {"LiberationSans"})}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--consumer-build-directory", type=Path)
    args = parser.parse_args()
    versions = {name: metadata.version(name) for name in ("pypdf", "pypdfium2", "Pillow")}
    require(versions == {"pypdf": "6.10.0", "pypdfium2": "5.13.0", "Pillow": "12.3.0"}, "use the pinned verification environment")
    directory = args.consumer_build_directory
    if directory is None: directory = Path((ROOT / "build/verification/consumer-build-directory.txt").read_text(encoding="utf-8").strip())
    if not directory.is_absolute(): directory = ROOT / directory
    OUTPUT.mkdir(parents=True, exist_ok=True)
    report = {"tools": versions, "consumerBuildDirectory": str(directory)}
    contacts = []
    for name, path, consumer in (("report", FIXTURES / "styled.pdf", False),
                                 ("consumer", directory / "output/consumer-contents-style.pdf", True)):
        report[name] = check_pdf(path, consumer)
        first, second = render(path, OUTPUT / name)
        require(len(first) == len(second) == 2, "contents independent renderer omitted a complete page")
        differences = [difference(a, b, 2) for a, b in zip(first, second, strict=True)]
        require(all(value["meanAbsoluteChannelError"] < 4 and value["changedPixelFraction"] < .06 for value in differences),
                "contents independent renderers disagree")
        report[name]["rendererDifferences"] = differences
        contacts.extend((f"{name} {index}", page) for index, page in enumerate(first, 1))
    require(len(contacts) == 4, "complete contents fixture/caller page budget differs")
    contact_sheet(contacts, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"pages": 4, "contentsStyles": "passed", "fixedLabelColumn": "passed", "finalNavigation": "passed"}, indent=2))


if __name__ == "__main__":
    main()
