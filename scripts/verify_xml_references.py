# SPDX-License-Identifier: Apache-2.0
"""Independent final fields, passive PDF links and native OOXML field/relationship checks."""
from __future__ import annotations

from contextlib import closing
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from zipfile import ZipFile

import pypdfium2 as pdfium
from pypdf import PdfReader

from verify_advanced_pdf import difference, render_pdfium, render_poppler, require
from verify_inline_images import image_boxes
from verify_production import contact_sheet

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "examples/build/xml-references"
OUTPUT = ROOT / "build/verification/xml-references"
W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
R = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}"
WP = "{http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing}"
A = "{http://schemas.openxmlformats.org/drawingml/2006/main}"
OD = "{urn:com.mirakyux.export4j:semantic:1}"


def compact(value):
    return "".join(value.split())


def texts(path):
    result = []
    with pdfium.PdfDocument(path) as document:
        for index in range(len(document)):
            with closing(document[index]) as page, closing(page.get_textpage()) as text:
                result.append(text.get_text_bounded())
    return result


def check_package(path, expected_kinds, expected_text_uri):
    with ZipFile(path) as package:
        main = ET.fromstring(package.read("word/document.xml"))
        relationships = ET.fromstring(package.read("word/_rels/document.xml.rels"))
        targets = {r.get("Id"): r.get("Target") for r in relationships}
        fields = main.findall(f".//{W}fldSimple")
        require([f.get(W + "instr").split()[0] for f in fields] == expected_kinds,
                "native Word field kinds/order differ")
        require(all(float(f.get(OD + "width")) == 24 for f in fields), "explicit field width lost")
        bookmarks = {b.get(W + "name") for b in main.findall(f".//{W}bookmarkStart")}
        require(len(bookmarks) == 2 and all(f.get(W + "instr").split()[1] in bookmarks
                for f in fields if f.get(W + "instr").startswith(("REF ", "PAGEREF "))),
                "native reference bookmarks missing")
        links = main.findall(f".//{W}hyperlink")
        pictures = main.findall(f".//{WP}docPr/{A}hlinkClick")
        require(len(links) == len(pictures) == 1 and targets[pictures[0].get(R + "id")] == "https://example.com/image",
                "standard text/image links missing")
        require(targets[links[0].get(R + "id")] == expected_text_uri,
                "text link target changed")
        external = [r for r in relationships if r.get("TargetMode") == "External"]
        require(len(external) == 2 and all(r.get("Type").endswith("/hyperlink") for r in external),
                "unexpected external relationship")
        require(sum(name.startswith("word/media/") for name in package.namelist()) == 1, "explicit media missing")
        numbering = ET.fromstring(package.read("word/numbering.xml"))
        numbered = [p for p in main.findall(f".//{W}p") if p.find(f"{W}pPr/{W}numPr") is not None]
        require(len(numbered) == 1, "numbered target paragraph missing or duplicated")
        number_id = numbered[0].find(f"{W}pPr/{W}numPr/{W}numId").get(W + "val")
        definitions = [n for n in numbering.findall(f"{W}num") if n.get(W + "numId") == number_id]
        require(len(definitions) == 1, "numbered paragraph has no unique definition")
        overrides = [n for n in definitions[0].findall(f"{W}lvlOverride") if n.get(W + "ilvl") == "0"]
        require(len(overrides) == 1 and overrides[0].find(f"{W}startOverride").get(W + "val") == "3",
                "numbered target's standard restart override lost")
        return {"fields": len(fields), "bookmarks": len(bookmarks), "hyperlinks": 2, "images": 1}


def check_links(reader, expected_external):
    external, internal = [], []
    images = image_boxes(reader)
    require(len(images) == 1, "linked image lost or repeated")
    for index, page in enumerate(reader.pages):
        for ref in page.get("/Annots", []):
            link = ref.get_object()
            require(link["/Subtype"] == "/Link", "unexpected annotation")
            left, bottom, right, top = map(float, link["/Rect"])
            require(0 <= left < right <= float(page.mediabox.width)
                    and 0 <= bottom < top <= float(page.mediabox.height), "link leaves page")
            if "/A" in link:
                require(link["/A"]["/S"] == "/URI", "unexpected action")
                uri = str(link["/A"]["/URI"])
                external.append(uri)
                if uri == "https://example.com/image":
                    require(any(i["page"] == index and abs(i["x"] - left) < .003
                                and abs(i["y"] - (float(page.mediabox.height) - top)) < .003
                                and abs(i["width"] - (right - left)) < .003
                                and abs(i["height"] - (top - bottom)) < .003 for i in images),
                            "image hyperlink misses its actual rectangle")
            else:
                require("/Dest" in link, "link has no destination")
                destination = link["/Dest"]
                target = next((n for n, p in enumerate(reader.pages)
                               if p.indirect_reference.idnum == destination[0].idnum), None)
                require(target is not None, "internal destination points outside final pages")
                internal.append({"page": index, "target": target, "width": right - left})
    require(sorted(external) == sorted(expected_external), "passive external links lost or duplicated")
    return internal


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    report, contacts = {}, []
    path = FIXTURES / "references.pdf"
    reader, page_texts = PdfReader(path, strict=True), texts(path)
    count = len(reader.pages)
    require(count >= 5 and len(page_texts) == count and "Contents" in page_texts[0], "contents offset fixture differs")
    require(f"OPENcurrent=2total={count}target={count}number=3" in compact(page_texts[1]), "final opening fields incorrect")
    require(f"TARGET-ENDcurrent={count}total={count}" in compact(page_texts[-1]), "closing fields incorrect")
    ids = re.findall(r"XML\d{2}-\d{2}", "\n".join(page_texts))
    require(ids == [f"XML{row:02}-{line:02}" for row in range(1, 3) for line in range(1, 15)],
            "dynamic original lines lost, reordered or duplicated")
    occupied = []
    for index, text in enumerate(page_texts):
        if "HEADER" in text:
            occupied.append(index)
            require(compact(text).count(f"HEADERpage={index + 1}total={count}target={count}") == 1,
                    "repeated header fields do not reflect their actual final page")
    require(len(occupied) >= 3, "dynamic rows did not continue with repeated headers")
    links = check_links(reader, ["https://example.com/report", "https://example.com/image",
                                 "https://example.com/cell", "https://example.com/cell"])
    require(sum(l["target"] == count - 1 and abs(l["width"] - 24) < .003 for l in links) == len(occupied) + 1,
            "page references lost final destinations/reserved widths")
    require(sum(l["target"] == 1 and abs(l["width"] - 24) < .003 for l in links) == 1,
            "number reference lost its numbered target")
    report["references"] = {"pages": count, "originalLines": len(ids), "headerPages": [i + 1 for i in occupied],
                            "internalLinks": links, "externalLinks": 4, "images": 1}
    require((FIXTURES / "docx-rejected.txt").read_text(encoding="utf-8") == "REPEATED_HEADER_PAGE_REJECTED\n",
            "DOCX repeated-header PAGE rejection not recorded")
    word_path = FIXTURES / "word-fields.pdf"
    word_reader, word_text = PdfReader(word_path, strict=True), texts(word_path)
    require(len(word_reader.pages) == 2 and "WORDcurrent=1total=2target=2number=3" in compact(word_text[0])
            and "WORD-ENDcurrent=2total=2" in compact(word_text[1]), "Word companion semantic fields differ")
    word_links = check_links(word_reader, ["https://example.com/report", "https://example.com/image"])
    require(len(word_links) == 2 and sorted((l["target"], round(l["width"], 3)) for l in word_links) == [(0, 24), (1, 24)],
            "Word companion references jump to wrong pages or widths")
    report["wordPackage"] = check_package(FIXTURES / "word-fields.docx", ["PAGE", "NUMPAGES", "PAGEREF", "REF", "PAGE", "NUMPAGES"],
                                         "https://example.com/report")
    consumer_dir = Path((ROOT / "build/verification/consumer-build-directory.txt").read_text(encoding="utf-8").strip()) / "output"
    consumer = consumer_dir / "consumer-xml-references.pdf"
    consumer_text = texts(consumer)
    require(len(consumer_text) == 2 and "XMLcurrent=1total=2target=2number=3" in compact(consumer_text[0])
            and "XML-ENDpage=2" in compact(consumer_text[1]), "isolated consumer final XML fields differ")
    consumer_links = check_links(PdfReader(consumer), ["https://example.com/consumer", "https://example.com/image"])
    require(len(consumer_links) == 2 and sorted((l["target"], round(l["width"], 3)) for l in consumer_links) == [(0, 24), (1, 24)],
            "consumer references jump to wrong pages or widths")
    report["consumerPackage"] = check_package(consumer_dir / "consumer-xml-references.docx", ["PAGE", "NUMPAGES", "PAGEREF", "REF", "PAGE"],
                                             "https://example.com/consumer")
    for name, source in (("references", path), ("word-fields", word_path), ("consumer", consumer)):
        first = render_poppler(source, OUTPUT / name / "poppler")
        second = render_pdfium(source, OUTPUT / name / "pdfium")
        require(len(first) == len(second) == len(PdfReader(source).pages), "independent page count differs")
        metrics = [difference(a, b, 1) for a, b in zip(first, second, strict=True)]
        require(all(m["meanAbsoluteChannelError"] < 4 and m["changedPixelFraction"] < .06 for m in metrics),
                "independent renderers disagree")
        report.setdefault(name, {}).update(renderDifference=metrics)
        contacts.extend((f"{name} {i}", page) for i, page in enumerate(first, 1))
    contact_sheet(contacts, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"pagesIncludingConsumer": len(contacts), "headers": len(occupied), "sources": "passed",
                      "links": "passed", "nativeDocxFields": "passed"}, indent=2))


if __name__ == "__main__":
    main()
