# SPDX-License-Identifier: Apache-2.0
"""Independent OOXML, native Word field and all-page PDF checks. No field instructions execute here."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET
from zipfile import ZipFile
from docx import Document
from pypdf import PdfReader
from verify_advanced_pdf import difference, render_pdfium, render_poppler, require
from verify_production import actual_text, contact_sheet

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "examples/build/docx-fields"
OUTPUT = ROOT / "build/verification/docx-fields"
W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
OD = "{urn:com.mirakyux.export4j:semantic:1}"


def check_package(path, expected_count):
    with ZipFile(path) as archive:
        require(archive.testzip() is None, "DOCX ZIP checksum differs")
        main = ET.fromstring(archive.read("word/document.xml"))
        fields = list(main.iter(W + "fldSimple"))
        require(len(fields) == expected_count, "native field count differs")
        require(all(float(field.attrib[OD + "width"]) > 0 and field.attrib[W + "dirty"] == "true" for field in fields), "field metadata missing")
        require(all(not "".join(field.itertext()) for field in fields), "export invented cached field values")
        instructions = [field.attrib[W + "instr"] for field in fields]
        require({instruction.split()[0] for instruction in instructions} >= {"PAGE", "NUMPAGES", "PAGEREF"}, "field kinds missing")
        starts, ends = list(main.iter(W + "bookmarkStart")), list(main.iter(W + "bookmarkEnd"))
        require({node.attrib[W + "id"] for node in starts} == {node.attrib[W + "id"] for node in ends}, "unbalanced bookmarks")
        names = {node.attrib[W + "name"] for node in starts}
        require(all(instruction.split()[1] in names for instruction in instructions if instruction.startswith(("PAGEREF ", "REF "))), "unresolved bookmark target")
        settings = ET.fromstring(archive.read("word/settings.xml"))
        require(settings.find(W + "updateFields").attrib[W + "val"] == "true", "updateFields missing")
        relations = ET.fromstring(archive.read("word/_rels/document.xml.rels"))
        require(len({node.attrib["Id"] for node in relations}) == len(relations), "duplicate relationship IDs")
        require(any(node.attrib.get("Target") == "settings.xml" and node.attrib["Type"].endswith("/settings") for node in relations), "settings relation missing")
        types = ET.fromstring(archive.read("[Content_Types].xml"))
        require(any(node.attrib.get("PartName") == "/word/settings.xml" for node in types), "settings content type missing")
    document = Document(path)
    require(len(document.tables) == 1, "python-docx table read failed")
    return {"fields": len(fields), "bookmarks": len(starts), "instructions": instructions}


def rendered(path, target, contacts):
    poppler = render_poppler(path, target / "poppler")
    pdfium = render_pdfium(path, target / "pdfium")
    require(len(poppler) == len(pdfium) == len(PdfReader(path).pages), "renderer page count differs")
    metrics = [difference(a, b, 1) for a, b in zip(poppler, pdfium, strict=True)]
    require(all(m["meanAbsoluteChannelError"] < 4 and m["changedPixelFraction"] < .06 for m in metrics), "PDF renderers disagree")
    contacts.extend((f"{target.name} {i + 1}", page) for i, page in enumerate(poppler))
    return {"pages": len(poppler), "rendererDifferences": metrics}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--word", action="store_true", help="Require the local Word COM verification outputs")
    args = parser.parse_args()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    report, contacts = {}, []
    report["package"] = check_package(FIXTURES / "fields.docx", 9)
    require(sum(instruction.startswith("REF ") for instruction in report["package"]["instructions"]) == 2, "number references missing")
    reader = PdfReader(FIXTURES / "imported.pdf")
    require(actual_text(reader) == ["BODY PAGE=1 TOTAL=3", "TARGET PAGE=7 NUMBER=4", "TABLE PAGE=1 \ufffc LINK",
                                   "CELL TOTAL=3", "TARGET=7 NUMBER=4", "ROW-1 PAGE=1", "CELL TOTAL=3", "TARGET=7 NUMBER=4"], "imported fields or repeated headers differ")
    ordinary = "\n".join(page.extract_text() for page in reader.pages)
    require(all(ordinary.count(f"ROW-{i}") == 1 for i in range(2, 8)), "table rows lost or duplicated")
    require("4. NUMBER-TARGET" in reader.pages[2].extract_text(), "numbered target differs")
    internal = [item.get_object() for page in reader.pages for item in page.get("/Annots", []) if "/Dest" in item.get_object()]
    require(len(internal) == 7, "imported reference link count differs")
    require(sum(item["/Dest"][0].idnum == reader.pages[2].indirect_reference.idnum for item in internal) == 6, "number target links differ")
    report["imported"] = rendered(FIXTURES / "imported.pdf", OUTPUT / "imported", contacts)
    pointer = ROOT / "build/verification/consumer-build-directory.txt"
    consumer = Path(pointer.read_text(encoding="utf-8").strip()) / "output"
    report["consumerPackage"] = check_package(consumer / "consumer-fields.docx", 6)
    consumer_reader = PdfReader(consumer / "consumer-docx-fields.pdf")
    require(actual_text(consumer_reader) == ["CONSUMER-PAGE=1 TOTAL=2 TARGET=2", "CONSUMER-CELL=1 TOTAL=2 TARGET=2"], "Maven consumer import values differ")
    report["consumer"] = rendered(consumer / "consumer-docx-fields.pdf", OUTPUT / "consumer", contacts)
    if args.word:
        word = json.loads((FIXTURES / "word-report.json").read_text(encoding="utf-8-sig"))
        require(word["sourceSha256"] == hashlib.sha256((FIXTURES / "fields.docx").read_bytes()).hexdigest(), "Word report belongs to another DOCX export")
        require(word["firstUpdateError"] == word["secondUpdateError"] == 0, "Word reported field update error")
        native = [field for field in word["fields"] if field["instruction"].split()[0] != "HYPERLINK"]
        require(len(native) == 9, "Word lost native fields")
        require([field["instruction"] for field in native] == report["package"]["instructions"], "Word native instructions differ from the generated package")
        require([field["value"] for field in native] == ["1", "3", "7", "4", "1", "3", "7", "4", "1"], "Word native field values differ")
        word_reader = PdfReader(FIXTURES / "word.pdf")
        require(len(word_reader.pages) == word["pages"] == 3, "Word page count differs")
        text = [page.extract_text() for page in word_reader.pages]
        require("BODY PAGE=1 TOTAL=3" in text[0] and "TARGET PAGE=7 NUMBER=4" in text[0] and "4. NUMBER-TARGET" in text[2], "Word rendered native values differ")
        require(all("CELL TOTAL=3" in text[i] and "TARGET=7 NUMBER=4" in text[i] for i in [0, 1]), "Word repeated header values differ")
        report["word"] = word | rendered(FIXTURES / "word.pdf", OUTPUT / "word", contacts)
    contact_sheet(contacts, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
