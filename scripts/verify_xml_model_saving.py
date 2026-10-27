# SPDX-License-Identifier: Apache-2.0
"""Verify semantic XML/ZIP saving using independent ZIP/XML, OOXML, PDF and two renderers."""
from __future__ import annotations

import argparse
import csv
from io import BytesIO
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from zipfile import ZipFile
from PIL import Image, ImageChops
from pypdf import PdfReader
from verify_advanced_pdf import difference, require
from verify_adr_form import render
from verify_production import contact_sheet, extracted

ROOT = Path(__file__).resolve().parents[1]
EXAMPLES = ROOT / "examples/build/xml-model-saving"
OUTPUT = ROOT / "build/verification/xml-model-saving"
W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"


def bundle(path):
    with ZipFile(path) as archive:
        require(archive.testzip() is None, "invalid ZIP CRC")
        names = archive.namelist()
        require(len(names) == len(set(names)), "duplicate ZIP name")
        entries = {name: archive.read(name) for name in names}
    require(len(entries) == 5 and len([n for n in entries if n.startswith("images/")]) == 1, "unexpected resource inventory")
    for filename, asset in (("Regular.ttf", "LiberationSans-Regular.ttf"), ("Bold.ttf", "LiberationSans-Bold.ttf")):
        require(entries["fonts/" + filename] == (ROOT / "assets/fonts" / asset).read_bytes(), "font bytes changed")
    require(entries["licenses/LiberationSans-OFL.txt"] == (ROOT / "assets/fonts/LiberationSans-OFL.txt").read_bytes(), "font notice bytes changed")
    root = ET.fromstring(entries["template.xml"])
    require(root.tag == "document" and root.get("version") == "1" and root.get("literal-text") == "true", "existing XML/literal mode lost")
    sections = root.findall("section")
    require([(s.get("id"), s.get("first-page-number")) for s in sections] == [("first", "3"), ("second", "7")], "section numbering lost")
    intro = root.find(".//p[@id='intro']")
    require(intro.get("first-indent") == "4.0" and intro.get("subsequent-indent") == "2.0" and intro.get("line-break") == "CJK_STRICT", "paragraph geometry changed")
    require("${literal}" in "".join(intro.itertext()), "literal source text changed")
    require(intro.find("span").get("baseline-shift") == "-2.0", "text baseline shift lost")
    require(root.find(".//bullet").get("level") == "2" and root.find(".//numbered").get("marker-role") == "bold", "list marker semantics lost")
    require(len(root.findall(".//table")) == 3 and root.find(".//cell[@column-span='2']") is not None and root.find(".//cell[@row-span='2']") is not None, "nested/merged table structure lost")
    require(root.find(".//table[@id='table']").get("padding-left") == "6.0", "asymmetric table padding lost")
    require(all(root.find(".//" + tag) is not None for tag in ("page-number", "page-count", "page-reference", "number-reference")), "field kinds lost")
    return entries


def docx(path, marker, image):
    with ZipFile(path) as archive:
        xml = archive.read("word/document.xml")
        root = ET.fromstring(xml)
        text = "".join(value.text or "" for value in root.iter(W + "t"))
        require(marker in text and "${literal}" in text, "DOCX literal/edited content changed")
        require(len(list(root.iter(W + "tbl"))) == 3, "DOCX nested table missing")
        require(any(e.get(W + "val") == "2" for e in root.iter(W + "gridSpan")) and len(list(root.iter(W + "vMerge"))) == 2, "DOCX native merges missing")
        fields = list(root.iter(W + "fldSimple"))
        require([f.get(W + "instr").split()[0] for f in fields] == ["PAGE", "NUMPAGES", "PAGEREF", "REF"], "DOCX four native fields missing")
        bookmarks = {b.get(W + "name") for b in root.iter(W + "bookmarkStart")}
        require(all(f.get(W + "instr").split()[1] in bookmarks for f in fields[2:]), "DOCX references have no native target")
        sizes = [(e.get(W + "w"), e.get(W + "h")) for e in root.iter(W + "pgSz")]
        require(sizes == [("8400", "10000"), ("9200", "8000")], "DOCX section paper lost")
        require([e.get(W + "start") for e in root.iter(W + "pgNumType")] == ["3", "7"], "DOCX page restart lost")
        media = [n for n in archive.namelist() if n.startswith("word/media/")]
        require(len(media) == 1 and archive.read(media[0]) == image, "DOCX original image bytes lost")
        numbering = ET.fromstring(archive.read("word/numbering.xml"))
        require(any(e.get(W + "val") == "224488" for e in numbering.iter(W + "color")), "DOCX marker appearance lost")
        return xml


def pdf(path, name, marker, contacts):
    reader = PdfReader(path)
    require(len(reader.pages) == 2, "PDF section pagination changed")
    require([(float(p.mediabox.width), float(p.mediabox.height)) for p in reader.pages] == [(420, 500), (460, 400)], "PDF mixed paper changed")
    combined = extracted(path)
    for token in [marker, "${literal}", "MERGED-MODEL", "OWNER-MODEL", "CELL-C", "CELL-D", "INNER-MODEL", "END-MODEL"]:
        require(combined.count(token) == 1, "PDF missing/duplicate content: " + token)
    compact = re.sub(r"\s+", "", combined)
    require("PAGE=3TOTAL=2TARGET=7NUMBER=4" in compact, "PDF final section fields changed")
    links = [a.get_object() for p in reader.pages for a in p.get("/Annots", [])]
    require(len(links) == 3 and sum("/A" in a for a in links) == 1, "PDF external/reference links lost")
    require(len(reader.pages[0].images) == 1 and len(reader.pages[1].images) == 0, "PDF image duplicated/lost")
    with Image.open(BytesIO(reader.pages[0].images[0].data)) as image:
        require(image.size == (32, 16) and image.convert("RGB").getpixel((16, 8)) == (34, 85, 170), "PDF image pixels changed")
    first, second = render(path, OUTPUT / name)
    require(len(first) == len(second) == 2, "not all PDF pages rendered")
    metrics = []
    for index, (a, b) in enumerate(zip(first, second)):
        delta = difference(a, b, 2)
        require(delta["meanAbsoluteChannelError"] < 4 and delta["changedPixelFraction"] < .06, "dual render differs materially")
        contacts.append((name + f"-{index + 1}", a)); metrics.append(delta)
    return first, metrics


def exact_pixels(a, b):
    with Image.open(a) as left, Image.open(b) as right:
        return left.size == right.size and ImageChops.difference(left.convert("RGB"), right.convert("RGB")).getbbox() is None


def semantic_xml(source):
    root = ET.fromstring(source)
    for parent in root.iter():
        previous = None
        for child in list(parent):
            if previous is not None and previous.tag == child.tag == "span" and previous.attrib == child.attrib and not len(previous) and not len(child):
                previous.text = (previous.text or "") + (child.text or "")
                parent.remove(child)
            else:
                previous = child
    return ET.tostring(root)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--consumer-build-directory", type=Path)
    args = parser.parse_args()
    caller = (args.consumer_build_directory or Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip())) / "output"
    OUTPUT.mkdir(parents=True, exist_ok=True)
    original = bundle(EXAMPLES / "original.zip")
    require(bundle(EXAMPLES / "cycle-3.zip") == original, "three saves changed XML/resource bytes")
    for path, replacement in ((EXAMPLES / "edited.zip", "EDITED-MODEL"), (caller / "consumer-model.zip", "CALLER-MODEL")):
        changed = bundle(path)
        # Literal replacement may split its owning run; merge only adjacent spans with identical full attributes.
        expected = original["template.xml"].replace(b"ORIGINAL-MODEL", replacement.encode())
        require(semantic_xml(changed["template.xml"]) == semantic_xml(expected), "targeted edit changed other XML/content/style")
        require(all(changed[n] == original[n] for n in original if n != "template.xml"), "untouched resources changed")
    with (EXAMPLES / "policy.csv").open(newline="") as stream:
        rows = {row["action"]: row for row in csv.DictReader(stream)}
    require([(rows[a]["warnings"], rows[a]["losses"]) for a in ("ERROR", "WARN", "IGNORE")] == [("0", "0"), ("1", "1"), ("0", "1")], "loss actions do not reconcile")
    require(not (EXAMPLES / "policy-ERROR.zip").exists(), "strict error published output")
    for action in ("WARN", "IGNORE"):
        with ZipFile(EXAMPLES / f"policy-{action}.zip") as archive:
            require("KEPT-MODEL" in "".join(ET.fromstring(archive.read("template.xml")).itertext()), "policy dropped supported content")
    image = next(original[n] for n in original if n.startswith("images/"))
    contacts, report, renders, docs = [], {}, {}, {}
    cases = [(EXAMPLES, n, "EDITED-MODEL" if n == "edited" else "ORIGINAL-MODEL") for n in ("source", "reopened", "cycle-3", "edited")]
    cases.append((caller, "consumer-model", "CALLER-MODEL"))
    for directory, name, marker in cases:
        docs[name] = docx(directory / (name + ".docx"), marker, image)
        renders[name], report[name] = pdf(directory / (name + ".pdf"), name, marker, contacts)
    for name in ("reopened", "cycle-3"):
        require(docs[name] == docs["source"], "unchanged standard OOXML differs")
        require(all(exact_pixels(a, b) for a, b in zip(renders["source"], renders[name])), "unchanged model PDF pixels differ")
    require(not exact_pixels(renders["source"][0], renders["edited"][0]) and exact_pixels(renders["source"][1], renders["edited"][1]), "edit did not stay on its intended page")
    contact_sheet(contacts, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps({"pdfPages": 10, "docxFiles": 5, "zipFiles": 6, "modelCycles": 3, "unchangedControlsPixelExact": True, "dualRender": report}, indent=2) + "\n")
    print("XML model saving: 3 cycles, 6 ZIPs, 10 PDF pages, 5 native DOCX files and loss policies verified")


if __name__ == "__main__":
    main()
