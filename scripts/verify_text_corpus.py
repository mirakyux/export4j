# SPDX-License-Identifier: Apache-2.0
"""Fixed-font text corpus: logical source, two renderers and measured engine search/selection.

Search failures are reported as compatibility gaps, not hidden by reversed-text matching.
This records PDFium APIs and Poppler extraction, not human reader UI interaction.
"""
from __future__ import annotations

import base64
import csv
from contextlib import closing
import hashlib
from importlib import metadata
import json
import math
from pathlib import Path
import shutil
import subprocess

import pypdfium2 as pdfium
from pypdf import PdfReader

from verify_advanced_pdf import difference, render_pdfium, render_poppler, require
from verify_production import actual_text, contact_sheet

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "examples/build/text-corpus"
OUTPUT = ROOT / "build/verification/text-corpus"


def compact(value):
    return "".join(value.split())


def reader_evidence(path, query):
    result = []
    with pdfium.PdfDocument(path) as document:
        for number in range(len(document)):
            with closing(document[number]) as page, closing(page.get_textpage()) as text:
                occurrences = []
                with closing(text.search(query, match_case=True)) as search:
                    while (match := search.get_next()) is not None:
                        index, count = match
                        selected = text.get_text_range(index, count)
                        rectangles = [list(text.get_rect(i)) for i in range(text.count_rects(index, count))]
                        width, height = page.get_size()
                        require(all(len(rect) == 4 and all(math.isfinite(c) for c in rect)
                                    and -1 <= rect[0] <= rect[2] <= width + 1
                                    and -1 <= rect[1] <= rect[3] <= height + 1 for rect in rectangles),
                                "reader selection rectangle outside page")
                        occurrences.append({"characterIndex": index, "characterCount": count,
                                            "selectedText": selected, "rectangles": rectangles})
                result.append({"page": number + 1, "text": text.get_text_bounded(), "search": occurrences})
    return result


def main():
    for package, expected in (("pypdf", "6.10.0"), ("pypdfium2", "5.13.0"), ("Pillow", "12.3.0")):
        require(metadata.version(package) == expected, f"verification version differs: {package}")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    rows = list(csv.DictReader((FIXTURES / "manifest.tsv").open(encoding="utf-8"), delimiter="\t"))
    require(len(rows) == 12 and len({(r["id"], r["variant"]) for r in rows}) == 12, "corpus inventory differs")
    report = {"tools": {"pdfium": str(pdfium.PDFIUM_INFO),
                        **{p: metadata.version(p) for p in ("pypdf", "pypdfium2", "Pillow")}},
              "scope": "engine search and selected text/rectangles; no live reader UI or per-glyph selection claim",
              "fixtures": [], "compatibilityGaps": []}
    contacts = []
    for row in rows:
        path = FIXTURES / row["file"]
        source = base64.b64decode(row["sourceBase64"]).decode("utf-8")
        query = base64.b64decode(row["queryBase64"]).decode("utf-8")
        reader = PdfReader(path, strict=True)
        require(len(reader.pages) == int(row["pages"]), "manifest page count differs")
        require(all(abs(float(p.mediabox.width) - 320) < .01 and abs(float(p.mediabox.height) - 480) < .01
                    for p in reader.pages), "corpus geometry differs")
        logical = "".join(actual_text(reader))
        extracted = "\n".join(p.extract_text() or "" for p in reader.pages)
        require(compact(source) in compact(logical or extracted), f"logical source lost: {row['file']}")
        text_tool = shutil.which("pdftotext")
        poppler_text = (subprocess.run([text_tool, "-enc", "UTF-8", "-nopgbrk", str(path), "-"],
                                       check=True, capture_output=True).stdout.decode("utf-8") if text_tool else None)
        engine = reader_evidence(path, query)
        matches = [match for page in engine for match in page["search"]]
        expected_matches = source.count(query)
        search_exact = len(matches) == expected_matches and all(m["selectedText"] == query for m in matches)
        selection_nonempty = bool(matches) and all(m["rectangles"] for m in matches)
        pypdf_exact = compact(source) in compact(extracted)
        if not search_exact or not selection_nonempty or not pypdf_exact:
            report["compatibilityGaps"].append({"fixture": row["file"], "query": query,
                                                 "expected": expected_matches, "found": len(matches),
                                                 "selectedTextExact": search_exact, "selectionRectangles": selection_nonempty,
                                                 "pypdfExtractsSource": pypdf_exact})
        name = path.stem
        first = render_poppler(path, OUTPUT / name / "poppler")
        second = render_pdfium(path, OUTPUT / name / "pdfium")
        require(len(first) == len(second) == len(reader.pages), "renderer page counts differ")
        differences = [difference(a, b, 1) for a, b in zip(first, second, strict=True)]
        require(all(d["meanAbsoluteChannelError"] < 4 and d["changedPixelFraction"] < .06 for d in differences),
                f"renderers differ materially: {row['file']}")
        contacts.extend((f"{name} {n}", page) for n, page in enumerate(first, 1))
        report["fixtures"].append({"id": row["id"], "variant": row["variant"], "file": row["file"],
                                   "sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "font": row["font"],
                                   "pages": len(reader.pages), "source": source, "query": query,
                                   "logicalSource": "preserved", "actualText": logical,
                                   "pypdfExtraction": extracted, "popplerExtraction": poppler_text,
                                   "pdfium": engine, "queryOccurrences": expected_matches,
                                   "searchAndSelectedTextExact": search_exact,
                                   "pypdfExtractsSource": pypdf_exact,
                                   "minimumSelectionHeightPoints": min((rect[3] - rect[1] for match in matches
                                                                       for rect in match["rectangles"]), default=0),
                                   "selectionRectanglesNonempty": selection_nonempty, "renderDifference": differences})
    contact_sheet(contacts, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"fixtures": len(rows), "pages": len(contacts), "logicalSources": "passed",
                      "renderers": "passed", "compatibilityGaps": report["compatibilityGaps"]}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
