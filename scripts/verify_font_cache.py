# SPDX-License-Identifier: Apache-2.0
"""Independently compare complete PDFs emitted through published font-cache API paths."""
from __future__ import annotations

import argparse
import hashlib
from importlib import metadata
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

from PIL import Image, ImageChops
from pypdf import PdfReader
import pypdfium2 as pdfium


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def render(path, output):
    output.mkdir(parents=True)
    subprocess.run(["pdftoppm", "-png", "-r", "144", str(path), str(output / "poppler")],
                   check=True, capture_output=True, timeout=120)
    images = {"poppler": sorted(output.glob("poppler-*.png"))}
    require(len(images["poppler"]) == 1, "Poppler did not render exactly one complete page")
    with pdfium.PdfDocument(path) as pdf:
        require(len(pdf) == 1, "PDFium did not find the expected page")
        page = pdf[0]
        try:
            text = page.get_textpage()
            try:
                content = text.get_text_range()
                geometry = [(text.get_text_range(i, 1), tuple(round(value, 3) for value in text.get_charbox(i)))
                            for i in range(text.count_chars()) if not text.get_text_range(i, 1).isspace()]
            finally:
                text.close()
            bitmap = page.render(scale=2)
            try:
                target = output / "pdfium-1.png"
                with bitmap.to_pil() as image:
                    image.convert("RGB").save(target)
                images["pdfium"] = [target]
            finally:
                bitmap.close()
        finally:
            page.close()
    return content, geometry, {name: paths[0] for name, paths in images.items()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--consumer-build-directory", required=True, type=Path)
    parser.add_argument("--output-directory", required=True, type=Path)
    args = parser.parse_args()
    source = args.consumer_build_directory.resolve() / "output/font-cache"
    output = args.output_directory.resolve()
    output.mkdir(parents=True, exist_ok=False)
    properties = {entry.attrib["key"]: entry.text for entry in ET.parse(source / "consumer-report.xml").findall("entry")}
    for key in ("defaultLoaderEnabled", "defaultPdfEnabled", "passiveAutomaticExpiry", "activeClearSafe",
                "callerFaceSurvivesExpiry"):
        require(properties.get(key) == "true", "consumer did not establish " + key)
    expected = "".join(properties["expectedText"].split())
    names = ("disabled", "cold", "warm", "expired", "page-session", "spool")
    reference = None
    results = {}
    for name in names:
        path = source / (name + ".pdf")
        reader = PdfReader(path)
        require(len(reader.pages) == 1, "unexpected page count: " + name)
        page = reader.pages[0]
        fonts = page["/Resources"]["/Font"].get_object()
        require(fonts, "no PDF fonts: " + name)
        embedded = []
        for value in fonts.values():
            font = value.get_object()
            descendants = font.get("/DescendantFonts")
            descendant = descendants[0].get_object() if descendants else font
            descriptor = descendant.get("/FontDescriptor")
            require(descriptor is not None, "missing embedded font descriptor: " + name)
            descriptor = descriptor.get_object()
            program = descriptor.get("/FontFile2") or descriptor.get("/FontFile3")
            require(program is not None, "font unexpectedly became an implicit system font: " + name)
            data = program.get_object().get_data()
            embedded.append(hashlib.sha256(data).hexdigest())
        text, geometry, images = render(path, output / name)
        require("".join(text.split()) == expected, "logical text differs: " + name)
        if reference is None:
            reference = (geometry, images)
        else:
            require(geometry == reference[0], "glyph selection geometry differs from disabled cache: " + name)
            for engine in images:
                with Image.open(reference[1][engine]) as first, Image.open(images[engine]) as second:
                    require(first.size == second.size, engine + " page dimensions changed: " + name)
                    require(ImageChops.difference(first.convert("RGB"), second.convert("RGB")).getbbox() is None,
                            engine + " pixels differ from disabled cache: " + name)
        results[name] = {"sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "pages": 1,
                         "embeddedFontPrograms": embedded, "selectedCharacters": len(geometry),
                         "popplerPixelsEqualDisabled": True, "pdfiumPixelsEqualDisabled": True}
    report = {"checks": "passed", "consumerBuildDirectory": str(args.consumer_build_directory),
              "consumer": properties, "pdfs": results, "pagesRenderedPerEngine": len(results),
              "comparison": "Exact same-engine pixels and PDFium character geometry against disabled caching",
              "tools": {name: metadata.version(name) for name in ("pypdf", "pypdfium2", "Pillow")},
              "visualReview": "Full images generated; record visual inspection separately"}
    (output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("Font-cache PDF structure, logical text, geometry and both independent renderers passed for six complete pages")


if __name__ == "__main__":
    main()
