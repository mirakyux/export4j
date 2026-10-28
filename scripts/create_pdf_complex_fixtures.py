# SPDX-License-Identifier: Apache-2.0
"""Original clipped/transparency PDF inputs built independently with pypdf."""
from hashlib import sha256
from io import BytesIO
import json
from pathlib import Path
from pypdf import PdfWriter
from pypdf.generic import BooleanObject, FloatObject, NameObject
from create_pdf_compatibility_fixtures import dictionary, numbers, simple_font, stream
from create_pdf_form_fixtures import form

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build/verification/pdf-complex-fixtures"
TEXT = b"BT /F1 18 Tf 20 120 Td (COMPLEX CONTENT) Tj ET"


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    records = {}
    for kind in ("clip", "blend-alpha", "transparent-form", "soft-mask", "text-clip", "gs-font",
                 "invalid-alpha", "unknown-state", "missing-state"):
        writer = PdfWriter()
        writer.metadata = None
        font = simple_font(writer, embedded=True)
        resources = dictionary(Font=dictionary(F1=font))
        state = dictionary(Type=NameObject("/ExtGState"))
        background = b"0.9 0.7 0.2 rg 10 70 250 100 re f 0 0 0 rg "
        data = background + TEXT
        if kind == "clip":
            data = background + b"q 20 90 60 60 re W n " + TEXT + b" Q"
        elif kind == "blend-alpha":
            state.update(dictionary(ca=FloatObject(.45), CA=FloatObject(.6), BM=NameObject("/Multiply"),
                                    AIS=BooleanObject(False), TK=BooleanObject(True)))
            data = background + b"/GS gs 0.1 0.3 0.8 rg 30 80 140 70 re f 0 0 0 rg " + TEXT
        elif kind == "transparent-form":
            state[NameObject("/ca")] = FloatObject(.4)
            local = dictionary(Font=dictionary(F1=font), ExtGState=dictionary(GS=state))
            body = form(writer, b"/GS gs 0.1 0.3 0.8 rg 20 80 180 70 re f 0 0 0 rg " + TEXT,
                        local, bbox=(0, 0, 300, 240))
            body.get_object()[NameObject("/Group")] = dictionary(S=NameObject("/Transparency"),
                CS=NameObject("/DeviceRGB"), I=BooleanObject(True), K=BooleanObject(False))
            resources[NameObject("/XObject")] = dictionary(Fm=body)
            data = background + b"/Fm Do"
        elif kind == "soft-mask":
            mask = form(writer, b"0.5 g 0 0 300 240 re f", dictionary(), bbox=(0, 0, 300, 240))
            mask.get_object()[NameObject("/Group")] = dictionary(S=NameObject("/Transparency"), CS=NameObject("/DeviceGray"), I=BooleanObject(True))
            state[NameObject("/SMask")] = dictionary(S=NameObject("/Luminosity"), G=mask, BC=numbers([0]))
            data = background + b"/GS gs 0.1 0.3 0.8 rg 20 80 180 70 re f 0 0 0 rg " + TEXT
        elif kind == "text-clip":
            data = background + b"BT /F1 36 Tf 20 110 Td 7 Tr (MASK) Tj ET 0.1 0.3 0.8 rg 0 0 300 240 re f"
        elif kind == "gs-font":
            from pypdf.generic import ArrayObject
            state.update(dictionary(Font=ArrayObject([font, FloatObject(18)]), TK=BooleanObject(True)))
            data = background + b"BT /GS gs 20 120 Td (COMPLEX CONTENT) Tj ET"
        elif kind == "invalid-alpha":
            state[NameObject("/ca")] = FloatObject(2)
            data = b"/GS gs " + TEXT
        elif kind == "unknown-state":
            state[NameObject("/PrivateState")] = BooleanObject(True)
            data = b"/GS gs " + TEXT
        elif kind == "missing-state":
            data = b"/Missing gs " + TEXT
        resources[NameObject("/ExtGState")] = dictionary(GS=state)
        page = writer.add_blank_page(300, 240)
        page[NameObject("/Resources")] = resources
        page[NameObject("/Contents")] = stream(writer, data)
        encoded = BytesIO()
        writer.write(encoded)
        value = encoded.getvalue()
        (OUTPUT / (kind + ".pdf")).write_bytes(value)
        records[kind] = {"sha256": sha256(value).hexdigest(), "accepted": kind not in ("invalid-alpha", "unknown-state", "missing-state")}
    (OUTPUT / "manifest.json").write_text(json.dumps(records, indent=2) + "\n", encoding="utf-8")
    print("Created 9 original clipping/transparency fixtures")


if __name__ == "__main__":
    main()
