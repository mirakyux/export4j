# SPDX-License-Identifier: Apache-2.0
"""Independent XML/ZIP and final reference PDF content, destinations and render controls."""
import argparse
import csv
import hashlib
from importlib.metadata import version
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from markdown_it import MarkdownIt
from pypdf import PdfReader
import pypdfium2 as pdfium
from verify_adr_form import render
from verify_advanced_pdf import difference, require
from verify_production import check_bundle
from verify_semantic_tables import annotations
from verify_streaming_rich_text import painted_spans, physical_fonts
from verify_web_exports import Tree, safe
from verify_xml_model_saving import exact_pixels, semantic_xml

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "examples/build/xml-cross-reference-saving"
OUTPUT = ROOT / "build/verification/xml-cross-reference-saving"
PREFIX = "PAGE-ORIGINAL: this reference wraps across several words before the target page = "


def compact(text):
    return re.sub(r"\s+", "", text)


def bundle(path):
    with ZipFile(path) as archive:
        require(archive.testzip() is None, "reference ZIP CRC failure")
        require(len(archive.namelist()) == len(set(archive.namelist())) == 3, "reference ZIP resource inventory differs")
        entries = {name: archive.read(name) for name in archive.namelist()}
    require(entries["fonts/Regular.ttf"] == (ROOT / "assets/fonts/LiberationSans-Regular.ttf").read_bytes(), "reference font changed")
    require(entries["licenses/LiberationSans-OFL.txt"] == (ROOT / "assets/fonts/LiberationSans-OFL.txt").read_bytes(), "reference license changed")
    root = ET.fromstring(entries["template.xml"])
    require(root.get("literal-text") == "true", "reference literal mode changed")
    return entries, root


def reference_pdf(path, name, marker="ORIGINAL", target_page=2):
    reader = PdfReader(path, strict=True)
    dynamic = name == "dynamic"
    expected_pages = 1 if dynamic else 3
    require(len(reader.pages) == expected_pages, "reference pagination changed")
    physical_fonts(reader, {"LiberationSans"})
    text = []
    with pdfium.PdfDocument(path) as document:
        for page in document:
            try:
                tp = page.get_textpage()
                try: text.append(compact(tp.get_text_bounded()))
                finally: tp.close()
            finally: page.close()
    links = annotations(reader)
    if dynamic:
        require(text[0].count("LOCAL=Figure1") == text[0].count("LOCAL=Figure2") == 1, "loop reference resolved to another copy")
        require(text[0].count("DYNAMIC=1") == 2 and text[0].count("LOOP-A") == text[0].count("LOOP-B") == 1,
                "dynamic full target or loop text changed")
        require([item["targetPageIndex"] for item in links] == [0, 0, 0, 0], "dynamic reference destinations changed")
    else:
        prefix = PREFIX.replace("ORIGINAL", marker)
        require(text[0].count(compact(prefix) + ("12" if target_page == 2 else "3")) == 1, "wrapped page reference has wrong final displayed number")
        require(text[0].count("NUMBER-TARGET=Figure2") == text[0].count("ITEM-TARGET=4") == 1, "numbered reference label changed")
        require(text[1].count("BACK-PAGE=3") == text[1].count("MIDDLE-PAGE${literal}") == 1,
                "backward reference or literal body changed")
        require(text[2].count("Figure2.CAPTION-B") == text[2].count("TARGET-FINAL") == 1, "forward target source lost")
        require([(item["pageIndex"], item["targetPageIndex"]) for item in links] == [(0, target_page), (0, 2), (0, 0), (1, 0)],
                "retargeting changed an unrelated reference or physical destination")
        require(links[0]["height"] >= 40, "long reference no longer has a multiline reservation")
        spans = painted_spans(reader)
        require(all(27.9 <= s["x"] < s["right"] <= 392.1 and 28 <= s["baseline"] <= 412 for s in spans),
                "reference text escaped the body")
        for item in links:
            require(28 <= item["x"] and item["x"] + item["width"] <= 392.1 and 28 <= item["y"]
                    and item["y"] + item["height"] <= 412.1, "reference link rectangle escaped the body")
    a, b = render(path, OUTPUT / name)
    require(len(a) == len(b) == expected_pages, "not all reference pages rendered")
    metrics = [difference(x, y, 2) for x, y in zip(a, b, strict=True)]
    require(all(m["meanAbsoluteChannelError"] < 4 and m["changedPixelFraction"] < .06 for m in metrics), "reference dual render differs")
    return a, metrics


def web():
    with (SOURCE / "policy.csv").open(newline="") as stream:
        rows = {(r["format"], r["action"]): r for r in csv.DictReader(stream)}
    for fmt, extension in (("HTML", "html"), ("MARKDOWN", "md")):
        require(rows[(fmt, "ERROR")]["losses"] == "0" and not (SOURCE / f"web-ERROR.{extension}").exists(), "strict reference failure published output")
        require(rows[(fmt, "WARN")]["warnings"] == rows[(fmt, "WARN")]["losses"] == rows[(fmt, "IGNORE")]["losses"] == "1"
                and rows[(fmt, "IGNORE")]["warnings"] == "0", "reference action/loss report differs")
        encoded = (SOURCE / f"web-WARN.{extension}").read_text(encoding="utf-8")
        require(encoded == (SOURCE / f"web-IGNORE.{extension}").read_text(encoding="utf-8"), "reference WARN/IGNORE encoding differs")
        tree = Tree(MarkdownIt("commonmark").render(encoded) if fmt == "MARKDOWN" else encoded)
        safe(tree)
        require("WEB-REFERENCE=[unresolved reference]" in tree.root.text() and "WEB-TARGET" in tree.root.text(), "actual reference degradation differs")


def libraries(consumer):
    check_bundle(consumer)
    components = json.loads((ROOT / "build/reports/sbom.cdx.json").read_text(encoding="utf-8"))["components"]
    own = [item for item in components if item["group"] == "com.mirakyux.export4j"]
    require(len(own) == 15, "reference library inventory changed")
    archives = list((consumer / "distributions").glob("*.zip"))
    require(len(archives) == 1, "reference caller distribution ambiguous")
    with ZipFile(archives[0]) as archive:
        for item in own:
            matches = [n for n in archive.namelist() if n.endswith("/" + item["name"] + "-" + item["version"] + ".jar")]
            require(len(matches) == 1 and hashlib.sha256(archive.read(matches[0])).hexdigest() == item["hashes"][0]["content"],
                    "reference caller distribution has stale library bytes")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--consumer-build-directory", type=Path)
    args = parser.parse_args()
    versions = {name: version(name) for name in ("pypdf", "pypdfium2", "Pillow", "markdown-it-py")}
    require(versions == {"pypdf": "6.10.0", "pypdfium2": "5.13.0", "Pillow": "12.3.0", "markdown-it-py": "4.0.0"}, "install pinned verification requirements")
    consumer = args.consumer_build_directory or Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip())
    libraries(consumer)
    caller = consumer / "output"
    OUTPUT.mkdir(parents=True, exist_ok=True)
    original, root = bundle(SOURCE / "original.zip")
    require(bundle(SOURCE / "cycle-3.zip")[0] == original, "three reference cycles changed XML/resources")
    require([(s.get("id"), s.get("first-page-number")) for s in root.findall("section")] == [("first", "3"), ("second", "12")], "reference section numbering lost")
    refs = list(root.iter("cross-reference"))
    require([(r.get("id"), r.get("kind"), r.get("target")) for r in refs] == [
        ("ref-page", "PAGE", "figure-b"), ("ref-number", "NUMBER", "figure-b"), ("ref-item", "NUMBER", "item-a"),
        ("ref-back", "PAGE", "figure-a")], "reference kinds/order/targets lost")
    require(refs[0].text == PREFIX and all(r.get("first-indent") == "4.0" and r.get("subsequent-indent") == "6.0"
            and r.get("size") == "11.0" and r.get("line-height") == "20.0" for r in refs), "reference prefix/format changed")
    for base, stem, marker in ((SOURCE, "edited", "EDITED"), (caller, "consumer-cross-reference", "CALLER")):
        entries, _ = bundle(base / (stem + ".zip"))
        expected = ET.fromstring(original["template.xml"])
        ref = expected.find(".//cross-reference[@id='ref-page']")
        ref.set("target", "figure-a"); ref.text = PREFIX.replace("ORIGINAL", marker)
        require(semantic_xml(entries["template.xml"]) == semantic_xml(ET.tostring(expected)), "reference edit changed unrelated XML")
        require(all(entries[n] == original[n] for n in original if n != "template.xml"), "reference edit changed resources")
    dynamic, dynamic_root = bundle(SOURCE / "dynamic.zip")
    require("@" not in dynamic["template.xml"].decode(), "rendered loop IDs were not explicitly renamed")
    require(len(list(dynamic_root.iter("cross-reference"))) == 4, "dynamic reference model lost")
    controls, metrics = {}, {}
    for base, name, marker, page in [(SOURCE, name, "EDITED" if name == "edited" else "ORIGINAL", 0 if name == "edited" else 2)
                                     for name in ("source", "reopened", "cycle-3", "edited")] + [(caller, "consumer-cross-reference", "CALLER", 0)]:
        controls[name], metrics[name] = reference_pdf(base / (name + ".pdf"), name, marker, page)
    _, metrics["dynamic"] = reference_pdf(SOURCE / "dynamic.pdf", "dynamic")
    for name in ("reopened", "cycle-3"):
        require(all(exact_pixels(a, b) for a, b in zip(controls["source"], controls[name], strict=True)), "unchanged references changed pixels")
    for name in ("edited", "consumer-cross-reference"):
        require([exact_pixels(a, b) for a, b in zip(controls["source"], controls[name], strict=True)] == [False, True, True], "reference edit escaped its page")
    web()
    (OUTPUT / "report.json").write_text(json.dumps({"zipFiles": 5, "modelCycles": 3, "pdfPages": 16, "webFiles": 4,
        "unchangedPixelExact": True, "consumerLibraries": 15, "versions": versions, "dualRender": metrics}, indent=2) + "\n")
    print("XML independent references: 5 ZIPs, 3 cycles, 16 PDF pages, retargeted destinations, lexical copies and Web actions verified")


if __name__ == "__main__":
    main()
