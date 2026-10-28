# SPDX-License-Identifier: Apache-2.0
"""Independent source, field, link and drawing checks for recursive/grid/contour continuation."""
import argparse
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

from pypdf import PdfReader
from verify_advanced_pdf import difference, require
from verify_pdf_raster import references
from verify_production import contact_sheet
from verify_semantic_tables import annotations, page_logical, table_borders
from verify_variable_columns import ink_coverage
from verify_variable_tables import logical_pages

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build/verification/complex-flows"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--consumer-build-directory", type=Path)
    args = parser.parse_args()
    consumer = args.consumer_build_directory or Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip())
    source = consumer / "output/gap03-complex-flows"
    report = {}
    tiles = []
    for name, prefix, count in (("recursive", "R", 36), ("grid", "R", 36), ("contour", "F", 64), ("semantic-float", "S", 18)):
        pdf = source / (name + ".pdf")
        reader = PdfReader(pdf, strict=True)
        text = logical_pages(pdf)
        joined = "\n".join(text)
        compact = re.sub(r"\s+", "", joined)
        require(len(reader.pages) == len(text) > 1, "complex flow must span physical pages")
        expected = [f"{prefix}{index:03}" for index in range(1, count + 1)]
        if name == "contour":
            # Reader geometry heuristics may treat an irregular left edge as multiple columns.
            # Verify unique extraction and original marked logical order as separate contracts.
            require(sorted(re.findall(prefix + r"\d{3}", joined)) == expected, "contour extraction lost or repeated source")
            marked = "".join(value for page in reader.pages for value in page_logical(page))
            require(re.findall(prefix + r"\d{3}", marked) == expected, "contour marked logical source order changed")
        else:
            require(re.findall(prefix + r"\d{3}", joined) == expected, f"{name}: original source lost, repeated or reordered")
        if name != "contour":
            if name != "semantic-float":
                require(compact.count("RECURSIVE-END") == 1, "recursive tail was lost or repeated")
            require(len(annotations(reader)) > 5, "nested original hyperlinks disappeared")
            leaf_fields = []
            for index, page in enumerate(reader.pages):
                # Narrow grid cells can wrap a complete header into PAGE and OF lines.
                # Later grid pages may contain only following component content after the inner table ends.
                headers = [value for value in page_logical(page) if value.startswith(("PAGE=", "OF="))]
                header_source = re.sub(r"\s+", "", "".join(headers))
                pairs = re.findall(r"PAGE=(\d+)OF=(\d+)", header_source)
                require(all(pair == (str(index + 1), str(len(reader.pages))) for pair in pairs)
                        and "".join(f"PAGE={page}OF={total}" for page, total in pairs) == header_source,
                        "nested repeated header used logical region instead of final physical page")
                if re.search(prefix + r"\d{3}", text[index]):
                    require(pairs, "nested body has no repeated header")
                if "LEAF-PAGE=" in re.sub(r"\s+", "", text[index]):
                    require(f"LEAF-PAGE={index+1}" in re.sub(r"\s+", "", text[index]), "nested leaf field resolved to wrong page")
                    leaf_fields.append(index)
            require(len(leaf_fields) == (0 if name == "semantic-float" else 1), "nested leaf field lost or repeated")
            for box in table_borders(reader):
                require(box["x"] >= 19.997 and box["y"] >= 19.997 and box["x"] + box["width"] <= 380.003
                        and box["y"] + box["height"] <= 200.003, "table left actual page region")
        if name == "grid":
            require(re.findall(r"G\d{3}", joined) == [f"G{index:03}" for index in range(1, 17)], "grid column source changed")
            # PDFium marks an end-of-line hyphen with U+0002. Check native glyph labels through
            # the independent ToUnicode extractor, while PDFium checks outlined nested ActualText above.
            native_compact = re.sub(r"\s+", "", "".join(page.extract_text() for page in reader.pages))
            for token in ("GRID-SIDE-A", "GRID-SIDE-B", "GRID-END", "GRID-AFTER"):
                require(native_compact.count(token) == 1, "grid neighbor or tail duplicated")
        svg = ET.parse(source / (name + ".svg"))
        ns = {"s": "http://www.w3.org/2000/svg"}
        require(len(svg.findall(".//s:svg", ns)) == len(reader.pages), "SVG sidecar page drawings differ")
        if name in ("contour", "semantic-float"):
            require(svg.findall(".//s:clipPath", ns), "arbitrary contour masks missing from SVG")
        if name == "semantic-float":
            require(sorted(re.findall(r"B\d{3}", joined)) == [f"B{index:03}" for index in range(1, 51)], "floating semantic body changed")
            marked = "".join(value for page in reader.pages for value in page_logical(page))
            require(re.findall(r"B\d{3}", marked) == [f"B{index:03}" for index in range(1, 51)], "floating semantic body marked order changed")
            require(sum(len(page.images) for page in reader.pages) == 1, "floating image lost or repeated")
            links = annotations(reader)
            require(sum(link.get("uri") == "https://example.com/float-image" for link in links) == 1, "floating image link changed")
        first, second = references(pdf, OUTPUT / name)
        metrics = []
        for index, (left, right) in enumerate(zip(first, second, strict=True), 1):
            raster = source / f"{name}-{index}.png"
            values = [difference(raster, reference, blur_radius=1) for reference in (left, right)]
            coverage = [ink_coverage(raster, reference) for reference in (left, right)]
            require(all(value["meanAbsoluteChannelError"] < 4 and value["changedPixelFraction"] < .06 for value in values),
                    "static output differs from independent visible page content")
            require(all(max(value) < .01 for value in coverage), "independent rendering lost visible ink")
            metrics.append({"differences": values, "uncoveredInk": coverage})
            tiles.extend([(f"{name} {index} Raster", raster), (f"{name} {index} Poppler", left), (f"{name} {index} PDFium", right)])
        report[name] = {"pages": len(reader.pages), "checks": "passed", "rendering": metrics}
    contact_sheet(tiles, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({name: value["pages"] for name, value in report.items()}))


if __name__ == "__main__":
    main()
