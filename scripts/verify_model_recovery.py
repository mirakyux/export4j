# SPDX-License-Identifier: Apache-2.0
"""Independent companion bindings, standard structures, external rewrites and visual controls."""
import argparse
import csv
import hashlib
import io
import json
from pathlib import Path
import struct
import xml.etree.ElementTree as ET
from zipfile import ZipFile, ZIP_DEFLATED

from markdown_it import MarkdownIt
from PIL import Image, ImageChops
from pypdf import PdfReader, PdfWriter
from pypdf.generic import IndirectObject
from verify_adr_form import render
from verify_advanced_pdf import difference, require
from verify_streaming_rich_text import physical_fonts

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "examples/build/model-recovery"
OUTPUT = ROOT / "build/verification/model-recovery"
EXTENSIONS = {"PDF": "pdf", "DOCX": "docx", "HTML": "html", "MARKDOWN": "md", "SVG": "svg", "PNG_ZIP": "png.zip", "XML_TEMPLATE": "xml.zip"}


def unzip(path):
    with ZipFile(path) as archive:
        require(archive.testzip() is None, "ZIP CRC failure")
        require(len(archive.namelist()) == len(set(archive.namelist())), "duplicate ZIP entries")
        return {name: archive.read(name) for name in archive.namelist()}


def prepare_external():
    """Use independent libraries to rewrite files; these are not Word/Acrobat UI saves."""
    with ZipFile(SOURCE / "external.docx", "w", compression=ZIP_DEFLATED, compresslevel=1) as target:
        for name, data in unzip(SOURCE / "original.docx").items():
            target.writestr(name, data)
        target.comment = b"Independent ZIP reserialization"
    writer = PdfWriter(); writer.append(PdfReader(SOURCE / "original.pdf"))
    writer.add_metadata({"/Producer": "Independent pypdf reserialization"})
    with (SOURCE / "external.pdf").open("wb") as output:
        writer.write(output)
    # HTML/Markdown are deliberately normalized by a text tool, separately from reader-app evidence.
    for extension in ("html", "md"):
        text = (SOURCE / f"original.{extension}").read_text(encoding="utf-8")
        (SOURCE / f"external.{extension}").write_bytes((text.rstrip() + "\r\n\r\n").encode("utf-8"))
    ET.ElementTree(ET.parse(SOURCE / "original.svg").getroot()).write(SOURCE / "external.svg", encoding="utf-8", xml_declaration=True)
    with ZipFile(SOURCE / "external.png.zip", "w", compression=ZIP_DEFLATED) as target:
        for name, data in unzip(SOURCE / "original.png.zip").items():
            with Image.open(io.BytesIO(data)) as image:
                encoded = io.BytesIO(); image.save(encoded, format="PNG", compress_level=1)
            target.writestr(name, encoded.getvalue())
    with ZipFile(SOURCE / "external.xml.zip", "w", compression=ZIP_DEFLATED) as target:
        for name, data in unzip(SOURCE / "original.xml.zip").items():
            if name == "template.xml":
                data = ET.tostring(ET.fromstring(data), encoding="utf-8", xml_declaration=True)
            target.writestr(name, data)
    for extension in EXTENSIONS.values():
        require((SOURCE / f"original.{extension}").read_bytes() != (SOURCE / f"external.{extension}").read_bytes(), "external rewrite did not change bytes")


def companion(path):
    entries = unzip(path)
    root = ET.fromstring(entries.pop("recovery.xml"))
    require(root.tag == "recovery" and root.get("version") == "1", "unsupported recovery manifest")
    current = path.with_name(path.name.removesuffix(".recovery.zip"))
    # Explicit current-model snapshots use a separately named companion.
    if not current.exists() and path.name.endswith(".current.recovery.zip"):
        current = path.with_name(path.name.replace(".current.recovery.zip", ".current.zip"))
    data = current.read_bytes()
    require(int(root.get("bytes")) == len(data) and root.get("sha256") == hashlib.sha256(data).hexdigest(), "standard-file binding differs")
    model = hashlib.sha256()
    for name, content in sorted(entries.items()):
        encoded = name.encode("utf-8")
        model.update(struct.pack(">II", len(encoded), len(content))); model.update(encoded); model.update(content)
    require(model.hexdigest() == root.get("model-sha256"), "model/resource binding differs")
    require(entries["fonts/Regular.ttf"] == (ROOT / "assets/fonts/LiberationSans-Regular.ttf").read_bytes(), "font bytes changed")
    require(entries["licenses/Font.txt"] == (ROOT / "assets/fonts/LiberationSans-OFL.txt").read_bytes(), "license bytes changed")
    require(set(entries) == {"template.xml", "fonts/Regular.ttf", "licenses/Font.txt"}, "unexpected retained source/resource")
    document = ET.fromstring(entries["template.xml"])
    require(document.tag == "document" and document.get("literal-text") == "true", "snapshot is not the existing static XML grammar")
    require("UNUSED-SOURCE" not in entries["template.xml"].decode("utf-8"), "stale authoring source was copied")
    return entries, root, document


def verify_standard(path):
    name = path.name
    expected = "EDITED" if name.startswith("edited.") else "ORIGINAL"
    if name.endswith(".docx"):
        parts = unzip(path); root = ET.fromstring(parts["word/document.xml"])
        text = "".join(root.itertext())
        require(expected in text and "UNCHANGED-CONTROL" in text, "DOCX text missing")
    elif name.endswith(".html"):
        text = path.read_text(encoding="utf-8")
        require(expected in text and "UNCHANGED-CONTROL" in text and "<section" in text, "HTML text/structure missing")
    elif name.endswith(".md"):
        tokens = MarkdownIt().parse(path.read_text(encoding="utf-8"))
        require(sum(t.type == "paragraph_open" for t in tokens) == 2, "Markdown paragraph structure differs")
        require(expected in "".join(t.content for t in tokens), "Markdown content missing")
    elif name.endswith(".svg"):
        root = ET.parse(path).getroot(); ns = {"s": "http://www.w3.org/2000/svg"}
        require(root.find(".//s:path", ns) is not None and expected in "".join(root.itertext()), "SVG outlines/text missing")
    elif name.endswith(".png.zip"):
        pages = unzip(path); require(list(pages) == ["page-00001.png"], "PNG page inventory differs")
        with Image.open(io.BytesIO(next(iter(pages.values())))) as image:
            require(image.width > 700 and image.height > 1000 and image.getextrema()[0][0] < 64, "PNG page is blank/invalid")
    elif name.endswith(".xml.zip"):
        root = ET.fromstring(unzip(path)["template.xml"])
        require(expected in "".join(root.itertext()), "XML document content missing")


def verify(consumer_directory):
    OUTPUT.mkdir(parents=True, exist_ok=True)
    manifests = sorted(SOURCE.glob("*.recovery.zip")); require(len(manifests) == 73, "recovery inventory differs")
    original_model = companion(SOURCE / "original.pdf.recovery.zip")[0]["template.xml"]
    for path in manifests:
        entries, manifest, model = companion(path)
        if path.name.startswith("original."):
            require(entries["template.xml"] == original_model, "format changed the retained model")
        if path.name.startswith("cycle-3.") or path.name.startswith("bridge-"):
            require(entries["template.xml"] == original_model, "control model changed")
        if path.name.startswith("edited.") or path.name.endswith(".current.recovery.zip"):
            require("ORIGINAL" not in "".join(model.itertext()), "deleted original content returned")
        if path.name == "history.pdf.recovery.zip":
            require(len(manifest.findall("issue")) == 1, "historical loss was duplicated or removed")
    matrix = list(csv.DictReader((SOURCE / "matrix.csv").open(encoding="utf-8")))
    require(len(matrix) == 56 and sum(row["rounds"] == "3" for row in matrix) == 7, "round trip matrix is incomplete")
    require(all(row["model"] == row["resources"] == "true" for row in matrix), "model/resource control failed")
    for prefix in ("original", "cycle-3", "edited"):
        for extension in EXTENSIONS.values():
            verify_standard(SOURCE / f"{prefix}.{extension}")
    before, after = unzip(SOURCE / "original.docx"), unzip(SOURCE / "package-edited.docx")
    require(before.keys() == after.keys(), "package part inventory changed")
    require([name for name in before if before[name] != after[name]] == ["word/document.xml"], "untouched package bytes changed")
    require(b"PACKAGE-EDITED" in after["word/document.xml"] and b"ORIGINAL" not in after["word/document.xml"], "package edit content differs")
    policy = list(csv.DictReader((SOURCE / "policy.csv").open(encoding="utf-8")))
    for format_key in ("HTML", "MARKDOWN"):
        rows = {r["action"]: r for r in policy if r["format"] == format_key}
        require(rows["WARN"]["losses"] == rows["IGNORE"]["losses"] and int(rows["WARN"]["losses"]) > 0, "loss policy reconciliation differs")
        require(int(rows["WARN"]["warnings"]) > 0 and rows["IGNORE"]["warnings"] == "0", "warning policy differs")
        require(all(row["recovery"] == "false" for row in rows.values()), "lossy output retained a richer snapshot")
    external = list(csv.DictReader((SOURCE / "external.csv").open(encoding="utf-8")))
    require(len(external) == 7 and all(row["recovered"] == "false" for row in external), "external rewrite accepted old model")
    history = next(csv.DictReader((SOURCE / "history.csv").open(encoding="utf-8")))
    require(int(history["prior"]) > 0 and int(history["current"]) > 0 and int(history["total"]) == sum(int(history[k]) for k in ("prior", "current", "recovery")), "new and historical losses did not accumulate")
    pdfs = sorted(SOURCE.glob("*.pdf"))
    if consumer_directory:
        pdfs += sorted((consumer_directory / "output").glob("consumer-model-recovery*.pdf"))
        for path in (consumer_directory / "output").glob("consumer-model-recovery*.recovery.zip"):
            entries, manifest, model = companion(path)
            require("CALLER" in "".join(model.itertext()) and "ORIGINAL" not in "".join(model.itertext()), "caller recovered a deleted model")
    results = {}; renders = {}
    for path in pdfs:
        reader = PdfReader(path, strict=True); require(len(reader.pages) == 1, "unexpected PDF pagination")
        text = "".join(page.extract_text() or "" for page in reader.pages)
        if path.stem == "redacted":
            require("ORIGINAL" not in text and "RECOVERY-CONTROL" not in text, "redacted text remained extractable")
            require("/Font" not in reader.pages[0]["/Resources"] and not reader.pages[0].get("/Annots"), "redaction retained source font/annotation objects")
            for generation, objects in reader.xref.items():
                for number in objects:
                    if number == 0:
                        continue
                    obj = reader.get_object(IndirectObject(number, generation, reader))
                    require("ORIGINAL" not in str(obj), "redaction retained original object text")
                    if hasattr(obj, "get_data"):
                        require(b"ORIGINAL" not in obj.get_data(), "redaction retained original decoded stream")
        else:
            physical_fonts(reader, {"LiberationSans"})
            marker = "CALLER" if path.stem.startswith("consumer-model-recovery") else "HISTORY-CURRENT" if path.stem == "history" else "PDF-EDIT" if path.stem == "page-edited" else "EDITED" if path.stem == "edited" else "ORIGINAL"
            require(marker in text, "PDF content missing: " + path.name)
            if path.stem != "history":
                require("UNCHANGED-CONTROL" in text, "untouched PDF text lost")
        poppler, pdfium = render(path, OUTPUT / path.stem)
        diff = difference(poppler[0], pdfium[0], blur_radius=2)
        require(diff["meanAbsoluteChannelError"] < .8 and diff["changedPixelFraction"] < .004, "independent PDF renderers diverge")
        renders[path.stem] = (poppler[0], pdfium[0]); results[path.name] = diff
    baseline = renders["original"]
    for name in ["cycle-3"] + [name for name in renders if name.startswith("bridge-PDF-")]:
        for index in (0, 1):
            require(difference(baseline[index], renders[name][index])["meanAbsoluteChannelError"] == 0, "unmodified PDF changed pixels")
    for name in ("edited", "page-edited"):
        for index in (0, 1):
            with Image.open(baseline[index]) as a, Image.open(renders[name][index]) as b:
                require(ImageChops.difference(a, b).getbbox() is not None, "actual PDF edit changed no pixels")
                require(ImageChops.difference(a.crop((0, 300, a.width, a.height)), b.crop((0, 300, b.width, b.height))).getbbox() is None, "untouched PDF region changed pixels")
    report = {"companions": len(manifests), "matrixRows": len(matrix), "aToBToA": 49, "formats": list(EXTENSIONS), "externalRewrites": len(external), "pdfPages": len(pdfs), "renders": results}
    (OUTPUT / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps({key: value for key, value in report.items() if key != "renders"}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prepare-external", action="store_true")
    parser.add_argument("--consumer-build-directory", type=Path)
    arguments = parser.parse_args()
    if arguments.prepare_external:
        prepare_external()
    else:
        verify(arguments.consumer_build_directory)
