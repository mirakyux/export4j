# SPDX-License-Identifier: Apache-2.0
"""Independent geometry, semantic order, native formats and two-engine visual evidence."""
import argparse
import base64
import csv
from hashlib import sha256
from html.parser import HTMLParser
from importlib.metadata import version
import json
from pathlib import Path
import re
from zipfile import ZipFile
import xml.etree.ElementTree as ET
from markdown_it import MarkdownIt
from PIL import Image, ImageChops
from pypdf import PdfReader
import pypdfium2 as pdfium
from verify_advanced_pdf import require
from verify_pdf_raster import references
from verify_production import contact_sheet

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "build/verification/pdf-automatic-fixtures"
OUTPUT = ROOT / "build/verification/pdf-automatic-recovery"
W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"


def rows(path):
    with path.open(encoding="utf-8", newline="") as source:
        return list(csv.DictReader(source, delimiter="\t"))


def text(row):
    return base64.b64decode(row["text64"]).decode("utf-8")


def compact(value):
    return "".join(value.split())


def model_assert(model, expected):
    require([text(row) for row in model if row["scope"] == "BODY" and row["text64"]] == expected["body"], "body content lost, duplicated or reordered")
    for scope in ("HEADER", "FOOTER"):
        require([text(row) for row in model if row["scope"] == scope and row["text64"]] == expected.get(scope.lower()+"s", []), "repeating page region changed")
    tables = [row for row in model if row["type"] == "TABLE"]
    require(len(tables) == expected["tables"], "table inference/false-positive count")
    if "grid" in expected:
        require([int(tables[0][key]) for key in ("rows", "columns")] == expected["grid"], "physical grid changed")
    for field, expectation in (("rowSpan", "rowSpans"), ("columnSpan", "columnSpans")):
        actual = sorted(int(row[field]) for row in model if row["type"] == "CELL" and int(row[field]) > 1)
        require(actual == sorted(expected.get(expectation, [])), "merged cell coverage changed")
    require(sum(row["type"] == "BULLET" for row in model) == expected.get("bullets", 0), "editable bullets changed")
    require([int(row["number"]) for row in model if row["type"] == "NUMBER"] == expected.get("numbers", []), "editable number restart changed")
    if expected.get("nested"):
        require(any(".r" in row["path"] for row in tables), "nested table flattened")
    if "headerRows" in expected:
        require(int(tables[0]["headerRows"]) == expected["headerRows"], "continuation repeated header count")
    ids = [row["id"] for row in model if row["id"]]
    require(len(ids) == len(set(ids)), "semantic IDs duplicated")
    return {row["id"]: row for row in model if row["id"]}


class Visible(HTMLParser):
    def __init__(self):
        super().__init__(); self.stack = []; self.parts = []; self.tags = []
    def handle_starttag(self, tag, attrs):
        self.tags.append((tag, dict(attrs)))
        if tag not in ("meta", "img", "br", "hr", "link", "input"):
            self.stack.append((tag, dict(attrs)))
    def handle_endtag(self, tag):
        for index in range(len(self.stack)-1, -1, -1):
            if self.stack[index][0] == tag:
                del self.stack[index:]; break
    def handle_data(self, data):
        if not any(tag in ("head", "style", "script") or attrs.get("class") == "marker" for tag, attrs in self.stack):
            self.parts.append(data)


def full_text(model):
    sections = list(dict.fromkeys(row["path"].split(".", 1)[0] for row in model))
    return "".join(text(row) for section in sections for scope in ("HEADER", "BODY", "FOOTER")
                   for row in model if row["scope"] == scope and row["path"].split(".", 1)[0] == section)


def native_formats(directory, label, model, expected):
    with ZipFile(directory / (label+".docx")) as package:
        document = ET.fromstring(package.read("word/document.xml"))
        actual = "".join(node.text or "" for node in document.iter(W+"t"))
        require(compact(actual) == compact("".join(expected["body"])), "DOCX editable body changed")
        tables = list(document.iter(W+"tbl")); require(len(tables) == expected["tables"], "native DOCX table count")
        require(sum(int(node.get(W+"val", "1")) > 1 for node in document.iter(W+"gridSpan")) == len(expected.get("columnSpans", [])), "native DOCX column span count")
        require(sum(node.get(W+"val") == "restart" for node in document.iter(W+"vMerge")) == len(expected.get("rowSpans", [])), "native DOCX vertical merge")
        if expected.get("nested"):
            require(any(node.find(".//"+W+"tbl") is not None for table in tables for node in table.findall(W+"tr/"+W+"tc")), "nested DOCX table absent")
        if expected.get("headerRows"):
            require(len(list(document.iter(W+"tblHeader"))) == expected["headerRows"], "native DOCX repeating headers absent")
        if expected.get("bullets"):
            numbering = ET.fromstring(package.read("word/numbering.xml"))
            require(any(node.get(W+"val") == "bullet" for node in numbering.iter(W+"numFmt")), "native DOCX numbering absent")
    html = Visible(); html.feed((directory/(label+".html")).read_text(encoding="utf-8"))
    require(compact("".join(html.parts)) == compact(full_text(model)), "HTML editable content/order differs")
    require(sum(tag == "table" for tag, _ in html.tags) == expected["tables"], "native HTML tables absent")
    require(sum(tag in ("td", "th") and int(attrs.get("colspan", 1)) > 1 for tag, attrs in html.tags) == len(expected.get("columnSpans", [])), "native HTML merged columns absent")
    require(sum(tag in ("td", "th") and int(attrs.get("rowspan", 1)) > 1 for tag, attrs in html.tags) == len(expected.get("rowSpans", [])), "native HTML merged rows absent")
    markdown = Visible(); markdown.feed(MarkdownIt("commonmark").enable("table").render((directory/(label+".md")).read_text(encoding="utf-8")))
    markdown_text = "".join(markdown.parts)
    if expected.get("nested"): markdown_text = markdown_text.replace(" / ", "")
    require(compact(markdown_text) == compact(full_text(model)), "Markdown content lost or reordered during documented structural degradation")
    pdf = PdfReader(directory/(label+".pdf"))
    extracted = "\n".join(page.extract_text() for page in pdf.pages)
    clean = compact(re.sub(r"(?m)^\s*(?:[-*•]|\d+[.)])\s+", "", extracted))
    # Page regions are painted after the body. Their content is verified separately;
    # content-stream operator order is not the physical page reading order.
    for value in expected.get("headers", []) + expected.get("footers", []):
        token = compact(value); require(token in clean, "reflowed page region content absent"); clean = clean.replace(token, "", 1)
    require(clean == compact("".join(expected["body"])), "reflowed PDF body text lost, duplicated or reordered")
    return len(pdf.pages)


def provenance(source, sources, ids):
    unique = set(); leaves = {}
    for row in sources:
        require(row["id"] in ids, "source addresses nonexistent block")
        page = int(row["page"]); box = tuple(float(row[key]) for key in ("x", "y", "width", "height"))
        require(page >= 0 and box[0] >= 0 and box[1] >= 0 and box[2] > 0 and box[3] > 0 and box[0]+box[2] <= 601 and box[1]+box[3] <= 801, "invalid physical source box")
        key = (row["id"], page, box, row["cellPath"]); require(key not in unique, "duplicate provenance fragment"); unique.add(key)
        if row["cellPath"]:
            path = [int(value) for value in row["cellPath"].split("/")]
            require(len(path) % 2 == 0 and min(path) >= 0 and ids[row["id"]]["type"] == "TABLE", "inline table provenance path invalid")
        if ids[row["id"]]["type"] != "TABLE" or row["cellPath"]:
            leaves.setdefault(page, []).append(box)
    characters = 0
    with pdfium.PdfDocument(source) as document:
        for index in range(len(document)):
            page = document[index]
            try:
                textpage = page.get_textpage()
                try:
                    for position in range(textpage.count_chars()):
                        value = textpage.get_text_range(position, 1)
                        if not value.strip(): continue
                        left, bottom, right, top = textpage.get_charbox(position)
                        cx, cy = (left+right)/2, 800-(bottom+top)/2
                        # Source line boxes use font metrics, PDFium supplies actual ink boxes.
                        require(any(x-3 <= cx <= x+w+3 and y-3 <= cy <= y+h+3 for x,y,w,h in leaves.get(index, [])), "visible source character lacks leaf provenance")
                        characters += 1
                finally: textpage.close()
            finally: page.close()
    return characters


def main():
    parser = argparse.ArgumentParser(); parser.add_argument("--consumer-build-directory", type=Path); args = parser.parse_args()
    directory = args.consumer_build_directory or Path((ROOT/"build/verification/consumer-build-directory.txt").read_text().strip())
    caller = directory/"output/pdf-automatic-recovery"; OUTPUT.mkdir(parents=True, exist_ok=True)
    expected_files = json.loads((FIXTURES/"manifest.json").read_text())["files"]
    summary = json.loads((caller/"summary.json").read_text())
    require(summary == {"sources": len(expected_files), "conversions": len(expected_files)*8, "rejections": len(expected_files)+3}, "consumer acceptance controls incomplete")
    report = {}; page_images = 0; source_characters = 0; negative_controls = 0
    for name, expected in expected_files.items():
        source = caller/(name+"-source.pdf")
        require(sha256(source.read_bytes()).hexdigest() == expected["sha256"] == sha256((FIXTURES/(name+".pdf")).read_bytes()).hexdigest(), "independent fixture/source bytes changed")
        require(len(PdfReader(source).pages) == expected["pages"], "source page count changed")
        tiles = []; rendered = references(source, OUTPUT/name/"source")
        for engine, pages in enumerate(rendered):
            tiles.extend((f"{name} source E{engine+1} P{i+1}", path) for i,path in enumerate(pages)); page_images += len(pages)
        previous = None
        for action in ("warn", "ignore"):
            label = name+"-"+action; model = rows(caller/(label+".model.tsv")); ids = model_assert(model, expected)
            source_map = rows(caller/(label+".sources.tsv")); losses = rows(caller/(label+".losses.tsv"))
            effects = ";".join(row["effects"] for row in losses)
            require(any(row["feature"] == "PDF_LAYOUT" and row["treatment"] == "DEGRADE" for row in losses), "layout inference loss absent")
            require(all(row["action"] == action.upper() for row in losses), "configured actual loss action differs")
            if expected.get("uncertain"): require("UNCERTAIN" in effects, "uncertainty not disclosed")
            if expected.get("columns"): require("COLUMN_READING_ORDER" in effects, "reading order inference not disclosed")
            if expected.get("explicit"): require("EXPLICIT_TABLE_GRID_OVERRIDES" in effects, "explicit override not disclosed")
            if expected.get("continuation"): require("CROSS_PAGE_TABLE_CONTINUATION" in effects, "continuation not disclosed")
            if action == "warn":
                source_characters += provenance(source, source_map, ids)
                previous = (model, source_map, [(row["feature"],row["treatment"],row["effects"]) for row in losses])
                # Exercise the independent oracle against missing content and a swapped column order.
                candidates = [row for row in model if row["scope"] == "BODY" and row["text64"]]
                if len(candidates) > 1:
                    for altered in ([row for row in model if row is not candidates[0]], [dict(row, text64=candidates[1]["text64"] if row is candidates[0] else candidates[0]["text64"] if row is candidates[1] else row["text64"]) for row in model]):
                        rejected = False
                        try: model_assert(altered, expected)
                        except AssertionError: rejected = True
                        require(rejected, "independent content/order negative control failed"); negative_controls += 1
            else:
                require(previous == (model, source_map, [(row["feature"],row["treatment"],row["effects"]) for row in losses]), "WARN/IGNORE changed content, geometry or actual losses")
            pdf_pages = native_formats(caller, label, model, expected)
            output_pages = references(caller/(label+".pdf"), OUTPUT/name/action)
            for engine, pages in enumerate(output_pages):
                require(len(pages) == pdf_pages, "independent renderer page count differs")
                for index, path in enumerate(pages):
                    if action == "ignore":
                        other = OUTPUT/name/"warn"/("poppler" if engine == 0 else "pdfium")/path.name
                        with Image.open(path) as a, Image.open(other) as b:
                            require(ImageChops.difference(a.convert("RGB"), b.convert("RGB")).getbbox() is None, "WARN/IGNORE visible pixels differ")
                    tiles.append((f"{name} {action} E{engine+1} P{index+1}", path)); page_images += 1
        contact_sheet(tiles, OUTPUT/name)
        report[name] = {"sourcePages": expected["pages"], "tables": expected["tables"], "conversions": 8}
    require((caller/"budget-protected.html").read_bytes() == b"existing output", "budget failure replaced old target")
    snapshot = (caller/"snapshot.html").read_text(encoding="utf-8"); require("<header>" in snapshot and "LEFT 1 first segment" in snapshot, "conversion did not snapshot AUTO configuration")
    result = {"cases": report, "conversions": len(expected_files)*8, "pageImages": page_images, "sourceCharactersWithLeafProvenance": source_characters, "negativeControls": negative_controls,
              "tools": {name: version(name) for name in ("pypdf", "pypdfium2", "Pillow", "markdown-it-py")}}
    (OUTPUT/"report.json").write_text(json.dumps(result, indent=2)+"\n", encoding="utf-8"); print(json.dumps({key:value for key,value in result.items() if key not in ("cases","tools")}))


if __name__ == "__main__": main()
