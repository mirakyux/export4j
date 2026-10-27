# SPDX-License-Identifier: Apache-2.0
"""Inspect Markdown/SVG single-file bindings, visible structures and re-exported PDFs independently."""
import argparse
import base64
import csv
import hashlib
import io
import json
from pathlib import Path
import re
import struct
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from markdown_it import MarkdownIt
from PIL import Image
from pypdf import PdfReader
from verify_advanced_pdf import difference, render_pdfium, render_poppler, require
from verify_embedded_html_recovery import HtmlView
from verify_production import check_bundle

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "examples/build/embedded-comment-recovery"
OUTPUT = ROOT / "build/verification/embedded-comment-recovery"
MARKDOWN = MarkdownIt("commonmark", {"html": True})
SVG = "{http://www.w3.org/2000/svg}"


def inspect(path, format_key, expected, binding=True):
    data = path.read_bytes()
    match = re.fullmatch(rb"(.*)\n<!--export4j-model-recovery\n([A-Za-z0-9+/]*={0,2})\n-->\n", data, re.DOTALL)
    require(match is not None and data.count(b"<!--export4j-model-recovery") == 1, "missing or duplicate recovery comment")
    standard, encoded = match.groups()
    if format_key == "MARKDOWN":
        tokens = MARKDOWN.parse(data.decode("utf-8"))
        comments = [token for token in tokens if token.type == "html_block"]
        require(len(comments) == 1 and comments[0].content.startswith("<!--export4j-model-recovery\n"), "recovery is not one CommonMark comment")
        require(sum(token.type == "paragraph_open" for token in tokens) == 2, "Markdown paragraphs changed")
        rendered = MARKDOWN.render(data.decode("utf-8"))
        plain = MARKDOWN.render(standard.decode("utf-8"))
        require(" ".join(HtmlView(("<body>" + rendered + "</body>").encode()).text).split()
                == [expected, "EMBEDDED-COMMENT", "UNCHANGED-CONTROL"], "rendered Markdown content/order changed")
        require(rendered[:rendered.index("<!--export4j-model-recovery")] == plain, "embedding changed Markdown rendering")
    else:
        tree = ET.fromstring(data)
        require(tree.tag == SVG + "svg" and tree.find(".//" + SVG + "path") is not None, "SVG outlines missing")
        require(ET.tostring(tree) == ET.tostring(ET.fromstring(standard)), "comment modified SVG elements")
        comments = [node for _, node in ET.iterparse(io.BytesIO(data), events=("comment",))]
        require(len(comments) == 1 and comments[0].text.startswith("export4j-model-recovery\n"), "recovery is not one inert XML comment")
        require(expected + " EMBEDDED-COMMENT" in "".join(tree.itertext()) and "UNCHANGED-CONTROL" in "".join(tree.itertext()), "SVG logical content changed")
        if not binding:
            require(any(node.get("fill") == "rgb(0% 0% 100%)" for node in tree.iter()), "external SVG color did not change")
    with ZipFile(io.BytesIO(base64.b64decode(encoded, validate=True))) as archive:
        require(archive.testzip() is None and len(archive.namelist()) == len(set(archive.namelist())), "ZIP CRC/duplicate failure")
        entries = {name: archive.read(name) for name in archive.namelist()}
    manifest = ET.fromstring(entries.pop("recovery.xml"))
    require(manifest.tag == "recovery" and manifest.get("version") == "1" and manifest.get("format") == format_key, "manifest format/version differs")
    matches = int(manifest.get("bytes")) == len(standard) and manifest.get("sha256") == hashlib.sha256(standard).hexdigest()
    require(matches == binding, "current-file byte binding differs")
    digest = hashlib.sha256()
    for name, content in sorted(entries.items()):
        key = name.encode("utf-8")
        digest.update(struct.pack(">II", len(key), len(content)))
        digest.update(key)
        digest.update(content)
    require(digest.hexdigest() == manifest.get("model-sha256"), "model/resource digest differs")
    require(set(entries) == {"template.xml", "fonts/Regular.ttf", "licenses/Font.txt"}, "unexpected hidden source")
    require(entries["fonts/Regular.ttf"] == (ROOT / "assets/fonts/LiberationSans-Regular.ttf").read_bytes(), "font bytes changed")
    require(entries["licenses/Font.txt"] == (ROOT / "assets/fonts/LiberationSans-OFL.txt").read_bytes(), "font notice changed")
    xml = entries["template.xml"].decode("utf-8")
    words = [word for paragraph in ET.fromstring(xml).findall(".//p") for word in "".join(paragraph.itertext()).split()]
    require("UNUSED-SOURCE" not in xml, "unused authoring source retained")
    if binding:
        require(words == [expected, "EMBEDDED-COMMENT", "UNCHANGED-CONTROL"], "model and current output differ")
        if expected != "ORIGINAL":
            require("ORIGINAL" not in xml, "deleted text survived in model")
    return standard, entries["template.xml"]


def pdf(path, expected, label):
    reader = PdfReader(path)
    require(len(reader.pages) == 1, "PDF page count differs")
    require(reader.pages[0].extract_text().split() == [expected, "EMBEDDED-COMMENT", "UNCHANGED-CONTROL"], "PDF text/order differs")
    poppler = render_poppler(path, OUTPUT / label / "poppler")
    pdfium = render_pdfium(path, OUTPUT / label / "pdfium")
    require(len(poppler) == len(pdfium) == 1, "renderer page count differs")
    comparison = difference(poppler[0], pdfium[0], blur_radius=1)
    require(comparison["meanAbsoluteChannelError"] < 4 and comparison["changedPixelFraction"] < .06, "renderers differ materially")
    return poppler[0], comparison


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--consumer-build-directory", type=Path, required=True)
    args = parser.parse_args()
    consumer = args.consumer_build_directory.resolve()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    renders = {}
    files = 0
    for format_key, extension in (("MARKDOWN", "md"), ("SVG", "svg")):
        directory = SOURCE / extension
        original, model = inspect(directory / f"original.{extension}", format_key, "ORIGINAL")
        require(original == (directory / f"plain.{extension}").read_bytes(), "embedding altered standard bytes")
        cycled, cycled_model = inspect(directory / f"cycle-3.{extension}", format_key, "ORIGINAL")
        require(original == cycled and model == cycled_model, "three rounds changed file/model")
        inspect(directory / f"edited.{extension}", format_key, "EDITED")
        inspect(directory / f"external.{extension}", format_key, "EXTERNAL" if extension == "md" else "ORIGINAL", binding=False)
        inspect(consumer / f"output/consumer-embedded-comment.{extension}", format_key, "CALLER")
        files += 5
        first, renders[f"{extension}-control"] = pdf(directory / "control.pdf", "ORIGINAL", f"{extension}-control")
        edited, renders[f"{extension}-edited"] = pdf(directory / "edited.pdf", "EDITED", f"{extension}-edited")
        _, renders[f"{extension}-consumer"] = pdf(consumer / f"output/consumer-embedded-comment-{extension}.pdf", "CALLER", f"{extension}-consumer")
        with Image.open(first) as before, Image.open(edited) as after:
            require(before.size == after.size and before.tobytes() != after.tobytes(), "real edit did not change PDF pixels")
            require(before.crop((0, 100, before.width, before.height)).tobytes() == after.crop((0, 100, after.width, after.height)).tobytes(), "untouched region changed")
    for action in ("WARN", "IGNORE"):
        inspect(SOURCE / f"md/fallback-{action}.md", "MARKDOWN", "EXTERNAL")
        files += 1
    with (SOURCE / "policy.csv").open(encoding="utf-8", newline="") as source:
        rows = {(row["format"], row["action"]): row for row in csv.DictReader(source)}
    require(len(rows) == 6, "policy matrix incomplete")
    warn, ignore = rows["MARKDOWN", "WARN"], rows["MARKDOWN", "IGNORE"]
    require(warn["losses"] == ignore["losses"] and int(warn["losses"]) > 0 and int(warn["warnings"]) > 0 and ignore["warnings"] == "0", "Markdown actions disagree")
    require(all(rows["SVG", action]["fallback"] == "false" for action in ("ERROR", "WARN", "IGNORE")), "SVG fabricated semantic fallback")
    report = {"singleFiles": files, "formats": ["MARKDOWN", "SVG"], "rounds": 3, "policyRows": len(rows), "pdfPages": 6, "renders": renders, "localBundle": check_bundle(consumer)}
    (OUTPUT / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report))


if __name__ == "__main__":
    main()
