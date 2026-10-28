# SPDX-License-Identifier: Apache-2.0
"""Independently verify default View, retained tagged/layered graphs and explicit recovery losses."""
import argparse
import csv
from hashlib import sha256
from io import BytesIO
import json
from pathlib import Path
from zipfile import ZipFile
from PIL import Image, ImageChops, ImageFilter
from pypdf import PdfReader
from pypdf.filters import FlateDecode
from pypdf.generic import ArrayObject, DictionaryObject, IndirectObject, NameObject, StreamObject
from verify_advanced_pdf import difference, require
from verify_pdf_font_compatibility import compact, converted_text
from verify_pdf_raster import references
from verify_production import contact_sheet

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "build/verification/pdf-passive-fixtures"
OUTPUT = ROOT / "build/verification/pdf-passive"


def losses(path, action, features):
    with path.open(encoding="utf-8", newline="") as source:
        values = list(csv.DictReader(source, delimiter="\t"))
    require(values and all(item["action"] == action.upper() for item in values), "passive loss action changed")
    require(features <= {item["feature"] for item in values}, "required passive losses are missing: " + path.name)
    treatments = {"PDF_TAGS": "DISCARD", "PDF_LAYERS": "DEGRADE", "PDF_LAYOUT": "DEGRADE",
                  "PDF_STRUCTURE": "DEGRADE", "PDF_FONT_MAPPING": "DEGRADE",
                  "COMMENTS": "DEGRADE", "ANNOTATIONS": "DISCARD"}
    for item in values:
        if item["feature"] in treatments:
            require(item["treatment"] == treatments[item["feature"]], "passive loss treatment changed: " + item["feature"])
    return [{key: value for key, value in item.items() if key != "action"} for item in values]


def preserved_graph(path):
    """Canonicalize object identities and decoded stream bytes, ignoring serialization details."""
    reader = PdfReader(path)
    seen = {}

    def visit(value):
        if isinstance(value, IndirectObject):
            value = value.get_object()
        if isinstance(value, (DictionaryObject, ArrayObject)):
            identity = id(value)
            if identity in seen:
                return {"ref": seen[identity]}
            index = len(seen)
            seen[identity] = index
            if isinstance(value, ArrayObject):
                return {"id": index, "array": [visit(item) for item in value]}
            keys = sorted(value)
            if value.get("/Type") in ("/Page", "/Pages"):
                keys = [key for key in keys if key != "/Parent"]
            if isinstance(value, StreamObject):
                keys = [key for key in keys if key not in ("/Length", "/Filter", "/DecodeParms")]
            result = {"id": index, "dictionary": {str(key): visit(value.raw_get(key)) for key in keys}}
            if isinstance(value, StreamObject):
                result["decodedSha256"] = sha256(value.get_data()).hexdigest()
            return result
        if isinstance(value, bytes):
            return {"bytes": value.hex()}
        if isinstance(value, NameObject):
            return {"name": str(value)}
        if value is None or isinstance(value, (str, int, float, bool)):
            return value
        return str(value)

    catalog = reader.trailer["/Root"]
    result = {key: visit(catalog.raw_get(key)) for key in ("/OCProperties", "/StructTreeRoot", "/MarkInfo") if key in catalog}
    result["pages"] = [visit(page) for page in reader.pages]
    return result


def ink(image, box):
    return image.crop(box).convert("L").point(lambda value: 255 if value < 210 else 0)


def region_metrics(actual, reference, visible, x=20):
    result = []
    require(actual.size == reference.size == (720, 480), "passive raster geometry changed")
    for text, baseline in visible:
        # Source advances are explicit, so the region tightly encloses each line.
        width = sum(620 + ord(char) % 3 * 20 for char in text) * 12 / 1000
        box = tuple(round(value * 2) for value in (x - 2, 240 - baseline - 14, x + width + 3, 240 - baseline + 3))
        left, right = ink(actual, box), ink(reference, box)
        a, b = sum(left.histogram()[1:]), sum(right.histogram()[1:])
        require(a > 60 and b > 60 and .65 < a / b < 1.4, "passive line is missing or incomplete")
        require(max(abs(a - b) for a, b in zip(left.getbbox(), right.getbbox(), strict=True)) <= 2, "passive line origin/advance changed")
        missing_a = sum(ImageChops.subtract(left, right.filter(ImageFilter.MaxFilter(3))).histogram()[1:])
        missing_b = sum(ImageChops.subtract(right, left.filter(ImageFilter.MaxFilter(3))).histogram()[1:])
        unmatched = (missing_a + missing_b) / (a + b)
        require(unmatched < .08, "passive line glyph coverage differs")
        result.append({"text": text, "box": box, "unmatchedInkFraction": unmatched})
    return result


def hidden_regions(image):
    for box in ((35, 116, 530, 164), (35, 216, 530, 264), (35, 316, 530, 364), (70, 420, 530, 475)):
        require(ink(image, box).getbbox() is None, "disabled layer content became visible")


def render(path, label, tiles):
    values = references(path, OUTPUT / label)
    require(len(values[0]) == len(values[1]) == 1, "passive source/output pagination changed")
    for engine, pages in enumerate(values):
        tiles.append((label + " engine " + str(engine + 1), pages[0]))
    return [pages[0] for pages in values]


def recovered_pdf_image(path):
    page = PdfReader(path).pages[0]
    images = [item.get_object() for item in page["/Resources"].get("/XObject", {}).values()
              if item.get_object().get("/Subtype") == "/Image"]
    require(len(images) == 1, "layer fallback PDF did not preserve one visible-page image")
    value = images[0]
    require(value["/Filter"] == "/FlateDecode" and value["/ColorSpace"] == "/DeviceRGB" and value["/BitsPerComponent"] == 8, "layer fallback PDF image coding changed")
    parameters = value.get("/DecodeParms")
    if parameters is not None:
        parameters = parameters.get_object()
    data = FlateDecode.decode(value._data, parameters)
    return Image.frombytes("RGB", (value["/Width"], value["/Height"]), data)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--consumer-build-directory", type=Path)
    args = parser.parse_args()
    consumer = args.consumer_build_directory or Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip())
    if not consumer.is_absolute():
        consumer = ROOT / consumer
    directory = consumer / "output/pdf-passive"
    manifest = json.loads((FIXTURES / "manifest.json").read_text())["files"]
    summary = json.loads((directory / "summary.json").read_text())
    require(summary == {"acceptedSources": 5, "rasters": 10, "imports": 10, "conversions": 20, "preservedEdits": 5, "rejections": 27}, "consumer did not complete all passive cases")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    tiles, metrics = [], {}
    pdf_pages = 0
    for name, record in manifest.items():
        path = FIXTURES / (name + ".pdf")
        require(sha256(path.read_bytes()).hexdigest() == record["sha256"] and len(PdfReader(path).pages) == record["pages"], "passive fixture changed")
        if record["kind"] == "invalid":
            require((directory / (name + "-protected.pdf")).read_bytes() == b"existing destination", "invalid passive input changed old output")
            continue
        if record["kind"] == "control":
            render(path, name, tiles)
            pdf_pages += 1
            continue
        require((directory / (name + "-source.pdf")).read_bytes() == path.read_bytes(), "consumer changed source bytes")
        source = render(path, name + "-source", tiles)
        edited_path = directory / (name + "-edited.pdf")
        require(PdfReader(edited_path).metadata.title == "Passive structure preserved", "metadata edit was not applied")
        require(preserved_graph(path) == preserved_graph(edited_path), "passive edit changed the layer/tag/resource reference graph")
        edited = render(edited_path, name + "-edited", tiles)
        pdf_pages += 2
        for before, after in zip(source, edited, strict=True):
            require(difference(before, after, 0)["changedPixelFraction"] == 0, "metadata edit changed the original visible page")
        require((directory / (name + "-selection-protected.pdf")).read_bytes() == b"existing destination", "unsupported passive remapping changed old output")
        expected = "".join(text for text, baseline in record["visible"])
        if name != "tagged-object":
            require(compact((directory / (name + ".read.txt")).read_text()) == compact(expected), "default View read leaked hidden text or lost visible content")
        required = ({"PDF_LAYERS"} if record["layered"] else set()) | ({"PDF_TAGS"} if record["tagged"] else set())
        raster_required = required | ({"COMMENTS"} if name == "tagged-object" else set())
        import_required = required | {"PDF_LAYOUT", "PDF_STRUCTURE" if record["layered"] else "PDF_FONT_MAPPING"}
        if name == "tagged-object":
            import_required.add("ANNOTATIONS")
        reports = {"raster": [], "import": [], "pdf": [], "docx": []}
        metrics[name] = {}
        for action in ("warn", "ignore"):
            label = name + "-" + action
            raster = directory / (label + ".png")
            with Image.open(raster) as actual:
                metrics[name][action] = []
                for reference in source:
                    delta = difference(raster, reference, 1)
                    require(delta["meanAbsoluteChannelError"] < 4 and delta["changedPixelFraction"] < .06, "passive raster differs from independent default View")
                    with Image.open(reference) as baseline:
                        delta["lines"] = region_metrics(actual, baseline, record["visible"], 40 if name == "tagged-form" else 20)
                    metrics[name][action].append(delta)
                if record["layered"]:
                    hidden_regions(actual)
            tiles.append((label + " Export4j", raster))
            with ZipFile(directory / (label + ".zip")) as archive:
                require(len(archive.namelist()) == 1 and archive.read(archive.namelist()[0]) == raster.read_bytes(), "passive ZIP changed exact prepared pixels")
            reports["raster"].append(losses(directory / (label + "-raster.losses.tsv"), action, raster_required))
            reports["import"].append(losses(directory / (label + "-import.losses.tsv"), action, import_required))
            if record["layered"]:
                require(not (directory / (label + "-import.txt")).read_text(), "layer raster recovery exposed source text")
                recovered = directory / (label + "-recovered.png")
                with Image.open(recovered) as image, Image.open(raster) as rendered:
                    require(image.convert("RGB").tobytes() == rendered.convert("RGB").tobytes() and image.size == rendered.size, "layer recovery image differs from visible source pixels")
                tiles.append((label + " recovered", recovered))
            else:
                require(compact((directory / (label + "-import.txt")).read_text()) == compact(expected), "tag recovery changed source text")
            for extension in ("pdf", "docx"):
                converted = directory / (label + "." + extension)
                reports[extension].append(losses(converted.with_name(converted.name + ".losses.tsv"), action, import_required))
                if record["layered"]:
                    if extension == "pdf":
                        extracted = converted_text(converted)
                        require(compact(extracted) == "\ufffc", "layer conversion retained hidden or flattened source text")
                        image = recovered_pdf_image(converted)
                    else:
                        require(not converted_text(converted), "DOCX layer conversion retained source text")
                        with ZipFile(converted) as archive:
                            media = [name for name in archive.namelist() if name.startswith("word/media/")]
                            require(len(media) == 1, "DOCX layer conversion did not preserve one image")
                            image = Image.open(BytesIO(archive.read(media[0])))
                    try:
                        with Image.open(directory / (label + "-recovered.png")) as original:
                            require(image.size == original.size and image.convert("RGB").tobytes() == original.convert("RGB").tobytes(), "layer conversion changed recovered source pixels")
                    finally:
                        image.close()
                else:
                    require(compact(converted_text(converted)) == compact(expected), "tag conversion lost or duplicated source content")
                if extension == "pdf":
                    render(converted, label + "-converted", tiles)
                    pdf_pages += 1
        for values in reports.values():
            require(values[0] == values[1], "IGNORE discarded actual passive losses")
    sheets = OUTPUT / "contact-sheets"
    sheets.mkdir(exist_ok=True)
    contact_sheet(tiles, sheets)
    report = {"checks": "passed", **summary, "fixtureFiles": len(manifest), "renderedPdfPages": pdf_pages,
              "independentPageImages": pdf_pages * 2, "contactTiles": len(tiles), "defaultViewMetrics": metrics}
    (OUTPUT / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in report.items() if key != "defaultViewMetrics"}))


if __name__ == "__main__":
    main()
