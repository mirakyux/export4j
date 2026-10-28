# SPDX-License-Identifier: Apache-2.0
"""Independently verify per-paint alpha, SVG transforms, final pixels and PDF/A-2b."""
import argparse
from hashlib import sha256
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

from PIL import Image
from pypdf import PdfReader
from pypdf.generic import ContentStream
from verify_advanced_pdf import require, difference
from verify_pdf_raster import references
from verify_production import contact_sheet

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build/verification/gap21-graphics"


def pixel(path, point, expected):
    with Image.open(path) as image:
        actual = image.convert("RGB").getpixel((point[0] * 2, point[1] * 2))
        # Alpha is quantized independently to 8-bit channels by the different renderers.
        require(max(abs(left - right) for left, right in zip(actual, expected)) <= 2,
                f"source-over pixel mismatch in {path.name} at {point}: {actual}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--consumer-build-directory", type=Path)
    parser.add_argument("--verapdf", type=Path, default=ROOT / "build/verification/verapdf/verapdf.bat")
    args = parser.parse_args()
    directory = args.consumer_build_directory or Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip())
    caller = directory / "output/gap21-graphics"
    OUTPUT.mkdir(parents=True, exist_ok=True)
    summary = json.loads((caller / "summary.json").read_text())
    require(summary == {"refusals": 8, "pages": 3}, "consumer did not complete all refusal paths")
    paint_pixels = [((20, 20), (255, 128, 128)), ((60, 40), (128, 64, 191)),
                    ((100, 50), (128, 128, 255)), ((160, 20), (255, 255, 255)),
                    ((200, 20), (0, 255, 0)), ((240, 20), (255, 255, 255)),
                    ((280, 20), (128, 255, 128)), ((180, 70), (191, 191, 255)),
                    ((240, 70), (0, 0, 0)), ((20, 120), (191, 191, 191))]
    svg_pixels = [((20, 20), (255, 128, 128)), ((60, 40), (128, 64, 191)),
                  ((170, 20), (191, 255, 191)), ((210, 20), (0, 255, 0)),
                  ((250, 20), (255, 255, 255)), ((290, 20), (191, 255, 191)),
                  ((250, 120), (0, 0, 0)), ((190, 110), (0, 0, 0)),
                  ((310, 160), (0, 0, 255)), ((20, 120), (191, 191, 191))]
    tiles, report = [], {"pixels": {}, "pdfResources": {}, "compliance": {}}
    for name, controls in (("paint", paint_pixels), ("svg-input", svg_pixels),
                           ("paint-a2b", [((20, 20), (255, 128, 128))])):
        source = caller / (name + ".pdf")
        reader = PdfReader(source, strict=True)
        require(len(reader.pages) == 1, "graphics sample changed page count")
        page = reader.pages[0]
        require(list(map(float, page.mediabox)) == [0, 0, 320, 180], "graphics geometry changed")
        states = page["/Resources"]["/ExtGState"]
        alpha = {(float(state.get_object().get("/ca", 1)), float(state.get_object().get("/CA", 1))) for state in states.values()}
        require((0.5, 0.25) in alpha, "PDF lacks explicit independent stroking and non-stroking alpha")
        operations = ContentStream(page.get_contents(), reader).operations
        require(any(operator == b"gs" for _, operator in operations), "PDF never applied transparency state")
        first, second = references(source, OUTPUT / name)
        require(len(first) == len(second) == 1, "independent render page count differs")
        images = [first[0], second[0]]
        if name != "paint-a2b":
            images.append(caller / (name + ".png"))
            tree = ET.parse(caller / (name + ".svg"))
            require(any(node.get("fill-opacity") is not None for node in tree.iter()), "SVG discarded fill alpha")
            require(any(node.get("stroke-opacity") == "0.25" for node in tree.iter()), "SVG discarded stroke alpha")
            require(not any(node.tag.endswith("g") and node.get("opacity") is not None for node in tree.iter()),
                    "SVG substituted multiplicative group opacity for paint state")
        for path in images:
            for point, expected in controls:
                pixel(path, point, expected)
            if name == "paint":
                with Image.open(path) as image:
                    text = image.convert("RGB").crop((20, 280, 300, 330))
                    darkest = min(min(value) for value in text.get_flattened_data())
                    require(abs(darkest - 153) <= 2, "text did not retain its independent 0.4 fill alpha")
            tiles.append((f"{name} {path.parent.name}", path))
        if name == "paint":
            require("Paint opacity" in page.extract_text(), "native PDF text lost searchability")
        # Text outlines and native PDF standard text use explicit fonts but can have different hinting;
        # the independent color controls above prove alpha without relaxing text geometry checks elsewhere.
        delta = difference(first[0], second[0], 1)
        require(delta["meanAbsoluteChannelError"] < 2, "independent PDF renderers disagree")
        report["pixels"][name] = {"controls": len(controls), "renderers": len(images), "comparison": delta}
        report["pdfResources"][name] = sorted(alpha)
    require(args.verapdf.is_file(), "veraPDF is required to validate the final archival sample")
    result = subprocess.run([str(args.verapdf), "--format", "xml", "--flavour", "2b", str(caller / "paint-a2b.pdf")],
                            capture_output=True, timeout=90, check=False)
    (OUTPUT / "paint-a2b-verapdf.xml").write_bytes(result.stdout)
    parsed = ET.fromstring(result.stdout)
    validations = parsed.findall(".//validationReport")
    require(len(validations) == 1 and validations[0].get("isCompliant") == "true", "transparent PDF/A-2b final file is not compliant")
    jobs = parsed.findall(".//job")
    require(len(jobs) == 1 and Path(jobs[0].findtext("item/name")).resolve() == (caller / "paint-a2b.pdf").resolve(),
            "compliance report describes another file")
    report["compliance"]["paint-a2b"] = {"profile": validations[0].get("profileName"),
                                          "sha256": sha256((caller / "paint-a2b.pdf").read_bytes()).hexdigest()}
    contact_sheet(tiles, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print("GAP-21 graphics: 3 final PDFs dual-rendered; 62 alpha/transform pixel controls; SVG attributes; 8 refusals; PDF/A-2b compliant")


if __name__ == "__main__":
    main()
