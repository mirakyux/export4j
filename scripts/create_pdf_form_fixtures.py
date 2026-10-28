# SPDX-License-Identifier: Apache-2.0
"""Create original Form XObject inputs independently with pypdf and explicit font bytes."""
from hashlib import sha256
from io import BytesIO
import json
from pathlib import Path
from pypdf import PdfReader, PdfWriter
from pypdf.generic import DecodedStreamObject, NameObject, NumberObject
from create_pdf_compatibility_fixtures import dictionary, numbers, simple_font, stream

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build/verification/pdf-form-fixtures"
FONT = ROOT / "assets/fonts/LiberationSans-Regular.ttf"
BEFORE = b"BT /F1 12 Tf 20 220 Td (PAGE BEFORE) Tj ET "
AFTER = b" BT /F1 12 Tf 20 20 Td (PAGE AFTER) Tj ET"


def form(writer, data, resources=None, bbox=(0, 0, 220, 90), matrix=(1, 0, 0, 1, 0, 0)):
    value = DecodedStreamObject()
    value.set_data(data)
    value.update(dictionary(Type=NameObject("/XObject"), Subtype=NameObject("/Form"),
                            FormType=NumberObject(1), BBox=numbers(bbox), Matrix=numbers(matrix)))
    if resources is not None:
        value[NameObject("/Resources")] = resources
    return writer._add_object(value.flate_encode())


def image(writer):
    value = DecodedStreamObject()
    value.set_data(bytes((34, 102, 170)) * 6 * 4)
    value.update(dictionary(Type=NameObject("/XObject"), Subtype=NameObject("/Image"),
        Width=NumberObject(6), Height=NumberObject(4), ColorSpace=NameObject("/DeviceRGB"), BitsPerComponent=NumberObject(8)))
    return writer._add_object(value.flate_encode())


def page(writer, font, data, objects=None):
    result = writer.add_blank_page(360, 240)
    result[NameObject("/Resources")] = dictionary(Font=dictionary(F1=font), XObject=objects or dictionary())
    result[NameObject("/Contents")] = stream(writer, data)
    return result


def expected(text, x, baseline, size=12, color="000000", width=None):
    # The source declares these advances explicitly; they intentionally differ
    # from the font's native metrics and exercise resource scoping.
    advance = sum(620 + ord(char) % 3 * 20 for char in text) * size / 1000 if width is None else width
    return {"text": text, "x": x, "baseline": 240 - baseline, "size": size, "color": color, "width": advance}


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    records = {}

    def save(name, writer, kind, contents=None):
        encoded = BytesIO()
        writer.write(encoded)
        data = encoded.getvalue()
        (OUTPUT / (name + ".pdf")).write_bytes(data)
        records[name] = {"sha256": sha256(data).hexdigest(), "pages": len(PdfReader(BytesIO(data)).pages),
                         "kind": kind, "contents": contents or []}

    def document():
        writer = PdfWriter()
        writer.metadata = None
        return writer, simple_font(writer, embedded=True)

    writer, font = document()
    body = form(writer, b"0.2 0.4 0.6 rg BT /F1 12 Tf 8 45 Td (FORM BODY) Tj ET "
        b"q 24 0 0 16 8 8 cm /Im Do Q", dictionary(Font=dictionary(F1=font), XObject=dictionary(Im=image(writer))),
        matrix=(1.25, 0, 0, 1.25, 7, 5))
    page(writer, font, BEFORE + b"q 1 0 0 1 25 70 cm /Fm Do Q" + AFTER, dictionary(Fm=body))
    save("form-matrix", writer, "accepted", [expected("PAGE BEFORE", 20, 220),
        expected("FORM BODY", 42, 131.25, 15, "336699"),
        {"text": "[IMAGE]", "x": 42, "y": 135, "width": 30, "height": 20}, expected("PAGE AFTER", 20, 20)])

    writer, font = document()
    inner_font = simple_font(writer, embedded=True)
    inner_font.get_object()[NameObject("/Widths")] = numbers([800] * 95)
    inner = form(writer, b"0 0.5 0.2 rg BT /F1 12 Tf 5 15 Td (NESTED ITEM) Tj ET", dictionary(Font=dictionary(F1=inner_font)), bbox=(0, 0, 180, 40))
    outer = form(writer, b"q 1 0 0 1 20 90 cm /Inner Do Q q 1 0 0 1 20 30 cm /Inner Do Q",
        dictionary(Font=dictionary(F1=font), XObject=dictionary(Inner=inner)), bbox=(0, 0, 220, 150), matrix=(1, 0, 0, 1, 5, 3))
    page(writer, font, BEFORE + b"q 1 0 0 1 30 25 cm /Fm Do Q" + AFTER, dictionary(Fm=outer))
    save("form-nested", writer, "accepted", [expected("PAGE BEFORE", 20, 220),
        expected("NESTED ITEM", 60, 133, color="008033", width=105.6),
        expected("NESTED ITEM", 60, 73, color="008033", width=105.6), expected("PAGE AFTER", 20, 20)])

    writer, font = document()
    inherited = form(writer, b"BT /F1 12 Tf 10 30 Td (INHERITED BODY) Tj ET")
    page(writer, font, BEFORE + b"q 1 0 0 1 30 100 cm /Fm Do Q" + AFTER, dictionary(Fm=inherited))
    save("form-inherited", writer, "accepted", [expected("PAGE BEFORE", 20, 220), expected("INHERITED BODY", 40, 130), expected("PAGE AFTER", 20, 20)])

    writer, font = document()
    unused = form(writer, b"0 0 10 10 re W n BT /F1 12 Tf 10 30 Td (UNUSED SECRET) Tj ET", dictionary(Font=dictionary(F1=font)))
    page(writer, font, BEFORE + AFTER, dictionary(Unused=unused))
    save("form-unused", writer, "accepted", [expected("PAGE BEFORE", 20, 220), expected("PAGE AFTER", 20, 20)])

    writer, font = document()
    hidden = form(writer, b"BT /F1 12 Tf 10 30 Td (VISIBLE BODY) Tj ET "
        b"BT /F1 12 Tf 10 90 Td (HIDDEN SECRET) Tj ET "
        b"q 24 0 0 16 10 100 cm /Im Do Q", dictionary(Font=dictionary(F1=font), XObject=dictionary(Im=image(writer))), bbox=(0, 0, 220, 60))
    page(writer, font, BEFORE + b"q 1 0 0 1 30 90 cm /Fm Do Q" + AFTER, dictionary(Fm=hidden))
    save("form-hidden", writer, "accepted", [expected("PAGE BEFORE", 20, 220), expected("VISIBLE BODY", 40, 120), expected("PAGE AFTER", 20, 20)])

    for kind in ("partial", "transparent", "clip", "bad-matrix", "missing-do", "late-invalid"):
        writer, font = document()
        page(writer, font, b"BT /F1 12 Tf 20 120 Td (SAFE PAGE) Tj ET")
        commands = b"BT /F1 12 Tf 10 30 Td (UNSUPPORTED SECRET) Tj ET"
        bbox = (0, 0, 220, 90)
        if kind == "partial":
            bbox = (0, 0, 35, 90)
        if kind in ("clip", "late-invalid"):
            commands = b"0 0 220 90 re W n " + commands
        if kind == "late-invalid":
            commands += b" /Absent Do"
        bad = form(writer, commands, dictionary(Font=dictionary(F1=font)), bbox=bbox)
        if kind == "transparent":
            bad.get_object()[NameObject("/Group")] = dictionary(S=NameObject("/Transparency"), CS=NameObject("/DeviceRGB"))
        if kind == "bad-matrix":
            bad.get_object()[NameObject("/Matrix")] = numbers((1, 0, 0, 1, 0))
        called = b"/Absent Do" if kind == "missing-do" else b"/Fm Do"
        page(writer, font, b"q 1 0 0 1 30 90 cm " + called + b" Q", dictionary(Fm=bad))
        save("form-" + kind, writer, "skipped" if kind in ("partial", "transparent", "clip") else "invalid")

    (OUTPUT / "manifest.json").write_text(json.dumps({"fontSha256": sha256(FONT.read_bytes()).hexdigest(), "files": records}, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"fixtures": len(records), "pages": sum(item["pages"] for item in records.values())}))


if __name__ == "__main__":
    main()
