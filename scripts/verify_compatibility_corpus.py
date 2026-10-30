#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Independently inspect all 4x4 conversions, loss actions and rejected-input controls."""
import argparse
import csv
import hashlib
from importlib.metadata import version
import itertools
import json
from pathlib import Path
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from markdown_it import MarkdownIt
from pypdf import PdfReader
from verify_adr_form import render
from verify_advanced_pdf import difference, require
from verify_production import contact_sheet
from verify_web_exports import Tree, safe

ROOT = Path(__file__).resolve().parents[1]
W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"


def rows(path):
    with path.open(encoding="utf-8", newline="") as stream:
        return list(csv.DictReader(stream, delimiter="\t"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--consumer-build-directory", type=Path, required=True)
    parser.add_argument("--fixtures", type=Path, default=ROOT / "build/verification/compatibility-fixtures")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    products = args.consumer_build_directory / "output/compatibility-corpus"
    cases = rows(products / "cases.tsv")
    formats = ("HTML", "MARKDOWN", "DOCX", "PDF")
    expected = set(itertools.product(formats, formats, ("WARN", "IGNORE")))
    require(len(cases) == 32 and {(row["source"], row["target"], row["action"]) for row in cases} == expected, "conversion corpus scope changed")
    manifest = json.loads((args.fixtures / "manifest.json").read_text(encoding="utf-8"))
    require(len(manifest["files"]) == 10, "independent input corpus is incomplete")
    for name, item in manifest["files"].items():
        require(hashlib.sha256((args.fixtures / name).read_bytes()).hexdigest() == item["sha256"], "fixture bytes changed: " + name)
    markdown = MarkdownIt("commonmark")
    losses = {}; text_by_case = {}; hashes = {}; tiles = []; rendering = {}
    for row in cases:
        path = products / row["file"]
        require(path.name == row["file"] and path.is_file(), "invalid corpus output path")
        key = row["source"], row["target"], row["action"]
        if row["target"] == "PDF":
            reader = PdfReader(path)
            require(len(reader.pages) == 1, "unexpected corpus PDF page count")
            text = reader.pages[0].extract_text()
            first, second = render(path, args.output / path.stem)
            require(len(first) == len(second) == 1, "missing independent rendered page")
            metric = difference(first[0], second[0], 2)
            require(metric["meanAbsoluteChannelError"] < 4 and metric["changedPixelFraction"] < .06, "PDF renderer disagreement")
            rendering[path.name] = metric
            tiles.extend([(path.name + " Poppler", first[0]), (path.name + " PDFium", second[0])])
        elif row["target"] == "DOCX":
            with ZipFile(path) as package:
                require(package.testzip() is None, "invalid output ZIP CRC")
                document = ET.fromstring(package.read("word/document.xml"))
                text = "".join(node.text or "" for node in document.iter(W + "t"))
                if row["source"] in {"HTML", "DOCX", "PDF"}:
                    kept = next(p for p in document.iter(W + "p") if "CORPUS-KEEP" in "".join(p.itertext()))
                    require(any(node.get(W + "val") == "123456" for node in kept.iter(W + "color")), "unchanged color was lost")
        else:
            raw = path.read_text(encoding="utf-8")
            tree = Tree(markdown.render(raw) if row["target"] == "MARKDOWN" else raw); safe(tree)
            text = tree.root.text()
        for token in ("CORPUS-EDITED", "CORPUS-KEEP"):
            require(text.count(token) == 1, "missing or repeated semantic content: " + path.name + ": " + token)
        require("CORPUS-ORIGINAL" not in text, "edit did not reach final bytes")
        text_by_case[key] = text
        actual = rows(path.with_name(path.name + ".losses.tsv"))
        require(all(issue["action"] == row["action"] for issue in actual), "loss action differs from requested action")
        require(sum(issue["treatment"] in {"DEGRADE", "DISCARD"} for issue in actual) == int(row["losses"]), "actual loss count differs")
        require(sum(issue["action"] == "WARN" for issue in actual) == int(row["warnings"]), "warning count differs")
        losses[key] = [{name: value for name, value in issue.items() if name != "action"} for issue in actual]
        hashes[path.name] = hashlib.sha256(path.read_bytes()).hexdigest()
    for source, target in itertools.product(formats, formats):
        require(losses[source, target, "WARN"] == losses[source, target, "IGNORE"], "IGNORE suppressed actual losses")
        require(text_by_case[source, target, "WARN"] == text_by_case[source, target, "IGNORE"], "WARN/IGNORE semantic content differs")
    require(any(issue["feature"] == "TEXT_STYLE" for issue in losses["HTML", "MARKDOWN", "WARN"]), "untouched color loss is absent")
    require(any(issue["feature"] == "PDF_LAYOUT" for issue in losses["PDF", "DOCX", "WARN"]), "PDF reconstruction loss is absent")
    guards = rows(products / "guards.tsv")
    expected_guards = set(manifest["files"]) | {"strict-loss-protects-target", "output-byte-budget",
                                              "cancelled-output-protects-target", "offline-css-under-all-actions", "xml-external-entity"}
    require(len(guards) == len(expected_guards) and {row["case"] for row in guards} == expected_guards, "missing rejection or compatibility controls")
    require(all(int(row["checks"]) == (8 if row["case"].startswith("bad-") else 3 if row["case"] == "offline-css-under-all-actions" else 1) for row in guards), "guard scope changed")
    require((products / "protected.docx").read_bytes() == bytes([1, 2, 3]), "package edit replaced old target")
    require((products / "protected.md").read_bytes() == bytes([4, 5, 6]), "conversion replaced old target")
    require(not list(products.glob(".export4j-*")), "temporary output leaked")
    contact_sheet(tiles, args.output)
    report = {"status": "passed", "conversionPaths": 16, "outputs": len(cases), "guardGroups": len(guards),
              "guardChecks": sum(int(row["checks"]) for row in guards), "independentPdfPages": len(rendering),
              "fixtureManifestSha256": hashlib.sha256((args.fixtures / "manifest.json").read_bytes()).hexdigest(),
              "outputSha256": hashes, "rendering": rendering,
              "tools": {name: version(name) for name in ("pypdf", "pypdfium2", "Pillow", "markdown-it-py")},
              "scope": "fixed programmatic compatibility corpus; desktop reader interaction excluded"}
    (args.output / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({name: value for name, value in report.items() if name not in {"outputSha256", "rendering"}}, indent=2))


if __name__ == "__main__":
    main()
