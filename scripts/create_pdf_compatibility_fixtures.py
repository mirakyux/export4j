# SPDX-License-Identifier: Apache-2.0
"""Build explicit-font controls and graphics-state PDFs without Export4j or PDFBox."""
from hashlib import sha256
from io import BytesIO
import json
from pathlib import Path
from pypdf import PdfReader, PdfWriter
from pypdf.generic import (
    ArrayObject, BooleanObject, DecodedStreamObject, DictionaryObject, FloatObject,
    NameObject, NumberObject,
)

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build/verification/pdf-compatibility-fixtures"
FONT = ROOT / "assets/fonts/LiberationSans-Regular.ttf"
MAPPING_TEXT = b"BT /F1 14 Tf 20 80 Td (MAPPED SECRET KEEP) Tj 0 -50 Td (OUTSIDE KEEP) Tj ET"
GS_TEXT = (b"BT /F1 12 Tf 20 95 Td (GS BEFORE) Tj ET "
           b"q /State gs 0.2 0.4 0.6 rg BT /F1 14 Tf 20 65 Td (GS BODY) Tj ET "
           b"20 54 m 200 54 l S Q BT /F1 12 Tf 20 35 Td (GS AFTER) Tj ET")


def dictionary(**items):
    return DictionaryObject({NameObject("/" + key): value for key, value in items.items()})


def numbers(values):
    return ArrayObject([FloatObject(value) for value in values])


def stream(writer, data):
    value = DecodedStreamObject()
    value.set_data(data)
    return writer._add_object(value.flate_encode())


def widths(standard):
    # Adobe Helvetica's uppercase/space metrics used by this original ASCII fixture.
    # Lowercase and punctuation are not painted. Arbitrary declared widths in the
    # other fixture deliberately differ from the supplied font's native metrics.
    uppercase = [667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833,
                 722, 778, 667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611]
    metrics = dict(zip(range(65, 91), uppercase, strict=True))
    metrics[32] = 278
    return [metrics.get(code, 0) if standard else 620 + code % 3 * 20 for code in range(32, 127)]


def simple_font(writer, *, embedded, standard=False):
    if standard and not embedded:
        return writer._add_object(dictionary(Type=NameObject("/Font"), Subtype=NameObject("/Type1"),
            BaseFont=NameObject("/Helvetica"), Encoding=NameObject("/WinAnsiEncoding")))
    descriptor = dictionary(Type=NameObject("/FontDescriptor"), FontName=NameObject("/LiberationSans" if embedded else "/ExternalFixture"),
        Flags=NumberObject(32), FontBBox=numbers([-200, -300, 1200, 1000]), Ascent=NumberObject(900),
        Descent=NumberObject(-250), CapHeight=NumberObject(700), ItalicAngle=NumberObject(0), StemV=NumberObject(80))
    if embedded:
        program = DecodedStreamObject()
        program.set_data(FONT.read_bytes())
        program[NameObject("/Length1")] = NumberObject(len(FONT.read_bytes()))
        descriptor[NameObject("/FontFile2")] = writer._add_object(program.flate_encode())
    unicode = (b"/CIDInit /ProcSet findresource begin 12 dict begin begincmap "
        b"/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def "
        b"/CMapName /FixtureUnicode def /CMapType 2 def "
        b"1 begincodespacerange <00> <FF> endcodespacerange "
        b"1 beginbfrange <20> <7E> <0020> endbfrange "
        b"endcmap CMapName currentdict /CMap defineresource pop end end")
    font = dictionary(Type=NameObject("/Font"), Subtype=NameObject("/TrueType"),
        BaseFont=NameObject("/LiberationSans" if embedded else "/ExternalFixture"),
        FontDescriptor=writer._add_object(descriptor), Encoding=NameObject("/WinAnsiEncoding"),
        FirstChar=NumberObject(32), LastChar=NumberObject(126), Widths=numbers(widths(standard)))
    if not standard:
        font[NameObject("/ToUnicode")] = stream(writer, unicode)
    return writer._add_object(font)


def page(writer, font, commands, states=None):
    result = writer.add_blank_page(240, 120)
    resources = dictionary(Font=dictionary(F1=font))
    if states is not None:
        resources[NameObject("/ExtGState")] = states
    result[NameObject("/Resources")] = resources
    result[NameObject("/Contents")] = stream(writer, commands)
    return result


def writer():
    result = PdfWriter()
    result.metadata = None
    return result


def save(name, document, records):
    encoded = BytesIO()
    document.write(encoded)
    data = encoded.getvalue()
    (OUTPUT / (name + ".pdf")).write_bytes(data)
    parsed = PdfReader(BytesIO(data))
    records[name] = {"sha256": sha256(data).hexdigest(), "pages": len(parsed.pages)}


def ordinary_state(blend):
    return dictionary(Type=NameObject("/ExtGState"), LW=FloatObject(2), LC=NumberObject(1),
        LJ=NumberObject(2), ML=FloatObject(5), D=ArrayObject([numbers([3, 2]), FloatObject(1)]),
        RI=NameObject("/RelativeColorimetric"), SA=BooleanObject(False),
        CA=FloatObject(1), ca=FloatObject(1), BM=NameObject("/" + blend), SMask=NameObject("/None"))


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    records = {}
    for name, standard in (("mapped-standard", True), ("mapped-widths", False)):
        for control in (False, True):
            document = writer()
            page(document, simple_font(document, embedded=control, standard=standard), MAPPING_TEXT)
            save(name + ("-control" if control else ""), document, records)
    for name, blend in (("gs-normal", "Normal"), ("gs-compatible", "Compatible")):
        document = writer()
        font = simple_font(document, embedded=True)
        page(document, font, GS_TEXT, dictionary(State=document._add_object(ordinary_state(blend))))
        save(name, document, records)
    for name in ("transparent", "blend", "font", "unknown", "missing", "malformed"):
        document = writer()
        font = simple_font(document, embedded=True)
        page(document, font, b"BT /F1 12 Tf 20 80 Td (SAFE PAGE) Tj ET")
        state = ordinary_state("Normal")
        if name == "transparent":
            state[NameObject("/ca")] = FloatObject(.5)
        elif name == "blend":
            state[NameObject("/BM")] = NameObject("/Multiply")
        elif name == "font":
            state[NameObject("/Font")] = ArrayObject([font, FloatObject(14)])
        elif name == "unknown":
            state[NameObject("/FixtureUnknown")] = NumberObject(1)
        elif name == "malformed":
            state[NameObject("/LW")] = NameObject("/WrongType")
        states = dictionary() if name == "missing" else dictionary(State=document._add_object(state))
        page(document, font, GS_TEXT, states)
        save("gs-" + name, document, records)
    (OUTPUT / "manifest.json").write_text(json.dumps({"fontSha256": sha256(FONT.read_bytes()).hexdigest(), "files": records}, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"fixtures": len(records), "pages": sum(item["pages"] for item in records.values())}))


if __name__ == "__main__":
    main()
