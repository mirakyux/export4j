# SPDX-License-Identifier: Apache-2.0
"""Check XML multilevel decimal numbering, final links and native DOCX; render all four PDF pages.

Expected source values and geometry are declared independently of generated Java manifests.
The nine-level DOCX is a native structure probe; it adds no PDF rendering claim.
"""
from __future__ import annotations

import argparse
from importlib import metadata
import json
from pathlib import Path
import xml.etree.ElementTree as ET
from zipfile import ZipFile

import pypdfium2 as pdfium
from pypdf import PdfReader
from verify_adr_form import render
from verify_advanced_pdf import difference, require
from verify_production import actual_text, contact_sheet
from verify_semantic_tables import annotations, check_characters, close
from verify_streaming_rich_text import painted_spans, physical_fonts, same_color
from verify_themes import paragraph_format, text_of
from verify_xml_includes import native_page

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "examples/build/xml-numbering"
OUTPUT = ROOT / "build/verification/xml-numbering"
W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
R = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}"
OD = "{urn:com.mirakyux.export4j:semantic:1}"


def contract(consumer=False):
    """Original text, labels, source locations and field reservations, without reading output."""
    size, margin = (10, 20) if consumer else (11, 24)
    rows, fields = [], []

    def item(id_, text, label, page, top, list_id="global", restart=0):
        rows.append({"id": id_, "text": text, "label": label, "page": page, "top": top,
                     "listId": list_id, "restart": restart, "level": len(label.split("."))})

    if consumer:
        item("global-parent", "CONSUMER-PARENT", "5", 0, 48, restart=5)
        item("global-child", "CONSUMER-CHILD", "5.3", 0, 70, restart=3)
        main = {"id": "main-ref", "page": 0, "top": 92,
                "source": "CONSUMER-MAIN-REF number=\ufffc", "resolved": "CONSUMER-MAIN-REF number=5.3",
                "fields": [("global-child", 36, 172.51953)]}
        title, included, global_target, global_label = "XML-NUMBERING-CONSUMER", "CONSUMER-INCLUDED", "global-child", "5.3"
        codes, first_top, self_x, global_x = ("C1", "C2", "ROOT"), 42, (177.5293, 177.5293, 193.45215), (248.83691, 248.83691, 264.75977)
        external_x, external_width = (288.44336, 288.44336, 304.3662), (55.73926, 55.72364, 72.09278)
    else:
        for id_, text, label, top, restart in (
                ("global-parent", "MAIN-PARENT", "3", 52, 3),
                ("global-child", "MAIN-CHILD", "3.2", 74, 2),
                ("global-leaf", "MAIN-LEAF", "3.2.1", 96, 0),
                ("global-next-child", "MAIN-NEXT-CHILD", "3.3", 118, 0),
                ("global-next-parent", "MAIN-NEXT-PARENT", "4", 140, 0),
                ("global-reset-child", "MAIN-RESET-CHILD", "4.1", 162, 0)):
            item(id_, text, label, 0, top, restart=restart)
        main = {"id": "main-ref", "page": 0, "top": 184,
                "source": "MAIN-REF leaf=\ufffc child=\ufffc parent=\ufffc", "resolved": "MAIN-REF leaf=3.2.1 child=3.3 parent=3",
                "fields": [("global-leaf", 42, 104.37842), ("global-next-child", 36, 178.48145), ("global-parent", 36, 255.15137)]}
        title, included, global_target, global_label = "XML-NUMBERING-REPORT", "INCLUDED-LISTS", "global-leaf", "3.2.1"
        codes, first_top, self_x, global_x = ("A", "B", "ROOT"), 46, (141.66992, 142.88379, 167.12354), (216.5083, 217.72217, 241.96191)
        external_x, external_width = (262.4707, 263.68457, 287.92432), (55.1006, 54.53809, 79.31298)
    fields.append(main)
    for index, code in enumerate(codes):
        prefix, suffix = ("record", "@" + str(index)) if index < 2 else ("separate", "")
        stem, number, top = prefix + "-", str(index + 1 if index < 2 else 1), first_top + index * 66
        child_number = number + (".1" if consumer else ".2")
        item(stem + "parent" + suffix, ("CONSUMER-LOCAL-PARENT " if consumer else "INCLUDED-PARENT ") + code,
             number, 1, top, prefix + "-checks")
        item(stem + "child" + suffix, ("CONSUMER-LOCAL-CHILD " if consumer else "INCLUDED-CHILD ") + code,
             child_number, 1, top + 22, prefix + "-checks", 0 if consumer else 2)
        source = ("CONSUMER-LOCAL-REF " if consumer else "INCLUDED-REF ") + code + " self=\ufffc global=\ufffc DETAILS-" + code
        fields.append({"id": stem + "ref" + suffix, "page": 1, "top": top + 44, "source": source,
                       "resolved": source.replace("\ufffc", child_number, 1).replace("\ufffc", global_label, 1),
                       "fields": [(stem + "child" + suffix, 36, self_x[index]), (global_target, 36 if consumer else 42, global_x[index])],
                       "external": ("DETAILS-" + code, external_x[index], external_width[index])})
    ids = ["report"] + [row["id"] for row in rows if row["page"] == 0] + ["main-ref", "break", "included-title"]
    for index in range(3):
        ids.extend([row["id"] for row in rows if row["page"] == 1][index * 2:index * 2 + 2])
        ids.append(fields[index + 1]["id"])
    return {"size": size, "margin": margin, "width": 440 if consumer else 420, "height": 460 if consumer else 520,
            "title": title, "included": included, "rows": rows, "fields": fields, "ids": ids,
            "uri": "https://example.com/consumer-numbering" if consumer else "https://example.com/numbering"}


def pdfium_source(path, config):
    """Check each source line once despite PDFium's outline-anchor reading-order limitation."""
    count = 0
    with pdfium.PdfDocument(path) as document:
        require(len(document) == 2, "numbering PDFium page count differs")
        for index in range(2):
            page = document[index]
            text = page.get_textpage()
            try:
                lines = [line.strip() for line in text.get_text_bounded().splitlines() if line.strip()]
                wanted = [config["title"] if index == 0 else config["included"]]
                wanted.extend(row["label"] + ". " + row["text"] for row in config["rows"] if row["page"] == index)
                wanted.extend(value["resolved"] for value in config["fields"] if value["page"] == index)
                require(sorted(lines) == sorted(wanted), "numbering PDFium original text/full labels differ")
                count += len(lines)
            finally:
                text.close()
                page.close()
    return count


def check_pdf(path, config):
    reader = PdfReader(path, strict=True)
    require(len(reader.pages) == 2 and not reader.get_fields(), "numbering PDF page/form contract differs")
    for page in reader.pages:
        close(page.mediabox.width, config["width"], "numbering PDF page width differs")
        close(page.mediabox.height, config["height"], "numbering PDF page height differs")
    require(actual_text(reader) == [value["resolved"] for value in config["fields"]], "numbering final complete reference values differ")
    spans = painted_spans(reader)
    expected = []
    for page in range(2):
        expected.append((page, config["title"] if page == 0 else config["included"], config["margin"], config["margin"], 16 if page == 0 else config["size"]))
        for row in (value for value in config["rows"] if value["page"] == page):
            x = config["margin"] + (row["level"] - 1) * config["size"] * 1.5
            expected.append((page, row["label"] + ".", x, row["top"], config["size"]))
            expected.append((page, row["text"], None, row["top"], config["size"]))
    require([(span["page"], span["text"]) for span in spans] == [(value[0], value[1]) for value in expected],
            "numbering painted source order/full decimal prefixes differ")
    for index, (span, (_, _, x, top, size)) in enumerate(zip(spans, expected, strict=True)):
        close(span["size"], size, "numbering painted font size differs")
        require(same_color(span["color"], (0, 0, 0)), "numbering painted text color differs")
        close(span["baseline"], top + size * 1854 / 2048, "numbering source baseline/line spacing differs")
        if x is not None: close(span["x"], x, "numbering level indentation differs")
        else: close(span["x"], spans[index - 1]["right"], "numbering body no longer follows its complete prefix")
    all_links = annotations(reader)
    internal = [value for value in all_links if "targetPageIndex" in value]
    external = [value for value in all_links if "uri" in value]
    wanted_internal = [(value, field) for value in config["fields"] for field in value["fields"]]
    require(len(internal) == len(wanted_internal) and len(external) == 3, "numbering final link count differs")
    rows = {value["id"]: value for value in config["rows"]}
    for link, (source, (target, width, x)) in zip(internal, wanted_internal, strict=True):
        require(link["pageIndex"] == source["page"] and link["targetPageIndex"] == rows[target]["page"],
                "numbering reference escaped its current lexical copy/global page")
        for key, value in (("x", x), ("y", source["top"]), ("width", width), ("height", 18),
                           ("destinationX", config["margin"]), ("destinationY", rows[target]["top"])):
            close(link[key], value, "numbering final reference rectangle/XYZ differs: " + key)
    for link, source in zip(external, config["fields"][1:], strict=True):
        _, x, width = source["external"]
        require(link["uri"] == config["uri"] and link["pageIndex"] == 1, "numbering passive text URI differs")
        for key, value in (("x", x), ("y", source["top"]), ("width", width), ("height", 18)):
            close(link[key], value, "numbering final external text rectangle differs: " + key)
    for link in all_links:
        require(config["margin"] <= link["x"] < link["x"] + link["width"] <= config["width"] - config["margin"]
                and config["margin"] <= link["y"] < link["y"] + link["height"] <= config["height"] - config["margin"],
                "numbering link leaves page margins")
    return {"pages": 2, "numberedItems": len(rows), "resolvedFields": len(internal), "internalLinks": len(internal),
            "externalLinks": len(external), "pdfiumSourceLines": pdfium_source(path, config),
            "characters": check_characters(path, config["margin"]), "physicalFonts": physical_fonts(reader, {"LiberationSans"})}


def native_number(numbering, node, label):
    """Resolve standard native rules and ancestor starts; numId sharing is not a contract."""
    values = [int(value) for value in label.split(".")]
    properties = node.find(W + "pPr/" + W + "numPr")
    require(properties is not None, "native numbering properties missing")
    require(properties.find(W + "ilvl").get(W + "val") == str(len(values) - 1), "native zero-based numbering level differs")
    num_id = properties.find(W + "numId").get(W + "val")
    instances = [value for value in numbering.findall(W + "num") if value.get(W + "numId") == num_id]
    require(len(instances) == 1, "native numbering instance missing/repeated")
    reference = instances[0].find(W + "abstractNumId").get(W + "val")
    definitions = [value for value in numbering.findall(W + "abstractNum") if value.get(W + "abstractNumId") == reference]
    require(len(definitions) == 1, "native abstract numbering missing/repeated")
    require(definitions[0].find(W + "multiLevelType").get(W + "val") == "multilevel", "native multilevel type differs")
    levels = definitions[0].findall(W + "lvl")
    require([value.get(W + "ilvl") for value in levels] == [str(index) for index in range(9)], "native nine-level definitions differ")
    for index, level in enumerate(levels):
        require(level.find(W + "numFmt").get(W + "val") == "decimal"
                and level.find(W + "start").get(W + "val") == "1"
                and level.find(W + "lvlText").get(W + "val") == ".".join("%" + str(value) for value in range(1, index + 2)) + ".",
                "native complete decimal numbering rule differs")
    overrides = instances[0].findall(W + "lvlOverride")
    require([value.get(W + "ilvl") for value in overrides] == [str(index) for index in range(len(values))],
            "native ancestor level overrides missing/repeated")
    for override, expected in zip(overrides, values, strict=True):
        starts = override.findall(W + "startOverride")
        require(override.find(W + "lvl") is None and len(starts) == 1 and int(starts[0].get(W + "val")) == expected,
                "native effective ancestor/current startOverride differs")


def native_source(node):
    values = []
    for child in node:
        if child.tag in (W + "r", W + "hyperlink"): values.append(text_of(child))
        elif child.tag == W + "fldSimple": values.append("\ufffc")
    return "".join(values)


def native_fields(root, sources):
    starts, ends = list(root.iter(W + "bookmarkStart")), list(root.iter(W + "bookmarkEnd"))
    bookmarks = {value.get(W + "name"): value.get(OD + "owner") for value in starts}
    target_ids = {target for source in sources for target, _, _ in source["fields"]}
    require(len(bookmarks) == len(starts) == len(target_ids) and set(bookmarks.values()) == target_ids,
            "native numbering reference target bookmarks differ")
    require({value.get(W + "id"): value.get(OD + "owner") for value in starts}
            == {value.get(W + "id"): value.get(OD + "owner") for value in ends} and len(ends) == len(starts),
            "native numbering bookmark ends differ")
    locations = {child: (paragraph, index) for paragraph in root.iter(W + "p") for index, child in enumerate(paragraph)}
    require(len({value.get(W + "id") for value in starts}) == len(starts), "native numbering bookmark IDs repeated")
    for start in starts:
        require(start in locations, "native numbering target bookmark is not a direct paragraph child")
        paragraph, index = locations[start]
        require(paragraph.get(OD + "id") == start.get(OD + "owner") in target_ids,
                "native numbering bookmark moved outside its actual target paragraph")
        matching_ends = [value for value in ends if value.get(W + "id") == start.get(W + "id")]
        require(len(matching_ends) == 1 and matching_ends[0] in locations
                and locations[matching_ends[0]][0] is paragraph and locations[matching_ends[0]][1] > index,
                "native numbering bookmark end moved outside its actual target paragraph")
        end_index = locations[matching_ends[0]][1]
        outside = list(paragraph)[:index] + list(paragraph)[end_index + 1:]
        require(not any(child.tag in (W + "r", W + "hyperlink", W + "fldSimple")
                        and (text_of(child) or child.tag == W + "fldSimple") for child in outside),
                "native numbering bookmark no longer encloses its full target text")
    all_fields = list(root.iter(W + "fldSimple"))
    expected = [(target, width) for source in sources for target, width, _ in source["fields"]]
    require(len(all_fields) == len(expected), "native number-reference field count differs")
    for field, (target, width) in zip(all_fields, expected, strict=True):
        instruction = field.get(W + "instr").split()
        require(len(instruction) == 4 and instruction[0] == "REF" and instruction[2:] == ["\\w", "\\h"]
                and bookmarks.get(instruction[1]) == target and field.get(W + "dirty") == "true",
                "native complete-number REF switches/lexical target/update flag differ")
        close(float(field.get(OD + "width")), width, "native number-reference reservation differs")
        require(text_of(field) == "", "native number-reference unexpectedly relies on a cached number")
    return len(all_fields)


def package(path):
    with ZipFile(path) as archive:
        require(not any(name.startswith("word/media/") for name in archive.namelist()), "text-only numbering DOCX gained media")
        root = ET.fromstring(archive.read("word/document.xml"))
        numbering = ET.fromstring(archive.read("word/numbering.xml"))
        relationships = ET.fromstring(archive.read("word/_rels/document.xml.rels"))
    nodes = [value for value in root.find(W + "body") if value.tag != W + "sectPr"]
    require(all(value.tag == W + "p" for value in nodes), "numbering DOCX gained nonparagraph blocks")
    ids = [value.get(OD + "id") for value in nodes]
    require(all(ids) and len(ids) == len(set(ids)), "numbering DOCX source IDs missing/repeated")
    return root, numbering, relationships, nodes, dict(zip(ids, nodes, strict=True))


def check_docx(path, config):
    root, numbering, relationships, nodes, by_id = package(path)
    require([value.get(OD + "id") for value in nodes] == config["ids"], "native numbering complete source order differs")
    native_page(root, config["width"], config["height"], config["margin"])
    for row in config["rows"]:
        node = by_id[row["id"]]
        require(node.get(OD + "kind") == "number" and node.get(OD + "listId") == row["listId"]
                and node.get(OD + "restart") == str(row["restart"]) and native_source(node) == row["text"],
                "native list source text/identity/restart differs")
        native_number(numbering, node, row["label"])
    for id_, text, size, line in (("report", config["title"], 16, 24), ("included-title", config["included"], config["size"], 18)):
        require(native_source(by_id[id_]) == text, "native numbering title text differs")
        paragraph_format(by_id[id_], size, line)
    for source in config["fields"]:
        require(native_source(by_id[source["id"]]) == source["source"], "native numbering source field positions/text differ")
    require(by_id["break"].get(OD + "kind") == "break"
            and by_id["break"].find(W + "r/" + W + "br").get(W + "type") == "page", "native explicit page break differs")
    for node in nodes:
        if node.get(OD + "id") in ("report", "break"): continue
        paragraph_format(node, config["size"], 18)
    numbered_ids = {value["id"] for value in config["rows"]}
    require(all(node.get(OD + "id") in numbered_ids or node.find(W + "pPr/" + W + "numPr") is None for node in nodes),
            "native plain source paragraph gained list numbering")
    targets = {value.get("Id"): value for value in relationships}
    links = list(root.iter(W + "hyperlink"))
    require(len(links) == 3, "native numbering external link count differs")
    for link, source in zip(links, config["fields"][1:], strict=True):
        relationship = targets[link.get(R + "id")]
        require(text_of(link) == source["external"][0] and relationship.get("TargetMode") == "External"
                and relationship.get("Type").endswith("/hyperlink") and relationship.get("Target") == config["uri"],
                "native passive link text/relationship differs")
    require(all(value.get("Type").endswith("/hyperlink") and value.get("Target") == config["uri"]
                for value in relationships if value.get("TargetMode") == "External"), "native numbering gained an external relationship")
    return {"topLevelBlocks": len(nodes), "numberedItems": len(config["rows"]), "nativeFields": native_fields(root, config["fields"]),
            "nativeNumbering": "nine-level decimal rules and each effective ancestor override checked"}


def check_ninth_level(path):
    root, numbering, relationships, nodes, by_id = package(path)
    require([value.get(OD + "id") for value in nodes] == ["depth-" + str(value) for value in range(1, 10)] + ["deep-ref"],
            "native ninth-level source order differs")
    native_page(root, 420, 520, 24)
    labels = []
    for level in range(1, 10):
        node = by_id["depth-" + str(level)]
        label = ".".join(["1"] * (level - 1) + ["2147483647" if level == 9 else "1"])
        require(node.get(OD + "kind") == "number" and node.get(OD + "listId") == "deep"
                and node.get(OD + "restart") == ("2147483647" if level == 9 else "1")
                and native_source(node) == "DEPTH-" + str(level), "native ninth-level source/restart differs")
        native_number(numbering, node, label)
        labels.append(label)
    for node in nodes: paragraph_format(node, 10, 18)
    require(native_source(by_id["deep-ref"]) == "NINTH-REF \ufffc" and not list(root.iter(W + "hyperlink"))
            and not any(value.get("TargetMode") == "External" for value in relationships), "native ninth-level reference source differs")
    fields = native_fields(root, [{"fields": [("depth-9", 160, None)]}])
    return {"numberedItems": 9, "maximumLevel": 9, "lastLabel": labels[-1], "nativeFields": fields,
            "scope": "native DOCX structure and Java strict full-model round-trip; no additional PDF pages"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--consumer-build-directory", type=Path)
    args = parser.parse_args()
    versions = {name: metadata.version(name) for name in ("pypdf", "pypdfium2", "Pillow")}
    require(versions == {"pypdf": "6.10.0", "pypdfium2": "5.13.0", "Pillow": "12.3.0"}, "use the pinned verification environment")
    directory = args.consumer_build_directory
    if directory is None: directory = Path((ROOT / "build/verification/consumer-build-directory.txt").read_text(encoding="utf-8").strip())
    if not directory.is_absolute(): directory = ROOT / directory
    OUTPUT.mkdir(parents=True, exist_ok=True)
    report = {"tools": versions, "consumerBuildDirectory": str(directory)}
    contacts = []
    for name, path, config in (("sample", FIXTURES / "report.pdf", contract()),
                               ("consumer", directory / "output/consumer-xml-numbering.pdf", contract(True))):
        result = check_pdf(path, config)
        result["docx"] = check_docx(path.with_suffix(".docx"), config)
        first, second = render(path, OUTPUT / name)
        require(len(first) == len(second) == 2, "numbering independent renderer omitted a complete page")
        differences = [difference(a, b, 2) for a, b in zip(first, second, strict=True)]
        require(all(value["meanAbsoluteChannelError"] < 4 and value["changedPixelFraction"] < .06 for value in differences),
                "numbering independent renderers disagree")
        result["rendererDifferences"] = differences
        report[name] = result
        contacts.extend((name + " " + str(index), image) for index, image in enumerate(first, 1))
    report["ninthLevelDocx"] = check_ninth_level(FIXTURES / "nine-level.docx")
    require(len(contacts) == 4, "numbering complete fixture/caller page count differs")
    contact_sheet(contacts, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"pagesIncludingConsumer": 4, "xmlMultilevelNumbering": "passed", "nativeNinthLevel": "passed"}, indent=2))


if __name__ == "__main__":
    main()
