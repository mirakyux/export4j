# SPDX-License-Identifier: Apache-2.0
"""Independently inspect the inlineVectors PDF/SVG/PNG specimen.

Run after :examples:inlineVectors with requirements-verification.txt and Poppler.
Reads real PDF path operators and SVG path coordinates; never updates a baseline.
All PDF pages receive Poppler, PDFium, resvg and exported-PNG comparisons.
"""
from __future__ import annotations

import argparse
from collections import Counter
from copy import deepcopy
import hashlib
from importlib import metadata
import json
import math
from pathlib import Path
import platform
import re
import struct
import subprocess
import xml.etree.ElementTree as ET

from PIL import Image
from pypdf import PdfReader
import pypdfium2 as pdfium
import resvg_py

from verify_advanced_pdf import difference, require
from verify_production import actual_text, contact_sheet

ROOT = Path(__file__).resolve().parents[1]
TEX = (
    r"x = \frac{-b \pm \sqrt{b^2-4ac}}{2a}",
    r"x_1^2 + x_2^2 = r^2",
    r"\sum_{i=1}^{n}i = \frac{n(n+1)}{2}",
)
ORDER = (0, 1, 2, 0, 1, 2, 0, 1)
IDENTITY = (1, 0, 0, 1, 0, 0)
OPERATIONS = {"MOVE": (0, 2), "LINE": (1, 2), "CURVE": (2, 6), "CLOSE": (3, 0)}
TOLERANCE = .002  # pt; existing inline-object protocol, above PDF decimal serialization error.
SVG = "{http://www.w3.org/2000/svg}"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def compose(left, right):
    a, b, c, d, e, f = left
    g, h, i, j, k, l = right
    return (a*g+c*h, b*g+d*h, a*i+c*j, b*i+d*j, a*k+c*l+e, b*k+d*l+f)


def point(matrix, x, y):
    a, b, c, d, e, f = matrix
    return a*x+c*y+e, b*x+d*y+f


def close_values(left, right, label):
    require(len(left) == len(right) and all(abs(a-b) <= TOLERANCE for a, b in zip(left, right, strict=True)), label)


def validate_manifest(manifest):
    require(manifest["schemaVersion"] == 1 and manifest["units"] == "pt" and manifest["dpi"] == 144, "unexpected geometry protocol")
    require(manifest["pages"] == 1 and manifest["htmlImportLosses"] == 0, "specimen pagination/import loss changed")
    require(manifest["images"] == 0 and manifest["nativeGlyphRuns"] > 0, "specimen lost native ordinary text or gained images")
    formulas = manifest["formulas"]
    require(tuple(item["tex"] for item in formulas) == TEX, "the three fixed TeX expressions changed")
    require(len({item["resource"] for item in formulas}) == 3, "vector resources are not distinct")
    for formula in formulas:
        width, ascent, descent = (formula[name] for name in ("width", "ascent", "descent"))
        require(all(math.isfinite(value) for value in (width, ascent, descent)) and width > 0 and ascent > 0 and descent > 0,
                "invalid baseline-relative vector dimensions")
        require(formula["sourceBaseline"] == ascent, "preparation baseline changed")
        segments = formula["path"]
        require(0 < formula["segments"] == len(segments) <= 10_000, "vector segment budget/count changed")
        encoding = bytearray(struct.pack(">idddi", 1, width, ascent, descent, len(segments)))
        opened = False
        for segment in segments:
            operation, values = segment["operation"], segment["coordinates"]
            require(operation in OPERATIONS, "unknown resource path operation")
            ordinal, count = OPERATIONS[operation]
            require(len(values) == count and all(math.isfinite(value) for value in values), "invalid resource coordinates")
            require(operation == "MOVE" or opened, "resource path has no initial move")
            opened = operation != "CLOSE"
            require(all(0 <= values[index] <= width and -ascent <= values[index+1] <= descent
                        for index in range(0, count, 2)), "resource coordinates exceed the reserved box")
            encoding.append(ordinal)
            encoding.extend(struct.pack(">" + "d"*count, *values))
        require(hashlib.sha256(encoding).hexdigest() == formula["resource"], "canonical resource SHA256 does not match its geometry")
        require(any(item["operation"] == "CURVE" for item in segments), "formula must contain actual curved outlines")
    expected = manifest["expected"]
    require(len(expected) == len(manifest["placements"]) == 8, "expected eight model/vector placements")
    resources = [formulas[index]["resource"] for index in ORDER]
    require([item["resource"] for item in expected] == resources, "model vector order changed")
    require([item["resource"] for item in manifest["placements"]] == resources, "display vector order changed")
    locations = [("quadratic", -1, -1, 10), ("powers", -1, -1, 21), ("display", -1, -1, 0),
                 ("formula-table", 1, 1, 0), ("formula-table", 2, 1, 0), ("formula-table", 3, 1, 0),
                 ("html-1", -1, -1, 13), ("html-2", -1, -1, 13)]
    texts = ["Solution: \ufffc for a nonzero a.", "The circle satisfies \ufffc in this plane.", "\ufffc",
             "\ufffc", "\ufffc", "\ufffc", "HTML inline: \ufffc keeps its source.", "HTML inline: \ufffc keeps its baseline."]
    for expected_item, location, text in zip(expected, locations, texts, strict=True):
        require(tuple(expected_item[name] for name in ("container", "row", "column", "sourceIndex")) == location,
                "model source/container position changed")
        source = expected_item["sourceText"]
        require(source == text and source.count("\ufffc") == 1 and source[location[3]] == "\ufffc",
                "vector does not consume its one actual model object marker")
    by_id = {formula["resource"]: formula for formula in formulas}
    for placement in manifest["placements"]:
        formula = by_id[placement["resource"]]
        x, baseline = placement["baseline"]
        close_values(placement["box"], [x, baseline-formula["ascent"], formula["width"], formula["ascent"]+formula["descent"]],
                     "reserved placement box differs from its baseline geometry")
        left, top, width, height = placement["box"]
        require(placement["page"] == 1 and 36 <= left and 36 <= top and left+width <= 564 and top+height <= 864,
                "vector placement exceeds the explicit content area")
    return by_id


def pdf_paths(reader):
    """Interpret actual painted content-stream paths, including each active PDF CTM."""
    result = []
    native_shows = 0
    invisible_shows = 0
    font_names = set()
    for page_index, page in enumerate(reader.pages, 1):
        height = float(page.mediabox.height)
        require(not page.get("/Resources", {}).get("/XObject"), "fixture unexpectedly contains image/Form XObjects")
        matrix, fill, mode = IDENTITY, (0, 0, 0), 0
        stack, path = [], []
        def coordinates(values):
            transformed = []
            for index in range(0, len(values), 2):
                x, y = point(matrix, float(values[index]), float(values[index+1]))
                transformed.extend((x, height-y))
            return transformed
        for operands, operator in page.get_contents().operations:
            if operator == b"q":
                stack.append((matrix, fill, mode))
            elif operator == b"Q":
                require(stack, "unbalanced PDF restore")
                matrix, fill, mode = stack.pop()
            elif operator == b"cm":
                matrix = compose(matrix, tuple(map(float, operands)))
            elif operator == b"rg":
                fill = tuple(map(float, operands))
            elif operator == b"g":
                fill = (float(operands[0]),)*3
            elif operator == b"Tr":
                mode = int(operands[0])
            elif operator == b"Tf":
                font_names.add(str(operands[0]))
            elif operator in (b"Tj", b"TJ", b"'", b'"'):
                if mode == 3:
                    invisible_shows += 1
                else:
                    native_shows += 1
            elif operator in (b"Do", b"INLINE IMAGE"):
                raise AssertionError("fixture paints an image or unexpected XObject")
            elif operator in (b"m", b"l", b"c"):
                path.append(({b"m": "MOVE", b"l": "LINE", b"c": "CURVE"}[operator], coordinates(operands)))
            elif operator == b"h":
                path.append(("CLOSE", []))
            elif operator == b"re":
                x, y, width, height_rect = map(float, operands)
                for operation, xy in (("MOVE", [x, y]), ("LINE", [x+width, y]),
                                      ("LINE", [x+width, y+height_rect]), ("LINE", [x, y+height_rect])):
                    path.append((operation, coordinates(xy)))
                path.append(("CLOSE", []))
            elif operator in (b"f", b"f*", b"B", b"B*"):
                if path:
                    result.append({"page": page_index, "path": path, "fill": fill, "rule": "evenodd" if b"*" in operator else "nonzero"})
                path = []
            elif operator in (b"n", b"S", b"s", b"b", b"b*"):
                require(operator not in (b"b", b"b*"), "unexpected combined implicit-close fill")
                path = []
            elif operator in (b"v", b"y"):
                raise AssertionError("unexpected PDF shorthand curve; inspect before extending this fixture parser")
        require(not stack and not path, "unfinished PDF graphics/path state")
    require(native_shows > 0 and invisible_shows == 8, "expected native ordinary text plus eight explicit logical formula carriers")
    fonts = []
    for page in reader.pages:
        for name, reference in page["/Resources"].get("/Font", {}).items():
            if str(name) not in font_names:
                continue
            font = reference.get_object()
            require(font.get("/Subtype") == "/Type0" and "/ToUnicode" in font, "native ordinary text lacks Type0/ToUnicode")
            descendant = font["/DescendantFonts"][0].get_object()
            descriptor = descendant["/FontDescriptor"]
            require("/FontFile2" in descriptor and descriptor["/FontFile2"].get_data(), "ordinary physical TrueType font is not embedded")
            require("LiberationSans" in str(font["/BaseFont"]), "unexpected native font; formulas should use explicit paths")
            fonts.append(str(font["/BaseFont"]))
    return result, {"nativeTextShowOperators": native_shows, "invisibleLogicalCarriers": invisible_shows, "embeddedOrdinaryFonts": sorted(set(fonts))}


def svg_paths(root):
    require(root.tag == SVG+"svg" and not root.findall(".//"+SVG+"image"), "SVG contains an image or is not SVG")
    result, labels = [], []
    pages = root.findall(SVG+"svg")
    require(len(pages) == 1 and pages[0].get("data-page") == "1", "SVG page structure changed")
    def walk(element, matrix, fill="black", rule="nonzero"):
        if element.tag == SVG+"defs":
            return
        if "transform" in element.attrib:
            transform = element.attrib["transform"]
            require(transform.startswith("matrix(") and transform.endswith(")"), "unexpected SVG transform syntax")
            values = tuple(float(value) for value in transform[7:-1].replace(",", " ").split())
            require(len(values) == 6, "SVG transform matrix dimension changed")
            matrix = compose(matrix, values)
        fill, rule = element.get("fill", fill), element.get("fill-rule", rule)
        if "aria-label" in element.attrib:
            labels.append(element.attrib["aria-label"])
        if element.tag == SVG+"path" and fill != "none":
            tokens = re.findall(r"[A-Za-z]|[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?", element.get("d", ""))
            path, position = [], 0
            while position < len(tokens):
                operation = tokens[position]
                require(operation in ("M", "L", "C", "Z"), "SVG path syntax outside this fixed specimen")
                count = {"M": 2, "L": 2, "C": 6, "Z": 0}[operation]
                values = list(map(float, tokens[position+1:position+1+count]))
                require(len(values) == count and all(math.isfinite(value) for value in values), "invalid SVG coordinates")
                transformed = []
                for index in range(0, count, 2):
                    transformed.extend(point(matrix, values[index], values[index+1]))
                path.append(({"M": "MOVE", "L": "LINE", "C": "CURVE", "Z": "CLOSE"}[operation], transformed))
                position += count+1
            result.append({"page": 1, "path": path, "fill": fill, "rule": rule})
        for child in element:
            walk(child, matrix, fill, rule)
    for page in pages:
        close_values(tuple(map(float, page.get("viewBox", "").split())), (0, 0, 600, 900), "SVG page dimensions changed")
        walk(page, IDENTITY)
    return result, labels


def verify_paths(manifest, painted, label):
    resources = {item["resource"]: item for item in manifest["formulas"]}
    matches, consumed = [], set()
    for placement in manifest["placements"]:
        resource = resources[placement["resource"]]
        dx, dy = placement["baseline"]
        source = resource["path"]
        candidates = []
        for index, actual in enumerate(painted):
            if index in consumed or actual["page"] != placement["page"] or len(actual["path"]) != len(source):
                continue
            error = 0
            for expected, (operation, coordinates) in zip(source, actual["path"], strict=True):
                if expected["operation"] != operation or len(coordinates) != len(expected["coordinates"]):
                    break
                for coordinate, value in enumerate(expected["coordinates"]):
                    error = max(error, abs(value+(dx if coordinate % 2 == 0 else dy)-coordinates[coordinate]))
            else:
                if error <= TOLERANCE:
                    candidates.append((index, error))
        require(len(candidates) == 1, f"{label}: a complete vector path is missing, duplicated, moved or changed")
        index, error = candidates[0]
        actual = painted[index]
        require(actual["rule"] == "nonzero", f"{label}: vector fill rule changed")
        require(actual["fill"] in ((0, 0, 0), "black", "rgb(0.0% 0.0% 0.0%)", "rgb(0% 0% 0%)"), f"{label}: vector black fill changed")
        first = actual["path"][0][1]
        origin = source[0]["coordinates"]
        derived = [first[0]-origin[0], first[1]-origin[1]]
        close_values(derived, placement["baseline"], f"{label}: independently derived baseline differs")
        close_values([derived[0], derived[1]-resource["ascent"], resource["width"], resource["ascent"]+resource["descent"]],
                     placement["box"], f"{label}: reserved resource box differs")
        consumed.add(index)
        matches.append({"resource": resource["resource"], "page": placement["page"], "paintIndex": index,
                        "segments": len(source), "maximumCoordinateErrorPt": error, "derivedBaselinePt": derived})
    require([item["paintIndex"] for item in matches] == sorted(item["paintIndex"] for item in matches), f"{label}: formula paint order changed")
    segment_counts = {len(resource["path"]) for resource in resources.values()}
    require(consumed == {index for index, item in enumerate(painted) if len(item["path"]) in segment_counts},
            f"{label}: an additional formula-sized path was painted outside its declared placement")
    counts = Counter(item["resource"] for item in matches)
    require(tuple(counts[item["resource"]] for item in manifest["formulas"]) == (3, 3, 2), f"{label}: formula occurrence counts differ")
    return matches


def pdfium_text(path):
    logical, searches = [], Counter()
    with pdfium.PdfDocument(path) as document:
        for index in range(len(document)):
            page = document[index]
            try:
                textpage = page.get_textpage()
                try:
                    logical.append(textpage.get_text_bounded())
                    for tex in TEX:
                        search = textpage.search(tex, match_case=True)
                        try:
                            while (match := search.get_next()) is not None:
                                require(textpage.get_text_range(*match) == tex, "PDFium selected TeX differs from the source")
                                searches[tex] += 1
                        finally:
                            search.close()
                finally:
                    textpage.close()
            finally:
                page.close()
    combined = "\n".join(logical)
    require(tuple(combined.count(tex) for tex in TEX) == (3, 3, 2), "PDFium formula text occurrence counts differ")
    require(tuple(searches[tex] for tex in TEX) == (3, 3, 2), "PDFium formula search occurrence counts differ")
    require("\ufffc" not in combined, "PDFium exposed a model placeholder instead of formula reading text")
    return combined, dict(searches)


def render(path, directory):
    poppler, pdfium_directory = directory/"poppler", directory/"pdfium"
    poppler.mkdir(parents=True, exist_ok=True)
    pdfium_directory.mkdir(parents=True, exist_ok=True)
    subprocess.run(["pdftoppm", "-cropbox", "-r", "144", "-png", str(path), str(poppler/"page")],
                   check=True, capture_output=True, timeout=60)
    second = []
    with pdfium.PdfDocument(path) as document:
        for index in range(len(document)):
            page = document[index]
            try:
                bitmap = page.render(scale=2)
                try:
                    target = pdfium_directory/f"page-{index+1}.png"
                    with bitmap.to_pil() as image:
                        with image.convert("RGB") as rgb:
                            rgb.save(target)
                    second.append(target)
                finally:
                    bitmap.close()
            finally:
                page.close()
    first = sorted(poppler.glob("page-*.png"), key=lambda item: int(item.stem.rsplit("-", 1)[1]))
    require(len(first) == len(second) == 1, "dual-rendered page counts changed")
    return first, second


def negative_controls(manifest, painted):
    def rejected(callback, label):
        try:
            callback()
        except AssertionError:
            return label
        raise AssertionError(f"negative control escaped: {label}")
    altered = deepcopy(manifest)
    altered["formulas"][0]["path"][0]["coordinates"][0] += 1
    checks = [rejected(lambda: validate_manifest(altered), "resource coordinate/hash mutation")]
    moved = deepcopy(manifest)
    moved["placements"][0]["baseline"][1] += 2
    checks.append(rejected(lambda: verify_paths(moved, painted, "negative"), "paint baseline moved by 2pt"))
    marker = deepcopy(manifest)
    marker["expected"][0]["sourceText"] = marker["expected"][0]["sourceText"].replace("\ufffc", "x")
    checks.append(rejected(lambda: validate_manifest(marker), "model atomic marker removed"))
    removed = deepcopy(painted)
    first_count = manifest["formulas"][0]["segments"]
    removed = [item for item in removed if len(item["path"]) != first_count]
    checks.append(rejected(lambda: verify_paths(manifest, removed, "negative"), "all first-formula painted paths removed"))
    duplicated = deepcopy(painted)
    extra = deepcopy(next(item for item in painted if len(item["path"]) == first_count))
    for _, coordinates in extra["path"]:
        for index in range(0, len(coordinates), 2):
            coordinates[index] += 4
    duplicated.append(extra)
    checks.append(rejected(lambda: verify_paths(manifest, duplicated, "negative"), "undeclared shifted formula copy added"))
    return checks


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-directory", type=Path, default=ROOT/"examples/build/inline-vectors")
    parser.add_argument("--output-directory", type=Path, default=ROOT/"build/verification/inline-vectors")
    args = parser.parse_args()
    source, output = args.source_directory.resolve(), args.output_directory.resolve()
    output.mkdir(parents=True, exist_ok=True)
    versions = {name: metadata.version(name) for name in ("pypdf", "pypdfium2", "Pillow", "resvg-py")}
    require(versions == {"pypdf": "6.10.0", "pypdfium2": "5.13.0", "Pillow": "12.3.0", "resvg-py": "0.5.0"}, "install the pinned verification requirements")
    poppler_version = subprocess.run(["pdftoppm", "-v"], check=True, capture_output=True, text=True, timeout=15)
    versions.update({"python": platform.python_version(), "pdfiumEngine": str(pdfium.PDFIUM_INFO),
                     "poppler": (poppler_version.stderr or poppler_version.stdout).splitlines()[0]})
    manifest = json.loads((source/"geometry.json").read_text(encoding="utf-8"))
    validate_manifest(manifest)
    with (source/"mixed.pdf").open("rb") as stream:
        reader = PdfReader(stream, strict=True)
        require(len(reader.pages) == manifest["pages"], "PDF page count differs from model")
        require(list(map(float, reader.pages[0].mediabox)) == [0, 0, 600, 900], "PDF physical page dimensions changed")
        painted, ordinary = pdf_paths(reader)
        pdf_geometry = verify_paths(manifest, painted, "PDF content stream")
        carriers = actual_text(reader)
        require(len(carriers) == 8 and [next((index for index, tex in enumerate(TEX) if tex in value), None) for value in carriers] == list(ORDER),
                "PDF ActualText formula count or order changed")
        native = "\n".join(page.extract_text() or "" for page in reader.pages)
        require(all(native.count(text) == 1 for text in ("Inline vector mathematics", "Fraction and root", "Sub/superscripts", "Summation")),
                "native ordinary text is missing or duplicated")
    logical, searches = pdfium_text(source/"mixed.pdf")
    (output/"pdfium-text.txt").write_text(logical, encoding="utf-8")
    root = ET.parse(source/"mixed.svg").getroot()
    vector_paths, labels = svg_paths(root)
    svg_geometry = verify_paths(manifest, vector_paths, "SVG file")
    require(tuple("\n".join(labels).count(tex) for tex in TEX) == (3, 3, 2), "SVG formula reading text counts differ")
    first, second = render(source/"mixed.pdf", output)
    raster = source/"page-00001.png"
    require(len(list(source.glob("page-*.png"))) == 1, "stale/extra exported PNG pages exist")
    with Image.open(raster) as image:
        require(image.format == "PNG" and image.size == (1200, 1800), "exported PNG dimensions do not match 144 DPI")
        image.verify()
    # Fix explicit pixel extents; resvg never resolves system fonts because SVG text is outlined.
    root.set("width", "1200")
    root.set("height", "1800")
    svg_raster = output/"resvg-page-1.png"
    svg_raster.write_bytes(resvg_py.svg_to_bytes(svg_string=ET.tostring(root, encoding="unicode"),
                          width=1200, height=1800, background="#ffffff", skip_system_fonts=True))
    comparisons = {}
    for name, left, right in (("popplerPdfium", first[0], second[0]), ("exportedPngPdfium", raster, second[0]),
                              ("resvgPdfium", svg_raster, second[0])):
        raw, blurred = difference(left, right), difference(left, right, 1)
        require(blurred["meanAbsoluteChannelError"] < 4 and blurred["changedPixelFraction"] < .06,
                f"{name}: independent painted output differs: {blurred}")
        comparisons[name] = {"raw": raw, "blur1": blurred}
    contact_sheet([("Poppler page 1", first[0]), ("PDFium page 1", second[0]),
                   ("Exported PNG page 1", raster), ("resvg page 1", svg_raster)], output)
    report = {"checks": "passed", "pages": 1, "uniqueVectorResources": 3, "formulaPlacements": 8, "imageObjects": 0,
              "formulaRepresentation": "nonzero filled vector paths with original TeX ActualText",
              "ordinaryText": ordinary, "modelAtomicMarkers": 8, "htmlImportLosses": 0,
              "pdfContentStreamGeometry": pdf_geometry, "svgFileGeometry": svg_geometry, "pdfiumSearchCounts": searches,
              "renderProtocol": {"dpi": 144, "blurPixels": 1, "meanLimit": 4, "changedFractionLimit": .06,
                                 "geometryTolerancePt": TOLERANCE}, "rendering": comparisons,
              "negativeControls": negative_controls(manifest, painted), "tools": versions,
              "artifacts": {name: digest(source/name) for name in ("geometry.json", "mixed.pdf", "mixed.svg", "page-00001.png")},
              "visualReview": "all pages rendered; human/agent visual review recorded separately"}
    (output/"report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
