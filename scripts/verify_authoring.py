# SPDX-License-Identifier: Apache-2.0
"""Independent PDF advances/anchors, semantic OOXML and two-renderer authoring verification."""
from __future__ import annotations

import base64
from importlib import metadata
import json
from pathlib import Path
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from pypdf import PdfReader
from pypdf.generic import TextStringObject
from verify_advanced_pdf import difference, render_pdfium, render_poppler, require
from verify_inline_images import compose, image_boxes
from verify_multi_floats import pdf_geometry, point
from verify_production import actual_text, contact_sheet

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "examples/build/authoring"
OUTPUT = ROOT / "build/verification/authoring"
NO_START = "、。，．！？：；）］｝〉》」』】〕〗〙〛’”％‰…)]},.;:!?%"
NO_END = "（［｛〈《「『【〔〖〘〚‘“([{"


def cid_widths(font):
    descendant = font["/DescendantFonts"][0].get_object()
    widths, values, index = {}, descendant.get("/W", []), 0
    while index < len(values):
        start, item = int(values[index]), values[index + 1]
        if isinstance(item, list):
            widths.update((start + offset, float(value)) for offset, value in enumerate(item))
            index += 2
        else:
            widths.update((cid, float(values[index + 2])) for cid in range(start, int(item) + 1))
            index += 3
    return widths, float(descendant.get("/DW", 1000))


def glyph_advances(reader):
    """Compute final painted advances from CID widths and relative TJ operands, independent of Java IR."""
    result = []
    for page_index, page in enumerate(reader.pages):
        matrix, tm, stack = (1, 0, 0, 1, 0, 0), None, []
        font, size = None, 0
        for operands, operator in page.get_contents().operations:
            if operator == b"q":
                stack.append(matrix)
            elif operator == b"Q":
                matrix = stack.pop()
            elif operator == b"cm":
                matrix = compose(matrix, tuple(float(value) for value in operands))
            elif operator == b"Tf":
                font = page["/Resources"]["/Font"][operands[0]].get_object()
                size = float(operands[1])
            elif operator == b"Tm":
                tm = tuple(float(value) for value in operands)
            elif operator == b"TJ":
                require(tm is not None and font.get("/Subtype") == "/Type0", "fixture glyph mapping changed")
                widths, default = cid_widths(font)
                advance = 0.0
                for item in operands[0]:
                    if isinstance(item, (str, bytes)):
                        data = item.original_bytes if isinstance(item, TextStringObject) else bytes(item)
                        require(len(data) % 2 == 0, "CID string has odd byte count")
                        advance += sum(widths.get(int.from_bytes(data[i:i + 2], "big"), default) for i in range(0, len(data), 2)) * size / 1000
                    else:
                        advance -= float(item) * size / 1000
                x, y = point(matrix, tm[4], tm[5])
                result.append({"page": page_index, "x": x, "baseline": float(page.mediabox.height) - y, "right": x + advance})
    return result


def manifest(name):
    rows = []
    for value in (FIXTURES / f"{name}-lines.tsv").read_text(encoding="utf-8").splitlines():
        block, page, start, end, x, y, width, source = value.split("\t")
        rows.append({"block": block, "page": int(page), "start": int(start), "end": int(end), "x": float(x),
                     "baseline": float(y), "width": float(width), "source": base64.b64decode(source).decode("utf-8")})
    return rows


def check_simple(reader):
    actual = glyph_advances(reader)
    errors = []
    for block in ("simple-cjk", "simple-latin"):
        source = (FIXTURES / f"{block}.txt").read_text(encoding="utf-8")
        lines = [row for row in manifest("typography") if row["block"] == block]
        require(lines[0]["start"] == 0 and lines[-1]["end"] == len(source), "source endpoint differs")
        for index, row in enumerate(lines):
            if index:
                require(row["start"] == lines[index - 1]["end"], "line source gap or repetition")
            runs = [run for run in actual if run["page"] == row["page"] and abs(run["baseline"] - row["baseline"]) < .003]
            require(runs, "final PDF is missing a measured glyph line")
            left, right = min(run["x"] for run in runs), max(run["right"] for run in runs)
            error = max(abs(left - row["x"]), abs(right - left - row["width"]))
            require(error < .02, f"CID/TJ advance differs from layout: {error}")
            errors.append(error)
            if index + 1 < len(lines):
                require(abs(right - 384) < .02, "soft-wrapped line does not reach the right content edge")
                boundary = row["end"]
                require(source[boundary - 1] not in NO_END and source[boundary] not in NO_START, "strict punctuation prohibition violated")
                require(not (source[boundary - 1] == source[boundary] and source[boundary] in "…—"), "paired punctuation split")
            else:
                require(row["width"] < 348, "final line was stretched")
    return max(errors)


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    report, contacts = {"tools": {name: metadata.version(name) for name in ("pypdf", "pypdfium2", "Pillow", "python-docx")}}, []
    for name, pages in (("quickstart", 1), ("typography", 3), ("imported", 3), ("component", 1)):
        path = FIXTURES / f"{name}.pdf"
        reader = PdfReader(path)
        require(len(reader.pages) == pages, f"{name}: unexpected page count")
        outlines = actual_text(reader)
        if name in ("typography", "imported"):
            require(len(image_boxes(reader)) == 1, "inline image lost or repeated")
            links = [ref.get_object() for page in reader.pages for ref in page.get("/Annots", []) if ref.get_object().get("/Subtype") == "/Link"]
            require(len(links) == 5 and all(link["/A"]["/S"] == "/URI" for link in links), "rich semantic hyperlinks lost")
            require(any("PAGE=2" in text for text in outlines), "inline page value no longer follows its final line")
            anchors = pdf_geometry(reader, 24)[1]
            expected = [row for row in manifest(name) if row["source"]]
            require([row["source"] for row in expected] == [line["source"] for line in anchors], "outline source order differs")
            for row, line in zip(expected, anchors, strict=True):
                require(row["page"] == line["page"] and abs(row["x"] - line["x"]) < .003
                        and abs(row["width"] - line["width"]) < .003, "final outline placement or width differs")
        elif name == "component":
            source = (FIXTURES / "component.txt").read_text(encoding="utf-8")
            require("".join(outlines) == source.replace("\n", ""), "component logical text lost or reordered")
            anchors = pdf_geometry(reader, 24)[1]
            require(any(abs(line["width"] - 348) < .003 for line in anchors), "component justification missing")
            require(anchors[-1]["width"] < 348, "component final line was stretched")
        svg = ET.parse(FIXTURES / f"{name}.svg").getroot()
        require(len(svg.findall("{http://www.w3.org/2000/svg}svg")) == pages, "static preview page count differs")
        poppler = render_poppler(path, OUTPUT / name / "poppler")
        pdfium = render_pdfium(path, OUTPUT / name / "pdfium")
        deltas = [difference(a, b, 1) for a, b in zip(poppler, pdfium, strict=True)]
        require(all(delta["meanAbsoluteChannelError"] < 3 and delta["changedPixelFraction"] < .03 for delta in deltas), "independent renderers disagree")
        contacts.extend((f"{name} page {index + 1}", page) for index, page in enumerate(poppler))
        report[name] = {"pages": pages, "twoRendererDifferences": deltas}
    report["maxFinalGlyphAdvanceErrorPoints"] = check_simple(PdfReader(FIXTURES / "typography.pdf"))
    roundtrip = [difference(OUTPUT / "typography/poppler" / f"page-{index}.png", OUTPUT / "imported/poppler" / f"page-{index}.png") for index in range(1, 4)]
    require(all(delta["meanAbsoluteChannelError"] == 0 for delta in roundtrip), "this fixture's DOCX roundtrip changed its appearance")
    report["fixtureRoundtripDifferences"] = roundtrip
    with ZipFile(FIXTURES / "typography.docx") as archive:
        root = ET.fromstring(archive.read("word/document.xml"))
        w = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
        require(any(jc.get(w + "val") == "both" for jc in root.iter(w + "jc")), "native Word justification missing")
        require(len(list(root.iter(w + "kinsoku"))) >= 4, "explicit strict-policy roundtrip markers missing")
        require(len(list(root.iter(w + "hyperlink"))) >= 4, "Word rich semantic links missing")
        require(len([name for name in archive.namelist() if name.startswith("word/media/")]) == 1, "Word image embedding differs")
    consumer = Path((ROOT / "build/verification/consumer-build-directory.txt").read_text(encoding="utf-8").strip()) / "output"
    consumer_pages = []
    for name in ("consumer-authoring", "consumer-authoring-sections"):
        path = consumer / f"{name}.pdf"
        reader = PdfReader(path)
        require(len(reader.pages) == 1 and len(reader.pages[0].get("/Annots", [])) == 3, "Maven consumer authoring output differs")
        require((reader.pages[0].extract_text() or "").count("CONSUMER-AUTHORING") == 3, "Maven consumer lost rich semantic text")
        poppler = render_poppler(path, OUTPUT / name / "poppler")
        pdfium = render_pdfium(path, OUTPUT / name / "pdfium")
        require(difference(poppler[0], pdfium[0], 1)["meanAbsoluteChannelError"] < 3, "consumer independent renderers disagree")
        consumer_pages.append(poppler[0])
        contacts.append((name, poppler[0]))
    require(difference(*consumer_pages)["meanAbsoluteChannelError"] == 0, "default sectioned output changed the consumer fixture")
    report["consumer"] = {"buildDirectory": str(consumer.parent), "pages": 2, "sourceAndLinks": "passed"}
    contact_sheet(contacts, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
