# SPDX-License-Identifier: Apache-2.0
"""Independently verify all six fictional common-document PDF examples.

Run after :examples:commonDocuments, using requirements-verification.txt and
Poppler on PATH. Content checks independently bind the exported XML/JSON;
Designer tests separately check catalog entries and editable bindings.
"""
from __future__ import annotations

import argparse
from collections import Counter
from dataclasses import dataclass, field
from decimal import Decimal, ROUND_HALF_UP
import hashlib
from io import BytesIO
from importlib import metadata
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

import pypdfium2 as pdfium
from PIL import Image
from pypdf import PdfReader
from pypdf.generic import IndirectObject, NameObject

from verify_adr_report import cell_rectangles, embedded_fonts, render
from verify_advanced_pdf import difference, require
from verify_multi_floats import pdf_geometry
from verify_inline_images import image_boxes
from verify_production import contact_sheet

ROOT = Path(__file__).resolve().parents[1]
DOCUMENTS = ("service-contract", "resume", "user-manual", "quotation", "meeting-minutes", "weekly-report")
# Measured after rendering the expanded source/data with the explicit example fonts.
PAGES = {"service-contract": 7, "resume": 2, "user-manual": 8,
         "quotation": 4, "meeting-minutes": 3, "weekly-report": 4}
EXPRESSION = re.compile(r"\$\{([A-Za-z_][A-Za-z_0-9.]*)\}")
TEXT_BLOCKS = {"p", "heading", "bullet", "numbered", "cell"}
GEOMETRY_TOLERANCE = .003  # pt: serialized PDF coordinates, independent of raster tolerances.
GLYPH_TOLERANCE = .6  # pt: glyph contour versus declared body/cell edges.


@dataclass(frozen=True)
class SourceText:
    text: str
    kind: str
    table: str = ""
    header: bool = False
    list_id: str = ""
    level: int = 1
    restart: int = 0


@dataclass
class TablePlan:
    id: str
    columns: list
    nested: bool
    headers: list = field(default_factory=list)
    rows: list = field(default_factory=list)


def compact(text):
    return re.sub(r"\s+|\ufffc", "", text)


def resolve(scope, path):
    value = scope
    for part in path.split("."):
        value = value[part]
    return value


def bind(text, scope):
    return EXPRESSION.sub(lambda match: str(resolve(scope, match.group(1))), text or "")


def inline(element, scope, page_number=0, page_count=0):
    """Expand only the text/field syntax actually used by these six examples."""
    result = bind(element.text, scope)
    for child in element:
        if child.tag == "page-number":
            result += str(page_number)
        elif child.tag == "page-count":
            result += str(page_count)
        elif child.tag in ("span", "link"):
            result += inline(child, scope, page_number, page_count)
        elif child.tag in ("style-if", "image"):
            pass  # Formatting rules and image/alt resources do not add document text.
        else:
            raise AssertionError(f"unsupported inline element in verification fixture: {child.tag}")
        result += bind(child.tail, scope)
    return result


def expected_blocks(element, scope, tables=None, table="", header=False):
    """Expand only these samples' declared content; unknown constructs fail explicitly."""
    if tables is None:
        tables = []
    tag = element.tag
    if tag == "document":
        return [text for child in element if child.tag not in ("page", "header", "footer")
                for text in expected_blocks(child, scope, tables)]
    if tag in TEXT_BLOCKS:
        if tag == "cell" and any(child.tag in ("p", "table") for child in element):
            require(all(child.tag in ("p", "table") for child in element), "mixed inline/block cell in verification fixture")
            return [text for child in element for text in expected_blocks(child, scope, tables, table, header)]
        return [SourceText(inline(element, scope), tag, table, header,
                           element.get("list-id", ""), int(element.get("level", 1)), int(element.get("restart", 0)))]
    if tag in ("each", "list"):
        return [text for item in resolve(scope, element.attrib["items"])
                for child in element for text in expected_blocks(child, {**scope, element.attrib["as"]: item}, tables, table, header)]
    if tag == "if":
        condition = resolve(scope, element.attrib["test"])
        require(type(condition) is bool, "sample conditional is not a boolean binding")
        branch = element.find("then" if condition else "else")
        return [] if branch is None else expected_blocks(branch, scope, tables, table, header)
    if tag in ("table", "rows"):
        plan = TablePlan(element.attrib.get("id", ""), list(element.find("columns")), bool(table))
        tables.append(plan)
        result, occupied = [], set()
        for row_index, row in enumerate(element.findall("header")):
            column = 0
            for cell in row:
                while (row_index, column) in occupied:
                    column += 1
                column_span, row_span = int(cell.get("column-span", 1)), int(cell.get("row-span", 1))
                text = inline(cell, scope)
                plan.headers.append({"row": row_index, "column": column, "columnSpan": column_span,
                                     "rowSpan": row_span, "text": compact(text)})
                occupied.update((r, c) for r in range(row_index, row_index + row_span)
                                for c in range(column, column + column_span))
                result.extend(expected_blocks(cell, scope, tables, plan.id, True))
                column += column_span
        bindings = [{**scope, element.attrib["as"]: item} for item in resolve(scope, element.attrib["items"])] if tag == "rows" else [scope]
        for binding in bindings:
            for row in element.findall("row"):
                texts = [text for cell in row for text in expected_blocks(cell, binding, tables, plan.id)]
                plan.rows.append([compact(text.text) for text in texts if compact(text.text)])
                result.extend(texts)
        return result
    if tag in ("row", "then", "else"):
        return [text for child in element for text in expected_blocks(child, scope, tables, table, header)]
    raise AssertionError(f"unsupported block element in verification fixture: {tag}")


def source_contract(name, output):
    source = ROOT / "examples/src/main/resources/templates/common" / f"{name}.xml"
    exported = output / f"{name}.xml"
    require(source.read_bytes() == exported.read_bytes(), f"{name}: exported XML is stale or changed")
    tree = ET.parse(exported).getroot()
    ids = [element.attrib["id"] for element in tree.iter() if "id" in element.attrib]
    require(len(ids) == len(set(ids)), f"{name}: duplicate source block IDs")
    require(not any(element.tag in ("page-break", "section")
                    or element.attrib.get("page-break-before", "false") == "true" for element in tree.iter()),
            f"{name}: source forces page breaks instead of natural pagination")
    require(tree.find(".//rows") is not None or tree.find(".//each") is not None,
            f"{name}: example does not demonstrate repeated data binding")
    data = json.loads((output / f"{name}.json").read_text(encoding="utf-8"))
    original_data = json.loads(source.with_suffix(".json").read_text(encoding="utf-8"))
    require(data == original_data, f"{name}: exported binding data differs from its source")
    expected = expected_blocks(tree, data)
    require(expected and all("${" not in text.text for text in expected), f"{name}: unresolved template expression")
    return tree, data, expected, len(ids)


def page_settings(tree):
    page = tree.find("page").attrib
    margin = float(page.get("margin", 0))
    margins = {side: float(page.get("margin-" + side, margin)) for side in ("top", "right", "bottom", "left")}
    header, footer = tree.find("header"), tree.find("footer")
    require(footer is not None and len(footer) == 1 and footer[0].tag == "p", "sample footer must contain one paragraph")
    require(header is None or len(header) == 1 and header[0].tag in ("p", "table"),
            "sample header must contain one paragraph or one static table")
    return margins, header, footer


def region_text(region, data, page, count):
    if region is None:
        return ""
    if region[0].tag == "p":
        return compact(inline(region[0], data, page, count))
    return compact("".join(value.text for value in expected_blocks(region[0], data)))


def check_images(name, reader, tree, output):
    """Check painted invocations, source pixels and independently decoded bounds."""
    specifications = {"service-contract": ("contract_logo", (256, 256)),
                      "resume": ("resume_portrait", (384, 480))}
    declared = list(tree.iter("image"))
    painted = image_boxes(reader)
    if name not in specifications:
        require(not declared and not painted, f"{name}: unexpected image in this text-only example")
        return []
    reference, pixels = specifications[name]
    require(len(declared) == 1 and declared[0].get("ref") == reference and declared[0].get("alt", "").strip(),
            f"{name}: missing image binding or alternative description")
    width, height = float(declared[0].attrib["width"]), float(declared[0].attrib["height"])
    require(abs(width / height - pixels[0] / pixels[1]) < .000001, f"{name}: source image is stretched")
    source = ROOT / "examples/src/main/resources/templates/common/assets" / f"{reference}.png"
    encoded = source.read_bytes()
    require((output / "images" / source.name).read_bytes() == encoded, f"{name}: exported image bytes changed")
    notice = source.with_name(f"{reference}-license.txt")
    require((output / "licenses" / notice.name).read_bytes() == notice.read_bytes(),
            f"{name}: exported image source/license notice changed")
    with Image.open(BytesIO(encoded)) as original:
        require(original.format == "PNG" and original.size == pixels, f"{name}: original image format/pixel dimensions changed")
        with original.convert("RGBA") as rgba:
            expected_pixels = rgba.tobytes()
    margins, header, _ = page_settings(tree)
    contract = name == "service-contract"
    expected_pages = list(range(len(reader.pages))) if contract else [0]
    require([image["page"] for image in painted] == expected_pages,
            f"{name}: first-page portrait or repeated header logo was lost/duplicated")
    for index, page in enumerate(reader.pages):
        require(len(page.images) == int(index in expected_pages), f"{name}: unexpected/missing embedded image on page {index + 1}")
        if index not in expected_pages:
            continue
        # pypdf 6.10 passes an indirect /DecodeParms to FlateDecode without
        # resolving it, which skips a valid PNG predictor. Resolve that same
        # dictionary in memory before extraction; retain exact pixel equality.
        for reference_object in page["/Resources"]["/XObject"].values():
            resource = reference_object.get_object()
            if isinstance(resource.get("/DecodeParms"), IndirectObject):
                resource[NameObject("/DecodeParms")] = resource["/DecodeParms"]
        with Image.open(BytesIO(page.images[0].data)) as embedded:
            with embedded.convert("RGBA") as rgba:
                require(embedded.size == pixels and rgba.tobytes() == expected_pixels,
                        f"{name}: embedded image pixels differ from the original PNG on page {index + 1}")
    expected_x = margins["left"] if contract else 595.3 - margins["right"] - width
    top = margins["top"] if contract else margins["top"] + float(header.attrib["height"]) + float(header.get("gap", 0))
    parent = next(parent for parent in tree.iter() if declared[0] in list(parent))
    available_height = float(header.attrib["height"]) if contract else float(parent.attrib["line-height"])
    for image in painted:
        require(image["pixels"] == list(pixels) and abs(image["width"] - width) < GEOMETRY_TOLERANCE
                and abs(image["height"] - height) < GEOMETRY_TOLERANCE,
                f"{name}: painted image dimensions differ from the XML declaration")
        require(abs(image["x"] - expected_x) < GEOMETRY_TOLERANCE
                and top - GEOMETRY_TOLERANCE <= image["y"]
                and image["y"] + height <= top + available_height + GEOMETRY_TOLERANCE,
                f"{name}: image left/right alignment or header/identity-row placement changed")
    if contract:
        require(all(all(abs(image[key] - painted[0][key]) < GEOMETRY_TOLERANCE for key in ("x", "y", "width", "height"))
                    for image in painted), f"{name}: repeated logo geometry differs between pages")
    with pdfium.PdfDocument(output / f"{name}.pdf") as document:
        for index in range(len(document)):
            page = document[index]
            try:
                independent = list(page.get_objects(filter=[pdfium.raw.FPDF_PAGEOBJ_IMAGE]))
                expected = [image for image in painted if image["page"] == index]
                require(len(independent) == len(expected), f"{name}: PDFium image invocation count disagrees")
                for image, wanted in zip(independent, expected, strict=True):
                    left, bottom, right, top_edge = image.get_bounds()
                    actual = {"x": left, "y": 841.9 - top_edge, "width": right - left, "height": top_edge - bottom}
                    require(all(abs(actual[key] - wanted[key]) < GEOMETRY_TOLERANCE for key in actual),
                            f"{name}: pypdf/PDFium painted image bounds differ")
            finally:
                page.close()
    return [{**image, "reference": reference, "sourceSha256": hashlib.sha256(encoded).hexdigest(),
             "rgbaSha256": hashlib.sha256(expected_pixels).hexdigest()} for image in painted]


def quotation_math(data):
    money = lambda value: Decimal(value.replace(",", ""))
    items, quote = data["items"], data["quote"]
    amounts = []
    for item in items:
        calculated = (money(item["quantity"]) * money(item["unitPrice"])).quantize(Decimal(".01"), rounding=ROUND_HALF_UP)
        require(calculated == money(item["amount"]), f"quotation: incorrect amount for item {item['number']}")
        amounts.append(calculated)
    subtotal = sum(amounts, Decimal("0.00"))
    rate = money(quote["taxRate"].removesuffix("%")) / 100
    tax = (subtotal * rate).quantize(Decimal(".01"), rounding=ROUND_HALF_UP)
    require(subtotal == money(quote["subtotal"]), "quotation: incorrect subtotal")
    require(tax == money(quote["tax"]), "quotation: incorrect tax")
    require(subtotal + tax == money(quote["total"]), "quotation: incorrect total")
    discount = (subtotal * money(quote["discountRate"].removesuffix("%")) / 100).quantize(Decimal(".01"), rounding=ROUND_HALF_UP)
    net = subtotal - discount
    discounted_tax = (net * rate).quantize(Decimal(".01"), rounding=ROUND_HALF_UP)
    require(discount == money(quote["discount"]), "quotation: incorrect discount")
    require(net == money(quote["net"]), "quotation: incorrect discounted subtotal")
    require(discounted_tax == money(quote["discountedTax"]), "quotation: incorrect discounted tax")
    require(net + discounted_tax == money(quote["payable"]), "quotation: incorrect payable amount")
    return {"lineAmounts": [str(amount) for amount in amounts], "subtotal": str(subtotal),
            "tax": str(tax), "total": str(subtotal + tax), "discount": str(discount), "net": str(net),
            "discountedTax": str(discounted_tax), "payable": str(net + discounted_tax)}


def contract_math(data):
    money = lambda value: Decimal(value.replace(",", ""))
    contract = data["contract"]
    amounts = []
    for item in data["lineItems"]:
        amount = (money(item["quantity"]) * money(item["unitPrice"])).quantize(Decimal(".01"), rounding=ROUND_HALF_UP)
        require(amount == money(item["amount"]), f"service-contract: incorrect amount for {item['code']}")
        amounts.append(amount)
    subtotal = sum(amounts, Decimal("0.00"))
    tax = (subtotal * money(contract["taxRate"].removesuffix("%")) / 100).quantize(Decimal(".01"), rounding=ROUND_HALF_UP)
    total = subtotal + tax
    require(subtotal == money(contract["subtotal"]), "service-contract: incorrect subtotal")
    require(tax == money(contract["tax"]), "service-contract: incorrect tax")
    require(total == money(contract["total"]), "service-contract: incorrect total")
    percentages, payments = [], []
    for payment in data["payments"]:
        percent = money(payment["percent"].removesuffix("%"))
        amount = (total * percent / 100).quantize(Decimal(".01"), rounding=ROUND_HALF_UP)
        require(amount == money(payment["amount"]), f"service-contract: incorrect payment for {payment['stage']}")
        percentages.append(percent)
        payments.append(amount)
    require(sum(percentages) == 100, "service-contract: payment percentages do not total 100%")
    require(sum(payments) == total, "service-contract: payments do not total the contract amount")
    return {"lineAmounts": [str(amount) for amount in amounts], "subtotal": str(subtotal),
            "tax": str(tax), "total": str(total), "paymentAmounts": [str(amount) for amount in payments],
            "paymentPercentages": [str(percent) for percent in percentages]}


def contains(outer, inner, tolerance=GEOMETRY_TOLERANCE):
    return (outer["x"] - tolerance <= inner["x"]
            and outer["y"] - tolerance <= inner["y"]
            and inner["x"] + inner["width"] <= outer["x"] + outer["width"] + tolerance
            and inner["y"] + inner["height"] <= outer["y"] + outer["height"] + tolerance)


def check_cell_nesting(name, cells):
    nested = []
    for index, first in enumerate(cells):
        for second in cells[index + 1:]:
            if first["page"] != second["page"]:
                continue
            overlap_x = min(first["x"] + first["width"], second["x"] + second["width"]) - max(first["x"], second["x"])
            overlap_y = min(first["y"] + first["height"], second["y"] + second["height"]) - max(first["y"], second["y"])
            if overlap_x < GEOMETRY_TOLERANCE or overlap_y < GEOMETRY_TOLERANCE:
                continue
            # Nested tables own independent cells inside a parent's padded viewport.
            # Equal/partly intersecting rectangles still fail; containment is not overlap.
            if contains(first, second) and not contains(second, first):
                nested.append((first, second))
            elif contains(second, first) and not contains(first, second):
                nested.append((second, first))
            else:
                raise AssertionError(f"{name}: bordered cells cross or duplicate one another: {first} / {second}")
    return len(nested)


def column_geometry(plan, width):
    fixed = sum(float(column.get("width", 0)) for column in plan.columns)
    fractions = sum(float(column.get("weight", 0)) for column in plan.columns)
    require(all("width" in column.attrib or "weight" in column.attrib for column in plan.columns),
            "verification fixtures must use explicit fixed/fractional columns")
    return [float(column.get("width")) if "width" in column.attrib else (width - fixed) * float(column.get("weight")) / fractions
            for column in plan.columns]


def match_header(plan, page_cells, left, width):
    """Match every declared header anchor's columns, row span and row boundaries."""
    columns = column_geometry(plan, width)
    expected = [{**header, "x": left + sum(columns[:header["column"]]),
                 "width": sum(columns[header["column"]:header["column"] + header["columnSpan"]])}
                for header in plan.headers]

    def matches(header, cell):
        return (header["text"] == cell["text"] and abs(header["x"] - cell["x"]) < GEOMETRY_TOLERANCE
                and abs(header["width"] - cell["width"]) < GEOMETRY_TOLERANCE)

    first = expected[0]
    for initial in (cell for cell in page_cells if matches(first, cell)):
        top = initial["y"]
        boundaries, selected = {0: top}, []
        for header in expected:
            candidates = [cell for cell in page_cells if matches(header, cell)
                          and cell["y"] >= top - GEOMETRY_TOLERANCE]
            candidate = next((cell for cell in candidates
                              if (header["row"] not in boundaries or abs(cell["y"] - boundaries[header["row"]]) < GEOMETRY_TOLERANCE)
                              and (header["row"] + header["rowSpan"] not in boundaries
                                   or abs(cell["y"] + cell["height"] - boundaries[header["row"] + header["rowSpan"]]) < GEOMETRY_TOLERANCE)), None)
            if candidate is None:
                break
            boundaries[header["row"]] = candidate["y"]
            boundaries[header["row"] + header["rowSpan"]] = candidate["y"] + candidate["height"]
            selected.append(candidate)
        else:
            keys = sorted(boundaries)
            if keys == list(range(max(keys) + 1)) and all(boundaries[a] < boundaries[b] for a, b in zip(keys, keys[1:])):
                return selected
    return []


def unique_probes(expected):
    """Use meaningful exact body records/clauses, excluding short reusable labels."""
    bodies = [compact(value.text) for value in expected if not value.header and compact(value.text)]
    headers = [compact(value.text) for value in expected if value.header]
    source = "\0".join(bodies)
    candidates = set()
    for text in bodies:
        # Stable identifiers prove individual records without confusing repeated numeric cells.
        candidates.update(re.findall(r"(?<![A-Za-z0-9-])[A-Za-z]{1,12}-[0-9]{2,}(?:-[A-Za-z0-9]+)*", text))
        for phrase in re.split(r"[。；！？\n]", text):
            if 16 <= len(phrase) <= 160:
                candidates.add(phrase)
            elif len(phrase) > 160:
                candidates.update(part for part in re.split(r"[，,;]", phrase) if 16 <= len(part) <= 96)
    candidates = [token for token in sorted(candidates) if not any(token in header for header in headers)]
    require(candidates, "expanded sample has no unique body records or clauses to check")
    return {token: source.count(token) for token in candidates}


def repeated_headers(name, tree, data, expected, pages, cell_texts, margins):
    tables = []
    expected_blocks(tree, data, tables)
    body_source = "\0".join(compact(value.text) for value in expected if not value.header)
    matched, long_tables, header_regions = {}, [], [[] for _ in pages]
    for plan in tables:
        if not plan.headers or plan.nested:
            continue
        row_pages = set()
        row_codes = []
        for row in plan.rows:
            codes = [token for text in row
                     for token in re.findall(r"(?<![A-Za-z0-9-])[A-Za-z]{1,12}-[0-9]{2,}(?:-[A-Za-z0-9]+)*", text)
                     if body_source.count(token) == 1]
            if codes:
                row_codes.append(codes[0])
            probes = list(codes)
            for text in sorted(row, key=len, reverse=True):
                if len(text) < 12:
                    continue
                phrases = []
                for token in (text[:32], text[-32:]):
                    if body_source.count(token) == 1:
                        phrases.append(token)
                if phrases:
                    probes.extend(phrases)
                    break
            for token in probes:
                row_pages.update(index for index, text in enumerate(pages) if token in compact(text))
        if not row_pages:
            # Small tables may contain only shared labels/numbers; they remain covered
            # by content, non-overlap and glyph-in-cell checks rather than guessed owners.
            continue
        located = []
        for index in sorted(row_pages):
            cells = match_header(plan, cell_texts[index], margins["left"], 595.3 - margins["left"] - margins["right"])
            require(cells, f"{name}: complete merged header missing above {plan.id} records on page {index + 1}")
            header_regions[index].extend(cells)
            located.append(index + 1)
        normalized = compact("".join(pages))
        positions = [normalized.find(token) for token in row_codes]
        require(all(position >= 0 for position in positions) and positions == sorted(positions),
                f"{name}: source record order changed in {plan.id}")
        matched[plan.id] = {"pages": located, "levels": max(header["row"] for header in plan.headers) + 1,
                            "cellsPerHeader": len(plan.headers), "records": len(plan.rows), "orderedRecordCodes": row_codes}
        if len(located) > 1:
            long_tables.append(plan.id)
    return {"tables": matched, "crossPageTables": long_tables}, header_regions


def cell_key(cell):
    return tuple(cell[key] for key in ("page", "x", "y", "width", "height"))


def continued_cells(tree, data, expected, headers, cell_texts, margins):
    """Join only exact source cells at matching last/first rows across a repeated header.

    PDF painting visits all columns on a page before continuing a split row on
    the next page. Restore that cell's text before checking source frequencies.
    """
    plans = []
    expected_blocks(tree, data, plans)
    replacements, report = {}, []
    width = 595.3 - margins["left"] - margins["right"]
    for plan in plans:
        if plan.id not in headers["crossPageTables"]:
            continue
        texts = {compact(value.text) for value in expected if value.table == plan.id and not value.header}
        pages = headers["tables"][plan.id]["pages"]
        for first, second in zip(pages, pages[1:]):
            require(second == first + 1, f"{plan.id}: paginated table skipped a page")
            first_header = match_header(plan, cell_texts[first - 1], margins["left"], width)
            second_header = match_header(plan, cell_texts[second - 1], margins["left"], width)
            bottom = max(cell["y"] + cell["height"] for cell in first_header)
            top = max(cell["y"] + cell["height"] for cell in second_header)
            before = [cell for cell in cell_texts[first - 1] if cell["y"] >= bottom - GEOMETRY_TOLERANCE]
            after = [cell for cell in cell_texts[second - 1] if abs(cell["y"] - top) < GEOMETRY_TOLERANCE]
            if not before or not after:
                continue
            last_row = max(cell["y"] for cell in before)
            for cell in before:
                if abs(cell["y"] - last_row) >= GEOMETRY_TOLERANCE or not cell["text"]:
                    continue
                following = next((candidate for candidate in after
                                  if abs(candidate["x"] - cell["x"]) < GEOMETRY_TOLERANCE
                                  and abs(candidate["width"] - cell["width"]) < GEOMETRY_TOLERANCE), None)
                if following is None or not following["text"]:
                    continue
                joined = cell["text"] + following["text"]
                if joined in texts:
                    require(cell_key(cell) not in replacements and cell_key(following) not in replacements,
                            f"{plan.id}: overlapping source-cell continuations")
                    replacements[cell_key(cell)] = joined
                    replacements[cell_key(following)] = ""
                    report.append({"table": plan.id, "pages": [first, second], "characters": len(joined)})
    return replacements, report


def check_numbering(name, expected, visible):
    """Check the decimal labels declared by numbered items, separately from body text."""
    lists, labels = {}, []
    for value in expected:
        if value.kind != "numbered":
            continue
        levels = lists.setdefault(value.list_id, [0] * 9)
        require(1 <= value.level <= 9 and all(levels[index] for index in range(value.level - 1)),
                f"{name}: numbered sample skips a parent level")
        levels[value.level - 1] = value.restart or levels[value.level - 1] + 1
        levels[value.level:] = [0] * (9 - value.level)
        label = ".".join(str(number) for number in levels[:value.level]) + "."
        probe = label + compact(value.text)[:24]
        require(probe in visible, f"{name}: numbered list label missing or out of sequence: {probe}")
        labels.append(label)
    return labels


def check_pdf(name, path, tree, data, expected):
    reader = PdfReader(path)
    count = len(reader.pages)
    require(count >= 2, f"{name}: expanded example must demonstrate real pagination")
    require(count == PAGES[name], f"{name}: natural page count changed")
    require(not reader.get_fields(), f"{name}: static example unexpectedly contains form fields")
    margins, header, footer = page_settings(tree)
    header_height = float(header.get("height", 0)) if header is not None else 0
    header_gap = float(header.get("gap", 0)) if header is not None else 0
    footer_height, footer_gap = float(footer.attrib["height"]), float(footer.attrib.get("gap", 0))
    body_top = margins["top"] + header_height + header_gap
    body_bottom = 841.9 - margins["bottom"] - footer_height - footer_gap
    header_bottom = margins["top"] + header_height
    footer_top, footer_bottom = 841.9 - margins["bottom"] - footer_height, 841.9 - margins["bottom"]
    expected_footers = [region_text(footer, data, index + 1, count) for index in range(count)]
    expected_headers = [region_text(header, data, index + 1, count) for index in range(count)]
    pypdf_body, pdfium_body, glyph_count = [], [], 0
    body_glyphs, cell_texts = [], []
    cells = cell_rectangles(reader)
    for cell in cells:
        require(margins["left"] - GEOMETRY_TOLERANCE <= cell["x"]
                and cell["x"] + cell["width"] <= 595.3 - margins["right"] + GEOMETRY_TOLERANCE
                and body_top - GEOMETRY_TOLERANCE <= cell["y"]
                and cell["y"] + cell["height"] <= body_bottom + GEOMETRY_TOLERANCE,
                f"{name}: bordered cell leaves the body region")
    nested_pairs = check_cell_nesting(name, cells)
    # Outlined field paragraphs have one-point carrier glyphs plus /ActualText.
    # Classify their logical anchors by final position rather than assuming only footers use fields.
    _, logical_anchors, _ = pdf_geometry(reader, 16)
    with pdfium.PdfDocument(path) as document:
        require(len(document) == count, f"{name}: PDFium page count disagrees")
        for index, source_page in enumerate(reader.pages):
            require(abs(float(source_page.mediabox.width) - 595.3) < .01
                    and abs(float(source_page.mediabox.height) - 841.9) < .01,
                    f"{name}: pypdf A4 dimensions changed")
            runs = []

            def visitor(text, _matrix, _text_matrix, _font, size):
                if size == 1 and text.strip():
                    require(text.strip() == "x", f"{name}: unexpected logical text carrier")
                elif text:
                    # pypdf flushes some same-line font-switch text after resetting its
                    # visitor text matrix. Use it to cross-check content; PDFium below
                    # independently checks physical body/header/footer separation.
                    runs.append(text)

            source_page.extract_text(visitor_text=visitor)
            runs.extend(anchor["source"] for anchor in logical_anchors if anchor["page"] == index)
            pypdf_body.append("".join(runs))
            page = document[index]
            try:
                require(abs(page.get_width() - 595.3) < .01 and abs(page.get_height() - 841.9) < .01,
                        f"{name}: PDFium A4 dimensions changed")
                textpage = page.get_textpage()
                try:
                    require(Counter(compact(pypdf_body[-1])) == Counter(compact(textpage.get_text_range())),
                            f"{name}: pypdf/PDFium page character frequencies differ on page {index + 1}")
                    visible_footer = compact(textpage.get_text_bounded(0, 841.9 - footer_bottom - .1,
                                                                      595.3, 841.9 - footer_top + .1))
                    require(visible_footer == expected_footers[index],
                            f"{name}: visible PAGE/PAGES footer missing, displaced or stale on page {index + 1}")
                    if header is not None:
                        visible_header = compact(textpage.get_text_bounded(0, 841.9 - header_bottom - .1,
                                                                          595.3, 841.9 - margins["top"] + .1))
                        require(visible_header == expected_headers[index],
                                f"{name}: repeated header missing or displaced on page {index + 1}")
                    pdfium_body.append(textpage.get_text_bounded(0, 841.9 - body_bottom - .1,
                                                                 595.3, 841.9 - body_top + .1))
                    page_cells = [cell for cell in cells if cell["page"] == index]
                    cell_texts.append([{**cell, "text": compact(textpage.get_text_bounded(
                        cell["x"], 841.9 - cell["y"] - cell["height"], cell["x"] + cell["width"], 841.9 - cell["y"]))}
                        for cell in page_cells])
                    page_body_glyphs = []
                    for character_index in range(textpage.count_chars()):
                        character = textpage.get_text_range(character_index, 1, errors="ignore")
                        if not character.strip() or character == "\ufffc":
                            continue
                        left, bottom, right, top = textpage.get_charbox(character_index)
                        y1, y2 = 841.9 - top, 841.9 - bottom
                        require(margins["left"] - GLYPH_TOLERANCE <= left <= right
                                <= 595.3 - margins["right"] + GLYPH_TOLERANCE,
                                f"{name}: visible glyph crosses a horizontal page margin")
                        in_body = body_top - GLYPH_TOLERANCE <= y1 <= y2 <= body_bottom + GLYPH_TOLERANCE
                        in_header = header is not None and margins["top"] - GLYPH_TOLERANCE <= y1 <= y2 <= header_bottom + GLYPH_TOLERANCE
                        in_footer = footer_top - GLYPH_TOLERANCE <= y1 <= y2 <= footer_bottom + GLYPH_TOLERANCE
                        require(in_body or in_header or in_footer,
                                f"{name}: visible glyph leaves body/header/footer or enters a reserved gap")
                        box = left, y1, right, y2
                        center = (left + right) / 2, (y1 + y2) / 2
                        owners = [cell for cell in page_cells
                                  if cell["x"] <= center[0] <= cell["x"] + cell["width"]
                                  and cell["y"] <= center[1] <= cell["y"] + cell["height"]]
                        if owners:
                            owner = min(owners, key=lambda cell: cell["width"] * cell["height"])
                            require(owner["x"] - GLYPH_TOLERANCE <= box[0]
                                    and owner["y"] - GLYPH_TOLERANCE <= box[1]
                                    and box[2] <= owner["x"] + owner["width"] + GLYPH_TOLERANCE
                                    and box[3] <= owner["y"] + owner["height"] + GLYPH_TOLERANCE,
                                    f"{name}: visible glyph crosses its innermost bordered cell edge")
                        if in_body:
                            page_body_glyphs.append((character, center, cell_key(owner) if owners else None))
                        glyph_count += 1
                    body_glyphs.append(page_body_glyphs)
                finally:
                    textpage.close()
            finally:
                page.close()
    headers_report, header_regions = repeated_headers(name, tree, data, expected, pdfium_body, cell_texts, margins)
    replacements, continuations = continued_cells(tree, data, expected, headers_report, cell_texts, margins)
    # Exclude the actual matched table-header rectangles from body frequency checks.
    # A page break must not splice a repeated header into a continued source sentence.
    visible_parts, emitted = [], set()
    for index, glyphs in enumerate(body_glyphs):
        for character, center, owner in glyphs:
            if any(cell["x"] - GEOMETRY_TOLERANCE <= center[0] <= cell["x"] + cell["width"] + GEOMETRY_TOLERANCE
                   and cell["y"] - GEOMETRY_TOLERANCE <= center[1] <= cell["y"] + cell["height"] + GEOMETRY_TOLERANCE
                   for cell in header_regions[index]):
                continue
            if owner in replacements:
                if owner not in emitted:
                    visible_parts.append(replacements[owner])
                    emitted.add(owner)
            else:
                visible_parts.append(character)
    require(emitted == replacements.keys(), f"{name}: a continued source cell has no visible glyphs")
    visible_body = "".join(visible_parts)
    expected_counts = Counter(compact(value.text) for value in expected if not value.header and compact(value.text))
    require("${" not in visible_body and "\ufffd" not in visible_body,
            f"{name}: found an unresolved expression or replacement character")
    for token, repetitions in expected_counts.items():
        require(visible_body.count(token) >= repetitions, f"{name}: lost content: {token[:90]}")
    exact = unique_probes(expected)
    for token, repetitions in exact.items():
        require(visible_body.count(token) == repetitions,
                f"{name}: body record/clause lost or duplicated ({visible_body.count(token)} vs {repetitions}): {token[:90]}")
    labels = check_numbering(name, expected, visible_body)
    require(compact("".join(pdfium_body)).find(compact(expected[0].text))
            < compact("".join(pdfium_body)).rfind(compact(expected[-1].text)),
            f"{name}: first/last body content order changed")
    fonts = embedded_fonts(reader)
    require(any("LXGWWenKaiTC" in font for font in fonts), f"{name}: explicit Chinese example font changed")
    return {"pages": count, "fonts": fonts, "bodyTextBlocks": len(expected),
            "images": check_images(name, reader, tree, path.parent),
            "borderedCells": len(cells), "nestedCellPairs": nested_pairs, "visibleGlyphs": glyph_count,
            "exactBodyProbes": len(exact), "repeatedHeaders": headers_report,
            "continuedCells": continuations,
            "numberedLabels": labels,
            "headers": expected_headers, "footers": expected_footers,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-directory", type=Path, default=ROOT / "examples/build/common-documents")
    parser.add_argument("--render-directory", type=Path, default=ROOT / "build/verification/common-documents")
    parser.add_argument("--samples", nargs="+", choices=DOCUMENTS, default=list(DOCUMENTS),
                        help="Only verify the selected examples; defaults to all six.")
    args = parser.parse_args()
    require(len(args.samples) == len(set(args.samples)), "duplicate sample selection")
    args.render_directory.mkdir(parents=True, exist_ok=True)
    report = {"tools": {name: metadata.version(name) for name in ("pypdf", "pypdfium2", "Pillow")},
              "renderComparison": {"dpi": 192, "blurPixels": 2, "meanLimit": 4, "changedFractionLimit": .06},
              "samples": args.samples, "documents": {}}
    contacts = []
    for name in args.samples:
        tree, data, expected, id_count = source_contract(name, args.output_directory)
        path = args.output_directory / f"{name}.pdf"
        result = check_pdf(name, path, tree, data, expected)
        result["sourceIds"] = id_count
        if name == "quotation":
            result["amountCalculation"] = quotation_math(data)
        elif name == "service-contract":
            result["amountCalculation"] = contract_math(data)
        poppler, pdfium_pages = render(path, args.render_directory / name)
        require(len(poppler) == len(pdfium_pages) == result["pages"], f"{name}: renderer omitted a page")
        differences = [difference(first, second, 2) for first, second in zip(poppler, pdfium_pages, strict=True)]
        require(all(value["meanAbsoluteChannelError"] < 4 and value["changedPixelFraction"] < .06 for value in differences),
                f"{name}: independent renderers differ: {differences}")
        result["renderDifferences"] = differences
        report["documents"][name] = result
        contacts.extend((f"{name}-{index + 1}", image) for index, image in enumerate(poppler))
    cross_page = [(name, table) for name, result in report["documents"].items()
                  for table in result["repeatedHeaders"]["crossPageTables"]]
    require(cross_page or not set(args.samples).intersection({"service-contract", "user-manual", "quotation", "meeting-minutes"}),
            "selected expanded examples do not exercise a genuinely paginated table")
    require(not set(args.samples).intersection({"service-contract", "user-manual", "quotation"})
            or any(result["repeatedHeaders"]["tables"][table]["levels"] >= 2
                for result in report["documents"].values() for table in result["repeatedHeaders"]["crossPageTables"]),
            "expanded examples do not repeat a merged multi-level header across pages")
    report["crossPageTables"] = [{"document": name, "table": table} for name, table in cross_page]
    contact_sheet(contacts, args.render_directory)
    (args.render_directory / "verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=True))


if __name__ == "__main__":
    main()
