# SPDX-License-Identifier: Apache-2.0
"""Independently verify the finite Latin rich-text/font-scope fixtures in actual output.

These fixtures use one simple TrueType glyph per ASCII character. Their glyph/contour
count assertions are not a general complex-script or grapheme-cluster rule.
"""
from __future__ import annotations

import argparse
from importlib import metadata
import json
from pathlib import Path
import re
import struct
import xml.etree.ElementTree as ET

from pypdf import PdfReader
from pypdf.generic import TextStringObject
from verify_adr_form import render
from verify_advanced_pdf import difference, require
from verify_authoring import cid_widths
from verify_continuous_columns import font_ascent
from verify_multi_floats import compose, point
from verify_production import contact_sheet
from verify_streaming_rich_text import decoration, painted_spans, physical_fonts, same_color, strokes

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build/verification/component-text"
SVG = "{http://www.w3.org/2000/svg}"
BLUE, PURPLE, BLACK = (34 / 255, 68 / 255, 136 / 255), (136 / 255, 51 / 255, 102 / 255), (0, 0, 0)


def close(actual, wanted, message, tolerance=.003):
    require(abs(actual - wanted) <= tolerance, f"{message}: {actual} versus {wanted}")


def run(spans, page, text, variant, size, color, x=None, baseline=None):
    found = [value for value in spans if value["page"] == page and value["text"] == text]
    require(len(found) == 1, "missing or repeated painted run: " + text)
    value = found[0]
    require(value["font"].endswith("+LiberationSans" + variant), "physical font differs: " + text)
    close(value["size"], size, "run point size")
    require(same_color(value["color"], color), "run RGB differs: " + text)
    if x is not None: close(value["x"], x, "run x")
    if baseline is not None: close(value["baseline"], baseline, "run baseline")
    return value


def expected_sources(page, caller):
    if caller:
        return ["CALLER-REGULAR", "CALLER-DEFAULT-ROLE ", "ITALIC", "CALLER-HEADER ", "DETAIL", "PAGE 1 OF 1"]
    body = (["BODY-FIRST Regular document text", "ROLE-DEFAULT-BOLD", "RICH-DEFAULT-BOLD ", "RICH-EXPLICIT-ITALIC"]
            if page == 0 else ["BODY-SECOND Regular document text", "ROLE-FIXED-BOLD"])
    return ["BRAND", *body, "REPORT ", "DETAIL", " H", "2", "O", "KEY ", "UNDERLINE",
            f"PAGE {page + 1} OF 2", "STATUS ", "REVIEWED"]


def font_geometry(font):
    """Read actual embedded simple-glyph contours/bounds without Java or an extra dependency."""
    descendant = font["/DescendantFonts"][0].get_object()
    data = descendant["/FontDescriptor"]["/FontFile2"].get_data()
    tables = {struct.unpack_from(">4sIII", data, 12 + index * 16)[0]: struct.unpack_from(">4sIII", data, 12 + index * 16)[2]
              for index in range(struct.unpack_from(">H", data, 4)[0])}
    units = struct.unpack_from(">H", data, tables[b"head"] + 18)[0]
    long_locations = struct.unpack_from(">h", data, tables[b"head"] + 50)[0]
    require(long_locations in (0, 1), "unexpected embedded glyph location format")
    mapping = descendant.get("/CIDToGIDMap", "/Identity")
    mapping = None if mapping == "/Identity" else mapping.get_object().get_data()

    def glyph(cid):
        gid = cid if mapping is None else struct.unpack_from(">H", mapping, cid * 2)[0]
        offset = tables[b"loca"] + gid * (4 if long_locations else 2)
        start, end = struct.unpack_from(">II" if long_locations else ">HH", data, offset)
        if not long_locations: start, end = start * 2, end * 2
        if start == end: return 0, (0, 0, 0, 0), units
        contours, *bounds = struct.unpack_from(">hhhhh", data, tables[b"glyf"] + start)
        require(contours >= 0, "fixture unexpectedly uses a composite/complex glyph")
        return contours, bounds, units
    return glyph


def attach_glyph_geometry(reader, spans, caller):
    for page_index, page in enumerate(reader.pages):
        raw = []
        def text(value, _cm, _tm, _font, _size):
            if value.strip(): raw.append(value)
        page.extract_text(visitor_text=text)
        require(raw == expected_sources(page_index, caller), "exact PDF source order/whitespace differs")
        selected = [value for value in spans if value["page"] == page_index]
        require(len(selected) == len(raw), "PDF painted run count differs from complete source")
        for span, source in zip(selected, raw, strict=True):
            require(span["text"] == source.strip() and all(ord(char) < 128 for char in source),
                    "fixture source is outside its explicit simple Latin scope")
            span["source"] = source
        font, size, cursor = None, 0, 0
        fonts = {}
        for operands, operator in page.get_contents().operations:
            if operator == b"Tf":
                name, size = operands[0], float(operands[1])
                if name not in fonts:
                    font = page["/Resources"]["/Font"][name].get_object()
                    fonts[name] = (*cid_widths(font), font_geometry(font))
                font = fonts[name]
            elif operator == b"TJ":
                require(cursor < len(selected), "unexpected extra PDF text-show operation")
                span, glyphs, advance = selected[cursor], [], 0.0
                widths, default, geometry = font
                cids = []
                for item in operands[0]:
                    if isinstance(item, (str, bytes)):
                        data = item.original_bytes if isinstance(item, TextStringObject) else bytes(item)
                        require(len(data) % 2 == 0, "fixture CID string has odd byte count")
                        cids.extend(int.from_bytes(data[index:index + 2], "big") for index in range(0, len(data), 2))
                    else:
                        close(float(item), 0, "simple Latin fixture gained a spacing adjustment", tolerance=0)
                require(len(cids) == len(span["source"]), "one-glyph-per-character Latin fixture changed")
                for char, cid in zip(span["source"], cids, strict=True):
                    contours, bounds, units = geometry(cid)
                    require((contours == 0) == char.isspace(), "Latin glyph visibility/count differs")
                    x0, y0, x1, y1 = bounds
                    glyphs.append({"char": char, "contours": contours,
                                   "bounds": (span["x"] + advance + x0 * size / units, span["baseline"] - y1 * size / units,
                                              span["x"] + advance + x1 * size / units, span["baseline"] - y0 * size / units)})
                    advance += widths.get(cid, default) * size / 1000
                close(span["x"] + advance, span["right"], "independent glyph advance")
                span["glyphs"] = glyphs
                cursor += 1
        require(cursor == len(selected), "fixture text-show operations do not match complete runs")


def check(path, caller):
    reader = PdfReader(path, strict=True)
    pages, width, height, left = (1, 360, 240, 18) if caller else (2, 400, 380, 20)
    require(len(reader.pages) == pages, "rich component pagination differs")
    fonts = physical_fonts(reader, {"LiberationSans", "LiberationSans-Bold", "LiberationSans-Italic"})
    require(not reader.trailer["/Root"].get("/AcroForm"), "pure component fixture gained a form tree")
    spans, lines = painted_spans(reader), strokes(reader)
    ascent = font_ascent(ROOT / "assets/fonts/LiberationSans-Regular.ttf")
    for index, page in enumerate(reader.pages):
        close(float(page.mediabox.width), width, "page width")
        close(float(page.mediabox.height), height, "page height")
        require(list(map(float, page.mediabox)) == [0, 0, width, height]
                and list(map(float, page.cropbox)) == [0, 0, width, height], "rich PDF page origin/crop differs")
        require(float(page.get("/UserUnit", 1)) == 1, "rich PDF page unit differs")
        require(not page.get("/Annots") and page.get("/Rotate", 0) == 0, "pure component fixture gained interactivity/rotation")
        mode, stack = 0, []
        for operands, operator in page.get_contents().operations:
            require(operator in {b"BT", b"ET", b"Q", b"RG", b"S", b"TJ", b"Tf", b"Tm", b"cm", b"l", b"m", b"q", b"rg", b"w", b"Tr"},
                    "rich PDF gained clipping, indirect or unexpected painting")
            if operator == b"q": stack.append(mode)
            elif operator == b"Q": mode = stack.pop()
            elif operator == b"Tr": mode = int(operands[0])
            elif operator in (b"TJ", b"Tj"): require(mode == 0, "rich component text became invisible")
            elif operator == b"gs": raise AssertionError("rich fixture gained indirect paint state")
            elif operator == b"cm":
                require(tuple(map(float, operands[:4])) in ((1, 0, 0, 1), (1, 0, 0, -1)),
                        "rich PDF gained a scaling/rotation transform")
            elif operator == b"Tm":
                require(tuple(map(float, operands[:4])) == (1, 0, 0, -1), "rich PDF text matrix changed")
        require(not stack, "rich fixture graphics state unbalanced")
        footer_y = 202 if caller else 336
        run(spans, index, f"PAGE {index + 1} OF {pages}", "-Bold", 10, BLACK, left, footer_y + 10 * ascent)
    if caller:
        run(spans, 0, "CALLER-HEADER", "-Bold", 10, BLUE, 18, 18 + 10 * ascent)
        header = run(spans, 0, "CALLER-HEADER", "-Bold", 10, BLUE)
        run(spans, 0, "DETAIL", "-Italic", 10, PURPLE, x=header["right"], baseline=18 + 10 * ascent)
        run(spans, 0, "CALLER-REGULAR", "", 10, BLACK, 18, 48 + 10 * ascent)
        first = run(spans, 0, "CALLER-DEFAULT-ROLE", "-Bold", 10, BLUE, 18, 72 + 10 * ascent)
        run(spans, 0, "ITALIC", "-Italic", 10, PURPLE, x=first["right"], baseline=72 + 10 * ascent)
        require(not lines, "caller fixture gained unexpected decoration")
    else:
        bold_ascent = font_ascent(ROOT / "assets/fonts/LiberationSans-Bold.ttf")
        baseline = 20 + 14 * bold_ascent
        for index in range(2):
            report = run(spans, index, "REPORT", "-Bold", 14, BLUE, 20, baseline)
            detail = run(spans, index, "DETAIL", "-Italic", 10, PURPLE, report["right"], baseline)
            hydrogen = run(spans, index, "H", "", 10, BLACK, detail["right"], baseline)
            subscript = run(spans, index, "2", "", 8, BLACK, hydrogen["right"], baseline + 3)
            run(spans, index, "O", "", 10, BLACK, subscript["right"], baseline)
            key = run(spans, index, "KEY", "", 10, BLACK, 20, baseline + 20)
            under = run(spans, index, "UNDERLINE", "-Bold", 10, (136 / 255, 68 / 255, 34 / 255), key["right"], baseline + 20)
            decoration(under, lines, 1)
            run(spans, index, "BRAND", "-Bold", 10, BLACK, 20, 225 + 10 * ascent)
            status = run(spans, index, "STATUS", "-Bold", 10, BLUE, 20, 288 + 10 * ascent)
            old = run(spans, index, "REVIEWED", "-Italic", 10, (51 / 255, 102 / 255, 51 / 255), status["right"], 288 + 10 * ascent)
            decoration(old, lines, -3)
        require(len(lines) == 4, "rich page-region decoration count differs")
        run(spans, 0, "BODY-FIRST Regular document text", "", 10, BLACK, 20, 74 + 10 * ascent)
        run(spans, 1, "BODY-SECOND Regular document text", "", 10, BLACK, 20, 74 + 10 * ascent)
        run(spans, 0, "ROLE-DEFAULT-BOLD", "-Bold", 10, BLACK, 20, 98 + 10 * ascent)
        run(spans, 1, "ROLE-FIXED-BOLD", "-Bold", 10, BLACK, 20, 98 + 10 * ascent)
        default = run(spans, 0, "RICH-DEFAULT-BOLD", "-Bold", 10, BLUE, 20, 116 + 10 * ascent)
        run(spans, 0, "RICH-EXPLICIT-ITALIC", "-Italic", 10, PURPLE, default["right"], 116 + 10 * ascent)
    attach_glyph_geometry(reader, spans, caller)
    for value in spans:
        require(left - .003 <= value["x"] <= value["right"] <= width - left + .003,
                "rich text actual advance leaves declared page width")
    check_svg(path.with_suffix(".svg"), reader, spans, width, height)
    return {"pages": pages, "physicalFonts": fonts, "paintedRuns": len(spans), "decorations": len(lines)}


def path_contours(data, allow_open=False):
    number = r"[-+]?(?:\d*\.\d+|\d+\.?\d*)(?:[eE][-+]?\d+)?"
    require(re.fullmatch(r"(?:[MLCZ]|" + number + r"|\s)+", data or "") is not None, "unexpected SVG path command")
    tokens = re.findall(r"[MLCZ]|" + number, data)
    result, current, index = [], [], 0
    while index < len(tokens):
        command = tokens[index]
        require(command in ("M", "L", "C", "Z"), "SVG path lacks an explicit command")
        index += 1
        if command == "Z":
            require(current, "SVG path closes an empty contour")
            result.append(current)
            current = []
            continue
        require((command == "M") == (not current), "SVG contour is unclosed or lacks its move")
        count = 6 if command == "C" else 2
        values = tokens[index:index + count]
        require(len(values) == count and all(value not in ("M", "L", "C", "Z") for value in values), "SVG path coordinate count differs")
        current.extend((float(values[offset]), float(values[offset + 1])) for offset in range(0, count, 2))
        index += count
    if current:
        require(allow_open, "SVG glyph contour is unclosed")
        result.append(current)
    require(result, "SVG path has no contours")
    return result


def rgb(value):
    match = re.fullmatch(r"rgb\(([^)]+)\)", value or "")
    require(match is not None, "SVG paint is not explicit RGB")
    values = match[1].split()
    require(len(values) == 3 and all(value.endswith("%") for value in values), "SVG RGB syntax differs")
    return tuple(float(value[:-1]) / 100 for value in values)


def check_svg(path, reader, spans, width, height):
    root = ET.parse(path).getroot()
    pages = root.findall(SVG + "svg")
    require(root.tag == SVG + "svg" and len(pages) == len(reader.pages), "rich SVG pages differ")
    require(set(root.attrib) == {"width", "height", "viewBox"}, "rich SVG root gained a transform or indirect paint state")
    require(root.get("width").endswith("pt") and root.get("height").endswith("pt"), "rich SVG root units differ")
    close(float(root.get("width")[:-2]), width, "rich SVG root width")
    close(float(root.get("height")[:-2]), height * len(pages) + 20 * (len(pages) - 1), "rich SVG root height")
    require(list(map(float, root.get("viewBox", "").split())) == [0, 0, width, height * len(pages) + 20 * (len(pages) - 1)],
            "rich SVG root viewBox differs")
    for node in root.iter():
        require(all(node.get(key) is None for key in ("opacity", "fill-opacity", "display", "visibility", "style")),
                "rich SVG gained hidden/indirect paint state")
    for index, page in enumerate(pages):
        require(set(page.attrib) == {"data-page", "x", "y", "width", "height", "viewBox"}, "rich SVG page paint state differs")
        require(page.get("data-page") == str(index + 1), "rich SVG physical page differs")
        close(float(page.get("width")), width, "rich SVG page width")
        close(float(page.get("height")), height, "rich SVG page height")
        require(list(map(float, page.get("viewBox", "").split())) == [0, 0, width, height], "rich SVG page viewBox differs")
        close(float(page.get("x")), 0, "rich SVG page x")
        close(float(page.get("y")), index * (height + 20), "rich SVG page y")
        backgrounds = page.findall(SVG + "rect")
        require(len(backgrounds) == 1 and list(page)[0] is backgrounds[0]
                and backgrounds[0].attrib == {"width": "100%", "height": "100%", "fill": "white"},
                "rich SVG background was moved or changed to obscure text")
        found, decorations = [], []
        expected_lines = [line for line in strokes(reader) if line["page"] == index]
        def walk(node, matrix, in_run=False):
            allowed = {SVG + "g": {"transform", "aria-label"}, SVG + "title": set(),
                       SVG + "rect": {"width", "height", "fill"},
                       SVG + "path": {"d", "fill"} if in_run else {"d", "fill", "stroke", "stroke-width", "stroke-linecap", "stroke-linejoin"},
                       SVG + "svg": {"data-page", "x", "y", "width", "height", "viewBox"}}
            require(node.tag in allowed and set(node.attrib) <= allowed[node.tag], "rich SVG gained clipping, masking or unexpected paint state")
            if node.tag == SVG + "rect": require(node is backgrounds[0], "rich SVG gained an obscuring rectangle")
            transform = node.get("transform")
            if transform is not None:
                match = re.fullmatch(r"matrix\(([^)]+)\)", transform)
                require(match is not None, "unexpected rich SVG transform")
                values = tuple(map(float, match[1].split()))
                require(len(values) == 6 and values[:4] == (1, 0, 0, 1), "rich SVG gained a scaling/rotation transform")
                matrix = compose(matrix, values)
            label = node.get("aria-label")
            if label is not None:
                matches = [value for value in spans if value["page"] == index and value["source"] == label]
                require(len(matches) == 1, "SVG run differs from independently decoded PDF source")
                span, contours = matches[0], []
                paths = node.findall(SVG + "path")
                require(paths, "rich SVG label has no painted glyph path")
                for shape in paths:
                    require(same_color(rgb(shape.get("fill")), span["color"]),
                            "rich SVG glyph color differs from actual PDF")
                    require(shape.get("transform") is None, "SVG glyph path transformed")
                    contours.extend(path_contours(shape.get("d")))
                require(len(contours) == sum(glyph["contours"] for glyph in span["glyphs"]), "SVG glyph contours were deleted or duplicated")
                cursor, visible = 0, 0
                for glyph in span["glyphs"]:
                    count = glyph["contours"]
                    if not count: continue
                    visible += 1
                    points = [point(matrix, *value) for contour in contours[cursor:cursor + count] for value in contour]
                    x0, y0, x1, y1 = glyph["bounds"]
                    require(all(x0 - .04 <= x <= x1 + .04 and y0 - .04 <= y <= y1 + .04 for x, y in points),
                            "SVG glyph differs from actual embedded-font bounds/position")
                    xs, ys = zip(*points, strict=True)
                    # JDK float glyph outlines are rounded to 1/64 pt; retain a finite tolerance.
                    for actual, expected in zip((min(xs), min(ys), max(xs), max(ys)), glyph["bounds"], strict=True):
                        close(actual, expected, "SVG actual glyph bounds", tolerance=.04)
                    cursor += count
                require(visible == sum(not char.isspace() for char in span["source"]), "SVG visible Latin glyph count differs")
                found.append(label)
            elif node.tag == SVG + "path" and not in_run:
                require(node.get("fill") == "none" and node.get("stroke-linecap") == "butt"
                        and node.get("stroke-linejoin") == "miter", "SVG decoration paint differs")
                segments = path_contours(node.get("d"), allow_open=True)
                require(len(segments) == 1 and len(segments[0]) == 2, "SVG decoration is not one line")
                (x0, y0), (x1, y1) = [point(matrix, *value) for value in segments[0]]
                matches = [line for line in expected_lines if abs(line["x"] - x0) < .003 and abs(line["right"] - x1) < .003
                           and abs(line["y"] - y0) < .003 and abs(y1 - y0) < .003
                           and abs(line["width"] - float(node.get("stroke-width"))) < .003
                           and same_color(line["color"], rgb(node.get("stroke")))]
                require(len(matches) == 1 and matches[0] not in decorations, "SVG decoration differs from actual PDF line")
                decorations.append(matches[0])
            for child in node: walk(child, matrix, in_run or label is not None)
        walk(page, (1, 0, 0, 1, 0, 0))
        require(found == [value["source"] for value in spans if value["page"] == index], "rich SVG source order/count differs")
        require(len(decorations) == len(expected_lines), "SVG decoration was deleted or duplicated")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--consumer-build-directory", type=Path)
    args = parser.parse_args()
    versions = {name: metadata.version(name) for name in ("pypdf", "pypdfium2", "Pillow")}
    require(versions == {"pypdf": "6.10.0", "pypdfium2": "5.13.0", "Pillow": "12.3.0"}, "use pinned verification tools")
    directory = args.consumer_build_directory or Path((ROOT / "build/verification/consumer-build-directory.txt").read_text(encoding="utf-8").strip())
    if not directory.is_absolute(): directory = ROOT / directory
    OUTPUT.mkdir(parents=True, exist_ok=True)
    report, contacts = {"tools": versions, "consumerBuildDirectory": str(directory)}, []
    for name, path, caller in (("example", ROOT / "examples/build/component-text/report.pdf", False),
                               ("consumer", directory / "output/consumer-component-text.pdf", True)):
        checked = check(path, caller)
        first, second = render(path, OUTPUT / name)
        require(len(first) == len(second) == checked["pages"], "renderer missed complete rich-text pages")
        metrics = [difference(a, b, 2) for a, b in zip(first, second, strict=True)]
        require(all(value["meanAbsoluteChannelError"] < 4 and value["changedPixelFraction"] < .06 for value in metrics),
                "independent rich component renderers disagree")
        checked["rendererDifferences"] = metrics
        report[name] = checked
        contacts.extend((name + " " + str(index), page) for index, page in enumerate(first, 1))
    contact_sheet(contacts, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"componentText": "passed", "pagesIncludingConsumer": len(contacts)}, indent=2))


if __name__ == "__main__": main()
