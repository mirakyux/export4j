# SPDX-License-Identifier: Apache-2.0
"""Independent PDF/OOXML validation of XML sections, fixed image/table bands and native fields."""
from __future__ import annotations
import argparse
from hashlib import sha256
from importlib import metadata
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from zipfile import ZipFile
from docx import Document
from pypdf import PdfReader
from verify_adr_form import render
from verify_advanced_pdf import difference, require
from verify_inline_images import image_boxes
from verify_production import actual_text, contact_sheet
from verify_streaming_rich_text import painted_spans, outlined_paths, physical_fonts

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build/verification/xml-sections"
W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
R = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}"
P = "{http://schemas.openxmlformats.org/package/2006/relationships}"
A = "{http://schemas.openxmlformats.org/drawingml/2006/main}"
OD = "{urn:com.mirakyux.export4j:semantic:1}"


def close(a, b, label):
    require(abs(a-b) < .0251, f"{label}: {a} versus {b}")


def pdf_check(path, caller):
    reader = PdfReader(path)
    count = 3 if caller else 4
    require(len(reader.pages) == count, "section page count differs")
    numbers = [1, 2, 7] if caller else [1, 2, 8, None]
    header_pages = {0, 1} if caller else {0, 1, 2}
    brand = "CALLER / SECTION REPORT" if caller else "EXPORT4J / SECTION REPORT"
    images, spans, outlines = image_boxes(reader), painted_spans(reader), outlined_paths(reader)
    require([value["page"] for value in images] == sorted(header_pages), "header images missing/repeated or clear ignored")
    rows = []
    for index, page in enumerate(reader.pages):
        width, height = ((360, 240) if index == 2 else (320, 220)) if caller else ((420, 300) if index == 2 else (360, 260))
        close(float(page.mediabox.width), width, "section page width")
        close(float(page.mediabox.height), height, "section page height")
        require(not page.get("/Annots"), "static fixture gained annotations")
        logical = actual_text(type("PageView", (), {"pages": [page]})())
        require(sum(brand in value for value in logical) == int(index in header_pages), "header inherited/replaced/cleared incorrectly")
        if numbers[index] is not None:
            require(logical.count(f"PAGE {numbers[index]}") == 1 and logical.count(f"TOTAL {count}") == 1, "final section page/count fields differ")
        else:
            require(not logical, "cleared section still has band fields")
        margin = 18 if caller else 20
        body_top = margin + (30 if caller else 40) if index in header_pages else margin
        body_bottom = height - margin - (32 if caller else 38) if numbers[index] is not None else height - margin
        for span in [value for value in spans if value["page"] == index]:
            if any(span["text"].startswith(prefix) for prefix in ("REPORT-", "APPENDIX-", "CLOSING-", "CALLER-ROW-", "CALLER-LAST-")):
                rows.extend(re.findall(r"(?:REPORT|APPENDIX|CLOSING|CALLER-ROW|CALLER-LAST)-[0-9]{2}", span["text"]))
                require(body_top <= span["baseline"] <= body_bottom, "body overlaps bands")
                require(margin <= span["x"] < span["right"] <= width-margin+.025, "body crosses margin")
        for image in [value for value in images if value["page"] == index]:
            close(image["x"], margin, "image left")
            close(image["width"], 12 if caller else 18, "image width")
            require(margin <= image["y"] < image["y"]+image["height"] <= body_top, "image leaves header")
        for ink in [value for value in outlines if value["page"] == index]:
            in_header = index in header_pages and margin <= ink["top"] < ink["bottom"] <= body_top
            in_footer = numbers[index] is not None and body_bottom <= ink["top"] < ink["bottom"] <= height-margin
            require(in_header or in_footer, "region outline crosses body or page margins")
    expected = ([f"CALLER-ROW-{i:02d}" for i in range(1, 9)] + ["CALLER-LAST-01"]) if caller else (
        [f"REPORT-{i:02d}" for i in range(1, 10)] + ["APPENDIX-01", "APPENDIX-02", "CLOSING-01"])
    require(rows == expected, "body lost, duplicated or reordered across sections")
    return {"pages": count, "sourceLines": len(rows), "images": len(images), "pageNumbers": numbers,
            "fonts": physical_fonts(reader, {"LiberationSans"})}


def docx_check(path, caller):
    document = Document(path)
    require(len(document.sections) == (2 if caller else 3), "python-docx section count differs")
    with ZipFile(path) as package:
        main = ET.fromstring(package.read("word/document.xml"))
        relations = {item.attrib["Id"]: item.attrib["Target"] for item in ET.fromstring(package.read("word/_rels/document.xml.rels"))}
        sections = main.findall(".//" + W + "sectPr")
        media = [name for name in package.namelist() if name.startswith("word/media/")]
        require(len(media) == 1, "media did not deduplicate across bands/sections")
        for index, section in enumerate(sections):
            header = section.find(W + "headerReference")
            footer = section.find(W + "footerReference")
            require(header is not None and footer is not None, "missing explicit default band or clear reference")
            require(header.attrib[W + "type"] == footer.attrib[W + "type"] == "default", "unexpected band variant")
            hpath, fpath = "word/" + relations[header.attrib[R + "id"]], "word/" + relations[footer.attrib[R + "id"]]
            hroot, froot = ET.fromstring(package.read(hpath)), ET.fromstring(package.read(fpath))
            has_header = index < (1 if caller else 2)
            has_footer = caller or index < 2
            drawings = hroot.findall(".//" + W + "drawing")
            require(len(drawings) == int(has_header), "standard header drawing missing or explicit clear failed")
            require(len(froot.findall(W + "tbl")) == int(has_footer), "standard footer table missing or clear failed")
            fields = froot.findall(".//" + W + "fldSimple")
            require([value.attrib[W + "instr"] for value in fields] == (["PAGE", "NUMPAGES"] if has_footer else []), "native PAGE/NUMPAGES fields differ")
            if has_header:
                local = {item.attrib["Id"]: item.attrib["Target"] for item in ET.fromstring(package.read("word/_rels/" + hpath.split('/')[-1] + ".rels"))}
                for blip in hroot.findall(".//" + A + "blip"):
                    require("word/" + local[blip.attrib[R + "embed"]] in media, "image does not resolve in header's relationship scope")
            if has_footer:
                grid = froot.findall(".//" + W + "gridCol")
                expected_width = (284 if index == 0 else 324) if caller else (320 if index == 0 else 380)
                close(sum(int(value.attrib[W + "w"]) / 20 for value in grid), expected_width, "per-section footer grid width")
            margin = section.find(W + "pgMar")
            base = 18 if caller else 20
            close(int(margin.attrib[W + "top"]) / 20, base + ((30 if caller else 40) if has_header else 0), "OOXML reserved top")
            close(int(margin.attrib[W + "bottom"]) / 20, base + ((32 if caller else 38) if has_footer else 0), "OOXML reserved bottom")
            require(len(document.sections[index].header._element.findall(".//" + W + "drawing")) == int(has_header), "independent Word part model missed header drawing")
            require(len(document.sections[index].footer.tables) == int(has_footer), "independent Word part model missed footer table")
        require(ET.fromstring(package.read("word/settings.xml")).find(W + "updateFields") is not None, "native fields not marked for update")
    return {"sections": len(document.sections), "media": len(media), "standardFields": True, "scopedImages": True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--consumer-build-directory", type=Path)
    parser.add_argument("--word", action="store_true")
    args = parser.parse_args()
    versions = {name: metadata.version(name) for name in ("pypdf", "pypdfium2", "Pillow", "python-docx")}
    require(versions == {"pypdf": "6.10.0", "pypdfium2": "5.13.0", "Pillow": "12.3.0", "python-docx": "1.2.0"}, "use pinned verification tools")
    consumer = args.consumer_build_directory or Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip())
    fixtures = ROOT / "examples/build/xml-sections"
    OUTPUT.mkdir(parents=True, exist_ok=True)
    report, contacts, rendered = {"tools": versions}, [], {}
    for name, path, caller in (("example", fixtures / "report.pdf", False), ("imported", fixtures / "imported.pdf", False),
            ("consumer", consumer / "output/consumer-xml-sections.pdf", True), ("consumer-imported", consumer / "output/consumer-xml-sections-imported.pdf", True)):
        result = pdf_check(path, caller)
        first, second = render(path, OUTPUT / name)
        require(len(first) == len(second) == result["pages"], "renderer missed pages")
        metrics = [difference(a,b,2) for a,b in zip(first,second,strict=True)]
        require(all(value["meanAbsoluteChannelError"] < 4 and value["changedPixelFraction"] < .06 for value in metrics), "renderers disagree")
        report[name] = {**result, "rendererDifferences": metrics}
        contacts.extend((name + " " + str(index), image) for index,image in enumerate(first,1))
        rendered[name] = first
    for a,b in (("example", "imported"), ("consumer", "consumer-imported")):
        require(all(difference(x,y)["meanAbsoluteChannelError"] == 0 for x,y in zip(rendered[a],rendered[b],strict=True)), "strict DOCX import changed visible content")
    report["docxExample"] = docx_check(fixtures / "report.docx", False)
    report["docxConsumer"] = docx_check(consumer / "output/consumer-xml-sections.docx", True)
    if args.word:
        word = json.loads((fixtures / "word-report.json").read_text(encoding="utf-8-sig"))
        require(word["sourceSha256"] == sha256((fixtures / "report.docx").read_bytes()).hexdigest(), "Word report is stale")
        require(len(word["sections"]) == 3, "Word lost sections")
        require([value["headerInlineImages"] for value in word["sections"]] == [1,1,0], "Word lost inherited image or clear")
        require([value["footerTables"] for value in word["sections"]] == [1,1,0], "Word lost footer table or clear")
        require([value["footerFields"] for value in word["sections"]] == [2,2,0], "Word lost native footer fields")
        report["word"] = {"version": word["wordVersion"], "pages": word["pages"], "sections": 3, "updatedFields": True, "pdfExported": word["pdfExported"]}
        if word["pdfExported"]:
            reader = PdfReader(fixtures / "word.pdf")
            text = "\n".join(page.extract_text() for page in reader.pages)
            require(re.findall(r"REPORT-([0-9]{2})", text) == [f"{i:02d}" for i in range(1,10)], "Word lost/duplicated body rows")
            for marker in ("APPENDIX-01", "APPENDIX-02", "CLOSING-01"):
                require(text.count(marker) == 1, "Word lost source " + marker)
            require(all(f"PAGE {number}" in text for number in (1,2,8)), "Word section PAGE values differ")
            first,second = render(fixtures / "word.pdf", OUTPUT / "word")
            contacts.extend(("word " + str(index), image) for index,image in enumerate(first,1))

    contact_sheet(contacts, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report,indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"xmlSections": "passed", "pagesIncludingConsumer": len(contacts), "word": args.word},indent=2))

if __name__ == "__main__":
    main()
