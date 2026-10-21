# SPDX-License-Identifier: Apache-2.0
"""Independent PDF structures, editable-field round trip, two rendering engines and visual baseline.

Requires Poppler on PATH and requirements-verification.txt. Baselines change only with
--update-baseline after review of every rendered page. No PDF byte equality assertions.
"""
from __future__ import annotations

import argparse
import hashlib
from importlib import metadata
import json
import platform
import re
import shutil
import subprocess
from pathlib import Path

import pypdfium2 as pdfium
from PIL import Image, ImageChops, ImageFilter, ImageStat
from pypdf import PdfReader, PdfWriter
from pypdf.generic import ArrayObject, DecodedStreamObject, DictionaryObject, FloatObject, NameObject, NumberObject, TextStringObject

ROOT = Path(__file__).resolve().parents[1]


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def widgets(reader):
    result = []
    for index, page in enumerate(reader.pages):
        for reference in page.get("/Annots", []):
            widget = reference.get_object()
            if widget.get("/Subtype") == "/Widget":
                parent = widget.get("/Parent")
                field = parent.get_object() if parent else widget
                result.append((index, field, widget))
    return result


def check_fields(reader, expected):
    fields = reader.get_fields() or {}
    require(set(fields) == set(expected), "canonical field tree differs from expected names")
    for name, value in expected.items():
        require(fields[name].get("/V") == value, f"stale canonical value: {name}")
        require(not fields[name].get("/Ff", 0) & 1, f"read-only field: {name}")
    found = widgets(reader)
    require(len(found) == 5, "expected three text/choice widgets plus two radio widgets")
    for _, field, widget in found:
        name = field.get("/T")
        require(name in expected and field.get("/V") == expected[name], "widget/parent value disagrees with canonical tree")
        normal = widget.get("/AP", {}).get("/N")
        require(normal is not None, f"missing normal appearance: {name}")
        normal = normal.get_object()
        if field.get("/FT") == "/Btn":
            state = widget.get("/AS")
            require(state in normal, f"radio appearance state missing: {name}")
            require(bool(normal[state].get_object().get_data()), "empty radio appearance")
        else:
            require(bool(normal.get_data()), f"empty field appearance: {name}")
    selected = [widget.get("/AS") for _, field, widget in found if field.get("/T") == "mode" and widget.get("/AS") != "/Off"]
    require(selected == [expected["mode"]], "radio widgets do not have exactly one selected value")
    form = reader.trailer["/Root"]["/AcroForm"]
    require(not getattr(form.get("/NeedAppearances"), "value", False), "appearance regeneration must not be required")


def check_structure(path):
    reader = PdfReader(path)
    require(len(reader.pages) >= 5, "fixture should exercise pagination")
    sizes = [(float(p.mediabox.width), float(p.mediabox.height)) for p in reader.pages]
    require(any(w > h for w, h in sizes) and any(w < h for w, h in sizes), "mixed portrait/landscape sections missing")
    text = "\n".join(page.extract_text() or "" for page in reader.pages)
    flow = re.findall(r"FLOW-(\d{2})", text)
    require(flow == [f"{number:02d}" for number in range(1, 63)], "nested flow lost/repeated/reordered source lines")
    for number in range(1, 13):
        require(f"GROUP-{number:02d}" in text, f"merged table lost group {number}")
    for expected in ("Real Bold", "Italic + underline", "Figure 1", "[1]", "[2]", "正文结束"):
        require(re.sub(r"\s+", "", expected) in re.sub(r"\s+", "", text), f"missing semantic content: {expected}")
    check_fields(reader, {"cn_name": "文档引擎", "status": "审核", "mode": "/draft", "cn_notes": "可编辑中文备注\n请填写并保存。"})
    outlines = []
    def walk(items):
        for item in items:
            if isinstance(item, list):
                walk(item)
            else:
                outlines.append(reader.get_destination_page_number(item))
    walk(reader.outline)
    require(len(outlines) == 4 and all(0 <= page < len(reader.pages) for page in outlines), "outline targets invalid")
    links = 0
    soft_masks = 0
    for page in reader.pages:
        for annotation in page.get("/Annots", []):
            annotation = annotation.get_object()
            if annotation.get("/Subtype") == "/Link":
                require("/Dest" in annotation, "internal reference link lacks destination")
                links += 1
        for obj in page.get("/Resources", {}).get("/XObject", {}).values():
            soft_masks += int("/SMask" in obj.get_object())
    require(links == 6, "expected four contents links and two cross references")
    require(soft_masks >= 1, "PNG transparency has no PDF soft mask")
    return {"pages": len(reader.pages), "sizes": sizes, "links": links, "outlinePages": outlines, "softMasks": soft_masks}


def round_trip(path, output):
    writer = PdfWriter()
    writer.clone_document_from_reader(PdfReader(path))
    expected = {"cn_name": "新增中文姓名", "status": "完成", "mode": "/final", "cn_notes": "保存后的中文备注\n第二行。"}
    writer.update_page_form_field_values(None, {"mode": expected["mode"]}, auto_regenerate=False)
    form = writer.root_object["/AcroForm"]
    # pypdf 6.10's high-level Type0 appearance writer fails on the integer CMap
    # metadata key. This independent fixture filler uses the PDF's complete
    # ToUnicode mapping, without modifying pypdf or dropping field appearances.
    for reference in form["/Fields"]:
        field = reference.get_object()
        name = field.get("/T")
        if name != "mode":
            field[NameObject("/V")] = TextStringObject(expected[name])
            if name == "status":
                field[NameObject("/I")] = ArrayObject([NumberObject(field["/Opt"].index(expected[name]))])
            fixture_text_appearance(writer, form, field, expected[name])
    with output.open("wb") as stream:
        writer.write(stream)
    check_fields(PdfReader(output), expected)


def fixture_text_appearance(writer, form, field, value):
    """Single-font, fixed-size Identity-H fixture fields only; intentionally not a generic PDF form editor."""
    match = re.search(r"/(\w+)\s+([\d.]+)\s+Tf", field.get("/DA", form["/DA"]))
    require(match is not None, "fixture default appearance font missing")
    font_name, size = match[1], float(match[2])
    font = form["/DR"]["/Font"][f"/{font_name}"]
    require(font["/Encoding"] == "/Identity-H", "fixture filler requires Identity-H")
    descendant = font["/DescendantFonts"][0].get_object()
    require(descendant.get("/CIDToGIDMap") == "/Identity", "fixture filler requires complete glyph-ID mapping")
    cmap = font["/ToUnicode"].get_data().decode("ascii")
    reverse = {}
    for block in re.findall(r"beginbfrange(.*?)endbfrange", cmap, re.S):
        for start, end, destination in re.findall(r"<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>", block):
            first, last, target = int(start, 16), int(end, 16), int(destination, 16)
            for cid in range(first, last + 1):
                char = (target + cid - first).to_bytes(len(destination) // 2, "big").decode("utf-16-be")
                reverse.setdefault(char, cid.to_bytes(2, "big"))
    for block in re.findall(r"beginbfchar(.*?)endbfchar", cmap, re.S):
        for source, destination in re.findall(r"<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>", block):
            reverse.setdefault(bytes.fromhex(destination).decode("utf-16-be"), bytes.fromhex(source))
    require(all(char in reverse for char in value if char != "\n"), "new fixture text is missing embedded glyphs")
    rect = field["/Rect"]
    width, height = float(rect[2] - rect[0]), float(rect[3] - rect[1])
    ascent = float(descendant["/FontDescriptor"]["/Ascent"]) / 1000 * size
    commands = [f"q 1 1 1 rg 0 0 {width} {height} re f 0.6 0.7 0.8 RG 0.7 w 0.35 0.35 {width - 0.7} {height - 0.7} re S",
                f"1 1 {width - 2} {height - 2} re W n BT /{font_name} {size} Tf 0 g"]
    for index, line in enumerate(value.split("\n")):
        encoded = b"".join(reverse[char] for char in line).hex()
        y = height - 3 - ascent - index * size * 1.2
        require(y > 0, "fixture multiline text is taller than the field")
        commands.append(f"1 0 0 1 3 {y} Tm <{encoded}> Tj")
    commands.append("ET Q")
    stream = DecodedStreamObject()
    stream.set_data("\n".join(commands).encode("ascii"))
    stream.update({NameObject("/Type"): NameObject("/XObject"), NameObject("/Subtype"): NameObject("/Form"),
                   NameObject("/BBox"): ArrayObject([FloatObject(0), FloatObject(0), FloatObject(width), FloatObject(height)]),
                   NameObject("/Resources"): form["/DR"]})
    field[NameObject("/AP")] = DictionaryObject({NameObject("/N"): writer._add_object(stream)})


def render_poppler(path, directory):
    directory.mkdir(parents=True, exist_ok=True)
    subprocess.run(["pdftoppm", "-r", "96", "-png", str(path), str(directory / "page")], check=True, capture_output=True)
    return sorted(directory.glob("page-*.png"), key=lambda item: int(item.stem.rsplit("-", 1)[1]))


def render_pdfium(path, directory):
    directory.mkdir(parents=True, exist_ok=True)
    paths = []
    with pdfium.PdfDocument(path) as document:
        document.init_forms()
        for index in range(len(document)):
            page = document[index]
            try:
                bitmap = page.render(scale=96 / 72, draw_annots=True)
                try:
                    output = directory / f"page-{index + 1}.png"
                    bitmap.to_pil().convert("RGB").save(output)
                    paths.append(output)
                finally:
                    bitmap.close()
            finally:
                page.close()
    return paths


def difference(first, second, blur_radius=0):
    with Image.open(first) as a, Image.open(second) as b:
        require(a.size == b.size, f"render dimensions differ: {a.size} versus {b.size}")
        a, b = a.convert("RGB"), b.convert("RGB")
        if blur_radius:
            a = a.filter(ImageFilter.GaussianBlur(blur_radius))
            b = b.filter(ImageFilter.GaussianBlur(blur_radius))
        diff = ImageChops.difference(a, b)
        mean = sum(ImageStat.Stat(diff).mean) / 3
        histogram = diff.convert("L").histogram()
        changed = sum(histogram[25:]) / (a.width * a.height)
        return {"meanAbsoluteChannelError": round(mean, 6), "changedPixelFraction": round(changed, 6)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pdf", type=Path, default=ROOT / "examples/build/export4j-advanced-example.pdf")
    parser.add_argument("--baseline", type=Path, default=ROOT / "verification/baselines/advanced-poppler")
    parser.add_argument("--output", type=Path, default=ROOT / "examples/build/advanced-verification")
    parser.add_argument("--update-baseline", action="store_true")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    report = check_structure(args.pdf)
    poppler_version = subprocess.run(["pdftoppm", "-v"], check=True, capture_output=True, text=True)
    report["tools"] = {"python": platform.python_version(), "platform": platform.platform(),
                       "poppler": (poppler_version.stderr or poppler_version.stdout).splitlines()[0],
                       "pdfium": str(pdfium.PDFIUM_INFO),
                       **{name: metadata.version(name) for name in ("pypdf", "pypdfium2", "Pillow")}}
    filled = args.output / "filled-roundtrip.pdf"
    round_trip(args.pdf, filled)
    poppler = render_poppler(args.pdf, args.output / "poppler")
    pdfium_pages = render_pdfium(args.pdf, args.output / "pdfium")
    render_pdfium(filled, args.output / "filled-pdfium")
    require(len(poppler) == len(pdfium_pages) == report["pages"], "renderers disagree on page count")
    # At 96 DPI the engines round fractional glyph baselines differently. A
    # one-pixel blur tolerates hinting while still detecting missing/moved content.
    # Same-engine persisted baseline comparisons below remain unblurred.
    report["crossRendererRawDifference"] = [difference(a, b) for a, b in zip(poppler, pdfium_pages, strict=True)]
    cross = [difference(a, b, blur_radius=1) for a, b in zip(poppler, pdfium_pages, strict=True)]
    require(all(diff["meanAbsoluteChannelError"] < 4 and diff["changedPixelFraction"] < 0.06 for diff in cross), "PDFium/Poppler renderings differ materially")
    report["crossRendererDifference"] = cross
    report["crossRendererBlurRadiusPixels"] = 1
    if args.update_baseline:
        args.baseline.mkdir(parents=True, exist_ok=True)
        # Only the explicit baseline target is mutated; no recursive directory removal.
        for old in args.baseline.glob("page-*.png"):
            old.unlink()
        for index, source in enumerate(poppler, 1):
            shutil.copyfile(source, args.baseline / f"page-{index}.png")
        (args.baseline / "manifest.json").write_text(json.dumps({"pages": report["pages"], "dpi": 96, "tools": report["tools"],
            "sourceSha256": hashlib.sha256(args.pdf.read_bytes()).hexdigest()}, indent=2) + "\n", encoding="utf-8")
    reference = sorted(args.baseline.glob("page-*.png"), key=lambda item: int(item.stem.rsplit("-", 1)[1]))
    require(len(reference) == len(poppler), "baseline page count differs; review every page before an explicit baseline update")
    comparison = [difference(a, b) for a, b in zip(poppler, reference, strict=True)]
    require(all(diff["meanAbsoluteChannelError"] <= 0.7 and diff["changedPixelFraction"] <= 0.02 for diff in comparison), "visual regression exceeds tolerance")
    report["baselineDifference"] = comparison
    report["formRoundTrip"] = "passed, editable Unicode text, dropdown and radio group"
    (args.output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
