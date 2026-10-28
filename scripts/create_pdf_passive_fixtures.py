# SPDX-License-Identifier: Apache-2.0
"""Create original optional-content and tagged PDF inputs with independent pypdf objects."""
from hashlib import sha256
from io import BytesIO
import json
from pathlib import Path
from pypdf import PdfReader, PdfWriter
from pypdf.generic import ArrayObject, BooleanObject, NameObject, NumberObject, TextStringObject
from create_pdf_compatibility_fixtures import dictionary, numbers, simple_font, stream
from create_pdf_form_fixtures import form

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build/verification/pdf-passive-fixtures"


def text(value, baseline):
    return f"BT /F1 12 Tf 20 {baseline} Td ({value}) Tj ET ".encode("ascii")


def document():
    writer = PdfWriter()
    writer.metadata = None
    font = simple_font(writer, embedded=True)
    page = writer.add_blank_page(360, 240)
    page[NameObject("/Resources")] = dictionary(Font=dictionary(F1=font))
    return writer, page, font


def tag(writer, page, source, *, form_stream=None, object_ref=None):
    root = dictionary(Type=NameObject("/StructTreeRoot"))
    root_ref = writer._add_object(root)
    element = dictionary(Type=NameObject("/StructElem"), S=NameObject("/P"), P=root_ref, Pg=page.indirect_reference)
    element_ref = writer._add_object(element)
    if form_stream is not None:
        form_stream.get_object()[NameObject("/StructParents")] = NumberObject(0)
        element[NameObject("/K")] = dictionary(Type=NameObject("/MCR"), Pg=page.indirect_reference, Stm=form_stream, MCID=NumberObject(0))
        page[NameObject("/Contents")] = stream(writer, source)
    else:
        page[NameObject("/StructParents")] = NumberObject(0)
        element[NameObject("/K")] = NumberObject(0)
        page[NameObject("/Contents")] = stream(writer, b"/P << /MCID 0 >> BDC " + source + b" EMC")
    root[NameObject("/K")] = element_ref
    entries = [NumberObject(0), ArrayObject([element_ref])]
    if object_ref is not None:
        object_ref.get_object()[NameObject("/StructParent")] = NumberObject(1)
        object_element = writer._add_object(dictionary(Type=NameObject("/StructElem"), S=NameObject("/Annot"),
            P=root_ref, Pg=page.indirect_reference, K=dictionary(Type=NameObject("/OBJR"), Pg=page.indirect_reference, Obj=object_ref)))
        root[NameObject("/K")] = ArrayObject([element_ref, object_element])
        entries.extend((NumberObject(1), object_element))
    root[NameObject("/ParentTree")] = writer._add_object(dictionary(Nums=ArrayObject(entries)))
    root[NameObject("/ParentTreeNextKey")] = NumberObject(2 if object_ref is not None else 1)
    writer._root_object[NameObject("/StructTreeRoot")] = root_ref
    writer._root_object[NameObject("/MarkInfo")] = dictionary(Marked=BooleanObject(True))
    return root, element


def layer_document(tagged=False):
    writer, page, font = document()
    on = writer._add_object(dictionary(Type=NameObject("/OCG"), Name=TextStringObject("Visible reports")))
    off = writer._add_object(dictionary(Type=NameObject("/OCG"), Name=TextStringObject("Hidden notes")))
    properties = dictionary(On=on, Off=off)
    for policy in ("AnyOn", "AllOn", "AnyOff", "AllOff"):
        properties[NameObject("/" + policy)] = writer._add_object(dictionary(Type=NameObject("/OCMD"), OCGs=ArrayObject([on, off]), P=NameObject("/" + policy)))
    properties[NameObject("/Expression")] = writer._add_object(dictionary(Type=NameObject("/OCMD"), VE=ArrayObject([
        NameObject("/And"), on, ArrayObject([NameObject("/Not"), off])])))
    hidden_form = form(writer, text("FORM SECRET", 15), dictionary(Font=dictionary(F1=font)), bbox=(0, 0, 220, 30))
    hidden_form.get_object()[NameObject("/OC")] = off
    resources = page["/Resources"]
    resources[NameObject("/Properties")] = properties
    resources[NameObject("/XObject")] = dictionary(Hidden=hidden_form)
    config = dictionary(BaseState=NameObject("/OFF"), ON=ArrayObject([on]), OFF=ArrayObject([off]),
        Order=ArrayObject([on, off]), Locked=ArrayObject([off]))
    writer._root_object[NameObject("/OCProperties")] = dictionary(OCGs=ArrayObject([on, off]), D=config)
    visible = [("LAYER TITLE", 220), ("VISIBLE ON", 190), ("VISIBLE ANY ON", 140), ("VISIBLE ANY OFF", 90), ("VISIBLE EXPRESSION", 40)]
    commands = text("LAYER TITLE", 220)
    for prop, phrase, baseline in (("On", "VISIBLE ON", 190), ("Off", "HIDDEN SECRET", 165),
            ("AnyOn", "VISIBLE ANY ON", 140), ("AllOn", "ALL ON SECRET", 115),
            ("AnyOff", "VISIBLE ANY OFF", 90), ("AllOff", "ALL OFF SECRET", 65),
            ("Expression", "VISIBLE EXPRESSION", 40)):
        commands += b"/OC /" + prop.encode("ascii") + b" BDC " + text(phrase, baseline) + b"EMC "
    commands += b"q 1 0 0 1 20 0 cm /Hidden Do Q"
    if tagged:
        tag(writer, page, commands)
    else:
        page[NameObject("/Contents")] = stream(writer, commands)
    return writer, visible


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    records = {}

    def save(name, writer, kind, visible, tagged=False, layered=False):
        encoded = BytesIO()
        writer.write(encoded)
        data = encoded.getvalue()
        (OUTPUT / (name + ".pdf")).write_bytes(data)
        records[name] = {"kind": kind, "sha256": sha256(data).hexdigest(), "pages": len(PdfReader(BytesIO(data)).pages),
            "visible": visible, "tagged": tagged, "layered": layered}

    for name, tagged in (("layers", False), ("tagged-layers", True)):
        writer, visible = layer_document(tagged)
        save(name, writer, "accepted", visible, tagged, True)
        control, page, font = document()
        page[NameObject("/Contents")] = stream(control, b"".join(text(value, baseline) for value, baseline in visible))
        save(name + "-control", control, "control", visible)

    writer, page, font = document()
    tag(writer, page, text("TAGGED BODY", 160))
    save("tagged-page", writer, "accepted", [("TAGGED BODY", 160)], True)

    writer, page, font = document()
    body = form(writer, b"/P << /MCID 0 >> BDC " + text("TAGGED FORM", 30) + b"EMC", dictionary(Font=dictionary(F1=font)))
    page["/Resources"][NameObject("/XObject")] = dictionary(Fm=body)
    tag(writer, page, b"q 1 0 0 1 20 90 cm /Fm Do Q", form_stream=body)
    save("tagged-form", writer, "accepted", [("TAGGED FORM", 120)], True)

    writer, page, font = document()
    appearance = form(writer, b"0.2 0.4 0.6 rg 0 0 10 10 re f", dictionary(), bbox=(0, 0, 10, 10))
    annotation = writer._add_object(dictionary(Type=NameObject("/Annot"), Subtype=NameObject("/Text"),
        Rect=numbers((300, 20, 310, 30)), Contents=TextStringObject("Original annotation"),
        P=page.indirect_reference, AP=dictionary(N=appearance)))
    page[NameObject("/Annots")] = ArrayObject([annotation])
    tag(writer, page, text("TAGGED OBJECT", 160), object_ref=annotation)
    save("tagged-object", writer, "accepted", [("TAGGED OBJECT", 160)], True)

    writer, visible = layer_document()
    writer._root_object["/OCProperties"]["/D"][NameObject("/ON")] = ArrayObject([writer._add_object(dictionary(Type=NameObject("/OCG"), Name=TextStringObject("Undeclared")))])
    save("invalid-layer-reference", writer, "invalid", [])
    writer, page, font = document()
    root, element = tag(writer, page, text("BAD TREE", 160))
    root["/ParentTree"]["/Nums"][1] = ArrayObject([])
    save("invalid-parent-tree", writer, "invalid", [])
    writer, page, font = document()
    root, element = tag(writer, page, text("BAD CYCLE", 160))
    element[NameObject("/K")] = root.raw_get(NameObject("/K"))
    save("invalid-structure-cycle", writer, "invalid", [])
    (OUTPUT / "manifest.json").write_text(json.dumps({"files": records}, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"fixtures": len(records), "pages": sum(item["pages"] for item in records.values())}))


if __name__ == "__main__":
    main()
