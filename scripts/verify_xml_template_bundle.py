# SPDX-License-Identifier: Apache-2.0
"""Independent ZIP/source/resource, standard DOCX and all-page dual-render verification."""
from __future__ import annotations

import argparse
from io import BytesIO
import json
from pathlib import Path
import xml.etree.ElementTree as ET
from zipfile import ZipFile
from PIL import Image
from pypdf import PdfReader
from verify_advanced_pdf import difference, require
from verify_adr_form import render
from verify_production import contact_sheet, extracted

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build/verification/xml-template-bundle"
EXAMPLES = ROOT / "examples/build/xml-template-bundle"
W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
ENTRIES = {"template.xml", "fragments/intro.xml", "images/logo.png", "fonts/Regular.ttf", "licenses/LiberationSans-OFL.txt"}


def entries(path):
    with ZipFile(path) as archive:
        require(archive.testzip() is None, "ZIP CRC validation failed")
        names = [item.filename for item in archive.infolist() if not item.is_dir()]
        require(set(names) == ENTRIES and len(names) == len(ENTRIES), "template entry inventory differs")
        result = {name: archive.read(name) for name in names}
    require(result["fonts/Regular.ttf"] == (ROOT / "assets/fonts/LiberationSans-Regular.ttf").read_bytes(), "font bytes changed")
    require(result["licenses/LiberationSans-OFL.txt"] == (ROOT / "assets/fonts/LiberationSans-OFL.txt").read_bytes(), "font license changed")
    root = ET.fromstring(result["template.xml"])
    require(root.tag == "document" and root.get("version") == "1", "existing XML schema lost")
    source = result["template.xml"].decode("utf-8")
    require(all(token in source for token in ("${title}", "${item.name}", '<if test="approved">', '<each items="items"', '<rows items="items"')), "binding source was replaced with rendered data")
    return result


def edits(before, after, old, new, old_fragment, new_fragment):
    require(after["template.xml"] == before["template.xml"].replace(old.encode(), new.encode()), "source editing changed unrelated bytes")
    require(after["fragments/intro.xml"] == before["fragments/intro.xml"].replace(old_fragment.encode(), new_fragment.encode()), "fragment editing changed unrelated bytes")
    for name in ENTRIES - {"template.xml", "fragments/intro.xml"}:
        require(before[name] == after[name], "untouched resource changed: " + name)


def docx(path, markers, rows, image):
    with ZipFile(path) as archive:
        root = ET.fromstring(archive.read("word/document.xml"))
        text = " ".join(value.text or "" for value in root.iter(W + "t"))
        for marker in markers:
            require(text.count(marker) == 1, "DOCX missing/duplicate template content: " + marker)
        tables = list(root.iter(W + "tbl"))
        require(len(tables) == 1, "DOCX native table missing")
        actual = [["".join(node.text or "" for node in cell.iter(W + "t")) for cell in row.findall(W + "tc")] for row in tables[0].findall(W + "tr")]
        require(actual == [["Name", "Count"], *rows], "DOCX dynamic rows changed")
        native_images = [name for name in archive.namelist() if name.startswith("word/media/")]
        require(len(native_images) == 1 and archive.read(native_images[0]) == image, "DOCX image bytes changed")
        size = next(root.iter(W + "pgSz"))
        require(size.get(W + "w") == "8400" and size.get(W + "h") == "10000", "DOCX source paper settings changed")


def pdf(path, name, markers, forbidden, contacts):
    reader = PdfReader(path)
    require(len(reader.pages) == 1, "unexpected PDF pagination")
    page = reader.pages[0]
    require(float(page.mediabox.width) == 420 and float(page.mediabox.height) == 500, "PDF paper settings changed")
    text = extracted(path)
    for marker in markers:
        require(text.count(marker) == 1, "PDF missing/duplicate bound text: " + marker)
    require(all(marker not in text for marker in forbidden) and "${" not in text, "PDF has stale/unbound text")
    require(len(page.images) == 1, "PDF image lost")
    with Image.open(BytesIO(page.images[0].data)) as image:
        require(image.size == (48, 24) and image.convert("RGB").getpixel((24, 12)) == (34, 85, 170), "PDF logo pixels changed")
    first, second = render(path, OUTPUT / name)
    require(len(first) == len(second) == 1, "dual rendering did not cover all pages")
    delta = difference(first[0], second[0], 2)
    require(delta["meanAbsoluteChannelError"] < 4 and delta["changedPixelFraction"] < .06, "PDFium/Poppler differ materially")
    contacts.append((name, first[0]))
    return delta


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--consumer-build-directory", type=Path)
    args = parser.parse_args()
    consumer_build = args.consumer_build_directory or Path((ROOT / "build/verification/consumer-build-directory.txt").read_text(encoding="utf-8").strip())
    consumer = consumer_build / "output"
    original = entries(EXAMPLES / "original.zip")
    edited = entries(EXAMPLES / "edited.zip")
    independent = entries(OUTPUT / "independent.zip")
    caller = entries(consumer / "consumer-template.zip")
    edits(original, edited, "XML-TEMPLATE", "EDITED-TEMPLATE", "ORIGINAL-INTRO", "EDITED-INTRO")
    edits(independent, caller, "CALLER-TEMPLATE", "CALLER-EDITED", "ORIGINAL-FRAGMENT", "EDITED-FRAGMENT")
    require(b"\r\n" in caller["template.xml"], "external CRLF source lost")
    cases = [
        (EXAMPLES, "original", "XML-TEMPLATE Batch-A", "ORIGINAL-INTRO Batch-A", True, [["Alpha", "2"], ["Beta", "3"]], original),
        (EXAMPLES, "edited", "EDITED-TEMPLATE Batch-A", "EDITED-INTRO Batch-A", True, [["Alpha", "2"], ["Beta", "3"]], edited),
        (EXAMPLES, "alternate", "EDITED-TEMPLATE Batch-B", "EDITED-INTRO Batch-B", False, [["Alpha", "2"], ["Beta", "3"]], edited),
        (consumer, "consumer-template-original", "CALLER-TEMPLATE External", "ORIGINAL-FRAGMENT External", True, [["Gamma", "4"], ["Delta", "5"]], independent),
        (consumer, "consumer-template-edited", "CALLER-EDITED External", "EDITED-FRAGMENT External", False, [["Gamma", "4"], ["Delta", "5"]], caller),
    ]
    contacts, report = [], {}
    for directory, name, heading, intro, approved, rows, bundle in cases:
        status = "STATUS-APPROVED" if approved else "STATUS-PENDING"
        markers = [heading, intro, status, "END-TEMPLATE", *["ITEM " + row[0] for row in rows]]
        forbidden = ["STATUS-PENDING" if approved else "STATUS-APPROVED"]
        docx(directory / (name + ".docx"), markers, rows, bundle["images/logo.png"])
        report[name] = pdf(directory / (name + ".pdf"), name, markers, forbidden, contacts)
    contact_sheet(contacts, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps({"pdfPages": 5, "docxFiles": 5, "zipFiles": 4, "dualRender": report}, indent=2) + "\n", encoding="utf-8")
    print("XML template ZIP: exact source/resources, independent input, 5 PDF pages and 5 native DOCX files verified")


if __name__ == "__main__":
    main()
