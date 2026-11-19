# SPDX-License-Identifier: Apache-2.0
"""Inspect published-consumer architecture fixtures; render every PDF with Poppler and PDFium.

Run with the project QA Python after a successful architectureConvergence consumer task.
This verifier neither builds artifacts nor updates baselines. LibreOffice rendering is optional;
its absence is recorded separately from the standard OOXML and PDF checks.
"""
from __future__ import annotations

import argparse
from importlib import metadata
import json
from pathlib import Path
import re
import subprocess
import sys
from zipfile import ZipFile
import xml.etree.ElementTree as ET

import pdfplumber
from pypdf import PdfReader
import pypdfium2 as pdfium

from verify_easy_export import compact, contact_sheet, digest, render_pdf, require_same_pixels

ROOT = Path(__file__).resolve().parents[1]
NS = {"w": "http://schemas.openxmlformats.org/wordprocessingml/2006/main"}
W = "{" + NS["w"] + "}"
POINT_TOLERANCE = .001  # PDF coordinates are serialized at five decimal places.


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def source_path(source, relative):
    require(isinstance(relative, str) and not Path(relative).is_absolute(), "fixture path must be relative")
    path = (source / relative).resolve()
    require(path.is_relative_to(source) and path.is_file(), f"missing or escaping fixture: {relative}")
    return path


def tools(office):
    versions = {name: metadata.version(name) for name in ("pypdf", "pdfplumber", "pypdfium2", "Pillow")}
    require(versions == {"pypdf": "6.10.0", "pdfplumber": "0.11.8", "pypdfium2": "5.13.0", "Pillow": "12.3.0"},
            "use the pinned project verification environment")
    versions.update(python=sys.version.split()[0], pdfium=pdfium.PDFIUM_INFO.version)
    result = subprocess.run(["pdftoppm", "-v"], capture_output=True, text=True, timeout=30, check=True)
    match = re.search(r"pdftoppm version (\S+)", result.stdout + result.stderr)
    require(match is not None, "cannot identify Poppler")
    versions["poppler"] = match.group(1)
    if office:
        require(office.is_file(), "explicit DOCX reader executable is missing")
        result = subprocess.run([str(office), "--version"], capture_output=True, text=True, timeout=30, check=True)
        versions["libreoffice"] = (result.stdout + result.stderr).strip()
    return versions


def check_runtime(build, manifest):
    expected = {}
    for line in (build / "resolved-library-sha256.txt").read_text(encoding="utf-8-sig").splitlines():
        coordinate, filename, sha = line.split("\t")
        expected[coordinate, filename] = sha
    artifacts = []
    for line in (build / "resolved-library-files.txt").read_text(encoding="utf-8-sig").splitlines():
        coordinate, filename = line.split("\t", 1)
        path = Path(filename)
        require(path.is_file() and path.suffix == ".jar", "consumer runtime includes a missing/non-JAR library")
        sha = digest(path)
        require(sha == expected[coordinate, path.name], "consumer runtime JAR hash changed")
        artifacts.append((path, sha))
    providers = []
    for entry in manifest["runtimeLibraries"]:
        resource = entry["requiredClass"].replace(".", "/") + ".class"
        owners = []
        for path, sha in artifacts:
            with ZipFile(path) as jar:
                if resource in jar.namelist():
                    owners.append((path.name, sha))
        require(owners == [(entry["jar"], entry["sha256"])], f"published runtime provider differs: {resource}")
        providers.append(entry)
    require(len(providers) >= 8, "consumer omitted required published API providers")
    return {"runtimeJarCount": len(artifacts), "verifiedUniqueProviders": providers}


def check_markers(text, markers, label):
    joined = compact(text)
    for marker in markers:
        require(joined.count(compact(marker)) == 1, f"source marker missing or duplicated: {label}/{marker}")


def selected_characters(characters, token):
    text = "".join(character["text"] for character in characters)
    require(text.count(token) == 1, f"physical marker missing or duplicated: {token}")
    start = text.index(token)
    selected, offset = [], 0
    for character in characters:
        end = offset + len(character["text"])
        if offset >= start and end <= start + len(token):
            selected.append(character)
        offset = end
    require("".join(character["text"] for character in selected) == token, f"physical marker split: {token}")
    return selected


def check_pdf(path, markers):
    reader = PdfReader(path, strict=True)
    require(len(reader.pages) > 0, "empty PDF")
    check_markers("\n".join(page.extract_text() for page in reader.pages), markers, path.name)
    fonts = set()
    for page in reader.pages:
        for reference in page["/Resources"].get("/Font", {}).values():
            font = reference.get_object()
            fonts.add(str(font.get("/BaseFont", "")))
            descendants = font.get("/DescendantFonts")
            physical = descendants[0].get_object() if descendants else font
            descriptor = physical.get("/FontDescriptor")
            require(descriptor is not None, f"font lacks an embedding descriptor: {path.name}")
            descriptor = descriptor.get_object()
            require(descriptor.get("/FontFile2") or descriptor.get("/FontFile3"), f"font program absent: {path.name}")
    with pdfplumber.open(path) as pdf:
        require(len(pdf.pages) == len(reader.pages), "independent PDF parsers disagree about page count")
        characters = [dict(character, page=index + 1) for index, page in enumerate(pdf.pages) for character in page.chars]
        for page in pdf.pages:
            for character in page.chars:
                require(-.1 <= character["x0"] <= character["x1"] <= page.width + .1
                        and -.1 <= character["top"] <= character["bottom"] <= page.height + .1,
                        f"text extends outside page: {path.name}")
        for marker in markers:
            selected_characters(characters, marker)
    return {"sha256": digest(path), "pages": len(reader.pages), "embeddedFonts": sorted(fonts),
            "markers": markers, "characters": len(characters)}


def check_pdf_grid(path, points):
    with pdfplumber.open(path) as pdf:
        require(len(pdf.pages) == 1, "fraction fixture must remain a complete one-page table")
        page = pdf.pages[0]
        require(abs(page.width - 360) <= POINT_TOLERANCE and abs(page.height - 260) <= POINT_TOLERANCE,
                "fraction fixture page dimensions differ from its explicit source")
        # Cell borders are stroked rectangles inset by half their line width. Recover their
        # painted outer boundaries; header fill edges and stroke centerlines are different data.
        rectangles = [rectangle for rectangle in page.rects if rectangle["stroke"]]
        require(len(rectangles) == 2 * len(points), "fraction fixture lost a header/body cell border")
        bounds = [(rectangle["x0"] - rectangle["linewidth"] / 2,
                   rectangle["x1"] + rectangle["linewidth"] / 2) for rectangle in rectangles]
        edges = sorted({round(value, 5) for pair in bounds for value in pair})
    expected, cursor = [24.0], 24.0  # Explicit fixture page margin, independent of the layout implementation.
    for width in points:
        require(width > 0, "nonpositive expected column width")
        cursor += width
        expected.append(cursor)
    require(len(edges) == len(expected) and all(abs(left - right) <= POINT_TOLERANCE for left, right in zip(edges, expected)),
            f"PDF physical table grid differs: {path.name}: {edges} != {expected}")
    for left, right in bounds:
        require(any(abs(left - expected[index]) <= POINT_TOLERANCE
                    and abs(right - expected[index + 1]) <= POINT_TOLERANCE for index in range(len(points))),
                f"PDF header/body cell extent differs: {path.name}: {(left, right)}")
    return {"verticalGridPoints": edges, "expectedGridPoints": expected}


def check_pdf_roles(path, roles, family):
    with pdfplumber.open(path) as pdf:
        characters = [dict(character, page=index + 1) for index, page in enumerate(pdf.pages) for character in page.chars]
    result = {}
    for marker, role in roles.items():
        variant = role.removeprefix("report").removeprefix("_")
        suffix = {"": "", "bold": "-Bold", "italic": "-Italic", "boldItalic": "-BoldItalic"}[variant]
        expected = family.replace(" ", "") + suffix
        selected = selected_characters(characters, marker)
        names = {re.sub(r"^[A-Z]{6}\+", "", character["fontname"].lstrip("/")) for character in selected}
        require(names == {expected}, f"physical font role changed: {path.name}/{marker}: {names} != {expected}")
        result[marker] = {"fontRole": role, "physicalFont": expected, "page": selected[0]["page"],
                          "box": [round(min(char["x0"] for char in selected), 3), round(min(char["top"] for char in selected), 3),
                                  round(max(char["x1"] for char in selected), 3), round(max(char["bottom"] for char in selected), 3)]}
    return result


def docx_parts(path):
    with ZipFile(path) as package:
        names = package.namelist()
        require(len(names) == len(set(names)) and package.testzip() is None, "invalid DOCX package")
        main = ET.fromstring(package.read("word/document.xml"))
        styles = ET.fromstring(package.read("word/styles.xml"))
    return main, styles


def check_docx(path, markers, points=None, roles=None, family=None):
    main, styles = docx_parts(path)
    text = "".join(node.text or "" for node in main.findall(".//w:t", NS))
    check_markers(text, markers, path.name)
    result = {"sha256": digest(path), "markers": markers}
    if points is not None:
        tables = main.findall(".//w:tbl", NS)
        require(len(tables) == 1, "fraction table count changed")
        table = tables[0]
        grid = [int(node.get(W + "w")) for node in table.findall("w:tblGrid/w:gridCol", NS)]
        expected = [round(value * 20) for value in points]
        require(grid == expected and all(0 < value < 2**63 for value in grid), f"standard DOCX grid changed: {grid} != {expected}")
        require(int(table.find("w:tblPr/w:tblW", NS).get(W + "w")) == sum(grid), "DOCX table width differs from its physical grid")
        for row in table.findall("w:tr", NS):
            widths = row.findall("w:tc/w:tcPr/w:tcW", NS)
            require([int(width.get(W + "w")) for width in widths] == grid, "DOCX cell widths differ from grid")
            require(all(width.get(W + "type") == "dxa" for width in widths), "DOCX cell width units changed")
        result["gridTwips"] = grid
    if roles:
        declared = {node.get(W + "styleId"): node for node in styles.findall("w:style", NS)}
        checks = {}
        for marker, expected_role in roles.items():
            runs = [node for node in main.findall(".//w:r", NS) if marker in "".join(node.itertext())]
            require(len(runs) == 1, f"native Word marker missing/duplicated: {marker}")
            style = runs[0].find("w:rPr/w:rStyle", NS)
            require(style is not None and style.get(W + "val") == expected_role, f"native Word font role changed: {marker}")
            properties = declared[expected_role].find("w:rPr", NS)
            fonts = properties.find("w:rFonts", NS)
            require(fonts is not None and all(fonts.get(W + slot) == family for slot in ("ascii", "hAnsi", "eastAsia", "cs")),
                    f"native Word family changed: {marker}")
            bold, italic = properties.find("w:b", NS), properties.find("w:i", NS)
            enabled = lambda node: node is not None and node.get(W + "val", "true") not in ("0", "false", "off")
            require(enabled(bold) == (expected_role.endswith("_bold") or expected_role.endswith("_boldItalic"))
                    and enabled(italic) == (expected_role.endswith("_italic") or expected_role.endswith("_boldItalic")),
                    f"native Word variant changed: {marker}")
            checks[marker] = {"fontRole": expected_role, "fontFamily": family, "bold": enabled(bold), "italic": enabled(italic)}
        result["fontRoles"] = checks
    return result


def convert_docx(path, target, office):
    target.mkdir(parents=True, exist_ok=False)
    command = [str(office), "-env:UserInstallation=" + (target / "profile").as_uri(), "--headless", "--invisible",
               "--norestore", "--convert-to", "pdf:writer_pdf_Export", "--outdir", str(target), str(path)]
    result = subprocess.run(command, capture_output=True, text=True, timeout=180)
    (target / "conversion.log").write_text(result.stdout + "\n" + result.stderr, encoding="utf-8")
    pdf = target / (path.stem + ".pdf")
    require(result.returncode == 0 and pdf.is_file(), f"independent DOCX reader failed: {path.name}")
    return pdf


def inspect(build, output, office):
    source = build / "output/architecture-convergence"
    require((source / "manifest.json").is_file(), "successful architectureConvergence consumer output is required")
    manifest = json.loads((source / "manifest.json").read_text(encoding="utf-8"))
    require(manifest["schemaVersion"] == 1, "unknown architecture consumer manifest")
    output.mkdir(parents=True, exist_ok=False)
    result = {"consumerBuild": str(build), "manifestSha256": digest(source / "manifest.json"),
              "runtime": check_runtime(build, manifest), "pdf": {}, "docx": {}, "readers": {}}
    pdf_specs, docx_specs, comparisons = {}, {}, []
    require({item["shape"] for item in manifest["fractionPairs"]} == {"single", "double", "mixed"}, "missing fraction cases")
    for group in manifest["fractionPairs"]:
        require({case["scale"] for case in group["sources"]} == {"1", "1e308"}, "missing fraction scale control")
        for case in group["sources"]:
            pdf_specs[case["pdf"]] = (group["markers"], group["expectedGridPoints"], None, None)
            docx_specs[case["docx"]] = pdf_specs[case["pdf"]]
        comparisons.append((group["sources"][0]["pdf"], group["sources"][1]["pdf"], "equivalent fraction weights"))
    flow = manifest["flowPair"]
    for name in (flow["full"], flow["paged"]):
        pdf_specs[name] = (flow["markers"], None, None, None)
    comparisons.append((flow["full"], flow["paged"], "full and paged same semantic source"))
    require({case["format"] for case in manifest["roleCases"]} == {"markdown", "html", "xml", "prepared-syntax"}, "missing role entry points")
    for case in manifest["roleCases"]:
        spec = (case["markers"], None, case["expectedFontRoles"], case["expectedFontFamily"])
        pdf_specs[case["pdf"]] = spec
        docx_specs[case["docx"]] = spec
    pdf_specs["cache-reuse.pdf"] = (["BORROWED_CACHE_REUSE"], None, None, None)
    comparisons.extend(("roles/markdown.pdf", name, "same Markdown source through XML/prepared syntax")
                       for name in ("roles/xml.pdf", "roles/prepared-syntax.pdf"))
    require({path.relative_to(source).as_posix() for path in source.rglob("*.pdf")} == set(pdf_specs), "unlisted/missing fixture PDF")
    renders, reader_renders = {}, {}
    for name, (markers, points, roles, family) in pdf_specs.items():
        path = source_path(source, name)
        checked = check_pdf(path, markers)
        if points is not None:
            checked.update(check_pdf_grid(path, points))
        if roles:
            checked["fontRoles"] = check_pdf_roles(path, roles, family)
        result["pdf"][name] = checked
        rendered = render_pdf(path, output / "pdf" / name.removesuffix(".pdf"))
        require(rendered["pages"] == checked["pages"], "incomplete dual PDF rendering")
        check_markers(rendered["text"], markers, name + "/PDFium")
        renders[name] = rendered
    require(result["pdf"][flow["full"]]["pages"] == flow["pages"] > 1, "same-source flow fixture lost pagination")
    for left, right, label in comparisons:
        require_same_pixels(renders[left], renders[right], label)
    for name, (markers, points, roles, family) in docx_specs.items():
        path = source_path(source, name)
        result["docx"][name] = check_docx(path, markers, points, roles, family)
        if office is None:
            result["readers"][name] = {"status": "skipped", "reason": "--soffice was not supplied"}
            continue
        result["readers"][name] = {"status": "running"}
        try:
            opened = convert_docx(path, output / "docx" / name.removesuffix(".docx"), office)
            rendered = render_pdf(opened, opened.parent / "render")
            check_markers(rendered["text"], markers, name + "/LibreOffice")
            reader_renders[name] = rendered
            result["readers"][name] = {"status": "opened", "pages": rendered["pages"], "pdfSha256": digest(opened)}
        except Exception as failure:
            result["readers"][name] = {"status": "failed", "type": type(failure).__name__, "message": str(failure)}
            (output / "partial-report.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
            raise
    if office:
        for group in manifest["fractionPairs"]:
            require_same_pixels(reader_renders[group["sources"][0]["docx"]], reader_renders[group["sources"][1]["docx"]], "equivalent DOCX fraction weights")
        require_same_pixels(reader_renders["roles/xml.docx"], reader_renders["roles/prepared-syntax.docx"], "prepared XML DOCX")
    backend = manifest["backend"]
    require(backend["storage"] == "TEMP_FILE" and backend["afterExporterClose"]["closed"] is False
            and backend["afterExporterClose"]["borrowedEntries"] == 0
            and backend["afterExporterClose"]["parses"] >= backend["beforeStarter"]["parses"] + 4
            and backend["afterReuse"]["hits"] > backend["afterExporterClose"]["hits"]
            and backend["afterOwnerClose"]["closed"] is True and backend["afterOwnerClose"]["borrowedEntries"] == 0
            and all(snapshot["cleanupFailures"] == 0 for key, snapshot in backend.items() if key != "storage"),
            "borrowed backend/cache lifecycle proof failed")
    for failure in manifest["failures"]:
        expected = b"old-backend-target" if failure["target"] == "backend" else b"old-target-must-survive"
        require(failure["oldTargetPreserved"] is True and source_path(source, failure["file"]).read_bytes() == expected,
                "budget failure changed its old destination")
        require(failure["expectedCode"] == ("BUDGET_EXCEEDED" if failure["target"] == "backend" else "OUTPUT_LIMIT"), "budget error class changed")
    require({failure["target"] for failure in manifest["failures"]} == {"backend", "pdf", "docx"}, "budget controls missing")
    require(not list(source.rglob(".export4j-*.tmp")), "failed atomic exports left temporary destinations")
    for name in manifest["sourceFiles"]:
        source_path(source, name)
    sheets = [(f"{name} / {engine} / {index}", image) for name, rendered in {**renders, **reader_renders}.items()
              for engine in ("poppler", "pdfium") for index, image in enumerate(rendered[engine], 1)]
    contact_sheet(sheets, output)
    (output / "index.html").write_text("<!doctype html><meta charset='utf-8'><title>Architecture convergence</title>"
            + "<style>body{font:16px sans-serif;margin:24px}img{max-width:100%;display:block;margin-bottom:24px}</style>"
            + "<h1>Architecture convergence: independent render sheets</h1>"
            + "".join(f"<a href='{path.name}'><img src='{path.name}' alt='{path.name}'></a>" for path in sorted(output.glob("contact-*.png"))), encoding="utf-8")
    result.update(status="passed", pdfSameEngineComparisons=len(comparisons), backend=backend, failures=manifest["failures"],
                  renderedPagesPerEngine=sum(rendered["pages"] for rendered in [*renders.values(), *reader_renders.values()]),
                  docxReaderExecuted=office is not None,
                  visualReview="Every new PDF page has dual renders and contact sheets; record visual inspection separately.")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--consumer-build-directory", type=Path, action="append", required=True)
    parser.add_argument("--output-directory", type=Path, required=True)
    parser.add_argument("--soffice", type=Path, help="Explicit optional independent DOCX reader")
    args = parser.parse_args()
    output = args.output_directory.resolve()
    require(output.is_relative_to(ROOT / "build"), "verification output must remain under ignored build/")
    output.mkdir(parents=True, exist_ok=False)
    report = {"status": "running", "consumers": []}
    try:
        office = args.soffice.resolve() if args.soffice else None
        report["tools"] = tools(office)
        for index, build in enumerate(args.consumer_build_directory, 1):
            report["consumers"].append(inspect(build.resolve(), output / f"consumer-{index}", office))
        providers = [{entry["requiredClass"]: entry["sha256"] for entry in consumer["runtime"]["verifiedUniqueProviders"]}
                     for consumer in report["consumers"]]
        require(all(item == providers[0] for item in providers), "consumer resolution modes loaded different runtime providers")
        report.update(status="passed" if office else "pdf-and-structure-passed-docx-reader-skipped",
                      runtimeProviderHashesMatch=True,
                      boundary="No Word editing/save check or visual acceptance is inferred from generated render sheets.")
    except Exception as failure:
        report.update(status="failed", failure={"type": type(failure).__name__, "message": str(failure)})
        raise
    finally:
        (output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": report["status"], "consumers": len(report["consumers"]), "output": str(output)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
