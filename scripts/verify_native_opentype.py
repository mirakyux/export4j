# SPDX-License-Identifier: Apache-2.0
"""Independent shaping, font outlines, PDF source/search/selection, dual rendering and CFF PDF/A."""
from __future__ import annotations

import argparse
import base64
from contextlib import closing
import csv
from copy import deepcopy
from hashlib import sha256
from importlib import metadata
import json
import math
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

import pypdfium2 as pdfium
import resvg_py
from PIL import Image, ImageChops
from pypdf import PdfReader
import uharfbuzz as hb
from fontTools.pens.boundsPen import BoundsPen
from fontTools.svgLib.path import parse_path
from fontTools.ttLib import TTFont

from verify_advanced_pdf import difference, render_pdfium, render_poppler, require
from verify_production import contact_sheet
from verify_semantic_tables import page_logical

ROOT = Path(__file__).resolve().parents[1]


def original(path):
    return path.read_bytes().decode("utf-8")


def compact(text):
    return "".join(text.split())


def shaping(source):
    rows = list(csv.DictReader((source / "glyphs.tsv").open(encoding="utf-8"), delimiter="\t"))
    groups = {}
    for row in rows:
        groups.setdefault(row["name"], []).append(row)
    require(len(groups) == 26 and len(rows) > 600, "native shaping corpus is incomplete")
    report = {}
    paths = {}
    for name, glyphs in groups.items():
        head = glyphs[0]
        program = (ROOT / "assets/fonts" / head["font"]).read_bytes()
        text = base64.b64decode(head["source64"]).decode("utf-8")
        axes = dict((key, float(value)) for key, value in (item.split("=") for item in head["axes"].split(",") if item))
        face = hb.Face(program)
        font = hb.Font(face)
        font.scale = (face.upem, face.upem)
        font.set_variations(axes)
        buffer = hb.Buffer()
        buffer.add_str(text)
        buffer.direction = {"LEFT_TO_RIGHT": "ltr", "RIGHT_TO_LEFT": "rtl", "TOP_TO_BOTTOM": "ttb", "BOTTOM_TO_TOP": "btt"}[head["direction"]]
        buffer.language = "und"
        buffer.guess_segment_properties()
        hb.shape(font, buffer)
        require(len(buffer.glyph_infos) == len(glyphs), f"independent glyph count: {name}")
        utf16 = [len(text[:index].encode("utf-16-le")) // 2 for index in range(len(text) + 1)]
        ends = sorted({utf16[info.cluster] for info in buffer.glyph_infos} | {utf16[-1]})
        scale = float(head["size"]) / face.upem
        x = y = 0
        outline_checks = 0
        with TTFont(ROOT / "assets/fonts" / head["font"]) as tt:
            glyph_set = tt.getGlyphSet(location=axes)
            order = tt.getGlyphOrder()
            for row, info, pos in zip(glyphs, buffer.glyph_infos, buffer.glyph_positions, strict=True):
                start = utf16[info.cluster]
                require(int(row["gid"]) == info.codepoint and int(row["start"]) == start
                        and int(row["end"]) == next(value for value in ends if value > start), f"independent GID/UTF-16 cluster: {name}")
                expected = (x + pos.x_offset * scale, y - pos.y_offset * scale, pos.x_advance * scale, -pos.y_advance * scale)
                require(all(abs(float(row[key]) - value) < 0.00001 for key, value in zip(("x", "y", "xAdvance", "yAdvance"), expected, strict=True)),
                        f"independent GPOS/advance: {name}")
                x += pos.x_advance * scale
                y -= pos.y_advance * scale
                actual = base64.b64decode(row["path64"]).decode("utf-8")
                pen = BoundsPen(glyph_set)
                glyph_set[order[info.codepoint]].draw(pen)
                if pen.bounds is None:
                    require(not actual, f"empty independent outline: {name}")
                else:
                    native_pen = BoundsPen(None)
                    parse_path(actual, native_pen)
                    require(native_pen.bounds is not None, f"missing native outline: {name}")
                    x0, y0, x1, y1 = pen.bounds
                    expected_bounds = (x0 * scale, -y1 * scale, x1 * scale, -y0 * scale)
                    # HarfBuzz varies on the integer font-unit grid; fontTools retains fractional units.
                    require(max(abs(a - b) for a, b in zip(expected_bounds, native_pen.bounds, strict=True)) <= scale * 1.1,
                            f"independent variable/CFF2 glyph bounds: {name}")
                    outline_checks += 1
            paths[name] = [row["path64"] for row in glyphs]
        report[name] = {"glyphs": len(glyphs), "outlineBounds": outline_checks, "fontSha256": sha256(program).hexdigest(), "axes": axes}
    axes = [name for name in paths if name.startswith("variable-") and name != "variable-default"]
    require(len(axes) == 13 and all(paths[name] != paths["variable-default"] for name in axes), "all 13 variation axes must change real outlines")
    require(paths["cff2-200"] != paths["cff2-900"], "CFF2 coordinates did not change actual outlines")
    return report


def reader_checks(path, expected_pages, query):
    result = []
    with pdfium.PdfDocument(path) as document:
        require(len(document) == len(expected_pages), "PDFium page count differs")
        for index, expected in enumerate(expected_pages):
            with closing(document[index]) as page, closing(page.get_textpage()) as text:
                extracted = text.get_text_range()
                require(compact(extracted) == compact(expected), f"PDFium source lost/reordered: {path.name} page {index + 1}")
                found = []
                with closing(text.search(query, match_case=True)) as search:
                    while (match := search.get_next()) is not None:
                        start, count = match
                        selected = text.get_text_range(start, count)
                        rects = [list(text.get_rect(i)) for i in range(text.count_rects(start, count))]
                        width, height = page.get_size()
                        require(selected == query and rects and all(all(math.isfinite(v) for v in rect) and
                                -1 <= rect[0] <= rect[2] <= width + 1 and -1 <= rect[1] <= rect[3] <= height + 1 for rect in rects),
                                f"PDFium search/selected text or region: {path.name}")
                        found.append({"start": start, "count": count, "selected": selected, "rectangles": rects})
                require(len(found) == expected.count(query), f"PDFium exact query occurrences: {path.name} page {index + 1}")
                result.append({"text": extracted, "query": query, "matches": found})
    return result


def cff_structure(path, font_file, expected):
    reader = PdfReader(path, strict=True)
    with TTFont(ROOT / "assets/fonts" / font_file) as font:
        raw = font.reader["CFF "]
        cid = hasattr(font["CFF "].cff.topDictIndex[0], "ROS")
    programs = []
    for reference in reader.pages[0]["/Resources"]["/Font"].values():
        embedded = reference.get_object()
        require("/ToUnicode" in embedded, "CFF ToUnicode missing")
        descendant = embedded["/DescendantFonts"][0].get_object() if cid else embedded
        program = descendant["/FontDescriptor"]["/FontFile3"]
        require(program["/Subtype"] == ("/CIDFontType0C" if cid else "/Type1C") and program.get_data() == raw, "CFF embedded program/subtype differs")
        programs.append(program.indirect_reference.idnum)
    require(len(set(programs)) == 1, "CFF encodings duplicated full font program")
    require(compact(reader.pages[0].extract_text()) == compact(expected), "pypdf native CFF extraction differs")
    return {"fontDictionaries": len(programs), "sharedPrograms": 1, "subtype": "CIDFontType0C" if cid else "Type1C", "programSha256": sha256(raw).hexdigest()}


def compliance(path, flavour, executable, target):
    result = subprocess.run([str(executable), "--format", "xml", "--flavour", flavour, str(path)], capture_output=True, timeout=90, check=False)
    (target / f"{path.stem}-verapdf.xml").write_bytes(result.stdout)
    document = ET.fromstring(result.stdout)
    validation = document.findall(".//validationReport")
    require(len(validation) == 1 and validation[0].get("isCompliant") == "true", f"native CFF PDF/A-{flavour}: {path.name}")
    jobs = document.findall(".//job")
    require(len(jobs) == 1 and Path(jobs[0].findtext("item/name")).resolve() == path.resolve(), "PDF/A report describes another file")
    return {"profile": validation[0].get("profileName"), "sha256": sha256(path.read_bytes()).hexdigest()}


def svg_checks(source, target, name, expected_pages):
    root = ET.parse(source / f"{name}.svg").getroot()
    pages = [node for node in root if node.tag.endswith("svg")]
    require(len(pages) == len(expected_pages), f"SVG page count: {name}")
    result = []
    for index, (node, expected) in enumerate(zip(pages, expected_pages, strict=True)):
        labels = [next(iter(item)).text or "" for item in node.iter() if "aria-label" in item.attrib]
        require("".join(labels) == expected.replace("\r\n", "\n").replace("\r", "\n"), f"SVG logical source: {name}")
        page = deepcopy(node)
        page.set("x", "0"); page.set("y", "0")
        with Image.open(target / name / "pdfium" / f"page-{index + 1}.png") as reference:
            width, height = reference.size
        # Both pages share their point viewBox. Fix the outer pixel viewport to PDFium's
        # rounded 96-DPI extent so resvg does not ceil the fractional height a second time.
        page.set("width", str(width)); page.set("height", str(height))
        rendered = target / name / f"resvg-page-{index + 1}.png"
        rendered.write_bytes(resvg_py.svg_to_bytes(svg_string=ET.tostring(page, encoding="unicode"),
                width=width, height=height, background="#ffffff", skip_system_fonts=True))
        diff = difference(rendered, target / name / "pdfium" / f"page-{index + 1}.png", 1)
        require(diff["meanAbsoluteChannelError"] < 5 and diff["changedPixelFraction"] < .07, f"independent SVG/PDF rendering: {name}")
        result.append(diff)
    return result


def inspect(source, target, verapdf):
    target.mkdir(parents=True, exist_ok=True)
    report = {"tools": {name: metadata.version(name) for name in ("pypdf", "pypdfium2", "Pillow", "fonttools", "uharfbuzz", "resvg-py")},
              "independentHarfBuzz": hb.version_string(), "nativeHarfBuzz": original(source / "native-version.txt"),
              "shaping": shaping(source), "fixtures": {}, "archival": {}}
    contacts = []
    rows = list(csv.DictReader((source / "fixtures.tsv").open(encoding="utf-8"), delimiter="\t"))
    require(len(rows) == 37 and len({row["name"] for row in rows}) == 37, "font fixture inventory differs")
    for row in rows:
        name = row["name"]
        path = source / f"{name}.pdf"
        reader = PdfReader(path, strict=True)
        count = int(row["pages"])
        expected = original(source / f"{name}.txt")
        pages = [original(source / f"{name}-page-{index + 1}.txt") for index in range(count)]
        require(len(reader.pages) == count and "".join(pages) == expected, f"page/source coverage: {name}")
        native_cff = row["nativeCff"] == "true"
        if not native_cff:
            for index, page in enumerate(reader.pages):
                require("".join(page_logical(page)) == pages[index], f"PDF ActualText source: {name} page {index + 1}")
        svg = ET.parse(source / f"{name}.svg").getroot()
        require(svg.tag.endswith("svg"), f"SVG output is invalid: {name}")
        # Use a real corpus word/sequence rather than a reversed-text search workaround.
        query = ("天地" if name.startswith("vertical") else "السَّلَامُ" if name == "mixed-bidi" else
                 expected.splitlines()[0] if name.startswith("script-") else "😀" if name.startswith("NotoColorEmoji") else
                 "café" if name == "cff-native" else "中😀" if name == "cff-cid-native" else "A" if row["color"] == "true" else "Hamburgefontsiv")
        evidence = reader_checks(path, pages, query)
        rendered_poppler = render_poppler(path, target / name / "poppler")
        rendered_pdfium = render_pdfium(path, target / name / "pdfium")
        require(len(rendered_poppler) == len(rendered_pdfium) == count, f"dual rendering counts: {name}")
        diffs = [difference(a, b, 1) for a, b in zip(rendered_poppler, rendered_pdfium, strict=True)]
        require(all(item["meanAbsoluteChannelError"] < 4 and item["changedPixelFraction"] < .06 for item in diffs), f"dual renderings differ: {name}")
        native_diffs = []
        for index, png in enumerate(rendered_pdfium):
            with Image.open(source / f"{name}-page-{index + 1}.png") as image, Image.open(png) as independent:
                native = image.convert("RGB").resize(independent.size, Image.Resampling.LANCZOS)
                native_path = target / name / f"native-96dpi-page-{index + 1}.png"
                native.save(native_path)
                require(ImageChops.difference(independent.convert("RGB"), Image.new("RGB", independent.size, "white")).getbbox() is not None,
                        f"empty independently rendered font page: {name}")
            diff = difference(native_path, png, 1)
            require(diff["meanAbsoluteChannelError"] < 5 and diff["changedPixelFraction"] < .07, f"native PNG and PDF differ: {name}")
            native_diffs.append(diff)
            contacts.append((f"{name} {index + 1}", png))
        item = {"pages": count, "sha256": sha256(path.read_bytes()).hexdigest(), "pdfium": evidence,
                "pypdfText": [page.extract_text() for page in reader.pages], "dualRendering": diffs, "nativePngRendering": native_diffs}
        item["svgRendering"] = svg_checks(source, target, name, pages)
        if native_cff:
            font_file = "SourceSans3-Regular.otf" if name == "cff-native" else "Export4j-CID-Test.otf"
            item["cff"] = cff_structure(path, font_file, expected)
            for profile, flavour in (("a1b", "1b"), ("a2b", "2b"), ("a3b", "3b"), ("a4f", "4f")):
                archive = source / f"{name}-{profile}.pdf"
                report["archival"][archive.stem] = compliance(archive, flavour, verapdf, target)
                cff_structure(archive, font_file, expected)
                reader_checks(archive, [expected], query)
                first = render_poppler(archive, target / archive.stem / "poppler")
                second = render_pdfium(archive, target / archive.stem / "pdfium")
                require(len(first) == len(second) == 1 and difference(first[0], second[0], 1)["meanAbsoluteChannelError"] < 4, "archived CFF renderers differ")
                contacts.append((archive.stem, second[0]))
        report["fixtures"][name] = item
    for prefix in ("Export4j-ColorV0-Test", "Export4j-ColorV1-Test"):
        a = source / f"{prefix}-page-1.png"
        b = source / f"{prefix}-palette1-page-1.png"
        require(difference(a, b)["meanAbsoluteChannelError"] > 1, "actual palette pixels did not change")
    contact_sheet(contacts, target)
    report["summary"] = {"fixtures": len(rows), "pdfFiles": len(rows) + len(report["archival"]), "pages": len(contacts),
                         "shapedRuns": len(report["shaping"]), "glyphs": sum(item["glyphs"] for item in report["shaping"].values()),
                         "independentSvgPages": sum(len(item["svgRendering"]) for item in report["fixtures"].values()),
                         "searchScope": "PDFium APIs; actual logical text and nonempty regions, line/run-level outline anchors; no live UI claim"}
    (target / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report["summary"], ensure_ascii=True, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--consumer-build-directory", type=Path)
    parser.add_argument("--output", type=Path, default=ROOT / "build/verification/native-opentype")
    parser.add_argument("--verapdf", type=Path, default=ROOT / "build/verification/verapdf/verapdf.bat")
    parser.add_argument("--svg-only", action="store_true", help="Add independent SVG checks to an existing report for the exact same final PDFs")
    args = parser.parse_args()
    directory = args.consumer_build_directory or Path((ROOT / "build/verification/consumer-build-directory.txt").read_text(encoding="utf-8").strip())
    source = directory / "output/opentype"
    if args.svg_only:
        report_path = args.output / "report.json"
        report = json.loads(report_path.read_text(encoding="utf-8"))
        count = 0
        for name, item in report["fixtures"].items():
            require(item["sha256"] == sha256((source / f"{name}.pdf").read_bytes()).hexdigest(), "existing report describes a different PDF")
            pages = [original(source / f"{name}-page-{index + 1}.txt") for index in range(item["pages"])]
            item["svgRendering"] = svg_checks(source, args.output, name, pages)
            count += len(pages)
        report["tools"]["resvg-py"] = metadata.version("resvg-py")
        report["summary"]["independentSvgPages"] = count
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"independentSvgPages": count, "checks": "passed"}))
    else:
        inspect(source, args.output, args.verapdf)


if __name__ == "__main__":
    main()
