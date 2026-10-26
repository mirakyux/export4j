# SPDX-License-Identifier: Apache-2.0
"""Independent semantic order, editable OOXML/HTML/Markdown, image pixels and PDF rendering."""
import base64
import csv
from html.parser import HTMLParser
from io import BytesIO
import json
import re
from pathlib import Path
from zipfile import ZipFile
import xml.etree.ElementTree as ET
from PIL import Image
from pypdf import PdfReader
import pypdfium2 as pdfium
from markdown_it import MarkdownIt
from verify_advanced_pdf import require
from verify_production import contact_sheet
from verify_pdf_raster import references
from verify_web_exports import Tree

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


def inline_anchor_outputs(directory):
    tiles = []
    for kind in ("image-only", "body"):
        stem = f"consumer-inline-anchor-{kind}"
        expected = "BEFORE-ANCHORAFTER-ANCHOR" if kind == "body" else ""
        for suffix in ("-source.pdf", ".pdf", ".docx", ".html", ".md"):
            path = directory / (stem + suffix)
            if suffix.endswith(".pdf"):
                reader = PdfReader(path); require(len(reader.pages) == 1, "inline anchor pagination changed")
                page = reader.pages[0]
                for reference in page["/Resources"]["/Font"].values():
                    font = reference.get_object()
                    require(font.get("/Subtype") == "/Type0", "inline anchor introduced a standard font")
                    descendant = font["/DescendantFonts"][0].get_object()
                    require(descendant.get("/Subtype") == "/CIDFontType2" and descendant["/FontDescriptor"].get_object().get("/FontFile2"), "inline anchor font program is not embedded TrueType")
                images = list(page.images); require(len(images) == 1, "inline anchor image count changed")
                image_pixels(images[0].data)
                with pdfium.PdfDocument(path) as pdf:
                    rendered_page = pdf[0]
                    try:
                        textpage = rendered_page.get_textpage()
                        try: text = textpage.get_text_range()
                        finally: textpage.close()
                    finally: rendered_page.close()
                for engine, pages in enumerate(references(path, OUTPUT / (stem + suffix.replace(".", "-")))):
                    tiles.extend([(f"{stem}{suffix} engine {engine+1}", rendered) for rendered in pages])
            elif suffix == ".docx":
                with ZipFile(path) as package:
                    document = ET.fromstring(package.read("word/document.xml"))
                    text = "".join(node.text or "" for node in document.iter(W + "t"))
                    media = [name for name in package.namelist() if name.startswith("word/media/")]
                    require(len(media) == 1, "inline anchor DOCX media count changed"); image_pixels(package.read(media[0]))
            else:
                value = path.read_text(encoding="utf-8")
                if suffix == ".md": value = MarkdownIt("commonmark").render(value)
                tree = Tree(value); text = "".join(node.text() for node in tree.root.find("p"))
                images = tree.root.find("img"); require(len(images) == 1, "inline anchor web image count changed")
                image_pixels(base64.b64decode(images[0].attrs["src"].split(",", 1)[1]))
            require(re.sub(r"[\s\uFFFC]+", "", text) == expected, "inline anchor leaked phantom text or lost surrounding body: " + path.name)
    return tiles


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
    tiles.extend(inline_anchor_outputs(consumer))
    contact_sheet(tiles, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps({"checks": "passed", "semantic_blocks": 4, "edited_table": "verified", "inline_anchor_round_trips": 8, "rendered_pdf_pages": 6, "output_formats": ["DOCX", "HTML", "Markdown", "PDF"]}, indent=2)+"\n")
    print("PDF semantic recovery: editable headings/table/image/order, model edits, four output formats, eight inline-image round trips and dual rendering passed")


if __name__ == "__main__": main()
