# SPDX-License-Identifier: Apache-2.0
"""Independent XML/ZIP, note/caption/link PDF geometry and dual-render controls."""
import argparse
import csv
import json
import re
from importlib.metadata import version
import xml.etree.ElementTree as ET
from pathlib import Path
from zipfile import ZipFile

from pypdf import PdfReader
import pypdfium2 as pdfium
from markdown_it import MarkdownIt
from verify_adr_form import render
from verify_advanced_pdf import difference, require
from verify_semantic_tables import annotations, page_logical
from verify_streaming_rich_text import painted_spans, physical_fonts
from verify_xml_model_saving import exact_pixels, semantic_xml
from verify_web_exports import Tree, safe

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "examples/build/xml-semantic-saving"
OUTPUT = ROOT / "build/verification/xml-semantic-saving"
LINKS = ["https://example.com/" + name for name in ("heading", "bullet", "number")]


def compact(text):
    return re.sub(r"\s+", "", text)


def bundle(path):
    with ZipFile(path) as archive:
        require(archive.testzip() is None, "ZIP CRC failure")
        entries = {n: archive.read(n) for n in archive.namelist()}
    root = ET.fromstring(entries["template.xml"])
    require(root.get("literal-text") == "true", "literal mode lost")
    notes = root.find("notes").findall("note")
    require([(n.get("id"), n.get("paragraph"), n.get("kind"), n.text) for n in notes] == [
        ("foot-explicit", "body", "FOOTNOTE", "FOOT-EXPLICIT ${literal}"),
        ("end", "figure-1", "ENDNOTE", "END-NOTE ${literal}"),
        ("foot-legacy", "number", "FOOTNOTE", "FOOT-LEGACY")], "note order/content/anchors changed")
    require([n.get("kind") for n in root.iter("caption")] == ["Figure", "Figure", "Table"], "caption types/order flattened")
    markers = list(root.iter("note-marker"))
    require(len(markers) == 1 and markers[0].get("target") == "foot-explicit" and markers[0].get("width") == "12.0"
            and markers[0].get("baseline-shift") == "-3.0", "explicit marker geometry changed")
    require([n.get("href") for n in root.iter("link")] == LINKS, "text block links lost")
    require(entries["fonts/Regular.ttf"] == (ROOT / "assets/fonts/LiberationSans-Regular.ttf").read_bytes(), "font bytes changed")
    require(entries["licenses/LiberationSans-OFL.txt"] == (ROOT / "assets/fonts/LiberationSans-OFL.txt").read_bytes(), "license bytes changed")
    return entries


def pdf(path, name, caption):
    reader = PdfReader(path, strict=True)
    require(len(reader.pages) == 2, "explicit page break/note pagination changed")
    physical_fonts(reader, {"LiberationSans"})
    visible = []
    with pdfium.PdfDocument(path) as document:
        for page in document:
            try:
                textpage = page.get_textpage()
                try: visible.append(compact(textpage.get_text_bounded()))
                finally: textpage.close()
            finally: page.close()
    require(visible[0].count("FOOT-EXPLICIT${literal}") == 1 and visible[0].count("FOOT-LEGACY") == 1,
            "footnote content not on anchor page")
    require("END-NOTE" not in visible[0] and visible[1].count("END-NOTE${literal}") == 1,
            "endnote not emitted once at document end")
    require(visible[0].count(caption + "${literal}") == 1 and "CAPTION-SECOND" in visible[1], "caption edit escaped target")
    logical = [compact("".join(page_logical(page))) for page in reader.pages]
    require("FIRST=Figure1" in logical[0] and "SECOND=Figure2TABLE=Table1" in logical[1], "caption counters/references changed")
    spans = painted_spans(reader)
    feet = [s for s in spans if s["text"].startswith(("[1] FOOT-EXPLICIT", "[3] FOOT-LEGACY"))]
    require(len(feet) == 2 and all(s["page"] == 0 and 400 < s["baseline"] < 455 for s in feet), "footnotes escaped reserved bottom band")
    links = annotations(reader)
    external = [n for n in links if "uri" in n]
    require([n["uri"] for n in external] == LINKS and all(n["pageIndex"] == 0 for n in external), "external PDF links changed")
    require(all(26 <= n["x"] and n["x"] + n["width"] <= 394 and 26 <= n["y"] < 400 and n["height"] > 0 for n in external),
            "text block link rectangles escaped body")
    internal = [n for n in links if "targetPageIndex" in n]
    require([n["targetPageIndex"] for n in internal] == [0, 1, 1], "caption reference destinations changed")
    a, b = render(path, OUTPUT / name)
    require(len(a) == len(b) == 2, "not all pages rendered")
    metrics = [difference(x, y, 2) for x, y in zip(a, b, strict=True)]
    require(all(m["meanAbsoluteChannelError"] < 4 and m["changedPixelFraction"] < .06 for m in metrics), "independent render mismatch")
    return a, metrics


def web():
    with (SOURCE / "policy.csv").open(newline="") as stream:
        rows = {(r["format"], r["action"]): r for r in csv.DictReader(stream)}
    for fmt, extension in (("HTML", "html"), ("MARKDOWN", "md")):
        require(rows[(fmt, "ERROR")]["losses"] == "0", "strict rejection changed")
        warn, ignored = rows[(fmt, "WARN")], rows[(fmt, "IGNORE")]
        require(int(warn["warnings"]) == int(warn["losses"]) == (12 if fmt == "HTML" else 33) and ignored["warnings"] == "0"
                and ignored["losses"] == warn["losses"], "Web action/loss mismatch")
        require(not (SOURCE / f"web-ERROR.{extension}").exists(), "strict failure published output")
        warn_text = (SOURCE / f"web-WARN.{extension}").read_text(encoding="utf-8")
        require((SOURCE / f"web-IGNORE.{extension}").read_text(encoding="utf-8") == warn_text, "WARN/IGNORE encoding differs")
        tree = Tree(MarkdownIt("commonmark").render(warn_text) if fmt == "MARKDOWN" else warn_text)
        safe(tree)
        require([n.attrs["href"] for n in tree.root.find("a")] == LINKS and "CAPTION-ORIGINAL" in tree.root.text(), "supported Web text/link content lost")
        require(all(text not in tree.root.text() for text in ("FOOT-EXPLICIT", "END-NOTE", "FOOT-LEGACY")),
                "reported note discard did not happen")
    return rows


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--consumer-build-directory", type=Path)
    args = parser.parse_args()
    versions = {name: version(name) for name in ("pypdf", "pypdfium2", "Pillow", "markdown-it-py")}
    require(versions == {"pypdf": "6.10.0", "pypdfium2": "5.13.0", "Pillow": "12.3.0", "markdown-it-py": "4.0.0"},
            "install pinned verification requirements")
    caller = (args.consumer_build_directory or Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip())) / "output"
    OUTPUT.mkdir(parents=True, exist_ok=True)
    original = bundle(SOURCE / "original.zip")
    require(bundle(SOURCE / "cycle-3.zip") == original, "three cycles changed XML/resources")
    for directory, stem, marker in ((SOURCE, "edited", "CAPTION-EDITED"), (caller, "consumer-semantics", "CAPTION-CALLER")):
        changed = bundle(directory / (stem + ".zip"))
        expected = original["template.xml"].replace(b"CAPTION-ORIGINAL", marker.encode())
        require(semantic_xml(changed["template.xml"]) == semantic_xml(expected), "caption edit changed unrelated model")
        require(all(changed[n] == original[n] for n in original if n != "template.xml"), "edit changed resources")
    controls, metrics = {}, {}
    for base, name, marker in [(SOURCE, name, "CAPTION-EDITED" if name == "edited" else "CAPTION-ORIGINAL")
                               for name in ("source", "reopened", "cycle-3", "edited")] + [(caller, "consumer-semantics", "CAPTION-CALLER")]:
        controls[name], metrics[name] = pdf(base / (name + ".pdf"), name, marker)
    for name in ("reopened", "cycle-3"):
        require(all(exact_pixels(a, b) for a, b in zip(controls["source"], controls[name], strict=True)), "unchanged model changed visible pixels")
    for name in ("edited", "consumer-semantics"):
        require([exact_pixels(a, b) for a, b in zip(controls["source"], controls[name], strict=True)] == [False, True], "caption edit escaped first page")
    web()
    (OUTPUT / "report.json").write_text(json.dumps({"zipFiles": 4, "pdfPages": 10, "modelCycles": 3, "webFiles": 4,
        "unchangedPixelExact": True, "versions": versions, "dualRender": metrics}, indent=2) + "\n")
    print("XML complete semantics: 4 ZIPs, 3 cycles, 10 PDF pages, exact controls, caption edits and Web losses verified")


if __name__ == "__main__":
    main()
