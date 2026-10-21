# SPDX-License-Identifier: Apache-2.0
"""Independently verify the XML-authored report, semantic OOXML and isolated public API output.

Use the pinned project Python environment after :examples:xmlTemplates and the isolated
consumer. Source contracts below are independent of Java layout. All PDF pages are rendered
by Poppler and PDFium; no visual baseline is created or changed.
"""
from __future__ import annotations

import argparse
from importlib import metadata
import io
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from PIL import Image
from pypdf import PdfReader
from pypdf.generic import TextStringObject
from verify_adr_form import render
from verify_advanced_pdf import difference, require
from verify_authoring import glyph_advances
from verify_inline_images import compose, image_boxes
from verify_multi_floats import point
from verify_production import actual_text, contact_sheet
from verify_semantic_tables import check_characters, close, page_logical, table_borders

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "examples/build/xml-templates"
OUTPUT = ROOT / "build/verification/xml-templates"
SVG = "{http://www.w3.org/2000/svg}"
W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
WP = "{http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing}"
A = "{http://schemas.openxmlformats.org/drawingml/2006/main}"
R = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}"
TITLE = "青禾观察站月度巡检报告"
AREAS = ("东门仓", "西侧库", "南楼层", "北通道", "中央厅")
OBSERVATION_TEXT = ("现场标识已核对。", "通行路线保持畅通。", "设备外观未见明显损坏。", "台账与现场编号一致。",
                    "发现一处标签需更新。", "责任人已确认处理日期。", "复查将在下月首周完成。", "原始记录已归档备查。", "后续按计划持续跟踪。")
OBSERVATIONS = [f"OBS{record:02}-{line:02}" for record in range(1, 6) for line in range(1, 10)]
LABELS = [TITLE, "报告期间：2026年9月；编制单位：青禾观察站（虚构）。",
          "本期完成五个区域巡检，记录与处理建议如下。", "处理结论：需按计划复查。",
          "发现事项已落实到责任人。", "本期重点事项", "重点一：对照记录核查现场标识。",
          "重点二：由责任人跟踪复查安排。", "巡检记录与处理建议",
          "复核说明：以上内容均为虚构演示，不作为实际业务结论。"]


def page_text(reader):
    return [(page.extract_text() or "") + "\n" + "\n".join(page_logical(page)) for page in reader.pages]


def compact(text):
    return "".join(text.split())


def ids(text):
    return re.findall(r"OBS\d{2}-\d{2}", text)


def observation_lines(record):
    return [f"OBS{record:02}-{line:02} " + (AREAS[record - 1] + "：" if line == 1 else "")
            + OBSERVATION_TEXT[line - 1] for line in range(1, 10)]


def check_rich_span(reader, source, margin):
    """Verify actual PDF text color/CIDs and the separately stroked underline, without Java manifests."""
    blue = (0x22 / 255, 0x44 / 255, 0x88 / 255)
    advances = iter(glyph_advances(reader))
    spans, underlines = [], []
    for page_index, page in enumerate(reader.pages):
        matrix, fill, stroke, line_width = (1, 0, 0, 1, 0, 0), (0, 0, 0), (0, 0, 0), 1
        stack, path = [], []
        font, size = None, 0
        for operands, operator in page.get_contents().operations:
            if operator == b"q":
                stack.append((matrix, fill, stroke, line_width, font, size))
            elif operator == b"Q":
                matrix, fill, stroke, line_width, font, size = stack.pop()
            elif operator == b"cm":
                matrix = compose(matrix, tuple(float(value) for value in operands))
            elif operator == b"rg":
                fill = tuple(float(value) for value in operands)
            elif operator == b"RG":
                stroke = tuple(float(value) for value in operands)
            elif operator == b"w":
                line_width = float(operands[0])
            elif operator == b"Tf":
                font = page["/Resources"]["/Font"][operands[0]].get_object()
                size = float(operands[1])
            elif operator == b"TJ":
                placement = next(advances)
                require(placement["page"] == page_index, "PDF text advance traversal lost its page")
                if all(abs(a - b) < .00001 for a, b in zip(fill, blue, strict=True)):
                    cmap = font["/ToUnicode"].get_object().get_data().decode("ascii")
                    mapping = {}
                    for block in re.findall(r"beginbfrange(.*?)endbfrange", cmap, re.S):
                        for line in block.strip().splitlines():
                            match = re.fullmatch(r"\s*<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>\s*", line)
                            require(match is not None, "fixture uses an unsupported CID range mapping")
                            first, last, value = (int(item, 16) for item in match.groups())
                            mapping.update((cid, chr(value + cid - first)) for cid in range(first, last + 1))
                    text = []
                    for value in operands[0]:
                        if isinstance(value, (str, bytes)):
                            data = value.original_bytes if isinstance(value, TextStringObject) else bytes(value)
                            require(len(data) % 2 == 0, "colored CID string has odd byte count")
                            text.extend(mapping[int.from_bytes(data[i:i + 2], "big")] for i in range(0, len(data), 2))
                    spans.append({**placement, "source": "".join(text), "fontSize": size})
            elif operator == b"m":
                path = [point(matrix, *(float(value) for value in operands))]
            elif operator == b"l":
                path.append(point(matrix, *(float(value) for value in operands)))
            elif operator == b"S" and all(abs(a - b) < .00001 for a, b in zip(stroke, blue, strict=True)):
                require(len(path) == 2, "rich span underline is not one straight painted segment")
                underlines.append({"page": page_index, "x": path[0][0], "right": path[1][0],
                                   "y": float(page.mediabox.height) - path[0][1],
                                   "endY": float(page.mediabox.height) - path[1][1], "width": line_width})
    require(next(advances, None) is None, "PDF text advance traversal left an unmatched run")
    require(len(spans) == len(underlines) == 1 and spans[0]["source"] == source,
            "rich conditional span text/color/underline lost, changed or repeated")
    span, underline = spans[0], underlines[0]
    require(span["page"] == underline["page"] == 0, "rich conditional span moved from its actual first page")
    require(margin <= span["x"] < span["right"] <= float(reader.pages[0].mediabox.width) - margin,
            "rich span leaves the physical page body")
    close(underline["x"], span["x"], "underline does not start at its actual colored text")
    close(underline["right"], span["right"], "underline does not end at its actual CID/TJ advance")
    close(underline["y"], underline["endY"], "rich span underline is not horizontal")
    close(underline["y"], span["baseline"] + span["fontSize"] / 10, "underline baseline offset differs")
    close(underline["width"], span["fontSize"] / 18, "underline stroke width differs")
    return {"text": span, "underline": underline}


def check_pdf(path):
    reader = PdfReader(path)
    require(len(reader.pages) == 3 and not reader.get_fields(), "report must remain three static PDF pages")
    require(reader.metadata.title == TITLE and reader.metadata.author == "青禾观察站（虚构）",
            "bound report title/author metadata differs")
    texts = page_text(reader)
    text = "\n".join(texts)
    for page in reader.pages:
        close(page.mediabox.width, 420, "report physical page width differs")
        close(page.mediabox.height, 480, "report physical page height differs")
        require(not page.get("/Annots"), "XML report unexpectedly contains interactions")
    require(ids(text) == OBSERVATIONS, "dynamic original rows/lines lost, repeated or reordered")
    require(re.findall(r"OBS\d{2}-\d{2} [^\n]*", text)
            == [line for record in range(1, 6) for line in observation_lines(record)],
            "final PDF dynamic observation text changed or lost")
    for label in LABELS:
        require(compact(text).count(compact(label)) == 1, "bound report label missing, duplicated or changed")
    require("本期无需追加复查" not in text and "${" not in text, "inactive branch or unresolved variable leaked to PDF")
    require(all(text.count(f"QH-{record:02}") == 1 for record in range(1, 6)), "dynamic record keys lost or repeated")
    require(TITLE in texts[0] and "复核说明" in texts[-1], "report opening/closing source moved to wrong pages")
    for page in texts:
        require(len(re.findall(r"(?m)^编号[ \t]+巡检记录[ \t]*$", page)) == 1,
                "dynamic table must repeat one complete header on every occupied page")
    # A dynamic source row must really cross a physical page, rather than moving every row whole.
    pages_by_record = {record: {page for page, text in enumerate(texts) if re.search(rf"OBS{record:02}-\d{{2}}", text)}
                       for record in range(1, 6)}
    require(any(len(pages) > 1 for pages in pages_by_record.values()), "fixture did not exercise complete-line dynamic row splitting")
    images = image_boxes(reader)
    require(len(images) == 2 and [item["page"] for item in images] == [0, 2], "explicit repeated image lost, duplicated or moved")
    for item in images:
        require(item["pixels"] == [8, 8] and not item["alpha"], "explicit PNG decoded pixel contract differs")
        close(item["width"], 12, "explicit image width differs")
        close(item["height"], 12, "explicit image height differs")
        require(28 <= item["x"] < item["x"] + 12 <= 392 and 28 <= item["y"] < item["y"] + 12 <= 452,
                "inline image leaves actual report page margins")
    borders = table_borders(reader)
    require(borders and all(28 <= item["x"] < item["x"] + item["width"] <= 392.003
                           and 28 <= item["y"] < item["y"] + item["height"] <= 452.003 for item in borders),
            "dynamic table painted cell leaves the actual body region")
    for page in range(3):
        cells = [item for item in borders if item["pageIndex"] == page]
        require(cells, "an occupied PDF page lost its table cell borders")
        top = min(item["y"] for item in cells)
        header = [item for item in cells if abs(item["y"] - top) < .003]
        require(len(header) == 2, "repeated header does not have both original cells")
        for cell, x, width in zip(header, (28, 100), (72, 292), strict=True):
            close(cell["x"], x, "declared table column origin differs")
            close(cell["width"], width, "fixed/fraction table column width differs")
            close(cell["height"], 30, "declared repeating header line/padding height differs")
    rich_span = check_rich_span(reader, "处理结论：需按计划复查。", 28)
    return reader, {"pages": 3, "sourceLines": 45, "dynamicRecords": 5,
                    "recordPhysicalPages": {str(key): sorted(value) for key, value in pages_by_record.items()},
                    "images": images, "paintedCellBorders": len(borders), "richSpan": rich_span,
                    "characters": check_characters(path, 28), "branchAndBindingsChecked": True}


def docx_text(node):
    result = []
    for element in node.iter():
        if element.tag == W + "t":
            result.append(element.text or "")
        elif element.tag == W + "br":
            result.append("\n")
    return "".join(result)


def check_docx(path):
    with ZipFile(path) as archive:
        require(archive.testzip() is None, "DOCX package checksum differs")
        main = ET.fromstring(archive.read("word/document.xml"))
        text = docx_text(main)
        require(ids(text) == OBSERVATIONS and "${" not in text, "DOCX dynamic source order/binding differs")
        require("本期无需追加复查" not in text, "inactive XML branch leaked to DOCX")
        for label in LABELS:
            require(compact(text).count(compact(label)) == 1, "DOCX report label missing or duplicated")
        tables = main.findall(".//" + W + "tbl")
        require(len(tables) == 1, "declarative rows did not export as one standard Word table")
        rows = tables[0].findall(W + "tr")
        require(len(rows) == 6, "dynamic source rows were serialized as PDF presentation fragments")
        require(rows[0].find(W + "trPr/" + W + "tblHeader") is not None, "standard DOCX repeated-header property missing")
        require(all(row.find(W + "trPr/" + W + "cantSplit") is None for row in rows[1:]),
                "explicit dynamic row splitting was lost from standard DOCX")
        for record, row in enumerate(rows[1:], 1):
            cells = row.findall(W + "tc")
            require(len(cells) == 2 and docx_text(cells[0]) == f"QH-{record:02}", "DOCX dynamic row identity differs")
            expected = "\n".join(observation_lines(record))
            require(docx_text(cells[1]) == expected, "DOCX original complete-line long cell differs")
        colored = [run for run in main.iter(W + "r") if run.find(W + "rPr/" + W + "color") is not None
                   and run.find(W + "rPr/" + W + "color").get(W + "val") == "224488"]
        require(len(colored) == 1 and docx_text(colored[0]) == "处理结论：需按计划复查。"
                and colored[0].find(W + "rPr/" + W + "u") is not None,
                "standard DOCX rich conditional span/color/underline differs")
        properties = main.findall(".//" + WP + "docPr")
        require(len(properties) == 2 and len({item.get("id") for item in properties}) == 2
                and all(item.get("descr") == "蓝色巡检标记" for item in properties), "inline image descriptions/unique drawing IDs lost")
        blips = main.findall(".//" + A + "blip")
        require(len(blips) == 2 and len({item.get(R + "embed") for item in blips}) == 1, "same explicit image resource was not deduplicated")
        media = [name for name in archive.namelist() if name.startswith("word/media/")]
        require(len(media) == 1, "explicit report image must use one standard embedded media part")
        with Image.open(io.BytesIO(archive.read(media[0]))) as image:
            require(image.format == "PNG" and image.size == (8, 8), "standard DOCX embedded PNG changed")
        relations = ET.fromstring(archive.read("word/_rels/document.xml.rels"))
        require(not any(item.get("TargetMode") == "External" for item in relations), "template introduced an external resource relation")
        require(not main.findall(".//" + W + "fldSimple") and not main.findall(".//" + W + "hyperlink"),
                "XML report introduced native fields or hyperlinks")
        page = main.find(".//" + W + "pgSz")
        require(page is not None and page.get(W + "w") == "8400" and page.get(W + "h") == "9600",
                "DOCX explicit page geometry differs")
    return {"tables": 1, "originalTableRows": 6, "images": 2, "embeddedMedia": 1,
            "repeatedHeaderPropertyChecked": True, "sourceAndStyleChecked": True}


def check_svg(reader):
    main = ET.parse(FIXTURES / "report.svg").getroot()
    require(len(main.findall(SVG + "svg")) == len(reader.pages), "SVG omitted a report page")
    require(len(main.findall(".//" + SVG + "image")) == 2, "SVG explicit image count differs")
    require(not main.findall(".//" + SVG + "a"), "static XML report unexpectedly contains links")
    values = []
    for element in main.iter():
        if element.tag == SVG + "text":
            values.append("".join(element.itertext()))
        elif element.tag == SVG + "g" and element.get("aria-label") is not None:
            values.append(element.get("aria-label"))
    text = "\n".join(values)
    require(ids(text) == OBSERVATIONS, "SVG dynamic original source order differs")
    for label in LABELS:
        require(compact(text).count(compact(label)) == 1, "SVG bound report label missing or duplicated")
    marked = actual_text(reader)
    require([value for value in values if value in set(marked)] == marked, "SVG marked logical source order differs from final PDF")
    return {"pages": len(reader.pages), "images": 2, "originalSourceOrderChecked": True}


def check_consumer(path):
    reader = PdfReader(path)
    require(len(reader.pages) == 1 and not reader.get_fields(), "isolated XML consumer must remain one static PDF page")
    page = reader.pages[0]
    close(page.mediabox.width, 320, "consumer physical page width differs")
    close(page.mediabox.height, 260, "consumer physical page height differs")
    require(not page.get("/Annots"), "consumer XML template unexpectedly contains interactions")
    text = page_text(reader)[0]
    for label in ("CONSUMER-TEMPLATE", "CONSUMER-REVIEW-NO", "CONSUMER-TEMPLATE-END", "VALUE-A", "VALUE-B", "Key", "Value"):
        expected = 2 if label == "CONSUMER-TEMPLATE" else 1
        require(text.count(label) == expected, "consumer template label or selected false branch differs")
    require(text.count("ITEM-A") == text.count("ITEM-B") == 2 and "CONSUMER-REVIEW-YES" not in text and "${" not in text,
            "isolated each/rows/false-branch binding differs")
    pictures = image_boxes(reader)
    require(len(pictures) == 1 and pictures[0]["pixels"] == [2, 2], "consumer explicit image lost or changed")
    close(pictures[0]["width"], 12, "consumer explicit image width differs")
    close(pictures[0]["height"], 12, "consumer explicit image height differs")
    rich_span = check_rich_span(reader, "CONSUMER-REVIEW-NO", 16)
    return {"pages": 1, "images": 1, "characters": check_characters(path, 16),
            "richSpan": rich_span, "publicXmlCompilerAndFalseBranchChecked": True}


def render_checked(path, name, result, contacts):
    poppler, pdfium = render(path, OUTPUT / name)
    require(len(poppler) == len(pdfium) == result["pages"], "independent renderer omitted a physical page")
    metrics = [difference(first, second, 2) for first, second in zip(poppler, pdfium, strict=True)]
    require(all(item["meanAbsoluteChannelError"] < 4 and item["changedPixelFraction"] < .06 for item in metrics),
            "independent PDF renderers disagree")
    contacts.extend((f"{name} page {index + 1}", path) for index, path in enumerate(poppler))
    result["rendererDifferences"] = metrics


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--consumer-build-directory", type=Path)
    args = parser.parse_args()
    versions = {name: metadata.version(name) for name in ("pypdf", "pypdfium2", "Pillow")}
    require(versions == {"pypdf": "6.10.0", "pypdfium2": "5.13.0", "Pillow": "12.3.0"}, "use pinned project Python tools")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    reader, report = check_pdf(FIXTURES / "report.pdf")
    result = {"tools": versions, "report": report, "docx": check_docx(FIXTURES / "report.docx"), "svg": check_svg(reader)}
    contacts = []
    render_checked(FIXTURES / "report.pdf", "report", report, contacts)
    consumer_directory = args.consumer_build_directory
    if consumer_directory is None:
        pointer = ROOT / "build/verification/consumer-build-directory.txt"
        require(pointer.is_file(), "run isolated consumer or pass --consumer-build-directory")
        consumer_directory = Path(pointer.read_text(encoding="utf-8").strip())
    if not consumer_directory.is_absolute():
        consumer_directory = ROOT / consumer_directory
    consumer = consumer_directory / "output/consumer-template.pdf"
    require(consumer.is_file(), "successful isolated XML-template consumer output is missing")
    consumer_result = check_consumer(consumer)
    render_checked(consumer, "consumer", consumer_result, contacts)
    result["consumerBuildDirectory"] = str(consumer_directory)
    result["consumer"] = consumer_result
    contact_sheet(contacts, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
