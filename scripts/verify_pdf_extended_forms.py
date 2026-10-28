# SPDX-License-Identifier: Apache-2.0
"""Independent structure, exact data exchange, all-page double rendering and field flattening checks."""
import argparse
import csv
from hashlib import sha256
from io import BytesIO
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
from PIL import Image
import pypdfium2 as pdfium
from pypdf import PdfReader, PdfWriter
from pypdf.generic import NameObject
from verify_advanced_pdf import difference, require
from verify_pdf_font_compatibility import compact
from verify_production import contact_sheet

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "build/verification/pdf-extended-forms-fixtures"
OUTPUT = ROOT / "build/verification/pdf-extended-forms"
FILLED = {"multi": "FIRST LINE\nSECOND LINE", "comb": "AB12CD34", "rich": "RED BOLD UNDERLINED", "list": ["A", "C"]}
EXTERNAL = {**FILLED, "multi": "EXCHANGED FIRST\nEXCHANGED SECOND", "comb": "DATA123"}


def references(source, directory):
    poppler, target = directory / "poppler", directory / "pdfium"
    poppler.mkdir(parents=True, exist_ok=True)
    target.mkdir(parents=True, exist_ok=True)
    subprocess.run(["pdftoppm", "-cropbox", "-r", "144", "-png", str(source), str(poppler / "page")],
                   check=True, capture_output=True, timeout=60)
    pages = []
    with pdfium.PdfDocument(source) as document:
        document.init_forms()
        for index in range(len(document)):
            page = document[index]
            try:
                # PDFium's form layer uses LCD text. Match page text to that mode so
                # flatten equivalence compares identical antialiasing, not two modes.
                bitmap = page.render(scale=2, may_draw_forms=True, draw_annots=True, optimize_mode="lcd")
                try:
                    name = target / f"page-{index + 1}.png"
                    bitmap.to_pil().convert("RGB").save(name)
                    pages.append(name)
                finally:
                    bitmap.close()
            finally:
                page.close()
    return sorted(poppler.glob("page-*.png"), key=lambda path: int(path.stem.rsplit("-", 1)[1])), pages


def fields(path):
    reader = PdfReader(path)
    form = reader.trailer["/Root"].get("/AcroForm")
    if form is None:
        return {}
    form = form.get_object()
    repair = form.get("/NeedAppearances")
    require(repair is None or getattr(repair, "value", None) is False, "reader repair was requested")
    return {value.get_object()["/T"]: value.get_object() for value in form.get("/Fields", [])}


def check_values(path, expected, rich=True):
    actual = fields(path)
    require(set(actual) == set(expected), "field inventory changed: " + path.name)
    for name, value in expected.items():
        require(actual[name]["/V"] == value, "actual field value changed: " + path.name + " " + name)
        appearance = actual[name]["/AP"]["/N"].get_object()
        require(appearance.get("/Subtype") == "/Form" and len(appearance.get_data()) > 0, "concrete field AP missing")
        for font in appearance["/Resources"]["/Font"].values():
            value = font.get_object()
            child = value["/DescendantFonts"][0].get_object()
            require(child["/FontDescriptor"].get("/FontFile2") is not None, "field AP does not embed explicit TrueType")
        # An independent temporary page exposes exactly the AP text without relying on field /V.
        writer = PdfWriter()
        bounds = appearance["/BBox"]
        page = writer.add_blank_page(float(bounds[2]), float(bounds[3]))
        page[NameObject("/Resources")] = appearance.raw_get("/Resources").clone(writer)
        page[NameObject("/Contents")] = actual[name]["/AP"].raw_get("/N").clone(writer)
        encoded = BytesIO()
        writer.write(encoded)
        extracted = PdfReader(BytesIO(encoded.getvalue())).pages[0].extract_text()
        text = "Alpha Beta Gamma" if name == "list" else expected[name]
        require(compact(extracted) == compact(text), "appearance text differs from exact field value/options: " + name)
    require(int(actual["multi"]["/Ff"]) & (1 << 12), "multiline flag removed")
    multi_appearances = actual["multi"]["/AP"]
    require(multi_appearances["/R"].get_object().get_data() == multi_appearances["/N"].get_object().get_data()
            and multi_appearances["/D"]["/Pressed"].get_object().get_data() == multi_appearances["/N"].get_object().get_data(),
            "rollover/down states do not contain the updated normal value")
    require(int(actual["comb"]["/Ff"]) & (1 << 24) and actual["comb"]["/MaxLen"] == 8, "comb properties removed")
    require(int(actual["list"]["/Ff"]) & (1 << 21) and actual["list"]["/I"] == [0, 2], "selected indices do not match multiselect values")
    if rich:
        require(int(actual["rich"]["/Ff"]) & (1 << 25), "rich field flag removed")
        body = ET.fromstring(actual["rich"]["/RV"])
        require(body.tag == "{http://www.w3.org/1999/xhtml}body", "rich value is not standard XHTML")
        require(compact("".join(body.itertext())) == compact(expected["rich"]), "rich value text differs from /V")
        require("text-decoration:underline" in actual["rich"]["/RV"] and "color:#cc2200" in actual["rich"]["/RV"], "rich text styling lost")


def losses(path, action, required):
    with path.open(encoding="utf-8", newline="") as source:
        rows = list(csv.DictReader(source, delimiter="\t"))
    require(required <= {row["feature"] for row in rows}, "actual losses missing: " + path.name)
    expected = {"FORMS": "DISCARD", "FORM_DATA_APPEARANCE": "DISCARD", "FORM_DATA_RICH_TEXT": "DISCARD", "FORM_DATA_PROPERTIES": "DISCARD", "FIELD_APPEARANCE_STATES": "DEGRADE", "XFA_INTERACTIVITY": "DISCARD", "XFA_PAGE_RECONSTRUCTION": "DEGRADE", "XFA_FONT_MAPPING": "DEGRADE"}
    for row in rows:
        require(row["action"] == action.upper(), "actual action changed")
        if row["feature"] in expected:
            require(row["treatment"] == expected[row["feature"]], "actual treatment changed")
    return [{key: value for key, value in row.items() if key != "action"} for row in rows]


def exchange(path):
    if path.suffix == ".fdf":
        entries = PdfReader(path).trailer["/Root"]["/FDF"]["/Fields"]
        return {value.get_object()["/T"]: value.get_object()["/V"] for value in entries}
    root = ET.parse(path).getroot()
    require(root.tag == "{http://ns.adobe.com/xfdf/}xfdf", "XFDF namespace changed")
    result = {}
    for item in root.find("{http://ns.adobe.com/xfdf/}fields"):
        values = [child.text or "" for child in item.findall("{http://ns.adobe.com/xfdf/}value")]
        result[item.attrib["name"]] = values if len(values) != 1 else values[0]
        if item.attrib["name"] == "rich":
            rich = item.find("{http://ns.adobe.com/xfdf/}value-richtext/{http://www.w3.org/1999/xhtml}body")
            require(rich is not None, "XFDF rich text was escaped instead of written as standard XHTML")
    return result


def markup(path):
    annotations = PdfReader(path).pages[1]["/Annots"]
    require([item.get_object()["/Subtype"] for item in annotations] == ["/Highlight", "/Underline", "/StrikeOut", "/Ink", "/Square", "/Circle"], "six markup types changed")
    for raw in annotations:
        item = raw.get_object()
        require(item["/P"].get_object().get("/Type") == "/Page", "annotation owner missing")
        require(item["/AP"]["/N"].get_object().get("/Subtype") == "/Form", "markup normal AP missing")
        if item["/Subtype"] in ("/Highlight", "/Underline", "/StrikeOut"):
            require(len(item["/QuadPoints"]) == 8, "text markup quad changed")
        elif item["/Subtype"] == "/Ink":
            require(len(item["/InkList"]) == 1 and len(item["/InkList"][0]) == 6, "ink geometry changed")
    require(float(annotations[0].get_object()["/CA"]) == .4, "highlight opacity changed")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--consumer-build-directory", type=Path)
    args = parser.parse_args()
    consumer = args.consumer_build_directory or Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip())
    if not consumer.is_absolute():
        consumer = ROOT / consumer
    directory = consumer / "output/pdf-extended-forms"
    summary = json.loads((directory / "summary.json").read_text())
    require(summary == {"pdfOutputs": 21, "exchangeOutputs": 4, "rejections": 15, "protectedTargets": 11}, "consumer did not complete the full matrix")
    manifest = json.loads((FIXTURES / "manifest.json").read_text())["files"]
    for name, record in manifest.items():
        require(sha256((FIXTURES / name).read_bytes()).hexdigest() == record["sha256"], "independent source modified")
    require((directory / "source.pdf").read_bytes() == (FIXTURES / "forms.pdf").read_bytes(), "source envelope changed")
    require((directory / "protected.pdf").read_bytes() == b"existing destination", "failure replaced old target")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    sheets = OUTPUT / "contact-sheets"
    sheets.mkdir(exist_ok=True)
    tiles, renders, rendered_pages = [], {}, 0
    pdfs = [path for path in sorted(directory.glob("*.pdf")) if path.name != "protected.pdf"]
    require(len(pdfs) == summary["pdfOutputs"] + 1, "PDF output inventory changed")
    for path in pdfs:
        references_by_engine = references(path, OUTPUT / path.stem)
        renders[path.stem] = references_by_engine
        pages = len(PdfReader(path).pages)
        require(len(references_by_engine[0]) == len(references_by_engine[1]) == pages, "independent pagination changed")
        rendered_pages += pages
        for engine, images in enumerate(references_by_engine):
            tiles.extend((path.stem + " page " + str(index + 1) + " engine " + str(engine + 1), image) for index, image in enumerate(images))
    paired_reports = {}
    for action in ("warn", "ignore"):
        filled = directory / ("filled-" + action + ".pdf")
        check_values(filled, FILLED)
        markup(filled)
        require(fields(directory / ("flattened-" + action + ".pdf")) == {}, "flattening retained field editability")
        for left, right in zip(renders["filled-" + action], renders["flattened-" + action], strict=True):
            for before, after in zip(left, right, strict=True):
                require(difference(before, after, 0)["changedPixelFraction"] == 0, "field flattening changed visible content")
        for stem, required in (("filled", {"FIELD_APPEARANCE_STATES"}), ("flattened", {"FORMS"}), ("passive-data", {"FORM_DATA_PROPERTIES", "FIELD_APPEARANCE_STATES"})):
            paired_reports.setdefault(stem, []).append(losses(directory / (stem + "-" + action + ".losses.tsv"), action, required))
        for ext in ("fdf", "xfdf"):
            require(exchange(directory / ("exported-" + action + "." + ext)) == FILLED, "independent FDF/XFDF field data changed")
            check_values(directory / ("external-" + action + "-" + ext + ".pdf"), EXTERNAL)
            check_values(directory / ("roundtrip-" + action + "-" + ext + ".pdf"), FILLED, rich=False)
            paired_reports.setdefault("export-" + ext, []).append(losses(directory / ("exported-" + action + "." + ext + ".losses.tsv"), action, {"FORM_DATA_APPEARANCE"}))
            paired_reports.setdefault("roundtrip-" + ext, []).append(losses(directory / ("roundtrip-" + action + "-" + ext + ".losses.tsv"), action, {"FORM_DATA_RICH_TEXT"}))
        for name in ("xfa-static", "xfa-xdp", "xfa-dynamic"):
            path = directory / (name + "-" + action + ".pdf")
            reader = PdfReader(path)
            expected = manifest[name + ".pdf"]
            require(len(reader.pages) == expected["pages"] and fields(path) == {}, "XFA was not flattened to the declared static pagination")
            require(all(abs(float(page.mediabox.width) - 300) < .001 and abs(float(page.mediabox.height) - 220) < .001 for page in reader.pages), "XFA page dimensions changed")
            actual = compact("".join(page.extract_text() for page in reader.pages))
            require(actual == compact("".join(expected["values"])), "XFA bound values missing/duplicated/reordered")
            if name == "xfa-dynamic":
                require(compact(reader.pages[0].extract_text()) == compact("".join(expected["values"][:5])) and compact(reader.pages[1].extract_text()) == compact("".join(expected["values"][5:])), "XFA dynamic page break changed")
            require("/AcroForm" not in reader.trailer["/Root"], "flattened XFA retained dynamic template/data")
            paired_reports.setdefault(name, []).append(losses(directory / (name + "-" + action + ".losses.tsv"), action, {"XFA_INTERACTIVITY", "XFA_PAGE_RECONSTRUCTION", "XFA_FONT_MAPPING"}))
    for stem, values in paired_reports.items():
        require(values[0] == values[1], "IGNORE dropped actual losses: " + stem)
    auto = fields(directory / "auto.pdf")["comb"]
    size = float(auto["/DA"].split()[1])
    require(4 <= size < 100 and auto["/V"] == "ABCDEFGH", "explicit automatic size/capacity changed")
    contact_sheet(tiles, sheets)
    report = {"checks": "passed", **summary, "fixtureFiles": len(manifest), "renderedPdfPages": rendered_pages, "independentPageImages": rendered_pages * 2, "contactTiles": len(tiles)}
    (OUTPUT / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report))


if __name__ == "__main__":
    main()
