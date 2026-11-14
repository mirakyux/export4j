# SPDX-License-Identifier: Apache-2.0
"""Inspect configured-once exports and independently render every generated PDF/DOCX page."""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
from importlib import metadata
import json
from pathlib import Path
import subprocess
from zipfile import ZipFile
import xml.etree.ElementTree as ET

from PIL import Image, ImageChops
from pypdf import PdfReader
from pypdf.generic import ContentStream
import pypdfium2 as pdfium

from verify_production import actual_text, contact_sheet

ROOT = Path(__file__).resolve().parents[1]
NS = {"w": "http://schemas.openxmlformats.org/wordprocessingml/2006/main",
      "m": "http://schemas.openxmlformats.org/officeDocument/2006/math"}
OBJECT_CASES = ("xml-record", "xml-bean")
RICH_CASES = {"markdown", "xml", *OBJECT_CASES}
CASES = {"markdown": (5, 1), "xml": (5, 1), **{case: (5, 1) for case in OBJECT_CASES},
         "html": (1, 1), "text": (0, 0), "visual-only": (1, 0)}
FORMULAS = (r"x + \text{年}", "x_1^2", r"\frac{a+b}{c}", r"\sqrt{x}", "x^2 + y^2 = z^2")


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def compact(text):
    return "".join(text.split())


def render_pdf(path, output):
    output.mkdir(parents=True, exist_ok=False)
    subprocess.run(["pdftoppm", "-png", "-r", "144", str(path), str(output / "poppler")],
                   check=True, capture_output=True, timeout=120)
    poppler = sorted(output.glob("poppler-*.png"))
    pdfium_images, text = [], []
    with pdfium.PdfDocument(path) as document:
        for index in range(len(document)):
            page = document[index]
            try:
                textpage = page.get_textpage()
                try:
                    text.append(textpage.get_text_range())
                    width, height = page.get_size()
                    for offset in range(textpage.count_chars()):
                        if textpage.get_text_range(offset, 1).isspace():
                            continue
                        left, bottom, right, top = textpage.get_charbox(offset)
                        require(-.1 <= left <= right <= width + .1 and -.1 <= bottom <= top <= height + .1,
                                "selectable text extends beyond a page")
                finally:
                    textpage.close()
                bitmap = page.render(scale=2)
                try:
                    destination = output / f"pdfium-{index + 1}.png"
                    with bitmap.to_pil() as image:
                        image.convert("RGB").save(destination)
                    pdfium_images.append(destination)
                finally:
                    bitmap.close()
            finally:
                page.close()
    require(len(poppler) == len(pdfium_images) > 0, "independent full-page render counts differ")
    return {"pages": len(poppler), "poppler": poppler, "pdfium": pdfium_images, "text": "\n".join(text)}


def check_pdf(path, case):
    expected_formulas, expected_images = CASES[case]
    reader = PdfReader(path, strict=True)
    fonts, images, operations = set(), 0, Counter()
    text = "\n".join(page.extract_text() for page in reader.pages)
    logical = "\n".join(actual_text(reader))
    for page in reader.pages:
        for reference in page["/Resources"].get("/Font", {}).values():
            font = reference.get_object()
            fonts.add(str(font.get("/BaseFont", "")))
            descendants = font.get("/DescendantFonts")
            physical = descendants[0].get_object() if descendants else font
            descriptor = physical.get("/FontDescriptor")
            require(descriptor is not None, "PDF font is not explicitly embedded")
            descriptor = descriptor.get_object()
            require(descriptor.get("/FontFile2") or descriptor.get("/FontFile3"), "PDF font program is absent")
        for reference in page["/Resources"].get("/XObject", {}).values():
            images += int(reference.get_object().get("/Subtype") == "/Image")
        operations.update(operator.decode("ascii") for _, operator in ContentStream(page.get_contents(), reader).operations)
    require(images == expected_images, f"image count changed for {case}: {images}")
    if case in RICH_CASES:
        for item in ("EASY-EXPORT / configured once", "中英文正文", "中文单元格", "bold", "italic", "both"):
            require(compact(item) in compact(text + logical), f"PDF source text missing for {case}: {item}")
        require(all(compact(tex) in compact(logical) for tex in FORMULAS), "formula ActualText lost source occurrences")
        for variant in ("LiberationSans", "LiberationSans-Bold", "LiberationSans-Italic", "LiberationSans-BoldItalic", "LXGW"):
            require(any(variant in name for name in fonts), f"declared physical font variant absent: {variant}")
    elif case == "html":
        require("HTML-ENTRY" in text + logical and "中文正文" in text + logical and "x^2" in logical,
                "HTML content/formula missing from physical text or ActualText")
    elif case == "text":
        require("**literal**" in text and "<b>HTML</b>" in text and "$x^2$" in text,
                "literal source was parsed despite the text entry point")
        require(not logical, "literal source produced formula anchors")
    else:
        require("VISUAL-ONLY" in text + logical and r"\bar{x}" in logical, "PDF-only formula was not rendered")
    if expected_formulas:
        require(operations["c"] > 0 and operations["f"] >= expected_formulas, "vector formula curves/fills missing")
    return {"pages": len(reader.pages), "embeddedFonts": sorted(fonts), "images": images,
            "formulas": expected_formulas, "curvedPaths": operations["c"], "sha256": digest(path)}


def check_docx(path, case):
    expected_formulas, expected_images = CASES[case]
    with ZipFile(path) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)) and archive.testzip() is None, "invalid DOCX ZIP")
        root = ET.fromstring(archive.read("word/document.xml"))
        styles = ET.fromstring(archive.read("word/styles.xml"))
        equations = root.findall(".//m:oMath", NS)
        require(len(equations) == expected_formulas, "native equation count changed")
        require(len(root.findall(".//w:drawing", NS)) == expected_images, "native equations became raster images")
        require(len([name for name in names if name.startswith("word/media/")]) == expected_images,
                "DOCX embedded resource count changed")
        text = "".join(root.itertext())
        require("equation-slot" not in text and "export4j-formula:" not in text and "\ufffc" not in text,
                "temporary formula identity leaked into DOCX")
        fonts = styles.find(".//w:docDefaults/w:rPrDefault/w:rPr/w:rFonts", NS)
        require(fonts is not None, "shared family declaration did not generate Word font defaults")
        require([fonts.get("{" + NS["w"] + "}" + slot) for slot in ("ascii", "hAnsi", "eastAsia", "cs")]
                == ["Liberation Sans", "Liberation Sans", "LXGW WenKai TC", "Liberation Sans"],
                "Word script slots differ from the single explicit family configuration")
        if case in RICH_CASES:
            require("中英文正文" in text and "中文单元格" in text and "年" in text, "native Chinese body/formula text missing")
            require(len(root.findall(".//w:tbl", NS)) == 1, "Markdown table missing from DOCX")
            require(root.findall(".//m:f", NS) and root.findall(".//m:rad", NS), "fraction/radical native structures missing")
        if expected_formulas or expected_images:
            require(root.findall(".//w:spacing[@w:lineRule='atLeast']", NS), "object lines did not use the safe Word spacing default")
        return {"nativeEquations": len(equations), "images": expected_images, "text": text, "sha256": digest(path)}


def require_same_docx_parts(first, second):
    with ZipFile(first) as left, ZipFile(second) as right:
        require(set(left.namelist()) == set(right.namelist()), "object binding changed DOCX package members")
        for name in left.namelist():
            require(left.read(name) == right.read(name), f"object binding changed DOCX part: {name}")


def require_same_pixels(first, second, description):
    require(first["pages"] == second["pages"], f"{description} changed pagination")
    for engine in ("poppler", "pdfium"):
        for left, right in zip(first[engine], second[engine]):
            with Image.open(left) as before, Image.open(right) as after:
                require(before.size == after.size
                        and ImageChops.difference(before.convert("RGB"), after.convert("RGB")).getbbox() is None,
                        f"{description} changed {engine} pixels")


def inspect(source, output, soffice, reader_cache):
    output.mkdir(parents=True, exist_ok=False)
    summary = json.loads((source / "result.json").read_text(encoding="utf-8"))
    require(summary["markdownFormulas"] == summary["xmlFormulas"] == 5 and summary["htmlFormulas"] == 1
            and summary["textFormulas"] == summary["degradations"] == 0
            and summary["visualOnlyFormulas"] == 1 and summary["strictNativeFailurePreserved"] is True
            and summary["fontCacheHits"] > 0, "configured-once fixture did not satisfy its result contract")
    require((source / "strict-native-rejection.bin").read_text() == "old-target", "strict failure replaced prior output")
    # Fail structural checks before starting independent reader processes.
    pdfs = {case: check_pdf(source / (case + ".pdf"), case) for case in CASES}
    docx = {case: check_docx(source / (case + ".docx"), case) for case in CASES if case != "visual-only"}
    for case in OBJECT_CASES:
        require_same_docx_parts(source / "xml.docx", source / (case + ".docx"))
    renders, reader = {}, {}
    for case in CASES:
        renders[case] = render_pdf(source / (case + ".pdf"), output / case)
        require(renders[case]["pages"] == pdfs[case]["pages"], "structural and rendered page counts differ")
        tokens = {"markdown": ("EASY-EXPORT", "中英文正文", "中文单元格"),
                  "xml": ("EASY-EXPORT", "中英文正文", "中文单元格"),
                  "html": ("HTML-ENTRY", "中文正文", "bold text", "x^2", "Explicit resource."),
                  "text": ("TEXT-ENTRY", "中文", "**literal**", "<b>HTML</b>", "$x^2$"),
                  "visual-only": ("VISUAL-ONLY", r"\bar{x}")}["xml" if case in OBJECT_CASES else case]
        for token in tokens:
            require(compact(token) in compact(renders[case]["text"]),
                    f"independent PDF extraction lost {case} source: {token}")
        if case == "visual-only":
            continue
        if soffice:
            # Identical DOCX bytes use this run's already inspected reader output.
            # This cache is local to one invocation and never skips package checks above.
            docx_hash = docx[case]["sha256"]
            if docx_hash in reader_cache:
                result, rendered = reader_cache[docx_hash]
                reader[case] = dict(result, openedThisArtifact=False)
                renders[case + "-docx"] = rendered
                continue
            target = output / (case + "-docx")
            target.mkdir()
            command = [str(soffice), "-env:UserInstallation=" + (target / "profile").as_uri(), "--headless",
                       "--invisible", "--norestore", "--convert-to", "pdf:writer_pdf_Export", "--outdir", str(target),
                       str(source / (case + ".docx"))]
            result = subprocess.run(command, capture_output=True, text=True, timeout=180)
            (target / "conversion.log").write_text(result.stdout + "\n" + result.stderr, encoding="utf-8")
            require(result.returncode == 0 and (target / (case + ".pdf")).is_file(), "independent DOCX rendering failed")
            rendered = render_pdf(target / (case + ".pdf"), target / "render")
            for token in ({"markdown": ("EASY-EXPORT", "中英文正文", "中文单元格"),
                           "xml": ("EASY-EXPORT", "中英文正文", "中文单元格"),
                           "html": ("HTML-ENTRY", "中文正文"), "text": ("TEXT-ENTRY", "中文")}
                          ["xml" if case in OBJECT_CASES else case]):
                require(compact(token) in compact(rendered["text"]), "independent DOCX renderer lost source text")
            reader[case] = {"pages": rendered["pages"], "pdfSha256": digest(target / (case + ".pdf")),
                            "docxSha256": docx_hash, "openedThisArtifact": True}
            renders[case + "-docx"] = rendered
            reader_cache[docx_hash] = (reader[case], rendered)
    require(docx["markdown"]["text"] == docx["xml"]["text"], "XML binding changed the same source's DOCX text")
    require_same_pixels(renders["markdown"], renders["xml"], "XML binding of the same Markdown source")
    for case in OBJECT_CASES:
        require(renders["xml"]["text"] == renders[case]["text"], "object binding changed independent PDF text")
        require_same_pixels(renders["xml"], renders[case], case + " binding of the same Map data")
        if soffice:
            require_same_pixels(renders["xml-docx"], renders[case + "-docx"], case + " independent DOCX rendering")
    sheets = [(f"{case} / {engine} / {index + 1}", path) for case, result in renders.items()
              for engine in ("poppler", "pdfium") for index, path in enumerate(result[engine])]
    contact_sheet(sheets, output)
    for item in docx.values():
        item.pop("text")
    return {"checks": "passed", "source": summary, "pdf": pdfs, "docx": docx, "independentDocxReader": reader,
            "xmlMatchesMarkdownPixels": True, "objectBindingMatchesMap": True,
            "renderedPagesPerEngine": sum(item["pages"] for item in renders.values()),
            "visualReview": "Full-page dual renders and contact sheet generated; visual review recorded separately."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-directory", type=Path, default=ROOT / "examples/build/easy-export")
    parser.add_argument("--consumer-build-directory", type=Path, action="append", default=[])
    parser.add_argument("--output-directory", type=Path, default=ROOT / "build/verification/easy-export/output")
    parser.add_argument("--soffice", type=Path, help="Explicit independent DOCX reader executable; never a production dependency")
    args = parser.parse_args()
    versions = {name: metadata.version(name) for name in ("pypdf", "pypdfium2", "Pillow")}
    require(versions == {"pypdf": "6.10.0", "pypdfium2": "5.13.0", "Pillow": "12.3.0"},
            "use the pinned project verification environment")
    output = args.output_directory.resolve()
    require(output.is_relative_to(ROOT / "build"), "verification outputs must remain under ignored build/")
    output.mkdir(parents=True, exist_ok=False)
    office = args.soffice.resolve() if args.soffice else None
    if office:
        require(office.is_file(), "explicit DOCX reader executable is missing")
    reader_cache = {}
    report = {"tools": versions, "example": inspect(args.source_directory.resolve(), output / "example", office, reader_cache), "consumers": []}
    for index, build in enumerate(args.consumer_build_directory, 1):
        item = inspect(build.resolve() / "output/easy-export", output / f"consumer-{index}", office, reader_cache)
        require(item["source"].get("uniqueRuntimeProviders") is True and len(item["source"].get("starterJarSha256", "")) == 64,
                "consumer did not prove unique published starter providers")
        report["consumers"].append(item)
    report["docxReaderExecuted"] = office is not None
    report["uniqueDocxReaderInputs"] = len(reader_cache)
    report["boundary"] = "No Microsoft Word editing/save check is claimed. Independent reader pages require the separately recorded visual review."
    (output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"checks": "passed", "consumerModes": len(report["consumers"]),
                      "docxReaderExecuted": report["docxReaderExecuted"], "output": str(output)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
