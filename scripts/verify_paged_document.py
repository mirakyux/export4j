# SPDX-License-Identifier: Apache-2.0
"""Compare complete and page-at-a-time mixed document exports using independent PDF tools."""
import argparse
from contextlib import closing
import hashlib
import json
from pathlib import Path
import re

from PIL import Image, ImageChops
from pypdf import PdfReader
from pypdf.generic import NullObject
from verify_advanced_pdf import difference, require
from verify_pdf_raster import references
from verify_production import contact_sheet
from verify_variable_tables import logical_pages

ROOT = Path(__file__).resolve().parents[1]


def point(values):
    return tuple(None if value is None or isinstance(value, NullObject) else round(float(value), 3)
                 for value in values)


def xyz(values):
    left, top, zoom = point(values)
    # ISO 32000 XYZ destinations define zoom 0 as the same "retain zoom" operation as null.
    return left, top, None if zoom == 0 else zoom


def destination(reader, value):
    if hasattr(value, "get_object"):
        value = value.get_object()
    target = next(index for index, page in enumerate(reader.pages)
                  if page.indirect_reference == value[0])
    require(str(value[1]) == "/XYZ", "navigation destination is not XYZ")
    return target, xyz(value[2:])


def image_records(resources, active=None):
    active = set() if active is None else active
    resources = resources.get_object()
    records = []
    objects = resources.get("/XObject", {})
    if hasattr(objects, "get_object"):
        objects = objects.get_object()
    for reference in objects.values():
        obj = reference.get_object()
        identity = id(obj)
        require(identity not in active, "cyclic image/form resources")
        if obj.get("/Subtype") == "/Form":
            if "/Resources" in obj:
                records.extend(image_records(obj["/Resources"], active | {identity}))
        elif obj.get("/Subtype") == "/Image":
            mask = obj.get("/SMask")
            mask_hash = hashlib.sha256(mask.get_object().get_data()).hexdigest() if mask else ""
            records.append((int(obj["/Width"]), int(obj["/Height"]), str(obj.get("/ColorSpace")),
                            int(obj["/BitsPerComponent"]), hashlib.sha256(obj.get_data()).hexdigest(), mask_hash))
    return sorted(set(records))


def inspect(path, counts):
    texts = logical_pages(path)
    with closing(PdfReader(path, strict=True)) as reader:
        require(len(reader.pages) == counts["full"]["pages"] == len(texts), "final page count differs")
        page_sizes, links, images, fonts = [], [], [], []
        for index, page in enumerate(reader.pages):
            size = float(page.mediabox.width), float(page.mediabox.height)
            page_sizes.append(size)
            require(page["/Parent"]["/Type"] == "/Pages", "page has no valid parent tree")
            resources = page["/Resources"]
            page_fonts = set()
            font_dictionary = resources.get("/Font", {})
            if hasattr(font_dictionary, "get_object"):
                font_dictionary = font_dictionary.get_object()
            for reference in font_dictionary.values():
                font = reference.get_object()
                leaf = font["/DescendantFonts"][0].get_object() if "/DescendantFonts" in font else font
                descriptor = leaf.get("/FontDescriptor")
                require(descriptor is not None and any(key in descriptor.get_object()
                        for key in ("/FontFile", "/FontFile2", "/FontFile3")), "font program is not embedded")
                page_fonts.add(str(font["/BaseFont"]).split("+")[-1])
            fonts.append(sorted(page_fonts))
            images.append(image_records(resources))
            for reference in page.get("/Annots", []):
                annotation = reference.get_object()
                require(annotation.get("/Subtype") == "/Link", "unexpected annotation type")
                rect = point(annotation["/Rect"])
                require(-.003 <= rect[0] < rect[2] <= size[0] + .003
                        and -.003 <= rect[1] < rect[3] <= size[1] + .003, "link rectangle leaves page")
                if "/P" in annotation:
                    require(annotation.raw_get("/P") == page.indirect_reference, "annotation owns a different page")
                if "/Dest" in annotation:
                    links.append((index, rect, "internal", destination(reader, annotation["/Dest"])))
                else:
                    action = annotation["/A"]
                    require(action["/S"] == "/URI", "unexpected active action")
                    links.append((index, rect, "external", str(action["/URI"])))

        def outline(items):
            result = []
            for item in items:
                if isinstance(item, list):
                    result.append(outline(item))
                else:
                    result.append((item.title, reader.get_destination_page_number(item),
                                   str(item.typ), xyz([item.left, item.top, item.zoom])))
            return result

        bookmarks = outline(reader.outline)
    joined = "\n".join(texts)
    compact_text = "".join(joined.split())
    for marker in counts["uniqueMarkers"] + counts["footnoteMarkers"]:
        require(compact_text.count(marker) == 1,
                f"source marker lost or repeated: {marker}")
    for marker in counts["repeatingMarkers"]:
        require(joined.count(marker) >= 2, f"repeated header missing: {marker}")
    require(re.findall(r"NOTE\d{3}", compact_text) == counts["footnoteMarkers"], "continued footnote order differs")
    require(re.findall(r"NEST\d{3}", compact_text) == [f"NEST{index:03}" for index in range(1, 43)],
            "nested paragraph order differs")
    require(re.findall(r"ROW\d{3}", compact_text) == [f"ROW{index:03}" for index in range(1, 25)],
            "table body order differs")
    uris = {link[3] for link in links if link[2] == "external"}
    require(set(counts["externalUris"]) <= uris, "source image/text URI is missing")
    require(bookmarks and any(link[2] == "internal" for link in links), "contents/bookmarks missing")
    offset = counts["full"]["contentsPages"]
    for index, section_index in enumerate(counts["bodySectionIndices"]):
        section = counts["sections"][section_index]
        require(page_sizes[index + offset] == (section["width"], section["height"]), "section paper size differs")
        require(section["headerMarker"] in texts[index + offset]
                and section["footerMarker"] in texts[index + offset], "section page regions are missing")
        section_start = counts["bodySectionIndices"].index(section_index)
        number = section["firstPageNumber"] + index - section_start
        compact = "".join(texts[index + offset].split())
        for marker in (section["headerMarker"], section["footerMarker"]):
            require(f"{marker}P={number}/T={len(texts)}" in compact, "final PAGE/PAGES or section restart differs")
    target = next(index for index, text in enumerate(texts) if counts["futureTargetMarker"] in text)
    target_body = target - offset
    target_section = counts["bodySectionIndices"][target_body]
    section_start = counts["bodySectionIndices"].index(target_section)
    displayed_target = counts["sections"][target_section]["firstPageNumber"] + target_body - section_start
    require(f'{counts["futureReferenceMarker"]}page={displayed_target}' in "".join(joined.split()),
            "future field does not use the final displayed page")
    require(f"BLOCKREFpage={displayed_target}" in "".join(joined.split())
            and "BACKREFpage=1" in "".join(joined.split()), "cross-reference values differ")
    return dict(texts=texts, sizes=page_sizes, links=links, images=images, fonts=fonts, bookmarks=bookmarks)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--consumer-build-directory", type=Path)
    parser.add_argument("--output-directory", type=Path, default=ROOT / "build/verification/paged-document")
    args = parser.parse_args()
    build = args.consumer_build_directory or Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip())
    source = build / "output/paged-document"
    counts = json.loads((source / "counts.json").read_text())
    require(all(counts["full"][key] == counts["paged"][key]
                for key in ("pages", "bodyPages", "contentsPages", "diagnostics")), "summary differs")
    require(counts["paged"]["outputBytes"] == (source / "paged.pdf").stat().st_size, "summary byte count differs")
    complete, paged = inspect(source / "full.pdf", counts), inspect(source / "paged.pdf", counts)
    for key in complete:
        require(complete[key] == paged[key], f"complete and paged exports differ: {key}")
    args.output_directory.mkdir(parents=True, exist_ok=True)
    full_poppler, full_pdfium = references(source / "full.pdf", args.output_directory / "full")
    paged_poppler, paged_pdfium = references(source / "paged.pdf", args.output_directory / "paged")
    require(len(full_poppler) == len(full_pdfium) == len(paged_poppler) == len(paged_pdfium)
            == counts["paged"]["pages"], "independent renderer page count differs")
    metrics, tiles = [], []
    for index, (full, paged, other) in enumerate(zip(full_poppler, paged_poppler, paged_pdfium, strict=True)):
        with Image.open(full) as full_image, Image.open(paged) as paged_image:
            require(full_image.size == paged_image.size
                    and ImageChops.difference(full_image, paged_image).getbbox() is None,
                    f"new paged path changes visible pixels on page {index + 1}")
        delta = difference(paged, other, blur_radius=1)
        require(delta["meanAbsoluteChannelError"] < 3 and delta["changedPixelFraction"] < .07,
                f"independent renderers disagree on page {index + 1}")
        metrics.append(delta)
        tiles.extend([(f"Paged Poppler {index + 1}", paged), (f"Paged PDFium {index + 1}", other)])
    contact_sheet(tiles, args.output_directory)
    report = dict(counts=counts, links=len(complete["links"]), sameEnginePixelsIdentical=True,
                  rendering=metrics, checks="passed")
    (args.output_directory / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
