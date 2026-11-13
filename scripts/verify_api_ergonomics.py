#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Independently verify the API-ergonomics consumer's files and rendered pages.

Requires pypdf, PDFium, Pillow and Poppler. Pass the build directory of a successful
consumer invocation; no source-tree classes or implicit previous-run pointer is used.
All reports and rendered images remain under the repository's ignored build directory.
"""
from __future__ import annotations

import argparse
from contextlib import closing
from copy import copy
from hashlib import sha256
from html.parser import HTMLParser
import json
from math import hypot
from pathlib import Path
import shutil
import subprocess
import zipfile
import xml.etree.ElementTree as ET

from PIL import Image, ImageDraw
from pypdf import PdfReader
from pypdf.generic import ContentStream, NameObject
import pypdfium2 as pdfium

from verify_advanced_pdf import difference


ROOT = Path(__file__).resolve().parents[1]
PDF_NAMES = ("direct", "styles", "from-docx", "from-html", "from-markdown", "stream", "semantic", "replay", "budgets")
DOCX_NAMES = ("direct", "paged", "sectioned", "roundtrip", "from-html")
IDENTITY = (1.0, 0.0, 0.0, 1.0, 0.0, 0.0)


def concat(current, appended):
    """Concatenate a PDF cm matrix to a current six-value transformation matrix."""
    a, b, c, d, e, f = current
    aa, bb, cc, dd, ee, ff = appended
    return (a * aa + c * bb, b * aa + d * bb,
            a * cc + c * dd, b * cc + d * dd,
            a * ee + c * ff + e, b * ee + d * ff + f)


def image_draw_sizes(reader, page):
    """Read direct Image Do dimensions in this fixture's content order, measured in page points.

    This fixture has no Form XObjects. Unexpected Do structures fail explicitly instead
    of being skipped or interpreted as successfully checked image geometry.
    """
    resources = page["/Resources"].get_object()
    xobjects = resources["/XObject"].get_object()
    current = IDENTITY
    saved = []
    sizes = []
    placements = []
    for operands, operator in ContentStream(page.get("/Contents"), reader).operations:
        if operator == b"q":
            saved.append(current)
        elif operator == b"Q":
            assert saved, "unbalanced graphics-state restore in image fixture"
            current = saved.pop()
        elif operator == b"cm":
            assert len(operands) == 6, "unexpected image transformation matrix"
            current = concat(current, tuple(float(value) for value in operands))
        elif operator == b"Do":
            assert len(operands) == 1 and operands[0] in xobjects, "missing fixture XObject"
            image = xobjects[operands[0]].get_object()
            assert image.get("/Subtype") == "/Image", "fixture geometry checker requires direct image XObjects"
            sizes.append((hypot(current[0], current[1]), hypot(current[2], current[3])))
            placements.append(current)
    assert not saved, "unbalanced graphics-state save in image fixture"
    expected = ((72.0, 36.0), (40.0, 20.0))
    assert len(sizes) == len(expected), sizes
    for actual, target in zip(sizes, expected):
        assert abs(actual[0] - target[0]) <= 1e-3 and abs(actual[1] - target[1]) <= 1e-3, sizes
    return sizes, placements


def verify_mixed_text(reader, page, name, placements):
    """Check native text and the two image anchors in actual content order, not resource order."""
    stream = ContentStream(page["/Contents"], reader, forced_encoding="bytes")
    native_operations, markers, saved_modes = [], [], []
    marker = None
    rendering_mode, font_size, horizontal_scale = 0, None, 100
    text_matrix = IDENTITY
    for operands, operator in stream.operations:
        if operator == b"BDC":
            assert marker is None and operands[0] == "/Span", (name, "unexpected marked content")
            properties = operands[1].get_object()
            if isinstance(properties, str):
                properties = page["/Resources"]["/Properties"][properties].get_object()
            assert properties.get("/ActualText") == "\ufffc", (name, properties)
            marker = {"shows": 0}
            continue
        if operator == b"EMC":
            assert marker is not None and marker["shows"] == 1, (name, marker)
            markers.append(marker)
            marker = None
            continue
        assert operator != b"BMC", (name, "unexpected marked content")
        if marker is None:
            native_operations.append((operands, operator))
        if operator == b"q":
            saved_modes.append(rendering_mode)
        elif operator == b"Q":
            assert saved_modes, (name, "unbalanced graphics state")
            rendering_mode = saved_modes.pop()
        elif operator == b"Tr":
            rendering_mode = int(operands[0])
        elif operator == b"Tf":
            font_size = float(operands[1])
        elif operator == b"Tz":
            horizontal_scale = float(operands[0])
        elif operator == b"Tm":
            text_matrix = tuple(float(value) for value in operands)
        elif operator in (b"Tj", b"TJ", b"'", b'"'):
            if marker is None:
                assert rendering_mode == 0, (name, "native text must remain visible")
            else:
                assert operator == b"Tj" and rendering_mode == 3 and font_size == 1, (name, operands)
                marker.update(shows=marker["shows"] + 1, matrix=text_matrix, scale=horizontal_scale)
    assert marker is None and not saved_modes and len(markers) == len(placements) == 2, (name, markers)
    for anchor, image in zip(markers, placements, strict=True):
        matrix = anchor["matrix"]
        width, height = hypot(image[0], image[1]), hypot(image[2], image[3])
        assert matrix[:4] == (1, 0, 0, -1), (name, matrix)
        # This fixture's one-point invisible x anchor spans the image width and
        # sits one fifth of its height above the bottom. Check each occurrence.
        assert abs(matrix[4] - image[4]) < 1e-3, (name, matrix, image)
        assert abs(float(page.mediabox.height) - matrix[5] - image[5] - height / 5) < 1e-3, (name, matrix, image)
        assert abs(anchor["scale"] - width * 200) < 1e-3, (name, anchor, image)
    stream.operations = native_operations
    native_page = copy(page)
    native_page[NameObject("/Contents")] = stream
    assert native_page.extract_text() == "Ergonomics body.\nLOGO ", (name, native_page.extract_text())
    return {"nativeText": "Ergonomics body.\nLOGO ", "imageMarkers": len(markers)}


def verify_pdfs(source):
    actual_names = {path.stem for path in source.glob("*.pdf")}
    assert actual_names == set(PDF_NAMES), {"missing": sorted(set(PDF_NAMES) - actual_names),
                                          "unexpected": sorted(actual_names - set(PDF_NAMES))}
    pdfs = []
    drawing_sizes = {}
    for name in PDF_NAMES:
        path = source / (name + ".pdf")
        with closing(PdfReader(path)) as pdf:
            assert len(pdf.pages) == 1, name
            page = pdf.pages[0]
            text = page.extract_text()
            details = {}
            if name in ("direct", "from-docx"):
                assert text == "Ergonomics body.\nLOGO \nx\nx", (name, text)
                with pdfium.PdfDocument(path) as document:
                    with closing(document[0]) as rendered_page, closing(rendered_page.get_textpage()) as text_page:
                        extracted = text_page.get_text_range().replace("\r\n", "\n")
                        assert extracted == "Ergonomics body.\nLOGO \ufffc\n\ufffc", (name, ascii(extracted))
                assert abs(float(page.mediabox.width) - 841.88976) < .1, name
                assert abs(float(page.mediabox.height) - 595.27559) < .1, name
                images = list(page.images)
                assert images, name
                for item in images:
                    with item.image.convert("RGB") as image:
                        assert image.size == (20, 10), (name, image.size)
                        assert image.getpixel((0, 0)) == (255, 0, 0), name
                        assert image.getpixel((19, 0)) == (0, 0, 255), name
                drawing_sizes[name], placements = image_draw_sizes(pdf, page)
                details["mixedText"] = verify_mixed_text(pdf, page, name, placements)
            elif name == "styles":
                assert text.split() == ["REGULAR", "BOLD", "ITALIC", "COMBINED"], text
                fonts = page["/Resources"]["/Font"].get_object()
                bases = {str(font.get_object()["/BaseFont"]).split("+")[-1].lstrip("/") for font in fonts.values()}
                assert {"LiberationSans", "LiberationSans-Bold", "LiberationSans-Italic", "LiberationSans-BoldItalic"} <= bases, bases
            elif name == "from-html":
                assert text.strip() == "HTML import body.", text
            elif name == "from-markdown":
                assert text.strip() == "Markdown import body.", text
            elif name == "budgets":
                assert text.splitlines() == ["ID", "R1", "R2"], (name, text)
            else:
                assert text.splitlines() == ["ID", "R1", "R2", "R3"], (name, text)
        pdfs.append({"file": name + ".pdf", "pages": 1, **details})
    return pdfs, drawing_sizes


def verify_docx(source):
    namespaces = {"w": "http://schemas.openxmlformats.org/wordprocessingml/2006/main",
                  "wp": "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"}
    logo_hash = sha256((source / "logo.png").read_bytes()).hexdigest()
    assert {path.stem for path in source.glob("*.docx")} == set(DOCX_NAMES)
    for name in DOCX_NAMES:
        with zipfile.ZipFile(source / (name + ".docx")) as archive:
            assert archive.testzip() is None
            assert len(archive.namelist()) == len(set(archive.namelist()))
            for entry in archive.namelist():
                if entry.endswith(".xml") or entry.endswith(".rels"):
                    ET.fromstring(archive.read(entry))
            tree = ET.fromstring(archive.read("word/document.xml"))
            text = "".join(node.text or "" for node in tree.findall(".//w:t", namespaces))
            if name == "from-html":
                assert text == "HTML import body.", (name, text)
            else:
                assert "Ergonomics body." in text and "LOGO" in text, (name, text)
                sizes = {(int(node.attrib["cx"]), int(node.attrib["cy"])) for node in tree.findall(".//wp:extent", namespaces)}
                assert sizes == {(914400, 457200), (508000, 254000)}, (name, sizes)
                images = [sha256(archive.read(entry)).hexdigest() for entry in archive.namelist() if entry.startswith("word/media/")]
                assert images and all(value == logo_hash for value in images), name
                page = tree.find(".//w:pgSz", namespaces)
                assert int(page.attrib["{" + namespaces["w"] + "}w"]) > int(page.attrib["{" + namespaces["w"] + "}h"]), name
            if name == "paged":
                headers = [entry for entry in archive.namelist() if entry.startswith("word/header") and entry.endswith(".xml")]
                assert headers and any("HEADER PRESERVED" in archive.read(entry).decode("utf-8") for entry in headers)


class Text(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.data = []

    def handle_data(self, text):
        self.data.append(text)


def verify_html_and_cleanup(source):
    html = Text()
    html.feed((source / "from-markdown.html").read_text(encoding="utf-8"))
    html.close()
    assert "Markdown import body." in "".join(html.data)
    assert list((source / "temporary").iterdir()) == []


def render_and_compare(source, output, pdftoppm):
    cross = []
    for name in PDF_NAMES:
        target = output / "rendered" / name
        target.mkdir(parents=True, exist_ok=True)
        subprocess.run([pdftoppm, "-r", "144", "-png", str(source / (name + ".pdf")), str(target / "poppler")],
                       check=True, capture_output=True, timeout=60)
        with pdfium.PdfDocument(source / (name + ".pdf")) as document:
            with closing(document[0]) as page, closing(page.render(scale=2)) as bitmap:
                with bitmap.to_pil().convert("RGB") as image:
                    image.save(target / "pdfium-1.png")
        comparison = difference(target / "poppler-1.png", target / "pdfium-1.png", blur_radius=1)
        assert comparison["meanAbsoluteChannelError"] < 4 and comparison["changedPixelFraction"] < .06, (name, comparison)
        cross.append({"file": name, **comparison})
    for renderer in ("poppler-1.png", "pdfium-1.png"):
        with Image.open(output / "rendered" / "stream" / renderer) as first:
            for name in ("semantic", "replay"):
                with Image.open(output / "rendered" / name / renderer) as other:
                    assert first.size == other.size and first.tobytes() == other.tobytes(), (renderer, name)
    return cross


def contact_images(output):
    rows = (len(PDF_NAMES) + 3) // 4
    with Image.new("RGB", (1440, 450 * rows), "#eeeeee") as sheet:
        draw = ImageDraw.Draw(sheet)
        for index, name in enumerate(PDF_NAMES):
            x, y = (index % 4) * 360, (index // 4) * 450
            draw.text((x + 8, y + 6), name, fill="black")
            with Image.open(output / "rendered" / name / "poppler-1.png") as page:
                page.thumbnail((348, 414))
                sheet.paste(page, (x + 6, y + 28))
        sheet.save(output / "contact.png")
    with Image.open(output / "rendered" / "styles" / "poppler-1.png") as styles:
        with styles.crop((85, 80, 950, 175)) as detail:
            detail.save(output / "styles-detail.png")


def root_path(path):
    return (ROOT / path).resolve() if not path.is_absolute() else path.resolve()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--consumer-build-directory", type=Path, required=True,
                        help="Build directory of this run's successful API-ergonomics consumer")
    parser.add_argument("--output-directory", type=Path, default=Path("build/verification/api-ergonomics-output"),
                        help="Report and rendering directory, constrained to this repository's build directory")
    parser.add_argument("--pdftoppm", help="Poppler pdftoppm executable; defaults to discovery on PATH")
    args = parser.parse_args()
    source = root_path(args.consumer_build_directory) / "output" / "ergonomics"
    output = root_path(args.output_directory)
    build = (ROOT / "build").resolve()
    if output == build or not output.is_relative_to(build):
        raise ValueError("Verification outputs must remain inside the repository's build directory")
    pdftoppm = shutil.which(args.pdftoppm or "pdftoppm")
    if pdftoppm is None:
        raise RuntimeError("Poppler pdftoppm is required; install it on PATH or pass --pdftoppm")
    output.mkdir(parents=True, exist_ok=True)
    (output / "report.json").unlink(missing_ok=True)
    pdfs, drawing_sizes = verify_pdfs(source)
    verify_docx(source)
    verify_html_and_cleanup(source)
    cross = render_and_compare(source, output, pdftoppm)
    contact_images(output)
    report = {"checks": "passed", "pdfs": pdfs, "docxFiles": len(DOCX_NAMES), "crossRenderer": cross,
              "streamingVariantsPixelIdentical": True, "realFontVariants": 4,
              "imageBytesAndGeometryVerified": True, "pdfImageDrawingSizesPt": drawing_sizes}
    (output / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
