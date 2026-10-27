# SPDX-License-Identifier: Apache-2.0
"""Independently decode single PNG recovery, exact bindings, edits and exported PDFs."""
import argparse
import csv
import hashlib
import io
import json
from pathlib import Path
import struct
import xml.etree.ElementTree as ET
from zipfile import ZipFile
import zlib

from PIL import Image
from pypdf import PdfReader
from verify_advanced_pdf import difference, render_pdfium, render_poppler, require
from verify_production import check_bundle

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "examples/build/embedded-png-recovery"
OUTPUT = ROOT / "build/verification/embedded-png-recovery"
SIGNATURE = b"\x89PNG\r\n\x1a\n"


def chunks(data):
    require(data[:8] == SIGNATURE, "PNG signature differs")
    offset, result = 8, []
    while offset < len(data):
        require(len(data) - offset >= 12, "truncated PNG framing")
        size = struct.unpack_from(">I", data, offset)[0]
        end = offset + size + 12
        require(end <= len(data), "PNG chunk length exceeds file")
        name, value = data[offset + 4:offset + 8], data[offset + 8:end - 4]
        crc = struct.unpack_from(">I", data, end - 4)[0]
        require(zlib.crc32(name + value) == crc, "PNG CRC differs")
        result.append((name, value, data[offset:end]))
        offset = end
    require(result[0][0] == b"IHDR" and result[-1][0] == b"IEND", "PNG header/end order differs")
    require(sum(name == b"IEND" for name, _, _ in result) == 1, "duplicate PNG end")
    return result


def pixels(data):
    with Image.open(io.BytesIO(data)) as image:
        require(image.format == "PNG" and image.size == (794, 1123), "single PNG format/dimensions differ")
        return image.convert("RGB")


def inspect(path, expected, binding=True):
    data = path.read_bytes()
    parsed = chunks(data)
    embedded = [value for name, value, _ in parsed if name == b"exJM"]
    require(len(embedded) == 1 and parsed[-2][0] == b"exJM", "recovery must be unique and immediately before IEND")
    standard = SIGNATURE + b"".join(raw for name, _, raw in parsed if name != b"exJM")
    # Independent ImageIO replacement: Pillow decodes every PNG pixel with/without the private chunk.
    with pixels(data) as actual, pixels(standard) as plain:
        require(actual.tobytes() == plain.tobytes(), "private chunk changed decoded pixels")
    with ZipFile(io.BytesIO(embedded[0])) as archive:
        require(archive.testzip() is None and len(archive.namelist()) == len(set(archive.namelist())), "ZIP CRC/duplicate failure")
        entries = {name: archive.read(name) for name in archive.namelist()}
    manifest = ET.fromstring(entries.pop("recovery.xml"))
    require(manifest.tag == "recovery" and manifest.get("version") == "1" and manifest.get("format") == "PNG", "manifest format/version differs")
    matches = int(manifest.get("bytes")) == len(standard) and manifest.get("sha256") == hashlib.sha256(standard).hexdigest()
    require(matches == binding, "current PNG byte binding differs")
    digest = hashlib.sha256()
    for name, value in sorted(entries.items()):
        key = name.encode("utf-8")
        digest.update(struct.pack(">II", len(key), len(value)))
        digest.update(key)
        digest.update(value)
    require(digest.hexdigest() == manifest.get("model-sha256"), "model/resource digest differs")
    require(set(entries) == {"template.xml", "fonts/Regular.ttf", "licenses/Font.txt"}, "unexpected hidden source/resource")
    require(entries["fonts/Regular.ttf"] == (ROOT / "assets/fonts/LiberationSans-Regular.ttf").read_bytes(), "font bytes changed")
    require(entries["licenses/Font.txt"] == (ROOT / "assets/fonts/LiberationSans-OFL.txt").read_bytes(), "font notice changed")
    xml = entries["template.xml"].decode("utf-8")
    words = [word for paragraph in ET.fromstring(xml).findall(".//p") for word in "".join(paragraph.itertext()).split()]
    require(words == [expected, "EMBEDDED-PNG", "UNCHANGED-CONTROL"], "saved model text/order differs")
    require("UNUSED-SOURCE" not in xml, "unused authoring source survived")
    if expected != "ORIGINAL":
        require("ORIGINAL" not in xml, "deleted source text survived")
    return standard, entries["template.xml"], embedded[0]


def chunk(name, value):
    return struct.pack(">I", len(value)) + name + value + struct.pack(">I", zlib.crc32(name + value))


def prepare_external():
    original = (SOURCE / "original.png").read_bytes()
    parsed = chunks(original)
    payload = next(value for name, value, _ in parsed if name == b"exJM")
    with pixels(original) as image:
        image.paste((0, 80, 255), (10, 10, 30, 30))
        stream = io.BytesIO()
        image.save(stream, format="PNG")
        changed = stream.getvalue()
    saved = chunks(changed)
    require(all(name != b"exJM" for name, _, _ in saved), "Pillow unexpectedly retained recovery")
    # Explicitly retain the old payload after the independent editor changed actual pixels.
    external = SIGNATURE + b"".join(raw for _, _, raw in saved[:-1]) + chunk(b"exJM", payload) + saved[-1][2]
    (SOURCE / "external.png").write_bytes(external)
    with pixels(original) as image:
        image.save(SOURCE / "stripped.png", format="PNG")
    print("Independent Pillow rewrite prepared changed-pixel/stale and stripped-recovery PNGs")


def pdf(path, expected, label):
    reader = PdfReader(path)
    require(len(reader.pages) == 1, "PDF page count differs")
    require(reader.pages[0].extract_text().split() == [expected, "EMBEDDED-PNG", "UNCHANGED-CONTROL"], "PDF text/order differs")
    poppler = render_poppler(path, OUTPUT / label / "poppler")
    pdfium = render_pdfium(path, OUTPUT / label / "pdfium")
    require(len(poppler) == len(pdfium) == 1, "renderer page count differs")
    comparison = difference(poppler[0], pdfium[0], blur_radius=1)
    require(comparison["meanAbsoluteChannelError"] < 4 and comparison["changedPixelFraction"] < .06, "renderers differ materially")
    return poppler[0], comparison


def edited_pixels(before, after):
    with Image.open(before) as first, Image.open(after) as second:
        first, second = first.convert("RGB"), second.convert("RGB")
        require(first.size == second.size and first.tobytes() != second.tobytes(), "edit did not change pixels")
        # At 96 dpi the first line ends above y=100; the second line and remaining page are unchanged.
        require(first.crop((0, 100, first.width, first.height)).tobytes() == second.crop((0, 100, second.width, second.height)).tobytes(), "untouched second line/region changed")


def distributed_libraries(consumer):
    components = json.loads((ROOT / "build/reports/sbom.cdx.json").read_text(encoding="utf-8"))["components"]
    expected = {f'{entry["name"]}-{entry["version"]}.jar': entry["hashes"][0]["content"]
                for entry in components if entry["group"] == "com.mirakyux.export4j"}
    with ZipFile(consumer / "distributions/isolated-library-consumer.zip") as archive:
        actual = {Path(name).name: hashlib.sha256(archive.read(name)).hexdigest() for name in archive.namelist()
                  if "/lib/export4j-" in name and name.endswith(".jar")}
    require(len(expected) == 15 and actual == expected, "distributed caller JARs differ from current SBOM")
    return len(actual)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prepare-external", action="store_true")
    parser.add_argument("--consumer-build-directory", type=Path)
    args = parser.parse_args()
    if args.prepare_external:
        prepare_external()
        return
    require(args.consumer_build_directory is not None, "provide this run's successful isolated consumer directory")
    consumer = args.consumer_build_directory.resolve()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    original, model, _ = inspect(SOURCE / "original.png", "ORIGINAL")
    require(original == (SOURCE / "plain.png").read_bytes(), "embedding changed original PNG bytes/IDAT")
    cycled, cycle_model, _ = inspect(SOURCE / "cycle-3.png", "ORIGINAL")
    require(original == cycled and model == cycle_model and (SOURCE / "original.png").read_bytes() == (SOURCE / "cycle-3.png").read_bytes(), "three rounds changed PNG/model")
    inspect(SOURCE / "edited.png", "EDITED")
    inspect(consumer / "output/consumer-embedded-png.png", "CALLER")
    inspect(SOURCE / "external.png", "ORIGINAL", binding=False)
    with pixels((SOURCE / "original.png").read_bytes()) as first, pixels((SOURCE / "external.png").read_bytes()) as external:
        require(first.tobytes() != external.tobytes() and external.getpixel((10, 10)) == (0, 80, 255), "independent pixel edit absent")
    stripped = (SOURCE / "stripped.png").read_bytes()
    require(all(name != b"exJM" for name, _, _ in chunks(stripped)), "independent rewrite retained recovery")
    with pixels(original) as first, pixels(stripped) as second:
        require(first.tobytes() == second.tobytes(), "stripped-only rewrite changed pixels")
    with (SOURCE / "external.csv").open(encoding="utf-8", newline="") as stream:
        rows = list(csv.DictReader(stream))
    expected = {(name, action) for name in ("external.png", "stripped.png") for action in ("ERROR", "WARN", "IGNORE")}
    require(len(rows) == 6 and {(row["file"], row["action"]) for row in rows} == expected, "external action matrix incomplete")
    require(all(row["recovered"] == "false" and row["reason"] == ("STALE_OR_CONFLICTING_COMPANION" if row["file"] == "external.png" else "MISSING_COMPANION") for row in rows), "external rewrite restored old model")
    renders = {}
    control, renders["control"] = pdf(SOURCE / "control.pdf", "ORIGINAL", "control")
    edited, renders["edited"] = pdf(SOURCE / "edited.pdf", "EDITED", "edited")
    caller, renders["consumer"] = pdf(consumer / "output/consumer-embedded-png.pdf", "CALLER", "consumer")
    edited_pixels(SOURCE / "original.png", SOURCE / "edited.png")
    edited_pixels(SOURCE / "original.png", consumer / "output/consumer-embedded-png.png")
    edited_pixels(control, edited)
    edited_pixels(control, caller)
    report = {"pngFiles": 7, "embeddedFiles": 5, "rounds": 3, "externalActions": len(rows), "pdfPages": 3,
              "renders": renders, "localBundle": check_bundle(consumer), "distributedLibraries": distributed_libraries(consumer)}
    (OUTPUT / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report))


if __name__ == "__main__":
    main()
