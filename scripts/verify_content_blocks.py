# SPDX-License-Identifier: Apache-2.0
"""Inspect and render complete Java/XML source-content specimens independently."""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
from importlib import metadata
import json
from pathlib import Path
import subprocess
from zipfile import ZipFile
import xml.etree.ElementTree as ET

from PIL import Image, ImageChops
from pypdf import PdfReader
from pypdf.generic import ContentStream
import pypdfium2 as pdfium

from verify_production import actual_text

FORMULAS = (r"\frac{a+b}{c}", r"\sqrt{x}", "x_1^2", r"\frac{1}{2}")
FORMULA_LINES = ("Formula enabled: " + FORMULAS[0] + ".", FORMULAS[1],
                 "\ufffc Explicit image and HTML formula " + FORMULAS[2] + ".",
                 "PAGE-TWO / Text formula enabled: " + FORMULAS[3] + ".")
LINKS = ["https://example.com/content-blocks", "https://export4j.mirakyux.com/en/"]


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def render(path, output):
    output.mkdir(parents=True)
    subprocess.run(["pdftoppm", "-png", "-r", "144", str(path), str(output / "poppler")],
                   check=True, capture_output=True, timeout=120)
    images = {"poppler": sorted(output.glob("poppler-*.png")), "pdfium": []}
    texts, geometry = [], []
    with pdfium.PdfDocument(path) as pdf:
        for index in range(len(pdf)):
            page = pdf[index]
            try:
                text = page.get_textpage()
                try:
                    texts.append(text.get_text_range())
                    geometry.append([(text.get_text_range(i, 1), tuple(round(v, 3) for v in text.get_charbox(i)))
                                     for i in range(text.count_chars()) if not text.get_text_range(i, 1).isspace()])
                    width, height = page.get_size()
                    require(all(-.1 <= left <= right <= width + .1 and -.1 <= bottom <= top <= height + .1
                                for _, (left, bottom, right, top) in geometry[-1]), "selected glyph extends beyond page")
                finally:
                    text.close()
                bitmap = page.render(scale=2)
                try:
                    target = output / f"pdfium-{index + 1}.png"
                    with bitmap.to_pil() as image:
                        image.convert("RGB").save(target)
                    images["pdfium"].append(target)
                finally:
                    bitmap.close()
            finally:
                page.close()
    require(len(images["poppler"]) == len(images["pdfium"]) == len(texts), "incomplete independent rendering")
    return "\n".join(texts), geometry, images


def inspect_pdf(path):
    reader = PdfReader(path, strict=True)
    fonts, colors, links, images = set(), set(), [], 0
    operations = Counter()
    marked_groups = []
    for page in reader.pages:
        for reference in page["/Resources"].get("/Font", {}).values():
            font = reference.get_object()
            fonts.add(str(font.get("/BaseFont")))
            descendants = font.get("/DescendantFonts")
            descendant = descendants[0].get_object() if descendants else font
            descriptor = descendant.get("/FontDescriptor")
            require(descriptor is not None, "font has no embedded descriptor")
            descriptor = descriptor.get_object()
            require(descriptor.get("/FontFile2") or descriptor.get("/FontFile3"), "implicit system font in PDF")
        for reference in page["/Resources"].get("/XObject", {}).values():
            if reference.get_object().get("/Subtype") == "/Image":
                images += 1
        for annotation in page.get("/Annots", []):
            annotation = annotation.get_object()
            action = annotation.get("/A")
            if action is not None and action.get_object().get("/URI"):
                links.append(str(action.get_object()["/URI"]))
        stack = []
        following_outline = None
        for operands, operator in ContentStream(page.get_contents(), reader).operations:
            operations[operator.decode("ascii")] += 1
            if operator in (b"BT", b"re", b"BDC") and not stack:
                following_outline = None
            if operator in (b"BDC", b"BMC"):
                group = None
                if operator == b"BDC" and len(operands) > 1:
                    properties = operands[1]
                    if not isinstance(properties, dict):
                        properties = page["/Resources"].get("/Properties", {}).get(properties, {})
                    if properties.get("/ActualText") is not None:
                        group = {"text": str(properties["/ActualText"]), "moves": 0, "segments": 0, "fills": 0}
                        marked_groups.append(group)
                stack.append(group)
            elif operator == b"EMC":
                require(stack, "unbalanced PDF marked content")
                closed = stack.pop()
                if closed is not None:
                    # The PDF backend writes the logical reading anchor first, then its
                    # filled outline, outside the invisible anchor's marked-content group.
                    following_outline = closed
            for group in [*stack, following_outline]:
                if group is not None:
                    group["moves"] += int(operator == b"m")
                    group["segments"] += int(operator in (b"l", b"c", b"v", b"y"))
                    group["fills"] += int(operator in (b"f", b"f*", b"B", b"B*"))
            if operator == b"rg":
                colors.add(tuple(round(float(v) * 255) for v in operands))
        require(not stack, "unbalanced PDF marked content")
    return {"pages": len(reader.pages), "fonts": sorted(fonts), "colors": sorted(colors),
            "links": sorted(links), "imageOccurrences": images, "operations": dict(operations),
            "actualText": actual_text(reader), "formulaGroups": marked_groups}


def inspect_docx(path):
    ns = {"w": "http://schemas.openxmlformats.org/wordprocessingml/2006/main",
          "m": "http://schemas.openxmlformats.org/officeDocument/2006/math"}
    with ZipFile(path) as archive:
        root = ET.fromstring(archive.read("word/document.xml"))
        text = "".join(element.text or "" for element in root.findall(".//w:t", ns))
        colors = sorted({element.get("{" + ns["w"] + "}val") for element in root.findall(".//w:color", ns)})
        relationships = ET.fromstring(archive.read("word/_rels/document.xml.rels"))
        links = sorted(element.get("Target") for element in relationships if element.get("TargetMode") == "External")
        attr = "{" + ns["w"] + "}"
        sizes = sorted({int(element.get(attr + "val")) for element in root.findall(".//w:sz", ns)})
        heights = sorted({int(element.get(attr + "line")) for element in root.findall(".//w:spacing", ns)
                          if element.get(attr + "lineRule") == "exact"})
        require(not root.findall(".//m:oMath", ns), "source vector degradation must not claim native OMML")
        require("\ufffc" not in text and "EXPORT4JCONTENT" not in text, "temporary marker escaped to DOCX")
        return {"text": text, "colors": colors, "links": links,
                "fontSizeHalfPoints": sizes, "exactLineHeightTwips": heights,
                "tables": len(root.findall(".//w:tbl", ns)),
                "images": len(root.findall(".//w:drawing", ns)),
                "nativeEquations": 0}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--consumer-build-directory", type=Path)
    source.add_argument("--example-directory", type=Path)
    parser.add_argument("--output-directory", type=Path, required=True)
    args = parser.parse_args()
    source = (args.consumer_build_directory / "output/content-blocks" if args.consumer_build_directory
              else args.example_directory).resolve()
    output = args.output_directory.resolve()
    output.mkdir(parents=True, exist_ok=False)
    versions = {name: metadata.version(name) for name in ("pypdf", "pypdfium2", "Pillow")}
    require(versions == {"pypdf": "6.10.0", "pypdfium2": "5.13.0", "Pillow": "12.3.0"},
            "use the pinned project verification environment")
    evidence = json.loads((source / "result.json").read_text(encoding="utf-8"))
    require(evidence["modelsEqual"] is True and evidence["docxDegradations"] == 4,
            "Java/XML equality or explicit four-vector DOCX degradation was not established")
    reference = None
    pdfs = {}
    for name in ("java", "xml"):
        path = source / (name + ".pdf")
        structure = inspect_pdf(path)
        text, geometry, images = render(path, output / name)
        require(structure["pages"] == 2, "unexpected specimen page count")
        require("CONTENT-BLOCKS" in text and "literal ${secret}" in text, "source text or exactly-once binding changed")
        require(text.count("literal ${secret}") == 4, "literal source occurrences were lost or duplicated")
        require("EXPORT4JCONTENT" not in text and "MUST-NOT-APPEAR" not in text, "internal marker or second interpolation escaped to PDF")
        require(r"$\frac{z}{2}$" in text and "$x^9$" in text, "disabled formulas or tables lost original syntax")
        require("$x^2$" in text and "|Disabled|Value||---|---||Literal|$x^9$|" in "".join(text.split()),
                "TEXT formula or complete disabled table source changed")
        require(any("LiberationSans" in font for font in structure["fonts"]), "explicit body font missing")
        require(any("Bold" in font for font in structure["fonts"]), "local physical bold font missing")
        require((0x22, 0x33, 0x44) in structure["colors"] and (0x22, 0x44, 0x66) in structure["colors"]
                and (0xCC, 0x33, 0) in structure["colors"], "global, local or explicit HTML colors changed")
        require(structure["imageOccurrences"] == 1 and structure["links"] == LINKS, "explicit image or passive links changed")
        require(structure["operations"].get("Do") == 1, "registered image is not drawn exactly once")
        require(structure["operations"].get("c", 0) > 0 and structure["actualText"], "vector formula paths or logical text missing")
        formula_text = "\n".join(structure["actualText"])
        require(tuple(structure["actualText"]) == FORMULA_LINES, "complete formula reading lines or order changed")
        require(all(formula_text.count(formula) == 1 for formula in FORMULAS), "formula logical occurrences changed")
        require(r"\frac{z}{2}" not in formula_text and "x^9" not in formula_text and "$x^2$" not in formula_text,
                "disabled formula/table or TEXT syntax unexpectedly rendered mathematics")
        require(len(structure["formulaGroups"]) == 4 and all(group["moves"] > 0 and group["segments"] > 0 and group["fills"] > 0
                                                          for group in structure["formulaGroups"]),
                "a formula logical carrier lacks actual filled vector geometry")
        if reference is None:
            reference = (structure, text, geometry, images)
        else:
            require(structure == reference[0] and text == reference[1] and geometry == reference[2],
                    "Java/XML file structure, text or selection geometry differs")
            for engine, pages in images.items():
                require(len(pages) == len(reference[3][engine]), "Java/XML page count differs")
                for index, page in enumerate(pages):
                    with Image.open(page) as first, Image.open(reference[3][engine][index]) as second:
                        require(first.size == second.size and ImageChops.difference(first.convert("RGB"), second.convert("RGB")).getbbox() is None,
                                f"Java/XML {engine} page {index + 1} pixels differ")
        pdfs[name] = {"sha256": digest(path), **structure, "logicalText": text,
                      "selectedCharacters": sum(len(page) for page in geometry)}
    docx = inspect_docx(source / "content.docx")
    require("CONTENT-BLOCKS" in docx["text"] and "literal ${secret}" in docx["text"], "DOCX lost source or literal text")
    require(docx["text"].count("literal ${secret}") == 4, "DOCX literal source occurrences changed")
    require("MUST-NOT-APPEAR" not in docx["text"], "DOCX reinterpreted a source field")
    require(docx["tables"] == 2 and docx["images"] == 1 and docx["links"] == LINKS, "DOCX tables, registered image or hyperlinks changed")
    require(all(docx["text"].count(formula) == 1 for formula in FORMULAS), "DOCX lost or duplicated vector-to-text degradation")
    require(r"$\frac{z}{2}$" in docx["text"] and "$x^9$" in docx["text"], "DOCX disabled syntax changed")
    require("$x^2$" in docx["text"] and "|Disabled|Value||---|---||Literal|$x^9$|" in "".join(docx["text"].split()),
            "DOCX TEXT formula or complete disabled table source changed")
    require({"223344", "224466", "CC3300"}.issubset(set(docx["colors"])), "DOCX global/local/explicit CSS colors changed")
    require({22, 24, 26}.issubset(set(docx["fontSizeHalfPoints"]))
            and {480, 640, 720}.issubset(set(docx["exactLineHeightTwips"])), "DOCX inherited or explicit source typography changed")
    report = {"checks": "passed", "fixture": evidence, "pdfs": pdfs, "docx": docx,
              "completePagesPerRenderer": sum(pdf["pages"] for pdf in pdfs.values()),
              "javaXmlPixelsEqual": True, "javaXmlSelectionGeometryEqual": True,
              "artifacts": {path.name: digest(path) for path in source.iterdir() if path.is_file()},
              "tools": versions,
              "visualReview": "Complete images generated; record visual inspection separately"}
    (output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Source content: complete Java/XML structure, geometry, dual rendering and DOCX checks passed ({report['completePagesPerRenderer']} pages per engine)")


if __name__ == "__main__":
    main()
