# SPDX-License-Identifier: Apache-2.0
"""Independently check the complete offline ManuWeave example catalog and PDFs.

Run after :examples:manuWeave using the pinned verification environment and
Poppler on PATH. All pages are rendered by Poppler and PDFium by default.
The checks establish this source-backed example's documented formatting subset;
they do not certify journal submission compliance or LaTeX reproduction.
"""
from __future__ import annotations

import argparse
from collections import Counter
from concurrent.futures import ProcessPoolExecutor, as_completed
import csv
import ctypes
from dataclasses import dataclass
import hashlib
from importlib import metadata
import json
import math
from pathlib import Path
import re
import struct
import subprocess
import xml.etree.ElementTree as ET

import pypdfium2 as pdfium
from PIL import Image
from pypdf import PdfReader
from fontTools.ttLib import TTFont

from verify_advanced_pdf import difference, require
from verify_adr_report import embedded_fonts
from verify_inline_images import image_boxes
from verify_production import actual_text, contact_sheet

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "examples/src/main/resources/templates/manuweave"
GROUPS = {"Journals": 16, "Publishers": 57, "ACM": 41, "IEEE": 52,
          "Chinese journals": 10, "Elsevier": 5, "Wiley": 16,
          "More journals": 80, "General templates": 9}
POINTS_PER_MM = 72 / 25.4
# PDF coordinates and font sizes are rounded through float serialization.
GEOMETRY_TOLERANCE = .01
# Contours can overhang the declared advance box, especially italic glyphs.
GLYPH_OVERHANG = 1.5
EQUATION = r"\frac{a+b}{c+d}"
NUMBERED_PROFILES = {"comp-jasa-word", "comp-jslhr-word"}


def compact(value):
    return re.sub(r"\s+|\ufffc", "", value)


def properties(path):
    root = ET.parse(path).getroot()
    require(root.tag == "properties", f"not Java Properties XML: {path}")
    entries = root.findall("entry")
    keys = [entry.get("key") for entry in entries]
    require(all(keys) and len(set(keys)) == len(keys), f"duplicate/missing properties: {path}")
    return {entry.get("key"): entry.text or "" for entry in entries}


def number(values, key, default=None):
    value = values.get(key, default)
    require(value is not None, f"missing numeric property: {key}")
    parsed = float(value)
    require(math.isfinite(parsed), f"non-finite property: {key}")
    return parsed


def close(actual, expected, context, tolerance=GEOMETRY_TOLERANCE):
    require(abs(actual - expected) <= tolerance,
            f"{context}: actual {actual:.8f}, expected {expected:.8f} pt")


def float32(value):
    return struct.unpack("!f", struct.pack("!f", value))[0]


def read_catalog(directory):
    with (directory / "index.tsv").open(encoding="utf-8", newline="") as stream:
        rows = list(csv.reader(stream, delimiter="\t"))
    if rows and rows[0][:2] == ["group", "id"]:
        rows = rows[1:]
    require(all(len(row) == 5 for row in rows), "catalog requires five TSV fields")
    require(len(rows) == 286, "README snapshot requires exactly 286 profiles")
    require(dict(Counter(row[0] for row in rows)) == GROUPS,
            "README snapshot's nine template groups/counts differ")
    ids = [row[1] for row in rows]
    require(len(set(ids)) == len(ids), "duplicate catalog profile ID")
    require({path.stem for path in (directory / "profiles").glob("*.xml")} == set(ids),
            "catalog and profile resource files differ")
    result = {}
    for group, identifier, name, source, upstream in rows:
        require(re.fullmatch(r"[a-z0-9][a-z0-9-]*", identifier), "unsafe profile ID")
        data = properties(directory / "profiles" / f"{identifier}.xml")
        for key, expected in (("id", identifier), ("group", group), ("name", name),
                              ("source", source), ("upstream-path", upstream)):
            require(data.get(key) == expected, f"{identifier}: catalog/resource {key} differs")
        require(re.fullmatch(r"[a-f0-9]{40}", data.get("upstream.commit", "")),
                f"{identifier}: missing pinned upstream commit")
        require(re.fullmatch(r"[a-f0-9]{64}", data.get("upstream.sha256", "")),
                f"{identifier}: missing source SHA-256")
        roles = json.loads(data["role.names"])
        require(isinstance(roles, list) and len(set(roles)) == len(roles),
                f"{identifier}: invalid original role names")
        result[identifier] = data
    require(len({value["upstream.commit"] for value in result.values()}) == 1,
            "catalog mixes upstream snapshots")
    return result


@dataclass(frozen=True)
class Glyph:
    char: str
    page: int
    left: float
    top: float
    right: float
    bottom: float
    size: float
    font: str
    origin_x: float
    baseline: float


def glyphs(path):
    """Read visible text programs and character boxes through independent PDFium."""
    result = []
    with pdfium.PdfDocument(path) as document:
        for page_index in range(len(document)):
            page = document[page_index]
            try:
                height = page.get_height()
                textpage = page.get_textpage()
                try:
                    objects = {}
                    for index in range(textpage.count_chars()):
                        codepoint = pdfium.raw.FPDFText_GetUnicode(textpage, index)
                        if not codepoint:
                            continue
                        # PDFium marks a real line-ending hyphen as U+0002 in
                        # its character array. Restore only that flagged marker;
                        # the native pypdf text is checked independently below.
                        if codepoint == 2 and pdfium.raw.FPDFText_IsHyphen(textpage, index) == 1:
                            codepoint = ord("-")
                        char = chr(codepoint)
                        if char.isspace() or char == "\ufffc":
                            continue
                        text_object = textpage.get_textobj(index)
                        if text_object is None:
                            continue
                        # OutlineText has a non-painting 1 pt selection anchor. Its
                        # original TeX is checked through ActualText and vector paths,
                        # never treated as a visible prose glyph or a font-size sample.
                        if pdfium.raw.FPDFTextObj_GetTextRenderMode(text_object.raw) == 3:
                            continue
                        address = ctypes.cast(text_object.raw, ctypes.c_void_p).value
                        if address not in objects:
                            font = text_object.get_font()
                            matrix = text_object.get_matrix()
                            objects[address] = (text_object.get_font_size(), font.get_base_name(),
                                                matrix.e, height - matrix.f)
                        size, font_name, origin_x, baseline = objects[address]
                        left, bottom, right, top = textpage.get_charbox(index)
                        result.append(Glyph(char, page_index, left, height - top,
                                            right, height - bottom, size, font_name,
                                            origin_x, baseline))
                finally:
                    textpage.close()
            finally:
                page.close()
    require(result, f"{path.name}: no independently extractable text glyphs")
    return result


def samples(report):
    count = int(number(report, "sample.count"))
    require(count > 0, "sample report has no text")
    result = [(report[f"sample.{index}.role"], report[f"sample.{index}.text"])
              for index in range(count)]
    require(all(compact(text) for _, text in result), "empty sample text")
    return result


def page_facts(source, section):
    defaults = {"width": 210, "height": 297, "margin-top": 25.4,
                "margin-right": 25.4, "margin-bottom": 25.4, "margin-left": 25.4}
    result = {}
    for field, default in defaults.items():
        key = field.replace("-", "_") + "_mm"
        result[field] = number(source, f"sections.{section}_page.{key}",
                               source.get(f"page.{key}", default)) * POINTS_PER_MM
    return result


def font_metric_ratios():
    global FONT_INFO
    FONT_INFO = {}
    result = {}
    for name in (f"Liberation{family}-{variant}.ttf"
                 for family in ("Sans", "Serif")
                 for variant in ("Regular", "Bold", "Italic", "BoldItalic")):
        with TTFont(ROOT / "assets/fonts" / name) as font:
            result[name] = ((max(0, font["hhea"].ascent) + max(0, -font["hhea"].descent))
                            / font["head"].unitsPerEm)
            cmap = font.getBestCmap()
            FONT_INFO[name] = {"name": font["name"].getDebugName(6),
                               "ascent": max(0, font["hhea"].ascent) / font["head"].unitsPerEm,
                               "descent": max(0, -font["hhea"].descent) / font["head"].unitsPerEm,
                               "advances": {chr(code): font["hmtx"].metrics[glyph][0] / font["head"].unitsPerEm
                                            for code, glyph in cmap.items() if code < 128}}
    for name in ("LXGWWenKaiTC-Regular.ttf", "ZCOOLXiaoWei-Regular.ttf"):
        with TTFont(ROOT / "assets/fonts" / name) as font:
            result[name] = ((max(0, font["hhea"].ascent) + max(0, -font["hhea"].descent))
                            / font["head"].unitsPerEm)
            FONT_INFO[name] = {"name": font["name"].getDebugName(6),
                               "ascent": max(0, font["hhea"].ascent) / font["head"].unitsPerEm,
                               "descent": max(0, -font["hhea"].descent) / font["head"].unitsPerEm}
    return result


FONT_METRICS = None
FONT_INFO = None


def font_choice(requested, east_asian, bold, italic, chinese):
    """Independent bundled substitution policy; no system-font discovery."""
    variant = "BoldItalic" if bold and italic else "Bold" if bold else "Italic" if italic else "Regular"
    family = "Sans" if any(word in requested.lower() for word in (
        "sans", "arial", "helvetica", "biolinum", "calibri", "黑体")) else "Serif"
    # Bundled CJK remains the explicit regular WenKai face for every source
    # family; requested East Asian names are retained as facts, not fabricated.
    cjk_name = "LXGWWenKaiTC-Regular.ttf"
    files = [f"Liberation{family}-{variant}.ttf"] + ([cjk_name] if chinese else [])
    names = [FONT_INFO[name]["name"] for name in files]
    return {"files": files, "font-names": names, "variant": variant, "cjk": chinese}


def role_facts(source, role):
    """Compute expectations from attributed original facts, with documented example defaults."""
    prefix = f"role.{role}."
    default = 18 if role.startswith("title") else 12 if role.startswith("heading") else 10
    size = number(source, prefix + "size_pt", source.get("role.body.size_pt", default))
    bold = source.get(prefix + "bold", "false") == "true"
    italic = source.get(prefix + "italic", "false") == "true"
    cjk = source.get("example.language") == "zh"
    requested_font = source.get(prefix + "font", source.get("role.body.font", "Liberation Sans"))
    east_asian = source.get(prefix + "east_asia_font", source.get("role.body.east_asia_font", ""))
    choice = font_choice(requested_font, east_asian, bold, italic, cjk)
    ratios = [FONT_METRICS[name] for name in choice["files"]]
    requested = number(source, prefix + "line_spacing_pt",
                       size * number(source, prefix + "line_spacing", 1.4))
    first = number(source, prefix + "first_line_pt", 0)
    left = number(source, prefix + "left_indent_pt", 0)
    alignment = source.get(prefix + "alignment", "center" if role.startswith("title") else "left").upper()
    if alignment == "BOTH":
        alignment = "JUSTIFY"
    color = source.get(prefix + "font_color", "000000").replace("#", "").upper()
    if not re.fullmatch(r"[A-F0-9]{6}", color):
        color = "000000"
    return {"font-size": size, "line-height": max(requested, size * max(ratios)),
            "first-line-indent": max(0, first), "subsequent-line-indent": max(0, -first),
            "left-padding": max(0, left + min(0, first)),
            "right-padding": max(0, number(source, prefix + "right_indent_pt", 0)),
            "space-before": max(0, number(source, prefix + "space_before_pt", 0)),
            "space-after": max(0, number(source, prefix + "space_after_pt", 0)),
            "alignment": alignment, "rgb": color, **choice}


def running_facts(source, section, page):
    result = {}
    for name in ("header", "footer"):
        section_prefix = f"sections.{section}_page."
        enabled = any(key in source for key in (section_prefix + name + "_distance_mm",
                                                "page." + name + "_distance_mm"))
        enabled |= any(key.startswith(f"running.{name}.") for key in source)
        if not enabled:
            continue
        prefix = f"running.{name}."
        size = number(source, prefix + "size_pt", 8)
        choice = font_choice(source.get(prefix + "font", ""), "",
                             source.get(prefix + "bold") == "true",
                             source.get(prefix + "italic") == "true", False)
        minimum = size * max(FONT_METRICS[name] for name in choice["files"])
        if name == "footer":
            # A field's Roman-baseline graphic has a full-size float ascent;
            # the neighboring explicit font still contributes its descent.
            descent = max(FONT_INFO[name]["descent"]*size for name in choice["files"])
            minimum = max(minimum, float32(float32(size)+float32(descent)))
        line = max(number(source, prefix + "line_spacing_pt", size * 1.4), minimum)
        offset = number(source, section_prefix + name + "_distance_mm",
                        source.get("page." + name + "_distance_mm",
                                   page["margin-top" if name == "header" else "margin-bottom"] / POINTS_PER_MM)) * POINTS_PER_MM
        result[name] = {"font-size": size, "line-height": line, "minimum-line-height": minimum, "edge-offset": offset,
                        "gap": 0, **choice}
    return result


def reserved_page(source, section):
    result = page_facts(source, section)
    bands = running_facts(source, section, result)
    for name, band in bands.items():
        margin = "margin-top" if name == "header" else "margin-bottom"
        result[margin] = max(result[margin], band["edge-offset"] + band["line-height"])
    return result, bands


def section_split(source, front, body, front_running, body_running):
    kind = source.get("example.kind", "article")
    thesis = kind.endswith("thesis") or kind == "doctoral-dissertation"
    return front != body or front_running != body_running or thesis


def check_report(source, report):
    for key, value in source.items():
        require(report.get(key) == value, f"original attributed fact changed or disappeared: {key}")
    front, front_running = reserved_page(source, "front_matter")
    body, body_running = reserved_page(source, "body")
    for prefix, expected in (("effective.front-page.", front), ("effective.page.", body)):
        for field, value in expected.items():
            close(number(report, prefix + field), value, prefix + field)
    front_columns = int(number(source, "sections.front_matter_columns", source.get("page.columns", 1)))
    body_columns = int(number(source, "sections.body_columns", source.get("page.columns", 1)))
    gap = number(source, "sections.body_column_gap_mm", source.get("page.column_gap_mm", 6)) * POINTS_PER_MM
    require(number(report, "effective.front-columns") == front_columns, "front column count differs from source")
    require(number(report, "effective.body-columns") == body_columns, "body column count differs from source")
    close(number(report, "effective.column-gap"), gap, "source column gap")
    weights = json.loads(source.get("sections.body_page.column_widths_mm",
                                    source.get("page.column_widths_mm", "[]")))
    require(json.loads(report.get("effective.column-weights", "[]")) == weights,
            "explicit original column width ratios differ")
    roles = set(json.loads(source["role.names"])) | {role for role, _ in samples(report) if role != "equation_tex"}
    expected_roles = {role: role_facts(source, role) for role in roles}
    for role, expected in expected_roles.items():
        prefix = f"effective.role.{role}."
        for field, value in expected.items():
            if field in ("variant", "cjk", "files", "font-names"):
                continue
            if isinstance(value, str):
                require(report.get(prefix + field) == value, f"{role}: {field} changed from original fact")
            else:
                close(number(report, prefix + field), value, prefix + field)
        require(report.get(prefix + "font") == ";".join(expected["font-names"]),
                f"{role}: actual physical font names differ from the explicit source-family policy")
        require(report.get(prefix + "font-external") == "false",
                "this bundled-font acceptance requires no external manifest override")
    for section, bands in (("front", front_running), ("body", body_running)):
        for name, band in bands.items():
            prefix = f"effective.running.{section}.{name}."
            for field in ("font-size", "line-height", "minimum-line-height", "edge-offset", "gap"):
                close(number(report, prefix + field), band[field], prefix + field)
            require(report.get(prefix + "font") == ";".join(band["font-names"]),
                    "running physical font names differ from source-family selection")
            require(report.get(prefix + "font-external") == "false", "running font was externally overridden")
    for key, value in (("effective.pagination.min-lines-before", "2"),
                       ("effective.pagination.min-lines-after", "2"),
                       ("effective.pagination.heading-adjacency", "atomic-with-following-content"),
                       ("effective.pagination.columns-balanced", "true")):
        require(report.get(key) == value, f"explicit pagination policy differs: {key}")
    require(any(key.startswith("limitation.") for key in report),
            "example's source/projection differences must be explicitly reported")
    return front, body, front_columns, body_columns, gap, expected_roles, front_running, body_running


def check_references(source, report):
    """Derive the finite fictional article projection from original source fields."""
    reference = "extracted_format.references."
    citation = "extracted_format.citations."
    selectors = [reference + name + ".value" for name in (
        "default_citation_style", "citation_mode", "scheme", "in_text_citation_scheme", "style")]
    selectors += [citation + name + ".value" for name in ("journal_reference_family", "bibliography_style")]
    selectors += [reference + name + ".value" for name in ("bibliography_style", "label_pattern", "numbering")]
    selector = next((key for key in selectors if source.get(key, "").strip()), None)
    selected = source[selector] if selector else ""
    style = selected.lower().replace("_", "-")
    if "textual footnote" in style:
        mode = "textual-footnote"
    elif "author" in style or "year-label" in style or style in {"apa", "harvard", "chicago", "mla", "apacite"}:
        mode = "author-year"
    elif style == "eg-alpha-doi":
        mode = "alphabetic-label"
    else:
        mode = "numeric"
    provenance = f"{selector}={selected}" if selector else "no-explicit-citation-mode;example-default=numeric"
    require(report.get("effective.references.mode") == mode, "reference mode differs from the original selected citation-family fact")
    require(report.get("effective.references.source") == provenance, "reference source selector/provenance differs")
    require(report.get("effective.references.implementation") == "finite-fictional-reference-formatting-v1",
            "reference specimen must explicitly identify its finite formatting boundary")
    order = next((source[reference + key + ".value"] for key in ("order", "ordering", "entry_order")
                  if reference + key + ".value" in source), "")
    alphabetic = mode in {"author-year", "alphabetic-label"} or "author" in order or "alphabetical" in order
    if order in {"citation", "citation_order"}:
        alphabetic = False
    records = [
        {"given": "Alex", "surname": "Example", "title": "Reproducible document layout",
         "journal": "Fictional Engineering Review", "volume": "1", "number": "1", "pages": "1-8"},
        {"given": "Morgan", "surname": "Sample", "title": "Explicit fonts and bounded pagination",
         "journal": "Synthetic Systems Journal", "volume": "2", "number": "2", "pages": "9-16"},
        {"given": "Robin", "surname": "Demo", "title": "Independent verification of generated documents",
         "journal": "Example Proceedings", "volume": "3", "number": "1", "pages": "17-24"}]
    if alphabetic:
        records.sort(key=lambda item: item["surname"])
    raw_fields = source.get(reference + "article_fields.value", source.get(reference + "article_field_order.value", ""))
    fields = json.loads(raw_fields) if raw_fields else []
    require(isinstance(fields, list), "fictional reference field projection expects an explicit source list")
    include_title = source.get(reference + "article_title_included.value") != "false" and (
        not fields or "title" in fields or "article_title" in fields)
    year_position = source.get(reference + "year_position.value", "")
    if not year_position and "year" in fields and "journal" in fields:
        if fields.index("year") < fields.index("journal"):
            year_position = "after_authors"
        elif "pages" in fields and fields.index("year") < fields.index("pages"):
            year_position = "before_pages"
        else:
            year_position = "after_pages"
    if not year_position:
        year_position = "after_authors" if mode == "author-year" else "before_pages"
    name_order = source.get(reference + "author_name_order.value", "")
    expected = []
    for index, record in enumerate(records, 1):
        if "given_names" in name_order:
            author = f"{record['given']} {record['surname']}"
        elif name_order in {"initials_surname", "initials_then_surname"}:
            author = f"{record['given'][0]}. {record['surname']}"
        else:
            author = f"{record['surname']}, {record['given'][0]}."
        title = record["title"] if include_title else ""
        if title and source.get(reference + "article_title.value") == "quoted":
            title = f'"{title}"'
        prefix = author + (" (2026)." if year_position == "after_authors" else "" if author.endswith(".") else ".")
        journal = f"{record['journal']}, {record['volume']}"
        if "number" in fields:
            journal += f"({record['number']})"
        if year_position == "before_pages":
            journal += " (2026)"
        journal += f", {record['pages']}"
        if year_position == "after_pages":
            journal += " (2026)"
        label = "" if mode == "author-year" else f"[{record['surname'][:2]}26] " if mode == "alphabetic-label" else f"[{index}] "
        expected.append(label + prefix + (f" {title}. " if title else " ") + journal + ".")
    actual = [(text.split(" ", 1)[0], text.split(" ", 1)[1]) for role, text in samples(report)
              if text.startswith(("MW-R1 ", "MW-R2 ", "MW-END "))]
    require([marker for marker, _ in actual] == ["MW-R1", "MW-R2", "MW-END"], "reference specimen entry source markers changed")
    require([entry for _, entry in actual] == expected,
            "reference labels/order/name/title/year/volume/issue/page projection differs from original source facts")
    return mode, provenance


def source_ranges(sample_list, all_glyphs, identifier):
    actual = "".join(value.char for value in all_glyphs)
    expected = "".join(compact(value) for _, value in sample_list)
    require(actual == expected,
            f"{identifier}: independent PDFium text lost, duplicated, changed or reordered sample content")
    offset, result = 0, []
    for role, text in sample_list:
        token = compact(text)
        require(actual.count(token) == 1, f"{identifier}: sample text is not unique: {token[:60]}")
        result.append((role, text, all_glyphs[offset:offset + len(token)]))
        offset += len(token)
    return result


def body_start(source, sample_list, split=False):
    start = source.get("sections.body_starts_at", "document_start")
    if start == "document_start" and not split:
        return 0
    prefix = "MW-ABSTRACT-H " if start == "first_abstract" else "MW-H1 "
    found = [index for index, (_, text) in enumerate(sample_list) if text.startswith(prefix)]
    require(found, f"body transition has no matching role: {start}")
    return found[0]


def physical_page(source, settings, page):
    result = dict(settings)
    if source.get("page.mirror_margins") == "true" and (page + 1) % 2 == 0:
        result["margin-left"], result["margin-right"] = settings["margin-right"], settings["margin-left"]
    return result


def page_context(source, sample_list, all_glyphs, front, body, front_running, body_running):
    split = section_split(source, front, body, front_running, body_running)
    transition = body_start(source, sample_list, split)
    if not split:
        return transition, 0, False
    # The short structural marker is never split by the explicit kept group.
    marker = compact(sample_list[transition][1].split(" ", 1)[0])
    native = "".join(glyph.char for glyph in all_glyphs)
    require(native.count(marker) == 1, f"source section marker is not unique: {marker}")
    first_body_page = all_glyphs[native.index(marker)].page
    return transition, first_body_page, split


def in_band(y, page, band, name):
    top = band["edge-offset"] if name == "header" else page["height"] - band["edge-offset"] - band["line-height"]
    return top - GEOMETRY_TOLERANCE <= y <= top + band["line-height"] + GEOMETRY_TOLERANCE


def classify_glyphs(source, all_glyphs, first_body_page, front, body, front_running, body_running):
    result, running, numbers = [], {}, []
    for glyph in all_glyphs:
        settings = physical_page(source, front if glyph.page < first_body_page else body, glyph.page)
        bands = front_running if glyph.page < first_body_page else body_running
        band = next((name for name, spec in bands.items() if in_band(glyph.baseline, settings, spec, name)), None)
        if band:
            running.setdefault((glyph.page, band), []).append(glyph)
        elif source["id"] in NUMBERED_PROFILES and glyph.right <= settings["margin-left"] - 6 + GEOMETRY_TOLERANCE:
            numbers.append(glyph)
        else:
            result.append(glyph)
    return result, running, numbers


def check_text_geometry(ranges, source, front, body, front_columns, body_columns, gap, roles, transition):
    body_columns_used = set()
    body_line_count = 0
    weights = json.loads(source.get("sections.body_page.column_widths_mm",
                                    source.get("page.column_widths_mm", "[]")))
    for sample_index, (role, text, sample_glyphs) in enumerate(ranges):
        expected = roles[role]
        source_settings = front if sample_index < transition else body
        columns = front_columns if sample_index < transition else body_columns
        content_width = source_settings["width"] - source_settings["margin-left"] - source_settings["margin-right"]
        available = content_width - gap * (columns - 1)
        active_weights = weights if weights and columns == body_columns else [1] * columns
        widths = [available * weight / sum(active_weights) for weight in active_weights]
        for glyph in sample_glyphs:
            settings = physical_page(source, source_settings, glyph.page)
            close(glyph.size, expected["font-size"], f"{role}: actual font size")
            font = re.sub(r"^[A-Z]{6}\+", "", glyph.font)
            if ord(glyph.char) < 128:
                wanted = expected["font-names"][0]
                require(font == wanted, f"{role}: actual Latin font {font} differs from explicit variant {wanted}")
            else:
                allowed = font in expected["font-names"]
                require(allowed, f"{role}: unexpected font resource {font}")
                if "\u3400" <= glyph.char <= "\u9fff":
                    require(expected["cjk"] and font == expected["font-names"][-1],
                            f"{role}: Chinese glyph did not use the explicit CJK font")
            require(glyph.left >= settings["margin-left"] - GLYPH_OVERHANG
                    and glyph.right <= settings["width"] - settings["margin-right"] + GLYPH_OVERHANG
                    and glyph.top >= settings["margin-top"] - GLYPH_OVERHANG
                    and glyph.bottom <= settings["height"] - settings["margin-bottom"] + GLYPH_OVERHANG,
                    f"{role}: glyph leaves source page content margins (page {glyph.page + 1})")
        # Long body paragraphs expose continuation line advances, true physical columns,
        # and first/subsequent-line indents without relying on the generated report.
        if not re.match(r"MW-P\d{2}\b", text):
            continue
        lines = []
        for glyph in sample_glyphs:
            settings = physical_page(source, source_settings, glyph.page)
            origins = [settings["margin-left"] + sum(widths[:index]) + index * gap for index in range(columns)]
            column = max(index for index, origin in enumerate(origins)
                         if index == 0 or glyph.left >= origin - gap / 2)
            key = (glyph.page, column, round(glyph.baseline, 2))
            if not lines or lines[-1][0] != key:
                lines.append((key, []))
            lines[-1][1].append(glyph)
            origin = origins[column]
            require(glyph.left >= origin - GLYPH_OVERHANG
                    and glyph.right <= origin + widths[column] + GLYPH_OVERHANG,
                    f"body glyph enters source column gap: {role}")
        for line_index, (key, values) in enumerate(lines):
            page_index, column, baseline = key
            settings = physical_page(source, source_settings, page_index)
            origins = [settings["margin-left"] + sum(widths[:index]) + index * gap for index in range(columns)]
            body_columns_used.add(column)
            if expected["alignment"] in ("LEFT", "JUSTIFY"):
                wanted = origins[column] + expected["left-padding"]
                wanted += expected["first-line-indent"] if line_index == 0 else expected["subsequent-line-indent"]
                close(values[0].origin_x, wanted, f"body actual line indent ({text[:6]}, line {line_index + 1})")
            if line_index and key[:2] == lines[line_index - 1][0][:2]:
                close(baseline - lines[line_index - 1][0][2], expected["line-height"],
                      "body actual baseline interval", tolerance=.025)
        fragments = Counter(key[:2] for key, _ in lines)
        if len(fragments) > 1:
            require(all(count >= 2 for count in fragments.values()),
                    f"{text[:6]}: an observed page/column break violates the explicit 2/2 line policy")
        body_line_count += len(lines)
    require(body_line_count > 0, "no independently checked body lines")
    if body_columns > 1:
        require(body_columns_used == set(range(body_columns)),
                "sample did not exercise every declared body column")
    return {"bodyLines": body_line_count, "bodyColumnsUsed": sorted(body_columns_used)}


def compose(first, second):
    a, b, c, d, e, f = first
    g, h, i, j, k, l = second
    return (a*g+c*h, b*g+d*h, a*i+c*j, b*i+d*j, a*k+c*l+e, b*k+d*l+f)


def transformed(matrix, x, y):
    a, b, c, d, e, f = matrix
    return a*x+c*y+e, b*x+d*y+f


def cid_advance(page, resource, value):
    """Measure the actual PDF anchor from its embedded CID font's declared widths."""
    font = page["/Resources"]["/Font"][resource].get_object()
    require(font.get("/Subtype") == "/Type0" and font.get("/Encoding") == "/Identity-H",
            "logical anchor does not use an explicit horizontal CID font")
    encoded = value.original_bytes if hasattr(value, "original_bytes") else bytes(value)
    require(len(encoded) > 0 and len(encoded) % 2 == 0, "logical anchor has invalid CID text bytes")
    descendant = font["/DescendantFonts"][0].get_object()
    widths, declarations = {}, descendant.get("/W", [])
    index = 0
    while index < len(declarations):
        first, following = int(declarations[index]), declarations[index+1]
        if isinstance(following, list):
            widths.update((first+offset, float(width)) for offset, width in enumerate(following))
            index += 2
        else:
            last, width = int(following), float(declarations[index+2])
            widths.update((code, width) for code in range(first, last+1))
            index += 3
    default = float(descendant.get("/DW", 1000))
    return sum(widths.get(int.from_bytes(encoded[index:index+2], "big"), default)
               for index in range(0, len(encoded), 2)) / 1000


def marked_spans(reader):
    """Read ActualText, invisible anchors, and vector paint in its exact PDF scope."""
    result = []
    for page_index, page in enumerate(reader.pages):
        matrix, mode, text_matrix = (1, 0, 0, 1, 0, 0), 0, (1, 0, 0, 1, 0, 0)
        font, size, horizontal_scale = None, 0, 100
        graphics, marked = [], []
        height = float(page.mediabox.height)
        for operands, operator in page.get_contents().operations:
            if operator == b"q":
                graphics.append((matrix, mode, font, size, horizontal_scale))
            elif operator == b"Q":
                matrix, mode, font, size, horizontal_scale = graphics.pop()
            elif operator == b"cm":
                matrix = compose(matrix, tuple(map(float, operands)))
            elif operator == b"Tm":
                text_matrix = tuple(map(float, operands))
            elif operator == b"Tr":
                mode = int(operands[0])
            elif operator == b"Tf":
                font, size = operands[0], float(operands[1])
            elif operator == b"Tz":
                horizontal_scale = float(operands[0])
            elif operator in (b"BDC", b"BMC"):
                value = None
                if operator == b"BDC" and len(operands) > 1:
                    props = operands[1]
                    if not isinstance(props, dict):
                        props = page["/Resources"].get("/Properties", {}).get(props, {})
                    value = props.get("/ActualText")
                span = None if value is None else {"page": page_index, "text": str(value), "points": [], "paint-bounds": [],
                                                   "fills": 0, "images": 0, "shows": [], "anchor": None}
                marked.append(span)
                if span is not None:
                    result.append(span)
            elif operator == b"EMC":
                marked.pop()
            else:
                active = next((span for span in reversed(marked) if span is not None), None)
                if active is None:
                    continue
                if operator in (b"Tj", b"TJ", b"'", b'"'):
                    active["shows"].append(mode)
                    if active["anchor"] is None:
                        require(operator == b"Tj" and font is not None,
                                "outlined logical anchor must have one explicit CID string")
                        advance = cid_advance(page, font, operands[0]) * size * horizontal_scale / 100
                        effective = compose(matrix, text_matrix)
                        x, y = transformed(effective, 0, 0)
                        end_x, end_y = transformed(effective, advance, 0)
                        close(end_y, y, "logical anchor must be horizontal")
                        active["anchor"] = (x, height-y)
                        active["anchor-width"] = end_x-x
                elif operator == b"Do":
                    active["images"] += 1
                elif operator in (b"f", b"f*", b"B", b"B*"):
                    active["fills"] += 1
                    points = active["points"][active.get("last-paint", 0):]
                    if points:
                        active["paint-bounds"].append((min(x for x, _ in points), min(y for _, y in points),
                                                       max(x for x, _ in points), max(y for _, y in points)))
                    active["last-paint"] = len(active["points"])
                elif operator in (b"m", b"l", b"c", b"v", b"y"):
                    coordinates = list(map(float, operands))
                    for index in range(0, len(coordinates), 2):
                        x, y = transformed(matrix, *coordinates[index:index+2])
                        active["points"].append((x, height-y))
    return result


def check_formula(reader, report, spans, ranges, source, first_body_page, front, body,
                  front_columns, body_columns, gap, roles):
    tex_samples = [text for role, text in samples(report) if role == "equation_tex"]
    require(tex_samples == [EQUATION] and report.get("sample.equation.tex") == EQUATION,
            "fictional specimen's original TeX changed")
    formulas = [span for span in spans if span["text"] == EQUATION]
    require(len(formulas) == 1, "original equation TeX lost/duplicated in PDF ActualText")
    formula = formulas[0]
    require(formula["images"] == 0 and formula["fills"] >= 5 and len(formula["points"]) > 20,
            "display equation is not painted as nonempty vector outlines")
    require(formula["shows"] and set(formula["shows"]) == {3},
            "equation uses visible text instead of the explicit vector outline/selection anchor contract")
    points = formula["points"]
    formula["bounds"] = (min(x for x, _ in points), min(y for _, y in points),
                         max(x for x, _ in points), max(y for _, y in points))
    left, top, right, bottom = formula["bounds"]
    require(right-left > 8 and bottom-top > 10, "fraction has no meaningful two-dimensional ink")
    require(any(x2-x1 > 8 and 0 < y2-y1 <= 1 for x1, y1, x2, y2 in formula["paint-bounds"]),
            "display fraction has no independently observable thin horizontal fraction bar")
    marker = next(glyphs for _, text, glyphs in ranges if text == "MW-EQUATION")
    require({glyph.page for glyph in marker} == {formula["page"]}, "equation marker detached from its formula")
    require(top >= max(glyph.bottom for glyph in marker)-GEOMETRY_TOLERANCE,
            "equation overlaps its preceding marker")
    page = physical_page(source, front if formula["page"] < first_body_page else body, formula["page"])
    columns = front_columns if formula["page"] < first_body_page else body_columns
    available = page["width"]-page["margin-left"]-page["margin-right"]-gap*(columns-1)
    weights = json.loads(source.get("sections.body_page.column_widths_mm",
                                    source.get("page.column_widths_mm", "[]")))
    weights = weights if weights and columns == body_columns else [1]*columns
    widths = [available*weight/sum(weights) for weight in weights]
    origins = [page["margin-left"]+sum(widths[:index])+index*gap for index in range(columns)]
    marker_center = (min(glyph.left for glyph in marker)+max(glyph.right for glyph in marker))/2
    candidates = [index for index in range(columns)
                  if origins[index] <= marker_center <= origins[index]+widths[index]]
    require(len(candidates) == 1, "equation marker does not identify one source-derived physical column")
    column = candidates[0]
    key = "extracted_format.equations.alignment.value"
    requested = source.get(key, roles["equation"]["alignment"]).lower()
    alignment = {"center": "CENTER", "right": "RIGHT"}.get(requested, "LEFT")
    require(report.get("effective.equation.alignment") == alignment
            and report.get("effective.equation.alignment-source") == (key if key in source else "role.equation.alignment"),
            "equation alignment/source differs from original profile")
    logical_width = formula.get("anchor-width", 0)
    require(0 < logical_width <= widths[column], "equation has no finite natural logical width inside its column")
    offset = {"LEFT": 0, "CENTER": (widths[column]-logical_width)/2,
              "RIGHT": widths[column]-logical_width}[alignment]
    close(formula["anchor"][0], origins[column]+offset,
          "actual natural equation box alignment inside its source-derived physical column")
    require(left >= page["margin-left"]-GEOMETRY_TOLERANCE
            and right <= page["width"]-page["margin-right"]+GEOMETRY_TOLERANCE
            and top >= page["margin-top"]-GEOMETRY_TOLERANCE
            and bottom <= page["height"]-page["margin-bottom"]+GEOMETRY_TOLERANCE,
            "equation vector ink leaves the independently reserved physical body margins")
    require(actual_text(reader).count(EQUATION) == 1, "pypdf did not retain original TeX exactly once")
    return {"page": formula["page"], "vectorFills": formula["fills"], "bounds": formula["bounds"],
            "alignment": alignment, "column": column, "logicalWidth": logical_width}


def check_adjacency(ranges, images):
    checks = 0
    for index, (role, text, glyph_list) in enumerate(ranges):
        if role.startswith("heading") or role in ("abstract_heading", "references_heading"):
            following = ranges[index+1][2]
            require(glyph_list[-1].page == following[0].page, f"heading detached from following content: {text[:45]}")
            checks += 1
        if text.startswith("MW-TABLE "):
            group = ranges[index:index+9]
            require(len(group) == 9 and all(re.match(r"MW-C[1-4][1-2]\b", item[1]) for item in group[1:]),
                    "synthetic table cell source sequence changed")
            require(len({glyph.page for _, _, values in group for glyph in values}) == 1,
                    "table caption and its table split across physical pages")
            checks += 1
        if text.startswith("MW-FIGURE "):
            require(len({glyph.page for glyph in glyph_list}) == 1
                    and glyph_list[0].page == images[0]["page"], "figure and caption split across physical pages")
            require(images[0]["y"] + images[0]["height"] <= min(glyph.top for glyph in glyph_list) + GLYPH_OVERHANG,
                    "figure overlaps its caption")
            checks += 1
        if text.startswith("MW-REFS "):
            group = ranges[index:index+4]
            require(len(group) == 4 and len({glyph.page for _, _, values in group for glyph in values}) == 1,
                    "references heading and three entries split across pages")
            checks += 1
    require(checks >= 10, "sample did not exercise structural adjacency")
    return checks


def text_width(value, font_file, size, opentype=False):
    advances = [FONT_INFO[font_file]["advances"][char] for char in value]
    # Basic TrueType measurement explicitly quantizes em advances to 1/1000;
    # native OpenType footer shaping keeps the original physical font advances.
    if not opentype:
        advances = [math.floor(value*1000+.5)/1000 for value in advances]
    return sum(advances) * size


def vector_ink(reader):
    result = []
    for page_index, page in enumerate(reader.pages):
        matrix, stack, points = (1, 0, 0, 1, 0, 0), [], []
        height = float(page.mediabox.height)
        for operands, operator in page.get_contents().operations:
            if operator == b"q":
                stack.append(matrix)
            elif operator == b"Q":
                matrix = stack.pop()
            elif operator == b"cm":
                matrix = compose(matrix, tuple(map(float, operands)))
            elif operator in (b"m", b"l", b"c", b"v", b"y"):
                coordinates = list(map(float, operands))
                for index in range(0, len(coordinates), 2):
                    x, y = transformed(matrix, *coordinates[index:index+2])
                    points.append((x, height-y))
            elif operator == b"re":
                x, y, width, box_height = map(float, operands)
                for px, py in ((x, y), (x+width, y), (x, y+box_height), (x+width, y+box_height)):
                    px, py = transformed(matrix, px, py)
                    points.append((px, height-py))
            elif operator in (b"f", b"f*", b"B", b"B*", b"b", b"b*", b"S", b"s", b"n"):
                if points and operator != b"n":
                    result.append((page_index, min(x for x, _ in points), min(y for _, y in points),
                                   max(x for x, _ in points), max(y for _, y in points)))
                points = []
    return result


def check_running(source, report, pages, first_body_page, split, front, body,
                  front_running, body_running, running_glyphs, spans, vectors):
    checked = 0
    running_spans = [span for span in spans if span["text"] != EQUATION]
    used_spans = set()
    for page_index in range(pages):
        settings = physical_page(source, front if page_index < first_body_page else body, page_index)
        bands = front_running if page_index < first_body_page else body_running
        first = 0 if not split or page_index < first_body_page else first_body_page
        blank = source.get("sections.different_first_page") == "true" and page_index == first
        even = source.get("sections.different_odd_even_headers") == "true" and (page_index+1) % 2 == 0
        for name in ("header", "footer"):
            glyph_list = running_glyphs.get((page_index, name), [])
            band = bands.get(name)
            matching = [(index, span) for index, span in enumerate(running_spans)
                        if span["page"] == page_index and span["anchor"] is not None
                        and band and in_band(span["anchor"][1], settings, band, name)]
            if band is None or blank:
                require(not glyph_list and not matching, "a missing/explicit-empty running band contains content")
                continue
            authors = "Anonymous authors" if source.get("example.anonymous") == "true" else "Alex Example and Morgan Sample"
            expected = (authors if even else "Reproducible document layout") if name == "header" else f"Page {page_index+1} of {pages}"
            native = "".join(glyph.char for glyph in glyph_list)
            logical = native + "".join(span["text"] for _, span in matching)
            require(compact(logical) == compact(expected), f"page {page_index+1} {name}: final field/first/even text differs")
            for index, _ in matching:
                used_spans.add(index)
            font_file = band["files"][0]
            top = band["edge-offset"] if name == "header" else settings["height"]-band["edge-offset"]-band["line-height"]
            for glyph in glyph_list:
                close(glyph.size, band["font-size"], f"actual {name} font size")
                require(re.sub(r"^[A-Z]{6}\+", "", glyph.font) == band["font-names"][0], f"{name}: actual font differs from source selection")
                close(glyph.baseline, top + FONT_INFO[font_file]["ascent"]*band["font-size"], f"actual {name} source edge distance/baseline")
            width = settings["width"]-settings["margin-left"]-settings["margin-right"]
            if name == "header" and glyph_list:
                expected_x = settings["margin-left"] + (width-text_width(expected, font_file, band["font-size"]) if even else 0)
                close(glyph_list[0].origin_x, expected_x, "running header alignment/mirrored position")
            if name == "footer":
                require(not glyph_list and len(matching) == 1, "PAGE/PAGES footer must retain one outlined logical line")
                _, span = matching[0]
                reserved = text_width("Page  of ", font_file, band["font-size"], opentype=True) + 12*band["font-size"]
                close(span["anchor"][0], settings["margin-left"] + (width-reserved)/2, "footer source-font/reserved-field alignment")
                close(span["anchor"][1], top + .8*band["line-height"], "footer source edge distance")
                ink = [(left, upper, right, lower) for page, left, upper, right, lower in vectors
                       if page == page_index and upper >= top-GLYPH_OVERHANG
                       and lower <= top+band["line-height"]+GLYPH_OVERHANG]
                require(ink and max(right for _, _, right, _ in ink)-min(left for left, _, _, _ in ink) > 8,
                        "PAGE/PAGES footer has a logical anchor but no independently painted vector ink")
            checked += 1
    require(len(used_spans) == len(running_spans), "unclassified/duplicated outlined logical text outside equation/running regions")
    return checked


def check_physical_and_numbers(source, report, reader, ranges, number_glyphs, first_body_page, front, body):
    pages = len(reader.pages)
    numbered = source["id"] in NUMBERED_PROFILES
    require(report.get("effective.line-numbers") == str(numbered).lower(), "line-number profile selection differs from source definitions")
    require(report.get("effective.mirror-margins") == source.get("page.mirror_margins", "false"), "mirror flag differs from source")
    body_lines = []
    if numbered:
        for _, text, values in ranges:
            if re.match(r"MW-C[1-4][1-2]\b", text):
                continue
            for glyph in values:
                if not body_lines or body_lines[-1][0] != glyph.page or abs(body_lines[-1][1]-glyph.baseline) > GEOMETRY_TOLERANCE:
                    body_lines.append((glyph.page, glyph.baseline))
    actual_numbers = []
    for glyph in number_glyphs:
        if not actual_numbers or (actual_numbers[-1][0], round(actual_numbers[-1][1], 2)) != (glyph.page, round(glyph.baseline, 2)):
            actual_numbers.append((glyph.page, glyph.baseline, []))
        actual_numbers[-1][2].append(glyph)
    require(len(actual_numbers) == len(body_lines), "review line numbers lost/duplicated a real body baseline")
    close(number(report, "effective.line-number-count"), len(body_lines), "reported continuous review line count")
    close(number(report, "effective.physical.page-count"), pages, "physical page count")
    for index, ((page_index, baseline), (actual_page, actual_baseline, digits)) in enumerate(zip(body_lines, actual_numbers), 1):
        require(page_index == actual_page and "".join(glyph.char for glyph in digits) == str(index), "review line numbers are not continuous in original body source order")
        close(actual_baseline, baseline, "review number/actual text baseline")
        settings = physical_page(source, front if page_index < first_body_page else body, page_index)
        wanted_x = settings["margin-left"]-6-text_width(str(index), "LiberationSans-Regular.ttf", 7)
        close(digits[0].origin_x, wanted_x, "review line-number gutter alignment")
        for glyph in digits:
            close(glyph.size, 7, "review line-number explicit size")
            require(re.sub(r"^[A-Z]{6}\+", "", glyph.font) == "LiberationSans", "unexpected review number font")
        prefix = f"effective.line-number.{index}."
        close(number(report, prefix+"page"), page_index+1, "reported number page")
        close(number(report, prefix+"x"), wanted_x, "reported number x")
        close(number(report, prefix+"baseline"), baseline, "reported number baseline")
    counts = Counter(page for page, _ in body_lines)
    for page_index in range(pages):
        original = front if page_index < first_body_page else body
        settings = physical_page(source, original, page_index)
        prefix = f"effective.physical.page.{page_index+1}."
        for key in ("margin-left", "margin-right", "margin-top", "margin-bottom"):
            close(number(report, prefix+key), settings[key], f"source-derived {prefix+key}")
        close(number(report, prefix+"translation-x"), settings["margin-left"]-original["margin-left"], "source-derived mirror translation")
        close(number(report, prefix+"displayed-number"), page_index+1, "displayed page number")
        close(number(report, prefix+"line-number-count"), counts[page_index], "per-page review line count")
        shift = settings["margin-left"]-original["margin-left"]
        if shift:
            operations = reader.pages[page_index].get_contents().operations
            require(len(operations) > 4 and operations[0][1] == b"cm"
                    and operations[1][1] == b"q" and operations[2][1] == b"cm"
                    and operations[-1][1] == b"Q", "mirrored page lacks its complete physical graphics-state envelope")
            for actual, wanted in zip(map(float, operations[2][0]), (1, 0, 0, 1, shift, 0)):
                close(actual, wanted, "actual full-page physical mirror transform")
            depth = 0
            for index, (_, operator) in enumerate(operations[1:], 1):
                depth += (operator == b"q") - (operator == b"Q")
                require(depth > 0 or index == len(operations)-1,
                        "some page drawing escapes the source-derived mirrored margin transform")
    return len(body_lines)


def independent_logical_text(reader, source, first_body_page, front, body, front_running, body_running):
    result = []
    for page_index, page in enumerate(reader.pages):
        mode, stack = 0, []
        settings = physical_page(source, front if page_index < first_body_page else body, page_index)
        bands = front_running if page_index < first_body_page else body_running
        def operand(operator, operands, _cm, _tm):
            nonlocal mode
            if operator == b"q":
                stack.append(mode)
            elif operator == b"Q":
                mode = stack.pop()
            elif operator == b"Tr":
                mode = int(operands[0])
            elif operator == b"BDC" and len(operands) > 1:
                props = operands[1]
                if not isinstance(props, dict):
                    props = page["/Resources"].get("/Properties", {}).get(props, {})
                if str(props.get("/ActualText", "")) == EQUATION:
                    result.append(EQUATION)
        def text(value, cm, tm, _font, size):
            if not compact(value) or mode == 3:
                return
            x, y = transformed(compose(cm, tm), 0, 0)
            baseline = settings["height"]-y
            if any(in_band(baseline, settings, band, name) for name, band in bands.items()):
                return
            if source["id"] in NUMBERED_PROFILES and size == 7 and x < settings["margin-left"]-6+GEOMETRY_TOLERANCE:
                return
            result.append(value)
        page.extract_text(visitor_operand_before=operand, visitor_text=text)
    return "".join(result)


def verify(identifier, source, fixtures, target, render):
    target.mkdir(parents=True, exist_ok=True)
    report = properties(fixtures / f"{identifier}-report.xml")
    front, body, front_columns, body_columns, gap, roles, front_running, body_running = check_report(source, report)
    reference_mode, reference_source = check_references(source, report)
    sample_list = samples(report)
    source_text = (fixtures / f"{identifier}-source.txt").read_text(encoding="utf-8")
    require(compact(source_text) == "".join(compact(text) for _, text in sample_list),
            "source text file and sample report disagree")
    path = fixtures / f"{identifier}.pdf"
    reader = PdfReader(path)
    require(not reader.is_encrypted and len(reader.pages) > 0, "sample PDF is empty/encrypted")
    if source.get("example.anonymous") == "true":
        require(not str((reader.metadata or {}).get("/Author", "")), "anonymous PDF retains author metadata")
        require("Alex Example and Morgan Sample" not in actual_text(reader),
                "anonymous PDF reveals fictional author names outside its withheld body")
    require(len(reader.pages) == number(report, "effective.pages"), "reported page count differs")
    all_glyphs = glyphs(path)
    transition, first_body_page, split = page_context(source, sample_list, all_glyphs, front, body,
                                                      front_running, body_running)
    prose_glyphs, running_glyphs, number_glyphs = classify_glyphs(source, all_glyphs, first_body_page,
                                                               front, body, front_running, body_running)
    visible_samples = [(role, text) for role, text in sample_list if role != "equation_tex"]
    ranges = source_ranges(visible_samples, prose_glyphs, identifier)
    for page_index, page in enumerate(reader.pages):
        settings = front if page_index < first_body_page else body
        close(float(page.mediabox.width), settings["width"], "PDF MediaBox width")
        close(float(page.mediabox.height), settings["height"], "PDF MediaBox height")
        require(int(page.get("/Rotate", 0)) == 0, "unexpected page rotation")
    geometry = check_text_geometry(ranges, source, front, body, front_columns, body_columns, gap, roles, transition)
    # pypdf independently confirms source text as well as native-font embedding.
    logical = independent_logical_text(reader, source, first_body_page, front, body, front_running, body_running)
    require(compact(logical) == "".join(compact(text) for _, text in sample_list),
            "independent pypdf source text differs")
    fonts = embedded_fonts(reader)
    images = image_boxes(reader)
    require(len(images) == int(number(report, "image.count")) == 1, "sample figure image lost/repeated")
    for image in images:
        require(all(value > 0 for value in image["pixels"])
                and image["width"] > 0 and image["height"] > 0, "sample image has no pixels/area")
    spans = marked_spans(reader)
    formula = check_formula(reader, report, spans, ranges, source, first_body_page, front, body,
                            front_columns, body_columns, gap, roles)
    adjacency = check_adjacency(ranges, images)
    running_count = check_running(source, report, len(reader.pages), first_body_page, split, front, body,
                                  front_running, body_running, running_glyphs, spans, vector_ink(reader))
    line_count = check_physical_and_numbers(source, report, reader, ranges, number_glyphs,
                                            first_body_page, front, body)
    metrics = check_renders(path, target, len(reader.pages)) if render else []
    return {"pages": len(reader.pages), "samples": len(sample_list), "fonts": fonts,
            "images": images, "sourceSha256": source["upstream.sha256"],
            "pdfSha256": hashlib.sha256(path.read_bytes()).hexdigest(), **geometry,
            "formula": formula, "adjacencyChecks": adjacency,
            "runningRegionsChecked": running_count, "reviewLines": line_count,
            "referencesMode": reference_mode, "referencesSource": reference_source,
            "crossRendererDifferences": metrics}


def initialize_worker():
    global FONT_METRICS
    FONT_METRICS = font_metric_ratios()


def render_poppler_bounded(path, target, pages):
    target.mkdir(parents=True, exist_ok=True)
    # Per-document timeout prevents one external renderer from hanging the run.
    subprocess.run(["pdftoppm", "-r", "192", "-png", str(path), str(target / "page")],
                   check=True, capture_output=True, timeout=max(60, pages * 20))
    expected = [target / f"page-{index:0{len(str(pages))}d}.png" for index in range(1, pages + 1)]
    require(all(path.is_file() for path in expected), "Poppler did not render every current page")
    return expected


def render_pdfium_dense(path, target):
    target.mkdir(parents=True, exist_ok=True)
    paths = []
    with pdfium.PdfDocument(path) as document:
        for index in range(len(document)):
            page = document[index]
            try:
                scale = 192 / 72
                width = math.ceil(page.get_width() * scale)
                height = math.ceil(page.get_height() * scale)
                bitmap = pdfium.PdfBitmap.new_native(width, height, format=pdfium.raw.FPDFBitmap_BGR)
                try:
                    bitmap.fill_rect((255, 255, 255, 255), 0, 0, width, height)
                    # PdfPage.render fits the page into the rounded canvas and
                    # stretches fractional page sizes by up to one pixel. Use
                    # the exact DPI matrix, matching Poppler's physical scale.
                    matrix = pdfium.raw.FS_MATRIX(scale, 0, 0, scale, 0, 0)
                    clip = pdfium.raw.FS_RECTF(0, 0, width, height)
                    pdfium.raw.FPDF_RenderPageBitmapWithMatrix(
                        bitmap, page, ctypes.byref(matrix), ctypes.byref(clip), pdfium.raw.FPDF_ANNOT)
                    output = target / f"page-{index + 1}.png"
                    bitmap.to_pil().convert("RGB").save(output)
                    paths.append(output)
                finally:
                    bitmap.close()
            finally:
                page.close()
    return paths


def check_renders(path, target, pages):
    first = render_poppler_bounded(path, target / "poppler", pages)
    second = render_pdfium_dense(path, target / "pdfium")
    require(len(first) == len(second) == pages, "independent renderer page counts differ")
    # Dense small type uses the existing ADR protocol: 192 DPI and a two-pixel
    # blur (the same physical radius as one pixel at 96 DPI). Thresholds remain
    # unchanged; increased sampling reduces renderer-specific font hinting.
    metrics = [difference(a, b, 2) for a, b in zip(first, second, strict=True)]
    require(all(value["meanAbsoluteChannelError"] < 4
                and value["changedPixelFraction"] < .06 for value in metrics),
            f"{path.name}: Poppler/PDFium renderings differ materially")
    for rendered in first + second:
        with Image.open(rendered) as image:
            image.load()
            require(image.width > 0 and image.height > 0, "invalid rendered image")
    contact_sheet([(f"{path.stem} / {index}", item) for index, item in enumerate(first, 1)], target)
    return metrics


def main():
    global FONT_METRICS
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixtures", type=Path, default=ROOT / "examples/build/manuweave")
    parser.add_argument("--catalog", type=Path, default=CATALOG)
    parser.add_argument("--output", type=Path, default=ROOT / "build/verification/manuweave")
    parser.add_argument("--samples", nargs="+", metavar="PROFILE_ID")
    parser.add_argument("--jobs", type=int, default=1,
                        help="Independent verification processes (1-8); PDFium state is never shared across threads.")
    parser.add_argument("--skip-render", action="store_true",
                        help="Structural negative controls only; skips both renderers explicitly.")
    args = parser.parse_args()
    require(1 <= args.jobs <= 8, "--jobs must be between 1 and 8")
    FONT_METRICS = font_metric_ratios()
    catalog = read_catalog(args.catalog)
    selected = args.samples or list(catalog)
    require(len(set(selected)) == len(selected) and set(selected) <= catalog.keys(),
            "unknown or duplicate selected profile IDs")
    args.output.mkdir(parents=True, exist_ok=True)
    tools = {name: metadata.version(name) for name in ("pypdf", "pypdfium2", "Pillow", "fonttools")}
    if not args.skip_render:
        version = subprocess.run(["pdftoppm", "-v"], capture_output=True, text=True,
                                 check=True, timeout=15)
        tools["poppler"] = (version.stderr or version.stdout).splitlines()[0]
    results = {}
    if args.jobs == 1:
        for index, identifier in enumerate(selected, 1):
            results[identifier] = verify(identifier, catalog[identifier], args.fixtures,
                                         args.output / identifier, not args.skip_render)
            print(f"[{index}/{len(selected)}] {identifier}: {results[identifier]['pages']} pages", flush=True)
    else:
        executor = ProcessPoolExecutor(max_workers=args.jobs, initializer=initialize_worker)
        pending = {}
        try:
            for identifier in selected:
                future = executor.submit(verify, identifier, catalog[identifier], args.fixtures,
                                         args.output / identifier, not args.skip_render)
                pending[future] = identifier
            for index, future in enumerate(as_completed(pending), 1):
                identifier = pending[future]
                results[identifier] = future.result()
                print(f"[{index}/{len(selected)}] {identifier}: {results[identifier]['pages']} pages", flush=True)
        finally:
            executor.shutdown(wait=True, cancel_futures=True)
        results = {identifier: results[identifier] for identifier in selected}
    summary = {"catalogProfiles": len(catalog), "groups": GROUPS, "checkedProfiles": len(results),
               "jobs": args.jobs,
               "rendering": "skipped explicitly" if args.skip_render else "Poppler and PDFium, all pages",
               "renderComparison": {"dpi": 192, "blurPixels": 2, "meanLimit": 4,
                                    "changedFractionLimit": .06},
               "upstreamCommit": next(iter(catalog.values()))["upstream.commit"],
               "tools": tools, "profiles": results}
    (args.output / "report.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
                                            encoding="utf-8")


if __name__ == "__main__":
    main()
