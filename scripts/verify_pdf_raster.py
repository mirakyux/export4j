# SPDX-License-Identifier: Apache-2.0
"""Independent page geometry, visible pixels, density, ZIP and decoded image resource checks."""
import json
from pathlib import Path
import subprocess
from zipfile import ZipFile
from PIL import Image
from pypdf import PdfReader
from pypdf.filters import FlateDecode
import pypdfium2 as pdfium
from verify_advanced_pdf import require, difference
from verify_production import contact_sheet

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build/verification/pdf-raster"


def references(source, directory, background=(255, 255, 255, 255)):
    poppler = directory / "poppler"; target = directory / "pdfium"
    poppler.mkdir(parents=True, exist_ok=True); target.mkdir(parents=True, exist_ok=True)
    subprocess.run(["pdftoppm", "-cropbox", "-r", "144", "-png", str(source), str(poppler / "page")],
                   check=True, capture_output=True, timeout=60)
    pages = []
    with pdfium.PdfDocument(source) as document:
        document.init_forms()
        for index in range(len(document)):
            page = document[index]
            try:
                bitmap = page.render(scale=2, may_draw_forms=True, fill_color=background)
                try:
                    name = target / f"page-{index+1}.png"; bitmap.to_pil().convert("RGB").save(name); pages.append(name)
                finally: bitmap.close()
            finally: page.close()
    return sorted(poppler.glob("page-*.png"), key=lambda p: int(p.stem.rsplit("-", 1)[1])), pages


def same_zip(archive, directory, prefix=""):
    with ZipFile(archive) as bundle:
        require(len(bundle.namelist()) == 2, "raster ZIP image count")
        for name in bundle.namelist():
            require("/" not in name and ".." not in name, "generated archive filename")
            require(bundle.read(name) == (directory / (prefix + name)).read_bytes(), "ZIP bytes differ from exact image candidate")


def main():
    sample = ROOT / "examples/build/pdf-raster"
    consumer = Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip()) / "output"
    same_zip(sample / "pages.zip", sample); same_zip(sample / "jpeg-pages.zip", sample); same_zip(sample / "forms.zip", sample, "form-")
    tiles = []; metrics = {}
    for source, prefix, name in ((ROOT / "examples/build/pdf-page-editing/edited.pdf", "", "pages"),
                                 (ROOT / "examples/build/pdf-object-editing/edited.pdf", "form-", "forms")):
        first, second = references(source, OUTPUT / name); metrics[name] = []
        for index, (left, right) in enumerate(zip(first, second, strict=True)):
            actual = sample / f"{prefix}page-{index+1:04d}.png"
            with Image.open(actual) as image:
                require(image.format == "PNG" and abs(image.info["dpi"][0] - 144) < .02, "PNG physical density metadata")
                if name == "pages": require(image.size == ((600, 440) if index == 0 else (360, 480)), "CropBox/rotation geometry")
            values = [difference(actual, other, 1) for other in (left, right)]
            require(all(delta["meanAbsoluteChannelError"] < 4 and delta["changedPixelFraction"] < .06 for delta in values), "PDF raster differs from independent visible content")
            metrics[name].append(values); tiles.extend([(f"{name} {index+1} Export4j", actual), (f"{name} {index+1} Poppler", left), (f"{name} {index+1} PDFium", right)])
    _, tinted = references(ROOT / "examples/build/pdf-page-editing/edited.pdf", OUTPUT / "jpeg", (238, 240, 245, 255))
    metrics["jpeg"] = []
    for index, reference in enumerate(tinted):
        path = sample / f"page-{index+1:04d}.jpg"
        with Image.open(path) as image:
            require(image.format == "JPEG" and image.info["dpi"] == (144, 144), "JPEG density metadata")
            require(max(abs(a-b) for a,b in zip(image.getpixel((0, 0)), (238, 240, 245))) <= 3, "explicit JPEG background")
        delta = difference(path, reference, 1); require(delta["meanAbsoluteChannelError"] < 4 and delta["changedPixelFraction"] < .06, "JPEG visible content mismatch")
        metrics["jpeg"].append(delta); tiles.append((f"JPEG {index+1}", path))
    source_page = PdfReader(ROOT / "examples/build/pdf-page-editing/edited.pdf").pages[0]
    source_image = next(value.get_object() for value in source_page["/Resources"]["/XObject"].values() if value.get_object().get("/Subtype") == "/Image")
    require(source_image["/Filter"] == "/FlateDecode" and source_image["/ColorSpace"] == "/DeviceRGB" and source_image["/BitsPerComponent"] == 8, "fixture image coding")
    # pypdf 6.10's image helper misses an indirect DecodeParms here. Pass the resolved predictor
    # dictionary to its independent Flate decoder; do not change the PDF or accept shifted pixels.
    source_pixels = FlateDecode.decode(source_image._data, source_image["/DecodeParms"].get_object())
    require(len(source_pixels) == 30 * 20 * 3, "independent predictor decoded length")
    original = Image.frombytes("RGB", (30, 20), source_pixels).convert("RGBA")
    with Image.open(sample / "image-0001.png") as image:
        require(image.size == (30, 20) and image.convert("RGBA").tobytes() == original.tobytes(), "extracted decoded image pixels")
        require(set(image.convert("RGB").get_flattened_data()) == {(34, 102, 170)}, "embedded image source color")
    with ZipFile(sample / "images.zip") as bundle: require(bundle.namelist() == ["image-0001.png"] and bundle.read("image-0001.png") == (sample / "image-0001.png").read_bytes(), "extracted image ZIP")
    first, second = references(consumer / "consumer-page-edited.pdf", OUTPUT / "consumer")
    caller = consumer / "consumer-raster.png"
    for path in (first[0], second[0]):
        delta = difference(caller, path, 1); require(delta["meanAbsoluteChannelError"] < 4 and delta["changedPixelFraction"] < .06, "isolated raster caller output")
    with ZipFile(consumer / "consumer-raster.zip") as bundle: require(bundle.read("page-0001.png") == caller.read_bytes(), "consumer exact image archive")
    tiles.extend([("consumer Export4j", caller), ("consumer Poppler", first[0]), ("consumer PDFium", second[0]), ("extracted resource", sample / "image-0001.png")])
    features = {line.split("\t")[0] for line in (sample / "losses.tsv").read_text().splitlines()}
    require(features == {"DOCUMENT_METADATA", "JPEG_ENCODING", "FORMS", "LINKS", "COMMENTS", "BOOKMARKS", "ATTACHMENTS"}, "raster actual loss features")
    contact_sheet(tiles, OUTPUT)
    report = {"rendered_images": 7, "unique_extracted_images": 1, "checks": "passed", "rendering": metrics}
    (OUTPUT / "report.json").write_text(json.dumps(report, indent=2)+"\n"); print(json.dumps(report, indent=2))


if __name__ == "__main__": main()
