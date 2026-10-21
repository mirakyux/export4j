# SPDX-License-Identifier: Apache-2.0
"""Check independently declared XML theme contracts in final PDF/SVG and standard OOXML.

The small fixtures and isolated caller are double-rendered in full. No visual baseline
or generated Java style/geometry manifest supplies expected values.
"""
from __future__ import annotations

import argparse
import hashlib
from importlib import metadata
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from PIL import ImageFont
from pypdf import PdfReader
from verify_adr_form import render
from verify_advanced_pdf import difference, require
from verify_inline_images import image_boxes
from verify_production import actual_text, contact_sheet
from verify_semantic_tables import check_characters, close, inline_ink_bounds, table_borders
from verify_streaming_rich_text import decoration, outlined_paths, painted_spans, physical_fonts, same_color, strokes
from verify_themes import paragraph_format, svg_check, text_of, token

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "examples/build/xml-themes"
OUTPUT = ROOT / "build/verification/xml-themes"
W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
R = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}"
WP = "{http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing}"
A = "{http://schemas.openxmlformats.org/drawingml/2006/main}"
OD = "{urn:com.mirakyux.export4j:semantic:1}"
BLACK, BLUE, RED = (0, 0, 0), (34 / 255, 68 / 255, 136 / 255), (136 / 255, 68 / 255, 34 / 255)
CONFIG = {
    "blue": {"body": 11, "line": 20, "first": 4, "subsequent": 8, "cell": 10, "cellLine": 18,
             "headings": [20, 19, 18, 17, 16, 15], "padding": 4, "accent": BLUE, "tableTop": 345,
             "outerHeight": 94, "dynamicTop": 445, "innerTop": 389},
    "compact": {"body": 10, "line": 16, "first": 0, "subsequent": 4, "cell": 9, "cellLine": 14,
                "headings": [18, 17, 16, 15, 14, 13], "padding": 2, "accent": RED, "tableTop": 317,
                "outerHeight": 82, "dynamicTop": 405, "innerTop": 359},
}


def styles(spans, contracts):
    for text, (size, color) in contracts.items():
        value = token(spans, text)
        close(value["size"], size, "XML theme point size differs: " + text)
        require(same_color(value["color"], color), "XML theme RGB differs: " + text)


def border(borders, x, y, width, height):
    matches = [value for value in borders if value["pageIndex"] == 0
               and abs(value["x"] - x) < .003 and abs(value["y"] - y) < .003]
    require(len(matches) == 1, "XML theme table has no unique expected border")
    close(matches[0]["width"], width, "XML theme table width differs")
    close(matches[0]["height"], height, "XML theme table height differs")


def paragraph(nodes, text):
    pattern = re.compile(r"(?<![A-Z0-9-])" + re.escape(text) + r"(?![A-Z0-9-])")
    found = [node for node in nodes if pattern.search(text_of(node))]
    require(len(found) == 1, "native XML theme paragraph missing/repeated: " + text)
    return found[0]


def run_properties(node, text):
    found = [run for run in node.iter(W + "r") if text_of(run).strip() == text]
    require(len(found) == 1, "native named inline run missing/repeated: " + text)
    return found[0].find(W + "rPr")


def named_run(properties, size, accent, underline=True, shift=0):
    close(float(properties.find(W + "sz").get(W + "val")) / 2, size, "native named run size differs")
    require(properties.find(W + "rStyle").get(W + "val") == "bold", "native named font role lost")
    rgb = "".join(f"{round(value * 255):02X}" for value in accent)
    require(properties.find(W + "color").get(W + "val") == rgb, "native named RGB differs")
    require((properties.find(W + "u") is not None) == underline, "native named underline differs")
    close(float(properties.find(W + "position").get(W + "val")) / -2, shift, "native named baseline shift differs")


def package(path):
    with ZipFile(path) as archive:
        root = ET.fromstring(archive.read("word/document.xml"))
        style_root = ET.fromstring(archive.read("word/styles.xml"))
        require(not any(name.startswith("word/media/") for name in archive.namelist()), "static XML theme unexpectedly embeds media")
    definitions = [node for node in style_root.iter(W + "style") if node.get(W + "styleId") == "bold"]
    require(len(definitions) == 1 and definitions[0].find(W + "rPr/" + W + "b") is not None,
            "native XML theme real bold definition missing")
    return root


def check_docx(path, config, consumer=False):
    root = package(path)
    nodes = list(root.iter(W + "p"))
    if consumer:
        checks = {"XML-THEME-CONSUMER": (18, 28), "XML-THEME-BODY": (11, 20),
                  "XML-THEME-NOTE": (9, 18), "XML-THEME-KEY": (11, 20),
                  "XML-THEME-CELL": (10, 18), "XML-THEME-BLOCK": (9, 18)}
        keys, padding = ["XML-THEME-KEY"], 5
        accent, body, table_count = BLUE, 11, 1
        require("XML-THEME-NOTE OK" == text_of(paragraph(nodes, "XML-THEME-NOTE")), "native caller data binding differs")
    else:
        body, accent, padding, table_count = config["body"], config["accent"], config["padding"], 3
        checks = {text: (body, config["line"]) for text in ("BODY-A", "KEY", "RAISED-BASE", "BODY-END")}
        checks.update({"NAMED-P": (9, 18), "OVERRIDE12": (12, 18), "BLOCK-LOCAL": (8, 14),
                       "NAMED-CELL": (9, 18), "BLOCK-END": (8, 14)})
        checks.update({text: (config["cell"], config["cellLine"]) for text in
                       ("INNER-DEFAULT", "DEFAULT-CELL", "RECORD", "STATUS", "ROW-01", "ACCEPT-01", "ROW-02", "ACCEPT-02")})
        for level, size in enumerate(config["headings"], 1):
            label = "XML-TITLE" if level == 1 else f"SECTION-{level}"
            checks[label] = (size, size + 8)
            properties = paragraph(nodes, label).find(W + "pPr")
            require(properties.find(W + "pStyle").get(W + "val") == f"Heading{level}"
                    and properties.find(W + "keepNext") is not None and properties.find(W + "keepLines") is not None,
                    "native XML theme heading level/adjacency lost")
        keys = ["KEY", "HEADING-KEY", "ACCEPT-01", "ACCEPT-02"]
        named_run(run_properties(paragraph(nodes, "RAISED-BASE"), "RAISED"), 8, accent, False, -2)
        reset = run_properties(paragraph(nodes, "RESET"), "RESET")
        require(reset.find(W + "rStyle") is None and reset.find(W + "u") is None
                and reset.find(W + "strike") is None and reset.find(W + "color").get(W + "val") == "000000",
                "native explicit empty role/black/false decorations were replaced")
        for label in ("NAMED-P", "NAMED-CELL"):
            spacing = paragraph(nodes, label).find(W + "pPr/" + W + "spacing")
            close(float(spacing.get(W + "before")) / 20, 3, "native named before spacing lost inside/outside cell")
            close(float(spacing.get(W + "after")) / 20, 5, "native named after spacing lost inside/outside cell")
        require(text_of(paragraph(nodes, "XML-TITLE")) == "XML-TITLE Quarterly review"
                and text_of(paragraph(nodes, "BODY-END")) == "BODY-END Accepted after review.", "native XML binding differs")
    for text, (size, line) in checks.items(): paragraph_format(paragraph(nodes, text), size, line)
    for key in keys: named_run(run_properties(paragraph(nodes, key), key), body, accent)
    tables = list(root.iter(W + "tbl"))
    require(len(tables) == table_count, "native XML theme table hierarchy differs")
    for table in tables:
        margins = table.find(W + "tblPr/" + W + "tblCellMar")
        for side in ("top", "right", "bottom", "left"):
            close(float(margins.find(W + side).get(W + "w")) / 20, padding, "native XML theme table padding differs")
    if not consumer:
        require(len(tables[0].findall(W + "tr")) == len(tables[1].findall(W + "tr")) == 1
                and len(tables[2].findall(W + "tr")) == 3, "native nested/dynamic table row counts differ")
        require(len(tables[0].findall(".//" + W + "tbl")) == 1, "native atomic inner table was flattened")
        require(tables[2].find(W + "tr/" + W + "trPr/" + W + "tblHeader") is not None, "native dynamic repeated header lost")
    return {"paragraphFormatChecks": len(checks), "namedInlineChecks": len(keys) + (0 if consumer else 2), "nativeTables": len(tables)}


def check_report(name, config):
    path = FIXTURES / (name + ".pdf")
    reader = PdfReader(path, strict=True)
    require(len(reader.pages) == 1 and not reader.get_fields() and not reader.outline and not reader.pages[0].get("/Annots"),
            "static XML theme page/interaction contract differs")
    close(reader.pages[0].mediabox.width, 500, "XML theme width differs")
    close(reader.pages[0].mediabox.height, 760, "XML theme height differs")
    spans = painted_spans(reader)
    contracts = {text: (config["body"], BLACK) for text in ("BODY-A", "BODY-B", "VALUE", "RESET", "RAISED-BASE", "BODY-END")}
    contracts.update({text: (config["body"], config["accent"]) for text in ("KEY", "HEADING-KEY", "ACCEPT-01", "ACCEPT-02")})
    contracts.update({"NAMED-P": (9, BLACK), "OVERRIDE12": (12, BLACK), "RAISED": (8, config["accent"]),
                      "BLOCK-LOCAL": (8, BLACK), "NAMED-CELL": (9, BLACK), "BLOCK-END": (8, BLACK)})
    contracts.update({text: (config["cell"], BLACK) for text in ("INNER-DEFAULT", "DEFAULT-CELL", "RECORD", "STATUS", "ROW-01", "ROW-02")})
    contracts.update({"XML-TITLE" if level == 1 else f"SECTION-{level}": (size, BLACK)
                      for level, size in enumerate(config["headings"], 1)})
    styles(spans, contracts)
    first, second = token(spans, "BODY-A"), token(spans, "BODY-B")
    close(first["x"], 24 + config["first"], "XML theme body first indent differs")
    close(second["x"], 24 + config["subsequent"], "XML theme body subsequent indent differs")
    close(second["baseline"] - first["baseline"], config["line"], "XML theme body line height differs")
    for text in ("NAMED-P", "OVERRIDE12"): close(token(spans, text)["x"], 24, "complete named zero indent lost")
    close(token(spans, "RAISED")["baseline"] - token(spans, "RAISED-BASE")["baseline"], -2, "named inline baseline shift lost")
    require(token(spans, "RESET")["font"].endswith("LiberationSans"), "empty XML role did not select regular font")
    lines = strokes(reader)
    require(len(lines) == 4, "named inline underline lost/repeated or explicit false ignored")
    for text in ("KEY", "HEADING-KEY", "ACCEPT-01", "ACCEPT-02"):
        span = token(spans, text)
        require(span["font"].endswith("LiberationSans-Bold"), "named XML role did not select actual bold font")
        decoration(span, lines, max(.5, config["body"] / 10))
    fonts = physical_fonts(reader, {"LiberationSans", "LiberationSans-Bold"})
    borders, padding = table_borders(reader), config["padding"]
    require(len(borders) == 9, "XML static/inner/dynamic border count differs")
    border(borders, 24, config["tableTop"], 226, config["outerHeight"])
    border(borders, 250, config["tableTop"], 226, config["outerHeight"])
    border(borders, 24 + padding, config["innerTop"], 226 - 2 * padding, config["cellLine"] + 2 * padding)
    for row in range(3):
        for column in range(2):
            border(borders, 24 + column * 226, config["dynamicTop"] + row * (config["cellLine"] + 2 * padding),
                   226, config["cellLine"] + 2 * padding)
    for text in ("BLOCK-LOCAL", "NAMED-CELL", "BLOCK-END", "RECORD", "ROW-01", "ROW-02"):
        close(token(spans, text)["x"], 24 + padding, "XML theme outer padding differs")
    close(token(spans, "INNER-DEFAULT")["x"], 24 + 2 * padding, "inner table failed to reset theme defaults/padding")
    order = ["XML-TITLE", "BODY-A", "BODY-B", "NAMED-P", "OVERRIDE12", "KEY", "RESET", "RAISED-BASE",
             "SECTION-2", "HEADING-KEY", "SECTION-3", "SECTION-4", "SECTION-5", "SECTION-6", "BLOCK-LOCAL",
             "NAMED-CELL", "INNER-DEFAULT", "BLOCK-END", "DEFAULT-CELL", "RECORD", "STATUS", "ROW-01",
             "ACCEPT-01", "ROW-02", "ACCEPT-02", "BODY-END"]
    indices = [spans.index(token(spans, text)) for text in order]
    require(indices == sorted(indices), "XML theme changed bound semantic source order")
    require("Quarterly review" in token(spans, "XML-TITLE")["text"]
            and "Accepted after review." in token(spans, "BODY-END")["text"], "final XML data binding differs")
    svg_check(FIXTURES / (name + ".svg"), spans)
    return {"pages": 1, "styleChecks": len(contracts), "tableBorders": len(borders), "physicalFonts": fonts,
            "characters": check_characters(path, 24), "docx": check_docx(FIXTURES / (name + ".docx"), config)}


def check_interactive():
    path = FIXTURES / "interactive.pdf"
    reader = PdfReader(path, strict=True)
    require(len(reader.pages) == 1 and not reader.get_fields(), "XML theme interaction page/form contract differs")
    close(reader.pages[0].mediabox.width, 500, "interactive XML theme width differs")
    close(reader.pages[0].mediabox.height, 260, "interactive XML theme height differs")
    require(actual_text(reader) == ["LINK-KEY \ufffc", "XML-FIELDS page=1 total=1 target=1 number=3"],
            "themed XML final page/total/reference/number fields differ")
    require("3.NUMBER-TARGETReviewed." in "".join(reader.pages[0].extract_text().split()), "themed XML numbered target differs")
    spans = painted_spans(reader)
    styles(spans, {"XML-INTERACTIVE": (20, BLACK), "NUMBER-TARGET": (11, BLACK)})
    # Image/link/field paragraphs use painted outlines plus ActualText, not CID text shows.
    # Independently measure the complete named bold advance with Pillow's FreeType engine.
    ink = inline_ink_bounds(reader, "LINK-KEY \ufffc")
    paths = [value for value in outlined_paths(reader) if same_color(value["color"], BLUE)]
    require(len(ink) == len(paths) == 1, "named linked XML outline/color missing or repeated")
    close(ink[0]["x"], 24, "named linked XML outline start differs")
    font = ImageFont.truetype(str(ROOT / "assets/fonts/LiberationSans-Bold.ttf"), 11_000)
    close(ink[0]["right"] - ink[0]["x"], font.getlength("LINK-KEY") / 1000,
          "linked named XML 11 pt bold advance differs from independent FreeType")
    require(paths[0]["left"] == ink[0]["x"] and paths[0]["right"] == ink[0]["right"]
            and 57 <= paths[0]["top"] < paths[0]["bottom"] <= 75, "linked named outline leaves its fixed line box")
    physical_fonts(reader, {"LiberationSans"})
    images = image_boxes(reader)
    require(len(images) == 1, "themed XML linked image missing/repeated")
    external, destinations = {}, []
    for reference in reader.pages[0].get("/Annots", []):
        link = reference.get_object()
        require(link["/Subtype"] == "/Link", "unexpected XML theme annotation")
        left, bottom, right, top = map(float, link["/Rect"])
        require(24 <= left < right <= 476 and 24 <= bottom < top <= 236, "themed XML hyperlink leaves margins")
        if "/A" in link:
            require(link["/A"]["/S"] == "/URI", "themed XML link has unexpected action")
            uri = str(link["/A"]["/URI"])
            require(uri not in external, "themed XML external link repeated")
            external[uri] = (left, 260 - top, right - left, top - bottom)
        else:
            require(link["/Dest"][0].idnum == reader.pages[0].indirect_reference.idnum,
                    "themed XML final reference points outside target page")
            close(right - left, 16, "themed XML field link lost reserved width")
            destinations.append(link["/Dest"])
    require(set(external) == {"https://example.com/xml-theme-text", "https://example.com/xml-theme-image"}
            and len(destinations) == 2, "themed XML links lost/repeated")
    left, _, width, _ = external["https://example.com/xml-theme-text"]
    close(left, ink[0]["x"], "named text hyperlink misses final text start")
    close(left + width, ink[0]["right"], "named text hyperlink misses final text advance")
    for actual, expected in zip(external["https://example.com/xml-theme-image"],
                                (images[0]["x"], images[0]["y"], 12, 12), strict=True):
        close(actual, expected, "named XML image hyperlink misses actual image rectangle")
    require((FIXTURES / "static-rejected.txt").read_text(encoding="utf-8") == "INTERACTIVE_SVG_REJECTED_BEFORE_WRITE\n",
            "interactive static backend rejection missing")
    with ZipFile(FIXTURES / "interactive.docx") as archive:
        root = ET.fromstring(archive.read("word/document.xml"))
        relationships = ET.fromstring(archive.read("word/_rels/document.xml.rels"))
        targets = {node.get("Id"): node.get("Target") for node in relationships}
        fields = root.findall(".//" + W + "fldSimple")
        require([node.get(W + "instr").split()[0] for node in fields] == ["PAGE", "NUMPAGES", "PAGEREF", "REF"],
                "themed XML native field order/kinds differ")
        require(all(float(node.get(OD + "width")) == 16 for node in fields), "themed XML native field widths lost")
        names = {node.get(W + "name") for node in root.iter(W + "bookmarkStart")}
        require(all(node.get(W + "instr").split()[1] in names for node in fields[2:]), "themed XML native field target missing")
        hyperlinks = root.findall(".//" + W + "hyperlink")
        image_links = root.findall(".//" + WP + "docPr/" + A + "hlinkClick")
        require(len(hyperlinks) == len(image_links) == 1
                and targets[hyperlinks[0].get(R + "id")] == "https://example.com/xml-theme-text"
                and targets[image_links[0].get(R + "id")] == "https://example.com/xml-theme-image",
                "themed XML native text/image hyperlink relationships lost")
        require(sum(name.startswith("word/media/") for name in archive.namelist()) == 1, "themed XML native offline media lost")
        named_run(run_properties(hyperlinks[0], "LINK-KEY"), 11, BLUE)
    return {"pages": 1, "finalFields": 4, "internalLinks": 2, "externalLinks": 2, "images": 1,
            "characters": check_characters(path, 24), "nativeDocxFieldsAndLinks": "passed", "interactiveSvgRejection": "passed"}


def check_consumer(directory):
    base = directory / "output/consumer-xml-theme"
    reader = PdfReader(base.with_suffix(".pdf"), strict=True)
    require(len(reader.pages) == 1 and not reader.get_fields() and not reader.pages[0].get("/Annots"), "XML theme caller page/interaction count differs")
    close(reader.pages[0].mediabox.width, 400, "XML theme caller width differs")
    close(reader.pages[0].mediabox.height, 400, "XML theme caller height differs")
    spans = painted_spans(reader)
    contracts = {"XML-THEME-CONSUMER": (18, BLACK), "XML-THEME-BODY": (11, BLACK), "XML-THEME-NOTE": (9, BLACK),
                 "XML-THEME-KEY": (11, BLUE), "XML-THEME-VALUE": (11, BLACK), "XML-THEME-CELL": (10, BLACK), "XML-THEME-BLOCK": (9, BLACK)}
    styles(spans, contracts)
    key = token(spans, "XML-THEME-KEY")
    require(key["font"].endswith("LiberationSans-Bold"), "XML theme caller did not use real bold")
    decoration(key, strokes(reader), 1.1)
    physical_fonts(reader, {"LiberationSans", "LiberationSans-Bold"})
    borders = table_borders(reader)
    require(len(borders) == 2, "XML theme caller table border count differs")
    border(borders, 20, 122, 180, 28)
    border(borders, 200, 122, 180, 28)
    close(token(spans, "XML-THEME-CELL")["x"], 25, "XML theme caller table padding differs")
    require("OK" in token(spans, "XML-THEME-NOTE")["text"], "XML theme caller data binding differs")
    svg_check(base.with_suffix(".svg"), spans)
    return {"pages": 1, "styleChecks": len(contracts), "characters": check_characters(base.with_suffix(".pdf"), 20),
            "docx": check_docx(base.with_suffix(".docx"), {}, True)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--consumer-build-directory", type=Path)
    args = parser.parse_args()
    versions = {name: metadata.version(name) for name in ("pypdf", "pypdfium2", "Pillow")}
    require(versions == {"pypdf": "6.10.0", "pypdfium2": "5.13.0", "Pillow": "12.3.0"}, "use the pinned verification environment")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    report, contacts, sources = {"tools": versions}, [], []
    fonts = json.loads((FIXTURES / "fonts.json").read_text(encoding="utf-8"))["fonts"]
    require(len(fonts) == 2, "XML theme runtime font manifest differs")
    for font in fonts:
        data = (ROOT / "assets/fonts" / font["resource"]).read_bytes()
        require(font["sha256"] == hashlib.sha256(data).hexdigest() and font["bytes"] == len(data),
                "XML theme runtime font differs from explicit source")
    report["fontSources"] = fonts
    for name, config in CONFIG.items():
        report[name] = check_report(name, config)
        sources.append((name, FIXTURES / (name + ".pdf")))
    report["interactive"] = check_interactive()
    sources.append(("interactive", FIXTURES / "interactive.pdf"))
    directory = args.consumer_build_directory
    if directory is None: directory = Path((ROOT / "build/verification/consumer-build-directory.txt").read_text(encoding="utf-8").strip())
    if not directory.is_absolute(): directory = ROOT / directory
    report["consumerBuildDirectory"] = str(directory)
    report["consumer"] = check_consumer(directory)
    sources.append(("consumer", directory / "output/consumer-xml-theme.pdf"))
    for name, path in sources:
        first, second = render(path, OUTPUT / name)
        require(len(first) == len(second) == 1, "XML theme renderer omitted a complete page")
        comparisons = [difference(a, b, 2) for a, b in zip(first, second, strict=True)]
        require(all(value["meanAbsoluteChannelError"] < 4 and value["changedPixelFraction"] < .06 for value in comparisons),
                "XML theme independent renderers disagree")
        report[name]["rendererDifferences"] = comparisons
        contacts.append((name, first[0]))
    require(len(contacts) == 4, "complete XML theme fixture/caller page count differs")
    contact_sheet(contacts, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"pages": len(contacts), "xmlThemePdfSvgDocx": "passed", "finalFieldsAndLinks": "passed"}, indent=2))


if __name__ == "__main__":
    main()
