# SPDX-License-Identifier: Apache-2.0
"""Independent semantic order, editable OOXML/HTML/Markdown, image pixels and PDF rendering."""
import base64
import csv
from html.parser import HTMLParser
from io import BytesIO
import json
from pathlib import Path
from zipfile import ZipFile
import xml.etree.ElementTree as ET
from PIL import Image
from pypdf import PdfReader
import pypdfium2 as pdfium
from verify_advanced_pdf import require
from verify_production import contact_sheet
from verify_pdf_raster import references

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build/verification/pdf-semantic-import"
W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"


class Html(HTMLParser):
    def __init__(self):
        super().__init__(); self.tags = []; self.images = []; self.parts = []
    def handle_starttag(self, tag, attrs):
        self.tags.append(tag)
        if tag == "img": self.images.append(dict(attrs)["src"])
    def handle_data(self, data): self.parts.append(data)


def image_pixels(encoded):
    with Image.open(BytesIO(encoded)) as image:
        require(image.size == (8,6) and set(image.convert("RGB").get_flattened_data()) == {(34,102,170)}, "recovered source image pixels changed")


def main():
    sample = ROOT / "examples/build/pdf-semantic-import"
    consumer = Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip()) / "output"
    original = PdfReader(sample / "source.pdf").pages[0].extract_text()
    require(all(value in original for value in ("RECOVERY TITLE", "BODY-RECOVERY", "NAME", "VALUE", "ALPHA", "42")), "PDF source fixture missing content")
    with ZipFile(sample / "recovered.docx") as package:
        document = ET.fromstring(package.read("word/document.xml")); body = document.find(W+"body")
        require([node.tag for node in body if node.tag != W+"sectPr"] == [W+"p", W+"p", W+"tbl", W+"p"], "semantic OOXML block order")
        paragraphs = list(body.findall(W+"p")); require(paragraphs[0].find(W+"pPr/"+W+"pStyle").get(W+"val") == "Heading1", "editable heading style missing")
        rows = body.find(W+"tbl").findall(W+"tr")
        text = [["".join(cell.itertext()) for cell in row.findall(W+"tc")] for row in rows]
        require(text == [["NAME","VALUE"],["EDITED-CELL","42"]], "editable table cells not recovered/edited")
        media = [name for name in package.namelist() if name.startswith("word/media/")]; require(len(media) == 1, "recovered DOCX media count"); image_pixels(package.read(media[0]))
    html = Html(); html.feed((sample / "recovered.html").read_text())
    require("h1" in html.tags and html.tags.count("tr") == 2 and html.tags.count("td") == 2 and html.tags.count("th") == 2 and len(html.images) == 1, "HTML editable semantic elements")
    image_pixels(base64.b64decode(html.images[0].split(",",1)[1]))
    markdown = (sample / "recovered.md").read_text().replace("\\-", "-")
    require(markdown.startswith("# RECOVERY TITLE\n") and "| EDITED-CELL | 42 |" in markdown and "ALPHA" not in markdown, "Markdown heading/table edit missing")
    recovered = PdfReader(sample / "recovered.pdf")
    text = "\n".join(page.extract_text() for page in recovered.pages)
    require(all(value in text for value in ("RECOVERY TITLE", "BODY-RECOVERY", "NAME", "VALUE", "EDITED-CELL", "42")) and "ALPHA" not in text, "reflowed semantic PDF text")
    with pdfium.PdfDocument(sample / "recovered.pdf") as document:
        page = document[0]
        try:
            textpage = page.get_textpage()
            try: require("EDITED-CELL" in textpage.get_text_range(), "independent PDFium recovered text")
            finally: textpage.close()
        finally: page.close()
    rows = list(csv.DictReader((sample / "sources.tsv").open(), delimiter="\t"))
    require(len(rows) == 4 and len({row["block"] for row in rows}) == 4, "source block mapping count/identity")
    require([float(rows[-1][key]) for key in ("x","y","width","height")] == [20,200,40,30], "placed image source transform")
    for extension in ("docx", "html", "md", "pdf"):
        issues = [line.split("\t") for line in (sample / f"recovered.{extension}.losses.tsv").read_text().splitlines()]
        require({row[1] for row in issues if row[0] == "IMPORT"} == {"PDF_LAYOUT", "PDF_FONT_MAPPING", "PDF_VECTOR_GRAPHICS", "DOCUMENT_METADATA"}, "import loss report reconciliation")
    with ZipFile(consumer / "consumer-recovered.docx") as package: require("CALLER-RECOVERED" in package.read("word/document.xml").decode(), "isolated DOCX recovery")
    for extension in ("html", "md"): require("CALLER" in (consumer / f"consumer-recovered.{extension}").read_text(), "isolated web recovery")
    tiles = []
    for name in ("source", "recovered"):
        for engine, pages in enumerate(references(sample / f"{name}.pdf", OUTPUT / name)):
            tiles.extend([(f"{name} engine {engine+1} page {index+1}", page) for index,page in enumerate(pages)])
    contact_sheet(tiles, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps({"checks": "passed", "semantic_blocks": 4, "edited_table": "verified", "output_formats": ["DOCX", "HTML", "Markdown", "PDF"]}, indent=2)+"\n")
    print("PDF semantic recovery: editable headings/table/image/order, model edits, four output formats and dual rendering passed")


if __name__ == "__main__": main()
