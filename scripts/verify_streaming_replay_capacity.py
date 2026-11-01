# SPDX-License-Identifier: Apache-2.0
"""Independently verify complete row inventories and sampled rendering from finite capacity JVMs."""
import argparse
import json
import math
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


def positive(value):
    return type(value) is int and value > 0


def verify(directory, verify_active_temporary_bytes=False, require_published_jars=False):
    directory = directory.resolve()
    report = json.loads((directory / "report.json").read_text(encoding="utf-8-sig"))
    require(isinstance(report, dict) and isinstance(report.get("run"), str) and report["run"], "capacity run identity is missing")
    cases = report.get("results")
    require(isinstance(cases, list) and cases, "capacity report contains no executed cases")
    names = set()
    for case in cases:
        require(isinstance(case, dict), "invalid capacity case")
        name = case.get("case", "")
        require(isinstance(name, str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", name)
                and name not in names and (directory / name).resolve().parent == directory,
                "capacity case must have a unique safe local directory")
        names.add(name)
        require(case.get("mode") in {"layout", "pdf", "replay", "stability"}, "unknown capacity mode")
        require(positive(case.get("rows")) and case["rows"] <= 1_000_000, "invalid capacity row count")
        require(positive(case.get("samples")) and type(case.get("remainingTemporaryFiles")) is int
                and case["remainingTemporaryFiles"] == 0, "capacity process samples or final cleanup differ")
        require(isinstance(case.get("fixture"), dict), "capacity fixture counters are missing")
    if require_published_jars:
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
    executed = {"layout": 0, "pdf": 0, "replay": 0, "stability": 0}
    temporary_checks = 0
    for case in cases:
        fixture = case["fixture"]
        if case["mode"] == "stability":
            require(fixture == {"runs": 12, "failures": 8, "successfulRecoveryRuns": 4, "closes": 12, "temporaryFiles": 0}, "repeated failure/cancel recovery counters differ")
            executed["stability"] += 1
            continue
        passes = 2 if case["mode"] == "replay" else 1
        require(fixture.get("rows") == case["rows"] and positive(fixture.get("pages"))
                and fixture.get("passes") == passes and fixture.get("reads") == passes * (case["rows"] + 1)
                and fixture.get("closes") == passes and positive(fixture.get("maxHeapBytes"))
                and fixture["maxHeapBytes"] <= 64 * 1024 * 1024, "capacity source ownership or enforced heap differs")
        executed[case["mode"]] += 1
        if case["mode"] == "layout":
            continue
        path = directory / case["case"] / "streaming-replay" / f"capacity-{case['mode']}-{case['rows']}.pdf"
        require(path.resolve().is_relative_to(directory / case["case"]), "capacity PDF leaves its case directory")
        inspected = inspect_pdf(path, fixture["rows"], fixture["pages"], directory / case["case"] / "independent-rendering")
        if verify_active_temporary_bytes:
            require(type(case.get("peakTemporaryBytes")) in (int, float) and math.isfinite(case["peakTemporaryBytes"])
                    and case["peakTemporaryBytes"] > inspected["bytes"] * .8, "active atomic output was omitted from temporary disk sampling")
            temporary_checks += 1
        result.append({"case": case["case"], **inspected})
    require(not verify_active_temporary_bytes or temporary_checks > 0, "active temporary byte verification requires an actual PDF case")
    return {"run": report["run"], "checks": "passed", "casesVerified": executed,
            "activeTemporaryBytesVerified": temporary_checks > 0, "activeTemporaryByteChecks": temporary_checks,
            "publishedJarsVerified": require_published_jars, "pdfs": result}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--capacity-directory", type=Path, required=True)
    parser.add_argument("--verify-active-temporary-bytes", action="store_true")
    parser.add_argument("--require-published-jars", action="store_true")
    args = parser.parse_args()
    try:
        output = verify(args.capacity_directory, args.verify_active_temporary_bytes, args.require_published_jars)
        status = 0
    except Exception as failure:
        output = {"checks": "failed", "reason": str(failure), "activeTemporaryBytesVerified": False,
                  "publishedJarsVerified": False, "pdfs": []}
        status = 1
    (args.capacity_directory / "independent-report.json").write_text(json.dumps(output, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(output, indent=2))
    return status


if __name__ == "__main__":
    raise SystemExit(main())
