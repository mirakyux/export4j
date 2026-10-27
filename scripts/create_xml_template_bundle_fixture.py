# SPDX-License-Identifier: Apache-2.0
"""Create an ordinary ZIP with Python, independently of the Java template writer."""
from io import BytesIO
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED, ZIP_STORED
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build/verification/xml-template-bundle"


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    source = """<!-- 可编辑模板源码 -->
<document version="1" title="Template ${title}">
  <page width="420" height="500" margin="28"/>
  <heading level="1" size="20" line-height="28">CALLER-TEMPLATE ${title}</heading>
  <include ref="intro" prefix="intro"/>
  <if test="approved"><then><p>STATUS-APPROVED</p></then><else><p>STATUS-PENDING</p></else></if>
  <each items="items" as="item"><p>ITEM ${item.name}</p></each>
  <rows items="items" as="item"><columns><column weight="3"/><column weight="1"/></columns>
    <header><cell>Name</cell><cell>Count</cell></header>
    <row><cell>${item.name}</cell><cell>${item.count}</cell></row>
  </rows>
  <p line-height="32"><image ref="logo" width="48" height="24" alt="Synthetic blue logo"/></p>
  <p>END-TEMPLATE</p>
</document>
""".replace("\n", "\r\n")
    fragment = "<fragment version='1'><p>ORIGINAL-FRAGMENT ${title}</p></fragment>\r\n"
    png = BytesIO()
    Image.new("RGB", (48, 24), "#2255aa").save(png, format="PNG")
    with ZipFile(OUTPUT / "independent.zip", "w") as archive:
        archive.comment = b"Ordinary template ZIP created with Python standard library"
        for directory in ("fragments/", "images/", "fonts/", "licenses/"):
            archive.writestr(directory, b"", compress_type=ZIP_STORED)
        archive.writestr("template.xml", source.encode("utf-8"), compress_type=ZIP_DEFLATED)
        archive.writestr("fragments/intro.xml", fragment.encode("utf-8"), compress_type=ZIP_DEFLATED)
        archive.writestr("images/logo.png", png.getvalue(), compress_type=ZIP_STORED)
        archive.writestr("fonts/Regular.ttf", (ROOT / "assets/fonts/LiberationSans-Regular.ttf").read_bytes(), compress_type=ZIP_DEFLATED)
        archive.writestr("licenses/LiberationSans-OFL.txt", (ROOT / "assets/fonts/LiberationSans-OFL.txt").read_bytes(), compress_type=ZIP_DEFLATED)
    print("Independent ordinary XML template ZIP created")


if __name__ == "__main__":
    main()
