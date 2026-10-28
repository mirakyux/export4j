# SPDX-License-Identifier: Apache-2.0
"""Independent production fixtures, local Maven bundle and optional real-reader save evidence.

Run after build sbom publishLocalBundle :examples:production and the isolated consumer.
Requires requirements-verification.txt and Poppler on PATH. Does not update baselines.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
from importlib import metadata
import io
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from docx import Document as WordDocument
from PIL import Image, ImageDraw
from pypdf import PdfReader
import pypdfium2 as pdfium

from verify_advanced_pdf import check_fields, difference, render_pdfium, render_poppler, require

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "examples/build/production"
W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"


def extracted(path):
    text = []
    with pdfium.PdfDocument(path) as document:
        for index in range(len(document)):
            page = document[index]
            try:
                textpage = page.get_textpage()
                try:
                    text.append(textpage.get_text_bounded())
                finally:
                    textpage.close()
            finally:
                page.close()
    return "\n".join(text)


def actual_text(reader):
    values = []
    for page in reader.pages:
        for operands, operator in page.get_contents().operations:
            if operator == b"BDC" and len(operands) > 1:
                properties = operands[1]
                if not isinstance(properties, dict):
                    properties = page["/Resources"].get("/Properties", {}).get(properties, {})
                value = properties.get("/ActualText")
                if value is not None:
                    values.append(str(value))
    return values


def tags(text, prefix, count):
    found = re.findall(rf"{prefix}-(\d{{2}})", text)
    require(found == [f"{i:02d}" for i in range(1, count + 1)], f"{prefix} tags lost, duplicated or out of order: {found}")


def contact_sheet(paths, target):
    tiles = []
    for name, path in paths:
        with Image.open(path) as source:
            page = source.convert("RGB")
            page.thumbnail((420, 440))
        tile = Image.new("RGB", (440, 480), "#eeeeee")
        tile.paste(page, ((440 - page.width) // 2, 28))
        ImageDraw.Draw(tile).text((10, 8), name, fill="black")
        tiles.append(tile)
    for offset in range(0, len(tiles), 6):
        batch = tiles[offset:offset + 6]
        sheet = Image.new("RGB", (440 * 3, 480 * 2), "white")
        for index, tile in enumerate(batch):
            sheet.paste(tile, ((index % 3) * 440, (index // 3) * 480))
        sheet.save(target / f"contact-{offset // 6 + 1}.png")


def check_docx(path):
    doc = WordDocument(path)
    require(doc.core_properties.title == "Document engine local verification", "DOCX metadata differs")
    require(len(doc.sections) == 2, "DOCX sections differ")
    require(abs(doc.sections[0].page_width.pt - 430) < .1 and abs(doc.sections[1].page_width.pt - 500) < .1, "DOCX mixed page widths differ")
    require(doc.paragraphs[0].text == "多格式语义样例" and doc.paragraphs[0].style.name == "Heading 1", "DOCX heading differs")
    require(doc.paragraphs[1].runs[1].text == "Bold" and doc.paragraphs[1].runs[1].underline, "DOCX rich run differs")
    require(doc.paragraphs[1].runs[1].style.font.bold, "DOCX bold character style differs")
    require(len(doc.tables) == 1 and len(doc.tables[0].rows) == 2 and len(doc.tables[0].columns) == 2, "DOCX table differs")
    tags(doc.tables[0].cell(1, 0).text, "ROW", 3)
    with ZipFile(path) as archive:
        main = ET.fromstring(archive.read("word/document.xml"))
        numbering = ET.fromstring(archive.read("word/numbering.xml"))
        require(len(main.findall(f".//{W}sectPr")) == 2, "OOXML section properties differ")
        require(main.findall(f".//{W}pgNumType")[-1].get(W + "start") == "10", "OOXML restart differs")
        require([e.get(W + "val") for e in main.findall(f".//{W}ilvl")] == ["0", "1", "0"], "OOXML list levels differ")
        require(any(e.get(W + "val") == "bullet" for e in numbering.findall(f".//{W}numFmt")), "OOXML bullets missing")
        require(main.find(f".//{W}tblHeader") is not None, "OOXML repeat header missing")
        require("word/_rels/document.xml.rels" in archive.namelist(), "OOXML relationships missing")
    return {"paragraphs": len(doc.paragraphs), "sections": 2, "tables": 1, "independentParser": metadata.version("python-docx")}


def check_bundle(consumer_build_directory=None):
    sbom = json.loads((ROOT / "build/reports/sbom.cdx.json").read_text(encoding="utf-8"))
    require(sbom["bomFormat"] == "CycloneDX" and sbom["specVersion"] == "1.6", "SBOM format differs")
    components = sbom["components"]
    require(len(components) == len({c["bom-ref"] for c in components}) == 29, "SBOM runtime inventory differs")
    own = [c for c in components if c["group"] == "com.mirakyux.export4j"]
    require(len(own) == 16 and {"export4j-adapter-template", "export4j-adapter-pdf", "export4j-adapter-web", "export4j-opentype"} <= {c["name"] for c in own}, "SBOM library count differs")
    with (ROOT / "third-party/dependencies.csv").open(encoding="utf-8", newline="") as source:
        reviewed = {row["coordinate"]: row for row in csv.DictReader(source)}
    for component in components:
        if component["group"] == "com.mirakyux.export4j":
            continue
        key = f'{component["group"]}:{component["name"]}:{component["version"]}'
        require(reviewed[key]["scope"] == "runtime" and reviewed[key]["sha256"] == component["hashes"][0]["content"], f"external SBOM artifact differs: {key}")
        expected_license = "BSD-3-Clause" if component["group"] == "org.lwjgl" else "BSD-2-Clause" if component["group"] == "org.commonmark" else "MIT" if component["group"] in ("org.jsoup", "org.bouncycastle") else "Apache-2.0"
        require(component["licenses"] == [{"license": {"id": expected_license}}], f"external SBOM license differs: {key}")
    repo = ROOT / "build/repository/com/mirakyux/export4j"
    def published(name, version, extension, classifier=""):
        directory = repo / name / version
        resolved = version
        if version.endswith("-SNAPSHOT"):
            record = ET.parse(directory / "maven-metadata.xml")
            matches = [entry.findtext("value") for entry in record.findall("versioning/snapshotVersions/snapshotVersion")
                       if entry.findtext("extension") == extension and (entry.findtext("classifier") or "") == classifier]
            require(len(matches) == 1, f"snapshot metadata ambiguous: {name}/{extension}/{classifier}")
            resolved = matches[0]
        suffix = "-" + classifier if classifier else ""
        return directory / f"{name}-{resolved}{suffix}.{extension}"
    for component in own:
        name, version = component["name"], component["version"]
        artifact = published(name, version, "jar")
        require(hashlib.sha256(artifact.read_bytes()).hexdigest() == component["hashes"][0]["content"], f"SBOM hash differs: {name}")
        require(published(name, version, "jar", "sources").exists(), f"sources missing: {name}")
        require(published(name, version, "jar", "javadoc").exists(), f"Javadoc missing: {name}")
        with ZipFile(artifact) as archive:
            require("META-INF/LICENSE" in archive.namelist() and "META-INF/NOTICE" in archive.namelist(), f"license bundle missing: {name}")
            require(archive.read("META-INF/LICENSE") == (ROOT / "LICENSE").read_bytes()
                    and archive.read("META-INF/NOTICE") == (ROOT / "NOTICE").read_bytes(),
                    f"published license/notice is stale: {name}")
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    bom = ET.parse(published("export4j-bom", "0.1.0-SNAPSHOT", "pom"))
    managed = bom.findall("m:dependencyManagement/m:dependencies/m:dependency", ns)
    require({d.findtext("m:artifactId", namespaces=ns) for d in managed} == {c["name"] for c in own}, "BOM constraints differ")
    optional = ET.parse(published("export4j-opentype", "0.1.0-SNAPSHOT", "pom"))
    native_bindings = [entry for entry in optional.findall("m:dependencies/m:dependency", ns)
                       if entry.findtext("m:groupId", namespaces=ns) == "org.lwjgl"]
    require({entry.findtext("m:artifactId", namespaces=ns) for entry in native_bindings}
            == {"lwjgl", "lwjgl-harfbuzz"}
            and all(entry.findtext("m:classifier", namespaces=ns) is None for entry in native_bindings),
            "optional OpenType must publish Java bindings without forcing a platform native classifier")
    if consumer_build_directory is None:
        pointer = ROOT / "build/verification/consumer-build-directory.txt"
        require(pointer.exists(), "run verify_local_consumer.ps1 first or pass --consumer-build-directory")
        consumer_build_directory = Path(pointer.read_text(encoding="utf-8").strip())
    libraries = (consumer_build_directory / "resolved-libraries.txt").read_text().splitlines()
    require(len(libraries) == 29 and set(libraries) == {f'{c["group"]}:{c["name"]}:{c["version"]}' for c in components}, "isolated consumer dependencies differ")
    consumer = consumer_build_directory / "output"
    require("CONSUMER-OK-123" in extracted(consumer / "consumer.pdf"), "consumer PDF text differs")
    require(WordDocument(consumer / "consumer.docx").paragraphs[0].text == "CONSUMER-OK-123", "consumer DOCX differs")
    require(ET.parse(consumer / "consumer.svg").getroot().tag.endswith("svg"), "consumer SVG invalid")
    require(len(WordDocument(consumer / "consumer-images.docx").inline_shapes) == 1, "consumer DOCX inline image missing")
    image_pdf = PdfReader(consumer / "consumer-images.pdf")
    require(any(obj.get_object().get("/Subtype") == "/Image" for obj in image_pdf.pages[0]["/Resources"]["/XObject"].values()), "consumer PDF inline image missing")
    float_pdf = PdfReader(consumer / "consumer-floats.pdf")
    require(len(float_pdf.pages) == 1 and "".join(actual_text(float_pdf)) == "CONSUMER-FLOATS a\u0301", "consumer multiple-float source differs")
    require(sum(operator == b"W" for _, operator in float_pdf.pages[0].get_contents().operations) == 2, "consumer float viewports missing")
    columns_pdf = PdfReader(consumer / "consumer-columns.pdf")
    require(len(columns_pdf.pages) == 3 and actual_text(columns_pdf) == [f"COL-{index:02d}" for index in range(1, 23)],
            "consumer continuous columns lost, reordered or duplicated source")
    require([sum(operator == b"W" for _, operator in page.get_contents().operations) for page in columns_pdf.pages] == [2, 2, 1],
            "consumer continuous column viewports differ")
    require("CONSUMER-COLUMNS-TAIL" in columns_pdf.pages[-1].extract_text(), "consumer following block missing")
    mixed_pdf = PdfReader(consumer / "consumer-mixed-columns.pdf")
    fields_pdf = PdfReader(consumer / "consumer-inline-fields.pdf")
    require(len(fields_pdf.pages) == 2 and actual_text(fields_pdf) == ["CONSUMER-PAGE=1 TOTAL=2 TARGET=2", "CONSUMER-CELL=1 TOTAL=2 TARGET=2"], "consumer inline field values differ")
    field_links = [item.get_object() for item in fields_pdf.pages[0].get("/Annots", [])]
    require(len(field_links) == 2 and all(link["/Dest"][0].idnum == fields_pdf.pages[1].indirect_reference.idnum for link in field_links)
            and "FIELD-TARGET" in fields_pdf.pages[1].extract_text(), "consumer inline field target or link missing")
    require(len(mixed_pdf.pages) == 3 and actual_text(mixed_pdf) == [f"MIX-{index:02d}" for index in range(1, 9)],
            "consumer mixed column source differs")
    mixed_glyphs = "\n".join(page.extract_text() for page in mixed_pdf.pages)
    require(mixed_glyphs.count("GRID-HEAD") == 2 and all(mixed_glyphs.count(f"GRID-ROW-{row}") == 1 for row in range(1, 5)),
            "consumer table headers/body coverage differs")
    require([sum(operator == b"W" for _, operator in page.get_contents().operations) for page in mixed_pdf.pages] == [3, 2, 0],
            "consumer mixed column/image viewports differ")
    require(sum(operator == b"Do" for page in mixed_pdf.pages for _, operator in page.get_contents().operations) == 1,
            "consumer mixed image lost or duplicated")
    require("CONSUMER-MIXED-TAIL" in mixed_pdf.pages[-1].extract_text(), "consumer mixed following body missing")
    with ZipFile(consumer / "consumer-pages.zip") as archive:
        require(archive.namelist() == ["page-00001.png"], "consumer PNG pages differ")
        with Image.open(io.BytesIO(archive.read("page-00001.png"))) as page:
            require(page.width > 500 and page.height > 800, "consumer raster dimensions differ")
    examples_jar = ROOT / "examples/build/libs/examples-0.1.0-SNAPSHOT.jar"
    with ZipFile(examples_jar) as archive:
        for name in ("NotoNaskhArabic-Regular.ttf", "NotoNaskhArabic-OFL.txt", "NotoSansDevanagari-Regular.ttf", "NotoSansThai-Regular.ttf"):
            require(archive.read("fonts/" + name) == (ROOT / "assets/fonts" / name).read_bytes(), f"packaged Arabic font/license differs: {name}")
    return {"runtimeComponents": len(components), "libraries": 16, "bomConstraints": len(managed), "isolatedConsumer": "passed"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "build/verification/production")
    parser.add_argument("--reader-copy", type=Path, help="Optional Edge-filled PDF retained from an actual reader save")
    parser.add_argument("--consumer-build-directory", type=Path, help="Successful isolated consumer build; defaults to the script's last-success pointer")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    report = {"pdfs": {}, "tools": {name: metadata.version(name) for name in ("pypdf", "pypdfium2", "Pillow", "python-docx")}}
    contacts = []
    for name, count in {"typography": 1, "columns": 4, "float": 2, "split-table": 3, "notes": 4, "semantic-roundtrip": 2}.items():
        source = FIXTURES / f"{name}.pdf"
        reader = PdfReader(source)
        require(len(reader.pages) == count, f"{name} page count differs")
        poppler = render_poppler(source, args.output / name / "poppler")
        pdfium_pages = render_pdfium(source, args.output / name / "pdfium")
        require(len(poppler) == len(pdfium_pages) == count, f"{name} renderer counts differ")
        comparisons = [difference(a, b, blur_radius=1) for a, b in zip(poppler, pdfium_pages, strict=True)]
        require(all(d["meanAbsoluteChannelError"] < 4 and d["changedPixelFraction"] < .06 for d in comparisons), f"{name} renderers differ materially")
        text = extracted(source)
        (args.output / name / "extracted.txt").write_text(text, encoding="utf-8")
        if name == "columns":
            tags("".join(actual_text(reader)), "COL", 40)
            require(sorted(re.findall(r"COL-(\d{2})", text)) == [f"{i:02d}" for i in range(1, 41)], "PDFium column coverage differs")
        if name == "split-table":
            tags(text, "ROW", 40)
            require(text.count("SHORT:") == 1, "short table cell duplicated")
        if name == "notes":
            tags(text, "NOTE", 30)
        if name == "typography":
            logical = "".join(actual_text(reader))
            for expected in ("á ë Å", "العربية لا سلام 123", "שָׁלוֹם עולם 123", "English العربية 456 English", "中文标点：（测试）"):
                require(expected in logical, f"logical ActualText missing: {expected}")
                require(expected in text, f"PDFium logical text missing: {expected}")
        contacts.extend((f"{name} / {index}", path) for index, path in enumerate(poppler, 1))
        report["pdfs"][name] = {"pages": count, "sha256": hashlib.sha256(source.read_bytes()).hexdigest(), "crossRendererBlur1": comparisons}
    with ZipFile(FIXTURES / "typography-pages.zip") as archive:
        require(archive.namelist() == ["page-00001.png"], "raster page names differ")
        raster = args.output / "typography/raster.png"
        raster.write_bytes(archive.read("page-00001.png"))
    with pdfium.PdfDocument(FIXTURES / "typography.pdf") as document:
        page = document[0]
        bitmap = page.render(scale=2)
        try:
            pdf_path = args.output / "typography/pdfium-144.png"
            bitmap.to_pil().convert("RGB").save(pdf_path)
        finally:
            bitmap.close(); page.close()
    report["rasterVsPdf144"] = difference(raster, pdf_path, blur_radius=1)
    require(report["rasterVsPdf144"]["meanAbsoluteChannelError"] < 4 and report["rasterVsPdf144"]["changedPixelFraction"] < .06, "raster differs materially from PDF")
    svg = ET.parse(FIXTURES / "typography.svg").getroot()
    require(svg.tag.endswith("svg") and len(svg.findall(".//{http://www.w3.org/2000/svg}path")) > 5, "SVG outlines missing")
    report["docx"] = check_docx(FIXTURES / "semantic.docx")
    report["localBundle"] = check_bundle(args.consumer_build_directory)
    consumer_directory = args.consumer_build_directory or Path((ROOT / "build/verification/consumer-build-directory.txt").read_text(encoding="utf-8").strip())
    consumer_source = consumer_directory / "output/consumer-floats.pdf"
    consumer_poppler = render_poppler(consumer_source, args.output / "consumer-floats/poppler")
    consumer_pdfium = render_pdfium(consumer_source, args.output / "consumer-floats/pdfium")
    require(len(consumer_poppler) == len(consumer_pdfium) == 1, "consumer float renderer page count differs")
    comparisons = [difference(a, b, blur_radius=1) for a, b in zip(consumer_poppler, consumer_pdfium, strict=True)]
    require(all(d["meanAbsoluteChannelError"] < 4 and d["changedPixelFraction"] < .06 for d in comparisons), "consumer float renderers disagree")
    report["consumerFloats"] = {"pages": 1, "crossRendererBlur1": comparisons}
    contacts.extend(("consumer floats", page) for page in consumer_poppler)
    columns_source = consumer_directory / "output/consumer-columns.pdf"
    columns_poppler = render_poppler(columns_source, args.output / "consumer-columns/poppler")
    columns_pdfium = render_pdfium(columns_source, args.output / "consumer-columns/pdfium")
    require(len(columns_poppler) == len(columns_pdfium) == 3, "consumer column renderer page count differs")
    comparisons = [difference(a, b, blur_radius=1) for a, b in zip(columns_poppler, columns_pdfium, strict=True)]
    require(all(d["meanAbsoluteChannelError"] < 4 and d["changedPixelFraction"] < .06 for d in comparisons), "consumer column renderers disagree")
    report["consumerColumns"] = {"pages": 3, "crossRendererBlur1": comparisons}
    contacts.extend((f"consumer columns {index + 1}", page) for index, page in enumerate(columns_poppler))
    mixed_source = consumer_directory / "output/consumer-mixed-columns.pdf"
    mixed_poppler = render_poppler(mixed_source, args.output / "consumer-mixed-columns/poppler")
    mixed_pdfium = render_pdfium(mixed_source, args.output / "consumer-mixed-columns/pdfium")
    require(len(mixed_poppler) == len(mixed_pdfium) == 3, "consumer mixed renderer page count differs")
    comparisons = [difference(a, b, blur_radius=1) for a, b in zip(mixed_poppler, mixed_pdfium, strict=True)]
    require(all(d["meanAbsoluteChannelError"] < 4 and d["changedPixelFraction"] < .06 for d in comparisons), "consumer mixed renderers disagree")
    report["consumerMixedColumns"] = {"pages": 3, "crossRendererBlur1": comparisons}
    contacts.extend((f"consumer mixed columns {index + 1}", page) for index, page in enumerate(mixed_poppler))
    fields_source = consumer_directory / "output/consumer-inline-fields.pdf"
    fields_poppler = render_poppler(fields_source, args.output / "consumer-inline-fields/poppler")
    fields_pdfium = render_pdfium(fields_source, args.output / "consumer-inline-fields/pdfium")
    require(len(fields_poppler) == len(fields_pdfium) == 2, "consumer inline field renderer page count differs")
    comparisons = [difference(a, b, blur_radius=1) for a, b in zip(fields_poppler, fields_pdfium, strict=True)]
    require(all(d["meanAbsoluteChannelError"] < 4 and d["changedPixelFraction"] < .06 for d in comparisons), "consumer inline field renderers disagree")
    report["consumerInlineFields"] = {"pages": 2, "crossRendererBlur1": comparisons}
    contacts.extend((f"consumer inline fields {index + 1}", page) for index, page in enumerate(fields_poppler))
    fluent_source = consumer_directory / "output/consumer-fluent.pdf"
    fluent_reader = PdfReader(fluent_source)
    require(len(fluent_reader.pages) == 1, "consumer fluent page count differs")
    fluent_text = fluent_reader.pages[0].extract_text().split()
    require(fluent_text == ["FLUENT-CONSUMER", "Merged", "A", "B", "Key", "Value", "C", "3", "P1/1"],
            "consumer fluent content order or final footer differs")
    fluent_poppler = render_poppler(fluent_source, args.output / "consumer-fluent/poppler")
    fluent_pdfium = render_pdfium(fluent_source, args.output / "consumer-fluent/pdfium")
    require(len(fluent_poppler) == len(fluent_pdfium) == 1, "consumer fluent renderer page count differs")
    comparisons = [difference(a, b, blur_radius=1) for a, b in zip(fluent_poppler, fluent_pdfium, strict=True)]
    require(all(d["meanAbsoluteChannelError"] < 4 and d["changedPixelFraction"] < .06 for d in comparisons),
            "consumer fluent renderers disagree")
    report["consumerFluent"] = {"pages": 1, "crossRendererBlur1": comparisons}
    contacts.extend(("consumer fluent", page) for page in fluent_poppler)
    if args.reader_copy:
        expected = {"cn_name": "阅读器填写验证", "status": "草稿", "mode": "/final", "cn_notes": "新填中文备注\n保存后重开验证"}
        check_fields(PdfReader(args.reader_copy), expected)
        require(len(PdfReader(args.reader_copy).pages) == 7, "reader copy page count differs")
        report["realReaderSave"] = {"sha256": hashlib.sha256(args.reader_copy.read_bytes()).hexdigest(), "fields": expected, "editableWidgets": 5}
        reader_pages = render_poppler(args.reader_copy, args.output / "reader-copy/poppler")
        reader_pdfium = render_pdfium(args.reader_copy, args.output / "reader-copy/pdfium")
        comparisons = [difference(a, b, blur_radius=1) for a, b in zip(reader_pages, reader_pdfium, strict=True)]
        require(len(reader_pages) == len(reader_pdfium) == 7, "reader render count differs")
        require(all(d["meanAbsoluteChannelError"] < 4 and d["changedPixelFraction"] < .06 for d in comparisons), "reader copy renderers differ materially")
        report["realReaderSave"]["crossRendererBlur1"] = comparisons
        contacts.extend((f"reader copy / {i}", path) for i, path in enumerate(reader_pages, 1))
    contact_sheet(contacts, args.output)
    (args.output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
