# SPDX-License-Identifier: Apache-2.0
"""Check published ColumnFlow PDFs using final PDF source, geometry and two independent renderers."""
from __future__ import annotations

import argparse
from importlib import metadata
import json
from pathlib import Path
import re
from zipfile import ZipFile

from pypdf import PdfReader

from verify_adr_form import render
from verify_advanced_pdf import difference, require
from verify_inline_images import image_boxes
from verify_continuous_columns import font_ascent
from verify_production import contact_sheet
from verify_semantic_tables import check_characters, table_borders
from verify_streaming_rich_text import painted_spans

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--consumer-build-directory", required=True, type=Path)
    parser.add_argument("--designer-output-directory", type=Path)
    parser.add_argument("--output-directory", type=Path, default=Path("build/verification/column-flow"))
    args = parser.parse_args()
    fixtures = args.consumer_build_directory / "output/column-flow"
    output = args.output_directory
    output.mkdir(parents=True, exist_ok=True)
    report = {"tools": {name: metadata.version(name) for name in ("pypdf", "pypdfium2", "Pillow")}, "cases": {}}
    contacts = []
    for name in ("two", "three", "weighted", "multipage"):
        expected = (fixtures / f"{name}-tags.txt").read_text(encoding="utf-8").splitlines()
        for edited in (False, True):
            key = name + ("-edited" if edited else "")
            reader = PdfReader(fixtures / f"{key}.pdf")
            spans = painted_spans(reader)
            logical = "".join(span["text"] for span in spans)
            tags = re.findall(r"(?:BEFORE|EDITED|AFTER)-\d{2}|WIDE-TABLE|TABLE-VALUE|FULL-WIDTH-END", logical)
            wanted = [value.replace("BEFORE-01", "EDITED-01") if edited else value for value in expected]
            require(tags == wanted, f"{key}: source lost, duplicated or reordered: {tags}")
            source_sentence = "Each paragraph remains a semantic source. Text flows down a column and continues in the next, preserving its reading order."
            require(re.sub(r"\s", "", logical).count(re.sub(r"\s", "", source_sentence)) == (40 if name == "multipage" else 8),
                    f"{key}: paragraph text lost or repeated")
            require(len(reader.pages) >= 2 if name == "multipage" else len(reader.pages) == 1,
                    f"{key}: unexpected pagination")
            images = image_boxes(reader)
            require(len(images) == 1, f"{key}: full-width image lost or duplicated")
            image = images[0]
            require(abs(image["x"] - 30) < .003 and abs(image["width"] - 420) < .003
                    and abs(image["height"] - 40) < .003 and image["pixels"] == [840, 80],
                    f"{key}: image shrunk into a text column")
            ascent = font_ascent(ROOT / "assets/fonts/LiberationSans-Regular.ttf")
            lines = [{"page": span["page"], "source": span["text"], "x": span["x"],
                      "y": span["baseline"] - ascent * span["size"], "width": span["right"] - span["x"],
                      "height": 23 if span["size"] == 17 else 14} for span in spans]
            body = [line for line in lines if re.search(r"(?:BEFORE|EDITED|AFTER)-\d{2}", line["source"])]
            starts = sorted({round(line["x"], 3) for line in body})
            widths = [128, 128, 128] if name == "three" else [134, 268] if name == "weighted" else [201, 201]
            origins = [30]
            for width in widths[:-1]:
                origins.append(origins[-1] + width + 18)
            require(all(any(abs(start - origin) < .003 for origin in origins) for start in starts),
                    f"{key}: text starts outside configured columns: {starts}")
            require(len(starts) == len(origins), f"{key}: not all configured columns contain text")
            table_index = next(index for index, line in enumerate(lines) if "WIDE-TABLE" in line["source"])
            after_index = next(index for index, line in enumerate(lines) if "AFTER-" in line["source"])
            tail_index = next(index for index, line in enumerate(lines) if "FULL-WIDTH-END" in line["source"])
            before, after = lines[1:table_index], lines[after_index:tail_index]
            table = lines[table_index]
            first_after = after[0]
            for line in before + after:
                column = next((index for index, origin in enumerate(origins) if abs(line["x"] - origin) < .003), None)
                require(column is not None and line["x"] + line["width"] <= origins[column] + widths[column] + .01,
                        f"{key}: continued text crosses a column edge")
            require((table["page"], table["y"]) > max((line["page"], line["y"] + line["height"]) for line in before),
                    f"{key}: table overlaps earlier text")
            borders = table_borders(reader)
            require(borders and abs(max(border["x"] + border["width"] for border in borders)
                                   - min(border["x"] for border in borders) - 420) < .003,
                    f"{key}: table no longer spans the body width")
            require((image["page"], image["y"]) > max((border["pageIndex"], border["y"] + border["height"]) for border in borders),
                    f"{key}: image overlaps the full-width table")
            require((first_after["page"], first_after["y"]) > (image["page"], image["y"] + image["height"] - .01),
                    f"{key}: continued text overlaps the wide image")
            if name != "multipage":
                require(table["page"] == image["page"] == first_after["page"] == 0,
                        f"{key}: unnecessary page break around full-width content")
            for line in lines:
                require(line["x"] >= 29.99 and line["y"] >= 29.99
                        and line["x"] + line["width"] <= 450.01 and line["y"] + line["height"] <= 570.01,
                        f"{key}: text leaves the body")
            check_characters(fixtures / f"{key}.pdf", 30)
            with ZipFile(fixtures / f"{key}.zip") as bundle:
                source = bundle.read("template.xml").decode("utf-8")
                require(source.count("<columns ") >= 2 and "wide-table" in source,
                        f"{key}: saved template lost column containers")
                require(any(entry.startswith("images/") for entry in bundle.namelist()), f"{key}: saved image missing")
                if edited:
                    with ZipFile(fixtures / f"{name}.zip") as original:
                        original_images = {entry: original.read(entry) for entry in original.namelist() if entry.startswith("images/")}
                        edited_images = {entry: bundle.read(entry) for entry in bundle.namelist() if entry.startswith("images/")}
                        require(original_images == edited_images, f"{key}: image source changed after editing")
            poppler, pdfium = render(fixtures / f"{key}.pdf", output / key)
            comparisons = []
            for index, (first, second) in enumerate(zip(poppler, pdfium)):
                diff = difference(first, second, blur_radius=2)
                require(diff["meanAbsoluteChannelError"] < 4 and diff["changedPixelFraction"] < .06,
                        f"{key}: independent renderers differ: {diff}")
                comparisons.append(diff)
                contacts.append((f"{key} p{index + 1}", first))
            require(len(poppler) == len(pdfium) == len(reader.pages), f"{key}: rendered page missing")
            report["cases"][key] = {"pages": len(reader.pages), "markers": len(tags), "columnOrigins": starts,
                                     "fullWidthImage": image, "renderComparisons": comparisons}
    contact_sheet(contacts, output)
    if args.designer_output_directory:
        report["designer"] = {}
        designer_contacts = []
        designer_directory = output / "designer"
        designer_directory.mkdir(parents=True, exist_ok=True)
        for key in ("body-columns-sample-two", "body-columns-sample-three", "body-columns"):
            path = args.designer_output_directory / f"{key}.pdf"
            reader = PdfReader(path)
            images = image_boxes(reader)
            require(images and all(image["width"] >= 300 for image in images), f"{key}: full-width designer image missing")
            borders = table_borders(reader)
            margin = 42 if key.endswith("-two") else 36 if key.endswith("-three") else 30
            body_width = float(reader.pages[0].mediabox.width) - 2 * margin
            require(borders and abs(max(border["x"] + border["width"] for border in borders)
                    - min(border["x"] for border in borders) - body_width) < .003,
                    f"{key}: full-width designer table missing")
            check_characters(path, 30)
            poppler, pdfium = render(path, designer_directory / key)
            comparisons = [difference(first, second, blur_radius=2) for first, second in zip(poppler, pdfium)]
            require(len(comparisons) == len(reader.pages), f"{key}: rendered designer page missing")
            require(all(diff["meanAbsoluteChannelError"] < 4 and diff["changedPixelFraction"] < .06 for diff in comparisons),
                    f"{key}: independent designer renderers differ")
            designer_contacts.extend((f"{key} p{index + 1}", path) for index, path in enumerate(poppler))
            report["designer"][key] = {"pages": len(reader.pages), "images": images, "renderComparisons": comparisons}
        contact_sheet(designer_contacts, designer_directory)
    (output / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps({"cases": len(report["cases"]), "pages": sum(case["pages"] for case in report["cases"].values())}))


if __name__ == "__main__":
    main()
