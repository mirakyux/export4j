# SPDX-License-Identifier: Apache-2.0
"""Independently check finite rich streaming PDFs, physical fonts, styles and source ownership.

Every small fixture page is checked and rendered by two independent engines. Capacity is
measured separately by measure_streaming_table.ps1; this verifier makes no memory claim.
"""
from __future__ import annotations

import argparse
import hashlib
from importlib import metadata
import json
from pathlib import Path
import re
import struct
import xml.etree.ElementTree as ET

from pypdf import PdfReader
from verify_adr_form import render
from verify_advanced_pdf import difference, require
from verify_authoring import glyph_advances
from verify_continuous_columns import font_ascent
from verify_multi_floats import compose, pdf_geometry, point
from verify_production import actual_text, contact_sheet
from verify_semantic_tables import close, table_borders
from verify_streaming_table import final_geometry

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "examples/build/streaming-rich-text"
OUTPUT = ROOT / "build/verification/streaming-rich-text"
SVG = "{http://www.w3.org/2000/svg}"
BLUE, RED, BLACK = (34 / 255, 68 / 255, 136 / 255), (136 / 255, 68 / 255, 34 / 255), (0, 0, 0)
WORDS = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu xi omicron pi rho sigma tau "


def source(name, index):
    number = f"{index:02d}"
    if name == "styled": return f"WRAP-{number} {WORDS}ITALIC\nH2O x2 OLD END-{number}"
    return f"AR-{number} العربية لا 123\nDEV-{number} नमस्ते\nMISS-{number} 😀 END"


def same_color(actual, expected):
    return all(abs(a - b) < .00001 for a, b in zip(actual, expected, strict=True))


def painted_spans(reader):
    """Decode final text while capturing the actual text-show state before pypdf flushes it."""
    result = []
    for page_index, page in enumerate(reader.pages):
        color, stack, show, size, name = BLACK, [], None, 0, ""
        def before(operator, operands, cm, tm):
            nonlocal color, show, size, name
            if operator == b"q": stack.append(color)
            elif operator == b"Q": color = stack.pop()
            elif operator == b"rg": color = tuple(map(float, operands))
            elif operator == b"g": color = (float(operands[0]),) * 3
            elif operator == b"Tf":
                size = float(operands[1])
                name = str(page["/Resources"]["/Font"][operands[0]].get_object()["/BaseFont"])
            elif operator in (b"TJ", b"Tj"): show = (tuple(cm), tuple(tm), size, color, name)
        def text(value, _cm, _tm, _font, _size):
            if not value.strip() or show is None or show[2] <= 1: return
            matrix, tm, font_size, ink, font_name = show
            x, y = point(matrix, tm[4], tm[5])
            result.append({"page": page_index, "text": value.strip(), "size": font_size, "color": ink,
                           "font": font_name, "x": x, "baseline": float(page.mediabox.height) - y})
        page.extract_text(visitor_operand_before=before, visitor_text=text)
        require(not stack, "painted text scopes are unbalanced")
    advances = glyph_advances(reader)
    for span in result:
        candidates = [run for run in advances if run["page"] == span["page"]
                      and abs(run["x"] - span["x"]) < .003 and abs(run["baseline"] - span["baseline"]) < .003]
        require(len(candidates) == 1, "painted text has no unique independently decoded CID advance")
        span["right"] = candidates[0]["right"]
    return result


def strokes(reader):
    result = []
    for page_index, page in enumerate(reader.pages):
        matrix, color, width, stack, vertices = (1, 0, 0, 1, 0, 0), BLACK, 1, [], []
        for values, operator in page.get_contents().operations:
            if operator == b"q": stack.append((matrix, color, width))
            elif operator == b"Q": matrix, color, width = stack.pop()
            elif operator == b"cm": matrix = compose(matrix, tuple(map(float, values)))
            elif operator == b"RG": color = tuple(map(float, values))
            elif operator == b"G": color = (float(values[0]),) * 3
            elif operator == b"w": width = float(values[0])
            elif operator == b"m": vertices = [point(matrix, *map(float, values))]
            elif operator == b"l": vertices.append(point(matrix, *map(float, values)))
            elif operator == b"S":
                if len(vertices) == 2:
                    (x, y), (right, other_y) = vertices
                    close(y, other_y, "decoration is not horizontal")
                    result.append({"page": page_index, "x": x, "right": right,
                                   "y": float(page.mediabox.height) - y, "width": width, "color": color})
                vertices = []
            elif operator in (b"n", b"f", b"f*", b"B", b"B*"): vertices = []
        require(not stack, "decoration graphics scopes are unbalanced")
    return result


def outlined_paths(reader):
    """Capture filled glyph outlines independently of nonpainting logical anchors."""
    result = []
    for page_index, page in enumerate(reader.pages):
        matrix, color, stack, vertices = (1, 0, 0, 1, 0, 0), BLACK, [], []
        for values, operator in page.get_contents().operations:
            if operator == b"q": stack.append((matrix, color))
            elif operator == b"Q": matrix, color = stack.pop()
            elif operator == b"cm": matrix = compose(matrix, tuple(map(float, values)))
            elif operator == b"rg": color = tuple(map(float, values))
            elif operator == b"g": color = (float(values[0]),) * 3
            elif operator in (b"m", b"l", b"c"):
                coordinates = list(map(float, values))
                vertices.extend(point(matrix, coordinates[index], coordinates[index + 1])
                                for index in range(0, len(coordinates), 2))
            elif operator in (b"f", b"f*"):
                if vertices:
                    xs, ys = zip(*vertices, strict=True)
                    result.append({"page": page_index, "color": color, "left": min(xs), "right": max(xs),
                                   "top": float(page.mediabox.height) - max(ys),
                                   "bottom": float(page.mediabox.height) - min(ys)})
                vertices = []
            elif operator in (b"S", b"n", b"B", b"B*"): vertices = []
        require(not stack, "outlined glyph graphics scopes are unbalanced")
    return result


def decoration(span, lines, offset):
    values = [line for line in lines if line["page"] == span["page"] and abs(line["x"] - span["x"]) < .003
              and abs(line["right"] - span["right"]) < .02 and abs(line["y"] - span["baseline"] - offset) < .003
              and same_color(line["color"], span["color"])]
    require(len(values) == 1, "final underline/strike does not follow its exact painted run")
    close(values[0]["width"], max(.3, span["size"] / 18), "decoration thickness differs")


def physical_fonts(reader, expected):
    found = {}
    for page in reader.pages:
        for reference in page["/Resources"]["/Font"].values():
            font = reference.get_object()
            if font.get("/Subtype") != "/Type0": continue
            descendant = font["/DescendantFonts"][0].get_object()
            descriptor = descendant["/FontDescriptor"]
            require("/FontFile2" in descriptor and "/ToUnicode" in font, "physical font lacks embedded TrueType/Unicode data")
            data = descriptor["/FontFile2"].get_data()
            tables = {struct.unpack_from(">4sIII", data, 12 + index * 16)[0]: struct.unpack_from(">4sIII", data, 12 + index * 16)[2]
                      for index in range(struct.unpack_from(">H", data, 4)[0])}
            # PDFBox strips optional OS/2 from these embedded subsets. Validate the
            # actual font program through head.macStyle, with descriptor weight as
            # independent metadata, rather than assuming full source tables survive.
            style = struct.unpack_from(">H", data, tables[b"head"] + 44)[0]
            found[str(font["/BaseFont"]).split("+")[-1]] = {
                "weight": float(descriptor["/FontWeight"]), "bold": bool(style & 1),
                "italic": bool(style & 2), "embeddedBytes": len(data)}
    require(set(found) == expected, "final physical font set differs")
    for name, value in found.items():
        require(value["weight"] == (700 if name.endswith("Bold") else 400)
                and value["bold"] == name.endswith("Bold")
                and value["italic"] == name.endswith("Italic"), "final font program is not the declared physical variant")
    return found


def geometry(reader, per_page, header_height, body_height, columns):
    borders = table_borders(reader)
    wanted = []
    for page_index, count in enumerate(per_page):
        top = 20
        for height in [header_height] + [body_height] * count:
            left = 20
            for width in columns:
                wanted.append((page_index, left, top, width, height)); left += width
            top += height
    require(len(borders) == len(wanted), "rich table border count differs")
    for actual, (page, x, y, width, height) in zip(borders, wanted, strict=True):
        require(actual["pageIndex"] == page, "rich table row page differs")
        for key, value in {"x": x, "y": y, "width": width, "height": height}.items(): close(actual[key], value, "rich cell bounds differ: " + key)
    return borders


def check_fixture(name):
    path = FIXTURES / f"{name}.pdf"
    reader = PdfReader(path, strict=True)
    per_page, step, rows = ([3, 3, 1], 18, 7) if name == "styled" else ([2], 24, 2)
    require(len(reader.pages) == len(per_page) and not reader.get_fields() and not reader.outline
            and not any(page.get("/Annots") for page in reader.pages), "pure rich fixture page/interaction contract differs")
    for page in reader.pages:
        close(page.mediabox.width, 420, "rich page width differs"); close(page.mediabox.height, 250, "rich page height differs")
    borders = geometry(reader, per_page, step + 6, step * 3 + 6, (90, 290))
    first = 1
    svg_sources = []
    for page_index, count in enumerate(per_page):
        svg = ET.parse(FIXTURES / f"{name}-page-{page_index + 1}.svg").getroot()
        logical = "".join(group.get("aria-label", "") for group in svg.iter(SVG + "g"))
        wanted = "ROW-ID" + ("STYLED CONTENT" if name == "styled" else "COMPLEX CONTENT")
        # This fixture soft-wraps at the space after "iota". That boundary space is
        # retained in the original source, but is intentionally not painted on either line.
        wanted += "".join(f"ROW-{index:02d}" + (source(name, index).replace("\n", "").replace("iota kappa", "iotakappa")
                         if name == "styled" else source(name, index).replace("\n", "")) for index in range(first, first + count))
        require(logical == wanted, "per-page SVG logical source was lost/reordered at a soft wrap or role boundary")
        text = "".join((reader.pages[page_index].extract_text() or "").split())
        require(re.findall(r"ROW-(\d{2})", text) == [f"{index:02d}" for index in range(first, first + count)], "rich row source order differs")
        if name == "styled":
            for index in range(first, first + count):
                require(source(name, index).replace(" ", "").replace("\n", "") in text, "natural wrapping lost styled source text")
        svg_sources.append(logical); first += count
    summary = json.loads((FIXTURES / f"{name}-results.json").read_text(encoding="utf-8"))
    bound = json.loads((FIXTURES / f"{name}-sources.json").read_text(encoding="utf-8"))["rows"]
    require(bound == [{"row": index, "texts": [f"ROW-{index:02d}", source(name, index)],
                       "utf16Length": len(source(name, index).encode("utf-16-le")) // 2} for index in range(1, rows + 1)],
            "rich source UTF-16 snapshots differ")
    points = len("ROW-ID" + ("STYLED CONTENT" if name == "styled" else "COMPLEX CONTENT"))
    points += sum(len(f"ROW-{index:02d}") + len(source(name, index)) for index in range(1, rows + 1))
    expected = {"pages": len(per_page), "bodyRows": rows, "sourceCells": 2 * (rows + 1), "sourceCodePoints": points,
                "sourceInlineRuns": 2 + rows * (10 if name == "styled" else 7), "outputLines": rows * 4 + len(per_page) * 2,
                "opens": 1, "closes": 1, "nextCalls": rows + 1, "nextCallsAtPages": [4, 7, 8] if name == "styled" else [3],
                "pendingAtPages": [1, 1, 0] if name == "styled" else [0], "finished": 1, "writerCloses": 1,
                "outputBytes": path.stat().st_size}
    for key, value in expected.items(): require(summary[key] == value, "rich summary/ownership differs: " + key)
    spans, lines = painted_spans(reader), strokes(reader)
    if name == "styled":
        require(not actual_text(reader), "ordinary rich fixture unexpectedly became outlined text")
        require(len(lines) == 17, "header/body decoration count differs")
        for span in spans:
            token = span["text"]
            if token in ("ROW-ID", "STYLED CONTENT"):
                require(span["font"].endswith("LiberationSans-Bold" if token == "ROW-ID" else "LiberationSans-Italic"), "header font role differs")
                require(same_color(span["color"], BLUE if token == "ROW-ID" else RED), "header role color differs")
                if token == "STYLED CONTENT": decoration(span, lines, 1)
        for index in range(1, rows + 1):
            page = (index - 1) // 3
            local = [span for span in spans if span["page"] == page]
            wrap = next(span for span in local if span["text"] == f"WRAP-{index:02d}")
            ascent = font_ascent(ROOT / "assets/fonts/LiberationSans-Regular.ttf")
            expected_baseline = 47 + ((index - 1) % 3) * 60 + max(10 * ascent, 8 * ascent + 3)
            close(wrap["baseline"], expected_baseline, "first naturally wrapped baseline differs")
            close(wrap["x"], 113, "first naturally wrapped x differs")
            following = next(span for span in local if span["text"] == "kappa lambda mu nu xi omicron pi rho sigma tau"
                             and abs(span["baseline"] - expected_baseline - 18) < .003)
            close(following["x"], 113, "soft continuation does not return to the content edge")
            end = next(span for span in local if span["text"] == f"END-{index:02d}")
            require(end["font"].endswith("LiberationSans-Bold") and same_color(end["color"], BLUE), "body bold physical role/color differs")
            decoration(end, lines, 1)
            same_row = [span for span in local if abs(span["baseline"] - end["baseline"]) < .003]
            old = next(span for span in same_row if span["text"] == "OLD")
            require(old["font"].endswith("LiberationSans-Italic") and same_color(old["color"], RED), "body italic physical role/color differs")
            decoration(old, lines, -3)
            shifts = [span for span in local if span["text"] == "2" and abs(span["baseline"] - end["baseline"]) <= 3.003]
            require(len(shifts) == 2, "subscript/superscript glyphs were merged/lost")
            require(sorted(round(span["baseline"] - end["baseline"], 3) for span in shifts) == [-3, 2]
                    and all(span["size"] == 8 for span in shifts), "explicit baseline shifts/point sizes differ")
        fonts = physical_fonts(reader, {"LiberationSans", "LiberationSans-Bold", "LiberationSans-Italic"})
        require(summary["diagnostics"] == [], "strict Latin rich fixture has unexpected diagnostics")
    else:
        expected_logical = [line for index in range(1, 3) for line in source(name, index).splitlines()]
        require(actual_text(reader) == expected_logical, "complex source/emoji was changed to placeholder in logical text")
        anchors = pdf_geometry(reader, 24)[1]
        require(len(anchors) == 6, "complex complete-line anchor count differs")
        for index, anchor in enumerate(anchors):
            close(anchor["x"], 113, "complex line x differs")
            close(anchor["y"], 53 + (index // 3) * 78 + (index % 3) * 24, "complex line y differs")
        paths = outlined_paths(reader)
        require(len(paths) == 14, "complex painted outline count differs")
        require(all(same_color(path["color"], color)
                    for path, color in zip(paths, [BLACK, BLUE, BLUE, BLACK, RED, BLACK, BLACK] * 2, strict=True)),
                "complex physical glyph outline colors differ from their explicit roles")
        for row in range(2):
            for index, glyph in enumerate(paths[row * 7:(row + 1) * 7]):
                top = 53 + row * 78 + (0 if index < 3 else 24 if index < 5 else 48)
                require(glyph["page"] == 0 and 113 <= glyph["left"] <= glyph["right"] <= 397
                        and top <= glyph["top"] <= glyph["bottom"] <= top + 24,
                        "complex painted glyph outline leaves its reserved line/cell")
        regular_hash = hashlib.sha256((ROOT / "assets/fonts/LiberationSans-Regular.ttf").read_bytes()).hexdigest()
        require(len(summary["diagnostics"]) == 2, "complex emoji warning count differs")
        for index, value in enumerate(summary["diagnostics"], 1):
            original = source(name, index)
            offset = len(original[:original.index("😀")].encode("utf-16-le")) // 2
            require(value == {"row": index, "column": 1, "page": 0, "region": "STREAMING_TABLE", "start": offset,
                              "end": offset + 2, "sourceCodePoint": 0x1f600, "replacementCodePoint": ord("#"), "fontId": regular_hash},
                    "missing-glyph warning lost original UTF-16/selected role font")
        require(len(lines) == 1, "complex repeated header decoration count differs")
        fonts = physical_fonts(reader, {"LiberationSans-Bold", "LiberationSans-Italic"})
    return {"pages": len(reader.pages), "bodyRowsPerPage": per_page, "paintedBorders": len(borders), "physicalFonts": fonts,
            "outlinedBodyPaths": len(paths) if name == "complex" else 0,
            "characters": final_geometry(path, borders, margin=20), "summary": summary, "svgSources": svg_sources}


def check_consumer(directory):
    path = directory / "output/consumer-streaming-rich-text.pdf"
    reader = PdfReader(path, strict=True)
    require(len(reader.pages) == 2 and not reader.get_fields() and not any(page.get("/Annots") for page in reader.pages), "rich caller page/interaction count differs")
    borders = geometry(reader, [4, 4], 24, 24, (80, 200))
    spans, lines = painted_spans(reader), strokes(reader)
    require(len(lines) == 12, "caller rich decorations were lost/repeated")
    for page_index, page in enumerate(reader.pages):
        close(page.mediabox.width, 320, "caller width differs"); close(page.mediabox.height, 180, "caller height differs")
        text = "".join((page.extract_text() or "").split())
        require(text.startswith("SR-IDSR-STATE"), "caller rich header differs")
        for index in range(page_index * 4 + 1, page_index * 4 + 5):
            require(text.count(f"SR{index:02d}") == 2 and f"STATESR{index:02d}OK" in text, "caller body lost/repeated or reordered")
        for span in [span for span in spans if span["page"] == page_index and span["font"].endswith("LiberationSans-Bold")]:
            require(span["size"] == 10 and same_color(span["color"], BLUE), "caller true bold point size/color differs")
            decoration(span, lines, 1)
    fonts = physical_fonts(reader, {"LiberationSans", "LiberationSans-Bold"})
    return {"pages": 2, "bodyRowsPerPage": [4, 4], "paintedBorders": len(borders), "physicalFonts": fonts,
            "characters": final_geometry(path, borders, margin=20)}


def render_checked(path, name, value, contacts):
    first, second = render(path, OUTPUT / name)
    require(len(first) == len(second) == value["pages"], "rich renderer omitted a complete page")
    metrics = [difference(a, b, 2) for a, b in zip(first, second, strict=True)]
    require(all(metric["meanAbsoluteChannelError"] < 4 and metric["changedPixelFraction"] < .06 for metric in metrics), "rich independent renderers disagree")
    value["rendererDifferences"] = metrics
    contacts.extend((f"{name} page {index + 1}", page) for index, page in enumerate(first))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--consumer-build-directory", type=Path)
    args = parser.parse_args()
    versions = {name: metadata.version(name) for name in ("pypdf", "pypdfium2", "Pillow")}
    require(versions == {"pypdf": "6.10.0", "pypdfium2": "5.13.0", "Pillow": "12.3.0"}, "use the pinned verification environment")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    report, contacts = {"tools": versions}, []
    font_sources = json.loads((FIXTURES / "fonts.json").read_text(encoding="utf-8"))["fonts"]
    require(len(font_sources) == 5, "explicit runtime font manifest differs")
    for font in font_sources:
        data = (ROOT / "assets/fonts" / font["resource"]).read_bytes()
        require(font["sha256"] == hashlib.sha256(data).hexdigest() and font["bytes"] == len(data), "actual runtime font differs from explicit resource")
    report["fontSources"] = font_sources
    for name in ("styled", "complex"):
        report[name] = check_fixture(name)
        render_checked(FIXTURES / f"{name}.pdf", name, report[name], contacts)
    directory = args.consumer_build_directory
    if directory is None: directory = Path((ROOT / "build/verification/consumer-build-directory.txt").read_text(encoding="utf-8").strip())
    if not directory.is_absolute(): directory = ROOT / directory
    report["consumerBuildDirectory"] = str(directory)
    report["consumer"] = check_consumer(directory)
    render_checked(directory / "output/consumer-streaming-rich-text.pdf", "consumer", report["consumer"], contacts)
    require(len(contacts) == 6, "small rich fixture complete page count differs")
    contact_sheet(contacts, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"pages": len(contacts), "richStreaming": "passed", "physicalFontsAndStyles": "passed"}, indent=2))


if __name__ == "__main__":
    main()
