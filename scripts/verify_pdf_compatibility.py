# SPDX-License-Identifier: Apache-2.0
"""Independently verify explicit rendering fonts, graphics-state recovery and rejected inputs."""
import argparse
import csv
from hashlib import sha256
from importlib.metadata import version
import json
from pathlib import Path
from zipfile import ZipFile
import xml.etree.ElementTree as ET
from PIL import Image, ImageChops, ImageFilter
from pypdf import PdfReader
from pypdf.filters import FlateDecode
from verify_advanced_pdf import difference, require
from verify_pdf_content_editing import sanitize
from verify_pdf_font_compatibility import compact, converted_text, pdf_text
from verify_pdf_raster import references
from verify_production import contact_sheet

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "build/verification/pdf-compatibility-fixtures"
OUTPUT = ROOT / "build/verification/pdf-compatibility"
W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"


def rows(path):
    with path.open(encoding="utf-8", newline="") as source:
        return list(csv.DictReader(source, delimiter="\t"))


def losses(path, action, expected):
    result = rows(path)
    require(result and all(item["action"] == action.upper() for item in result), "actual loss actions changed")
    require(expected <= {item["feature"] for item in result}, "required losses absent: " + path.name)
    return [{key: value for key, value in item.items() if key != "action"} for item in result]


def document_font(page):
    return page["/Resources"]["/Font"]["/F1"].get_object()


def controlled_font(name):
    source = PdfReader(FIXTURES / (name + ".pdf")).pages[0]
    control = PdfReader(FIXTURES / (name + "-control.pdf")).pages[0]
    before = document_font(source)
    after = document_font(control)
    require(source.get_contents().get_data() == control.get_contents().get_data(), "font control changed source content bytes")
    require(before["/Encoding"] == after["/Encoding"] == "/WinAnsiEncoding", "control changed source encoding")
    program = after["/FontDescriptor"].get_object()["/FontFile2"].get_object().get_data()
    require(program == (ROOT / "assets/fonts/LiberationSans-Regular.ttf").read_bytes(), "control does not embed the explicitly supplied font")
    if name == "mapped-standard":
        require(before["/Subtype"] == "/Type1" and before["/BaseFont"] == "/Helvetica" and "/Widths" not in before, "standard-font source lost its original declaration")
    else:
        require(before["/BaseFont"] == "/ExternalFixture" and "/FontFile2" not in before["/FontDescriptor"].get_object(), "explicit-width source accidentally embeds a font")
        require(before["/Widths"] == after["/Widths"] and before["/FirstChar"] == after["/FirstChar"]
                and before["/LastChar"] == after["/LastChar"], "control changed declared widths")
        require(before["/ToUnicode"].get_object().get_data() == after["/ToUnicode"].get_object().get_data(), "control changed source ToUnicode")
    require(compact(pdf_text(FIXTURES / (name + "-control.pdf"))) == "MAPPEDSECRETKEEPOUTSIDEKEEP", "embedded control text differs")


def redacted_pixels(path):
    page = PdfReader(path).pages[0]
    images = list(page["/Resources"]["/XObject"].values())
    require(len(images) == 1, "redaction did not create exactly one image")
    image = images[0].get_object()
    require(image["/Filter"] == "/FlateDecode" and image["/ColorSpace"] == "/DeviceRGB"
            and image["/BitsPerComponent"] == 8, "redaction image coding changed")
    # Resolve indirect predictor parameters explicitly; pypdf's image convenience
    # accessor does not resolve this case in the locked verification version.
    pixels = FlateDecode.decode(image._data, image["/DecodeParms"].get_object())
    require(len(pixels) == image["/Width"] * image["/Height"] * 3, "redaction decoded byte count")
    return Image.frombytes("RGB", (image["/Width"], image["/Height"]), pixels)


def text_region_metrics(actual, reference):
    """Require both fixture lines, their placement and glyph coverage at 144 DPI.

    One pixel of ink dilation tolerates independent renderers' edge antialiasing;
    the two-pixel bounding-box tolerance is one physical pt. Comparing coverage
    inside each line prevents a mostly white page from hiding missing or moved text.
    """
    require(actual.size == reference.size == (480, 240), "text-control page geometry")
    result = []
    for region in ((32, 48, 456, 94), (32, 148, 456, 194)):
        left = actual.crop(region).convert("L").point(lambda value: 255 if value < 200 else 0)
        right = reference.crop(region).convert("L").point(lambda value: 255 if value < 200 else 0)
        left_ink = sum(left.histogram()[1:])
        right_ink = sum(right.histogram()[1:])
        require(left_ink > 100 and right_ink > 100, "mapped text line is empty or incomplete")
        require(.7 <= left_ink / right_ink <= 1.3, "mapped text line ink count differs from the embedded control")
        left_box = left.getbbox()
        right_box = right.getbbox()
        require(max(abs(a - b) for a, b in zip(left_box, right_box, strict=True)) <= 2,
                "mapped text line position or advance differs from the embedded control")
        left_missing = sum(ImageChops.subtract(left, right.filter(ImageFilter.MaxFilter(3))).histogram()[1:])
        right_missing = sum(ImageChops.subtract(right, left.filter(ImageFilter.MaxFilter(3))).histogram()[1:])
        unmatched = (left_missing + right_missing) / (left_ink + right_ink)
        require(unmatched < .05, "mapped glyph placement/widths differ inside the text line")
        result.append({"region": region, "actualInk": left_ink, "referenceInk": right_ink,
                       "actualInkBox": left_box, "referenceInkBox": right_box,
                       "unmatchedInkFraction": unmatched})
    return result


def font_mapping(directory, tiles, metrics):
    for name in ("mapped-standard", "mapped-widths"):
        controlled_font(name)
        control = FIXTURES / (name + "-control.pdf")
        independent = references(control, OUTPUT / (name + "-control"))
        for engine, pages in enumerate(independent):
            require(len(pages) == 1, "embedded control pagination")
            tiles.append((f"{name} embedded control {engine+1}", pages[0]))
        normalized = []
        for action in ("warn", "ignore"):
            label = name + "-" + action
            actual = directory / (label + ".png")
            with Image.open(actual) as image:
                require(image.size == (480, 240) and abs(image.info["dpi"][0] - 144) < .02, "mapped rendering geometry or physical density")
                original = image.convert("RGB")
            metrics[label] = []
            for pages in independent:
                delta = difference(actual, pages[0], 1)
                require(delta["meanAbsoluteChannelError"] < 4 and delta["changedPixelFraction"] < .06,
                        "mapped rendering differs from the same explicitly embedded font")
                with Image.open(pages[0]) as reference:
                    delta["textLines"] = text_region_metrics(original, reference)
                metrics[label].append(delta)
            tiles.append((label + " Export4j", actual))
            with ZipFile(directory / (label + ".zip")) as archive:
                require(len(archive.namelist()) == 1 and archive.read(archive.namelist()[0]) == actual.read_bytes(), "mapped image archive changed prepared bytes")
            report = losses(directory / (label + ".losses.tsv"), action, {"RENDER_FONT_MAPPING"})
            require(len(report) == 1 and report[0]["treatment"] == "DEGRADE"
                    and report[0]["effects"] == "EXPLICIT_SOURCE_FONT_SUBSTITUTION", "mapping was not reported as explicit substitution")
            redaction = directory / (label + "-redacted.pdf")
            sanitize(redaction)
            require(not pdf_text(redaction).strip(), "redacted source text remains independently extractable")
            with redacted_pixels(redaction) as image:
                require(image.size == original.size, "redaction changed mapped geometry")
                require(set(image.crop((36, 48, 446, 92)).get_flattened_data()) == {(0, 0, 0)}, "selected mapped-font pixels were not erased")
                require(image.crop((0, 110, 480, 240)).tobytes() == original.crop((0, 110, 480, 240)).tobytes(), "unselected mapped-font pixels changed")
            redaction_report = losses(directory / (label + "-redacted.losses.tsv"), action, {"RENDER_FONT_MAPPING", "RASTERIZATION"})
            normalized.append((report, redaction_report))
            for engine, pages in enumerate(references(redaction, OUTPUT / (label + "-redacted"))):
                require(len(pages) == 1, "mapped redaction pagination")
                tiles.append((label + f" redaction {engine+1}", pages[0]))
        require(normalized[0] == normalized[1], "IGNORE discarded mapping or redaction losses")
        with Image.open(directory / (name + "-warn.png")) as left, Image.open(directory / (name + "-ignore.png")) as right:
            require(left.convert("RGB").tobytes() == right.convert("RGB").tobytes(), "WARN/IGNORE produced different mapped images")
        require((directory / (name + "-protected.png")).read_bytes() == b"existing destination", "failed mapping overwrote its target")


def source_geometry(path):
    actual = rows(path)
    require(len(actual) == 3 and len({item["block"] for item in actual}) == 3, "graphics-state sources were lost or duplicated")
    font = document_font(PdfReader(FIXTURES / "gs-normal.pdf").pages[0])
    declared = [float(value) for value in font["/Widths"]]
    for item, text, size, baseline in zip(actual, ("GS BEFORE", "GS BODY", "GS AFTER"), (12, 14, 12), (25, 55, 85), strict=True):
        expected_width = sum(declared[ord(char) - 32] for char in text) * size / 1000
        require(item["page"] == "0" and abs(float(item["x"]) - 20) < .001, "graphics-state source page or horizontal origin")
        require(abs(float(item["width"]) - expected_width) < .001 and float(item["height"]) > 0,
                "graphics-state source advance or height changed")
        require(abs(float(item["y"]) + float(item["height"]) - baseline) < .001,
                "graphics-state top-left source baseline changed")


def graphics_states(directory, tiles):
    for name in ("gs-normal", "gs-compatible"):
        source = directory / (name + "-source.pdf")
        require(compact(pdf_text(source)) == "GSBEFOREGSBODYGSAFTER", "source graphics-state text differs")
        for engine, pages in enumerate(references(source, OUTPUT / (name + "-source"))):
            require(len(pages) == 1, "graphics-state source pagination")
            tiles.append((f"{name} source {engine+1}", pages[0]))
        for extension in ("pdf", "docx", "html", "md"):
            normalized = []
            for action in ("warn", "ignore"):
                label = name + "-" + action
                path = directory / (label + "." + extension)
                require(compact(converted_text(path)) == "GSBEFOREGSEDITEDGSAFTER", "graphics-state conversion lost, duplicated or retained incorrect text: " + path.name)
                source_geometry(directory / (label + ".sources.tsv"))
                report = losses(path.with_name(path.name + ".losses.tsv"), action,
                                {"PDF_LAYOUT", "PDF_FONT_MAPPING", "PDF_VECTOR_GRAPHICS"})
                require(not any(item["feature"] == "PDF_STRUCTURE" for item in report), "accepted graphics-state page was skipped")
                normalized.append(report)
                if extension == "docx":
                    with ZipFile(path) as archive:
                        document = ET.fromstring(archive.read("word/document.xml"))
                    paragraphs = document.findall(".//" + W + "body/" + W + "p")
                    body = next(node for node in paragraphs if "GS EDITED" in "".join(node.itertext()))
                    require(any(node.get(W + "val") == "336699" for node in body.findall(".//" + W + "color")), "graphics-state body color was not recovered")
                if extension == "pdf":
                    for engine, pages in enumerate(references(path, OUTPUT / label)):
                        require(len(pages) == 1, "recovered graphics-state pagination")
                        tiles.append((f"{label} recovered {engine+1}", pages[0]))
            require(normalized[0] == normalized[1], "IGNORE discarded graphics-state conversion losses")
    for kind in ("transparent", "blend", "font", "unknown"):
        reports = []
        for action in ("warn", "ignore"):
            label = "gs-" + kind + "-" + action
            require((directory / (label + ".text.txt")).read_text().strip() == "SAFE PAGE", "unsupported graphics-state content leaked into the recovered model")
            report = losses(directory / (label + ".losses.tsv"), action, {"PDF_STRUCTURE"})
            skipped = [item for item in report if item["feature"] == "PDF_STRUCTURE"]
            require(len(skipped) == 1 and skipped[0]["treatment"] == "SKIP_BLOCK"
                    and skipped[0]["effects"] == "PAGE_NOT_RECOVERED", "unsupported graphics-state page lacks the skip diagnostic")
            sources = rows(directory / (label + ".sources.tsv"))
            require(len(sources) == 1 and sources[0]["page"] == "0", "skipped graphics-state page retained source mappings")
            reports.append(report)
        require(reports[0] == reports[1], "IGNORE discarded unsupported-state losses")
    for kind in ("missing", "malformed"):
        require((directory / ("gs-" + kind + "-protected.pdf")).read_bytes() == b"existing destination", "invalid graphics state overwrote the old destination")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--consumer-build-directory", type=Path)
    args = parser.parse_args()
    consumer = args.consumer_build_directory
    if consumer is None:
        consumer = Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip())
    if not consumer.is_absolute():
        consumer = ROOT / consumer
    directory = consumer / "output/pdf-compatibility"
    manifest = json.loads((FIXTURES / "manifest.json").read_text())
    require(manifest["fontSha256"] == sha256((ROOT / "assets/fonts/LiberationSans-Regular.ttf").read_bytes()).hexdigest(), "explicit fixture font changed")
    for name, item in manifest["files"].items():
        source = FIXTURES / (name + ".pdf")
        require(sha256(source.read_bytes()).hexdigest() == item["sha256"] and len(PdfReader(source).pages) == item["pages"], "fixture bytes/page count changed")
        copied = directory / (name + "-source.pdf")
        if copied.exists():
            require(copied.read_bytes() == source.read_bytes(), "consumer changed external input bytes")
    summary = json.loads((directory / "summary.json").read_text())
    require(summary == {"mappedSources": 2, "mappedImages": 4, "redactions": 4, "stateConversions": 16, "stateSkips": 8, "rejections": 12}, "consumer did not complete every compatibility case")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    tiles = []
    metrics = {}
    font_mapping(directory, tiles, metrics)
    graphics_states(directory, tiles)
    sheets = OUTPUT / "contact-sheets"
    sheets.mkdir(exist_ok=True)
    contact_sheet(tiles, sheets)
    report = {"checks": "passed", **summary, "fixtureFiles": len(manifest["files"]), "renderedPdfPages": 12,
              "independentPageImages": 24, "contactTiles": len(tiles), "mappingRasterMetrics": metrics,
              "tools": {name: version(name) for name in ("pypdf", "pypdfium2", "Pillow", "python-docx", "markdown-it-py")}}
    (OUTPUT / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in report.items() if key not in ("mappingRasterMetrics", "tools")}))


if __name__ == "__main__":
    main()
