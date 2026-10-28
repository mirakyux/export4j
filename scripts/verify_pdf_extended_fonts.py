# SPDX-License-Identifier: Apache-2.0
"""Independently inspect extended source-font mappings, static programs and raster recovery."""
import argparse
import csv
from hashlib import sha256
from importlib.metadata import version
import json
from pathlib import Path
from zipfile import ZipFile
from PIL import Image, ImageChops, ImageFilter
from pypdf import PdfReader
from verify_advanced_pdf import require
from verify_pdf_compatibility import redacted_pixels
from verify_pdf_font_compatibility import compact, pdf_text
from verify_pdf_raster import references
from verify_production import contact_sheet

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "build/verification/pdf-extended-font-fixtures"
OUTPUT = ROOT / "build/verification/pdf-extended-fonts"


def ink(image, box):
    return image.crop(box).convert("L").point(lambda value: 255 if value < 210 else 0)


def glyph_metrics(actual, reference):
    """Inspect both painted lines so whitespace cannot conceal a missing or moved glyph."""
    require(actual.size == reference.size == (480, 240), "font fixture page dimensions changed")
    metrics = []
    for box in ((30, 30, 300, 86), (30, 140, 180, 186)):
        left, right = ink(actual, box), ink(reference, box)
        a, b = sum(left.histogram()[1:]), sum(right.histogram()[1:])
        require(a > 60 and b > 60, "font fixture text line is missing")
        require(.70 < a / b < 1.35, "mapped glyph ink differs from the embedded control")
        require(max(abs(x - y) for x, y in zip(left.getbbox(), right.getbbox(), strict=True)) <= 2,
                "source font metrics or glyph location changed")
        missing_a = sum(ImageChops.subtract(left, right.filter(ImageFilter.MaxFilter(3))).histogram()[1:])
        missing_b = sum(ImageChops.subtract(right, left.filter(ImageFilter.MaxFilter(3))).histogram()[1:])
        unmatched = (missing_a + missing_b) / (a + b)
        require(unmatched < .08, "mapped character codes select different glyphs")
        metrics.append({"region": box, "ink": [a, b], "bounds": [left.getbbox(), right.getbbox()], "unmatchedInkFraction": unmatched})
    return metrics


def raw_font(path):
    return PdfReader(path).pages[0]["/Resources"]["/Font"]["/F1"].get_object()


def control_structure(name):
    source_path = FIXTURES / (name + ".pdf")
    control_path = FIXTURES / (name + "-control.pdf")
    source = PdfReader(source_path).pages[0]
    control = PdfReader(control_path).pages[0]
    require(source.get_contents().get_data() == control.get_contents().get_data(), "embedded control changed source content codes or positions")
    if name == "cid-shared":
        source_fonts = source["/Resources"]["/Font"]
        original_a, original_b = source_fonts["/F1"].get_object(), source_fonts["/F2"].get_object()
        require(original_a["/DescendantFonts"][0].indirect_reference == original_b["/DescendantFonts"][0].indirect_reference,
                "shared CID fixture no longer tests a single shared descendant")
        controls = control["/Resources"]["/Font"]
        for key in ("/F1", "/F2"):
            original, mapped = source_fonts[key].get_object(), controls[key].get_object()
            a, b = original["/DescendantFonts"][0].get_object(), mapped["/DescendantFonts"][0].get_object()
            require(original["/Encoding"] == mapped["/Encoding"] if key == "/F1" else original["/Encoding"].get_object().get_data() == mapped["/Encoding"].get_object().get_data(), "shared CID control changed root Encoding")
            require(original["/ToUnicode"].get_object().get_data() == mapped["/ToUnicode"].get_object().get_data(), "shared CID control changed root Unicode evidence")
            require(a["/W"] == b["/W"] and a["/DW"] == b["/DW"] == 1000, "shared CID control changed source widths")
            require("/FontFile2" not in a["/FontDescriptor"].get_object() and "/CIDToGIDMap" not in a, "shared source unexpectedly embeds a mapping")
            require(b["/FontDescriptor"].get_object()["/FontFile2"].get_object().get_data() == (ROOT / "assets/fonts/LiberationSans-Regular.ttf").read_bytes(), "shared control changed explicit program")
        a, b = (controls[key].get_object()["/DescendantFonts"][0].get_object()["/CIDToGIDMap"].get_object().get_data() for key in ("/F1", "/F2"))
        require(a[2:4] == b[4:6] and a[4:6] == b[2:4] and a[2:4] != a[4:6], "shared CID controls do not require distinct glyph bindings")
        require(compact(pdf_text(control_path)) == "ABBA", "shared CID control Unicode changed")
        return
    a, b = raw_font(source_path), raw_font(control_path)
    require(a["/Encoding"] == b["/Encoding"] if name != "cid-custom" else a["/Encoding"].get_object().get_data() == b["/Encoding"].get_object().get_data(),
            "embedded control changed source encoding")
    require(a["/ToUnicode"].get_object().get_data() == b["/ToUnicode"].get_object().get_data(), "embedded control changed Unicode evidence")
    if name.startswith("cid-"):
        a, b = a["/DescendantFonts"][0].get_object(), b["/DescendantFonts"][0].get_object()
        require(a["/W"] == b["/W"] and a["/DW"] == b["/DW"] == 1000, "embedded CID control changed declared widths")
        require("/CIDToGIDMap" not in a and "/CIDToGIDMap" in b, "control must supply an explicit glyph map")
        gids = b["/CIDToGIDMap"].get_object().get_data()
        cids = (7, 9) if name == "cid-custom" else (1, 2)
        require(all(int.from_bytes(gids[cid * 2:cid * 2 + 2], "big") > 0 for cid in cids), "CID control selected a missing glyph")
    else:
        require(a["/Widths"] == b["/Widths"] and a["/FirstChar"] == b["/FirstChar"] == 0 and a["/LastChar"] == b["/LastChar"] == 255,
                "simple control changed source width range")
        require(a["/Encoding"]["/Differences"] == [128, "/Euro"], "fixture no longer tests dictionary Differences")
    require("/FontFile2" not in a["/FontDescriptor"].get_object(), "source fixture already embeds its mapping font")
    require(b["/FontDescriptor"].get_object()["/FontFile2"].get_object().get_data() == (ROOT / "assets/fonts/LiberationSans-Regular.ttf").read_bytes(),
            "independent control does not use the exact explicit TTF bytes")
    require(compact(pdf_text(control_path)) == ("€A€A" if name == "differences" else "ABAA"), "control Unicode evidence differs")


def loss_rows(path, action, required):
    with path.open(encoding="utf-8", newline="") as source:
        result = list(csv.DictReader(source, delimiter="\t"))
    require(result and all(item["action"] == action.upper() for item in result), "font loss actions changed")
    require(required <= {item["feature"] for item in result}, "font actual loss missing")
    return [{key: value for key, value in item.items() if key != "action"} for item in result]


def redaction(path, original, tiles):
    page = PdfReader(path).pages[0]
    require(not page["/Resources"].get("/Font") and len(list(page["/Resources"]["/XObject"].values())) == 1, "redaction copied source font resources")
    require(not pdf_text(path).strip() and not page.extract_text().strip(), "redaction retained extractable source text")
    with redacted_pixels(path) as image, Image.open(original) as source:
        require(image.size == source.size == (480, 240), "redaction dimensions changed")
        require(set(image.crop((0, 20, 480, 110)).get_flattened_data()) == {(255, 255, 255)}, "selected glyph pixels were not erased")
        require(image.crop((0, 110, 480, 240)).tobytes() == source.convert("RGB").crop((0, 110, 480, 240)).tobytes(), "unselected font pixels changed")
    for engine, pages in enumerate(references(path, OUTPUT / path.stem)):
        require(len(pages) == 1, "redacted font pagination")
        tiles.append((f"{path.stem} engine {engine+1}", pages[0]))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--consumer-build-directory", type=Path)
    args = parser.parse_args()
    consumer = args.consumer_build_directory or Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip())
    if not consumer.is_absolute():
        consumer = ROOT / consumer
    directory = consumer / "output/pdf-extended-fonts"
    OUTPUT.mkdir(parents=True, exist_ok=True)
    manifest = json.loads((FIXTURES / "manifest.json").read_text())
    require(manifest["fontSha256"] == sha256((ROOT / "assets/fonts/LiberationSans-Regular.ttf").read_bytes()).hexdigest(), "fixture font snapshot changed")
    for name, details in manifest["files"].items():
        source = FIXTURES / (name + ".pdf")
        require(sha256(source.read_bytes()).hexdigest() == details["sha256"] and (directory / source.name).read_bytes() == source.read_bytes(), "external source snapshot changed")
    summary = json.loads((directory / "summary.json").read_text())
    require(summary == {"mappedSources": 4, "mappedImages": 8, "embeddedImages": 2, "redactions": 10, "rasterRecoveries": 2, "skippedCmapPages": 2, "rejections": 29}, "extended font consumer did not complete all cases")
    tiles, metrics = [], {}
    for name in ("differences", "cid-identity", "cid-custom", "cid-shared"):
        control_structure(name)
        independent = references(FIXTURES / (name + "-control.pdf"), OUTPUT / (name + "-control"))
        for engine, pages in enumerate(independent):
            require(len(pages) == 1, "font control pagination")
            tiles.append((f"{name} explicit control engine {engine+1}", pages[0]))
        normalized = []
        for action in ("warn", "ignore"):
            label = name + "-" + action
            actual = directory / (label + ".png")
            metrics[label] = []
            with Image.open(actual) as image:
                for pages in independent:
                    with Image.open(pages[0]) as reference:
                        metrics[label].append(glyph_metrics(image, reference))
            with ZipFile(directory / (label + ".zip")) as bundle:
                require(len(bundle.namelist()) == 1 and bundle.read(bundle.namelist()[0]) == actual.read_bytes(), "mapped ZIP changed encoded image bytes")
            tiles.append((label + " Export4j", actual))
            normalized.append(loss_rows(directory / (label + ".losses.tsv"), action, {"RENDER_FONT_MAPPING"}))
            loss_rows(directory / (label + "-redacted.losses.tsv"), action, {"RENDER_FONT_MAPPING", "RASTERIZATION"})
            redaction(directory / (label + "-redacted.pdf"), actual, tiles)
        require(normalized[0] == normalized[1], "IGNORE discarded actual font losses")
    for name in ("type1", "type3"):
        actual = directory / (name + ".png")
        font = raw_font(FIXTURES / (name + ".pdf"))
        if name == "type1":
            program = font["/FontDescriptor"].get_object()["/FontFile"].get_object()
            require(program.get_data().startswith(b"%!PS-AdobeFont-1.0: Export4jFixture") and len(program.get_data()) == program["/Length1"] + program["/Length2"] + program["/Length3"], "original Type1 segments are inconsistent")
        else:
            require(font["/CharProcs"]["/A"].get_object().get_data().startswith(b"600 0 0 0 600 700 d1"), "Type3 fixture no longer paints its own static CharProc")
        metrics[name] = []
        for engine, pages in enumerate(references(FIXTURES / (name + ".pdf"), OUTPUT / (name + "-source"))):
            require(len(pages) == 1, "static font source pagination")
            with Image.open(actual) as image, Image.open(pages[0]) as reference:
                metrics[name].append(glyph_metrics(image, reference))
            tiles.append((f"{name} source engine {engine+1}", pages[0]))
        tiles.append((name + " Export4j", actual))
        redaction(directory / (name + "-redacted.pdf"), actual, tiles)
    recovery_losses = []
    for action in ("warn", "ignore"):
        label = "type3-recovered-" + action
        with Image.open(directory / (label + ".png")) as recovered, Image.open(directory / "type3.png") as actual:
            require(recovered.size == actual.size and recovered.convert("RGB").tobytes() == actual.convert("RGB").tobytes(), "Type3 semantic degradation lost visible page content")
        recovery_losses.append(loss_rows(directory / (label + ".losses.tsv"), action, {"PDF_LAYOUT", "PDF_STRUCTURE"}))
        tiles.append((label + " actual image block", directory / (label + ".png")))
    require(recovery_losses[0] == recovery_losses[1], "Type3 IGNORE dropped actual raster recovery losses")
    protected = list(directory.glob("*-protected.png"))
    require(len(protected) == 16 and all(path.read_bytes() == b"old output" for path in protected), "rejected source font changed an old output")
    rejected_cmap = []
    for action in ("warn", "ignore"):
        rejected_cmap.append(loss_rows(directory / ("conflicting-cid-" + action + ".losses.tsv"), action, {"RENDER_FONT"}))
    require(rejected_cmap[0] == rejected_cmap[1], "IGNORE dropped ambiguous CMap skip losses")
    sheets = OUTPUT / "contact-sheets"
    sheets.mkdir(exist_ok=True)
    contact_sheet(tiles, sheets)
    report = {"counts": summary, "fixtures": len(manifest["files"]), "independentPageImages": 32, "contactTiles": len(tiles),
              "metrics": metrics, "tools": {name: version(name) for name in ("pypdf", "pypdfium2", "Pillow")}}
    (OUTPUT / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"fixtures": report["fixtures"], "independentPageImages": report["independentPageImages"], "contactTiles": len(tiles), "checks": "passed"}))


if __name__ == "__main__":
    main()
