# SPDX-License-Identifier: Apache-2.0
"""Check actual unequal column widths, logical order, physical styles, media and dual rendering."""
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from pypdf import PdfReader
from PIL import Image, ImageChops, ImageFilter
from verify_advanced_pdf import difference, require
from verify_inline_images import image_boxes
from verify_pdf_raster import references
from verify_production import contact_sheet
from verify_semantic_tables import check_characters, page_logical
from verify_streaming_rich_text import decoration, painted_spans, same_color, strokes

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "examples/build/variable-columns"
OUTPUT = ROOT / "build/verification/variable-columns"


def tokens(reader, pattern, expected):
    text = "\n".join(page.extract_text() + "\n" + "\n".join(page_logical(page)) for page in reader.pages)
    require(re.findall(pattern, text) == expected, "variable-column logical source lost, repeated or reordered")
    return text


def geometry(reader, name):
    spans = [span for span in painted_spans(reader) if "WORD" in span["text"]]
    require(spans, "variable fixture has no independently decoded glyphs")
    groups = {}
    for span in spans:
        column = int(span["x"] > 120)
        left = (24, 129)[column] + (3 if name == "mixed" else 0)
        right = (109, 384)[column] - (3 if name == "mixed" else 0)
        require(left - .01 <= span["x"] <= span["right"] <= right + .01, "text leaves its actual column width")
        groups.setdefault((span["page"], round(span["baseline"], 2), column), []).append(span["text"])
    counts = [(key[2], len(re.findall(r"WORD\d{2}", "".join(values)))) for key, values in groups.items()]
    require(max(count for column, count in counts if column == 0) == 1, "narrow column was wrapped at a wider width")
    require(max(count for column, count in counts if column == 1) >= 3, "wide column was wrapped at the narrow width")
    if name == "rich":
        lines = strokes(reader)
        for index in range(1, 61):
            found = [span for span in spans if f"WORD{index:02d}" in span["text"]]
            require(len(found) == 1, "rich physical run lost or repeated")
            span = found[0]
            expected = (34 / 255, 68 / 255, 136 / 255) if index % 2 == 0 else (136 / 255, 68 / 255, 34 / 255)
            require(same_color(span["color"], expected), "rich run ink changed")
            require(span["font"].endswith("LiberationSans-Bold" if index % 2 == 0 else "LiberationSans"), "rich physical font changed")
            if index % 2 == 0: decoration(span, lines, 1.2)


def ink_coverage(first, second):
    # At 144 dpi, two pixels are one point. Geometry is checked separately against actual PDF advances.
    # Dense text hinting changes grayscale averages; missing strokes must still match in both directions.
    with Image.open(first) as a, Image.open(second) as b:
        require(a.size == b.size, "independent render sizes differ")
        masks = [value.convert("L").point(lambda level: 255 if level < 180 else 0) for value in (a, b)]
    missing = []
    for index in range(2):
        count = sum(masks[index].histogram()[1:])
        require(count > 0, "render contains no visible ink")
        uncovered = ImageChops.subtract(masks[index], masks[1-index].filter(ImageFilter.MaxFilter(5)))
        missing.append(sum(uncovered.histogram()[1:]) / count)
    return missing


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    tiles, report = [], {}
    caller = Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip()) / "output/consumer-variable-columns.pdf"
    for name, path in [(name, SOURCE / f"{name}.pdf") for name in ("plain", "rich", "complex", "mixed")] + [("caller", caller)]:
        reader = PdfReader(path, strict=True)
        require(len(reader.pages) == 2, "variable fixture page contract changed")
        expected = [f"C{index:02d}" for index in range(1, 37)] if name == "complex" else [
            f"CALL{index:02d}" if name == "caller" else f"WORD{index:02d}" for index in range(1, 41 if name == "caller" else 61)]
        pattern = r"C\d{2}" if name == "complex" else r"CALL\d{2}" if name == "caller" else r"WORD\d{2}"
        text = tokens(reader, pattern, expected)
        if name == "complex":
            require(text.count("العربية") == text.count("a\u0301") == 36, "complex clusters lost across unequal regions")
            require(all(any("C" in value for value in page_logical(page)) for page in reader.pages), "complex source did not continue onto both pages")
        elif name != "caller": geometry(reader, name)
        if name == "mixed":
            compact = "".join(text.split())
            require(compact.count("MIXED-BEGIN") == compact.count("MIXED-END") == 1, "mixed atomic content duplicated")
            images = image_boxes(reader)
            require(len(images) == 1 and images[0]["width"] == images[0]["height"] == 16, "mixed image lost or resized")
        if name != "caller":
            root = ET.parse(SOURCE / f"{name}.svg").getroot()
            require(len(list(root.iter("{http://www.w3.org/2000/svg}image"))) == (1 if name == "mixed" else 0), "SVG mixed media")
        differences = []
        for index, (first, second) in enumerate(zip(*references(path, OUTPUT / name), strict=True)):
            diff = difference(first, second, blur_radius=1)
            diff["uncoveredInkFractions"] = ink_coverage(first, second)
            require(max(diff["uncoveredInkFractions"]) < .001, f"independent render strokes differ: {name} page {index+1}")
            differences.append(diff)
            tiles.extend([(f"{name} Poppler {index+1}", first), (f"{name} PDFium {index+1}", second)])
        report[name] = {"pages": len(reader.pages), "characters": check_characters(path, 24), "renderDifferences": differences}
    contact_sheet(tiles, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print("Unequal columns: source order, actual widths, styled runs, complex clusters, mixed image, caller and dual rendering passed")


if __name__ == "__main__":
    main()
