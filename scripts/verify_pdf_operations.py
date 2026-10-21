# SPDX-License-Identifier: Apache-2.0
"""Independent static PDF page order, geometry, extraction and visible-content preservation."""
from __future__ import annotations

import json
from pathlib import Path

from pypdf import PdfReader

from verify_advanced_pdf import difference, render_pdfium, render_poppler, require
from verify_production import contact_sheet, extracted

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "examples/build/pdf-operations"
OUTPUT = ROOT / "build/verification/pdf-operations"


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    mapping = {"source-a": [("source-a", 0), ("source-a", 1)], "source-b": [("source-b", 0)],
               "selected": [("source-a", 1), ("source-a", 0)], "split": [("source-a", 1)],
               "merged": [("source-a", 1), ("source-b", 0), ("source-a", 0)]}
    readers, rendered = {}, {}
    report, contacts = {}, []
    for name, pages in mapping.items():
        path = FIXTURES / f"{name}.pdf"
        reader = readers[name] = PdfReader(path, strict=True)
        require(len(reader.pages) == len(pages), f"page count differs: {name}")
        require(not reader.is_encrypted and not reader.get_fields() and not reader.outline, "unexpected interactive output")
        text = extracted(path)
        require(text.count("SOURCE-") == len(pages), f"source pages lost or duplicated: {name}")
        first = render_poppler(path, OUTPUT / name / "poppler")
        second = render_pdfium(path, OUTPUT / name / "pdfium")
        require(len(first) == len(second) == len(pages), "renderer page count differs")
        cross = [difference(a, b, 1) for a, b in zip(first, second, strict=True)]
        require(all(d["meanAbsoluteChannelError"] < 4 and d["changedPixelFraction"] < .06 for d in cross), "renderers differ")
        rendered[name] = first
        contacts.extend((f"{name} {i}", page) for i, page in enumerate(first, 1))
        preservation = []
        for index, (source, source_page) in enumerate(pages):
            target, original = reader.pages[index], readers[source].pages[source_page]
            expected_tag = f"SOURCE-{source[-1].upper()}-{source_page + 1:02d}"
            require(expected_tag in (target.extract_text() or ""), f"page order differs: {name}/{index}")
            require(list(target.mediabox) == list(original.mediabox) and list(target.cropbox) == list(original.cropbox)
                    and target.rotation == original.rotation, "copied page geometry differs")
            require(not target.get("/Annots"), "unexpected page annotations")
            diff = difference(first[index], rendered[source][source_page])
            require(diff["meanAbsoluteChannelError"] == 0, f"static copy changed visible content: {name}/{index}")
            preservation.append(diff)
        report[name] = {"pages": len(pages), "sourcePages": pages, "renderDifference": cross,
                        "sourcePreservation": preservation}
    require("SOURCE-A-01" in (FIXTURES / "read.txt").read_text(encoding="utf-8"), "public extraction lost simple text")
    diagnostics = (FIXTURES / "diagnostics.txt").read_text(encoding="utf-8")
    require("ACTUAL_TEXT_NOT_APPLIED" in diagnostics, "outline extraction limitation was not reported")
    pointer = ROOT / "build/verification/consumer-build-directory.txt"
    consumer = Path(pointer.read_text(encoding="utf-8").strip()) / "output/consumer-pdf-operations.pdf"
    consumer_reader = PdfReader(consumer, strict=True)
    require(len(consumer_reader.pages) == 2 and "CONSUMER-OK-123" in extracted(consumer), "public adapter consumer failed")
    first = render_poppler(consumer, OUTPUT / "consumer/poppler")
    second = render_pdfium(consumer, OUTPUT / "consumer/pdfium")
    require(len(first) == len(second) == 2, "consumer renderer page counts differ")
    for number, (a, b) in enumerate(zip(first, second, strict=True), 1):
        diff = difference(a, b, 1)
        require(diff["meanAbsoluteChannelError"] < 4 and diff["changedPixelFraction"] < .06, "consumer renderers differ")
        contacts.append((f"consumer {number}", a))
    report["consumer"] = {"pages": 2, "mavenCoordinates": "passed"}
    contact_sheet(contacts, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"fixtures": len(mapping), "pagesIncludingConsumer": len(contacts),
                      "sourcePreservation": "exact Poppler pixels", "consumer": "passed"}, indent=2))


if __name__ == "__main__":
    main()
