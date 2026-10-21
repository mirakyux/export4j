# SPDX-License-Identifier: Apache-2.0
"""Independently check the finite list-marker fixtures, native DOCX and all five PDF pages.

Expectations come from ListMarkerExample and ListMarkerConsumer, not generated Java manifests.
These simple Latin fixtures do not establish general Word fidelity or complex-script compatibility.
"""
from __future__ import annotations

import argparse
from functools import lru_cache
from importlib import metadata
import json
from pathlib import Path
import struct
import xml.etree.ElementTree as ET
from zipfile import ZipFile

import pypdfium2 as pdfium
from pypdf import PdfReader
from verify_adr_form import render
from verify_advanced_pdf import difference, require
from verify_continuous_columns import font_ascent
from verify_form_styles import ttf_glyphs
from verify_production import actual_text, contact_sheet
from verify_semantic_tables import annotations, check_characters, close
from verify_streaming_rich_text import decoration, painted_spans, physical_fonts, same_color, strokes
from verify_themes import paragraph_format, text_of
from verify_xml_includes import native_page
from verify_xml_numbering import native_fields, native_number, native_source

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "examples/build/list-markers"
OUTPUT = ROOT / "build/verification/list-markers"
W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
OD = "{urn:com.mirakyux.export4j:semantic:1}"
BLACK = (0, 0, 0)
BLUE = (34 / 255, 68 / 255, 136 / 255)
PURPLE = (136 / 255, 51 / 255, 102 / 255)
GREEN = (51 / 255, 102 / 255, 51 / 255)


def style(role="", size=10, color=BLACK, underline=False, strike=False):
    return {"role": role, "size": size, "color": color, "underline": underline, "strike": strike}


def contract(caller):
    """Finite source/format/pagination expectations, independent of output and layout manifests."""
    blue, plain = style("bold", 12, BLUE, underline=True), style()
    if caller:
        rows = [
            {"id": "number", "label": "1", "body": "CALLER-NUMBER", "page": 0, "top": 18,
             "marker": blue, "bodyStyle": plain, "listId": "check", "restart": 0},
            {"id": "bullet", "label": "*", "body": "CALLER-BULLET", "page": 0, "top": 38,
             "marker": style("bold", 10, GREEN), "bodyStyle": plain},
        ]
        return {"pages": 1, "width": 320, "height": 220, "margin": 18, "rows": rows,
                "ids": ["number", "bullet"], "fonts": {"LiberationSans", "LiberationSans-Bold"}}
    rows = [
        {"id": "parent", "label": "3", "body": "BODY-ONE", "page": 0, "top": 20,
         "marker": blue, "bodyStyle": plain, "listId": "checks", "restart": 3},
        {"id": "child", "label": "3.1", "body": "BODY-TWO", "page": 0, "top": 40,
         "marker": blue, "bodyStyle": style("italic", 10, PURPLE), "listId": "checks", "restart": 0},
        {"id": "bullet", "label": "*", "body": "BULLET-BODY", "page": 0, "top": 60,
         "marker": style("italic", 10, GREEN, strike=True), "bodyStyle": plain},
        {"id": "legacy", "label": "1", "body": "LEGACY-BODY", "page": 0, "top": 80,
         "marker": plain, "bodyStyle": plain, "listId": "legacy-list", "restart": 0, "legacy": True},
        {"id": "next", "label": "4", "body": "BODY-THREE", "page": 1, "top": 20,
         "marker": blue, "bodyStyle": plain, "listId": "checks", "restart": 0},
    ]
    return {"pages": 2, "width": 360, "height": 280, "margin": 20, "rows": rows,
            "ids": ["parent", "child", "bullet", "legacy", "reference", "break", "next"],
            "fonts": {"LiberationSans", "LiberationSans-Bold", "LiberationSans-Italic"}}


def prefix(row):
    return row["label"] + (". " if "listId" in row else " ")


def font_path(role):
    variant = {"": "Regular", "bold": "Bold", "italic": "Italic"}[role]
    return ROOT / "assets/fonts" / f"LiberationSans-{variant}.ttf"


@lru_cache(maxsize=16)
def fixture_width(source, role, size):
    """Measure only this fixture's short ASCII prefixes against its explicit source TrueType font."""
    require(source.isascii(), "finite marker probe unexpectedly left ASCII")
    data = font_path(role).read_bytes()
    tables = {struct.unpack_from(">4sIII", data, 12 + index * 16)[0]:
              struct.unpack_from(">4sIII", data, 12 + index * 16)[2]
              for index in range(struct.unpack_from(">H", data, 4)[0])}
    units = struct.unpack_from(">H", data, tables[b"head"] + 18)[0]
    metrics = struct.unpack_from(">H", data, tables[b"hhea"] + 34)[0]
    glyphs = ttf_glyphs(data, source)
    return sum(struct.unpack_from(">H", data, tables[b"hmtx"] + min(glyph, metrics - 1) * 4)[0]
               for glyph in glyphs) * size / units


def check_span(span, expected, x, baseline):
    variant = {"": "", "bold": "-Bold", "italic": "-Italic"}[expected["role"]]
    require(span["font"].endswith("+LiberationSans" + variant), "marker/body physical font differs")
    close(span["size"], expected["size"], "marker/body point size differs")
    require(same_color(span["color"], expected["color"]), "marker/body RGB differs")
    close(span["x"], x, "marker/body adjacent horizontal position differs")
    close(span["baseline"], baseline, "marker/body shared baseline differs")


def check_visible_text(reader):
    """Large text-show operations must really paint, rather than merely carrying extractable source."""
    for page in reader.pages:
        mode, size, stack = 0, 0, []
        for operands, operator in page.get_contents().operations:
            if operator == b"q": stack.append((mode, size))
            elif operator == b"Q": mode, size = stack.pop()
            elif operator == b"Tr": mode = int(operands[0])
            elif operator == b"Tf": size = float(operands[1])
            elif operator in (b"Tj", b"TJ") and size > 1:
                require(mode == 0, "fixture marker/body text is not visibly filled")
            elif operator in (b"gs", b"Do"):
                raise AssertionError("finite text fixture gained opacity, a form XObject or image")
        require(not stack, "fixture text graphics state is unbalanced")


def pdfium_sources(path, config, caller):
    count = 0
    with pdfium.PdfDocument(path) as document:
        require(len(document) == config["pages"], "PDFium complete page count differs")
        for index in range(len(document)):
            page = document[index]
            text = page.get_textpage()
            try:
                wanted = [prefix(row) + row["body"] for row in config["rows"] if row["page"] == index]
                if not caller and index == 0: wanted.append("REF 3.1")
                lines = [line.strip() for line in text.get_text_bounded().splitlines() if line.strip()]
                require(lines == wanted, "PDFium full prefix/body/reference source order differs")
                count += len(lines)
            finally:
                text.close()
                page.close()
    return count


def check_pdf(path, config, caller):
    reader = PdfReader(path, strict=True)
    require(len(reader.pages) == config["pages"] and not reader.get_fields() and not reader.outline,
            "list fixture page/form/outline contract differs")
    for page in reader.pages:
        close(float(page.mediabox.left), 0, "fixture page x origin differs")
        close(float(page.mediabox.bottom), 0, "fixture page y origin differs")
        close(float(page.mediabox.width), config["width"], "fixture page width differs")
        close(float(page.mediabox.height), config["height"], "fixture page height differs")
        require(page.get("/Rotate", 0) == 0, "fixture page gained a rotation")
    check_visible_text(reader)
    spans, lines = painted_spans(reader), strokes(reader)
    expected_sources = []
    for row in config["rows"]:
        values = [prefix(row).strip() + " " + row["body"]] if row.get("legacy") else [prefix(row).strip(), row["body"]]
        expected_sources.extend((row["page"], value) for value in values)
    require([(span["page"], span["text"]) for span in spans] == expected_sources,
            "marker/body independent run order, legacy representation or pagination differs")
    cursor, decorated = 0, 0
    for row in config["rows"]:
        marker, body = row["marker"], row["bodyStyle"]
        level = len(row["label"].split(".")) if "listId" in row else 1
        x = config["margin"] + (level - 1) * 10 * 1.5
        ascent = max(marker["size"] * font_ascent(font_path(marker["role"])),
                     body["size"] * font_ascent(font_path(body["role"])))
        baseline = row["top"] + ascent
        mark = spans[cursor]
        check_span(mark, marker, x, baseline)
        if not row.get("legacy"):
            width = fixture_width(prefix(row), marker["role"], marker["size"])
            close(mark["right"] - mark["x"], width, "actual marker advance/trailing separator differs")
            check_span(spans[cursor + 1], body, mark["right"], baseline)
            require(spans[cursor + 1]["right"] <= config["width"] - config["margin"] + .003,
                    "body leaves the fixed content width")
            cursor += 1
        if marker["underline"]:
            decoration(mark, lines, marker["size"] / 10); decorated += 1
        if marker["strike"]:
            decoration(mark, lines, -marker["size"] * .3); decorated += 1
        cursor += 1
    require(len(lines) == decorated == (1 if caller else 4), "marker decoration leaked into body or disappeared")
    require(actual_text(reader) == ([] if caller else ["REF 3.1"]), "final complete numbering reference differs")
    links = annotations(reader)
    require(len(links) == (0 if caller else 1), "number-reference link count differs")
    if not caller:
        link = links[0]
        require(link["pageIndex"] == link["targetPageIndex"] == 0 and "uri" not in link,
                "number-reference link no longer targets its actual numbered paragraph")
        for key, value in {"x": 20 + fixture_width("REF ", "", 10), "y": 100, "width": 36,
                           "height": 20, "destinationX": 20, "destinationY": 40}.items():
            close(link[key], value, "final reference rectangle/XYZ differs: " + key)
        require(not reader.pages[1].get("/Annots"), "continued list gained a duplicate reference link")
    return {"pages": config["pages"], "listItems": len(config["rows"]), "paintedTextRuns": len(spans),
            "markerDecorations": decorated, "resolvedNumberReferences": len(links),
            "pdfiumSourceLines": pdfium_sources(path, config, caller),
            "characters": check_characters(path, config["margin"]),
            "physicalFonts": physical_fonts(reader, config["fonts"])}


def selected_level(numbering, paragraph, row):
    """Resolve this fixture's selected standard level through numId and abstractNumId, without od hints."""
    properties = paragraph.find(W + "pPr/" + W + "numPr")
    require(properties is not None, "native list lost standard numPr")
    index = len(row["label"].split(".")) - 1 if "listId" in row else 0
    require(properties.find(W + "ilvl").get(W + "val") == str(index), "native list selected level differs")
    num_id = properties.find(W + "numId").get(W + "val")
    instances = [node for node in numbering.findall(W + "num") if node.get(W + "numId") == num_id]
    require(len(instances) == 1, "native list instance missing/repeated")
    reference = instances[0].find(W + "abstractNumId").get(W + "val")
    definitions = [node for node in numbering.findall(W + "abstractNum") if node.get(W + "abstractNumId") == reference]
    require(len(definitions) == 1, "native list definition missing/repeated")
    levels = definitions[0].findall(W + "lvl")
    require([node.get(W + "ilvl") for node in levels] == [str(value) for value in range(9)],
            "native list nine-level structure differs")
    if "listId" in row:
        native_number(numbering, paragraph, row["label"])
    else:
        require(levels[index].find(W + "numFmt").get(W + "val") == "bullet"
                and levels[index].find(W + "lvlText").get(W + "val") == "*"
                and not instances[0].findall(W + "lvlOverride"), "native explicit bullet differs")
    return levels[index]


def native_style(properties, expected):
    require(properties is not None, "native selected marker/body run properties missing")
    for name in ("sz", "color", "position", "rStyle", "u", "strike"):
        require(len(properties.findall(W + name)) <= 1, "native direct style property repeated: " + name)
    size = properties.find(W + "sz")
    color = properties.find(W + "color")
    position = properties.find(W + "position")
    require(size is not None and color is not None and position is not None, "native complete style values missing")
    close(float(size.get(W + "val")) / 2, expected["size"], "native point size differs")
    require(color.get(W + "val") == "".join(f"{round(value * 255):02X}" for value in expected["color"]),
            "native marker/body RGB differs")
    require(position.get(W + "val") == "0", "native baseline shift differs")
    role = properties.find(W + "rStyle")
    require((role.get(W + "val") if role is not None else "") == expected["role"],
            "native marker/body role differs")
    underline = properties.find(W + "u")
    strike = properties.find(W + "strike")
    require((underline is not None and underline.get(W + "val", "single") != "none") == expected["underline"],
            "native marker/body underline differs")
    require((strike is not None and strike.get(W + "val", "true") not in ("false", "0", "off")) == expected["strike"],
            "native marker/body strike differs")


def check_docx(path, config, caller):
    with ZipFile(path) as archive:
        require(not any(name.startswith("word/media/") for name in archive.namelist()), "text-only DOCX gained media")
        document = ET.fromstring(archive.read("word/document.xml"))
        numbering = ET.fromstring(archive.read("word/numbering.xml"))
        styles = ET.fromstring(archive.read("word/styles.xml"))
        relationships = ET.fromstring(archive.read("word/_rels/document.xml.rels"))
    nodes = [node for node in document.find(W + "body") if node.tag != W + "sectPr"]
    require(all(node.tag == W + "p" for node in nodes) and [node.get(OD + "id") for node in nodes] == config["ids"],
            "native complete source blocks/order differ")
    by_id = {node.get(OD + "id"): node for node in nodes}
    native_page(document, config["width"], config["height"], config["margin"])
    for row in config["rows"]:
        paragraph = by_id[row["id"]]
        require(native_source(paragraph) == row["body"], "native body gained a literal marker or lost source")
        paragraph_format(paragraph, 10, 20)
        level = selected_level(numbering, paragraph, row)
        require(len(level.findall(W + "rPr")) == 1, "native selected level has no unique marker properties")
        native_style(level.find(W + "rPr"), row["marker"])
        runs = paragraph.findall(W + "r")
        require(len(runs) == 1 and text_of(runs[0]) == row["body"], "native body run structure differs")
        require(len(runs[0].findall(W + "rPr")) == 1, "native body has no unique run properties")
        native_style(runs[0].find(W + "rPr"), row["bodyStyle"])
    declared = [node for node in styles.findall(W + "style") if node.get(W + "type") == "character"]
    character_styles = {node.get(W + "styleId"): node for node in declared}
    require(len(character_styles) == len(declared), "native character style IDs repeated")
    require(set(character_styles) == ({"bold"} if caller else {"bold", "italic"}), "native font-role style set differs")
    bold = character_styles["bold"].find(W + "rPr/" + W + "b")
    require(bold is not None and bold.get(W + "val", "true") not in ("false", "0", "off"),
            "marker-only bold role is not registered with actual Word bold formatting")
    require(not any(node.get(W + "val") == "bold" for node in document.iter(W + "rStyle")),
            "marker-only bold style unexpectedly leaked into body runs")
    if not caller:
        italic = character_styles["italic"].find(W + "rPr/" + W + "i")
        require(italic is not None and italic.get(W + "val", "true") not in ("false", "0", "off"),
                "native italic role definition differs")
        require(native_source(by_id["reference"]) == "REF \ufffc", "native REF source position differs")
        require(by_id["reference"].find(W + "pPr/" + W + "numPr") is None, "plain reference acquired numbering")
        require(by_id["break"].find(W + "r/" + W + "br").get(W + "type") == "page", "native explicit page break differs")
        fields = native_fields(document, [{"fields": [("child", 36, None)]}])
    else:
        require(not list(document.iter(W + "fldSimple")) and not list(document.iter(W + "bookmarkStart")),
                "caller text-only fixture gained fields or bookmarks")
        fields = 0
    require(not any(node.get("TargetMode") == "External" for node in relationships), "native list gained external resources")
    return {"listItems": len(config["rows"]), "nativeFields": fields, "registeredCharacterStyles": sorted(character_styles),
            "markerStyle": "selected standard lvl/rPr and independent body rPr checked", "markerOnlyRole": "bold"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--consumer-build-directory", type=Path)
    args = parser.parse_args()
    versions = {name: metadata.version(name) for name in ("pypdf", "pypdfium2", "Pillow")}
    require(versions == {"pypdf": "6.10.0", "pypdfium2": "5.13.0", "Pillow": "12.3.0"}, "use pinned verification tools")
    directory = args.consumer_build_directory
    if directory is None:
        directory = Path((ROOT / "build/verification/consumer-build-directory.txt").read_text(encoding="utf-8").strip())
    if not directory.is_absolute(): directory = ROOT / directory
    OUTPUT.mkdir(parents=True, exist_ok=True)
    report, contacts = {"tools": versions, "consumerBuildDirectory": str(directory),
                        "scope": "finite Latin output; no general DOCX fidelity or real-reader save claim"}, []
    for name, path, caller in (("sample", FIXTURES / "report.pdf", False),
                               ("imported", FIXTURES / "imported.pdf", False),
                               ("consumer", directory / "output/consumer-list-markers.pdf", True)):
        config = contract(caller)
        result = check_pdf(path, config, caller)
        if name != "imported": result["docx"] = check_docx(path.with_suffix(".docx"), config, caller)
        first, second = render(path, OUTPUT / name)
        require(len(first) == len(second) == config["pages"], "independent renderer omitted a complete marker page")
        metrics = [difference(a, b, 2) for a, b in zip(first, second, strict=True)]
        require(all(value["meanAbsoluteChannelError"] < 4 and value["changedPixelFraction"] < .06 for value in metrics),
                "independent list marker renderers disagree")
        result["rendererDifferences"] = metrics
        report[name] = result
        contacts.extend((name + " " + str(index), image) for index, image in enumerate(first, 1))
    require(len(contacts) == 5, "finite marker fixture/caller total page count differs")
    contact_sheet(contacts, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"listMarkers": "passed", "pagesIncludingImportedAndConsumer": 5, "nativeDocxPackages": 2}, indent=2))


if __name__ == "__main__": main()
