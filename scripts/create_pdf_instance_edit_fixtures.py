# SPDX-License-Identifier: Apache-2.0
"""Original cross-state text and shared-image controls, authored independently with pypdf."""
from pathlib import Path
from io import BytesIO
import json
from pypdf import PdfWriter
from pypdf.generic import DecodedStreamObject, NameObject, NumberObject
from create_pdf_compatibility_fixtures import dictionary, numbers, simple_font, stream, widths
from create_pdf_form_fixtures import form

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build/verification/pdf-instance-edit-fixtures"


def save(writer, name):
    output = BytesIO()
    writer.write(output)
    (OUTPUT / (name + ".pdf")).write_bytes(output.getvalue())


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    advance = sum(widths(False)[ord(c) - 32] for c in "SEC") * .014
    for name, barrier in (("font", "/F2 16 Tf"), ("color", "0.8 0.1 0.2 rg"),
                          ("spacing", "1 Tc"), ("td", f"{advance} 0 Td"),
                          ("tm", f"1 0 0 1 {20 + advance} 80 Tm"), ("other-line", "0 -20 Td")):
        writer = PdfWriter(); writer.metadata = None
        original = simple_font(writer, embedded=True); alternate = simple_font(writer, embedded=True)
        for index in range(2):
            page = writer.add_blank_page(240, 120)
            page[NameObject("/Resources")] = dictionary(Font=dictionary(F1=original, F2=alternate))
            if name == "font" and index == 0:
                program = DecodedStreamObject(); program.set_data((ROOT / "assets/fonts/LiberationSans-Bold.ttf").read_bytes())
                program[NameObject("/Length1")] = NumberObject(len(program.get_data()))
                alternate.get_object()["/FontDescriptor"][NameObject("/FontFile2")] = writer._add_object(program.flate_encode())
                alternate.get_object()[NameObject("/BaseFont")] = NameObject("/LiberationSans-Bold")
                alternate.get_object()["/FontDescriptor"][NameObject("/FontName")] = NameObject("/LiberationSans-Bold")
            commands = f"BT /F1 14 Tf 20 80 Td (SEC) Tj {barrier} [(RET)] TJ ( KEEP) Tj ET"
            page[NameObject("/Contents")] = stream(writer, commands.encode("ascii"))
        save(writer, name)
    for alpha in (False, True):
        writer = PdfWriter(); writer.metadata = None
        value = DecodedStreamObject(); value.set_data(bytes([255, 0, 0]) * 16)
        value.update(dictionary(Type=NameObject("/XObject"), Subtype=NameObject("/Image"), Width=NumberObject(4), Height=NumberObject(4),
                                ColorSpace=NameObject("/DeviceRGB"), BitsPerComponent=NumberObject(8)))
        if alpha:
            mask = DecodedStreamObject(); mask.set_data(bytes([128]) * 16)
            mask.update(dictionary(Type=NameObject("/XObject"), Subtype=NameObject("/Image"), Width=NumberObject(4), Height=NumberObject(4),
                                   ColorSpace=NameObject("/DeviceGray"), BitsPerComponent=NumberObject(8)))
            value[NameObject("/SMask")] = writer._add_object(mask.flate_encode())
        image = writer._add_object(value.flate_encode())
        child = form(writer, b"q 40 0 0 40 0 0 cm /Image Do Q q 40 0 0 40 40 0 cm /Image Do Q", dictionary(XObject=dictionary(Image=image)), bbox=(0, 0, 80, 40))
        outer = form(writer, b"/Child Do", dictionary(XObject=dictionary(Child=child)), bbox=(0, 0, 80, 40))
        for index in range(2):
            page = writer.add_blank_page(180, 100)
            page[NameObject("/Resources")] = dictionary(XObject=dictionary(Body=outer))
            page[NameObject("/Contents")] = stream(writer, b"q 1 0 0 1 10 10 cm /Body Do Q q 1 0.2 0 1 90 10 cm 0 0 70 35 re W n /Body Do Q")
        save(writer, "images-alpha" if alpha else "images")
    print(json.dumps({"files": 8, "pages": 16}))


if __name__ == "__main__":
    main()
