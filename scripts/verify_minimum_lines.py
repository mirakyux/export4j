# SPDX-License-Identifier: Apache-2.0
"""Check minimum-line reflow against final PDF glyphs, SVG and two independent renderers."""
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

from pypdf import PdfReader
from verify_advanced_pdf import difference, require
from verify_pdf_raster import references
from verify_production import contact_sheet
from verify_streaming_rich_text import decoration, painted_spans, same_color, strokes
from verify_variable_columns import ink_coverage

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "examples/build/minimum-lines"
OUTPUT = ROOT / "build/verification/minimum-lines"


def check_source(reader, name):
    pattern, count = (r"CALL\d{2}", 5) if name == "caller" else (r"KEEP\d{2}", 5) if name == "explicit" else (r"TOKEN\d{2}", 31)
    prefix = "CALL" if name == "caller" else "KEEP" if name == "explicit" else "TOKEN"
    text = "\n".join(page.extract_text() for page in reader.pages)
    require(re.findall(pattern, text) == [f"{prefix}{index:02d}" for index in range(1, count + 1)],
            "minimum-line source lost, duplicated or reordered")
    require(not re.search(pattern, reader.pages[0].extract_text()), "short first-page columns consumed source")
    require(text.count("CALLER-PREFACE" if name == "caller" else "PREFACE") == 1, "preface duplicated")
    if name != "caller": require(text.count("DOCUMENT-END") == 1, "following paragraph lost or repeated")
    spans = [span for span in painted_spans(reader) if re.search(pattern, span["text"])]
    groups = {}
    for span in spans:
        column = int(span["x"] >= (100 if name == "caller" else 120))
        left, right = ((20, 80) if column == 0 else (100, 280)) if name == "caller" else (
            (24, 109) if column == 0 else (129, 384))
        require(left - .003 <= span["x"] <= span["right"] <= right + .003, "glyphs exceed their measured column")
        groups.setdefault((span["page"], column), set()).add(round(span["baseline"], 3))
    require(groups and min(map(len, groups.values())) >= 2, "a split leaves fewer than two lines")
    if name in ("explicit", "caller"):
        require([len(lines) for lines in groups.values()] == [3, 2], "last singleton was not moved to the next column")
    else:
        require(any(len(re.findall(pattern, span["text"])) >= 3 for span in spans)
                if name == "wrapped" else any(len(lines) >= 2 for (page, column), lines in groups.items() if column == 1),
                "wide column did not use its actual width")
    first = spans[0]
    require(abs(first["x"] - (20 if name == "caller" else 32)) < .003, "original first-line indent changed")
    require(all(abs(span["x"] - 129) < .003 for span in spans if name == "explicit" and span["x"] >= 120),
            "first-line indent repeated on continuation")
    if name == "rich":
        lines = strokes(reader)
        for index in range(1, 32):
            matches = [span for span in spans if f"TOKEN{index:02d}" in span["text"]]
            require(len(matches) == 1, "styled run lost or repeated")
            span = matches[0]
            require(same_color(span["color"], (34 / 255, 68 / 255, 136 / 255) if index % 2 == 0 else (136 / 255, 68 / 255, 34 / 255)),
                    "reflow changed explicit run color")
            require(span["font"].endswith("LiberationSans-Bold" if index % 2 == 0 else "LiberationSans"), "physical font changed")
            if index % 2 == 0: decoration(span, lines, 1.2)
    return {f"page-{page + 1}-column-{column + 1}": len(lines) for (page, column), lines in groups.items()}


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    caller = Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip()) / "output/consumer-minimum-lines.pdf"
    tiles, report = [], {}
    for name, path in [(name, SOURCE / f"{name}.pdf") for name in ("explicit", "wrapped", "rich")] + [("caller", caller)]:
        reader = PdfReader(path, strict=True)
        counts = check_source(reader, name)
        if name != "caller":
            svg = ET.parse(SOURCE / f"{name}.svg").getroot()
            require(sum(1 for _ in svg.iter("{http://www.w3.org/2000/svg}clipPath")) >= 2, "SVG column viewports missing")
        first, second = references(path, OUTPUT / name)
        require(len(first) == len(second) == len(reader.pages), "independent page counts differ")
        comparisons = []
        for index, (a, b) in enumerate(zip(first, second, strict=True)):
            delta = difference(a, b, blur_radius=1)
            delta["uncoveredInkFractions"] = ink_coverage(a, b)
            require(max(delta["uncoveredInkFractions"]) < .001, "independent renderers disagree on visible strokes")
            comparisons.append(delta)
            tiles.extend([(f"{name} Poppler {index + 1}", a), (f"{name} PDFium {index + 1}", b)])
        report[name] = {"pages": len(reader.pages), "linesPerColumn": counts, "renderDifferences": comparisons}
    contact_sheet(tiles, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print("Minimum lines: actual column widths, source order, 3/2 split, short-page skip, rich styles, caller and dual rendering passed")


if __name__ == "__main__":
    main()
