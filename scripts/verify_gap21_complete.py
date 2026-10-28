# SPDX-License-Identifier: Apache-2.0
"""Verify native advanced graphics, five final PDFs, SVG resources and source-SVG appearance."""
import argparse
from hashlib import sha256
from importlib import metadata
import json
from pathlib import Path
import subprocess
import time
from uuid import uuid4
import xml.etree.ElementTree as ET

from PIL import Image
from pypdf import PdfReader
from pypdf.generic import ContentStream
import resvg_py

from verify_advanced_pdf import difference, require
from verify_pdf_raster import references
from verify_production import contact_sheet

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build/verification/gap21-complete"
BLENDS = {"Normal", "Multiply", "Screen", "Overlay", "Darken", "Lighten", "ColorDodge", "ColorBurn",
          "HardLight", "SoftLight", "Difference", "Exclusion", "Hue", "Saturation", "Color", "Luminosity"}


def pdf_resources(page, reader):
    result = {"shadings": 0, "patterns": 0, "groups": 0, "softMasks": 0, "blends": set(),
              "separations": set(), "icc": [], "deviceCmyk": False}
    seen = set()

    def color(space):
        space = space.get_object()
        if space == "/DeviceCMYK":
            result["deviceCmyk"] = True
        elif isinstance(space, list) and space:
            if space[0] == "/Separation":
                result["separations"].add(str(space[1])[1:])
                color(space[2])
            elif space[0] == "/ICCBased":
                profile = space[1].get_object()
                data = profile.get_data()
                require(len(data) >= 128 and data[36:40] == b"acsp", "ICC resource is not an actual ICC program")
                result["icc"].append({"channels": profile["/N"], "sha256": sha256(data).hexdigest(), "bytes": len(data)})

    def stream(owner):
        owner = owner.get_object()
        if id(owner) in seen:
            return
        seen.add(id(owner))
        if "/Group" in owner:
            group = owner["/Group"]
            require(group.get("/S") == "/Transparency" and group.get("/I") is not None, "group attributes are incomplete")
            result["groups"] += 1
        resources = owner.get("/Resources", {}).get_object() if "/Resources" in owner else {}
        for space in resources.get("/ColorSpace", {}).values():
            color(space)
        for state in resources.get("/ExtGState", {}).values():
            state = state.get_object()
            if "/BM" in state:
                result["blends"].add(str(state["/BM"])[1:])
            mask = state.get("/SMask")
            mask = mask.get_object() if hasattr(mask, "get_object") else mask
            if isinstance(mask, dict):
                result["softMasks"] += 1
                stream(mask["/G"])
        for shading in resources.get("/Shading", {}).values():
            shading = shading.get_object()
            require(shading["/ShadingType"] == 1 and shading["/Function"]["/FunctionType"] == 4,
                    "gradient became an image or lost its native function")
            color(shading["/ColorSpace"])
            result["shadings"] += 1
        for pattern in resources.get("/Pattern", {}).values():
            pattern = pattern.get_object()
            require(pattern["/PatternType"] == 1 and pattern["/XStep"] > 0 and pattern["/YStep"] > 0,
                    "vector tile is not a native bounded tiling pattern")
            result["patterns"] += 1
            stream(pattern)
        for item in resources.get("/XObject", {}).values():
            item = item.get_object()
            require(item.get("/Subtype") != "/Image", "advanced vector paint was silently rasterized")
            stream(item)
        contents = owner.get_contents() if hasattr(owner, "get_contents") else owner
        if hasattr(contents, "get_data"):
            for operands, operator in ContentStream(contents, reader).operations:
                if operator in (b"k", b"K") or operator in (b"cs", b"CS") and operands == ["/DeviceCMYK"]:
                    result["deviceCmyk"] = True

    stream(page)
    for key in ("blends", "separations"):
        result[key] = sorted(result[key])
    return result


def pixel(path, point, expected, tolerance=2):
    with Image.open(path) as image:
        actual = image.convert("RGB").getpixel((point[0] * 2, point[1] * 2))
    require(max(abs(a - b) for a, b in zip(actual, expected)) <= tolerance,
            f"pixel mismatch in {path.name} at {point}: {actual}, expected {expected}")


def compare(left, right, *, max_mean=2, max_fraction=.02):
    delta = difference(left, right, 1)
    require(delta["meanAbsoluteChannelError"] < max_mean and delta["changedPixelFraction"] < max_fraction,
            f"render mismatch {left.name} / {right.name}: {delta}")
    return delta


def chromium_source(source, executable):
    require(executable.is_file(), "an explicit Chromium executable is required for source SVG verification")
    namespace = "http://www.w3.org/2000/svg"
    ET.register_namespace("", namespace)
    ET.register_namespace("xlink", "http://www.w3.org/1999/xlink")
    svg = ET.parse(source).getroot()
    svg.set("width", "840")
    svg.set("height", "720")
    html = OUTPUT / "source.html"
    html.write_text("<!doctype html><meta charset='utf-8'><style>html,body{margin:0;width:840px;height:720px;background:white}svg{display:block}</style>"
                    + ET.tostring(svg, encoding="unicode"), encoding="utf-8")
    output = OUTPUT / "source-chromium.png"
    output.unlink(missing_ok=True)
    profile = OUTPUT / ("chromium-profile-" + uuid4().hex)
    process = subprocess.Popen([str(executable), "--headless=new", "--disable-gpu", "--hide-scrollbars", "--disable-extensions",
                                "--no-first-run", "--no-default-browser-check", "--user-data-dir=" + str(profile),
                                "--screenshot=" + str(output), "--window-size=840,720", html.as_uri()],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            if output.exists():
                try:
                    with Image.open(output) as image:
                        image.verify()
                    break
                except (OSError, SyntaxError):
                    pass
            time.sleep(.05)
        else:
            raise AssertionError("Chromium did not produce a complete source-SVG screenshot within 45 seconds")
        process.wait(timeout=10)
    finally:
        if process.poll() is None:
            process.kill()
            process.wait(timeout=10)
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--consumer-build-directory", type=Path)
    parser.add_argument("--chromium", type=Path, required=True)
    parser.add_argument("--verapdf", type=Path, default=ROOT / "build/verification/verapdf/verapdf.bat")
    args = parser.parse_args()
    directory = args.consumer_build_directory or Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip())
    caller = directory / "output/gap21-complete"
    OUTPUT.mkdir(parents=True, exist_ok=True)
    summary = json.loads((caller / "summary.json").read_text())
    require(summary["pages"] == 5 and summary["blendModes"] == 16 and summary["losses"] >= 18 and summary["refusals"] == 8,
            "consumer did not finish the full paint, policy and reference controls")
    report = {"summary": summary, "resources": {}, "rendering": {}, "tools": {name: metadata.version(name) for name in ("resvg-py", "pypdf", "pypdfium2", "Pillow")}}
    tiles = []
    for name in ("gradients", "blends", "colors", "svg-input", "advanced-a2b"):
        source = caller / (name + ".pdf")
        reader = PdfReader(source, strict=True)
        require(len(reader.pages) == 1 and list(map(float, reader.pages[0].mediabox)) == [0, 0, 420, 360], "paint page geometry changed")
        resources = pdf_resources(reader.pages[0], reader)
        report["resources"][name] = resources
        if name in ("gradients", "advanced-a2b"):
            require(resources["shadings"] >= 9 and resources["patterns"] >= 1 and resources["softMasks"] >= 1 and resources["groups"] >= 2,
                    "native gradient, tile, alpha mask or isolated pattern paint is absent")
        elif name == "blends":
            require(set(resources["blends"]) == BLENDS and resources["groups"] >= 1, "all sixteen PDF blend modes and isolated group are required")
        elif name == "colors":
            require(resources["deviceCmyk"] and resources["icc"] and all(item["channels"] == 3 for item in resources["icc"])
                    and "Export4j Orange" in resources["separations"], "PDF lost CMYK, explicit ICC or named ink")
        else:
            require(resources["patterns"] >= 2 and resources["shadings"] >= 3 and resources["groups"] >= 1,
                    "SVG input did not retain its resolved vector resources")
        first, second = references(source, OUTPUT / name)
        require(len(first) == len(second) == 1, "independent PDF render page count differs")
        metrics = {"pdfEngines": compare(first[0], second[0])}
        checks = [first[0], second[0]]
        tiles.extend([(name + " Poppler", first[0]), (name + " PDFium", second[0])])
        if name != "advanced-a2b":
            png = caller / (name + ".png")
            tree = ET.parse(caller / (name + ".svg"))
            ids = [node.get("id") for node in tree.iter() if node.get("id")]
            require(len(ids) == len(set(ids)), "SVG resource identifiers collide")
            svg = OUTPUT / (name + "-resvg.png")
            svg.write_bytes(resvg_py.svg_to_bytes(svg_path=str(caller / (name + ".svg")), width=840, height=720, dpi=144,
                                                background="#ffffff", skip_system_fonts=True))
            metrics["svgPng"] = compare(svg, png, max_mean=1, max_fraction=.01)
            if name != "colors":
                metrics["pngPdf"] = compare(png, second[0])
            else:
                # Device CMYK deliberately retains a print space in PDF, while these two targets report RGB conversion.
                # ICC and named-ink RGB alternates have comparable appearance and are checked separately.
                for index, bounds in enumerate(((150, 10, 410, 100), (220, 130, 410, 210), (10, 240, 410, 320))):
                    paths = []
                    for label, path in (("png", png), ("pdf", second[0])):
                        target = OUTPUT / f"colors-control-{index}-{label}.png"
                        with Image.open(path) as image:
                            image.crop(tuple(value * 2 for value in bounds)).save(target)
                        paths.append(target)
                    metrics[f"colorControl{index}"] = compare(*paths, max_mean=1, max_fraction=.01)
            checks.extend((png, svg))
            tiles.extend([(name + " PNG", png), (name + " SVG resvg", svg)])
        if name in ("gradients", "advanced-a2b"):
            for path in checks:
                for point, expected in (((20, 25), (255, 0, 0)), ((150, 25), (0, 0, 255)), ((225, 235), (255, 102, 102)), ((210, 315), (89, 255, 89))):
                    pixel(path, point, expected, 3)
        if name == "blends":
            for path in checks:
                pixel(path, (70, 330), (128, 128, 255))
                pixel(path, (230, 330), (128, 64, 191))
            for index in range(16):
                x, y = 45 + index % 4 * 102, 35 + index // 4 * 72
                with Image.open(checks[0]) as reference:
                    expected = reference.convert("RGB").getpixel((x * 2, y * 2))
                for path in checks[1:]:
                    pixel(path, (x, y), expected)
        if name == "colors":
            for path in checks:
                pixel(path, (180, 50), (51, 153, 230))
                pixel(path, (340, 50), (255, 102, 0))
        report["rendering"][name] = metrics

    chromium = chromium_source(caller / "source.svg", args.chromium.resolve())
    report["sourceSvg"] = compare(chromium, caller / "svg-input.png", max_mean=1, max_fraction=.01)
    tiles.append(("source SVG Chromium", chromium))
    require(args.verapdf.is_file(), "veraPDF is required for the final advanced-graphics archival sample")
    result = subprocess.run([str(args.verapdf), "--format", "xml", "--flavour", "2b", str(caller / "advanced-a2b.pdf")],
                            capture_output=True, timeout=90, check=False)
    (OUTPUT / "advanced-a2b-verapdf.xml").write_bytes(result.stdout)
    parsed = ET.fromstring(result.stdout)
    validations = parsed.findall(".//validationReport")
    require(len(validations) == 1 and validations[0].get("isCompliant") == "true", "advanced native resources are not PDF/A-2b compliant")
    jobs = parsed.findall(".//job")
    require(len(jobs) == 1 and Path(jobs[0].findtext("item/name")).resolve() == (caller / "advanced-a2b.pdf").resolve(), "archival report describes another file")
    report["archival"] = {"profile": validations[0].get("profileName"), "sha256": sha256((caller / "advanced-a2b.pdf").read_bytes()).hexdigest()}
    contact_sheet(tiles, OUTPUT)
    report["renderedImages"] = len(tiles)
    (OUTPUT / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print("GAP-21 complete: 5 final PDFs dual-rendered; 4 SVGs resvg-rendered; source SVG Chromium-compared; 16 blends; native inks/ICC/patterns/soft masks; policies; PDF/A-2b")


if __name__ == "__main__":
    main()
