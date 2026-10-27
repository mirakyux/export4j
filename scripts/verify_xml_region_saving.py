# SPDX-License-Identifier: Apache-2.0
"""Independently inspect XML source sharing, native bands and all PDF pages after reopen/edit."""
import argparse
import json
import re
from pathlib import Path
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from PIL import Image
import pypdfium2 as pdfium
from pypdf import PdfReader
from verify_adr_form import render
from verify_advanced_pdf import difference, require
from verify_inline_images import image_boxes
from verify_production import contact_sheet
from verify_semantic_tables import page_logical
from verify_streaming_rich_text import outlined_paths, painted_spans, physical_fonts
from verify_xml_model_saving import exact_pixels, semantic_xml

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "examples/build/xml-region-saving"
OUTPUT = ROOT / "build/verification/xml-region-saving"
W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
R = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}"
P = "{http://schemas.openxmlformats.org/package/2006/relationships}"
BODIES = ["BODY-A1", "BODY-A2", "BODY-A3", "BODY-B1", "BODY-C1"]
NUMBERS = [2, 3, 4, 8, 10]


def bundle(path):
    with ZipFile(path) as archive:
        require(archive.testzip() is None, "ZIP CRC failure")
        entries = {name: archive.read(name) for name in archive.namelist()}
    root = ET.fromstring(entries["template.xml"])
    require(root.get("literal-text") == "true", "literal mode lost")
    sections = root.findall("section")
    require([s.get("first-page-number") for s in sections] == ["2", "8", "10"], "section numbering changed")
    sources = {kind: {} for kind in ("header", "footer", "background", "foreground")}
    references = 0
    for section in sections:
        for element in section:
            if element.tag not in sources:
                continue
            for node in (list(element) if element.tag in ("background", "foreground") else [element]):
                source_id, ref = node.get("source-id"), node.get("source-ref")
                require(bool(source_id) != bool(ref), "missing/ambiguous source declaration")
                if source_id:
                    require(source_id not in sources[element.tag], "duplicate source ID")
                    sources[element.tag][source_id] = node
                else:
                    require(ref in sources[element.tag] and not list(node) and node.get("height") is None, "invalid backward reference")
                    references += 1
    require(references >= 12, "shared sources were copied instead of referenced")
    ids = [n.get("id") for n in root.iter() if n.tag not in ("section",) and n.get("id")]
    require(len(ids) == len(set(ids)), "semantic IDs duplicated")
    first = sections[0]
    boxes = first.find("background").findall("box")
    require([(b.get("x"), b.get("y"), b.get("width")) for b in boxes] == [("20.0", "140.0", "80.0"), ("120.0", "140.0", "80.0")], "shared placement geometry changed")
    require(boxes[1].get("source-ref") == boxes[0].get("source-id"), "overlay sharing lost")
    require(not list(sections[1].find("background")) and not list(sections[2].find("foreground")), "explicit layer clearing lost")
    require(not any(n.get("variant") == "first" for n in sections[1] if n.tag in ("header", "footer")), "absent FIRST became an empty selector")
    empty_first = [n for n in sections[2] if n.tag in ("header", "footer") and n.get("variant") == "first"]
    require(len(empty_first) == 2, "explicit empty FIRST pair lost")
    for node in empty_first:
        definition = sources[node.tag].get(node.get("source-ref"), node)
        require(definition.get("height") == "0.0" and not list(definition), "empty FIRST no longer empty")
    require("${literal}" in "".join(root.itertext()), "literal region text changed")
    require(len([n for n in entries if n.startswith("images/")]) == 2, "image resource deduplication changed")
    for name in ("Regular.ttf", "Bold.ttf", "LiberationSans-OFL.txt"):
        prefix = "licenses/" if name.endswith(".txt") else "fonts/"
        require(entries[prefix + name] == (ROOT / "assets/fonts" / name.replace("Regular.ttf", "LiberationSans-Regular.ttf").replace("Bold.ttf", "LiberationSans-Bold.ttf")).read_bytes(), "explicit font/license bytes changed")
    return entries


def pdf(path, name, odd, contacts):
    reader = PdfReader(path, strict=True)
    require(len(reader.pages) == 5, "section page count changed")
    physical_fonts(reader, {"LiberationSans", "LiberationSans-Bold"})
    page_text = []
    with pdfium.PdfDocument(path) as document:
        for page in document:
            try:
                textpage = page.get_textpage()
                try: page_text.append(textpage.get_text_bounded())
                finally: textpage.close()
            finally: page.close()
    outlines = outlined_paths(reader)
    spans = painted_spans(reader)
    boxes = image_boxes(reader)
    headers = ["FIRST", odd, "EVEN", "EVEN", ""]
    footers = ["", "FOOT", "EVEN-FOOT", "EVEN-FOOT", ""]
    for index, page in enumerate(reader.pages):
        require((float(page.mediabox.width), float(page.mediabox.height)) == (360, 300), "page geometry changed")
        logical = re.sub(r"\s+", "", page_text[index])
        require([body for body in BODIES if body in logical] == [BODIES[index]] and logical.count(BODIES[index]) == 1, "body order/uniqueness changed")
        labels = [s for s in page_logical(page) if "PAGE=" in s]
        require(labels == [label + f" PAGE={NUMBERS[index]}TOTAL=5" for label in (headers[index], footers[index]) if label], "FIRST/display parity/footer choice changed")
        for label in (headers[index], footers[index]):
            if label:
                require(label + f"PAGE={NUMBERS[index]}TOTAL=5" in logical, "final band field values changed")
        require(logical.count("FRONT${literal}") == (1 if index < 4 else 0), "foreground table/literal changed")
        if index < 4:
            require(f"FINAL={NUMBERS[index]}/5" in logical, "foreground table final fields changed")
        widths = [b["width"] for b in boxes if b["page"] == index]
        require(widths == ([80, 80, 40] if index < 3 else [40] if index == 3 else [80, 80]), "image source/placement/paint order changed")
        body = next(s for s in spans if s["page"] == index and s["text"] == BODIES[index])
        require(body["x"] == 20 and 48 <= body["baseline"] < 85, "body band reservation changed")
        ink = [s for s in outlines if s["page"] == index]
        for selected, low, high in ((headers[index], 20, 44), (footers[index], 256, 280), (index < 4, 190, 230)):
            band_ink = [s for s in ink if low <= s["top"] < high]
            require((len(band_ink) >= 4 if selected else not band_ink)
                    and all(s["bottom"] <= high and 20 <= s["left"] < s["right"] <= 300 for s in band_ink), "field visible ink/band boundary changed")
    left, right = render(path, OUTPUT / name)
    require(len(left) == len(right) == 5, "not all pages rendered")
    metrics = []
    for index, (a, b) in enumerate(zip(left, right, strict=True)):
        delta = difference(a, b, 2)
        require(delta["meanAbsoluteChannelError"] < 4 and delta["changedPixelFraction"] < .06, "independent render mismatch")
        for image_path in (a, b):
            with Image.open(image_path) as im:
                pixels = im.convert("RGB")
                # The source images are solid; these points sit safely inside the overlapping boxes.
                expected = [(25, (34, 85, 170) if index != 3 else (255, 255, 255)),
                            (45, (136, 34, 68) if index != 4 else (34, 85, 170)),
                            (125, (34, 85, 170) if index != 3 else (255, 255, 255))]
                for x, color in expected:
                    require(max(abs(c - d) for c, d in zip(pixels.getpixel((round(x * 192 / 72), round(150 * 192 / 72))), color, strict=True)) <= 1, "visible overlay order/position changed")
        metrics.append(delta); contacts.append((name + f"-{index + 1}", a))
    return left, metrics


def docx(path, odd):
    with ZipFile(path) as archive:
        entries = {n: archive.read(n) for n in archive.namelist()}
    root = ET.fromstring(entries["word/document.xml"])
    settings = ET.fromstring(entries["word/settings.xml"])
    require(settings.find(W + "evenAndOddHeaders") is not None, "native parity switch missing")
    text = "".join(n.text or "" for n in root.iter(W + "t"))
    require(all(text.count(body) == 1 for body in BODIES), "native body changed")
    require("FRONT" not in text and not any(n.startswith("word/media/") for n in entries), "explicit DOCX overlay discard failed")
    rels = {n.get("Id"): "word/" + n.get("Target") for n in ET.fromstring(entries["word/_rels/document.xml.rels"]).findall(P + "Relationship")}
    sections = list(root.iter(W + "sectPr"))
    require(len(sections) == 3, "native section count changed")
    for index, section in enumerate(sections):
        require((section.find(W + "titlePg") is not None) == (index != 1), "native FIRST declaration changed")
        require(section.find(W + "pgNumType").get(W + "start") == str([2, 8, 10][index]), "native numbering changed")
        for kind in ("header", "footer"):
            refs = {n.get(W + "type"): ET.fromstring(entries[rels[n.get(R + "id")]]) for n in section.findall(W + kind + "Reference")}
            require(set(refs) == ({"default", "first", "even"} if index != 1 else {"default", "even"}), "native band references changed")
            for variant, band in refs.items():
                label = {"default": odd if kind == "header" else "FOOT", "even": "EVEN" if kind == "header" else "EVEN-FOOT", "first": "FIRST" if kind == "header" and index == 0 else ""}[variant]
                value = "".join(n.text or "" for n in band.iter(W + "t"))
                if index == 2 and variant == "first": label = ""
                require(value == (label + " PAGE=TOTAL=" if label else ""), "native band text changed")
                fields = [n.get(W + "instr").strip() for n in band.iter(W + "fldSimple")]
                require(fields == (["PAGE", "NUMPAGES"] if label else []), "native band fields changed")
    return entries


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--consumer-build-directory", type=Path)
    args = parser.parse_args()
    caller = (args.consumer_build_directory or Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip())) / "output"
    OUTPUT.mkdir(parents=True, exist_ok=True)
    original = bundle(SOURCE / "original.zip")
    require(bundle(SOURCE / "cycle-3.zip") == original, "three cycles changed XML/resources")
    for path, replacement in ((SOURCE / "edited.zip", b"EDIT"), (caller / "consumer-regions.zip", b"CALL")):
        changed = bundle(path)
        require(semantic_xml(changed["template.xml"]) == semantic_xml(original["template.xml"].replace(b"ODD", replacement)), "shared edit changed unrelated XML")
        require(all(changed[n] == original[n] for n in original if n != "template.xml"), "untouched resources changed")
    contacts, controls, report, packages = [], {}, {}, {}
    for name, odd, base in (("source", "ODD", SOURCE), ("reopened", "ODD", SOURCE), ("edited", "EDIT", SOURCE), ("consumer-regions", "CALL", caller)):
        controls[name], report[name] = pdf(base / (name + ".pdf"), name, odd, contacts)
        packages[name] = docx(base / (name + ".docx"), odd)
    require(packages["source"] == packages["reopened"], "unchanged native package contents changed")
    require(all(exact_pixels(a, b) for a, b in zip(controls["source"], controls["reopened"], strict=True)), "reopening changed visible layout")
    for changed in ("edited", "consumer-regions"):
        require([exact_pixels(a, b) for a, b in zip(controls["source"], controls[changed], strict=True)] == [True, False, True, True, True], "edit escaped selected shared header pages")
    contact_sheet(contacts, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps({"pdfPages": 20, "docxFiles": 4, "zipFiles": 4, "modelCycles": 3, "unchangedPixelExact": True, "dualRender": report}, indent=2) + "\n")
    print("XML region saving: 4 ZIPs, 3 cycles, 20 PDF pages, 4 native DOCX files and targeted edits verified")


if __name__ == "__main__":
    main()
