# SPDX-License-Identifier: Apache-2.0
"""Independently check note/reference recovery and explicit current-heading contents rebuilds."""
import argparse
import csv
import hashlib
import io
import json
from pathlib import Path
import struct
import xml.etree.ElementTree as ET

from docx import Document
from PIL import Image, ImageChops
from pypdf import PdfReader
from verify_adr_form import render
from verify_advanced_pdf import difference, require
from verify_complex_recovery import HtmlText, MARKDOWN, unzip
from verify_production import check_bundle, contact_sheet
from verify_section_recovery import embedded
from verify_semantic_tables import annotations, page_logical

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "examples/build/navigation-recovery"
OUTPUT = ROOT / "build/verification/navigation-recovery"
FORMATS = {"PDF": "pdf", "DOCX": "docx", "HTML": "html", "MARKDOWN": "md"}
WEB = {"HTML", "MARKDOWN"}
W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
SEMANTIC = "{urn:com.mirakyux.export4j:semantic:1}"


def contents(node):
    return "".join(node.itertext())


def body_tokens(expected):
    return [f"BODY-{expected} ${{literal}}", f"CAPTION-{expected}", "ANCHOR-END",
            "STEP-CONTROL", "ANCHOR-SECOND", "TARGET-FINAL", "CROSS-PAGE=", "CROSS-NUMBER="]


def recovery(encoded, standard, key, expected, retargeted=False):
    entries = unzip(encoded)
    manifest = ET.fromstring(entries.pop("recovery.xml"))
    require(manifest.get("version") == "1" and manifest.get("format") == key, "recovery format/version differs")
    require(int(manifest.get("bytes")) == len(standard)
            and manifest.get("sha256") == hashlib.sha256(standard).hexdigest(), "standard byte binding differs")
    digest = hashlib.sha256()
    for name, data in sorted(entries.items()):
        name = name.encode("utf-8")
        digest.update(struct.pack(">II", len(name), len(data))); digest.update(name); digest.update(data)
    require(digest.hexdigest() == manifest.get("model-sha256"), "model/resource binding differs")
    require(set(entries) == {"template.xml", "fonts/Regular.ttf", "licenses/Font.txt"}, "unexpected retained resources/source")
    for name, asset in (("fonts/Regular.ttf", "LiberationSans-Regular.ttf"), ("licenses/Font.txt", "LiberationSans-OFL.txt")):
        require(entries[name] == (ROOT / "assets/fonts" / asset).read_bytes(), "explicit font/license changed")
    model = ET.fromstring(entries["template.xml"])
    text = contents(model)
    for token in body_tokens(expected) + ["CHAPTER-" + expected, "DETAIL-CHAPTER", "TARGET-CHAPTER"]:
        require(text.count(token) == 1, "model missing/duplicated content: " + key + ": " + token)
    require(model.get("literal-text") == "true" and "UNUSED-NAVIGATION-SOURCE" not in text, "literal/source contract differs")
    require("RECOVERY-CONTENTS" not in text, "runtime contents configuration was unexpectedly serialized")
    if expected != "ORIGINAL": require("ORIGINAL" not in text, "old source text returned")
    ids = [n.get("id") for n in model.iter() if n.get("id")]
    require(len(ids) == len(set(ids)), "duplicate semantic IDs")
    require([n.get("level") for n in model.findall(".//heading")] == ["1", "2", "1"], "heading hierarchy changed")
    issues = manifest.findall("issue")
    if key not in WEB:
        require([n.get("id") for n in model.findall("section")] == ["a", "b"], "section IDs lost")
        require([n.get("first-page-number") for n in model.findall("section")] == ["3", "12"], "displayed page restarts lost")
        require({"heading-a", "detail", "heading-b", "body", "figure", "step", "inline", "cross-page", "cross-number"} <= set(ids), "block IDs lost")
        require(model.find(".//page-reference").get("target") == "heading-b"
                and model.find(".//number-reference").get("target") == "step", "native inline references changed")
    else:
        require(len(model.findall("section")) == 1, "Web flow unexpectedly restored source sections")
        require(not any(model.findall(".//" + tag) for tag in ("page-reference", "number-reference", "page-number", "page-count")), "Web restored native fields")
        require("[unresolved NOTE_MARKER]" in text, "Web marker degradation lost")
    notes = model.findall("notes/note")
    if key == "PDF":
        require(not issues, "exact PDF recovery reports losses")
        require([(n.get("id"), n.get("paragraph"), n.get("kind"), n.text) for n in notes] == [
            ("foot", "body", "FOOTNOTE", f"FOOT-{expected} ${{literal}}"),
            ("end", "figure", "ENDNOTE", f"END-{expected} ${{literal}}"),
            ("legacy-note", "legacy", "FOOTNOTE", "FOOT-LEGACY")], "note identity/anchor/kind/text changed")
        require(model.find(".//note-marker").get("target") == "foot", "explicit note target lost")
        require(model.find(".//caption").get("kind") == "Figure", "caption kind lost")
        refs = model.findall(".//cross-reference")
        require([(n.get("id"), n.get("kind"), n.get("target"), n.text) for n in refs] == [
            ("cross-page", "PAGE", "heading-a" if retargeted else "heading-b", "CROSS-PAGE="),
            ("cross-number", "NUMBER", "figure", "CROSS-NUMBER=")], "independent reference changed")
    else:
        require(not notes and not any(model.findall(".//" + tag) for tag in ("caption", "cross-reference", "note-marker")), "discarded semantics returned")
        require(not any(token in text for token in ("FOOT-", "END-" + expected)), "discarded note text returned")
        require(all(token in text for token in ("CROSS-PAGE=[unresolved reference]", "CROSS-NUMBER=[unresolved reference]")), "independent unresolved reference lost")
        require(any(n.get("feature") == "MODEL_RECOVERY" and n.find("effect").get("value") == "CURRENT_FILE_REIMPORT" for n in issues), "actual file reimport history missing")
        if key == "DOCX":
            markers = [n for n in issues if n.find("effect").get("value") == "NOTE_MARKER_REMOVED"]
            require(len(markers) == 1 and markers[0].get("treatment") == "DISCARD", "note marker discard report differs")
            location = markers[0].find("location")
            # Example edits retain earlier loss locations; the caller converts only after editing.
            marker_start = 22 if expected == "CALLER" else 24
            require((location.get("block"), location.get("start"), location.get("end"))
                    == ("body", str(marker_start), str(marker_start + 1)), "export-input marker UTF-16 range lost")
        if key in WEB: require(any(n.get("stage") == "IMPORT" for n in issues), "Web import history lost")
    return entries, manifest


def standard(data, key, expected):
    if key == "PDF": return
    if key in WEB:
        html = data.decode("utf-8") if key == "HTML" else MARKDOWN.render(data.decode("utf-8"))
        text = "".join(HtmlText(html).text)
        require(html.count("<h1") == 2 and html.count("<h2") == 1, "standard Web heading hierarchy differs")
    else:
        entries = unzip(data)
        doc = Document(io.BytesIO(data))
        require(len(doc.sections) == 2 and all(abs(s.page_width.pt - 420) < .01 and abs(s.page_height.pt - 480) < .01 for s in doc.sections), "native DOCX section geometry differs")
        require(not any("footnotes" in n or "endnotes" in n for n in entries), "DOCX retained removed note parts")
        root = ET.fromstring(entries["word/document.xml"])
        text = "".join(n.text or "" for n in root.iter(W + "t"))
        require([n.get(W + "instr").strip() for n in root.iter(W + "fldSimple")] == [
            "PAGEREF od_b_0 \\h", "REF od_b_1 \\w \\h", "NUMPAGES"], "native DOCX fields differ")
        owners = {}
        for p in root.iter(W + "p"):
            for bookmark in p.iter(W + "bookmarkStart"):
                owners[bookmark.get(W + "name")] = (bookmark.get(SEMANTIC + "owner"), "".join(n.text or "" for n in p.iter(W + "t")))
        require(owners == {"od_b_0": ("heading-b", "TARGET-CHAPTER"), "od_b_1": ("step", "STEP-CONTROL")}, "native bookmark target differs")
        require([n.get(W + "start") for n in root.iter(W + "pgNumType")] == ["3", "12"], "native displayed restarts differ")
        require(not any(list(root.iter(W + tag)) for tag in ("footnoteReference", "endnoteReference")), "native note markers remain")
    for token in body_tokens(expected): require(text.count(token) == 1, "standard content missing/duplicated: " + token)
    require(not any(token in text for token in ("FOOT-", "END-" + expected, "RECOVERY-CONTENTS")), "standard output retained discarded/runtime data")
    if expected != "ORIGINAL": require("ORIGINAL" not in text, "standard output has old text")


def pdf(path, label, key, expected, contacts, navigation=True, retargeted=False):
    reader = PdfReader(path, strict=True)
    count = 2 if key in WEB else (4 if navigation else 3)
    require(len(reader.pages) == count, "PDF page count differs: " + label)
    pages = [(p.extract_text() or "") + "\n" + "\n".join(page_logical(p)) for p in reader.pages]
    text = "\n".join(pages)
    for token in body_tokens(expected): require(text.count(token) == 1, "PDF content missing/duplicated: " + label + ": " + token)
    for token in ("CHAPTER-" + expected, "DETAIL-CHAPTER", "TARGET-CHAPTER"):
        require(text.count(token) == (2 if navigation else 1), "contents/body heading duplicated or missing")
    if expected != "ORIGINAL": require("ORIGINAL" not in text, "PDF has old source text")
    offset = int(navigation)
    destinations = []
    if navigation:
        destinations.extend([(0, 1), (0, 1 if key in WEB else 2), (0, 1 if key in WEB else 3)])
        require("RECOVERY-CONTENTS" in pages[0], "explicit contents page missing")
        for heading, number in zip(("CHAPTER-" + expected, "DETAIL-CHAPTER", "TARGET-CHAPTER"), ([2, 2, 2] if key in WEB else [3, 4, 12]), strict=True):
            require(f"{heading} {number}" in pages[0], "contents final displayed page differs")
        outline = reader.outline
        require(len(outline) == 3 and isinstance(outline[1], list) and len(outline[1]) == 1, "bookmark hierarchy differs")
        headings = [outline[0], outline[1][0], outline[2]]
        require([n.title for n in headings] == ["CHAPTER-" + expected, "DETAIL-CHAPTER", "TARGET-CHAPTER"], "bookmarks use stale headings")
        require([reader.get_destination_page_number(n) for n in headings] == ([1, 1, 1] if key in WEB else [1, 2, 3]), "bookmark physical page differs")
    else:
        require(not reader.outline and "RECOVERY-CONTENTS" not in text, "runtime navigation was implicitly restored")
    if key not in WEB:
        for index, p in enumerate(reader.pages):
            require((float(p.mediabox.width), float(p.mediabox.height)) == (420, 480), "PDF paper geometry differs")
            displayed = ([1] if navigation else []) + [3, 4, 12]
            require(f"FINAL={displayed[index]}/{count}" in page_logical(p), "final footer numbering differs")
        require(f"PAGE-TARGET=12 NUMBER-TARGET=4 TOTAL={count}" in page_logical(reader.pages[offset]), "final inline field values differ")
        destinations.extend([(offset, offset + 2), (offset, offset)])
        if key == "PDF":
            require(f"BODY-{expected} ${{literal}}[1] ANCHOR-END" in page_logical(reader.pages[offset]), "note marker body position differs")
            for index, token in ((offset, f"[1] FOOT-{expected} ${{literal}}"), (offset + 1, "[3] FOOT-LEGACY"), (offset + 2, f"[2] END-{expected} ${{literal}}")):
                require(pages[index].count(token) == 1 and text.count(token) == 1, "footnote/endnote page or content differs")
            require(f"Figure 1. CAPTION-{expected}[2]" in pages[offset], "caption numbering/note anchor differs")
            require("ANCHOR-SECOND[3]" in pages[offset + 1], "legacy note anchor differs")
            require(f"CROSS-PAGE={3 if retargeted else 12}" in pages[offset + 1]
                    and "CROSS-NUMBER=Figure 1" in pages[offset + 1], "final independent reference values differ")
            destinations.extend([(offset + 1, offset if retargeted else offset + 2), (offset + 1, offset)])
        else:
            require(not any(token in text for token in ("FOOT-", "END-" + expected, "[1]", "[2]", "[3]")), "DOCX restored discarded notes")
            require("CROSS-PAGE=[unresolved reference]" in text and "CROSS-NUMBER=[unresolved reference]" in text, "DOCX independent reference degradation differs")
    else:
        require("[unresolved NOTE_MARKER]" in text and "[unresolved REFERENCE_PAGE]" in text, "Web unresolved field text missing")
        require("FOOT-" not in text and "END-" + expected not in text, "Web restored discarded notes")
    links = annotations(reader)
    require([(n["pageIndex"], n.get("targetPageIndex")) for n in links] == destinations, "physical link targets/order differ: " + label)
    for link in links:
        p = reader.pages[link["targetPageIndex"]]
        require(0 <= link["destinationX"] <= float(p.mediabox.width) and 0 <= link["destinationY"] <= float(p.mediabox.height), "destination leaves target page")
    if key not in WEB:
        expected_y = ([28, 28, 28] if navigation else []) + [28, 100]
        if key == "PDF": expected_y += [28, 76]
        require(all(abs(link["destinationX"] - 28) < .001 and abs(link["destinationY"] - y) < .001
                    for link, y in zip(links, expected_y, strict=True)), "reference destination geometry differs")
    left, right = render(path, OUTPUT / label)
    metrics = []
    require(len(left) == len(right) == count, "renderer omitted pages")
    for index, (a, b) in enumerate(zip(left, right, strict=True)):
        delta = difference(a, b, blur_radius=2)
        require(delta["meanAbsoluteChannelError"] < 4 and delta["changedPixelFraction"] < .06, "independent render mismatch: " + label)
        metrics.append(delta); contacts.append((f"{label}-{index + 1}", a))
    return left, metrics


def exact_pixels(a, b):
    with Image.open(a) as x, Image.open(b) as y:
        return ImageChops.difference(x.convert("RGB"), y.convert("RGB")).getbbox() is None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--consumer-build-directory", type=Path, required=True)
    args = parser.parse_args()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    contacts, reports, controls, edited = [], {}, {}, {}
    for key, ext in FORMATS.items():
        directory, models, histories = SOURCE / key.lower(), {}, {}
        for stem in ("original", "cycle-3", "edited") + (() if key == "PDF" else ("ignored",)):
            expected = "EDITED" if stem == "edited" else "ORIGINAL"
            data = (directory / f"{stem}.{ext}").read_bytes()
            package = (directory / f"{stem}.{ext}.recovery.zip").read_bytes()
            entries, manifest = recovery(package, data, key, expected, stem == "edited")
            models[stem], histories[stem] = entries["template.xml"], manifest.findall("issue")
            require(all(n.get("action") == ("IGNORE" if stem == "ignored" else "WARN") for n in histories[stem]), "manifest action/history differs")
            plain, payload = embedded((directory / f"embedded-{stem}.{ext}").read_bytes(), key)
            require(plain == data and payload == package, "embedding changed accurate standard/model bytes")
            standard(data, key, expected)
        control = unzip((directory / "cycle-3.xml.zip").read_bytes())
        require(control["template.xml"] == models["original"], "XML control changed model")
        require(len(histories["cycle-3"]) >= len(histories["original"]), "target cycle dropped loss history")
        if key == "PDF": require(models["original"] == models["cycle-3"], "exact PDF cycle changed model")
        else:
            require(models["original"] == models["ignored"], "WARN/IGNORE changed model")
            require((directory / f"original.{ext}").read_bytes() == (directory / f"ignored.{ext}").read_bytes(), "WARN/IGNORE changed standard bytes")
        controls[key], reports[key.lower() + "-control"] = pdf(directory / "model-control.pdf", key.lower() + "-control", key, "ORIGINAL", contacts)
        edited[key], reports[key.lower() + "-edited"] = pdf(directory / "model-edited.pdf", key.lower() + "-edited", key, "EDITED", contacts, retargeted=key == "PDF")
    base, reports["source"] = pdf(SOURCE / "pdf/original.pdf", "source", "PDF", "ORIGINAL", contacts)
    require(all(exact_pixels(a, b) for a, b in zip(base, controls["PDF"], strict=True)), "unchanged PDF recovery changed pixels")
    require(not any(exact_pixels(a, b) for a, b in zip(base, edited["PDF"], strict=True)), "expected note/reference/contents edit absent on a page")
    _, reports["without-navigation"] = pdf(SOURCE / "without-runtime-navigation.pdf", "without-navigation", "PDF", "ORIGINAL", contacts, navigation=False)
    matrix = list(csv.DictReader((SOURCE / "matrix.csv").open(encoding="utf-8")))
    require(len(matrix) == 16 and {(r["a"], r["b"]) for r in matrix} == {(a, b) for a in FORMATS for b in FORMATS}, "A-B-A matrix incomplete")
    for row in matrix:
        require((row["exact_model"] == "true") == (row["a"] == row["b"] == "PDF"), "A-B-A preservation boundary differs")
        require(int(row["first_losses"]) <= int(row["b_losses"]) <= int(row["final_losses"]), "A-B-A history shrank")
    policies = list(csv.DictReader((SOURCE / "policy.csv").open(encoding="utf-8")))
    for key in FORMATS:
        rows = {r["action"]: r for r in policies if r["format"] == key}
        require(set(rows) == ({"WARN"} if key == "PDF" else {"ERROR", "WARN", "IGNORE"}), "policy action coverage missing")
        if key == "PDF": require(rows["WARN"]["total"] == "0" and rows["WARN"]["notes"] == "3", "exact policy differs")
        else:
            require(rows["WARN"]["total"] == rows["IGNORE"]["total"] == rows["WARN"]["warnings"]
                    and int(rows["WARN"]["total"]) > 0 and rows["IGNORE"]["warnings"] == "0"
                    and rows["WARN"]["notes"] == rows["IGNORE"]["notes"] == "0", "loss/warning/note policy differs")
    caller = args.consumer_build_directory / "output"
    for key, ext in (("PDF", "pdf"), ("DOCX", "docx")):
        plain, payload = embedded((caller / ("consumer-navigation." + ext)).read_bytes(), key)
        recovery(payload, plain, key, "CALLER", retargeted=True); standard(plain, key, "CALLER")
    for label, filename, key, nav in (("consumer", "consumer-navigation.pdf", "PDF", True),
            ("consumer-plain", "consumer-navigation-plain.pdf", "PDF", False),
            ("consumer-lossy", "consumer-navigation-lossy.pdf", "DOCX", True)):
        _, reports[label] = pdf(caller / filename, label, key, "CALLER", contacts, navigation=nav, retargeted=True)
    contact_sheet(contacts, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps({"matrix": 16, "exact_pairs": 1, "policies": policies,
        "pdf_pages": len(contacts), "renders": reports, "localBundle": check_bundle(args.consumer_build_directory)}, indent=2), encoding="utf-8")
    print(f"Verified notes, references, explicit contents rebuild, 16 A-B-A cases and {len(contacts)} PDF pages with two renderers")


if __name__ == "__main__":
    main()
