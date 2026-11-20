# SPDX-License-Identifier: Apache-2.0
"""Independently inspect published review fixes and render every new PDF page with two engines."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
from zipfile import ZipFile
import xml.etree.ElementTree as ET

import pdfplumber

from verify_architecture_convergence import check_markers, check_runtime, selected_characters, source_path, tools
from verify_easy_export import contact_sheet, digest, render_pdf, require_same_pixels

ROOT = Path(__file__).resolve().parents[1]
NS = {"w": "http://schemas.openxmlformats.org/wordprocessingml/2006/main"}
W = "{" + NS["w"] + "}"


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def check_characters(characters, expected_font, expected_rgb, label):
    require(characters, f"missing physical text: {label}")
    fonts = {re.sub(r"^[A-Z]{6}\+", "", char["fontname"].lstrip("/")) for char in characters}
    require(fonts == {expected_font}, f"physical font changed: {label}: {fonts}")
    for char in characters:
        color = char["non_stroking_color"]
        require(isinstance(color, (list, tuple)) and len(color) == 3, f"unexpected text color representation: {label}")
        rgb = tuple(round(component * 255) for component in color)
        require(rgb == expected_rgb, f"physical text color changed: {label}: {rgb}")


def check_pdf(path, case):
    with pdfplumber.open(path) as document:
        require(len(document.pages) == 1, f"fixture unexpectedly repaginated: {path.name}")
        characters = document.pages[0].chars
        text = "".join(char["text"] for char in characters)
        check_markers(text, case["markers"], path.name)
        rgb = tuple((case["rgb"] >> shift) & 255 for shift in (16, 8, 0))
        if case["fallback"]:
            for marker in ("PREFIX", "SUFFIX"):
                check_characters(selected_characters(characters, marker), "LiberationSerif", rgb, path.name + "/" + marker)
            font = "LiberationSerif-BoldItalic" if case["fontRole"].endswith("_boldItalic") else "LiberationSerif"
            check_characters(selected_characters(characters, case["markers"][1]), font, rgb, path.name + "/formula-source")
        else:
            # Inspect actual marker glyphs as well as the ordinary text. Repeated digits are
            # excluded from the body markers so the numbered prefix has an unambiguous owner.
            for marker in case["markers"]:
                check_characters(selected_characters(characters, marker), "LiberationSerif", rgb, path.name + "/" + marker)
            bullets = [char for char in characters if char["text"] == "•"]
            numbers = [char for char in characters if char["text"] in ("1", ".")]
            require(len(bullets) == 1 and "".join(char["text"] for char in numbers) == "1.", "list prefixes changed")
            check_characters(bullets + numbers, "LiberationSerif", rgb, path.name + "/list-markers")
        return {"sha256": digest(path), "pages": len(document.pages), "physicalTextChecked": True}


def check_docx(path, case):
    with ZipFile(path) as package:
        require(package.testzip() is None, "DOCX CRC failure")
        document = ET.fromstring(package.read("word/document.xml"))
        styles = ET.fromstring(package.read("word/styles.xml"))
        numbering = ET.fromstring(package.read("word/numbering.xml")) if not case["fallback"] else None
    check_markers("".join(node.text or "" for node in document.findall(".//w:t", NS)), case["markers"], path.name)
    registered = {style.get(W + "styleId"): style for style in styles.findall("w:style", NS)}
    roles = {"report", case["fontRole"]}
    for role in roles:
        properties = registered[role].find("w:rPr", NS)
        fonts = properties.find("w:rFonts", NS)
        require(fonts is not None and all(fonts.get(W + slot) == case["fontFamily"] for slot in ("ascii", "hAnsi", "eastAsia", "cs")),
                f"DOCX style family changed: {path.name}/{role}")
        if role.endswith("_boldItalic"):
            require(properties.find("w:b", NS) is not None and properties.find("w:i", NS) is not None, "DOCX lost combined font variant")
    for marker in case["markers"]:
        runs = [run for run in document.findall(".//w:r", NS) if marker in "".join(node.text or "" for node in run.findall("w:t", NS))]
        require(len(runs) == 1, f"DOCX marker run missing or split: {marker}")
        properties = runs[0].find("w:rPr", NS)
        role = properties.find("w:rStyle", NS)
        expected_role = case["fontRole"] if case["fallback"] and marker == case["markers"][1] else "report"
        require(role is not None and role.get(W + "val") == expected_role, f"DOCX run font role changed: {marker}")
        color = properties.find("w:color", NS)
        require(color is not None and color.get(W + "val").upper() == f"{case['rgb']:06X}", f"DOCX run color changed: {marker}")
    levels = 0
    if numbering is not None:
        abstracts = {node.get(W + "abstractNumId"): node for node in numbering.findall("w:abstractNum", NS)}
        instances = {node.get(W + "numId"): node.find("w:abstractNumId", NS).get(W + "val") for node in numbering.findall("w:num", NS)}
        for paragraph in document.findall(".//w:p", NS):
            if not any(marker in "".join(node.text or "" for node in paragraph.findall(".//w:t", NS)) for marker in case["markers"]):
                continue
            properties = paragraph.find("w:pPr/w:numPr", NS)
            require(properties is not None, "DOCX list paragraph lost native numbering")
            instance = properties.find("w:numId", NS).get(W + "val")
            level = properties.find("w:ilvl", NS).get(W + "val")
            definition = next(node for node in abstracts[instances[instance]].findall("w:lvl", NS) if node.get(W + "ilvl") == level)
            style = definition.find("w:rPr/w:rStyle", NS)
            color = definition.find("w:rPr/w:color", NS)
            require(style is not None and style.get(W + "val") == "report", "native list marker lost its font role")
            require(color is not None and color.get(W + "val").upper() == f"{case['rgb']:06X}", "native list marker lost color")
            levels += 1
        require(levels == 2, "native bullet/number marker controls missing")
    return {"sha256": digest(path), "nativeMarkerLevelsChecked": levels, "fontRolesChecked": sorted(roles)}


def inspect(build, output):
    source = build / "output/review-fixes"
    manifest = json.loads((source / "manifest.json").read_text(encoding="utf-8"))
    require(manifest["schemaVersion"] == 1 and len(manifest["cases"]) == 7, "unexpected fixture manifest")
    output.mkdir(parents=True, exist_ok=False)
    result = {"runtime": check_runtime(build, manifest), "pdf": {}, "docx": {}}
    renders = {}
    for case in manifest["cases"]:
        name = case["name"]
        pdf = source_path(source, case["pdf"])
        result["pdf"][name] = check_pdf(pdf, case)
        result["docx"][name] = check_docx(source_path(source, case["docx"]), case)
        renders[name] = render_pdf(pdf, output / name)
        require(renders[name]["pages"] == result["pdf"][name]["pages"], "incomplete PDF rendering")
        check_markers(renders[name]["text"], case["markers"], name + "/PDFium")
    for name in ("fallback-xml", "fallback-prepared"):
        require_same_pixels(renders["fallback-markdown"], renders[name], "same formula text through XML")
    for failure in manifest["failures"]:
        require(failure["oldTargetPreserved"] and source_path(source, failure["file"]).read_bytes() == b"old-strict-target",
                "strict formula failure replaced its old target")
    require(len(manifest["failures"]) == 2, "missing strict formula targets")
    require(manifest["session"] == {"preflightFailures": 128, "sameNameRetrySucceeded": True, "successfulDuplicateRejected": True},
            "failed source preflight retained namespace state")
    require(manifest["fontCheckpoint"]["checkpointCalls"] > 0 and manifest["fontCheckpoint"]["failurePropagated"]
            and manifest["fontCheckpoint"]["faceStillUsable"], "published cooperative font source check failed")
    contact_sheet([(f"{name} / {engine} / {page}", path) for name, rendered in renders.items()
                   for engine in ("poppler", "pdfium") for page, path in enumerate(rendered[engine], 1)], output)
    result.update(manifestSha256=digest(source / "manifest.json"), renderedPagesPerEngine=sum(value["pages"] for value in renders.values()),
                  docxReaderExecuted=False, session=manifest["session"], fontCheckpoint=manifest["fontCheckpoint"],
                  visualReview="All pages dual-rendered; visual review recorded separately.")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--consumer-build-directory", type=Path, action="append", required=True)
    parser.add_argument("--output-directory", type=Path, required=True)
    arguments = parser.parse_args()
    output = arguments.output_directory.resolve()
    require(output.is_relative_to(ROOT / "build"), "evidence must remain under ignored build/")
    output.mkdir(parents=True, exist_ok=False)
    report = {"status": "running", "tools": tools(None), "consumers": []}
    try:
        for index, build in enumerate(arguments.consumer_build_directory, 1):
            report["consumers"].append(inspect(build.resolve(), output / f"consumer-{index}"))
        providers = [{entry["requiredClass"]: entry["sha256"] for entry in consumer["runtime"]["verifiedUniqueProviders"]}
                     for consumer in report["consumers"]]
        require(all(item == providers[0] for item in providers), "consumer routes used different published JARs")
        report["status"] = "pdf-and-structure-passed-docx-reader-not-executed"
    except Exception as failure:
        report.update(status="failed", failure={"type": type(failure).__name__, "message": str(failure)})
        raise
    finally:
        (output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": report["status"], "consumers": len(report["consumers"])}, ensure_ascii=False))


if __name__ == "__main__":
    main()
