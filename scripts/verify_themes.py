# SPDX-License-Identifier: Apache-2.0
"""Independently check resolved theme values in final PDF, vector SVG and native OOXML.

Expected typography and geometry are declared here. No generated Java value manifest
supplies them, and no visual baseline is created or updated.
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

from pypdf import PdfReader
from verify_adr_form import render
from verify_advanced_pdf import difference, require
from verify_multi_floats import compose, point
from verify_production import contact_sheet
from verify_semantic_tables import check_characters, close, table_borders
from verify_streaming_rich_text import decoration, painted_spans, physical_fonts, same_color, strokes

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "examples/build/themes"
OUTPUT = ROOT / "build/verification/themes"
SVG = "{http://www.w3.org/2000/svg}"
W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
BLACK, BLUE, RED, GREEN = (0, 0, 0), (34 / 255, 68 / 255, 136 / 255), (136 / 255, 68 / 255, 34 / 255), (.1, .5, .2)
CONFIG = {
    "blue": {"body": 11, "line": 20, "first": 4, "subsequent": 8, "after": 4,
             "headings": [20, 19, 18, 17, 16, 15], "headingLines": [28, 27, 26, 25, 24, 23],
             "headingBefore": 4, "headingAfter": 2, "noteAfter": 4, "cell": 10, "cellLine": 18, "padding": 4, "accent": BLUE},
    "compact": {"body": 10, "line": 16, "first": 0, "subsequent": 4, "after": 4,
                "headings": [18, 17, 16, 15, 14, 13], "headingLines": [26, 25, 24, 23, 22, 21],
                "headingBefore": 4, "headingAfter": 2, "noteAfter": 4, "cell": 9, "cellLine": 14, "padding": 2, "accent": RED},
    "default": {"body": 12, "line": 18, "first": 0, "subsequent": 0, "after": 6,
                "headings": [24, 22, 20, 18, 16, 14], "headingLines": [36, 33, 30, 27, 24, 21],
                "headingBefore": 12, "headingAfter": 8, "noteAfter": 6, "cell": 12, "cellLine": 18, "padding": 6, "accent": BLUE},
}


def token(values, text):
    pattern = re.compile(r"(?<![A-Z0-9-])" + re.escape(text) + r"(?![A-Z0-9-])")
    found = [value for value in values if pattern.search(value["text"])]
    require(len(found) == 1, "theme token missing/repeated: " + text)
    return found[0]


def styles(spans, contracts):
    for text, (size, color) in contracts.items():
        value = token(spans, text)
        close(value["size"], size, "resolved theme point size differs: " + text)
        require(same_color(value["color"], color), "resolved theme color differs: " + text)


def borders_at(reader, top, heights, margin, width):
    borders = table_borders(reader)
    require(len(borders) == 2 * len(heights), "themed table border count differs")
    wanted = []
    for height in heights:
        wanted.extend([(margin, top, width, height), (margin + width, top, width, height)])
        top += height
    for border, expected in zip(borders, wanted, strict=True):
        require(border["pageIndex"] == 0, "themed table unexpectedly continued")
        for key, value in zip(("x", "y", "width", "height"), expected, strict=True):
            close(border[key], value, "themed table physical " + key + " differs")
    return borders


def svg_values(path):
    root = ET.parse(path).getroot()
    require(len(root.findall(SVG + "svg")) == 1, "theme SVG must contain the complete single page")
    values = []
    def walk(node, matrix):
        transform = node.get("transform")
        if transform is not None:
            match = re.fullmatch(r"matrix\(([^)]+)\)", transform)
            require(match is not None, "unexpected SVG transform syntax")
            matrix = compose(matrix, tuple(map(float, match[1].split())))
        if node.tag == SVG + "g" and node.get("aria-label") is not None:
            points, colors = [], []
            for shape in node.findall(SVG + "path"):
                numbers = list(map(float, re.findall(r"[-+]?(?:\d*\.\d+|\d+\.?\d*)(?:[eE][-+]?\d+)?", shape.get("d", ""))))
                require(len(numbers) % 2 == 0, "SVG glyph path has an incomplete coordinate")
                points.extend(point(matrix, numbers[index], numbers[index + 1]) for index in range(0, len(numbers), 2))
                match = re.fullmatch(r"rgb\(([^)]+)\)", shape.get("fill", ""))
                require(match is not None, "themed SVG glyph lacks explicit RGB fill")
                colors.append(tuple(float(value.rstrip("%")) / 100 for value in match[1].split()))
            require(points and colors, "themed SVG logical text has no painted vector path")
            xs, ys = zip(*points, strict=True)
            values.append({"text": node.get("aria-label").strip(), "colors": colors,
                           "left": min(xs), "top": min(ys), "width": max(xs) - min(xs), "height": max(ys) - min(ys)})
        for child in node: walk(child, matrix)
    walk(root, (1, 0, 0, 1, 0, 0))
    return root, values


def svg_check(path, spans):
    root, values = svg_values(path)
    require([value["text"] for value in values] == [span["text"] for span in spans], "SVG source differs from final PDF reading order")
    for value, span in zip(values, spans, strict=True):
        require(all(same_color(color, span["color"]) for color in value["colors"]), "SVG glyph RGB differs from final PDF")
        require(value["left"] >= span["x"] - .6 and value["left"] + value["width"] <= span["right"] + .6,
                "SVG glyph path leaves independently decoded PDF advance")
    return root, values


def check_report(name, config):
    path = FIXTURES / (name + "-report.pdf")
    reader = PdfReader(path, strict=True)
    require(len(reader.pages) == 1 and not reader.get_fields() and not reader.outline
            and not reader.pages[0].get("/Annots"), "themed semantic report page/interaction contract differs")
    close(reader.pages[0].mediabox.width, 500, "themed report width differs")
    close(reader.pages[0].mediabox.height, 640, "themed report height differs")
    spans = painted_spans(reader)
    contracts = {text: (config["body"], BLACK) for text in
                 ("BODY-OVERVIEW", "BODY-DETAIL", "BODY-METHOD", "BODY-SOURCE", "BODY-END", "VALUE")}
    contracts.update({"REPORT-TITLE": (config["headings"][0], BLACK), "NAMED-NOTE": (9, BLACK),
                      "KEY": (config["body"], config["accent"]), "PIN12": (12, BLACK),
                      "UNCHANGED": (12, BLACK), "EXPLICIT-BODY": (12, BLACK)})
    contracts.update({f"SECTION-{level}": (config["headings"][level - 1], BLACK) for level in range(2, 7)})
    contracts.update({text: (config["cell"], BLACK) for text in ("ITEM", "RESULT", "REVIEWED")})
    styles(spans, contracts)
    order = ["REPORT-TITLE", "BODY-OVERVIEW", "BODY-DETAIL", "NAMED-NOTE", "KEY", "VALUE", "SECTION-2",
             "ITEM", "RESULT", "REVIEWED", "PIN12", "UNCHANGED", "SECTION-3", "BODY-METHOD", "SECTION-4",
             "BODY-SOURCE", "SECTION-5", "SECTION-6", "BODY-END", "EXPLICIT-BODY"]
    positions = [spans.index(token(spans, text)) for text in order]
    require(positions == sorted(positions), "theme changed semantic source order")
    first, second = token(spans, "BODY-OVERVIEW"), token(spans, "BODY-DETAIL")
    close(first["x"], 24 + config["first"], "theme first-line indent differs")
    close(second["x"], 24 + config["subsequent"], "theme continuation indent differs")
    close(second["baseline"] - first["baseline"], config["line"], "theme body line height differs")
    close(token(spans, "EXPLICIT-BODY")["x"], 24, "explicit zero indent was replaced by theme")
    top = 24 + config["headingLines"][0] + config["headingAfter"] + 2 * config["line"] + config["after"]
    top += 16 + config["noteAfter"] + config["line"] + config["after"]
    top += config["headingBefore"] + config["headingLines"][1] + config["headingAfter"]
    heights = [config["cellLine"] + 2 * config["padding"]] * 2 + [20 + 2 * config["padding"]]
    borders = borders_at(reader, top, heights, 24, 226)
    for text in ("ITEM", "REVIEWED", "PIN12"):
        close(token(spans, text)["x"], 24 + config["padding"], "theme table padding differs")
    lines = strokes(reader)
    require(len(lines) == 1, "named theme decoration missing/repeated")
    key = token(spans, "KEY")
    require(key["font"].endswith("LiberationSans-Bold"), "theme role did not select the real bold font")
    decoration(key, lines, max(.5, config["body"] / 10))
    fonts = physical_fonts(reader, {"LiberationSans", "LiberationSans-Bold"})
    svg_root, svg = svg_check(FIXTURES / (name + "-report.svg"), spans)
    return {"pages": 1, "fontAndColorChecks": len(contracts), "tableBorders": len(borders), "characters": check_characters(path, 24),
            "physicalFonts": fonts, "spans": spans}, svg_root, svg


def check_components(name, config):
    path = FIXTURES / (name + "-components.pdf")
    reader = PdfReader(path, strict=True)
    require(len(reader.pages) == 1 and not reader.get_fields() and not reader.pages[0].get("/Annots"), "theme component page/interaction count differs")
    close(reader.pages[0].mediabox.width, 500, "theme component width differs")
    close(reader.pages[0].mediabox.height, 400, "theme component height differs")
    spans = painted_spans(reader)
    contracts = {text: (config["body"], BLACK) for text in
                 ("COMPONENT-OVERVIEW", "INHERITED", "INHERITED-NEXT", "OUTSIDE", "GRID-INHERITED", "THEME-RESTORED")}
    contracts.update({"LOCAL-SCOPE": (14, GREEN), "EXPLICIT12": (12, BLACK), "RESTORED": (14, GREEN),
                      "GRID-PIN12": (12, BLACK), "SNAPSHOT12": (12, BLACK)})
    styles(spans, contracts)
    first, second = token(spans, "INHERITED"), token(spans, "INHERITED-NEXT")
    close(first["x"], 26 + config["first"], "component body first-line default differs")
    close(second["x"], 26 + config["subsequent"], "component body continuation default differs")
    close(second["baseline"] - first["baseline"], config["line"], "component inherited body line height differs")
    close(token(spans, "LOCAL-SCOPE")["x"], 32, "local scope indent differs")
    close(token(spans, "EXPLICIT12")["x"], 26, "explicit component zero indent was replaced")
    close(token(spans, "RESTORED")["x"], 32, "restored component did not retain actual local context")
    physical_fonts(reader, {"LiberationSans"})
    svg_root, _ = svg_check(FIXTURES / (name + "-components.svg"), spans)
    return {"pages": 1, "fontAndColorChecks": len(contracts), "characters": check_characters(path, 24), "spans": spans}, svg_root


def text_of(node):
    return "".join(item.text or "" for item in node.iter(W + "t"))


def paragraph_format(node, size, line):
    properties = node.find(W + "pPr")
    require(properties is not None, "native DOCX paragraph properties missing")
    close(float(properties.find(W + "rPr/" + W + "sz").get(W + "val")) / 2, size, "native DOCX paragraph size differs")
    spacing = properties.find(W + "spacing")
    require(spacing.get(W + "lineRule") == "exact", "native DOCX fixed line spacing missing")
    close(float(spacing.get(W + "line")) / 20, line, "native DOCX line spacing differs")
    return properties


def check_docx(path, config, consumer=False):
    with ZipFile(path) as archive:
        document = ET.fromstring(archive.read("word/document.xml"))
        styles_root = ET.fromstring(archive.read("word/styles.xml"))
        require(not any(name.startswith("word/media/") for name in archive.namelist()), "themed DOCX unexpectedly embeds media")
    paragraphs = list(document.iter(W + "p"))
    def paragraph(text):
        found = [node for node in paragraphs if re.search(r"(?<![A-Z0-9-])" + re.escape(text) + r"(?![A-Z0-9-])", text_of(node))]
        require(len(found) == 1, "native DOCX theme token missing/repeated: " + text)
        return found[0]
    if consumer:
        checks = {"THEME-CONSUMER": (18, 28), "THEME-BODY": (11, 20), "THEME-NOTE": (10, 18),
                  "THEME-KEY": (11, 20), "ITEM": (10, 18), "STATE": (10, 18), "FIELD": (10, 18),
                  "OK": (10, 18), "EXPLICIT": (12, 20), "INHERITED": (10, 18)}
        key_text, accent = "THEME-KEY", BLUE
    else:
        checks = {text: (config["body"], config["line"]) for text in
                  ("BODY-OVERVIEW", "BODY-METHOD", "BODY-SOURCE", "BODY-END", "KEY")}
        checks.update({"NAMED-NOTE": (9, 16), "EXPLICIT-BODY": (12, 20), "PIN12": (12, 20), "UNCHANGED": (12, 20)})
        checks.update({text: (config["cell"], config["cellLine"]) for text in ("ITEM", "RESULT", "REVIEWED")})
        for level in range(1, 7):
            text = "REPORT-TITLE" if level == 1 else f"SECTION-{level}"
            checks[text] = (config["headings"][level - 1], config["headingLines"][level - 1])
            properties = paragraph(text).find(W + "pPr")
            require(properties.find(W + "pStyle").get(W + "val") == f"Heading{level}"
                    and properties.find(W + "keepNext") is not None and properties.find(W + "keepLines") is not None,
                    "native themed heading lost level/adjacency policies")
        key_text, accent = "KEY", config["accent"]
    for text, (size, line) in checks.items(): paragraph_format(paragraph(text), size, line)
    key = next(run for run in paragraph(key_text).iter(W + "r") if text_of(run).strip() == key_text)
    properties = key.find(W + "rPr")
    require(properties.find(W + "rStyle").get(W + "val") == "bold" and properties.find(W + "u").get(W + "val") == "single",
            "native named inline style lost real role/underline")
    rgb = "".join(f"{round(value * 255):02X}" for value in accent)
    require(properties.find(W + "color").get(W + "val") == rgb, "native named inline RGB differs")
    bold = [style for style in styles_root.iter(W + "style") if style.get(W + "styleId") == "bold"]
    require(len(bold) == 1 and bold[0].find(W + "rPr/" + W + "b") is not None, "native physical bold style missing")
    tables = list(document.iter(W + "tbl"))
    require(len(tables) == 1, "native themed table missing/repeated")
    margins = tables[0].find(W + "tblPr/" + W + "tblCellMar")
    for side in ("top", "right", "bottom", "left"):
        close(float(margins.find(W + side).get(W + "w")) / 20, 4 if consumer else config["padding"], "native table padding differs")
    return {"paragraphFormatChecks": len(checks), "nativeHeadingLevels": 1 if consumer else 6, "nativeInlineStyle": "passed", "nativeTablePadding": "passed"}


def check_consumer(directory):
    base = directory / "output/consumer-theme"
    path = base.with_suffix(".pdf")
    reader = PdfReader(path, strict=True)
    require(len(reader.pages) == 1 and not reader.get_fields() and not reader.pages[0].get("/Annots"), "theme caller page/interaction count differs")
    close(reader.pages[0].mediabox.width, 400, "theme caller width differs")
    close(reader.pages[0].mediabox.height, 400, "theme caller height differs")
    spans = painted_spans(reader)
    contracts = {"THEME-CONSUMER": (18, BLACK), "THEME-BODY": (11, BLACK), "THEME-NOTE": (10, BLACK),
                 "THEME-KEY": (11, BLUE), "THEME-VALUE": (11, BLACK), "ITEM": (10, BLACK), "STATE": (10, BLACK),
                 "FIELD": (10, BLACK), "OK": (10, BLACK), "EXPLICIT": (12, BLACK), "INHERITED": (10, BLACK)}
    styles(spans, contracts)
    key = token(spans, "THEME-KEY")
    require(key["font"].endswith("LiberationSans-Bold"), "theme caller role did not select real bold")
    lines = strokes(reader)
    require(len(lines) == 1, "theme caller underline missing/repeated")
    decoration(key, lines, 1.1)
    physical_fonts(reader, {"LiberationSans", "LiberationSans-Bold"})
    borders = borders_at(reader, 132, [26, 26, 28], 20, 180)
    svg_check(base.with_suffix(".svg"), spans)
    return {"pages": 1, "fontAndColorChecks": len(contracts), "tableBorders": len(borders),
            "characters": check_characters(path, 20), "docx": check_docx(base.with_suffix(".docx"), {}, True)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--consumer-build-directory", type=Path)
    args = parser.parse_args()
    versions = {name: metadata.version(name) for name in ("pypdf", "pypdfium2", "Pillow")}
    require(versions == {"pypdf": "6.10.0", "pypdfium2": "5.13.0", "Pillow": "12.3.0"}, "use the pinned verification environment")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    report, contacts, roots, svg_reports = {"tools": versions}, [], {}, {}
    fonts = json.loads((FIXTURES / "fonts.json").read_text(encoding="utf-8"))["fonts"]
    require(len(fonts) == 2, "theme runtime font manifest differs")
    for font in fonts:
        data = (ROOT / "assets/fonts" / font["resource"]).read_bytes()
        require(font["sha256"] == hashlib.sha256(data).hexdigest() and font["bytes"] == len(data), "theme actual runtime font differs from explicit source")
    report["fontSources"] = fonts
    for name in ("blue", "compact", "plain-default", "themed-default"):
        config = CONFIG[name if name in CONFIG else "default"]
        result, roots[name + "-report"], svg_reports[name] = check_report(name, config)
        result["docx"] = check_docx(FIXTURES / (name + "-report.docx"), config)
        component, roots[name + "-components"] = check_components(name, config)
        for suffix, value in (("report", result), ("components", component)):
            label = name + "-" + suffix
            first, second = render(FIXTURES / (label + ".pdf"), OUTPUT / label)
            require(len(first) == len(second) == 1, "theme renderer omitted a complete page")
            comparisons = [difference(a, b, 2) for a, b in zip(first, second, strict=True)]
            require(all(item["meanAbsoluteChannelError"] < 4 and item["changedPixelFraction"] < .06 for item in comparisons), "theme independent renderers disagree")
            value["rendererDifferences"] = comparisons
            report[label] = value
            contacts.append((label, first[0]))
    for suffix in ("report", "components"):
        require(report["plain-default-" + suffix]["spans"] == report["themed-default-" + suffix]["spans"], "default theme changed final PDF text geometry/style")
        require(ET.tostring(roots["plain-default-" + suffix]) == ET.tostring(roots["themed-default-" + suffix]), "default theme changed SVG vector values/source")
    for text, ratio in (("KEY", 11 / 10), ("REPORT-TITLE", 20 / 18), ("BODY-OVERVIEW", 11 / 10)):
        blue, compact = token(svg_reports["blue"], text), token(svg_reports["compact"], text)
        require(abs(blue["width"] - compact["width"] * ratio) < .05 and abs(blue["height"] - compact["height"] * ratio) < .05,
                "actual SVG glyph dimensions do not reflect theme point-size change: " + text)
    directory = args.consumer_build_directory
    if directory is None: directory = Path((ROOT / "build/verification/consumer-build-directory.txt").read_text(encoding="utf-8").strip())
    if not directory.is_absolute(): directory = ROOT / directory
    report["consumerBuildDirectory"] = str(directory)
    report["consumer"] = check_consumer(directory)
    first, second = render(directory / "output/consumer-theme.pdf", OUTPUT / "consumer")
    require(len(first) == len(second) == 1, "theme caller renderer omitted its complete page")
    comparisons = [difference(a, b, 2) for a, b in zip(first, second, strict=True)]
    require(all(item["meanAbsoluteChannelError"] < 4 and item["changedPixelFraction"] < .06 for item in comparisons), "theme caller independent renderers disagree")
    report["consumer"]["rendererDifferences"] = comparisons
    contacts.append(("consumer", first[0]))
    require(len(contacts) == 9, "theme complete page count differs")
    report["defaultCompatibility"] = "semantic values checked by example; final PDF text geometry and SVG vector values identical"
    contact_sheet(contacts, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"pages": len(contacts), "pdfSvgDocxThemes": "passed", "defaultCompatibility": "passed"}, indent=2))


if __name__ == "__main__":
    main()
