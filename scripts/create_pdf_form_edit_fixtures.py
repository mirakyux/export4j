# SPDX-License-Identifier: Apache-2.0
"""Create shared Form editing controls independently with pypdf and explicit TTF bytes."""
from hashlib import sha256
from io import BytesIO
import json
from pathlib import Path
from pypdf import PdfReader, PdfWriter
from pypdf.generic import FloatObject, NameObject, TextStringObject
from create_pdf_compatibility_fixtures import dictionary, numbers, simple_font, stream
from create_pdf_form_fixtures import form

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build/verification/pdf-form-edit-fixtures"
FONT = ROOT / "assets/fonts/LiberationSans-Regular.ttf"
TEXT = b"BT /F1 12 Tf 10 25 Td (SECRET KEEP) Tj ET"


def page(writer, font, body, untouched, commands):
    value = writer.add_blank_page(600, 400)
    value[NameObject("/Resources")] = dictionary(Font=dictionary(F1=font), XObject=dictionary(Body=body, Untouched=untouched))
    value[NameObject("/Contents")] = stream(writer, commands)
    return value


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    records = {}
    for name in ("shared", "nested", "inherited", "shadow", "spacing", "transparent", "marked", "malformed-matrix", "malformed-resource"):
        writer = PdfWriter()
        writer.metadata = None
        font = simple_font(writer, embedded=True)
        alternate = simple_font(writer, embedded=True)
        alternate.get_object()[NameObject("/Widths")] = numbers([800] * 95)
        alternate.get_object()[NameObject("/BaseFont")] = NameObject("/ScopedFixture")
        resources = dictionary(Font=dictionary(F1=font))
        commands = TEXT
        if name == "nested":
            inner = form(writer, TEXT, dictionary(Font=dictionary(F1=alternate)), bbox=(0, 0, 180, 60), matrix=(1, 0, 0, 1, 3, 2))
            resources[NameObject("/XObject")] = dictionary(Child=inner)
            commands = b"BT /F1 12 Tf 10 65 Td (OUTER KEEP) Tj ET q 1 0 0 1 8 5 cm /Child Do Q"
        elif name == "inherited":
            resources = None
        elif name == "shadow":
            resources = dictionary(Font=dictionary(F1=alternate))
            commands = b"BT 10 25 Td (SECRET KEEP) Tj ET"
        elif name == "transparent":
            resources[NameObject("/ExtGState")] = dictionary(State=writer._add_object(dictionary(ca=FloatObject(.5))))
            commands = b"/State gs " + commands
        elif name == "marked":
            commands = b"/Span BMC " + commands + b" EMC"
        if name == "shared":
            resources[NameObject("/ExtGState")] = dictionary(State=writer._add_object(dictionary(ca=FloatObject(1), CA=FloatObject(1), BM=NameObject("/Normal"))))
            commands = b"q /State gs 0.2 0.4 0.6 rg " + commands + b" Q"
        body = form(writer, commands, resources, bbox=(0, 0, 220, 90), matrix=(1.25, 0, 0, 1.25, 7, 5))
        if name == "malformed-matrix":
            body.get_object()[NameObject("/Matrix")] = TextStringObject("invalid matrix")
        elif name == "malformed-resource":
            body.get_object()[NameObject("/Resources")] = dictionary()
        untouched = form(writer, b"BT /F1 12 Tf 10 25 Td (CONTROL KEEP) Tj ET", dictionary(Font=dictionary(F1=font)))
        spacing = b"1 Tc " if name == "spacing" else b""
        initialize = b"BT /F1 12 Tf ET " if name == "shadow" else b""
        page(writer, font, body, untouched, initialize + b"q " + spacing + b"1 0 0 1 25 100 cm /Body Do Q "
             b"q 1 0 0 1 25 230 cm /Body Do Q q 1 0 0 1 330 100 cm /Untouched Do Q")
        page(writer, font, body, untouched, initialize + b"q 1 0 0 1 25 100 cm /Body Do Q "
             b"BT /F1 12 Tf 330 300 Td (CONTROL PAGE) Tj ET")
        encoded = BytesIO()
        writer.write(encoded)
        data = encoded.getvalue()
        (OUTPUT / (name + ".pdf")).write_bytes(data)
        kind = "invalid" if name.startswith("malformed") else "skip" if name in ("transparent", "marked") else "edited"
        records[name] = {"sha256": sha256(data).hexdigest(), "pages": len(PdfReader(BytesIO(data)).pages), "kind": kind,
                         "changes": 0 if kind != "edited" else 1 if name == "spacing" else 2,
                         "editedSecretIndices": [1] if name == "spacing" else [0, 1] if kind == "edited" else [],
                         "strictFeature": "TEXT_SHAPING" if name == "spacing" else "TEXT_STRUCTURE" if name == "transparent" else "MARKED_TEXT" if name == "marked" else "TEXT_FONT"}
    (OUTPUT / "manifest.json").write_text(json.dumps({"fontSha256": sha256(FONT.read_bytes()).hexdigest(), "files": records}, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"files": len(records), "pages": sum(record["pages"] for record in records.values())}))


if __name__ == "__main__":
    main()
