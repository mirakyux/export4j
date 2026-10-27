# SPDX-License-Identifier: Apache-2.0
"""Independently inspect single HTML files, ZIP/XML bindings and resulting PDF pages."""
import argparse
import base64
import csv
import hashlib
from html.parser import HTMLParser
import io
import json
from pathlib import Path
import re
import struct
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from PIL import Image
from pypdf import PdfReader
from verify_advanced_pdf import difference, render_pdfium, render_poppler, require
from verify_production import check_bundle

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "examples/build/embedded-html-recovery"
OUTPUT = ROOT / "build/verification/embedded-html-recovery"


class HtmlView(HTMLParser):
    def __init__(self, data):
        super().__init__(convert_charrefs=True)
        self.body = False
        self.text = []
        self.comments = []
        self.feed(data.decode("utf-8"))
        self.close()

    def handle_starttag(self, tag, attrs):
        if tag == "body":
            self.body = True

    def handle_endtag(self, tag):
        if tag == "p" and self.body:
            self.text.append("\n")
        if tag == "body":
            self.body = False

    def handle_data(self, data):
        if self.body:
            self.text.append(data)

    def handle_comment(self, data):
        self.comments.append(data)


def inspect(path, expected, binding=True):
    data = path.read_bytes()
    match = re.fullmatch(rb"(.*)\n<!--export4j-model-recovery\n([A-Za-z0-9+/]*={0,2})\n-->\n", data, re.DOTALL)
    require(match is not None, "HTML recovery comment missing or malformed")
    standard, encoded = match.groups()
    view = HtmlView(data)
    require(len(view.comments) == 1 and view.comments[0].startswith("export4j-model-recovery\n"), "recovery is not one inert HTML comment")
    require("".join(view.text).split() == [expected, "EMBEDDED-HTML", "UNCHANGED-CONTROL"], "HTML visible text differs")
    with ZipFile(io.BytesIO(base64.b64decode(encoded, validate=True))) as archive:
        require(archive.testzip() is None, "embedded ZIP CRC mismatch")
        require(len(archive.namelist()) == len(set(archive.namelist())), "embedded ZIP has duplicate entries")
        entries = {name: archive.read(name) for name in archive.namelist()}
    manifest = ET.fromstring(entries.pop("recovery.xml"))
    require(manifest.tag == "recovery" and manifest.get("version") == "1" and manifest.get("format") == "HTML", "unexpected recovery manifest")
    matches = manifest.get("sha256") == hashlib.sha256(standard).hexdigest() and int(manifest.get("bytes")) == len(standard)
    require(matches == binding, "HTML byte binding result differs")
    digest = hashlib.sha256()
    for name, content in sorted(entries.items()):
        key = name.encode("utf-8")
        digest.update(struct.pack(">II", len(key), len(content)))
        digest.update(key)
        digest.update(content)
    require(digest.hexdigest() == manifest.get("model-sha256"), "embedded model/resource digest mismatch")
    require(set(entries) == {"template.xml", "fonts/Regular.ttf", "licenses/Font.txt"}, "unexpected hidden source/resource")
    require(entries["fonts/Regular.ttf"] == (ROOT / "assets/fonts/LiberationSans-Regular.ttf").read_bytes(), "embedded font changed")
    require(entries["licenses/Font.txt"] == (ROOT / "assets/fonts/LiberationSans-OFL.txt").read_bytes(), "embedded license changed")
    xml = entries["template.xml"].decode("utf-8")
    model_words = [word for paragraph in ET.fromstring(xml).findall(".//p") for word in "".join(paragraph.itertext()).split()]
    require("UNUSED-SOURCE" not in xml, "old authoring source retained")
    if binding:
        require(model_words == [expected, "EMBEDDED-HTML", "UNCHANGED-CONTROL"], "saved model differs from current HTML")
        if expected != "ORIGINAL":
            require("ORIGINAL" not in xml, "deleted source text survived recovery")
    return standard, entries["template.xml"]


def pdf(path, expected):
    reader = PdfReader(path)
    require(len(reader.pages) == 1, "unexpected PDF page count")
    require(reader.pages[0].extract_text().split() == [expected, "EMBEDDED-HTML", "UNCHANGED-CONTROL"], "PDF text/order differs")
    poppler = render_poppler(path, OUTPUT / path.stem / "poppler")
    pdfium = render_pdfium(path, OUTPUT / path.stem / "pdfium")
    require(len(poppler) == len(pdfium) == 1, "renderer page count differs")
    comparison = difference(poppler[0], pdfium[0], blur_radius=1)
    require(comparison["meanAbsoluteChannelError"] < 4 and comparison["changedPixelFraction"] < .06, "PDF renderers differ materially")
    return poppler[0], comparison


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--consumer-build-directory", type=Path, required=True)
    args = parser.parse_args()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    original, model = inspect(SOURCE / "original.html", "ORIGINAL")
    require(original == (SOURCE / "plain.html").read_bytes(), "embedding modified standard HTML bytes")
    cycled, cycled_model = inspect(SOURCE / "cycle-3.html", "ORIGINAL")
    require(original == cycled and model == cycled_model, "three rounds changed HTML/model")
    inspect(SOURCE / "edited.html", "EDITED")
    inspect(SOURCE / "external.html", "EXTERNAL", binding=False)
    for action in ("WARN", "IGNORE"):
        inspect(SOURCE / f"fallback-{action}.html", "EXTERNAL")
    with (SOURCE / "policy.csv").open(encoding="utf-8", newline="") as source:
        rows = {row["action"]: row for row in csv.DictReader(source)}
    require(set(rows) == {"ERROR", "WARN", "IGNORE"}, "policy matrix incomplete")
    require(rows["WARN"]["losses"] == rows["IGNORE"]["losses"] and int(rows["WARN"]["losses"]) > 0, "losses differ between nonblocking actions")
    require(int(rows["WARN"]["warnings"]) > 0 and rows["IGNORE"]["warnings"] == "0", "warning visibility differs")
    consumer = args.consumer_build_directory.resolve()
    inspect(consumer / "output/consumer-embedded-html.html", "CALLER")
    renders = {}
    control, renders["control"] = pdf(SOURCE / "control.pdf", "ORIGINAL")
    edited, renders["edited"] = pdf(SOURCE / "edited.pdf", "EDITED")
    _, renders["consumer"] = pdf(consumer / "output/consumer-embedded-html.pdf", "CALLER")
    with Image.open(control) as first, Image.open(edited) as second:
        require(first.size == second.size and first.tobytes() != second.tobytes(), "edit did not change rendered PDF")
        # At 96 dpi the first line ends above y=100 and the unchanged second line starts below it.
        require(first.crop((0, 100, first.width, first.height)).tobytes() == second.crop((0, 100, second.width, second.height)).tobytes(), "edit changed untouched region")
    report = {"htmlFiles": 7, "rounds": 3, "actions": 3, "pdfPages": 3, "renders": renders, "localBundle": check_bundle(consumer)}
    (OUTPUT / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report))


if __name__ == "__main__":
    main()
