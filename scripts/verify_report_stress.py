# SPDX-License-Identifier: Apache-2.0
"""Checks fresh sampled stress exports, source isolation and every page with two independent renderers."""
from __future__ import annotations

import json
from importlib import metadata
from pathlib import Path
import re
from PIL import Image, ImageDraw
from pypdf import PdfReader
from verify_advanced_pdf import difference, render_pdfium, render_poppler, require
from verify_inline_images import image_boxes
from verify_production import actual_text

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "examples/build/report-stress"
OUTPUT = ROOT / "build/verification/report-stress"


def overview(paths, target):
    sheet = Image.new("RGB", (1080, 260 * ((len(paths) + 5) // 6)), "#eeeeee")
    draw = ImageDraw.Draw(sheet)
    for index, path in enumerate(paths):
        with Image.open(path) as image:
            page = image.convert("RGB")
            page.thumbnail((170, 235))
        x, y = (index % 6) * 180, (index // 6) * 260
        sheet.paste(page, (x + 5, y + 20))
        draw.text((x + 6, y + 4), f"page {index + 1}", fill="black")
    sheet.save(target)


def main():
    run = json.loads((FIXTURES / "report.json").read_text(encoding="utf-8"))
    resources = json.loads((OUTPUT / "process-report.json").read_text(encoding="utf-8-sig"))
    require(run == resources["fixture"] and run["run"] == resources["run"], "resource samples are from a different run")
    require(len(run["exports"]) == 6 and run["concurrency"] == 2 and run["recovery"], "incomplete bounded workload")
    require(resources["samples"] >= 2 and resources["peakWorkingSetBytes"] > 0, "process was not sampled")
    require("RECOVERY-OK" in PdfReader(FIXTURES / "recovery.pdf").pages[0].extract_text(), "failed/cancelled caller did not recover")
    checks = []
    for export in run["exports"]:
        index = export["index"]
        path = FIXTURES / f"report-{index}.pdf"
        reader = PdfReader(path)
        require(len(reader.pages) == export["pages"] >= 20 and path.stat().st_size == export["bytes"], "stress artifact differs from completed run")
        require(reader.metadata.title == f"REQUEST-{index}-END", "concurrent metadata isolation failed")
        text = "\n".join(page.extract_text() or "" for page in reader.pages)
        require(re.findall(r"GROUP-(\d{3})-END", text) == [f"{group:03d}" for group in range(run["groupsPerReport"])], "body markers lost, reordered or duplicated")
        require(re.findall(r"REQUEST-(\d+)-END", text) == [str(index)] * run["groupsPerReport"], "document content crossed concurrent requests")
        require(len(image_boxes(reader)) == 10, "stress inline image lost or repeated")
        require(any("PAGE=" in value for value in actual_text(reader)), "stress field output missing")
        poppler = render_poppler(path, OUTPUT / f"report-{index}/poppler")
        pdfium = render_pdfium(path, OUTPUT / f"report-{index}/pdfium")
        deltas = [difference(a, b, 1) for a, b in zip(poppler, pdfium, strict=True)]
        # Match the existing production/capability tolerance for dense pages at 96 DPI.
        require(all(delta["meanAbsoluteChannelError"] < 4 and delta["changedPixelFraction"] < .06 for delta in deltas), "stress independent renderers disagree")
        overview(poppler, OUTPUT / f"overview-{index}.png")
        checks.append({"index": index, "pages": len(reader.pages), "markers": run["groupsPerReport"], "twoRendererDifferences": deltas})
        print(f"Stress report {index}: {len(reader.pages)} pages verified", flush=True)
    result = {"run": run["run"], "tools": {name: metadata.version(name) for name in ("pypdf", "pypdfium2", "Pillow")},
              "totalPages": sum(check["pages"] for check in checks), "exports": checks,
              "resources": {key: value for key, value in resources.items() if key != "fixture"},
              "scope": "Bounded workload and sampled Windows JVM process; not a long-running leak or capacity guarantee."}
    (OUTPUT / "verification.json").write_text(json.dumps(result, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
