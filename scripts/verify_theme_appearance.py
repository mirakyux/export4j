# SPDX-License-Identifier: Apache-2.0
"""Independently check resolved theme fonts/colors in PDF, SVG and standard OOXML."""
import json
from pathlib import Path
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from pypdf import PdfReader
from verify_advanced_pdf import difference, require
from verify_pdf_raster import references
from verify_production import actual_text, contact_sheet
from verify_semantic_tables import check_characters
from verify_streaming_rich_text import outlined_paths, painted_spans, physical_fonts, same_color
from verify_themes import svg_check, token

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "examples/build/theme-appearance"
OUTPUT = ROOT / "build/verification/theme-appearance"
W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
BLUE, BROWN, GREEN, GRAY, PURPLE, BLACK = (
    (34/255, 68/255, 136/255), (136/255, 68/255, 34/255), (68/255, 102/255, 34/255),
    (102/255,)*3, (136/255, 51/255, 102/255), (0,)*3)


def painted(path, contracts):
    reader = PdfReader(path, strict=True)
    require(len(reader.pages) == 1, "appearance sample page count")
    fonts = physical_fonts(reader, {"LiberationSans", "LiberationSans-Bold"})
    spans = painted_spans(reader)
    for text, (size, rgb, bold) in contracts.items():
        value = token(spans, text)
        require(abs(value["size"] - size) < .01 and same_color(value["color"], rgb), "painted theme size/color differs: " + text)
        require(("LiberationSans-Bold" in value["font"]) == bold, "physical font differs: " + text)
    return reader, spans, fonts


def native(path, contracts):
    with ZipFile(path) as package:
        roots = [ET.fromstring(package.read(name)) for name in package.namelist()
                 if name == "word/document.xml" or name.startswith(("word/header", "word/footer")) and name.endswith(".xml")]
        numbering = ET.fromstring(package.read("word/numbering.xml")) if "word/numbering.xml" in package.namelist() else None
    for text, (size, color, role) in contracts.items():
        runs = [run for root in roots for run in root.iter(W + "r")
                if "".join(node.text or "" for node in run.iter(W + "t")).strip() == text]
        require(len(runs) == 1, "native theme token missing/duplicated: " + text)
        properties = runs[0].find(W + "rPr")
        require(properties is not None, "native style absent")
        declared = properties.find(W + "rStyle")
        require(("" if declared is None else declared.get(W + "val")) == role, "native theme role differs: " + text)
        require(properties.find(W + "color").get(W + "val") == color, "native RGB differs: " + text)
        require(float(properties.find(W + "sz").get(W + "val"))/2 == size, "native size differs: " + text)
    if numbering is not None:
        for properties in numbering.findall(".//" + W + "lvl/" + W + "rPr"):
            require(properties.find(W + "rStyle").get(W + "val") == "brand"
                    and properties.find(W + "color").get(W + "val") == "224488", "native marker appearance lost")


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    contracts = {
        "semantic": {"JAVA-TITLE": (16, BROWN, True), "JAVA-BODY": (11, BLUE, True),
                     "JAVA-BULLET": (11, BLUE, True), "JAVA-NUMBER": (11, BLUE, True),
                     "JAVA-HEADER": (11, GREEN, False), "JAVA-CELL": (11, GREEN, False),
                     "JAVA-EXPLICIT": (11, BLACK, False), "JAVA-LATER": (11, PURPLE, False)},
        "components": {"COMPONENT-INHERIT": (11, BLUE, True), "COMPONENT-LOCAL": (11, BLACK, False),
                       "COMPONENT-RESET": (11, BLACK, False)},
        "xml": {"XML-TITLE": (16, BROWN, True), "XML-BODY": (11, BLUE, True),
                "XML-CELL": (11, GREEN, False), "XML-RESET": (11, BLACK, False),
                "XML-EXPLICIT": (11, BLACK, False), "XML-HEADER": (10, BLUE, True)},
        "caller": {"CALLER-THEME": (11, BLUE, True), "CALLER-COMPONENT": (11, BLUE, True),
                   "CALLER-RESET": (11, BLACK, False)},
    }
    caller = Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip()) / "output"
    tiles, report = [], {}
    for name, expected in contracts.items():
        path = caller / "consumer-theme-appearance.pdf" if name == "caller" else SOURCE / (name + ".pdf")
        reader, spans, fonts = painted(path, expected)
        if name in ("semantic", "components"):
            svg_check(SOURCE / (name + ".svg"), spans)
        if name == "xml":
            require(actual_text(reader).count("XML-FOOTER 1/1") == 1, "final footer fields")
            outlines = outlined_paths(reader)
            require(len(outlines) == 4 and all(same_color(value["color"], GRAY) for value in outlines), "footer outline appearance")
            root = ET.parse(SOURCE / "xml.svg").getroot()
            require(any(node.get("aria-label") == "XML-FOOTER 1/1" for node in root.iter()), "SVG final footer fields")
        first, second = references(path, OUTPUT / name)
        diff = difference(first[0], second[0], blur_radius=1)
        require(diff["meanAbsoluteChannelError"] < 3 and diff["changedPixelFraction"] < .04, "independent render disagreement")
        tiles.extend([(name + " Poppler", first[0]), (name + " PDFium", second[0])])
        report[name] = {"fonts": fonts, "characters": check_characters(path, 16), "renderDifference": diff}
    native(SOURCE / "semantic.docx", {
        "JAVA-TITLE": (16, "884422", "brand"), "JAVA-BODY": (11, "224488", "brand"),
        "JAVA-HEADER": (11, "446622", "regular"), "JAVA-CELL": (11, "446622", "regular"),
        "JAVA-EXPLICIT": (11, "000000", ""), "JAVA-LATER": (11, "883366", "")})
    native(SOURCE / "xml.docx", {
        "XML-HEADER": (10, "224488", "brand"), "XML-TITLE": (16, "884422", "brand"),
        "XML-BODY": (11, "224488", "brand"), "XML-CELL": (11, "446622", "regular"),
        "XML-RESET": (11, "000000", ""), "XML-EXPLICIT": (11, "000000", "")})
    contact_sheet(tiles, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print("Theme appearance: physical fonts, domain/local overrides, final fields, SVG, native OOXML and four-page dual rendering passed")


if __name__ == "__main__":
    main()
