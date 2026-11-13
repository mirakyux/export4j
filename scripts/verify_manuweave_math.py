# SPDX-License-Identifier: Apache-2.0
"""Independently verify vector mathematics and the native DOCX math specimen.

Run after :examples:manuWeaveMath. Poppler and the pinned verification Python
environment are required. --word-directory explicitly enables installed-Word
receipt, edited-package and PDF checks; omitting it records NOT REQUESTED.
This checks application automation evidence, never human UI acceptance.
"""
from __future__ import annotations

import argparse
from copy import deepcopy
import hashlib
from importlib import metadata
import json
import math
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from pypdf import PdfReader
from pypdf.generic import ContentStream

from verify_advanced_pdf import require
from verify_manuweave import check_renders, properties
from verify_production import actual_text

ROOT = Path(__file__).resolve().parents[1]
M = "http://schemas.openxmlformats.org/officeDocument/2006/math"
W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
OD = "urn:com.mirakyux.export4j:semantic:1"
NS = {"m": M, "w": W, "s": "http://www.w3.org/2000/svg"}
EXPECTED_TEX = (
    r"x = \frac{-b \pm \sqrt{b^2-4ac}}{2a}",
    r"x_1^2 + x_2^2 = r^2",
    r"\int_{0}^{1} x^2\,dx = \frac{1}{3}",
    r"\sum_{i=1}^{n}i = \frac{n(n+1)}{2}",
    r"A = \begin{pmatrix}a&b\\c&d\end{pmatrix}",
    r"f(x) = \begin{cases}x^2,&\quad x\geq 0\\-x,&\quad x<0\end{cases}",
    r"\sin^2 \theta + \cos^2 \theta = 1 \quad \alpha + \beta = \gamma",
    r"\lim_{n \to \infty}\frac{1}{n} = 0 \qquad \prod_{i=1}^{3}i = 6",
)


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def markers(text, context):
    found = re.findall(r"MW-MATH-(\d{2})", text)
    require(found == [f"{index:02d}" for index in range(1, 9)],
            f"{context}: ordinary formula markers are missing, duplicated or reordered")


def pdf_vectors(reader):
    """Count nested images and real curve operators, including Form XObjects."""
    images = 0
    cubics = 0
    seen = set()

    def scan(stream, resources):
        nonlocal images, cubics
        if stream is not None:
            for _, operator in ContentStream(stream, reader).operations:
                images += operator == b"INLINE IMAGE"
                cubics += operator == b"c"
        for reference in resources.get("/XObject", {}).values():
            obj = reference.get_object()
            identity = id(obj)
            if identity in seen:
                continue
            seen.add(identity)
            if obj.get("/Subtype") == "/Image":
                images += 1
            elif obj.get("/Subtype") == "/Form":
                scan(obj, obj.get("/Resources", resources))

    for page in reader.pages:
        scan(page.get_contents(), page["/Resources"])
    return images, cubics


def check_vector_example(source, output):
    report = properties(source / "math-report.xml")
    require(report.get("formula.count") == "8", "math specimen must contain eight formulas")
    reader = PdfReader(source / "math.pdf")
    require(len(reader.pages) == int(report["effective.pages"]) == 1,
            "the vector mathematics specimen must occupy one page")
    logical = actual_text(reader)
    markers("\n".join(page.extract_text() or "" for page in reader.pages), "vector PDF")
    image_count, cubic_count = pdf_vectors(reader)
    require(image_count == 0 and cubic_count > 0, "math PDF must contain vector curves and no images")
    svg = ET.parse(source / "math.svg").getroot()
    require(svg.tag == "{" + NS["s"] + "}svg", "not a standard SVG document")
    require(not svg.findall(".//s:image", NS), "mathematical SVG unexpectedly contains a raster image")
    path_total = 0
    for index, tex in enumerate(EXPECTED_TEX, 1):
        prefix = f"formula.{index}."
        require(report[prefix + "tex"] == tex, f"formula {index}: original TeX specimen differs")
        require(logical.count(tex) == 1, f"formula {index}: PDF ActualText must preserve TeX exactly once")
        matches = [node for node in svg.findall(".//s:g", NS) if node.get("aria-label") == tex]
        require(len(matches) == 1, f"formula {index}: unique SVG original-TeX aria-label missing")
        paths = matches[0].findall("s:path", NS)
        require(len(paths) == int(report[prefix + "paths"]) > 0,
                f"formula {index}: SVG vector path count differs")
        require(all(node.get("d", "").strip() for node in paths), "empty formula vector outline")
        width, height, baseline = (float(report[prefix + name]) for name in ("width", "height", "baseline"))
        require(all(math.isfinite(value) for value in (width, height, baseline))
                and width > 0 and 0 < baseline < height, f"formula {index}: invalid natural pt dimensions")
        path_total += len(paths)
    return {"pages": len(reader.pages), "formulas": 8, "vectorPaths": path_total,
            "pdfCubicOperators": cubic_count, "imageObjects": image_count,
            "originalTexActualTextAndAriaLabels": True, "ordinaryMarkersUniqueAndOrdered": True,
            "crossRendererDifferences": check_renders(source / "math.pdf", output / "vector", len(reader.pages))}


def snapshot(node):
    require(node is not None, "missing expected mathematical XML element")
    return (node.tag, tuple(sorted((key, value) for key, value in node.attrib.items()
                                  if not key.startswith("{" + OD + "}"))),
            (node.text or "") if node.tag == "{" + M + "}t" else "",
            tuple(snapshot(child) for child in node))


def native_package(path, count=8, standard=False, ordinary_markers=True):
    with ZipFile(path) as archive:
        require(not any(name.startswith("word/media/") for name in archive.namelist()),
                f"{path.name}: native equations must not be image parts")
        if standard:
            for name in archive.namelist():
                if not (name.endswith(".xml") or name.endswith(".rels")):
                    continue
                data = archive.read(name)
                require(OD.encode() not in data, f"{path.name}/{name}: project namespace remains")
                root = ET.fromstring(data)
                require(all(not node.tag.startswith("{" + OD + "}")
                            and not any(key.startswith("{" + OD + "}") for key in node.attrib)
                            for node in root.iter()), f"{path.name}: own metadata remains")
        document = ET.fromstring(archive.read("word/document.xml"))
        settings = ET.fromstring(archive.read("word/settings.xml"))
    require(len(document.findall(".//m:oMath", NS)) == count
            and len(document.findall(".//m:oMathPara", NS)) == count,
            f"{path.name}: expected {count} standard native equations and display paragraphs")
    require(not document.findall(".//w:drawing", NS), f"{path.name}: native mathematics became drawings")
    require("\ufffc" not in "".join(document.itertext()), f"{path.name}: unencoded equation placeholder remains")
    if ordinary_markers:
        markers("".join(node.text or "" for node in document.findall(".//w:t", NS)), path.name)
    return document, settings


def check_native_example(source):
    original, settings = native_package(source / "math.docx")
    expected = [snapshot(node) for node in original.findall(".//m:oMath", NS)]
    expected_settings = snapshot(settings.find("m:mathPr", NS))
    for name, count in (("m", 2), ("rad", 1), ("nary", 3), ("sSubSup", 2), ("func", 2), ("limLow", 1)):
        require(len(original.findall(f".//m:{name}", NS)) == count, f"missing native {name} objects")
    require([node.get("{" + M + "}val") for node in original.findall(".//m:naryPr/m:chr", NS)]
            == ["∫", "∑", "∏"], "native integral, sum and product operators differ")
    require(len(original.findall(".//m:f", NS)) >= 3, "native fractions missing")
    for round_number in range(1, 4):
        current, current_settings = native_package(source / f"math-standard-round-{round_number}.docx", standard=True)
        require([snapshot(node) for node in current.findall(".//m:oMath", NS)] == expected,
                f"standard round {round_number}: native expression encoding changed")
        require(snapshot(current_settings.find("m:mathPr", NS)) == expected_settings,
                f"standard round {round_number}: typed math settings changed")
    edited, edited_settings = native_package(source / "math-edited.docx")
    changed = [snapshot(node) for node in edited.findall(".//m:oMath", NS)]
    require(changed[1:] == expected[1:], "the native edit changed another equation")
    first = deepcopy(original.find(".//m:oMath", NS))
    denominator = first.find(".//m:f/m:den", NS)
    require(denominator is not None and "".join(denominator.itertext()) == "2a", "original denominator differs")
    denominator.find("m:r/m:t", NS).text = "3a"
    require(changed[0] == snapshot(first), "native edit must change only the first denominator from 2a to 3a")
    require(snapshot(edited_settings.find("m:mathPr", NS)) == expected_settings, "formula edit changed math settings")
    return {"nativeFormulas": 8, "nativeMatrices": 2, "imageParts": 0,
            "standardRoundsWithoutProjectMetadata": 3, "allNativeEncodingsAndSettingsPreserved": True,
            "editedDenominator": ["2a", "3a"], "onlyFirstDenominatorChanged": True}


def check_word(directory, source, output):
    receipt = json.loads((directory / "word-math-acceptance.json").read_text(encoding="utf-8-sig"))
    for key in ("passed", "actualNativeEquationEdit", "otherEquationsUnchanged", "savedAndReopened", "pdfExported"):
        require(receipt.get(key) is True, f"Word automation receipt did not verify {key}")
    require(receipt.get("acceptance") == "Installed Microsoft Word application automation"
            and receipt.get("humanUiAcceptance") is False, "Word evidence is not explicit application automation")
    require(receipt.get("beforeNativeEquations") == receipt.get("afterNativeEquations") == 8,
            "Word must retain eight real native equations")
    require(receipt.get("denominatorBefore") == "2a" and receipt.get("denominatorAfter") == "5a",
            "Word receipt does not confirm the actual denominator edit")
    require(receipt.get("wordVersion") and receipt.get("wordBuild"), "actual Word version/build missing")
    require(receipt.get("inputSha256") == sha256(source / "math.docx"), "Word receipt belongs to another example")
    require(receipt.get("savedSha256") == sha256(directory / "word-saved.docx")
            and receipt.get("pdfSha256") == sha256(directory / "word-rendered.pdf"), "Word receipt artifact hashes differ")
    document, _ = native_package(directory / "word-saved.docx")
    require("".join(document.find(".//m:f/m:den", NS).itertext()) == "5a", "Word-saved standard OMML edit missing")
    readback = properties(source / "word-readback-report.xml")
    require(readback.get("word.sourceSha256") == sha256(directory / "word-saved.docx"),
            "Java math readback report is not bound to this Word-saved file")
    for key in ("word.mathAstPreserved", "word.mathSettingsPreserved", "word.strictReencodedReadback"):
        require(readback.get(key) == "true", f"Word-saved Java readback failed {key}")
    require(readback.get("word.readPolicy") == "ALLOW_LOSS" and readback.get("word.nativeEquations") == "8"
            and readback.get("word.firstDenominator") == "5a", "Word-saved typed readback details differ")
    require(int(readback.get("word.diagnostics.count", "-1")) >= 0, "ordinary Word loss diagnostics missing")
    reencoded, _ = native_package(source / "word-readback.docx")
    require("".join(reencoded.find(".//m:f/m:den", NS).itertext()) == "5a", "strict reencoded formula edit missing")
    reader = PdfReader(directory / "word-rendered.pdf")
    require(len(reader.pages) == receipt.get("pages") > 0, "Word PDF page count differs from application receipt")
    markers("\n".join(page.extract_text() or "" for page in reader.pages), "Word-exported PDF")
    return {"status": "verified", "humanUiAcceptance": False, "wordVersion": receipt["wordVersion"],
            "wordBuild": receipt["wordBuild"], "nativeFormulasBeforeAndAfter": 8, "editedDenominator": ["2a", "5a"],
            "sourceAndArtifactHashesBound": True, "typedJavaReadbackAndStrictReencoding": True,
            "receiptSha256": sha256(directory / "word-math-acceptance.json"),
            "inputSha256": receipt["inputSha256"], "savedSha256": receipt["savedSha256"], "pdfSha256": receipt["pdfSha256"],
            "readbackReportSha256": sha256(source / "word-readback-report.xml"),
            "readbackDocxSha256": sha256(source / "word-readback.docx"),
            "ordinaryWordImportDiagnostics": int(readback["word.diagnostics.count"]), "pages": len(reader.pages),
            "crossRendererDifferences": check_renders(directory / "word-rendered.pdf", output / "word", len(reader.pages))}


def check_consumer(directory):
    output = directory / "output"
    report = properties(output / "consumer-native-math-report.xml")
    require(report.get("formulas") == "2", "isolated public API consumer formula count differs")
    for key in ("strictRoundtrip", "actualEquationEdit", "otherEquationUnchanged", "sourceUnchanged", "portableReadersRejectMath"):
        require(report.get(key) == "true", f"isolated public API consumer failed {key}")
    require(report.get("originalSha256") == sha256(output / "consumer-native-math.docx")
            and report.get("editedSha256") == sha256(output / "consumer-native-math-edited.docx"),
            "isolated consumer report does not match its artifacts")
    original, _ = native_package(output / "consumer-native-math.docx", 2, ordinary_markers=False)
    edited, _ = native_package(output / "consumer-native-math-edited.docx", 2, ordinary_markers=False)
    require(len(original.findall(".//m:m", NS)) == 1 and len(original.findall(".//m:f", NS)) == 1,
            "isolated consumer must contain a native fraction and matrix")
    original_equations = original.findall(".//m:oMath", NS); edited_equations = edited.findall(".//m:oMath", NS)
    require(snapshot(original_equations[1]) == snapshot(edited_equations[1]), "isolated consumer matrix changed")
    first = deepcopy(original_equations[0]); denominator = first.find("m:f/m:den/m:r/m:t", NS)
    require(denominator is not None and denominator.text == "2a", "isolated original denominator differs")
    denominator.text = "3a"
    require(snapshot(first) == snapshot(edited_equations[0]), "isolated edit changed more than the denominator")
    libraries = (directory / "resolved-libraries.txt").read_text(encoding="utf-8")
    require("com.mirakyux.export4j:export4j-adapter-docx:" in libraries, "isolated consumer did not resolve the published DOCX adapter")
    require(":examples:" not in libraries.lower(), "isolated native mathematics consumer must not depend on examples")
    # The complete consumer now selects the separate optional mathematics module too.
    # Check the actual resolved native adapter rather than the aggregate classpath.
    resolved = [row.split("\t") for row in (directory / "resolved-library-files.txt").read_text(encoding="utf-8").splitlines()]
    adapter_jars = [Path(filename) for coordinate, filename in resolved
                    if coordinate.startswith("com.mirakyux.export4j:export4j-adapter-docx:")]
    require(len(adapter_jars) == 1, "native adapter runtime JAR is missing or ambiguous")
    with ZipFile(adapter_jars[0]) as adapter:
        require(all(b"org/scilab/forge/jlatexmath" not in adapter.read(name)
                    for name in adapter.namelist() if name.endswith(".class")),
                "native DOCX mathematical bytecode acquired a JLaTeXMath reference")
    return {"status": "verified", "nativeFormulas": 2, "fractionAndMatrix": True,
            "strictPublicApiRoundtripAndEdit": True, "sourceAndArtifactHashesBound": True,
            "reportSha256": sha256(output / "consumer-native-math-report.xml"),
            "originalSha256": report["originalSha256"], "editedSha256": report["editedSha256"],
            "resolvedLibrariesSha256": sha256(directory / "resolved-libraries.txt"),
            "exampleDependencyAbsent": True, "nativeAdapterJlatexmathReferencesAbsent": True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--example-directory", type=Path, default=ROOT / "examples/build/manuweave-math")
    parser.add_argument("--output-directory", type=Path, default=ROOT / "build/verification/manuweave-math")
    parser.add_argument("--word-directory", type=Path, help="explicit installed-Word automation output to verify")
    parser.add_argument("--consumer-build-directory", type=Path, help="fresh isolated published-consumer build directory")
    args = parser.parse_args(); args.output_directory.mkdir(parents=True, exist_ok=True)
    vector = check_vector_example(args.example_directory, args.output_directory)
    native = check_native_example(args.example_directory)
    word = {"status": "not_requested", "verified": False, "humanUiAcceptance": False}
    if args.word_directory is not None:
        word = check_word(args.word_directory, args.example_directory, args.output_directory)
    consumer = {"status": "not_requested", "verified": False}
    if args.consumer_build_directory is not None:
        consumer = check_consumer(args.consumer_build_directory)
    poppler = subprocess.run(["pdftoppm", "-v"], check=True, capture_output=True, text=True, timeout=15)
    result = {"vectorExample": vector, "nativeDocxExample": native, "installedWord": word, "publishedConsumer": consumer,
              "renderProtocol": {"dpi": 192, "blurPixels": 2, "meanLimit": 4, "changedFractionLimit": .06},
              "tools": {name: metadata.version(name) for name in ("pypdf", "pypdfium2", "Pillow")},
              "poppler": (poppler.stdout + poppler.stderr).strip().splitlines()[0]}
    (args.output_directory / "report.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
