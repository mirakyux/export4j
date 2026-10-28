# SPDX-License-Identifier: Apache-2.0
"""Original AcroForm, FDF/XFDF and static/dynamic XFA fixtures written independently with pypdf."""
from hashlib import sha256
from io import BytesIO
import json
from pathlib import Path
from pypdf import PdfWriter
from pypdf.generic import ArrayObject, BooleanObject, NameObject, NumberObject, TextStringObject
from create_pdf_compatibility_fixtures import dictionary, numbers, simple_font, stream

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build/verification/pdf-extended-forms-fixtures"


def pdf_form():
    writer = PdfWriter()
    writer.metadata = None
    page = writer.add_blank_page(420, 460)
    second = writer.add_blank_page(420, 460)
    font = simple_font(writer, embedded=True)
    resources = dictionary(Font=dictionary(F1=font))
    for value in (page, second):
        value[NameObject("/Resources")] = resources
    second[NameObject("/Contents")] = stream(writer, b"BT /F1 16 Tf 30 400 Td (HIGHLIGHT TARGET) Tj 0 -50 Td (UNDERLINE TARGET) Tj 0 -50 Td (STRIKEOUT TARGET) Tj ET")
    fields = ArrayObject()
    for name, rect, flags, value in (("multi", (20, 310, 240, 420), 4096, "OLD MULTI"),
                                   ("comb", (20, 250, 240, 292), 1 << 24, "OLD"),
                                   ("rich", (20, 175, 300, 230), (1 << 12) | (1 << 25), "OLD RICH"),
                                   ("list", (270, 300, 400, 420), 1 << 21, ["B"])):
        appearance = dictionary(Type=NameObject("/XObject"), Subtype=NameObject("/Form"),
                                BBox=numbers((0, 0, rect[2] - rect[0], rect[3] - rect[1])), Resources=resources)
        appearance_ref = stream(writer, b"0.95 g 0 0 100 20 re f BT /F1 10 Tf 4 8 Td (OLD) Tj ET")
        appearance_ref.get_object().update(appearance)
        field = dictionary(Type=NameObject("/Annot"), Subtype=NameObject("/Widget"), FT=NameObject("/Ch" if name == "list" else "/Tx"),
                           T=TextStringObject(name), Ff=NumberObject(flags), Rect=numbers(rect), P=page.indirect_reference,
                           V=ArrayObject([TextStringObject(v) for v in value]) if isinstance(value, list) else TextStringObject(value),
                           DA=TextStringObject("/F1 10 Tf 0 g"), F=NumberObject(4),
                           AP=dictionary(N=appearance_ref), MK=dictionary(BG=numbers((.96, .97, 1)), BC=numbers((.2, .3, .5))),
                           BS=dictionary(W=NumberObject(1), S=NameObject("/S")))
        if name == "comb":
            field[NameObject("/MaxLen")] = NumberObject(8)
        if name == "multi":
            field["/AP"][NameObject("/R")] = appearance_ref
            field["/AP"][NameObject("/D")] = dictionary(Pressed=appearance_ref)
        if name == "rich":
            field[NameObject("/RV")] = TextStringObject('<body xmlns="http://www.w3.org/1999/xhtml"><p>OLD RICH</p></body>')
        if name == "list":
            field[NameObject("/Opt")] = ArrayObject([ArrayObject([TextStringObject(code), TextStringObject(label)]) for code, label in (("A", "Alpha"), ("B", "Beta"), ("C", "Gamma"))])
            field[NameObject("/I")] = ArrayObject([NumberObject(1)])
        fields.append(writer._add_object(field))
    page[NameObject("/Annots")] = fields
    writer._root_object[NameObject("/AcroForm")] = writer._add_object(dictionary(Fields=fields, DR=resources, DA=TextStringObject("/F1 10 Tf 0 g"), NeedAppearances=BooleanObject(False)))
    return writer


def xfa(dynamic=False, *, xdp=False, invalid=""):
    writer = PdfWriter()
    writer.metadata = None
    writer.add_blank_page(300, 220)
    page = "<pageSet><pageArea><medium short='300pt' long='220pt'/><contentArea x='20pt' y='20pt' w='260pt' h='160pt'/></pageArea></pageSet>"
    field = "<field name='name' w='180pt' h='26pt'><ui><textEdit/></ui><value><text>DEFAULT</text></value></field>"
    body = "<subform name='row' layout='position' h='30pt'><occur min='0' max='-1'/>" + field + "</subform>" if dynamic else field
    if invalid == "script":
        body += "<event><script contentType='application/x-javascript'>invalid()</script></event>"
    if invalid == "external":
        body += "<draw w='20pt' h='20pt'><value><image href='https://example.invalid/image.png'/></value></draw>"
    template = "<template xmlns='http://www.xfa.org/schema/xfa-template/3.3/'><subform name='form' layout='" + ("tb" if dynamic else "position") + "'>" + page + body + "</subform></template>"
    values = [("ROW-" + str(i)) for i in range(8)] if dynamic else ["STATIC DATA VALUE"]
    records = "".join("<row><name>" + value + "</name></row>" for value in values) if dynamic else "<name>" + values[0] + "</name>"
    datasets = "<xfa:datasets xmlns:xfa='http://www.xfa.org/schema/xfa-data/1.0/'><xfa:data><form>" + records + "</form></xfa:data></xfa:datasets>"
    packets = stream(writer, ("<xdp:xdp xmlns:xdp='http://ns.adobe.com/xdp/'>" + template + datasets + "</xdp:xdp>").encode()) if xdp else ArrayObject([TextStringObject("template"), stream(writer, template.encode()), TextStringObject("datasets"), stream(writer, datasets.encode())])
    writer._root_object[NameObject("/AcroForm")] = writer._add_object(dictionary(Fields=ArrayObject(), XFA=packets))
    return writer, values


def fdf(values, action=False):
    writer = PdfWriter()
    writer.metadata = None
    writer._header = b"%FDF-1.2"
    writer._root_object.clear()
    fields = ArrayObject()
    for name, items in values.items():
        value = TextStringObject(items[0]) if len(items) == 1 else ArrayObject([TextStringObject(item) for item in items])
        field = dictionary(T=TextStringObject(name), V=value)
        if action:
            field[NameObject("/A")] = dictionary(S=NameObject("/JavaScript"), JS=TextStringObject("invalid()"))
        fields.append(writer._add_object(field))
    writer._root_object[NameObject("/FDF")] = dictionary(Fields=fields)
    return writer


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    manifest = {}

    def save(name, value, **record):
        if isinstance(value, PdfWriter):
            target = BytesIO()
            value.write(target)
            value = target.getvalue()
        elif isinstance(value, str):
            value = value.encode("utf-8")
        (OUTPUT / name).write_bytes(value)
        manifest[name] = {"sha256": sha256(value).hexdigest(), **record}

    save("forms.pdf", pdf_form(), kind="acroform", pages=2)
    for name, dynamic, xdp in (("xfa-static.pdf", False, False), ("xfa-xdp.pdf", False, True), ("xfa-dynamic.pdf", True, False)):
        writer, values = xfa(dynamic, xdp=xdp)
        save(name, writer, kind="xfa", dynamic=dynamic, values=values, pages=2 if dynamic else 1)
    for kind in ("script", "external"):
        writer, values = xfa(invalid=kind)
        save("xfa-invalid-" + kind + ".pdf", writer, kind="invalid-xfa")
    values = {"multi": ["EXCHANGED FIRST\nEXCHANGED SECOND"], "comb": ["DATA123"], "list": ["A", "C"]}
    save("values.fdf", fdf(values), kind="data", values=values)
    save("values.xfdf", '<xfdf xmlns="http://ns.adobe.com/xfdf/"><fields><field name="multi"><value>EXCHANGED FIRST\nEXCHANGED SECOND</value></field><field name="comb"><value>DATA123</value></field><field name="list"><value>A</value><value>C</value></field></fields></xfdf>', kind="data", values=values)
    save("unknown-property.xfdf", '<xfdf xmlns="http://ns.adobe.com/xfdf/"><fields><field name="multi" local-property="passive"><value>PASSIVE DATA</value></field></fields></xfdf>', kind="passive-data")
    save("invalid-action.fdf", fdf({"multi": ["BAD"]}, action=True), kind="invalid-data")
    save("invalid-xxe.xfdf", '<!DOCTYPE xfdf [<!ENTITY e SYSTEM "file:///unavailable">]><xfdf xmlns="http://ns.adobe.com/xfdf/"><fields><field name="multi"><value>&e;</value></field></fields></xfdf>', kind="invalid-data")
    (OUTPUT / "manifest.json").write_text(json.dumps({"files": manifest}, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"fixtures": len(manifest), "sourcePdfFiles": sum(name.endswith(".pdf") for name in manifest)}))


if __name__ == "__main__":
    main()
