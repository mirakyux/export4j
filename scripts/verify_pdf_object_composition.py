# SPDX-License-Identifier: Apache-2.0
"""Independent ownership, destination, namespace and all-page visual composition checks."""
from pathlib import Path
import json
from pypdf import PdfReader
from verify_pdf_object_editing import render, check_fields
from verify_advanced_pdf import require, difference
from verify_production import contact_sheet

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build/verification/pdf-object-composition"


def owned_widgets(reader):
    for page in reader.pages:
        for ref in page.get("/Annots", []):
            node = ref.get_object()
            if node["/Subtype"] == "/Widget":
                require(node["/P"].indirect_reference.idnum == page.indirect_reference.idnum, "widget has wrong physical page owner")


def main():
    sample = ROOT / "examples/build/pdf-object-editing"
    consumer = Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip()) / "output"
    source = PdfReader(sample / "edited.pdf"); composed = PdfReader(sample / "composed.pdf"); selected = PdfReader(sample / "selected.pdf")
    require(len(composed.pages) == 4 and len(selected.pages) == 1, "selection page count")
    values = {"name": "NAME-READY", "status": "Ready", "approved": "Yes", "mode": "Two"}
    check_fields(composed, {prefix + "_" + name: value for prefix in ("report", "copy") for name, value in values.items()})
    check_fields(selected, {"selected_" + name: value for name, value in values.items()})
    owned_widgets(composed); owned_widgets(selected)
    require(composed.metadata.title == "Edited object fixture", "first source metadata")
    require(set(composed.attachments) == {"report_evidence.txt", "copy_evidence.txt"}, "attachment namespaces")
    require(all(list(composed.attachments[name]) == [b"ATTACHMENT-KEEP"] for name in composed.attachments), "attachment bytes changed")
    outlines = composed.outline
    require([composed.get_destination_page_number(outlines[i]) for i in (0, 2)] == [0, 3], "remapped root destinations")
    require([composed.get_destination_page_number(outlines[i][0]) for i in (1, 3)] == [1, 2], "remapped nested destinations")
    for index, target in ((0, 1), (3, 2)):
        links = [ref.get_object() for ref in composed.pages[index]["/Annots"] if ref.get_object()["/Subtype"] == "/Link" and "/Dest" in ref.get_object()]
        require(len(links) == 1 and links[0]["/Dest"][0].idnum == composed.pages[target].indirect_reference.idnum, "internal page link remap")
    require(not any("/Dest" in ref.get_object() for ref in selected.pages[0]["/Annots"]), "link to excluded page survived")
    group = selected.trailer["/Root"]["/Outlines"]["/First"]["/First"]
    require("/Dest" not in group and "/A" not in group and group["/Title"] == "DETAIL", "excluded bookmark target did not become a group")
    losses = (sample / "composition-losses.tsv").read_text().splitlines()
    require({line.split("\t")[0] for line in losses} == {"DOCUMENT_METADATA", "LINK_TARGET", "BOOKMARK_TARGET"}, "composition loss report")
    caller = PdfReader(consumer / "consumer-object-composed.pdf"); check_fields(caller, {"first_caller": "CALLER-READY", "second_caller": "CALLER-READY"}); owned_widgets(caller)
    for index, original in enumerate((0, 1, 1, 0)):
        require(composed.pages[index].get_contents().get_data() == source.pages[original].get_contents().get_data(), "source drawing stream changed")
    tiles = []; metrics = {}; rendered = {}
    for path, name in ((sample / "composed.pdf", "composed"), (sample / "selected.pdf", "selected"), (consumer / "consumer-object-composed.pdf", "consumer")):
        poppler, pdfium = render(path, OUTPUT / name); rendered[name] = pdfium; metrics[name] = []
        for index, (left, right) in enumerate(zip(poppler, pdfium, strict=True)):
            delta = difference(left, right, 2); require(delta["meanAbsoluteChannelError"] < 4 and delta["changedPixelFraction"] < .06, "composition cross-renderer difference")
            metrics[name].append(delta); tiles.extend([(f"{name} {index+1} Poppler", left), (f"{name} {index+1} PDFium", right)])
    _, source_pages = render(sample / "edited.pdf", OUTPUT / "source")
    for path, source_index in zip(rendered["composed"], (0, 1, 1, 0), strict=True):
        require(difference(path, source_pages[source_index], 2)["meanAbsoluteChannelError"] < .1, "composition changed page appearance")
    contact_sheet(tiles, OUTPUT)
    report = {"pdf_pages": 7, "namespaced_fields": 14, "ownership_and_navigation": "passed", "checks": "passed", "rendering": metrics}
    (OUTPUT / "report.json").write_text(json.dumps(report, indent=2)+"\n"); print(json.dumps(report, indent=2))


if __name__ == "__main__": main()
