# SPDX-License-Identifier: Apache-2.0
"""Independently verify complete row inventories and sampled rendering from finite capacity JVMs."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import pypdfium2 as pdfium
from pypdf import PdfReader
from verify_advanced_pdf import difference, require

ROOT = Path(__file__).resolve().parents[1]


def inspect_pdf(path, rows, expected_pages, output):
    reader = PdfReader(path, strict=True)
    require(len(reader.pages) == expected_pages, "capacity PDF page tree differs from emitted count")
    expected = 0
    with pdfium.PdfDocument(path) as document:
        require(len(document) == expected_pages, "independent capacity PDF page count differs")
        for index in range(len(document)):
            page = document[index]
            try:
                text = page.get_textpage()
                try:
                    value = text.get_text_bounded()
                    identifiers = [int(item) for item in re.findall(r"\bR(\d+)\b", value)]
                    numbers = [int(item) for item in re.findall(r"\bVALUE\s+(\d+)\b", value)]
                    wanted = list(range(expected, expected + len(identifiers)))
                    require(identifiers == numbers == wanted and identifiers, "capacity source rows lost, repeated, reordered or mismatched")
                    expected += len(identifiers)
                finally:
                    text.close()
            finally:
                page.close()
        require(expected == rows, "capacity full source row inventory differs")
        metrics = []
        output.mkdir(parents=True, exist_ok=True)
        for index in sorted({0, len(document) // 2, len(document) - 1}):
            prefix = output / f"poppler-{index + 1}"
            subprocess.run(["pdftoppm", "-f", str(index + 1), "-l", str(index + 1), "-r", "96", "-png", "-singlefile", str(path), str(prefix)], check=True, capture_output=True, timeout=60)
            page = document[index]
            try:
                bitmap = page.render(scale=96 / 72)
                try:
                    target = output / f"pdfium-{index + 1}.png"
                    bitmap.to_pil().convert("RGB").save(target)
                finally:
                    bitmap.close()
            finally:
                page.close()
            delta = difference(prefix.with_suffix(".png"), target, blur_radius=1)
            require(delta["meanAbsoluteChannelError"] < 3 and delta["changedPixelFraction"] < .07, "capacity sampled PDF renderers disagree")
            metrics.append({"page": index + 1, **delta})
    return {"rowsVerified": expected, "pages": expected_pages, "bytes": path.stat().st_size, "sampledRendering": metrics}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--capacity-directory", type=Path, required=True)
    parser.add_argument("--verify-active-temporary-bytes", action="store_true")
    parser.add_argument("--require-published-jars", action="store_true")
    args = parser.parse_args()
    report = json.loads((args.capacity_directory / "report.json").read_text(encoding="utf-8-sig"))
    if args.require_published_jars:
        artifacts = report.get("runtimeArtifacts", [])
        require(artifacts and all(item["kind"] == "jar"
                                 and re.fullmatch(r"[0-9a-f]{64}", item["sha256"]) for item in artifacts),
                "published capacity evidence requires JARs with actual SHA-256 digests")
        require(all(item["version"] for item in artifacts if item["name"].startswith("export4j-")),
                "published Export4j JAR versions are missing")
        require(re.fullmatch(r"[0-9a-f]{64}", report.get("classpathSha256", "")),
                "published capacity classpath file digest is missing")
        require(any(item["name"].startswith("export4j-components-") for item in artifacts)
                and any(item["name"].startswith("export4j-backend-pdf-") for item in artifacts),
                "published capacity runtime is missing production Export4j JARs")
    result = []
    for case in report["results"]:
        require(case["samples"] > 0 and case["remainingTemporaryFiles"] == 0, "capacity process samples or final cleanup differ")
        fixture = case["fixture"]
        if case["mode"] == "stability":
            require(fixture == {"runs": 12, "failures": 8, "successfulRecoveryRuns": 4, "closes": 12, "temporaryFiles": 0}, "repeated failure/cancel recovery counters differ")
            continue
        require(fixture["reads"] == fixture["passes"] * (fixture["rows"] + 1) and fixture["closes"] == fixture["passes"]
                and fixture["maxHeapBytes"] <= 64 * 1024 * 1024, "capacity source ownership or enforced heap differs")
        if case["mode"] == "layout":
            continue
        path = args.capacity_directory / case["case"] / "streaming-replay" / f"capacity-{case['mode']}-{case['rows']}.pdf"
        inspected = inspect_pdf(path, fixture["rows"], fixture["pages"], args.capacity_directory / case["case"] / "independent-rendering")
        if args.verify_active_temporary_bytes:
            require(case["peakTemporaryBytes"] > inspected["bytes"] * .8, "active atomic output was omitted from temporary disk sampling")
        result.append({"case": case["case"], **inspected})
    output = {"run": report["run"], "checks": "passed", "activeTemporaryBytesVerified": args.verify_active_temporary_bytes,
              "publishedJarsVerified": args.require_published_jars, "pdfs": result}
    (args.capacity_directory / "independent-report.json").write_text(json.dumps(output, indent=2) + "\n")
    print(json.dumps(output, indent=2))


if __name__ == "__main__":
    main()
