# SPDX-License-Identifier: Apache-2.0
"""Independently verify combined self-generated recovery and real Word save evidence."""
import argparse
import csv
import hashlib
import io
import json
import re
import struct
import xml.etree.ElementTree as ET
from pathlib import Path

from docx import Document
from PIL import Image, ImageChops
from pypdf import PdfReader
from verify_adr_form import render
from verify_advanced_pdf import difference, require
from verify_complex_recovery import embedded as standard_embedded, unzip
from verify_production import check_bundle, contact_sheet
from verify_semantic_tables import page_logical

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "examples/build/recovery-closure"
OUTPUT = ROOT / "build/verification/recovery-closure"
FORMATS = {"PDF": "pdf", "DOCX": "docx", "HTML": "html", "MARKDOWN": "md"}
W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"


def embedded(data, key):
    if key != "PDF": return standard_embedded(data, key)
    reader = PdfReader(io.BytesIO(data), strict=True)
    require(set(reader.attachments) == {"export4j-recovery.zip"}, "PDF recovery attachment inventory differs")
    spec = reader.trailer["/Root"]["/Names"]["/EmbeddedFiles"]["/Names"][1].get_object()
    match = re.fullmatch(r"Export4j recovery;source-bytes=(\d+)", spec["/Desc"])
    require(match is not None and len(reader.attachments["export4j-recovery.zip"]) == 1, "PDF envelope is ambiguous")
    return data[:int(match[1])], reader.attachments["export4j-recovery.zip"][0]


def recovery(encoded, standard, key, expected):
    parts = unzip(encoded); manifest = ET.fromstring(parts.pop("recovery.xml"))
    require(manifest.get("version") == "1" and manifest.get("format") == key, "manifest format/version differs")
    require(int(manifest.get("bytes")) == len(standard) and manifest.get("sha256") == hashlib.sha256(standard).hexdigest(), "current standard byte binding differs")
    digest = hashlib.sha256()
    for name, data in sorted(parts.items()):
        encoded_name = name.encode("utf-8")
        digest.update(struct.pack(">II", len(encoded_name), len(data))); digest.update(encoded_name); digest.update(data)
    require(digest.hexdigest() == manifest.get("model-sha256"), "model/resource binding differs")
    for name, asset in (("fonts/Regular.ttf", "LiberationSans-Regular.ttf"), ("fonts/Bold.ttf", "LiberationSans-Bold.ttf"), ("licenses/Font.txt", "LiberationSans-OFL.txt")):
        require(parts[name] == (ROOT / "assets/fonts" / asset).read_bytes(), "explicit font/license changed")
    require(all(name in {"template.xml", "fonts/Regular.ttf", "fonts/Bold.ttf", "licenses/Font.txt"} or name.startswith("images/") for name in parts), "unexpected hidden source/resource")
    model = ET.fromstring(parts["template.xml"]); text = "".join(model.itertext()); xml = parts["template.xml"].decode("utf-8")
    require(model.get("literal-text") == "true" and "UNUSED-CLOSURE-SOURCE" not in xml, "authoring source returned")
    for token in ("BODY-A1 ${literal}", "INNER-" + expected, "CHAPTER-" + expected, "BODY-" + expected,
                  "CAPTION-" + expected, "BEFORE-INNER", "AFTER-INNER", "TARGET-CHAPTER", "PICTURE-CONTROL", "CROSS-PAGE=", "CROSS-NUMBER="):
        require(text.count(token) == 1, "combined content missing/duplicated: " + key + ": " + token)
    if expected != "ORIGINAL": require("ORIGINAL" not in xml, "removed original source returned")
    issues = manifest.findall("issue")
    if key == "PDF":
        require(not issues, "exact combined PDF contains losses")
        nav = model.find("navigation"); require(nav is not None and nav.get("headings") == "true" and nav.get("max-level") == "3", "portable heading declaration lost")
        toc = nav.find("contents"); require(toc.get("title") == "GAP24-CONTENTS" and toc.get("enabled") == "true", "contents declaration lost")
        require({n.get("part"): n.get("value") for n in toc.findall("color")} == {"TITLE": "#000000", "ENTRY": "#224488"}, "explicit/implicit appearance changed")
        require(toc.find("typography[@part='TITLE']").get("align") == "CENTER" and toc.find("typography[@part='PAGE_LABEL']") is None, "typography declaration intent changed")
        require(len(model.findall("notes/note")) == 2 and model.find(".//note-marker").get("target") == "foot", "note text/anchor/marker lost")
        require([s.get("first-page-number") for s in model.findall("section")] == ["2", "8"], "section page restart changed")
        require(model.find(".//cross-reference[@target='chapter-b']") is not None and model.find(".//number-reference[@target='step']") is not None, "cross-feature references lost")
        require(model.find(".//cell[@row-span='2']") is not None and len(model.findall(".//table")) == 2, "nested merged table lost")
        require(len([name for name in parts if name.startswith("images/")]) == 2, "combined image resources changed")
    else:
        require(model.find("navigation") is None and model.find("notes") is None and "GAP24-CONTENTS" not in xml
                and "FOOT-" + expected not in text and "END-" + expected not in text, "discarded source declarations returned")
        require(any(i.get("feature") == "NAVIGATION" for i in issues) and any(i.get("feature") == "MODEL_RECOVERY" for i in issues), "navigation/reimport loss missing")
        if key == "DOCX":
            require(len(model.findall("section")) == 2 and len(model.findall(".//table")) == 2, "native section/table structures lost")
            require(not model.findall(".//background/box") and not model.findall(".//foreground/box"), "discarded overlay returned")
        else:
            require(len(model.findall("section")) == 1 and model.find(".//image") is None, "Web actual current-file boundary differs")
    return parts["template.xml"]


def pdf(path, label, expected=None):
    reader = PdfReader(path, strict=True)
    logical = ["".join(page_logical(page)) + page.extract_text() for page in reader.pages]
    if expected:
        require(any("BODY-" + expected in value for value in logical), "PDF edited body missing: " + label)
        require(any("INNER-" + expected in value for value in logical), "PDF nested table edit missing: " + label)
    for page in reader.pages:
        fonts = page["/Resources"].get("/Font", {})
        for reference in fonts.values():
            font = reference.get_object()
            if "/DescendantFonts" in font: font = font["/DescendantFonts"][0].get_object()
            descriptor = font.get("/FontDescriptor")
            require(descriptor is not None and any(k in descriptor.get_object() for k in ("/FontFile", "/FontFile2", "/FontFile3")), "PDF uses an unembedded font")
    poppler, pdfium = render(path, OUTPUT / label)
    require(len(poppler) == len(pdfium) == len(reader.pages), "PDF render page inventory differs")
    results = []
    for index, (a, b) in enumerate(zip(poppler, pdfium)):
        value = difference(a, b, blur_radius=2)
        require(value["meanAbsoluteChannelError"] < 4 and value["changedPixelFraction"] < .06, "renderers diverge: " + label + ": " + str(index))
        results.append(value)
    return poppler, results


def main():
    parser = argparse.ArgumentParser(); parser.add_argument("--consumer-build-directory", type=Path, required=True)
    args = parser.parse_args(); OUTPUT.mkdir(parents=True, exist_ok=True)
    renders = []; results = {}; count = 0
    for key, extension in FORMATS.items():
        directory = SOURCE / key.lower(); models = {}
        for name in ("original", "cycle-3", "edited"):
            expected = "EDITED" if name == "edited" else "ORIGINAL"
            standard = (directory / (name + "." + extension)).read_bytes()
            payload = (directory / (name + "." + extension + ".recovery.zip")).read_bytes()
            models[name] = recovery(payload, standard, key, expected)
            require(embedded((directory / ("embedded-" + name + "." + extension)).read_bytes(), key) == (standard, payload), "embedded bytes/model changed")
            count += 1
        if key == "PDF": require(models["original"] == models["cycle-3"], "exact combined XML changed over cycles")
        for name, expected in (("model-control", "ORIGINAL"), ("model-edited", "EDITED")):
            label = key.lower() + "-" + name
            pages, values = pdf(directory / (name + ".pdf"), label, expected)
            renders.extend((label + "-" + str(i + 1), p) for i, p in enumerate(pages)); results[label] = values
        if key == "PDF":
            for name in ("original", "cycle-3", "edited"):
                label = "pdf-standard-" + name
                pages, values = pdf(directory / (name + ".pdf"), label, "EDITED" if name == "edited" else "ORIGINAL")
                renders.extend((label + "-" + str(i + 1), page) for i, page in enumerate(pages)); results[label] = values
            original = PdfReader(directory / "original.pdf"); edited = PdfReader(directory / "edited.pdf")
            require(original.outline[0]["/Title"] == "CHAPTER-ORIGINAL" and edited.outline[0]["/Title"] == "CHAPTER-EDITED", "restored contents uses stale heading text")
            require("GAP24-CONTENTS" in edited.pages[0].extract_text(), "persisted contents did not render automatically")
            links = [item.get_object() for item in edited.pages[0]["/Annots"]]
            require(len(links) == 2 and all(item.get("/Subtype") == "/Link" for item in links), "contents navigation links differ")
    matrix = list(csv.DictReader((SOURCE / "matrix.csv").open(encoding="utf-8")))
    require(len(matrix) == 16 and sum(row["exact_model"] == "true" for row in matrix) == 1, "combined matrix incomplete")
    require(all(int(r["first_losses"]) <= int(r["b_losses"]) <= int(r["final_losses"]) for r in matrix), "cumulative losses shrank")
    original, edited = unzip((SOURCE / "docx/original.docx").read_bytes()), unzip((SOURCE / "package-edited.docx").read_bytes())
    require(original.keys() == edited.keys() and [k for k in original if original[k] != edited[k]] == ["word/document.xml"], "unselected complex DOCX parts changed")
    require("PACKAGE-EDITED" in "".join(ET.fromstring(edited["word/document.xml"]).itertext()), "package actual edit missing")
    originals, _ = pdf(SOURCE / "page-original.pdf", "page-original")
    changed, _ = pdf(SOURCE / "page-edited.pdf", "page-edited")
    require(len(originals) == len(changed), "page-preserving edit changed pagination")
    for index, (a, b) in enumerate(zip(originals, changed)):
        with Image.open(a) as before, Image.open(b) as after:
            box = ImageChops.difference(before.convert("RGB"), after.convert("RGB")).getbbox()
            require((box is not None) == (index == 0), "unselected PDF page changed")
            if box: require(box[3] < 450, "text edit changed table, regions or overlays outside its text band")
    renders.extend(("page-original-" + str(i + 1), p) for i, p in enumerate(originals)); renders.extend(("page-edited-" + str(i + 1), p) for i, p in enumerate(changed))
    word = json.loads((SOURCE / "word/word-report.json").read_text(encoding="utf-8-sig"))
    require(word["edited"] and word["saved"] and word["reopened"] and word["wordVersion"], "real Word edit/save/reopen evidence missing")
    source = (SOURCE / "docx/embedded-original.docx").read_bytes(); saved = (SOURCE / "word/word-resaved.docx").read_bytes()
    require(word["sourceSha256"] == hashlib.sha256(source).hexdigest() and word["savedSha256"] == hashlib.sha256(saved).hexdigest(), "reader evidence is not bound to tested files")
    require(source != saved, "Word save did not change the file")
    require(not any("export4j-recovery" in name for name in unzip(saved)), "Word resave fixture unexpectedly retained recovery information")
    document = Document(io.BytesIO(saved)); current = "".join(n.text or "" for n in document.element.iter() if n.tag == W + "t")
    require("WORD-EDITED" in current and "ORIGINAL" not in current, "actual saved Word content differs")
    word_rows = list(csv.DictReader((SOURCE / "word/policy.csv").open(encoding="utf-8")))
    require({r["action"] for r in word_rows} == {"ERROR", "WARN", "IGNORE"} and all(r["recovered"] == "false" for r in word_rows), "reader resave policy coverage differs")
    policies = {row["action"]: row for row in word_rows}
    require(int(policies["WARN"]["losses"]) > 0 and policies["WARN"]["losses"] == policies["IGNORE"]["losses"]
            and int(policies["WARN"]["warnings"]) > 0 and int(policies["IGNORE"]["warnings"]) == 0, "reader losses/warnings differ from selected actions")
    current_models = []
    for action in ("WARN", "IGNORE"):
        xml = unzip((SOURCE / f"word/current-{action}.xml.zip").read_bytes())["template.xml"].decode("utf-8")
        current_models.append(xml)
        require("WORD-EDITED" in xml and "ORIGINAL" not in xml and "GAP24-CONTENTS" not in xml, "reader fallback revived source")
        current_model = ET.fromstring(xml)
        current_text = "".join(current_model.itertext())
        require(len(current_model.findall("section")) == 2 and len(current_model.findall(".//table")) == 2
                and "SHARED-WORD-EDITED" in current_text and "INNER-WORD-EDITED" in current_text
                and "CHAPTER-WORD-EDITED" in current_text and "PICTURE-CONTROL" in current_text, "reader fallback lost current body/band/table edits")
        pages, values = pdf(SOURCE / f"word/current-{action}.pdf", "word-" + action.lower())
        renders.extend(("word-" + action.lower() + "-" + str(i + 1), p) for i, p in enumerate(pages)); results["word-" + action.lower()] = values
    require(current_models[0] == current_models[1], "reader actions changed the actual current model")
    consumer = args.consumer_build_directory / "output"
    for name in ("consumer-closure", "consumer-closure-lossy"):
        pages, values = pdf(consumer / (name + ".pdf"), name, "CALLER")
        renders.extend((name + "-" + str(i + 1), p) for i, p in enumerate(pages)); results[name] = values
    check_bundle(args.consumer_build_directory)
    contact_sheet(renders, OUTPUT)
    report = {"combinedRecoveries": count, "matrixCases": len(matrix), "renderedPages": len(renders), "wordVersion": word["wordVersion"], "renderers": results}
    (OUTPUT / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps({k: v for k, v in report.items() if k != "renderers"}))


if __name__ == "__main__": main()
