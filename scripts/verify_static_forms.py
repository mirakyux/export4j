# SPDX-License-Identifier: Apache-2.0
"""Final vector shapes, logical choices, source order, grid geometry and independent rendering."""
from __future__ import annotations

import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from pypdf import PdfReader
from verify_advanced_pdf import difference, require
from verify_adr_form import render
from verify_multi_floats import pdf_geometry
from static_forms_geometry import form_marks, writing_lines
from verify_production import actual_text, contact_sheet

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build/verification/static-forms"
FIXTURES = ROOT / "examples/build/static-forms"


def check(path, consumer=False):
    reader = PdfReader(path)
    logical = "".join(actual_text(reader))
    glyphs = "".join(page.extract_text() or "" for page in reader.pages)
    require(not reader.get_fields(), "static controls unexpectedly created AcroForm fields")
    marks = form_marks(reader)
    logical_counts = {"checkbox": logical.count("□") + logical.count("☑"), "radio": logical.count("○") + logical.count("●")}
    for kind, count in logical_counts.items():
        require(sum(m["kind"] == kind for m in marks) == count, f"{kind} vector ink differs from logical controls")
    require(sum(m["selected"] for m in marks) == logical.count("☑") + logical.count("●"), "selected vector marks differ")
    require(marks, "no static vector controls")
    margin = 16 if consumer else 20
    lines = writing_lines(reader)
    require(len(lines) == 1, "writing line lost or duplicated")
    line = lines[0]
    require(line["page"] == 0 and abs(line["stroke"] - .5) < .003
            and abs(line["left"] - margin - .25) < .003
            and abs(line["right"] - float(reader.pages[0].mediabox.width) + margin + .25) < .003
            and margin <= line["y"] <= float(reader.pages[0].mediabox.height) - margin, "writing line leaves viewport or has wrong width")
    for mark in marks:
        page = reader.pages[mark["page"]]
        require(mark["x"] >= margin - .003 and mark["y"] >= margin - .003
                and mark["x"] + mark["size"] <= float(page.mediabox.width) - margin + .003
                and mark["y"] + mark["size"] <= float(page.mediabox.height) - margin + .003, "mark leaves content viewport")
    if consumer:
        require(len(reader.pages) == 1 and "CONSUMER-FORM ☑ YES □ NO" in logical and "CONSUMER-LABEL" in glyphs, "consumer content differs")
    else:
        for prefix, count in (("ITEM", 26), ("COL", 32)):
            require(re.findall(prefix + r"(\d{3})", logical) == [f"{i:03d}" for i in range(1, count + 1)], f"{prefix} options lost or reordered")
        require("BIDI ☑ العربية a\u0301" in logical, "bidi form label or mark lost")
        require(glyphs.count("ONCE") == glyphs.count("TAIL-STATIC-FORMS") == 1, "short merged label or tail lost/repeated")
        rectangles, anchors, _ = pdf_geometry(reader, 18)
        for anchor in anchors:
            if anchor["source"].startswith("ITEM"):
                require(any(r["page"] == anchor["page"] and r["x"] - .003 <= anchor["x"] and r["y"] - .003 <= anchor["y"]
                            and r["x"] + r["width"] + .003 >= anchor["x"] + anchor["width"]
                            and r["y"] + r["height"] + .003 >= anchor["y"] + anchor["height"] for r in rectangles), "option line leaves merged cell")
        require(glyphs.count("OPTIONS") >= 2, "continuation headers missing")
    return dict(pages=len(reader.pages), marks=len(marks), selected=sum(m["selected"] for m in marks), writingLines=len(lines), logicalCounts=logical_counts)


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    pointer = ROOT / "build/verification/consumer-build-directory.txt"
    consumer = Path(pointer.read_text(encoding="utf-8").strip()) / "output/consumer-static-forms.pdf"
    report, contacts = {}, []
    for name, path in (("static", FIXTURES / "static-forms.pdf"), ("consumer", consumer)):
        report[name] = check(path, name == "consumer")
        poppler, pdfium = render(path, OUTPUT / name)
        require(len(poppler) == len(pdfium) == report[name]["pages"], "independent renderer missed pages")
        comparisons = [difference(a, b, 2) for a, b in zip(poppler, pdfium, strict=True)]
        require(all(d["meanAbsoluteChannelError"] < 4 and d["changedPixelFraction"] < .06 for d in comparisons), "independent static form renderers disagree")
        report[name]["crossRenderer192DpiBlur2"] = comparisons
        contacts.extend((f"{name} / {i+1}", image) for i, image in enumerate(poppler))
    svg = ET.parse(FIXTURES / "static-forms.svg").getroot()
    labels = "".join(g.get("aria-label", "") for g in svg.iter("{http://www.w3.org/2000/svg}g"))
    require(re.findall(r"ITEM(\d{3})", labels) == [f"{i:03d}" for i in range(1, 27)], "SVG option order differs")
    require(labels.count("☑") > 0 and labels.count("●") == 2, "SVG static mark semantics missing")
    contact_sheet(contacts, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
