# SPDX-License-Identifier: Apache-2.0
"""Inspect semantic-theme exports from published consumers and render every PDF/DOCX page.

Run after the isolated consumer's easyExport task. This verifier never builds Java artifacts,
updates baselines, or requires locally installed glyphs for declaration-only DOCX output.
"""
from __future__ import annotations

import argparse
import ctypes
from importlib import metadata
import json
from pathlib import Path
import re
import subprocess
import sys
from zipfile import ZipFile
import xml.etree.ElementTree as ET

from PIL import Image, ImageChops
from pypdf import PdfReader
from pypdf.generic import ContentStream
import pypdfium2 as pdfium
import pypdfium2.raw as pdfium_raw

from verify_easy_export import contact_sheet, digest, render_pdf

ROOT = Path(__file__).resolve().parents[1]
NS = {"w": "http://schemas.openxmlformats.org/wordprocessingml/2006/main",
      "m": "http://schemas.openxmlformats.org/officeDocument/2006/math"}
W = "{" + NS["w"] + "}"
CASES = ("markdown", "html", "raw-html", "xml", "request", "css", "global")
PINNED = {"python": "3.12.14", "pypdf": "6.10.0", "pypdfium2": "5.13.0",
          "pdfium": "153.0.7999.0", "Pillow": "12.3.0", "poppler": "26.07.0"}
OFFICE_VERSION = "27.2.0.0.alpha0"
# x^2 control-box dimensions in pt from direct TexMath.vector calls, independently of
# source import, theme resolution and PDF/DOCX production. The reference uses the pinned
# mathematical fonts/JLaTeXMath 1.0.7. This is not a baseline refreshed from outputs under test.
# PDF writes coordinates at five decimal places; 0.001 pt bounds the resulting subtraction.
FORMULA_BOUNDS = {9: (7.706251996683156, 7.547277611960258),
                  10: (8.559030445966798, 8.394545377108393),
                  13: (11.132989193620215, 10.920721293484656),
                  14: (12.001391877201918, 11.767987423081347),
                  15: (12.838545002972237, 12.599630571448431),
                  16: (13.691323295078433, 13.431272481261544),
                  17: (14.559726070689695, 14.278538593453675),
                  18: (15.428129357201447, 15.125805236842414)}


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def expected(case):
    if case == "css":
        return [("CssHeader", 10, 0), ("CssCell", 10, 0)]
    result = [(f"Header{level}", 19 - level, 0x884422 + level * 0x080808) for level in range(1, 7)]
    result += [("Body", 10, 0x223344), ("Cell", 9, 0x445566)]
    if case == "request":
        result = [(label, 9 if label == "Cell" else 10, 0x223344) for label, _, _ in result]
    elif case == "global":
        result = [(label, size, 0) for label, size, _ in result]
    return result


def glyph_label(label):
    return "Glyph" + (label.removeprefix("Header") if label.startswith("Header") else label)


def tools(office):
    versions = {name: metadata.version(name) for name in ("pypdf", "pypdfium2", "Pillow")}
    versions.update(python=sys.version.split()[0], pdfium=pdfium.PDFIUM_INFO.version)
    poppler = subprocess.run(["pdftoppm", "-v"], capture_output=True, text=True, timeout=30, check=True)
    raw = poppler.stdout + poppler.stderr
    match = re.search(r"pdftoppm version (\S+)", raw)
    require(match is not None, "cannot identify the Poppler renderer version")
    versions["poppler"] = match.group(1)
    require(versions == PINNED, f"use the pinned verification tools: {versions}")
    if office:
        require(office.is_file(), "explicit independent DOCX reader executable is missing")
        result = subprocess.run([str(office), "--version"], capture_output=True, text=True, timeout=30, check=True)
        raw = (result.stdout + "\n" + result.stderr).strip()
        match = re.search(r"\bLibreOffice(?:Dev)? (\S+)", raw)
        require(match is not None and match.group(1) == OFFICE_VERSION,
                f"use the pinned LibreOffice reader {OFFICE_VERSION}: {raw}")
        versions["libreoffice"] = match.group(1)
        versions["libreofficeIdentification"] = raw
    return versions


def embedded_fonts(reader):
    fonts = set()
    for page in reader.pages:
        for reference in page["/Resources"].get("/Font", {}).values():
            font = reference.get_object()
            fonts.add(str(font.get("/BaseFont", "")))
            descendants = font.get("/DescendantFonts")
            physical = descendants[0].get_object() if descendants else font
            descriptor = physical.get("/FontDescriptor")
            require(descriptor is not None, "PDF font descriptor is absent")
            descriptor = descriptor.get_object()
            require(descriptor.get("/FontFile2") or descriptor.get("/FontFile3"), "PDF font program is absent")
        require(not any(ref.get_object().get("/Subtype") == "/Image"
                        for ref in page["/Resources"].get("/XObject", {}).values()),
                "theme formula was rasterized")
    require(fonts and all("LiberationSans" in name for name in fonts),
            f"theme PDF used an undeclared physical family: {fonts}")
    return sorted(fonts)


def selected_glyphs(path, labels):
    pages, glyphs = [], []
    with pdfium.PdfDocument(path) as pdf:
        for index in range(len(pdf)):
            page = pdf[index]
            try:
                textpage = page.get_textpage()
                try:
                    characters = []
                    for offset in range(textpage.count_chars()):
                        char = textpage.get_text_range(offset, 1)
                        values = [ctypes.c_uint() for _ in range(4)]
                        colored = pdfium_raw.FPDFText_GetFillColor(textpage.raw, offset,
                                                                  *(ctypes.byref(v) for v in values))
                        color = tuple(v.value for v in values) if colored else None
                        characters.append({"text": char, "page": index + 1,
                                           "fontSize": pdfium_raw.FPDFText_GetFontSize(textpage.raw, offset),
                                           "rgba": color, "box": textpage.get_charbox(offset)})
                    pages.append("".join(item["text"] for item in characters))
                    glyphs.append(characters)
                finally:
                    textpage.close()
            finally:
                page.close()
    selected = {}
    for label, size, rgb in labels:
        occurrences = []
        for page_index, text in enumerate(pages):
            cursor = 0
            for index, glyph in enumerate(glyphs[page_index]):
                if text.startswith(label, cursor):
                    run = []
                    count = 0
                    for item in glyphs[page_index][index:]:
                        run.append(item)
                        count += len(item["text"])
                        if count >= len(label):
                            break
                    require("".join(item["text"] for item in run) == label, f"PDF label mapping split: {label}")
                    occurrences.append(run)
                cursor += len(glyph["text"])
        require(len(occurrences) == 1, f"PDF label missing or duplicated: {label}")
        run = occurrences[0]
        color = ((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, 255)
        require(all(abs(item["fontSize"] - size) <= .001 for item in run), f"PDF glyph size differs for {label}")
        require(all(item["rgba"] == color for item in run), f"PDF glyph color differs for {label}: {run}")
        selected[label] = {"fontSize": size, "rgb": f"{rgb:06X}", "page": run[0]["page"],
                           "box": [min(item["box"][0] for item in run), min(item["box"][1] for item in run),
                                   max(item["box"][2] for item in run), max(item["box"][3] for item in run)]}
    return selected


def multiply(left, right):
    a, b, c, d, e, f = left
    g, h, i, j, k, l = right
    return (a*g + c*h, b*g + d*h, a*i + c*j, b*i + d*j, a*k + c*l + e, b*k + d*l + f)


def formula_paths(reader):
    groups = []
    for page_index, page in enumerate(reader.pages):
        matrix, color = (1, 0, 0, 1, 0, 0), (0, 0, 0)
        graphics, marked = [], []
        following = None
        for operands, operator in ContentStream(page.get_contents(), reader).operations:
            if operator == b"q":
                graphics.append((matrix, color))
            elif operator == b"Q":
                require(graphics, "unbalanced PDF graphics state")
                matrix, color = graphics.pop()
            elif operator == b"cm":
                matrix = multiply(matrix, tuple(float(value) for value in operands))
            elif operator == b"rg":
                color = tuple(round(float(value) * 255) for value in operands)
            elif operator == b"g":
                color = (round(float(operands[0]) * 255),) * 3
            if operator in (b"BT", b"re", b"BDC", b"BMC"):
                following = None
            if operator in (b"BDC", b"BMC"):
                group = None
                if operator == b"BDC" and len(operands) > 1:
                    properties = operands[1]
                    if not isinstance(properties, dict):
                        properties = page["/Resources"].get("/Properties", {}).get(properties, {}).get_object()
                    if properties.get("/ActualText") is not None and "x^2" in str(properties["/ActualText"]):
                        group = {"text": str(properties["/ActualText"]), "page": page_index + 1,
                                 "pending": [], "curves": 0, "paths": []}
                        groups.append(group)
                marked.append(group)
            elif operator == b"EMC":
                require(marked, "unbalanced PDF marked content")
                following = marked.pop()
            active = next((item for item in reversed(marked) if item is not None), following)
            if active is not None and operator in (b"m", b"l", b"c", b"v", b"y"):
                a, b, c, d, e, f = matrix
                for offset in range(0, len(operands), 2):
                    x, y = float(operands[offset]), float(operands[offset + 1])
                    active["pending"].append((a*x + c*y + e, b*x + d*y + f))
                active["curves"] += int(operator in (b"c", b"v", b"y"))
            if active is not None and operator in (b"f", b"f*", b"B", b"B*"):
                points = active["pending"]
                require(points, "filled formula-host path has no geometry")
                active["paths"].append({"box": [min(x for x, _ in points), min(y for _, y in points),
                                                  max(x for x, _ in points), max(y for _, y in points)],
                                         "curves": active["curves"], "rgb": color})
                active["pending"] = []
                active["curves"] = 0
        require(not marked and not graphics, "unclosed PDF state")
    for group in groups:
        require(not group.pop("pending"), "formula-host path was not painted")
        group.pop("curves")
        require(group["paths"], "formula host has no filled outlines")
    return groups


def check_pdf(path, case):
    labels = expected(case)
    reader = PdfReader(path, strict=True)
    fonts = embedded_fonts(reader)
    # Formula-bearing lines are outlined and expose a nonpainting 1 pt reading anchor.
    # The separate same-domain Glyph* lines exercise real native PDF text, not that anchor.
    glyphs = selected_glyphs(path, [(glyph_label(label), size, rgb) for label, size, rgb in labels])
    formulas = formula_paths(reader)
    require(len(formulas) == len(labels), f"formula ActualText count differs: {case}")
    for formula, (label, size, rgb) in zip(formulas, labels):
        require(label in formula["text"] and formula["text"].count("x^2") == 1,
                f"formula host ActualText differs: {case}/{label}")
        color = ((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255)
        width, height = FORMULA_BOUNDS[size]
        matching = [path for path in formula["paths"] if path["curves"] > 0
                    and abs((path["box"][2] - path["box"][0]) - width) <= .001
                    and abs((path["box"][3] - path["box"][1]) - height) <= .001]
        require(len(matching) == 1, f"formula control-box size differs for {case}/{label}: {formula['paths']}")
        require(matching[0]["rgb"] == color, f"formula path color differs for {case}/{label}")
        require(all(path["rgb"] == color for path in formula["paths"]),
                f"outlined label color differs for {case}/{label}")
        formula["box"] = matching[0]["box"]
        left, bottom, right, top = formula["box"]
        hosts = [path for path in formula["paths"] if path is not matching[0]
                 and -.1 <= left - path["box"][2] <= 3 * size
                 and bottom <= path["box"][3] + size and top >= path["box"][1] - size]
        require(hosts,
                f"formula left its text host for {case}/{label}")
        formula.update(host=label, fontSize=size, rgb=f"{rgb:06X}")
    return {"sha256": digest(path), "pages": len(reader.pages), "embeddedFonts": fonts,
            "labels": glyphs, "formulas": formulas}


def font_slots(node, fallback=None):
    fallback = fallback or {}
    fonts = node.find("w:rPr/w:rFonts", NS)
    return {slot: (fonts.get(W + slot, fallback.get(slot)) if fonts is not None else fallback.get(slot))
            for slot in ("ascii", "hAnsi", "eastAsia", "cs")}


def effective_math_color(run, paragraph, document, styles, parents):
    """Resolve this fixture's Word color cascade, including omitted automatic black.

    Theme colors and colored backgrounds need a separate theme/contrast resolver; reject
    them rather than treating every absent or automatic color as black.
    """
    def color(properties):
        node = properties.find("w:color", NS) if properties is not None else None
        if node is None:
            return None
        require(not any(node.get(W + attribute) is not None
                        for attribute in ("themeColor", "themeTint", "themeShade")),
                "theme-based Word color needs explicit theme resolution")
        value = node.get(W + "val", "auto")
        require(value == "auto" or re.fullmatch(r"[0-9a-fA-F]{6}", value),
                f"invalid Word text color: {value}")
        return value.upper()

    index = {style.get(W + "styleId"): style for style in styles.findall("w:style", NS)}

    def style_colors(identifier, visiting=()):
        require(identifier in index and identifier not in visiting,
                f"missing or cyclic Word style: {identifier}")
        style = index[identifier]
        base = style.find("w:basedOn", NS)
        result = style_colors(base.get(W + "val"), visiting + (identifier,)) if base is not None else []
        return result + [color(style.find("w:rPr", NS))]

    values = [color(styles.find("w:docDefaults/w:rPrDefault/w:rPr", NS))]
    paragraph_style = paragraph.find("w:pPr/w:pStyle", NS)
    if paragraph_style is not None:
        values.extend(style_colors(paragraph_style.get(W + "val")))
    else:
        default_styles = [style for style in index.values()
                          if style.get(W + "type") == "paragraph" and style.get(W + "default") == "1"]
        require(len(default_styles) <= 1, "multiple default Word paragraph styles")
        if default_styles:
            values.extend(style_colors(default_styles[0].get(W + "styleId")))
    # Paragraph mark properties are not ordinary text run formatting. These fixtures
    # deliberately contain no color there, so they cannot ambiguously style math.
    require(color(paragraph.find("w:pPr/w:rPr", NS)) is None,
            "paragraph mark color needs separate mathematical inheritance verification")
    run_style = run.find("w:rPr/w:rStyle", NS)
    if run_style is not None:
        values.extend(style_colors(run_style.get(W + "val")))
    lineage, node = [], run
    while node is not paragraph:
        lineage.append(node)
        require(node in parents, "native math run left its paragraph")
        node = parents[node]
    for ancestor in reversed(lineage):
        for properties in list(ancestor):
            if properties.tag.startswith("{" + NS["m"] + "}") and properties.tag.endswith("Pr"):
                values.append(color(properties.find("m:ctrlPr/w:rPr", NS)))
    values.append(color(run.find("w:rPr", NS)))
    resolved = next((value for value in reversed(values) if value is not None), "AUTO")
    if resolved == "AUTO":
        require(not document.findall("w:background", NS) and not document.findall(".//w:shd", NS)
                and not styles.findall(".//w:shd", NS)
                and not any(style.get(W + "type") == "table" for style in index.values()),
                "automatic Word text color requires an unshaded default white page fixture")
        return "000000"
    return resolved


def check_docx(path, case=None):
    with ZipFile(path) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)) and archive.testzip() is None, "invalid DOCX archive")
        root = ET.fromstring(archive.read("word/document.xml"))
        styles = ET.fromstring(archive.read("word/styles.xml"))
        settings = ET.fromstring(archive.read("word/settings.xml"))
        parents = {child: parent for parent in root.iter() for child in parent}
        default = styles.find(".//w:docDefaults/w:rPrDefault", NS)
        require(default is not None, "Word font defaults are absent")
        slots = font_slots(default)
        require(set(slots.values()) == {"Liberation Sans"}, f"Word script slots differ: {slots}")
        require(not root.findall(".//w:drawing", NS) and not any(name.startswith("word/media/") for name in names),
                "DOCX theme formula became an image")
        text = "".join(node.text or "" for node in root.findall(".//w:t", NS))
        require("\ufffc" not in text and "equation-slot" not in text and "EXPORT4JCONTENT" not in text,
                "temporary source/formula marker leaked into DOCX")
        equations = root.findall(".//m:oMath", NS)
        if case is None:
            require(text == "中文字体声明" and not equations, "declaration-only DOCX text changed")
            return {"sha256": digest(path), "fontSlots": slots, "text": text,
                    "boundary": "Family declaration only; no local glyph coverage or reader substitution check."}
        labels = expected(case)
        require(len(equations) == len(labels), f"native equation count differs for {case}")
        mathfont = settings.find(".//m:mathPr/m:mathFont", NS)
        require(mathfont is not None and mathfont.get("{" + NS["m"] + "}val") == "Cambria Math",
                "native Word mathematical family declaration changed")
        checks = {}
        for label, size, rgb in labels:
            probe = glyph_label(label)
            probe_runs = [run for run in root.findall(".//w:r", NS)
                          if "".join(t.text or "" for t in run.findall("w:t", NS)).strip() == probe]
            require(len(probe_runs) == 1, f"DOCX native-glyph probe missing or duplicated: {probe}")
            probe_run = probe_runs[0]
            require(set(font_slots(probe_run, slots).values()) == {"Liberation Sans"},
                    f"DOCX native-glyph probe family differs: {probe}")
            require(probe_run.find("w:rPr/w:sz", NS).get(W + "val") == str(size * 2),
                    f"DOCX native-glyph probe size differs: {probe}")
            require(probe_run.find("w:rPr/w:color", NS).get(W + "val") == f"{rgb:06X}",
                    f"DOCX native-glyph probe color differs: {probe}")
            matches = []
            for paragraph in root.findall(".//w:p", NS):
                runs = [run for run in paragraph.findall(".//w:r", NS)
                        if "".join(t.text or "" for t in run.findall("w:t", NS)).strip() == label]
                matches.extend((paragraph, run) for run in runs)
            require(len(matches) == 1, f"DOCX label missing or duplicated: {case}/{label}")
            paragraph, run = matches[0]
            require(set(font_slots(run, slots).values()) == {"Liberation Sans"}, f"DOCX label family differs: {label}")
            require(run.find("w:rPr/w:sz", NS).get(W + "val") == str(size * 2), f"DOCX text size differs: {label}")
            require(run.find("w:rPr/w:color", NS).get(W + "val") == f"{rgb:06X}", f"DOCX text color differs: {label}")
            hosted = paragraph.findall(".//m:oMath", NS)
            require(len(hosted) == 1, f"native equation left its text host: {label}")
            equation = hosted[0]
            superscript = equation.find("m:sSup", NS)
            require(superscript is not None and "".join(equation.itertext()) == "x2", f"native x^2 structure differs: {label}")
            mathruns = equation.findall(".//m:r", NS)
            require(len(mathruns) == 2, f"native formula runs differ: {label}")
            for mathrun in mathruns:
                require(mathrun.find("w:rPr/w:sz", NS).get(W + "val") == str(size * 2),
                        f"native formula size differs: {label}")
                require(effective_math_color(mathrun, paragraph, root, styles, parents) == f"{rgb:06X}",
                        f"native formula color differs: {label}")
            checks[label] = {"fontSize": size, "rgb": f"{rgb:06X}", "nativeSuperscript": True, "glyphProbe": probe}
        return {"sha256": digest(path), "fontSlots": slots, "mathFont": "Cambria Math",
                "nativeEquations": len(equations), "labels": checks}


def convert_docx(path, target, office):
    target.mkdir(parents=True, exist_ok=False)
    command = [str(office), "-env:UserInstallation=" + (target / "profile").as_uri(), "--headless", "--invisible",
               "--norestore", "--convert-to", "pdf:writer_pdf_Export", "--outdir", str(target), str(path)]
    result = subprocess.run(command, capture_output=True, text=True, timeout=180)
    (target / "conversion.log").write_text(result.stdout + "\n" + result.stderr, encoding="utf-8")
    pdf = target / (path.stem + ".pdf")
    require(result.returncode == 0 and pdf.is_file(), f"independent DOCX reader failed: {path.name}")
    return pdf


def compare_renders(left, right, label):
    require(left["pages"] == right["pages"], f"same-source pagination changed: {label}")
    for engine in ("poppler", "pdfium"):
        for index, (first, second) in enumerate(zip(left[engine], right[engine]), 1):
            with Image.open(first) as a, Image.open(second) as b:
                require(a.size == b.size and ImageChops.difference(a.convert("RGB"), b.convert("RGB")).getbbox() is None,
                        f"same-source {engine} pixels changed: {label}, page {index}")


def inspect(build, output, office):
    source = build / "output/easy-export/theme"
    summary = json.loads((build / "output/easy-export/result.json").read_text(encoding="utf-8"))
    require(summary.get("uniqueRuntimeProviders") is True and len(summary.get("starterJarSha256", "")) == 64,
            "consumer did not prove its published starter provider")
    output.mkdir(parents=True, exist_ok=False)
    pdfs = {case: check_pdf(source / (case + ".pdf"), case) for case in CASES}
    docx = {case: check_docx(source / (case + ".docx"), case) for case in CASES}
    declaration = check_docx(source / "declaration-only.docx")
    require((source / "physical-glyph-rejection.bin").read_bytes() == b"old-target",
            "missing physical glyph failure replaced the previous PDF target")
    renders, readers = {}, {}
    for case in CASES:
        rendered = render_pdf(source / (case + ".pdf"), output / case)
        require(rendered["pages"] == pdfs[case]["pages"], f"incomplete PDF rendering: {case}")
        for label, _, _ in expected(case):
            require(label in rendered["text"], f"independent extraction lost source label: {case}/{label}")
            require(glyph_label(label) in rendered["text"], f"independent extraction lost native-glyph probe: {case}/{label}")
        renders[case] = rendered
        if office:
            opened = convert_docx(source / (case + ".docx"), output / (case + "-docx"), office)
            reader = render_pdf(opened, output / (case + "-docx") / "render")
            for label, _, _ in expected(case):
                require(label in reader["text"], f"independent DOCX reader lost label: {case}/{label}")
                require(glyph_label(label) in reader["text"], f"independent DOCX reader lost native-glyph probe: {case}/{label}")
            # Every source formula has exactly one x and one exponent 2 in this fixture.
            compact = "".join(reader["text"].split())
            require(compact.count("x") == len(expected(case)), f"independent DOCX reader lost formula bases: {case}")
            require(compact.count("x2") == len(expected(case)), f"independent DOCX reader lost formula exponents: {case}")
            renders[case + "-docx"] = reader
            readers[case] = {"status": "opened", "pages": reader["pages"], "pdfSha256": digest(opened),
                             "docxSha256": docx[case]["sha256"]}
        else:
            readers[case] = {"status": "skipped", "reason": "--soffice was not supplied"}
    compare_renders(renders["markdown"], renders["xml"], "Markdown/XML PDF")
    if office:
        compare_renders(renders["markdown-docx"], renders["xml-docx"], "Markdown/XML DOCX reader")
    contact_sheet([(f"{case} / {engine} / {index}", path) for case, rendered in renders.items()
                   for engine in ("poppler", "pdfium") for index, path in enumerate(rendered[engine], 1)], output)
    return {"consumerBuild": str(build), "starterJarSha256": summary["starterJarSha256"],
            "pdf": pdfs, "docx": docx, "declarationOnlyDocx": declaration,
            "physicalGlyphFailurePreservedTarget": True, "independentDocxReader": readers,
            "markdownXmlSameEnginePixelsEqual": True,
            "renderedPagesPerEngine": sum(item["pages"] for item in renders.values()),
            "visualReview": "All pages and contact sheets generated; visual acceptance must be recorded separately."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--consumer-build-directory", type=Path, action="append", required=True)
    parser.add_argument("--output-directory", type=Path, required=True)
    parser.add_argument("--soffice", type=Path, help="Explicit pinned independent DOCX reader executable")
    args = parser.parse_args()
    output = args.output_directory.resolve()
    require(output.is_relative_to(ROOT / "build"), "verification output must remain under ignored build/")
    output.mkdir(parents=True, exist_ok=False)
    office = args.soffice.resolve() if args.soffice else None
    report = {"status": "running", "consumers": []}
    try:
        report["tools"] = tools(office)
        for index, build in enumerate(args.consumer_build_directory, 1):
            report["consumers"].append(inspect(build.resolve(), output / f"consumer-{index}", office))
        report.update(status="passed" if office else "pdf-and-structure-passed-docx-reader-skipped",
                      docxReaderExecuted=office is not None,
                      boundary="No Microsoft Word edit/save check is claimed; rendered pages require separate visual review.")
    except Exception as failure:
        report.update(status="failed", failure={"type": type(failure).__name__, "message": str(failure)})
        raise
    finally:
        (output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": report["status"], "consumerModes": len(report["consumers"]),
                      "docxReaderExecuted": report["docxReaderExecuted"], "output": str(output)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
