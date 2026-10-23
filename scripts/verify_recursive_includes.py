# SPDX-License-Identifier: Apache-2.0
"""Independently check nested namespaces, final fields, dynamic links and native OOXML."""
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from zipfile import ZipFile
from pypdf import PdfReader
from verify_advanced_pdf import difference, require
from verify_inline_images import image_boxes
from verify_pdf_raster import references
from verify_production import contact_sheet
from verify_semantic_tables import annotations, check_characters, page_logical

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "examples/build/recursive-includes"
OUTPUT = ROOT / "build/verification/recursive-includes"
W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
OD = "{urn:com.mirakyux.export4j:semantic:1}"
REL = "{http://schemas.openxmlformats.org/package/2006/relationships}"
URLS = {"https://example.com/records/A?view=detail", "mailto:docs@example.com"}


def native(path, consumer=False):
    with ZipFile(path) as package:
        content = package.read("word/document.xml")
        root = ET.fromstring(content)
        relations = package.read("word/_rels/document.xml.rels")
        require(b".template-link/" not in content + relations and b"${" not in content + relations,
                "unbound template destination escaped into DOCX")
        actual = {node.get("Target") for node in ET.fromstring(relations).findall(REL + "Relationship")
                  if node.get("Type", "").endswith("/hyperlink")}
        require(actual == ({"https://example.com/caller"} if consumer else URLS), "native external destinations")
        owners = {node.get(W + "name"): node.get(OD + "owner") for node in root.iter(W + "bookmarkStart")}
        fields = list(root.iter(W + "fldSimple"))
        require(len(fields) == (1 if consumer else 14), "native field count")
        for node in fields:
            instruction = node.get(W + "instr").split()
            if instruction[0] in ("PAGEREF", "REF"):
                require(instruction[1] in owners, "native reference points outside bookmark tree")
        if consumer:
            require(set(owners.values()) == {"outer-child-target"}, "caller composed namespace")
        else:
            by_id = {node.get(OD + "id"): node for node in root.iter(W + "p")}
            require(sum(1 for node in root.iter(W + "br") if node.get(W + "type") == "page") == 2, "native page breaks")
            for index in range(2):
                for suffix in ("parent", "leaf-local", "leaf-dynamic", "leaf-global"):
                    paragraph = by_id[f"batch-{suffix}@{index}"]
                    targets = [owners[node.get(W + "instr").split()[1]] for node in paragraph.iter(W + "fldSimple")]
                    expected = "global-target" if suffix == "leaf-global" else f"batch-leaf-target@{index}"
                    require(targets and set(targets) == {expected}, "native reference escaped lexical copy")
            require(len([name for name in package.namelist() if name.startswith("word/media/")]) == 1, "native image deduplication")


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    path = SOURCE / "includes.pdf"
    reader = PdfReader(path, strict=True)
    require(len(reader.pages) == 3, "nested include page count")
    require(reader.pages[0].extract_text() == "GLOBAL-TARGET", "main external target")
    links = annotations(reader)
    for index, code in enumerate(("A", "B"), 1):
        text = "\n".join(page_logical(reader.pages[index]))
        expected = [f"PARENT-{code} child={index+1}", f"LOCAL-{code} page={index+1} number={index}",
                    f"DYNAMIC-{code} page={index+1}", f"GLOBAL-{code} page=1", f"FINAL-{code} page={index+1} total=3", f"TEXT-{code}"]
        require(all(text.count(value) == 1 for value in expected), "scoped fields differ from final physical pages")
        require(f"{index}. TARGET-{code}" in reader.pages[index].extract_text(), "scoped list numbering")
        page_links = [link for link in links if link["pageIndex"] == index]
        internal = [link for link in page_links if "targetPageIndex" in link]
        require(len(internal) == 5 and sum(link["targetPageIndex"] == 0 for link in internal) == 1,
                "external target reference page")
        require(all(link["targetPageIndex"] == index and abs(link["destinationY"] - 52) < .01
                    for link in internal if link["targetPageIndex"] != 0), "local child reference destination")
        external = [link for link in page_links if "uri" in link]
        require(len(external) == 2 and {link["uri"] for link in external} == {sorted(URLS)[index-1]}, "dynamic text/image destination")
    require(len(image_boxes(reader)) == 2, "image occurrences")
    native(SOURCE / "includes.docx")
    caller = Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip()) / "output"
    caller_reader = PdfReader(caller / "consumer-includes.pdf", strict=True)
    require(len(caller_reader.pages) == 1 and "CALLER-NESTED" in caller_reader.pages[0].extract_text(), "caller nested content")
    caller_links = annotations(caller_reader)
    require(len(caller_links) == 2 and any(link.get("uri") == "https://example.com/caller" for link in caller_links)
            and any(link.get("targetPageIndex") == 0 for link in caller_links), "caller bound destinations")
    native(caller / "consumer-includes.docx", True)
    tiles, report = [], {}
    for label, pdf in (("includes", path), ("caller", caller / "consumer-includes.pdf")):
        differences = []
        for index, (first, second) in enumerate(zip(*references(pdf, OUTPUT / label), strict=True)):
            diff = difference(first, second, blur_radius=1)
            require(diff["meanAbsoluteChannelError"] < 3 and diff["changedPixelFraction"] < .04, "independent render disagreement")
            differences.append(diff)
            tiles.extend([(f"{label} Poppler {index+1}", first), (f"{label} PDFium {index+1}", second)])
        report[label] = {"characters": check_characters(pdf, 16), "renderDifferences": differences}
    contact_sheet(tiles, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print("Recursive includes: namespaces, lexical copies, final fields, dynamic text/image links, native DOCX and dual rendering passed")


if __name__ == "__main__":
    main()
