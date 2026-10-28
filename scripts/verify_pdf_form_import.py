# SPDX-License-Identifier: Apache-2.0
"""Check external Forms, recovered source geometry, clipping losses and four-format conversions."""
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
from verify_advanced_pdf import require
from verify_pdf_font_compatibility import compact, converted_text, pdf_text
from verify_pdf_raster import references
from verify_production import contact_sheet

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "build/verification/pdf-form-fixtures"
OUTPUT = ROOT / "build/verification/pdf-form-import"
W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"


def rows(path):
    with path.open(encoding="utf-8", newline="") as source:
        return list(csv.DictReader(source, delimiter="\t"))


def losses(path, action):
    items = rows(path)
    require(items and all(item["action"] == action.upper() for item in items), "Form actual loss actions changed")
    return [{key: value for key, value in item.items() if key != "action"} for item in items]


def ink(image, box):
    return image.crop(box).convert("L").point(lambda value: 255 if value < 210 else 0)


def source_regions(name, items, rendered):
    metrics = []
    for item in items:
        if item["text"] == "[IMAGE]":
            point = (round((item["x"] + item["width"] / 2) * 2), round((item["y"] + item["height"] / 2) * 2))
            for path in rendered:
                with Image.open(path) as image:
                    require(image.convert("RGB").getpixel(point) == (34, 102, 170), "Form image transformation or inherited clipping differs")
            continue
        # A line-sized region compares visible ink rather than averaging the white
        # page. Two pixels account for distinct rasterizers' edge antialiasing.
        box = tuple(round(value * 2) for value in (item["x"] - 2, item["baseline"] - item["size"] - 2,
                    item["x"] + item["width"] + 3, item["baseline"] + 3))
        with Image.open(rendered[0]) as left, Image.open(rendered[1]) as right:
            a = ink(left, box)
            b = ink(right, box)
        count_a, count_b = sum(a.histogram()[1:]), sum(b.histogram()[1:])
        require(count_a > 60 and count_b > 60, "source Form line is missing: " + name)
        require(.65 < count_a / count_b < 1.4, "source Form line ink differs between independent engines")
        require(max(abs(x - y) for x, y in zip(a.getbbox(), b.getbbox(), strict=True)) <= 2,
                "source Form line location differs between independent engines")
        missing_a = sum(ImageChops.subtract(a, b.filter(ImageFilter.MaxFilter(3))).histogram()[1:])
        missing_b = sum(ImageChops.subtract(b, a.filter(ImageFilter.MaxFilter(3))).histogram()[1:])
        unmatched = (missing_a + missing_b) / (count_a + count_b)
        require(unmatched < .08, "source Form glyph coverage differs between independent engines")
        metrics.append({"text": item["text"], "box": box, "ink": [count_a, count_b], "unmatchedInkFraction": unmatched})
    if name == "form-hidden":
        for path in rendered:
            with Image.open(path) as image:
                require(ink(image, (60, 70, 460, 140)).getbbox() is None,
                        "independent source rendering did not clip hidden Form content")
    return metrics


def model_geometry(path, expected):
    actual = rows(path)
    require(len(actual) == len(expected), "Form source map has missing or duplicate instances")
    for item, declaration in zip(actual, expected, strict=True):
        require(item["text"] == declaration["text"] and item["page"] == "0", "Form source order/page changed")
        require(abs(float(item["x"]) - declaration["x"]) < .002, "Form source horizontal origin changed")
        require(abs(float(item["width"]) - declaration["width"]) < .003, "Form source widths/resource scope changed")
        if declaration["text"] == "[IMAGE]":
            require(abs(float(item["y"]) - declaration["y"]) < .002 and abs(float(item["height"]) - declaration["height"]) < .002,
                    "Form image source rectangle changed")
        else:
            require(abs(float(item["y"]) + float(item["height"]) - declaration["baseline"]) < .002,
                    "Form source baseline changed")
            require(abs(float(item["size"]) - declaration["size"]) < .002 and item["color"] == declaration["color"],
                    "Form source size/color or graphics-state restoration changed")


def docx_structure(path, expected, has_image):
    with ZipFile(path) as archive:
        document = ET.fromstring(archive.read("word/document.xml"))
        media = [name for name in archive.namelist() if name.startswith("word/media/")]
        require(len(media) == int(has_image), "DOCX did not preserve the expected Form image")
        if has_image:
            from io import BytesIO
            with Image.open(BytesIO(archive.read(media[0]))) as image:
                require(image.size == (6, 4) and set(image.convert("RGB").get_flattened_data()) == {(34, 102, 170)}, "DOCX changed recovered Form pixels")
    for declaration in expected:
        if declaration["text"] == "[IMAGE]":
            continue
        text = declaration["text"].replace("BODY", "READY")
        candidates = [node for node in document.findall(".//" + W + "p")
                      if "".join(part.text or "" for part in node.iter(W + "t")) == text]
        require(candidates, "DOCX lost a Form paragraph")
        for node in candidates:
            require(any(value.get(W + "val") == declaration["color"] for value in node.findall(".//" + W + "color")), "DOCX Form color/state changed")
            require(any(value.get(W + "val") == str(round(declaration["size"] * 2)) for value in node.findall(".//" + W + "sz")), "DOCX Form scaled size changed")


def rendered_pages(source, name, tiles):
    rendered = references(source, OUTPUT / name)
    require(len(rendered[0]) == len(rendered[1]) == len(PdfReader(source).pages), "Form independent rendering page count")
    for engine, pages in enumerate(rendered):
        for index, path in enumerate(pages):
            with Image.open(path) as image:
                require(image.size == (720, 480), "Form output physical page size changed")
                require(sum(ink(image, (0, 0, 720, 480)).histogram()[1:]) > 100, "Form rendering is blank")
            tiles.append((f"{name} {index+1} engine {engine+1}", path))
    return rendered


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--consumer-build-directory", type=Path)
    args = parser.parse_args()
    consumer = args.consumer_build_directory or Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip())
    if not consumer.is_absolute():
        consumer = ROOT / consumer
    directory = consumer / "output/pdf-form-import"
    manifest = json.loads((FIXTURES / "manifest.json").read_text())
    require(manifest["fontSha256"] == sha256((ROOT / "assets/fonts/LiberationSans-Regular.ttf").read_bytes()).hexdigest(), "explicit Form fixture font changed")
    summary = json.loads((directory / "summary.json").read_text())
    require(summary == {"acceptedSources": 5, "conversions": 40, "skips": 6, "rejections": 14}, "consumer did not finish all Form checks")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    tiles, metrics = [], {}
    rendered_count = 0
    for name, source_record in manifest["files"].items():
        source = FIXTURES / (name + ".pdf")
        require(sha256(source.read_bytes()).hexdigest() == source_record["sha256"] and len(PdfReader(source).pages) == source_record["pages"], "Form fixture source changed")
        if source_record["kind"] == "accepted":
            require((directory / (name + "-source.pdf")).read_bytes() == source.read_bytes(), "consumer modified Form input")
            rendered = rendered_pages(source, name + "-source", tiles)
            rendered_count += source_record["pages"]
            metrics[name] = source_regions(name, source_record["contents"], [engine[0] for engine in rendered])
            expected_text = "".join(item["text"] for item in source_record["contents"] if item["text"] != "[IMAGE]")
            source_text = compact(pdf_text(source))
            if name == "form-hidden":
                # PDF text extraction can expose text outside a Form's BBox;
                # visible regions and recovered model content are checked separately.
                for item in source_record["contents"]:
                    require(compact(item["text"]) in source_text, "external Form source text is not independently readable")
            else:
                require(source_text == compact(expected_text), "external Form source text/order differs from its declaration")
            for extension in ("pdf", "docx", "html", "md"):
                reports = []
                for action in ("warn", "ignore"):
                    label = name + "-" + action
                    model_geometry(directory / (label + ".model.tsv"), source_record["contents"])
                    require(compact((directory / (label + ".text.txt")).read_text()) == compact(expected_text), "Form model text differs from the external declaration")
                    path = directory / (label + "." + extension)
                    converted_expected = expected_text.replace("BODY", "READY")
                    if extension == "pdf":
                        converted_expected = "".join("\ufffc" if item["text"] == "[IMAGE]" else item["text"].replace("BODY", "READY")
                                                     for item in source_record["contents"])
                    require(compact(converted_text(path)) == compact(converted_expected), "Form conversion lost, duplicated or revealed content: " + path.name)
                    report = losses(path.with_name(path.name + ".losses.tsv"), action)
                    require(not any(item["feature"] == "PDF_STRUCTURE" for item in report), "accepted Form was skipped during conversion")
                    require({"PDF_LAYOUT", "PDF_FONT_MAPPING"} <= {item["feature"] for item in report}, "Form conversion misses actual recovery losses")
                    if name == "form-hidden":
                        require({"PDF_HIDDEN_TEXT", "PDF_IMAGE_TRANSFORM"} <= {item["feature"] for item in report}, "hidden Form content lacks actual losses")
                    reports.append(report)
                    if extension == "docx":
                        docx_structure(path, source_record["contents"], name == "form-matrix")
                    if extension == "pdf":
                        page = PdfReader(path).pages[0]
                        # PDFium honors the image's standard ActualText marker;
                        # pypdf extracts its invisible one-glyph 'x' carrier. Both
                        # must appear only at the declared image position.
                        parser_expected = "".join("x" if item["text"] == "[IMAGE]" else item["text"].replace("BODY", "READY")
                                                  for item in source_record["contents"])
                        require(compact(page.extract_text()) == compact(parser_expected), "independent PDF parsers disagree about converted Form content")
                        properties = page["/Resources"].get("/Properties", {})
                        actual_texts = [str(value.get_object().get("/ActualText", "")) for value in properties.values()]
                        require(actual_texts == (["\ufffc"] if name == "form-matrix" else []), "PDF Form image ActualText marker changed")
                        rendered_pages(path, label, tiles)
                        rendered_count += 1
                require(reports[0] == reports[1], "IGNORE discarded actual Form conversion losses")
            if name == "form-matrix":
                for action in ("warn", "ignore"):
                    with Image.open(directory / (name + "-" + action + "-image-1.png")) as image:
                        require(image.size == (6, 4) and set(image.convert("RGB").get_flattened_data()) == {(34, 102, 170)}, "recovered Form image pixels changed")
        elif source_record["kind"] == "skipped":
            rendered_pages(source, name + "-source", tiles)
            rendered_count += source_record["pages"]
            reports = []
            for action in ("warn", "ignore"):
                label = name + "-" + action
                require((directory / (label + ".text.txt")).read_text().strip() == "SAFE PAGE", "skipped Form leaked source content")
                actual = rows(directory / (label + ".model.tsv"))
                require(len(actual) == 1 and actual[0]["page"] == "0", "skipped Form retained source map")
                report = losses(directory / (label + ".losses.tsv"), action)
                require(sum(item["feature"] == "PDF_STRUCTURE" and item["treatment"] == "SKIP_BLOCK" for item in report) == 1, "partial Form lacks one page-level skip")
                reports.append(report)
            require(reports[0] == reports[1], "IGNORE discarded Form skip losses")
        else:
            require((directory / (name + "-protected.pdf")).read_bytes() == b"existing destination", "invalid Form changed old target")
    sheets = OUTPUT / "contact-sheets"
    sheets.mkdir(exist_ok=True)
    contact_sheet(tiles, sheets)
    report = {"checks": "passed", **summary, "fixtureFiles": len(manifest["files"]), "renderedPdfPages": rendered_count,
              "independentPageImages": len(tiles), "sourceLineMetrics": metrics,
              "tools": {name: version(name) for name in ("pypdf", "pypdfium2", "Pillow", "markdown-it-py")}}
    (OUTPUT / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in report.items() if key not in ("sourceLineMetrics", "tools")}))


if __name__ == "__main__":
    main()
