# SPDX-License-Identifier: Apache-2.0
"""Independently verify actual Form edits, clone isolation, geometry and visible pixels."""
import argparse
import csv
from hashlib import sha256
from importlib.metadata import version
import json
import math
from pathlib import Path
import re
from PIL import Image, ImageChops, ImageDraw, ImageFilter
import pypdfium2 as pdfium
from pypdf import PdfReader
from pypdf.generic import ContentStream
from verify_advanced_pdf import require
from verify_pdf_raster import references
from verify_production import contact_sheet

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "build/verification/pdf-form-edit-fixtures"
OUTPUT = ROOT / "build/verification/pdf-form-edit"


def canonical(value):
    value = value.get_object() if hasattr(value, "get_object") else value
    if hasattr(value, "get_data"):
        return {"stream": sha256(value.get_data()).hexdigest(), "dictionary": {str(key): canonical(item) for key, item in value.items()
                if str(key) not in ("/Length", "/Filter", "/DecodeParms")}}
    if isinstance(value, dict):
        return {str(key): canonical(item) for key, item in value.items()}
    if isinstance(value, list):
        return [canonical(item) for item in value]
    if isinstance(value, (int, float)):
        return float(value)
    return str(value)


def invoked(value, resources, reader):
    result = []
    for operands, operator in ContentStream(value, reader).operations:
        if operator == b"Do":
            target = resources["/XObject"][operands[0]].get_object()
            if target.get("/Subtype") == "/Form":
                result.append((str(operands[0]), target))
    return result


def signature(form, inherited, reader):
    resources = form.get("/Resources", inherited).get_object()
    return {"stream": sha256(form.get_data()).hexdigest(),
            "attributes": {key: canonical(form[key]) for key in ("/Type", "/Subtype", "/FormType", "/BBox", "/Matrix", "/Group", "/OC") if key in form},
            "fonts": canonical(resources.get("/Font", {})), "states": canonical(resources.get("/ExtGState", {})),
            "children": [signature(child, resources, reader) for _, child in invoked(form, resources, reader)]}


def physical_characters(path, index):
    with pdfium.PdfDocument(path) as document:
        page = document[index]
        try:
            textpage = page.get_textpage()
            try:
                text, boxes = [], []
                for position in range(textpage.count_chars()):
                    value = textpage.get_text_range(position, 1)
                    require(len(value) == 1, "fixture text is not one character per PDFium index")
                    text.append(value)
                    boxes.append(textpage.get_charbox(position))
                return "".join(text), boxes
            finally:
                textpage.close()
        finally:
            page.close()


def word_boxes(text, boxes, word):
    result = []
    for match in re.finditer(re.escape(word), text):
        selected = boxes[match.start():match.end()]
        result.append((min(item[0] for item in selected), min(item[1] for item in selected),
                       max(item[2] for item in selected), max(item[3] for item in selected)))
    return result


def transformed_words(before, after):
    before_text, before_boxes = before
    after_text, after_boxes = after
    original = word_boxes(before_text, before_boxes, "KEEP")
    actual = word_boxes(after_text, after_boxes, "KEEP")
    require(len(original) == len(actual) >= 2, "unmatched Form words were lost or duplicated")
    for expected_box, actual_box in zip(original, actual, strict=True):
        for expected, value in zip(expected_box, actual_box, strict=True):
            # PDFium returns float coordinates. Eight ULPs cover its matrix and
            # glyph-bound arithmetic; this is below 0.0005 pt on these pages.
            tolerance = math.ldexp(1.0, math.frexp(abs(expected))[1] - 24) * 8
            require(abs(expected - value) <= tolerance, "unmatched Form glyph position/size changed")


def expected_text(text, selected):
    index = -1
    def replace(match):
        nonlocal index
        index += 1
        return "HI" if index in selected else match.group()
    return "".join(re.sub("SECRET", replace, text).split())


def pixel_box(box):
    left, bottom, right, top = box
    return (math.floor(left * 2) - 3, math.floor((400 - top) * 2) - 3,
            math.ceil(right * 2) + 3, math.ceil((400 - bottom) * 2) + 3)


def unchanged_pixels(source, target, boxes, word_regions=()):
    with Image.open(source) as before, Image.open(target) as after:
        require(before.size == after.size == (1200, 800), "Form edit changed the physical page size")
        delta = ImageChops.difference(before.convert("RGB"), after.convert("RGB"))
        draw = ImageDraw.Draw(delta)
        for box in boxes:
            require(ImageChops.difference(before.crop(box).convert("RGB"), after.crop(box).convert("RGB")).getbbox() is not None,
                    "selected Form instance has no changed pixels")
            require(after.crop(box).convert("L").point(lambda value: 255 if value < 210 else 0).getbbox() is not None,
                    "replacement text has no visible ink")
            draw.rectangle(box, fill=(0, 0, 0))
        if not boxes:
            require(delta.getbbox() is None, "unselected Form/page pixels changed")
            return
        # Preserving the old text advance changes the grouping of floating
        # point additions. Poppler can hint an unchanged glyph's edge one pixel
        # differently at an exact pixel boundary despite a one-ULP position
        # difference. Keep exact word geometry and original font-byte checks,
        # and check ink coverage/color rather than identical edge alpha here.
        masks = [value.convert("L").point(lambda item: 255 if item < 210 else 0) for value in (before, after)]
        for mask in masks:
            for box in boxes:
                ImageDraw.Draw(mask).rectangle(box, fill=0)
        counts = [sum(mask.histogram()[1:]) for mask in masks]
        require(min(counts) > 100 and .95 < counts[0] / counts[1] < 1.05, "unmatched Form ink amount changed")
        missing = sum(ImageChops.subtract(masks[0], masks[1].filter(ImageFilter.MaxFilter(3))).histogram()[1:])
        missing += sum(ImageChops.subtract(masks[1], masks[0].filter(ImageFilter.MaxFilter(3))).histogram()[1:])
        require(missing == 0, "unmatched Form glyph ink moved or disappeared")
        for region in word_regions:
            original_color = [channel.getextrema()[0] for channel in before.crop(region).convert("RGB").split()]
            actual_color = [channel.getextrema()[0] for channel in after.crop(region).convert("RGB").split()]
            require(max(abs(a - b) for a, b in zip(original_color, actual_color, strict=True)) <= 2,
                    "unmatched Form word color changed")


def render(path, label, tiles):
    images = references(path, OUTPUT / label)
    require(all(len(items) == 2 for items in images), "Form editing changed pagination")
    for engine, pages in enumerate(images):
        for index, image in enumerate(pages):
            tiles.append((f"{label} page {index+1} engine {engine+1}", image))
    for index in range(2):
        text, boxes = physical_characters(path, index)
        for word in ("HI", "KEEP", "CONTROL"):
            for bounds in word_boxes(text, boxes, word):
                box = pixel_box(bounds)
                with Image.open(images[0][index]) as left, Image.open(images[1][index]) as right:
                    a = left.crop(box).convert("L").point(lambda value: 255 if value < 210 else 0)
                    b = right.crop(box).convert("L").point(lambda value: 255 if value < 210 else 0)
                counts = [sum(value.histogram()[1:]) for value in (a, b)]
                require(min(counts) > 10 and .6 < counts[0] / counts[1] < 1.5, "Form word is absent in an independent renderer")
                require(max(abs(x - y) for x, y in zip(a.getbbox(), b.getbbox(), strict=True)) <= 2,
                        "independent Form renderers place a word differently")
                missing = sum(ImageChops.subtract(a, b.filter(ImageFilter.MaxFilter(3))).histogram()[1:])
                missing += sum(ImageChops.subtract(b, a.filter(ImageFilter.MaxFilter(3))).histogram()[1:])
                require(missing / sum(counts) < .12, "Form word ink differs between independent renderers")
    return images


def check_structure(source, target, record):
    before, after = PdfReader(source), PdfReader(target)
    require(len(before.pages) == len(after.pages) == 2, "Form output page count changed")
    require(before.pages[1].get_contents().get_data() == after.pages[1].get_contents().get_data(), "unselected page content stream changed")
    for index in range(2):
        left_resources, right_resources = before.pages[index]["/Resources"], after.pages[index]["/Resources"]
        for name in ("/Body", "/Untouched"):
            require(signature(left_resources["/XObject"][name].get_object(), left_resources, before)
                    == signature(right_resources["/XObject"][name].get_object(), right_resources, after),
                    "source Form, font, state, or nested resource was mutated")
    source_calls = invoked(before.pages[0].get_contents(), before.pages[0]["/Resources"], before)
    output_calls = invoked(after.pages[0].get_contents(), after.pages[0]["/Resources"], after)
    require(len(source_calls) == len(output_calls) == 3, "Form invocation count changed")
    changed = 0
    for (source_name, original), (output_name, candidate) in zip(source_calls, output_calls, strict=True):
        require(canonical(original["/BBox"]) == canonical(candidate["/BBox"])
                and canonical(original["/Matrix"]) == canonical(candidate["/Matrix"]), "Form BBox or Matrix changed")
        if source_name != output_name:
            changed += 1
            require(signature(original, before.pages[0]["/Resources"], before) != signature(candidate, after.pages[0]["/Resources"], after),
                    "new invocation resource does not contain an actual edit")
    require(changed == record["changes"], "Form resource rebinding does not match changed actual invocations")
    if record["changes"] == 0:
        require(before.pages[0].get_contents().get_data() == after.pages[0].get_contents().get_data(), "skipped Form page was rewritten")


def read_report(path, action):
    with path.open(encoding="utf-8", newline="") as source:
        items = list(csv.DictReader(source, delimiter="\t"))
    require(items and all(item["action"] == action for item in items), "Form edit actual losses lost the selected action")
    return [{key: value for key, value in item.items() if key != "action"} for item in items]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--consumer-build-directory", type=Path)
    args = parser.parse_args()
    consumer = args.consumer_build_directory or Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip())
    if not consumer.is_absolute():
        consumer = ROOT / consumer
    directory = consumer / "output/pdf-form-edit"
    manifest = json.loads((FIXTURES / "manifest.json").read_text())
    require(manifest["fontSha256"] == sha256((ROOT / "assets/fonts/LiberationSans-Regular.ttf").read_bytes()).hexdigest(), "explicit Form edit font changed")
    summary = json.loads((directory / "summary.json").read_text())
    require(summary == {"sources": 7, "outputs": 15, "changes": 22, "rejections": 11}, "Form consumer did not complete its matrix")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    tiles = []
    for name, record in manifest["files"].items():
        source = FIXTURES / (name + ".pdf")
        require(sha256(source.read_bytes()).hexdigest() == record["sha256"], "Form source bytes changed")
        require((directory / (name + "-protected.pdf")).read_bytes() == b"existing destination", "rejected Form edit overwrote the destination")
        if record["kind"] == "invalid":
            continue
        require((directory / (name + "-source.pdf")).read_bytes() == source.read_bytes(), "consumer source snapshot changed")
        source_images = render(source, name + "-source", tiles)
        source_text = [physical_characters(source, index) for index in range(2)]
        source_secrets = word_boxes(*source_text[0], "SECRET")
        require(len(source_secrets) == 2, "Form source does not contain exactly two selected-page instances")
        edit_boxes = [pixel_box(source_secrets[index]) for index in record["editedSecretIndices"]]
        keep_boxes = [pixel_box(value) for value in word_boxes(*source_text[0], "KEEP")]
        reports = []
        labels = [name + "-warn", name + "-ignore"] + (["shared-chained"] if name == "shared" else [])
        for label in labels:
            target = directory / (label + ".pdf")
            check_structure(source, target, record)
            text = [physical_characters(target, index) for index in range(2)]
            require("".join(text[0][0].split()) == expected_text(source_text[0][0], record["editedSecretIndices"]), "Form edit lost, duplicated, or changed unselected text")
            require(text[1] == source_text[1], "unselected page text/glyph boxes changed")
            transformed_words(source_text[0], text[0])
            report = read_report(target.with_name(target.name + ".losses.tsv"), "IGNORE" if label.endswith("ignore") else "WARN")
            if label.endswith(("warn", "ignore")):
                reports.append(report)
            if record["changes"]:
                require(sum(item["feature"] == "TEXT_FONT" for item in report) == (4 if label == "shared-chained" else record["changes"]), "Form match losses do not reconcile with changes")
            else:
                require(all(item["feature"] == record["strictFeature"] for item in report), "skipped Form reported an unrelated loss")
            actual_images = render(target, label, tiles)
            for engine in range(2):
                unchanged_pixels(source_images[engine][0], actual_images[engine][0], edit_boxes, keep_boxes)
                unchanged_pixels(source_images[engine][1], actual_images[engine][1], [])
        require(reports[0] == reports[1], "IGNORE discarded actual Form edit losses")
    sheets = OUTPUT / "contact-sheets"
    sheets.mkdir(exist_ok=True)
    contact_sheet(tiles, sheets)
    result = {"checks": "passed", **summary, "fixtureFiles": len(manifest["files"]), "independentPageImages": len(tiles),
              "tools": {name: version(name) for name in ("pypdf", "pypdfium2", "Pillow")}}
    (OUTPUT / "report.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result))


if __name__ == "__main__":
    main()
