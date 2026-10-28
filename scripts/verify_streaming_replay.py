# SPDX-License-Identifier: Apache-2.0
"""Independent bounded replay PDF structure, final navigation, source order and dual rendering."""
import argparse
import json
from pathlib import Path
import re
from pypdf import PdfReader
from pypdf.filters import FlateDecode
from verify_advanced_pdf import difference, require
from verify_pdf_raster import references
from verify_production import contact_sheet
from verify_semantic_tables import table_borders
from verify_variable_tables import logical_pages

ROOT = Path(__file__).resolve().parents[1]


def inspect(source):
    counts = json.loads((source / "counts.json").read_text())
    path = source / "replay.pdf"
    reader = PdfReader(path, strict=True)
    pages = logical_pages(path)
    require(len(reader.pages) == len(pages) == counts["pages"] and len(pages) > 3, "replay final page count differs")
    require(counts["opens"] == counts["closes"] == 3 and counts["bodyRows"] == 3, "replay probe/layout/write source ownership differs")
    require("Contents" in pages[0] and "Details 2" in pages[0] and f"Final target {len(pages)}" in pages[0], "front contents final page values differ")
    for index, text in enumerate(pages[1:], 1):
        require(text.count(f"Page {index + 1} / {len(pages)}") == 1 and len(re.findall(r"(?m)^REPORT\r?$", text)) == 1, "actual header PAGE/PAGES differ")
    joined = "\n".join(pages)
    require(joined.count("OWNER [1]") == joined.count("NEXT SOURCE ROW") == joined.count("LAST TARGET") == 1, "streaming anchors lost or duplicated")
    require(joined.count("Owner continuation") == 32, "vertical owner lost or repeated complete source lines")
    require(re.findall(r"NEST-\d{3}", joined) == [f"NEST-{index:03}" for index in range(1, 91)], "recursive paragraph source lost, repeated or reordered")
    require(joined.count("This footnote is reserved once") == 1 and "[1]" in pages[1], "footnote did not follow its first group page exactly once")
    outlines = [value for value in reader.outline if not isinstance(value, list)]
    require([(item.title, reader.get_destination_page_number(item)) for item in outlines] == [("Details", 1), ("Final target", len(pages) - 1)], "bookmarks point at incorrect final pages")
    internal, external = [], []
    for index, page in enumerate(reader.pages):
        for reference in page.get("/Annots", []):
            annotation = reference.get_object()
            require(annotation.get("/Subtype") == "/Link", "unexpected replay annotation type")
            rect = [float(value) for value in annotation["/Rect"]]
            require(0 <= rect[0] < rect[2] <= float(page.mediabox.width) and 0 <= rect[1] < rect[3] <= float(page.mediabox.height), "link rectangle leaves final page")
            if "/Dest" in annotation:
                destination = annotation["/Dest"]
                target = next(at for at, candidate in enumerate(reader.pages) if candidate.indirect_reference == destination[0])
                internal.append((index, target))
            else:
                action = annotation["/A"]
                uri = str(action["/URI"])
                require(action["/S"] == "/URI" and uri in ("https://example.org/replay", "https://example.org/replay/image"), "external link changed while remapping page objects")
                external.append((index, uri))
    require((0, 1) in internal and (0, len(pages) - 1) in internal and (1, len(pages) - 1) in internal and external, "future reference or contents links are missing")
    require((len(pages) - 1, "https://example.org/replay/image") in external, "inline picture link did not follow its final page")
    images = []
    for page in reader.pages:
        for reference in page["/Resources"].get("/XObject", {}).values():
            value = reference.get_object()
            if value.get("/Subtype") == "/Image":
                images.append(value)
    require(len(images) == 1 and images[0]["/Width"] == images[0]["/Height"] == 2 and images[0]["/ColorSpace"] == "/DeviceRGB", "spool image object identity or dimensions differ")
    image = images[0]
    parameters = image.get("/DecodeParms")
    decoded = FlateDecode.decode(image._data, parameters.get_object() if parameters else None)
    require(decoded == bytes((34, 102, 170)) * 4, "spool image pixels changed while remapping page resources")
    boxes = table_borders(reader)
    for box in boxes:
        require(box["x"] >= 19.997 and box["x"] + box["width"] <= 240.003 and box["y"] >= 19.997 and box["y"] + box["height"] <= 220.003, "recursive table or owner leaves its viewport")
    headers = [box for box in boxes if box["pageIndex"] == 1 and abs(box["y"] - 20) < .003]
    require(len(headers) == 2 and abs(headers[0]["width"] - 90.16211) < .003
            and abs(headers[1]["width"] - 129.83789) < .003, "bounded Auto probe did not produce actual fixed output widths")
    return {"counts": counts, "internalLinks": len(internal), "externalLinks": len(external), "gridRectangles": len(boxes)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--consumer-build-directory", type=Path)
    args = parser.parse_args()
    build = args.consumer_build_directory or Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip())
    source = build / "output/streaming-replay"
    output = ROOT / "build/verification/streaming-replay/output"
    output.mkdir(parents=True, exist_ok=True)
    report = inspect(source)
    first, second = references(source / "replay.pdf", output)
    require(len(first) == len(second) == report["counts"]["pages"], "replay independent rendering page count differs")
    metrics, tiles = [], []
    for index, (left, right) in enumerate(zip(first, second, strict=True)):
        delta = difference(left, right, blur_radius=1)
        require(delta["meanAbsoluteChannelError"] < 3 and delta["changedPixelFraction"] < .07, "replay independent rendering differs")
        metrics.append(delta)
        tiles.extend([(f"Replay Poppler {index + 1}", left), (f"Replay PDFium {index + 1}", right)])
    contact_sheet(tiles, output)
    report.update(rendering=metrics, checks="passed")
    (output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
