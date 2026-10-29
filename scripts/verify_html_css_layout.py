# SPDX-License-Identifier: Apache-2.0
"""Verify compiled offline HTML/CSS geometry, pagination, links and declared losses."""
import argparse
import base64
from importlib import metadata
import json
from pathlib import Path
import re
import subprocess
import time
from uuid import uuid4

from PIL import Image, ImageChops, ImageDraw, ImageOps
from pypdf import PdfReader
import resvg_py

from verify_advanced_pdf import difference, require
from verify_pdf_raster import references
from verify_production import contact_sheet

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build/verification/gap18-current"
SCENES = {
    "flex-grow": ["GROW-A", "GROW-B"],
    "flex-shrink": ["SHRINK-A", "SHRINK-B"],
    "flex-wrap": ["WRAP-A", "WRAP-B", "WRAP-C", "WRAP-D"],
    "flex-column": ["COLUMN-A", "COLUMN-B"],
    "grid": ["GRID-A", "GRID-B", "GRID-C"],
    "grid-span": ["SPAN-A", "SPAN-B", "SPAN-C"],
    "position": ["RELATIVE", "ABSOLUTE"],
    "body": ["BODY-A", "BODY-B"],
}
COLORS = [(216, 91, 54), (53, 116, 189), (65, 150, 91), (162, 108, 187), (238, 233, 221)]


def chromium(source, name, executable):
    require(executable.is_file(), "an explicit Chromium executable is required")
    font = base64.b64encode((ROOT / "assets/fonts/LiberationSans-Regular.ttf").read_bytes()).decode("ascii")
    # The explicit local font belongs only to this independent browser harness. The importer
    # continues to reject @font-face, data URLs in CSS and implicit resource loading.
    harness = OUTPUT / (name + "-source.html")
    harness.write_text("<!doctype html><meta charset='utf-8'><style>"
                       + "@font-face{font-family:VerificationFont;src:url(data:font/ttf;base64," + font + ")}"
                       + "html{margin:0;padding:0;width:560px;height:400px;background:white}"
                       + "body{margin:20pt;padding:0;font:12pt/18pt VerificationFont;background:white}"
                       + "</style>" + source.read_text(encoding="utf-8"), encoding="utf-8")
    target = OUTPUT / (name + "-chromium.png")
    target.unlink(missing_ok=True)
    profile = OUTPUT / ("chromium-profile-" + uuid4().hex)
    process = subprocess.Popen([str(executable), "--headless=new", "--disable-gpu", "--hide-scrollbars", "--disable-extensions",
                                "--no-first-run", "--no-default-browser-check", "--user-data-dir=" + str(profile),
                                "--force-device-scale-factor=1.5", "--screenshot=" + str(target), "--window-size=560,400",
                                "--virtual-time-budget=2000", harness.as_uri()], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            if target.exists():
                try:
                    with Image.open(target) as image:
                        image.verify()
                    break
                except (OSError, SyntaxError):
                    pass
            time.sleep(.05)
        else:
            raise AssertionError("Chromium did not produce a complete CSS screenshot within 45 seconds")
        process.wait(timeout=10)
    finally:
        if process.poll() is None:
            process.kill()
            process.wait(timeout=10)
    with Image.open(target) as image:
        require(image.size == (840, 600), "browser viewport/density differs from explicit PDF page")
    return target


def mask(image, color):
    channels = image.convert("RGB").split()
    result = Image.new("L", image.size, 255)
    for channel, value in zip(channels, color, strict=True):
        result = ImageChops.multiply(result, channel.point(lambda x: 255 if abs(x - value) <= 3 else 0))
    return result


def boxes_match(reference, candidate):
    with Image.open(reference) as browser, Image.open(candidate) as actual:
        require(browser.size == actual.size, "CSS output image size differs")
        result = []
        for color in COLORS:
            source = mask(browser, color); target = mask(actual, color)
            source_box = source.getbbox(); target_box = target.getbbox()
            require(bool(source_box) == bool(target_box), f"CSS color box missing: {color} in {candidate.name}")
            if source_box is None:
                continue
            require(max(abs(a - b) for a, b in zip(source_box, target_box, strict=True)) <= 2,
                    f"CSS box bounds differ from Chromium: {color}, {source_box}, {target_box}")
            # Fill only holes enclosed by this background. The flood fill preserves gaps
            # connected to the outside, including a same-color box incorrectly split in two.
            # Font baselines belong to the independent text/output checks.
            source_shape = background_shape(source); target_shape = background_shape(target)
            intersection = sum(ImageChops.multiply(source_shape, target_shape).histogram()[1:])
            union = sum(ImageChops.lighter(source_shape, target_shape).histogram()[1:])
            ratio = intersection / union
            require(ratio > .975, f"CSS box visible area differs from Chromium: {color}, {ratio}")
            result.append({"rgb": color, "browserBounds": source_box, "outputBounds": target_box, "intersectionOverUnion": ratio})
        require(len(result) >= 2, "layout scene did not contain its independent color geometry")
        return result


def background_shape(binary):
    result = Image.new("L", binary.size)
    bounds = binary.getbbox()
    if bounds:
        cropped = ImageOps.expand(binary.crop(bounds), border=1, fill=0)
        ImageDraw.floodfill(cropped, (0, 0), 128)
        filled = cropped.point(lambda value: 0 if value == 128 else 255)
        result.paste(filled.crop((1, 1, filled.width - 1, filled.height - 1)), (bounds[0], bounds[1]))
    return result


def negative_controls():
    directory = OUTPUT / "negative-controls"; directory.mkdir(exist_ok=True)
    source = Image.new("RGB", (240, 160), "white"); drawing = ImageDraw.Draw(source)
    drawing.rectangle((20, 20, 99, 79), fill=COLORS[0]); drawing.rectangle((140, 20, 219, 79), fill=COLORS[1])
    reference = directory / "source.png"; source.save(reference)
    for name in ("missing-box", "wrong-width", "wrong-gap", "split-background"):
        candidate = source.copy(); drawing = ImageDraw.Draw(candidate)
        if name == "missing-box":
            drawing.rectangle((20, 20, 99, 79), fill="white")
        elif name == "wrong-width":
            drawing.rectangle((90, 20, 99, 79), fill="white")
        elif name == "wrong-gap":
            drawing.rectangle((140, 20, 219, 79), fill="white"); drawing.rectangle((130, 20, 209, 79), fill=COLORS[1])
        else:
            drawing.rectangle((55, 20, 64, 79), fill="white")
        target = directory / (name + ".png"); candidate.save(target)
        try:
            boxes_match(reference, target)
        except AssertionError:
            continue
        raise AssertionError("CSS geometry verifier accepted its negative control: " + name)
    return 4


def content(reader, expected):
    text = "\n".join(page.extract_text() for page in reader.pages)
    previous = -1
    for token in expected:
        require(text.count(token) == 1, f"source text missing or duplicated: {token}")
        index = text.index(token)
        require(index > previous, "CSS visual order changed the logical source order")
        previous = index


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--consumer-build-directory", type=Path)
    parser.add_argument("--chromium", type=Path, required=True)
    args = parser.parse_args()
    directory = args.consumer_build_directory or Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip())
    caller = directory / "output/html-css-layout"
    OUTPUT.mkdir(parents=True, exist_ok=True)
    controls = negative_controls()
    summary = json.loads((caller / "summary.json").read_text())
    require(summary["scenes"] == 8 and summary["losses"] >= 6 and summary["refusals"] == 8 and summary["atomicControls"] == 2,
            "isolated CSS caller did not finish its policy, safety and atomic controls")
    report = {"summary": summary, "geometry": {}, "rendering": {}, "pages": 0, "negativeControls": controls,
              "tools": {name: metadata.version(name) for name in ("pypdf", "pypdfium2", "resvg-py", "Pillow")}}
    tiles = []
    for name, tokens in SCENES.items():
        colors = re.findall(r"background-color:\s*(#[a-fA-F0-9]{6})", (caller / (name + ".html")).read_text())
        require(len(colors) == len(set(colors)) and len(colors) >= 2, "each reference scene must declare one rectangle per unique background color")
        pdf = caller / (name + ".pdf")
        reader = PdfReader(pdf, strict=True)
        require(len(reader.pages) == 1 and list(map(float, reader.pages[0].mediabox)) == [0, 0, 420, 300], "CSS scene page dimensions differ")
        content(reader, tokens)
        first, second = references(pdf, OUTPUT / name)
        svg = OUTPUT / (name + "-resvg.png")
        svg.write_bytes(resvg_py.svg_to_bytes(svg_string=(caller / (name + ".svg")).read_text(), dpi=144, width=840, height=600, skip_system_fonts=True))
        browser = chromium(caller / (name + ".html"), name, args.chromium)
        report["geometry"][name] = {engine: boxes_match(browser, image) for engine, image in (("poppler", first[0]), ("pdfium", second[0]), ("resvg", svg))}
        for image in (second[0], svg):
            delta = difference(first[0], image, 1)
            require(delta["meanAbsoluteChannelError"] < 1 and delta["changedPixelFraction"] < .015, f"CSS output independent render mismatch: {name}: {delta}")
            report["rendering"].setdefault(name, []).append(delta)
        tiles.extend([(name + " Chromium source", browser), (name + " Poppler", first[0]), (name + " PDFium", second[0]), (name + " resvg", svg)])
        report["pages"] += 1
    for name in ("pagination", "links", "fallback-warn", "fallback-ignore"):
        pdf = caller / (name + ".pdf"); reader = PdfReader(pdf, strict=True)
        if name == "pagination":
            require(len(reader.pages) == 3, "explicit CSS page breaks did not produce three pages")
            for index, page in enumerate(reader.pages):
                require(list(map(float, page.mediabox)) == [0, 0, 300, 240] and page.extract_text().strip() == ["PAGE-ONE", "PAGE-TWO", "PAGE-THREE"][index], "@page geometry or page-break order differs")
        elif name == "links":
            content(reader, ["LINK-TEXT", "LINK-OTHER"])
            annotations = [item.get_object() for page in reader.pages for item in page.get("/Annots", [])]
            require(len(annotations) == 1 and annotations[0]["/A"]["/URI"] == "https://example.org/css-layout", "CSS layout lost its real hyperlink")
            rectangle = list(map(float, annotations[0]["/Rect"]))
            require(23.9 <= rectangle[0] <= 24.1 and 24 < rectangle[2] < 194 and 260 < rectangle[1] < rectangle[3] < 281, "CSS link rectangle did not follow the leaf translation")
            report["linkRectangle"] = rectangle
        else:
            content(reader, ["FALLBACK-A", "FALLBACK-B"])
            positions = []
            def visit(text, cm, tm, font, size):
                if text.strip():
                    x = float(tm[4] * cm[0] + tm[5] * cm[2] + cm[4]); y = float(tm[4] * cm[1] + tm[5] * cm[3] + cm[5])
                    positions.append((text.strip(), x, y))
            reader.pages[0].extract_text(visitor_text=visit)
            require(len(positions) == 2 and abs(positions[0][1] - positions[1][1]) < .01 and positions[0][2] > positions[1][2], "flex fallback did not actually become block flow")
        first, second = references(pdf, OUTPUT / name)
        for index, (left, right) in enumerate(zip(first, second, strict=True)):
            delta = difference(left, right, 1)
            require(delta["meanAbsoluteChannelError"] < 1 and delta["changedPixelFraction"] < .015, "independent page rendering differs")
            tiles.extend([(f"{name} {index+1} Poppler", left), (f"{name} {index+1} PDFium", right)])
        report["pages"] += len(reader.pages)
    require((caller / "atomic-old.pdf").read_bytes() == b"OLD-DESTINATION", "failed CSS output replaced old destination")
    require(not list(caller.glob(".export4j-*.tmp")), "CSS failure left an atomic temporary file")
    contact_sheet(tiles, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"checks": "passed", "pages": report["pages"], "geometryScenes": len(SCENES), "summary": summary}, indent=2))


if __name__ == "__main__":
    main()
