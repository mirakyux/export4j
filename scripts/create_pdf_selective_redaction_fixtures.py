# SPDX-License-Identifier: Apache-2.0
"""Independent static source controls; no Export4j/PDFBox output is used as input."""
from hashlib import sha256
from io import BytesIO
import json
from pathlib import Path
from pypdf import PdfWriter
from pypdf.generic import ArrayObject, FloatObject, NameObject, NumberObject, TextStringObject
from create_pdf_compatibility_fixtures import dictionary, simple_font, stream as compressed_stream

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT/"build/verification/pdf-selective-redaction-fixtures"


def stream(writer, data, **attributes):
    value = compressed_stream(writer, data)
    value.get_object().update(dictionary(**attributes))
    return value


def text(value, x, y, size=18):
    return f"BT /F1 {size} Tf 1 0 0 1 {x} {y} Tm ({value}) Tj ET\n"


def image(writer):
    # Pixels have three distinct, independently checkable bands. The middle is sensitive.
    pixels = b"".join(bytes((220,30,40) if x < 4 else (30,200,50) if x < 8 else (30,50,220)) for y in range(6) for x in range(12))
    result = stream(writer, pixels, Type=NameObject("/XObject"), Subtype=NameObject("/Image"), Width=NumberObject(12), Height=NumberObject(6), ColorSpace=NameObject("/DeviceRGB"), BitsPerComponent=NumberObject(8))
    result.get_object()[NameObject("/Private")] = TextStringObject("SECRET-IMAGE-DICTIONARY")
    return result


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True); files = {}
    for name in ("text-vector", "nested-form", "image-instance", "image-clip", "crop-0", "crop-90", "crop-180", "crop-270", "clipped-text", "unsupported-alpha", "unsupported-operator", "combined-depth", "malformed-nested-bt", "malformed-missing-et", "malformed-orphan-et", "malformed-orphan-emc", "malformed-missing-emc", "unicode-budget"):
        writer=PdfWriter(); writer.metadata={}; font=simple_font(writer,embedded=True)
        font.get_object()[NameObject("/Private")]=TextStringObject("SECRET-FONT-DICTIONARY")
        resources=dictionary(Font=dictionary(F1=font)); page=writer.add_blank_page(600,800)
        expected={"pages":1,"kept":["KEEP LEFT","KEEP RIGHT"],"removed":["SECRET"],"areas":[[0,195,80,140,170]]}
        content=text("KEEP LEFT",40,740)+text("SECRET",210,690)+text("KEEP RIGHT",380,740)
        if name == "text-vector":
            content+="0.1 0.3 0.8 rg 60 590 440 50 re f\n0 0 0 RG 3 w [8 4] 0 d 60 570 m 500 570 l S\n"
            content+="/Span << /ActualText (SECRET-ALTERNATIVE) /Alt (SECRET-ALT) >> BDC\n"+text("VISIBLE",40,500)+"EMC\nBT /F1 12 Tf 3 Tr 1 0 0 1 40 450 Tm (SECRET-HIDDEN) Tj ET\n"
            expected["kept"].append("VISIBLE");expected["alternativeTextKept"]=["VISIBLE"]; expected["paths"]=True
            writer.add_metadata({"/Title":"SECRET-METADATA"}); writer.add_attachment("notes.txt",b"SECRET-ATTACHMENT")
            unused=stream(writer,text("SECRET-UNUSED",0,0).encode("ascii"),Type=NameObject("/XObject"),Subtype=NameObject("/Form"),BBox=ArrayObject([NumberObject(0),NumberObject(0),NumberObject(100),NumberObject(30)]),Resources=dictionary(Font=dictionary(F1=font)))
            resources[NameObject("/XObject")]=dictionary(Unused=unused)
            other=writer.add_blank_page(600,800); other[NameObject("/Resources")]=resources; other[NameObject("/Contents")]=stream(writer,text("KEEP PAGE TWO",40,740).encode("ascii")); expected["pages"]=2; expected["kept"].append("KEEP PAGE TWO")
        elif name == "nested-form":
            inner=stream(writer,(text("SECRET",0,0)+"0 0 1 rg 0 -25 100 10 re f\n").encode("ascii"),Type=NameObject("/XObject"),Subtype=NameObject("/Form"),BBox=ArrayObject([NumberObject(-5),NumberObject(-30),NumberObject(110),NumberObject(30)]),Resources=dictionary(Font=dictionary(F1=font)))
            outer=stream(writer,b"q 1.2 0 0 1.2 10 20 cm /Inner Do Q\n",Type=NameObject("/XObject"),Subtype=NameObject("/Form"),BBox=ArrayObject([NumberObject(0),NumberObject(-20),NumberObject(150),NumberObject(60)]),Resources=dictionary(XObject=dictionary(Inner=inner)))
            resources[NameObject("/XObject")]=dictionary(Outer=outer)
            content=text("KEEP LEFT",40,740)+text("KEEP RIGHT",380,740)+"q 1 0 0 1 200 650 cm /Outer Do Q\nq 1 0 0 1 200 450 cm /Outer Do Q\n"
            expected["areas"]=[[0,195,100,160,90]]; expected["secretOccurrences"]=1; expected["removed"]=[]; expected["forms"]=True
        elif name == "image-instance":
            picture=image(writer); resources[NameObject("/XObject")]=dictionary(Im=picture)
            content=text("KEEP LEFT",40,740)+text("KEEP RIGHT",380,740)+"q 240 0 0 120 100 550 cm /Im Do Q\nq 240 0 0 120 100 350 cm /Im Do Q\n"
            expected["areas"]=[[0,180,130,80,120]]; expected["removed"]=[]; expected["images"]=True
        elif name == "image-clip":
            picture=image(writer); resources[NameObject("/XObject")]=dictionary(Im=picture)
            content="0 1 0 rg 0 0 600 800 re f\n0 g\n"+text("KEEP LEFT",40,740)+text("KEEP RIGHT",380,740)+"q 100 550 120 120 re W n 240 0 0 120 100 550 cm /Im Do Q\n"
            expected["areas"]=[[0,500,700,10,10]];expected["removed"]=[];expected["clipImage"]=True
        elif name.startswith("crop-"):
            rotation=int(name.split("-")[1]); page[NameObject("/CropBox")]=ArrayObject([NumberObject(20),NumberObject(30),NumberObject(580),NumberObject(770)])
            page[NameObject("/Rotate")]=NumberObject(rotation); page[NameObject("/UserUnit")]=FloatObject(1.5)
            # The source-sensitive rectangle is x=195..335,y=550..720. Map its corners to the view.
            def transform(x,y):
                x,y=x-20,y-30
                return {0:(x,y),90:(y,560-x),180:(560-x,740-y),270:(740-y,x)}[rotation]
            points=[transform(x,y) for x in (195,335) for y in (550,720)]; width,height=((560,740) if rotation%180==0 else (740,560))
            left=min(p[0] for p in points)*1.5; bottom=min(p[1] for p in points)*1.5; right=max(p[0] for p in points)*1.5; top=max(p[1] for p in points)*1.5
            expected["areas"]=[[0,left,height*1.5-top,right-left,top-bottom]]; expected["view"]=[width*1.5,height*1.5]
        elif name == "clipped-text":
            content=text("KEEP LEFT",40,740)+text("KEEP RIGHT",380,740)+"q 40 490 34 40 re W n\n"+text("CLIPPED",40,500,24)+"Q\n"+text("SECRET",210,690)
            expected["clipped"]=True
        elif name == "unsupported-alpha":
            resources[NameObject("/ExtGState")]=dictionary(GS=dictionary(ca=FloatObject(.5)))
            content+="/GS gs 1 0 0 rg 20 20 100 100 re f\n"; expected["unsupported"]=True
        elif name == "unsupported-operator":
            content+="1 2 3 UNKNOWN\n"; expected["unsupported"]=True
        elif name == "combined-depth":
            form=stream(writer,("q\n"*20+text("KEEP LEFT",40,740)+"Q\n"*20).encode("ascii"),Type=NameObject("/XObject"),Subtype=NameObject("/Form"),BBox=ArrayObject([NumberObject(0),NumberObject(0),NumberObject(600),NumberObject(800)]),Resources=dictionary(Font=dictionary(F1=font)))
            resources[NameObject("/XObject")]=dictionary(Deep=form);content="q\n"*20+"/Deep Do\n"+"Q\n"*20;expected["unsupported"]=True
        elif name.startswith("malformed-"):
            content+={"malformed-nested-bt":"BT BT ET ET", "malformed-missing-et":"BT", "malformed-orphan-et":"ET", "malformed-orphan-emc":"EMC", "malformed-missing-emc":"/Span BMC"}[name]
            expected["unsupported"]=True
        elif name == "unicode-budget":
            expansion="TWENTY CHARACTERS ABC".encode("utf-16-be").hex().upper()
            cmap="/CIDInit /ProcSet findresource begin 12 dict begin begincmap /CIDSystemInfo << /Registry (Test) /Ordering (UCS) /Supplement 0 >> def /CMapName /Expansion def /CMapType 2 def 1 begincodespacerange <00> <FF> endcodespacerange 1 beginbfchar <41> <"+expansion+"> endbfchar endcmap CMapName currentdict /CMap defineresource pop end end"
            font.get_object()[NameObject("/ToUnicode")]=stream(writer,cmap.encode("ascii"))
            content=text("A",40,740);expected["unsupported"]=True
        page[NameObject("/Resources")]=resources; page[NameObject("/Contents")]=stream(writer,content.encode("ascii"))
        encoded=BytesIO(); writer.write(encoded); data=encoded.getvalue()
        if name == "text-vector":
            revision=PdfWriter(BytesIO(data),incremental=True); revision.add_metadata({"/Title":"CURRENT METADATA"})
            encoded=BytesIO(); revision.write(encoded); data=encoded.getvalue(); expected["history"]=True
        (OUTPUT/(name+".pdf")).write_bytes(data)
        files[name]={"sha256":sha256(data).hexdigest(),**expected}
    (OUTPUT/"manifest.json").write_text(json.dumps({"files":files},indent=2)+"\n",encoding="utf-8")
    (OUTPUT/"regions.tsv").write_text("name\tpage\tx\ty\twidth\theight\n"+"".join(name+"\t"+"\t".join(str(value) for value in area)+"\n" for name,record in files.items() for area in record["areas"]),encoding="utf-8")
    print(json.dumps({"fixtures":len(files)}))


if __name__ == "__main__": main()
