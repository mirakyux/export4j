# SPDX-License-Identifier: Apache-2.0
"""Check offline XML inclusion in final PDF/SVG and native DOCX, then render every page.

Expected namespace, numbering, field and style values are declared independently here.
No generated Java semantic manifest or visual baseline supplies expected values.
"""
from __future__ import annotations

import argparse
import hashlib
from importlib import metadata
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from pypdf import PdfReader
from verify_adr_form import render
from verify_advanced_pdf import difference, require
from verify_production import actual_text, contact_sheet
from verify_semantic_tables import check_characters, close, table_borders
from verify_streaming_rich_text import decoration, painted_spans, physical_fonts, same_color, strokes
from verify_themes import paragraph_format, svg_check, text_of

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "examples/build/xml-includes"
OUTPUT = ROOT / "build/verification/xml-includes"
W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
OD = "{urn:com.mirakyux.export4j:semantic:1}"
BLACK, BLUE = (0, 0, 0), (34 / 255, 68 / 255, 136 / 255)


def matches(spans, text, count):
    pattern = re.compile(r"(?<![A-Z0-9-])" + re.escape(text) + r"(?![A-Z0-9-])")
    found = [value for value in spans if pattern.search(value["text"])]
    require(len(found) == count, "included source text missing/repeated: " + text)
    return found


def styled(spans, text, size, count=1, color=BLACK):
    found = matches(spans, text, count)
    for value in found:
        close(value["size"], size, "included PDF point size differs: " + text)
        require(same_color(value["color"], color), "included PDF RGB differs: " + text)
    return found


def page_contract(reader, count, width, height):
    require(len(reader.pages) == count and not reader.get_fields(), "included page/form contract differs")
    for page in reader.pages:
        close(page.mediabox.width, width, "included page width differs")
        close(page.mediabox.height, height, "included page height differs")


def native(path):
    with ZipFile(path) as archive:
        root = ET.fromstring(archive.read("word/document.xml"))
        numbering = ET.fromstring(archive.read("word/numbering.xml"))
        require(not any(name.startswith("word/media/") for name in archive.namelist()), "included text-only DOCX gained media")
    nodes = [node for node in root.find(W + "body") if node.tag != W + "sectPr"]
    ids = [node.get(OD + "id") for node in nodes]
    require(all(ids) and len(ids) == len(set(ids)), "included native DOCX IDs missing/repeated")
    return root, numbering, nodes, dict(zip(ids, nodes, strict=True))


def number_id(node):
    level = node.find(W + "pPr/" + W + "numPr/" + W + "ilvl")
    require(level is not None and level.get(W + "val") == "0", "included native list level differs")
    value = node.find(W + "pPr/" + W + "numPr/" + W + "numId")
    require(value is not None, "included native numbered paragraph lost numId")
    return value.get(W + "val")


def native_page(root, width, height, margin):
    require(len(list(root.iter(W + "sectPr"))) == 1, "included single-section DOCX gained extra sections")
    section = root.find(W + "body/" + W + "sectPr")
    require(section is not None, "included native DOCX page section missing")
    size, margins = section.find(W + "pgSz"), section.find(W + "pgMar")
    close(float(size.get(W + "w")) / 20, width, "included native page width differs")
    close(float(size.get(W + "h")) / 20, height, "included native page height differs")
    for side in ("top", "right", "bottom", "left"):
        close(float(margins.get(W + side)) / 20, margin, "included native page margin differs")


def number_start(numbering, num_id, expected):
    found = [node for node in numbering.findall(W + "num") if node.get(W + "numId") == num_id]
    require(len(found) == 1, "included native numbering source missing/repeated")
    reference = found[0].find(W + "abstractNumId")
    require(reference is not None, "included native abstract numbering reference missing")
    definitions = [node for node in numbering.findall(W + "abstractNum")
                   if node.get(W + "abstractNumId") == reference.get(W + "val")]
    require(len(definitions) == 1, "included native abstract numbering missing/repeated")
    levels = [node for node in definitions[0].findall(W + "lvl") if node.get(W + "ilvl") == "0"]
    require(len(levels) == 1, "included native numbering level missing/repeated")
    level = levels[0]
    require(level.find(W + "numFmt").get(W + "val") == "decimal"
            and level.find(W + "lvlText").get(W + "val") == "%1."
            and level.find(W + "start").get(W + "val") == "1", "included native decimal label definition differs")
    overrides = found[0].findall(W + "lvlOverride")
    require(len(overrides) == 1 and overrides[0].get(W + "ilvl") == "0"
            and overrides[0].find(W + "lvl") is None, "included native numbering level override differs")
    starts = overrides[0].findall(W + "startOverride")
    require(len(starts) == 1 and int(starts[0].get(W + "val")) == expected, "included native numbering start differs")


def static_docx(path):
    root, numbering, nodes, by_id = native(path)
    native_page(root, 500, 620, 24)
    ids = [node.get(OD + "id") for node in nodes]
    require(len(ids) == 14 and ids[0] == "report" and ids[7] == "separator", "included static native source order differs")
    for begin, prefix in ((1, "first"), (8, "second")):
        expected = [prefix + "-topic", prefix + "-note", None, prefix + "-step-a", prefix + "-step-b", prefix + "-detail"]
        for actual, wanted in zip(ids[begin:begin + 6], expected, strict=True):
            if wanted is None: require(re.fullmatch(prefix + r"-xml-\d+", actual) is not None, "included native auto ID escaped local prefix")
            else: require(actual == wanted, "included native explicit ID escaped local prefix")
        paragraph_format(by_id[prefix + "-topic"], 14, 22)
        paragraph_format(by_id[prefix + "-note"], 9, 18)
        paragraph_format(nodes[begin + 2], 11, 20)
        for item in ("step-a", "step-b"):
            value = by_id[prefix + "-" + item]
            require(value.get(OD + "listId") == prefix + "-steps" and value.get(OD + "restart") == "0",
                    "included native list ID/restart escaped local prefix")
            paragraph_format(value, 11, 20)
        # Native DOCX snapshots each item's resolved number in a separate num/startOverride.
        # Continuation is therefore checked by labels 1/2 and the shared semantic list ID.
        number_start(numbering, number_id(by_id[prefix + "-step-a"]), 1)
        number_start(numbering, number_id(by_id[prefix + "-step-b"]), 2)
        key = [run for run in nodes[begin + 2].iter(W + "r") if text_of(run) == "STATIC-KEY"]
        require(len(key) == 1, "included native named run missing/repeated")
        properties = key[0].find(W + "rPr")
        require(properties.find(W + "rStyle").get(W + "val") == "bold"
                and properties.find(W + "color").get(W + "val") == "224488"
                and properties.find(W + "u").get(W + "val") == "single", "included native named run style differs")
    first = {number_id(by_id["first-" + item]) for item in ("step-a", "step-b")}
    second = {number_id(by_id["second-" + item]) for item in ("step-a", "step-b")}
    require(first.isdisjoint(second), "included native local lists merged")
    tables = list(root.iter(W + "tbl"))
    require(len(tables) == 2 and not root.findall(".//" + W + "fldSimple"), "included static DOCX table/field count differs")
    for table in tables:
        require(len(table.findall(W + "tr")) == 1 and len(table.findall(W + "tr/" + W + "tc")) == 2, "included native table shape differs")
        margins = table.find(W + "tblPr/" + W + "tblCellMar")
        for side in ("top", "right", "bottom", "left"):
            close(float(margins.find(W + side).get(W + "w")) / 20, 3, "included native table padding differs")
        for paragraph in table.iter(W + "p"): paragraph_format(paragraph, 10, 18)
    return {"topLevelBlocks": 14, "isolatedLists": 2, "tables": 2, "namedRuns": 2}


def check_static():
    path = FIXTURES / "static.pdf"
    reader = PdfReader(path, strict=True)
    page_contract(reader, 1, 500, 620)
    require(not reader.pages[0].get("/Annots"), "included static PDF gained interactions")
    spans = painted_spans(reader)
    styled(spans, "XML-INCLUDE-STATIC", 18)
    for text, size in (("STATIC-PANEL", 14), ("STATIC-NOTE", 9), ("STATIC-VALUE", 11),
                       ("STATIC-STEP-A", 11), ("STATIC-STEP-B", 11), ("STATIC-CELL-A", 10), ("STATIC-CELL-B", 10)):
        styled(spans, text, size, 2)
    keys = styled(spans, "STATIC-KEY", 11, 2, BLUE)
    for key in keys:
        require(key["font"].endswith("LiberationSans-Bold"), "included named run did not select physical bold")
        decoration(key, strokes(reader), 1.1)
    styled(spans, "BETWEEN-PANELS", 11)
    labels = ["XML-INCLUDE-STATIC"] + ["STATIC-PANEL", "STATIC-NOTE", "STATIC-KEY", "STATIC-VALUE",
            "STATIC-STEP-A", "STATIC-STEP-B", "STATIC-CELL-A", "STATIC-CELL-B"] + ["BETWEEN-PANELS"] + [
            "STATIC-PANEL", "STATIC-NOTE", "STATIC-KEY", "STATIC-VALUE", "STATIC-STEP-A", "STATIC-STEP-B", "STATIC-CELL-A", "STATIC-CELL-B"]
    positions, used = [], {}
    for label in labels:
        occurrence = used.get(label, 0)
        positions.append(spans.index(matches(spans, label, labels.count(label))[occurrence]))
        used[label] = occurrence + 1
    require(positions == sorted(positions), "included static content changed source order")
    text = "".join(reader.pages[0].extract_text().split())
    require(text.count("1.STATIC-STEP-A") == text.count("2.STATIC-STEP-B") == 2,
            "included static list prefixes merged or failed to continue")
    require(text.count("STATIC-PANELReview") == text.count("STATIC-NOTEOK") == 2, "included static data binding differs")
    borders = table_borders(reader)
    require(len(borders) == 4, "included static table border count differs")
    for table, top in zip((borders[:2], borders[2:]), (172, 339), strict=True):
        for border, x in zip(table, (24, 250), strict=True):
            require(border["pageIndex"] == 0, "included static table changed page")
            for key, value in zip(("x", "y", "width", "height"), (x, top, 226, 24), strict=True):
                close(border[key], value, "included static table " + key + " differs")
    physical_fonts(reader, {"LiberationSans", "LiberationSans-Bold"})
    svg_check(FIXTURES / "static.svg", spans)
    return {"pages": 1, "isolatedLists": 2, "tableBorders": 4, "characters": check_characters(path, 24),
            "docx": static_docx(FIXTURES / "static.docx")}


def interactive_docx(path):
    root, numbering, nodes, by_id = native(path)
    native_page(root, 500, 420, 24)
    expected_ids = ["interactive-report", "global-target", "overview", "body-break"]
    for index in range(2):
        expected_ids.extend(["record-" + value + "@" + str(index) for value in
                             ("topic", "note", "step", "local", "global", "shown" if index == 0 else "hidden")])
    expected_ids.append("approved-summary")
    require([node.get(OD + "id") for node in nodes] == expected_ids, "included native lexical IDs/branch source order differs")
    require(by_id["global-target"].get(OD + "listId") == "global-list"
            and by_id["global-target"].get(OD + "restart") == "7", "included external native list identity/restart changed")
    number_start(numbering, number_id(by_id["global-target"]), 7)
    local_ids = [number_id(by_id["record-step@" + str(index)]) for index in range(2)]
    require(number_id(by_id["global-target"]) not in local_ids, "included native local and global list instances merged")
    for index, num_id in enumerate(local_ids):
        item = by_id["record-step@" + str(index)]
        require(item.get(OD + "listId") == "record-checks" and item.get(OD + "restart") == "0",
                "each included native list identity/restart changed")
        number_start(numbering, num_id, index + 1)
    bookmarks = {node.get(W + "name"): node.get(OD + "owner") for node in root.iter(W + "bookmarkStart")}
    require(set(bookmarks.values()) == {"global-target", "record-topic@0", "record-step@0", "record-topic@1", "record-step@1"},
            "included native target bookmarks differ")
    fields = list(root.iter(W + "fldSimple"))
    expected = []
    for index in range(2):
        expected.extend([("PAGE", None), ("NUMPAGES", None), ("PAGEREF", "record-topic@" + str(index)),
                         ("REF", "record-step@" + str(index)), ("PAGEREF", "global-target"), ("REF", "global-target")])
    expected.append(("REF", "global-target"))
    require(len(fields) == len(expected) == 13, "included native field count differs")
    for field, (kind, target) in zip(fields, expected, strict=True):
        instruction = field.get(W + "instr").split()
        require(instruction[0] == kind and field.get(W + "dirty") == "true", "included native field kind/update flag differs")
        close(float(field.get(OD + "width")), 16, "included native field reservation changed")
        if target is not None: require(bookmarks.get(instruction[1]) == target, "included native field escaped lexical target")
    for index in range(2):
        paragraph_format(by_id["record-topic@" + str(index)], 14, 22)
        paragraph_format(by_id["record-note@" + str(index)], 9, 18)
        for kind in ("step", "local", "global", "shown" if index == 0 else "hidden"):
            paragraph_format(by_id["record-" + kind + "@" + str(index)], 11, 20)
    paragraph_format(by_id["approved-summary"], 9, 18)
    return {"topLevelBlocks": 17, "nativeFields": 13, "targetBookmarks": 5, "lexicalTargets": "passed"}


def check_interactive():
    path = FIXTURES / "interactive.pdf"
    reader = PdfReader(path, strict=True)
    page_contract(reader, 2, 500, 420)
    expected = []
    for index, code in enumerate(("REC-A", "REC-B"), 1):
        expected.extend([f"LOCAL-FIELDS {code} page=2 total=2 target=2 number={index}", f"GLOBAL-FIELDS {code} page=1 number=7"])
    expected.append("INCLUDE-CONCLUSION Approved global=7.")
    require(actual_text(reader) == expected, "included final page/total/lexical/number values differ")
    spans = painted_spans(reader)
    styled(spans, "XML-INCLUDE-INTERACTIVE", 18)
    styled(spans, "GLOBAL-TARGET", 11)
    styled(spans, "INCLUDE-OVERVIEW", 11)
    styled(spans, "RECORD-HEADING", 14, 2)
    styled(spans, "RECORD-STATUS", 9, 2)
    styled(spans, "LOCAL-TARGET", 11, 2)
    styled(spans, "TRUE-BRANCH", 11)
    styled(spans, "FALSE-BRANCH", 11)
    require(all(value["page"] == 1 for value in matches(spans, "RECORD-HEADING", 2)), "included lexical records moved off second page")
    first, second = ("".join(page.extract_text().split()) for page in reader.pages)
    require("7.GLOBAL-TARGET" in first and "LOCAL-TARGET" not in first
            and "1.LOCAL-TARGETREC-A" in second and "2.LOCAL-TARGETREC-B" in second
            and "TRUE-BRANCHREC-A" in second and "FALSE-BRANCHREC-B" in second,
            "included bound branch/number source order differs")
    links, target_pages = [], []
    require(not reader.pages[0].get("/Annots"), "external source page gained a field annotation")
    expected_target_tops = [24, 69, 58, 58, 161, 206, 58, 58, 58]
    expected_link_tops = [92, 92, 115, 115, 229, 229, 252, 252, 298]
    annotations = reader.pages[1].get("/Annots", [])
    require(len(annotations) == len(expected_target_tops), "included resolved field link count differs")
    for reference, target_top, link_top in zip(annotations, expected_target_tops, expected_link_tops, strict=True):
        link = reference.get_object()
        require(link["/Subtype"] == "/Link" and "/A" not in link and "/Dest" in link, "included field gained an external action")
        left, bottom, right, top = map(float, link["/Rect"])
        require(24 <= left < right <= 476 and 24 <= bottom < top <= 396, "included field hyperlink leaves margins")
        close(right - left, 16, "included field hyperlink lost fixed reservation")
        close(420 - top, link_top, "included field hyperlink lost final source line")
        close(top - bottom, 18 if link_top == 298 else 20, "included field hyperlink line height differs")
        require(link["/Dest"][1] == "/XYZ", "included field destination type differs")
        close(link["/Dest"][2], 24, "included field destination left margin differs")
        close(link["/Dest"][3], 420 - target_top, "included field destination does not reach its actual source block")
        destinations = [index for index, page in enumerate(reader.pages) if page.indirect_reference.idnum == link["/Dest"][0].idnum]
        require(len(destinations) == 1, "included field destination page is not canonical")
        target_pages.append(destinations[0])
        links.append((left, 420 - top, right - left, top - bottom))
    require(target_pages == [1, 1, 0, 0, 1, 1, 0, 0, 0], "included fields point to wrong lexical/global pages")
    require((FIXTURES / "static-rejected.txt").read_text(encoding="utf-8") == "INCLUDED_INTERACTIVE_SVG_REJECTED_BEFORE_WRITE\n",
            "included interactive SVG rejection missing")
    physical_fonts(reader, {"LiberationSans"})
    return {"pages": 2, "resolvedFields": 13, "internalLinks": len(links), "targetPages": target_pages,
            "characters": check_characters(path, 24), "docx": interactive_docx(FIXTURES / "interactive.docx")}


def check_consumer(directory):
    base = directory / "output/consumer-xml-include"
    reader = PdfReader(base.with_suffix(".pdf"), strict=True)
    page_contract(reader, 1, 400, 400)
    require(not reader.pages[0].get("/Annots"), "included caller gained interactions")
    spans = painted_spans(reader)
    styled(spans, "XML-INCLUDE-CONSUMER", 18)
    styled(spans, "INCLUDE-PANEL", 14, 2)
    styled(spans, "INCLUDE-STATUS", 9, 2)
    styled(spans, "INCLUDE-STEP", 11, 2)
    text = "".join(reader.pages[0].extract_text().split())
    require(text.count("1.INCLUDE-STEP") == text.count("INCLUDE-STATUSOK") == 2, "included caller namespaces/binding differ")
    svg_check(base.with_suffix(".svg"), spans)
    root, numbering, nodes, by_id = native(base.with_suffix(".docx"))
    native_page(root, 400, 400, 20)
    require(len(nodes) == 7, "included caller native block count differs")
    ids = [node.get(OD + "id") for node in nodes]
    require(re.fullmatch(r"xml-\d+", ids[0]) is not None, "included caller native root auto ID differs")
    for begin, prefix in ((1, "first"), (4, "second")):
        require(ids[begin] == prefix + "-topic" and ids[begin + 2] == prefix + "-step"
                and re.fullmatch(prefix + r"-xml-\d+", ids[begin + 1]) is not None, "included caller native local IDs differ")
    for prefix in ("first", "second"):
        require(by_id[prefix + "-step"].get(OD + "listId") == prefix + "-steps"
                and by_id[prefix + "-step"].get(OD + "restart") == "0", "included caller native list/restart escaped prefix")
        number_start(numbering, number_id(by_id[prefix + "-step"]), 1)
    require(number_id(by_id["first-step"]) != number_id(by_id["second-step"]), "included caller native list IDs merged")
    require(not root.findall(".//" + W + "fldSimple"), "included static caller gained native fields")
    physical_fonts(reader, {"LiberationSans"})
    return {"pages": 1, "isolatedLists": 2, "topLevelBlocks": 7, "characters": check_characters(base.with_suffix(".pdf"), 20)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--consumer-build-directory", type=Path)
    args = parser.parse_args()
    versions = {name: metadata.version(name) for name in ("pypdf", "pypdfium2", "Pillow")}
    require(versions == {"pypdf": "6.10.0", "pypdfium2": "5.13.0", "Pillow": "12.3.0"}, "use the pinned verification environment")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    directory = args.consumer_build_directory
    if directory is None: directory = Path((ROOT / "build/verification/consumer-build-directory.txt").read_text(encoding="utf-8").strip())
    if not directory.is_absolute(): directory = ROOT / directory
    report = {"tools": versions, "consumerBuildDirectory": str(directory), "static": check_static(),
              "interactive": check_interactive(), "consumer": check_consumer(directory)}
    fonts = json.loads((FIXTURES / "fonts.json").read_text(encoding="utf-8"))["fonts"]
    require(len(fonts) == 2, "included explicit font manifest differs")
    for font in fonts:
        data = (ROOT / "assets/fonts" / font["resource"]).read_bytes()
        require(font["sha256"] == hashlib.sha256(data).hexdigest() and font["bytes"] == len(data), "included explicit source font differs")
    report["fontSources"] = fonts
    contacts = []
    for name, path, count in (("static", FIXTURES / "static.pdf", 1), ("interactive", FIXTURES / "interactive.pdf", 2),
                              ("consumer", directory / "output/consumer-xml-include.pdf", 1)):
        first, second = render(path, OUTPUT / name)
        require(len(first) == len(second) == count, "included renderer omitted a complete page")
        differences = [difference(a, b, 2) for a, b in zip(first, second, strict=True)]
        require(all(value["meanAbsoluteChannelError"] < 4 and value["changedPixelFraction"] < .06 for value in differences),
                "included independent renderers disagree")
        report[name]["rendererDifferences"] = differences
        contacts.extend((f"{name} {index}", image) for index, image in enumerate(first, 1))
    require(len(contacts) == 4, "complete included fixture/caller page count differs")
    contact_sheet(contacts, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"pages": 4, "offlineXmlIncludes": "passed", "nativeLexicalFields": "passed"}, indent=2))


if __name__ == "__main__":
    main()
