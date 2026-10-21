# SPDX-License-Identifier: Apache-2.0
"""Independently check rich semantic continuation inside merged cells and repeated grid headers.

Run after :examples:gridSemanticFlow and the isolated Maven consumer in the pinned project
Python environment. Original sources, field values and page/column constraints are specified
here; Java manifests only cross-check provenance and final independently painted geometry.
Every new PDF page is rendered by both Poppler and PDFium. No baseline is updated.
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
from verify_inline_images import image_boxes
from verify_multi_floats import pdf_geometry
from verify_production import actual_text, contact_sheet
from verify_semantic_table_flow import blue_ink
from verify_semantic_tables import annotations, check_characters, close, page_logical, table_borders

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "examples/build/grid-semantic-flow"
OUTPUT = ROOT / "build/verification/grid-semantic-flow"
TOLERANCE = .003
# Physical page and table geometry follow the declared 4 pt outer padding, 3 pt FlowColumn
# padding/gap, 24 pt table headers and 18 pt body lines. The introductory label consumes
# another 21 pt only in the first occupied cell fragment.
REGIONS = [(0, (28, 100, 364, 24), (31, 156, 265, 156), list(range(1, 8))),
           (1, (28, 28, 364, 24), (31, 63, 265, 264), list(range(8, 21))),
           (2, (28, 28, 364, 24), (31, 63, 265, 174), list(range(21, 29)))]
COLUMNS = [(0, (28, 128, 180, 24), (31, 184, 127, 192), list(range(1, 10))),
           (0, (232, 128, 180, 24), (235, 163, 127, 210), list(range(10, 20))),
           (1, (28, 28, 180, 24), (31, 63, 127, 192), list(range(20, 29)))]


def source_lines(prefix):
    result = []
    for number in range(1, 29):
        line = f"{prefix}-{number:02}"
        if number == 4:
            line += " P=\ufffc"
        if number == 14:
            line += " N=\ufffc"
        if number == 22:
            line += " R=\ufffc"
        if number in (12, 26):
            line += f" OPEN{number} \ufffc"
        if number == 18:
            line += " a\u0301"
        result.append(line)
    return result


def resolved_line(prefix, number, page, pages):
    line = source_lines(prefix)[number - 1]
    if number == 4:
        line = line.replace("\ufffc", str(page + 1))
    if number == 14:
        line = line.replace("\ufffc", str(pages))
    if number == 22:
        line = line.replace("\ufffc", "1")
    return line


def text_by_page(reader):
    return [(page.extract_text() or "") + "\n" + "\n".join(page_logical(page)) for page in reader.pages]


def same_box(actual, expected):
    return actual["pageIndex"] == expected["pageIndex"] and all(
        abs(float(actual[key]) - float(expected[key])) < TOLERANCE for key in ("x", "y", "width", "height"))


def check_sources(name, prefix):
    source = json.loads((FIXTURES / f"{name}-source.json").read_text(encoding="utf-8"))
    lower = prefix.lower()
    expected = [(f"{lower}-grid-header", 0, f"GRID {prefix} P=\ufffc N=\ufffc"),
                (f"{lower}-merged-content", 0, f"H{prefix} P=\ufffc N=\ufffc"),
                (f"{lower}-merged-content", 1, "\n".join(source_lines(prefix)))]
    require(len(source) == len(expected), "original semantic source count differs")
    for actual, (table, row, text) in zip(source, expected, strict=True):
        require(actual["tableId"] == table and actual["row"] == row and actual["column"] == 0
                and actual["source"] == text, "original source identity/text differs from independent fixture")
        require(actual["sourceUtf16Length"] == len(text.encode("utf-16-le")) // 2,
                "original source UTF-16 length differs")
        objects = [match.start() for match in re.finditer("\ufffc", text)]
        images = [match.end() for match in re.finditer(r"OPEN(?:12|26) ", text)] if row == 1 else []
        require(actual["imageIndices"] == images
                and actual["fieldIndices"] == [index for index in objects if index not in images],
                "original image/field UTF-16 offsets differ")
    return source


def check_manifest(name, prefix, reader, width, height, prefix_height):
    manifest = json.loads((FIXTURES / f"{name}-results.json").read_text(encoding="utf-8"))
    pages = len(reader.pages)
    require(manifest["prefix"] == prefix and manifest["pages"] == pages and manifest["bodyPageOffset"] == 0,
            "manifest final page metadata differs")
    placements = manifest["placements"]
    lower = prefix.lower()
    streams = {kind: [item for item in placements if item["tableId"] == f"{lower}-{kind}"]
               for kind in ("grid-header", "merged-content")}
    require(sum(map(len, streams.values())) == len(placements), "unexpected semantic source appeared")
    contract = REGIONS if name == "regions" else COLUMNS
    for kind, stream in streams.items():
        require(len(stream) == len(contract), "semantic region count differs from declared available-height contract")
        for actual, (page, outer, inner, numbers) in zip(stream, contract, strict=True):
            require(actual["pageIndex"] == page, "semantic fragment used a wrong physical page")
            for key, expected in zip(("x", "y", "width", "height"), outer if kind == "grid-header" else inner, strict=True):
                close(actual[key], expected, f"semantic {kind} {key} differs from independently declared geometry")
            if kind == "merged-content":
                body_lines = actual["rows"][1]["cells"][0]["lines"]
                require([line["logical"].split(" ", 1)[0] for line in body_lines]
                        == [f"{prefix}-{number:02}" for number in numbers],
                        "complete source lines did not use actual short/full region heights")
    borders = table_borders(reader)
    _, anchors, _ = pdf_geometry(reader, 18)
    marked = {(line["page"], line["source"], round(line["x"], 3), round(line["y"], 3)) for line in anchors}
    original_lines = source_lines(prefix)
    starts, position = [], 0
    for line in original_lines:
        starts.append(position)
        position += len(line.encode("utf-16-le")) // 2 + 1
    body_presentations, reference_lines, all_numbers = [], [], []
    for kind, stream in streams.items():
        require(len(stream) >= 2, "fixture did not exercise a repeated semantic stream")
        for index, placement in enumerate(stream):
            page = placement["pageIndex"]
            require(placement["fragmentIndex"] == index and placement["fragmentCount"] == len(stream),
                    "semantic presentation indices/count are not complete and contiguous")
            require(0 <= page < pages, "semantic presentation refers to a nonexistent physical page")
            require(24 <= placement["x"] < placement["x"] + placement["width"] <= width - 24 + TOLERANCE
                    and 24 <= placement["y"] < placement["y"] + placement["height"] <= height - 24 + TOLERANCE,
                    "semantic presentation leaves the actual page body")
            expected_rows = [0] if kind == "grid-header" else [0, 1]
            require([row["row"] for row in placement["rows"]] == expected_rows, "semantic rows lost or reordered")
            for row in placement["rows"]:
                require(row["header"] == (kind == "merged-content" and row["row"] == 0),
                        "semantic row/header identity differs")
                require(row["repeatedHeader"] == (kind == "merged-content" and row["row"] == 0 and index > 0),
                        "semantic table's repeated-header flag differs")
                require(row["continuedRow"] == (kind == "merged-content" and row["row"] == 1 and index > 0),
                        "semantic long-row continuation flag differs")
                require(len(row["cells"]) == 1 and row["cells"][0]["column"] == 0, "original column identity differs")
                cell = row["cells"][0]
                expected_box = {"pageIndex": page, **{key: cell[key] for key in ("x", "y", "width", "height")}}
                require(sum(same_box(border, expected_box) for border in borders) == 1,
                        "semantic cell does not match exactly one independently painted PDF border")
                if row["row"] == 0:
                    header = (f"GRID {prefix}" if kind == "grid-header" else f"H{prefix}") + f" P={page + 1} N={pages}"
                    require(cell["firstLineIndex"] == 0 and len(cell["lines"]) == 1, "header source line changed")
                    expected_source = (f"GRID {prefix}" if kind == "grid-header" else f"H{prefix}") + " P=\ufffc N=\ufffc"
                    line = cell["lines"][0]
                    require(line["logical"] == header and line["start"] == 0 and line["end"] == len(expected_source),
                            "repeated header field was resolved before its actual presentation")
                else:
                    numbers = []
                    for line in cell["lines"]:
                        match = re.fullmatch(rf"{prefix}-(\d{{2}}).*", line["logical"], flags=re.DOTALL)
                        require(match is not None, "a long source line was wrapped, dropped or unexpectedly split")
                        number = int(match.group(1))
                        require(1 <= number <= 28, "long source line number is outside fixture")
                        require(line["logical"] == resolved_line(prefix, number, page, pages),
                                "body field/text differs from source and final physical page")
                        start = starts[number - 1]
                        end = start + len(original_lines[number - 1].encode("utf-16-le")) // 2
                        require(line["start"] == start and line["end"] in (end, end + int(number < 28)),
                                "long-row original UTF-16 range differs")
                        if number == 22:
                            reference_lines.append({"pageIndex": page, **line})
                        body_presentations.append({"pageIndex": page, "number": number, **line})
                        numbers.append(number)
                    require(numbers and cell["firstLineIndex"] == numbers[0] - 1,
                            "continued cell firstLineIndex lost its original complete-line origin")
                    all_numbers.extend(numbers)
                for line in cell["lines"]:
                    require(cell["x"] + 3 - TOLERANCE <= line["x"]
                            and line["x"] + line["width"] <= cell["x"] + cell["width"] - 3 + TOLERANCE
                            and cell["y"] + 3 - TOLERANCE <= line["y"]
                            and line["y"] + 18 <= cell["y"] + cell["height"] - 3 + TOLERANCE,
                            "a complete line leaves its declared padded semantic cell")
                    must_be_marked = row["row"] == 0 or any(
                        line["logical"].startswith(f"{prefix}-{number:02}") for number in (4, 12, 14, 18, 22, 26))
                    if must_be_marked:
                        require(page_logical(reader.pages[page]).count(line["logical"]) >= 1,
                                "final PDF lost a field/image/complex logical source line")
                    if must_be_marked or line["logical"] in page_logical(reader.pages[page]):
                        require((page, line["logical"], round(line["x"], 3), round(line["y"], 3)) in marked,
                                "manifest source line does not match the final PDF's painted logical anchor")
    require(all_numbers == list(range(1, 29)), "long merged semantic source lines lost, repeated or reordered")
    outer = streams["grid-header"]
    body = streams["merged-content"]
    require(len(outer) == len(body), "every occupied merged region must retain both repeated headers")
    for header, content in zip(outer, body, strict=True):
        require(header["pageIndex"] == content["pageIndex"], "nested content moved away from its outer header")
        close(content["x"], header["x"] + 3, "nested FlowColumn horizontal padding differs")
        close(content["width"], (header["width"] + 8) * .75 - 14, "merged 3:1 cell width or nested padding differs")
        require(content["y"] >= header["y"] + header["height"] + 11 - TOLERANCE,
                "nested content overlaps repeated header or padding")
    close(outer[0]["x"], 28, "short first region lost page/outer-cell padding")
    close(outer[0]["y"], 28 + prefix_height, "short first region ignored already occupied page height")
    if name == "columns":
        require(len(outer) >= 3 and outer[0]["pageIndex"] == outer[1]["pageIndex"] == 0,
                "short initial columns were not both used")
        close(outer[1]["x"] - outer[0]["x"], 204, "continuous column gap/width differs")
        close(outer[0]["y"], outer[1]["y"], "short initial column tops differ")
        close(outer[0]["width"], 180, "two-column repeated header width differs")
    else:
        require(all(item["pageIndex"] == index for index, item in enumerate(outer)),
                "single-column region stream did not advance one physical page at a time")
        close(outer[0]["width"], 364, "full-width repeated header width differs")
    return streams, body_presentations, reference_lines, len(borders)


def check_links(reader, prefix, body, references):
    links = annotations(reader)
    external = [item for item in links if "uri" in item]
    internal = [item for item in links if "targetPageIndex" in item]
    require(len(external) == 4 and len(internal) == len(references) == 1, "source links lost, repeated or invented")
    reference, source = internal[0], references[0]
    require(reference["pageIndex"] == source["pageIndex"] and reference["targetPageIndex"] == 0,
            "reference did not follow its actual source/target physical pages")
    close(reference["destinationX"], 24, "reference target x differs")
    close(reference["destinationY"], 24, "reference target y differs")
    close(reference["width"], 14, "reference's reserved inline width differs")
    close(reference["height"], 18, "reference's complete line box differs")
    close(reference["y"], source["y"], "reference left its final source line")
    require(source["x"] < reference["x"] < source["x"] + source["width"], "reference reverted to whole-cell origin")
    images = image_boxes(reader)
    ink = blue_ink(reader)
    require(len(images) == 2 and len(ink) == 2, "inline source images/colored hyperlink ink lost or repeated")
    lower = prefix.lower()
    for number, image in zip((12, 26), images, strict=True):
        source = next(item for item in body if item["number"] == number)
        picture = [item for item in external if item["uri"] == f"https://example.com/grid-{lower}-image-{number}"]
        text = [item for item in external if item["uri"] == f"https://example.com/grid-{lower}-text-{number}"]
        require(len(picture) == len(text) == 1 and image["page"] == source["pageIndex"]
                == picture[0]["pageIndex"] == text[0]["pageIndex"], "image/text link moved away from source line")
        require(image["pixels"] == [8, 8], "inline image encoded pixel size changed")
        close(image["width"], 12, "inline image width differs")
        close(image["height"], 12, "inline image height differs")
        for key in ("x", "y", "width", "height"):
            close(picture[0][key], image[key], f"inline image link {key} differs from actual painted pixels")
        close(text[0]["y"], source["y"], "ordinary hyperlink left its actual complete source line")
        close(text[0]["height"], 18, "ordinary hyperlink line box differs")
        painted = [item for item in ink if item["pageIndex"] == source["pageIndex"]]
        require(len(painted) == 1, "colored hyperlink ink is on a wrong or duplicate page")
        close(text[0]["x"], painted[0]["x"], "ordinary hyperlink left misses actual painted ink")
        close(text[0]["x"] + text[0]["width"], painted[0]["right"], "ordinary hyperlink right misses actual underline")
        require(text[0]["x"] + text[0]["width"] <= image["x"] + TOLERANCE,
                "ordinary hyperlink rectangle overlaps the separate inline image link")
    return links, images


def check_fixture(name, prefix, width, height, prefix_height):
    path = FIXTURES / f"{name}.pdf"
    reader = PdfReader(path)
    require(len(reader.pages) == (3 if name == "regions" else 2) and not reader.get_fields(),
            "fixture page count/static-document contract differs")
    for page in reader.pages:
        close(page.mediabox.width, width, "physical page width differs")
        close(page.mediabox.height, height, "physical page height differs")
    sources = check_sources(name, prefix)
    streams, body, references, borders = check_manifest(name, prefix, reader, width, height, prefix_height)
    page_text = text_by_page(reader)
    text = "\n".join(page_text)
    require(re.findall(rf"{prefix}-(\d{{2}})", text) == [f"{number:02}" for number in range(1, 29)],
            "final PDF source lines lost, repeated or reordered")
    for source in body:
        require(page_text[source["pageIndex"]].count(f"{prefix}-{source['number']:02}") == 1,
                "manifest source line is not on its actual PDF page")
    for label in (f"{prefix}-PREFIX", f"{prefix}-END", f"MERGED {prefix}", f"CELL END {prefix}", "S-A", "S-B"):
        require(text.count(label) == 1, "surrounding merged-grid content lost or repeated")
    for kind, stream in streams.items():
        for item in stream:
            header = (f"GRID {prefix}" if kind == "grid-header" else f"H{prefix}") + f" P={item['pageIndex'] + 1} N={len(reader.pages)}"
            require(page_logical(reader.pages[item["pageIndex"]]).count(header)
                    == sum(1 for value in stream if value["pageIndex"] == item["pageIndex"]),
                    "final PDF repeated header page/total values differ")
    links, images = check_links(reader, prefix, body, references)
    emphasis = (.4, .133333, .266667)
    require(any(operator == b"rg" and all(abs(float(a) - b) < .00001 for a, b in zip(operands, emphasis, strict=True))
                for page in reader.pages for operands, operator in page.get_contents().operations),
            "explicit rich-text emphasis color disappeared")
    svg = ET.parse(FIXTURES / f"{name}-preview.svg").getroot()
    ns = "{http://www.w3.org/2000/svg}"
    require(len(svg.findall(f"{ns}svg")) == len(reader.pages), "static SVG omitted a physical page")
    require(len(svg.findall(f".//{ns}image")) == 2 and not svg.findall(f".//{ns}a"), "static SVG image/interaction contract differs")
    logical = [item.get("aria-label") for item in svg.iter(f"{ns}g") if item.get("aria-label") is not None]
    marked = actual_text(reader)
    require([value for value in logical if value in set(marked)] == marked, "static SVG final logical order differs from PDF")
    return {"pages": len(reader.pages), "originalSemanticCells": len(sources), "bodyLines": len(body),
            "outerHeaderPresentations": len(streams["grid-header"]), "innerTableFragments": len(streams["merged-content"]),
            "paintedCellBorders": borders, "links": links, "images": images,
            "characters": check_characters(path, 24), "originalUtf16AndFirstLinesChecked": True,
            "staticSvgChecked": True}


def check_consumer(path):
    reader = PdfReader(path)
    require(len(reader.pages) == 3 and not reader.get_fields(), "consumer must use three bounded complete-line regions")
    all_numbers = []
    for page_index, page in enumerate(reader.pages):
        close(page.mediabox.width, 320, "consumer physical page width differs")
        close(page.mediabox.height, 240, "consumer physical page height differs")
        logical = page_logical(page)
        header = f"CONSUMER-GRID P={page_index + 1} T=3"
        require(logical.count(header) == 1, "consumer repeated grid header has wrong actual page/total")
        body = [line for line in logical if line.startswith("CGF")]
        expected = [f"CGF{number:03} P={page_index + 1}" for number in range(page_index * 8 + 1, page_index * 8 + 9)]
        require(body == expected and logical == [header, *expected], "consumer body page fields/source order differ")
        all_numbers.extend(int(match) for match in re.findall(r"CGF(\d{3})", "\n".join(body)))
    require(all_numbers == list(range(1, 25)), "consumer source lines lost, repeated or reordered")
    require(not annotations(reader) and not image_boxes(reader), "consumer unexpectedly contains interactions/images")
    _, anchors, _ = pdf_geometry(reader, 20)
    require([item["source"] for item in anchors] == actual_text(reader), "consumer logical field anchor order differs")
    for page in range(3):
        header = next(item for item in anchors if item["page"] == page)
        close(header["x"], 21, "consumer repeated header padding x differs")
        close(header["y"], 21, "consumer repeated header padding y differs")
    return {"pages": 3, "headers": 3, "bodyLines": 24, "actualPageAndTotalFieldsChecked": True,
            "characters": check_characters(path, 16)}


def render_checked(path, name, result, contacts):
    poppler, pdfium = render(path, OUTPUT / name)
    require(len(poppler) == len(pdfium) == result["pages"], "independent renderer omitted a physical page")
    comparisons = [difference(a, b, 2) for a, b in zip(poppler, pdfium, strict=True)]
    require(all(item["meanAbsoluteChannelError"] < 4 and item["changedPixelFraction"] < .06 for item in comparisons),
            "independent renderers disagree")
    result["rendererDifferences"] = comparisons
    contacts.extend((f"{name} page {index + 1}", page) for index, page in enumerate(poppler))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--consumer-build-directory", type=Path)
    args = parser.parse_args()
    versions = {name: metadata.version(name) for name in ("pypdf", "pypdfium2", "Pillow")}
    require(versions == {"pypdf": "6.10.0", "pypdfium2": "5.13.0", "Pillow": "12.3.0"},
            "use the pinned project Python environment from requirements-verification.txt")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    report = {"tools": versions, "renderComparison": {"dpi": 192, "blurPixels": 2,
              "meanLimit": 4, "changedFractionLimit": .06}}
    contacts = []
    for contract in (("regions", "REG", 420, 360, 72), ("columns", "COL", 440, 420, 100)):
        name = contract[0]
        result = check_fixture(*contract)
        render_checked(FIXTURES / f"{name}.pdf", name, result, contacts)
        report[name] = result
    require(report["regions"]["pages"] + report["columns"]["pages"] <= 8, "sample page budget exceeded")
    consumer_directory = args.consumer_build_directory
    if consumer_directory is None:
        pointer = ROOT / "build/verification/consumer-build-directory.txt"
        require(pointer.is_file(), "run verify_local_consumer.ps1 or pass --consumer-build-directory")
        consumer_directory = Path(pointer.read_text(encoding="utf-8").strip())
    if not consumer_directory.is_absolute():
        consumer_directory = ROOT / consumer_directory
    consumer = consumer_directory / "output/consumer-grid-semantic-flow.pdf"
    require(consumer.is_file(), "successful isolated consumer merged-semantic PDF is missing")
    result = check_consumer(consumer)
    render_checked(consumer, "consumer", result, contacts)
    report["consumerBuildDirectory"] = str(consumer_directory)
    report["consumer"] = result
    contact_sheet(contacts, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
