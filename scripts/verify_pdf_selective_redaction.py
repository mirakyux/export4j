# SPDX-License-Identifier: Apache-2.0
"""Independently inspect fresh resources, retained glyphs, actual erasure and visible pixels."""
import argparse
import csv
from ctypes import c_double
from hashlib import sha256
import json
from pathlib import Path
import re
from io import BytesIO
from PIL import Image, ImageChops, ImageDraw
from pypdf import PdfReader, PdfWriter
from pypdf.generic import ContentStream, DecodedStreamObject, IndirectObject, NameObject
import pypdfium2 as pdfium
import pypdfium2.raw as pdfium_raw
from verify_advanced_pdf import require
from verify_pdf_raster import references
from verify_production import contact_sheet

ROOT=Path(__file__).resolve().parents[1]
FIXTURES=ROOT/"build/verification/pdf-selective-redaction-fixtures"
OUTPUT=ROOT/"build/verification/pdf-selective-redaction"


def compact(value): return "".join(value.split())


def characters(path):
    result=[]
    with pdfium.PdfDocument(path) as document:
        for index in range(len(document)):
            page=document[index]
            try:
                text=page.get_textpage()
                try:
                    entries=[]
                    for position in range(text.count_chars()):
                        value=text.get_text_range(position,1)
                        if not value.strip():continue
                        x,y=c_double(),c_double();require(pdfium_raw.FPDFText_GetCharOrigin(text,position,x,y),"retained glyph origin unavailable")
                        geometry=(x.value,y.value,pdfium_raw.FPDFText_GetFontSize(text,position),pdfium_raw.FPDFText_GetCharAngle(text,position))
                        entries.append((value,text.get_charbox(position),geometry))
                    result.append(entries)
                finally:text.close()
            finally:page.close()
    return result


def kept_geometry(source,target,expected,source_images,target_images):
    before=characters(source);after=characters(target);count=0
    original=PdfReader(source);actual=PdfReader(target)
    for a,b in zip(original.pages,actual.pages,strict=True):
        require(tuple(a.mediabox)==tuple(b.mediabox) and tuple(a.cropbox)==tuple(b.cropbox) and a.rotation==b.rotation and a.get("/UserUnit",1)==b.get("/UserUnit",1),"source view geometry changed")
    for word in expected["kept"]:
        # The source reader exposes ActualText instead of these painted glyphs;
        # their preserved appearance is checked by the full-page dual renders.
        if word in expected.get("alternativeTextKept",[]):continue
        token=compact(word);matches=[]
        for page_index,entries in enumerate(before):
            joined="".join(value for value,_,_ in entries)
            for match in re.finditer(re.escape(token),joined):matches.append((page_index,entries[match.start():match.end()]))
        require(len(matches)==1,"retained-word fixture ambiguity")
        page_index,entries=matches[0];joined="".join(value for value,_,_ in after[page_index]);start=joined.find(token)
        require(start>=0,"PDFium retained word order differs (including rotated text)")
        crop=original.pages[page_index].cropbox;rotation=original.pages[page_index].rotation
        def view(x,y):
            x-=float(crop.left);y-=float(crop.bottom);w,h=float(crop.width),float(crop.height)
            return {0:(x,h-y),90:(y,x),180:(w-x,y),270:(h-y,w-x)}[rotation]
        w,h=(float(crop.width),float(crop.height)) if rotation%180==0 else (float(crop.height),float(crop.width))
        for (_,expected_box,expected_origin),(_,_,actual_origin) in zip(entries,after[page_index][start:start+len(token)],strict=True):
            require(max(abs(a-b) for a,b in zip(expected_origin,actual_origin,strict=True))<.003,"retained glyph origin/font size/angle changed")
            # PDFium's tight charbox uses different vertical metric conventions for
            # simple TrueType and Type0 fonts (even when both render exactly alike).
            # Check actual per-glyph ink through both renderers, independently of
            # that representation-dependent box, plus exact origin/size/angle above.
            points=[view(x,y) for x in (expected_box[0],expected_box[2]) for y in (expected_box[1],expected_box[3])]
            for engine in range(2):
                with Image.open(source_images[engine][page_index]) as left,Image.open(target_images[engine][page_index]) as right:
                    sx,sy=left.width/w,left.height/h
                    rectangle=(int(min(p[0] for p in points)*sx)-3,int(min(p[1] for p in points)*sy)-3,int(max(p[0] for p in points)*sx)+4,int(max(p[1] for p in points)*sy)+4)
                    boxes=[]
                    for image in (left,right):
                        pixels=image.convert("RGB").crop(rectangle)
                        mask=Image.new("L",pixels.size);mask.putdata([255 if max(pixel)<128 else 0 for pixel in pixels.get_flattened_data()]);boxes.append(mask.getbbox())
                    require(all(box is not None for box in boxes) and max(abs(a-b) for a,b in zip(boxes[0],boxes[1],strict=True))<=1,"retained glyph visible ink moved in an independent renderer")
            count+=1
    return count


def objects(reader):
    keys={(number,generation) for generation,values in reader.xref.items() for number in values if number>0 and generation!=65535}
    keys.update((number,0) for number in reader.xref_objStm)
    return [reader.get_object(IndirectObject(number,generation,reader)) for number,generation in sorted(keys)]


def fresh_document(path, expected):
    reader=PdfReader(path); root=reader.trailer["/Root"]
    require("/Prev" not in reader.trailer and path.read_bytes().count(b"%%EOF")==1,"old revision history was copied")
    require(not any(key in root for key in ("/Metadata","/Names","/AF","/AcroForm","/Outlines","/StructTreeRoot","/OCProperties")),"hidden document structure retained")
    for page in reader.pages:
        require(not page.get("/Annots"),"source annotations copied")
        for value in page.get("/Resources",{}).get("/XObject",{}).values():
            require(value.get_object().get("/Subtype")!="/Form","original Form stream copied")
    secret_markers=(b"SECRET-METADATA",b"SECRET-ATTACHMENT",b"SECRET-ALTERNATIVE",b"SECRET-ALT",b"SECRET-HIDDEN",b"SECRET-UNUSED",b"SECRET-FONT-DICTIONARY",b"SECRET-IMAGE-DICTIONARY")
    for value in objects(reader):
        if isinstance(value,dict):
            require(not any(key in value for key in ("/Private","/ActualText","/Alt","/E")),"hidden source dictionary entry copied")
        if hasattr(value,"get_data"):
            data=value.get_data(); require(not any(marker in data for marker in secret_markers),"decoded target stream leaked source hidden content")
    pypdf_text="\n".join(page.extract_text() for page in reader.pages)
    with pdfium.PdfDocument(path) as document:
        pdfium_text=[]
        for index in range(len(document)):
            page=document[index]
            try:
                text=page.get_textpage()
                try: pdfium_text.append(text.get_text_range())
                finally: text.close()
            finally: page.close()
    for actual in (pypdf_text,"\n".join(pdfium_text)):
        clean=compact(actual)
        for word in expected["kept"]: require(clean.count(compact(word))==1,"outside searchable text lost or duplicated")
        for word in expected["removed"]: require(compact(word) not in clean,"selected sensitive text is still extractable")
        require(clean.count("SECRET")==expected.get("secretOccurrences",0),"wrong shared Form occurrence removed")
    return reader


def without_overlay(path, expected, output):
    writer=PdfWriter();writer.clone_document_from_reader(PdfReader(path))
    for page_index in {area[0] for area in expected["areas"]}:
        page=writer.pages[page_index]; content=ContentStream(page.get_contents(),writer); operations=content.operations
        require(operations[-1][1]==b"Q","redaction overlay group missing")
        start=len(operations)-2
        while start>=0 and operations[start][1]!=b"q": start-=1
        group=operations[start:]; require(start>=0 and any(operator==b"f" for _,operator in group) and not any(operator in (b"Tj",b"Do") for _,operator in group),"last group is not the visible overlay")
        content.operations=operations[:start]; stream=DecodedStreamObject();stream.set_data(content.get_data());page[NameObject("/Contents")]=writer._add_object(stream)
    with output.open("wb") as target: writer.write(target)


def normalized_difference(original, actual, expected, page_index):
    with Image.open(original) as left,Image.open(actual) as right:
        require(left.size==right.size,"physical view size differs between source and target")
        a=left.convert("RGB");b=right.convert("RGB")
        mask=Image.new("L",a.size,0);draw=ImageDraw.Draw(mask)
        width,height=expected.get("view",(600,800)); sx,sy=a.width/width,a.height/height
        for index,x,y,w,h in expected["areas"]:
            if index==page_index: draw.rectangle((int(x*sx)-3,int(y*sy)-3,int((x+w)*sx)+3,int((y+h)*sy)+3),fill=255)
        # Whole intersecting glyphs may legitimately extend beyond the selected box.
        # These controls select their entire words, so a three-pixel AA margin suffices.
        a.paste((255,255,255),(0,0),mask);b.paste((255,255,255),(0,0),mask)
        delta=ImageChops.difference(a,b);channels=list(delta.get_flattened_data()); changed=sum(max(pixel)>8 for pixel in channels)
        mean=sum(sum(pixel) for pixel in channels)/(a.width*a.height*3)
        require(mean<.8 and changed/(a.width*a.height)<.004,"outside visible content changed beyond glyph/vector antialiasing")
        return {"meanChannelError":mean,"changedFraction":changed/(a.width*a.height)}


def pixel(path, point, physical):
    with Image.open(path) as image:
        return image.convert("RGB").getpixel((round(point[0]*image.width/physical[0]),round(point[1]*image.height/physical[1])))


def main():
    parser=argparse.ArgumentParser();parser.add_argument("--consumer-build-directory",type=Path);args=parser.parse_args()
    directory=args.consumer_build_directory or Path((ROOT/"build/verification/consumer-build-directory.txt").read_text().strip()); caller=directory/"output/pdf-selective-redaction"
    expected_files=json.loads((FIXTURES/"manifest.json").read_text())["files"];OUTPUT.mkdir(parents=True,exist_ok=True)
    require(json.loads((caller/"summary.json").read_text())=={"sources":9,"outputs":18,"rejections":22},"consumer acceptance controls incomplete")
    report={};images=0;glyphs=0
    for name,expected in expected_files.items():
        source=caller/(name+"-source.pdf");require(sha256(source.read_bytes()).hexdigest()==expected["sha256"]==sha256((FIXTURES/(name+".pdf")).read_bytes()).hexdigest(),"independent source bytes changed")
        if expected.get("unsupported"):
            require(not (caller/(name+"-ignore.pdf")).exists(),"unsupported input emitted a partial result");continue
        if expected.get("history"):require(b"/Prev " in source.read_bytes() and source.read_bytes().count(b"%%EOF")==2,"source no longer exercises incremental hidden history")
        tiles=[];source_images=references(source,OUTPUT/name/"source")
        for engine,pages in enumerate(source_images):
            images+=len(pages);tiles.extend((f"{name} source E{engine+1} P{i+1}",path) for i,path in enumerate(pages))
        differences=[];previous_losses=None
        for action in ("warn","ignore"):
            label=name+"-"+action;target=caller/(label+".pdf");reader=fresh_document(target,expected);require(len(reader.pages)==expected["pages"],"page lost")
            with (caller/(label+".losses.tsv")).open() as stream: losses=list(csv.DictReader(stream,delimiter="\t"))
            require(all(row["action"]==action.upper() for row in losses),"configured policy action differs")
            require("PDF_CONTENT_RECONSTRUCTION" in {row["feature"] for row in losses},"reconstruction loss undisclosed")
            actual_losses=[(row["feature"],row["treatment"],row["effects"]) for row in losses]
            if previous_losses is None:previous_losses=actual_losses
            else:require(previous_losses==actual_losses,"WARN/IGNORE actual losses differ")
            if expected.get("clipped"):require("PDF_CLIPPED_TEXT" in {row["feature"] for row in losses},"clipped glyph vector degradation undisclosed")
            rendered=references(target,OUTPUT/name/action)
            if action=="warn":glyphs+=kept_geometry(source,target,expected,source_images,rendered)
            for engine,pages in enumerate(rendered):
                require(len(pages)==expected["pages"],"independent renderer page count")
                for page_index,path in enumerate(pages):
                    images+=1;tiles.append((f"{name} {action} E{engine+1} P{page_index+1}",path))
                    differences.append(normalized_difference(source_images[engine][page_index],path,expected,page_index))
                    if action=="ignore":
                        warn=OUTPUT/name/"warn"/("poppler" if engine==0 else "pdfium")/path.name
                        with Image.open(path) as a,Image.open(warn) as b:require(ImageChops.difference(a.convert("RGB"),b.convert("RGB")).getbbox() is None,"WARN/IGNORE pixels differ")
                    width,height=expected.get("view",(600,800))
                    for index,x,y,w,h in expected["areas"]:
                        if index==page_index:require(max(pixel(path,(x+w/2,y+h/2),(width,height)))==0,"selected region not visibly covered")
            if action=="warn":
                no_overlay=OUTPUT/name/"without-overlay.pdf";without_overlay(target,expected,no_overlay);erased=references(no_overlay,OUTPUT/name/"without-overlay")
                for engine,pages in enumerate(erased):
                    images+=len(pages);tiles.extend((f"{name} erased E{engine+1} P{i+1}",path) for i,path in enumerate(pages))
                    for index,path in enumerate(pages):
                        with Image.open(path) as a,Image.open(source_images[engine][index]) as b:require(a.size==b.size,"overlay-removed independent render has invalid page geometry")
                    if expected.get("paths"):
                        require(max(pixel(pages[0],(265,165),(600,800)))==255 and min(pixel(pages[0],(265,165),(600,800)))==255,"selected vector geometry was hidden by overlay rather than removed")
                        outside=pixel(pages[0],(100,165),(600,800));require(outside[2]>150 and outside[0]<150 and outside[1]<150,"outside vector fill lost")
                    if expected.get("forms"):
                        require(min(pixel(pages[0],(240,154),(600,800)))>250,"selected nested Form geometry remains behind the overlay")
                        outside=pixel(pages[0],(240,354),(600,800));require(outside[2]>150 and outside[0]<150 and outside[1]<150,"unselected nested Form vector geometry lost")
                    if expected.get("clipImage"):
                        require(pixel(pages[0],(280,190),(600,800))==(0,255,0),"clipped image painted over outside background")
                if expected.get("images"):
                    resource=[value.get_object() for value in reader.pages[0]["/Resources"]["/XObject"].values()]
                    require(len(resource)==2 and all(value["/Subtype"]=="/Image" for value in resource),"image invocation resources not independently rebuilt")
                    decoded=[value.get_data() for value in resource]
                    require(all(decoded[0][(row*12+column)*3:(row*12+column+1)*3]==b"\x00\x00\x00" for row in range(6) for column in range(4,8)),"sensitive image pixels remain encoded")
                    require(decoded[0]!=decoded[1] and bytes((30,200,50)) in decoded[1],"unselected image invocation changed")
        contact_sheet(tiles,OUTPUT/name);report[name]={"pages":expected["pages"],"outputs":2,"outsideAppearance":differences}
    require((caller/"protected.pdf").read_bytes()==(caller/"unsupported-protected.pdf").read_bytes()==b"existing output","protected target changed after refusal")
    result={"cases":report,"outputs":18,"pageImages":images,"rejections":22,"retainedGlyphsWithIndependentOriginsAndInk":glyphs};(OUTPUT/"report.json").write_text(json.dumps(result,indent=2)+"\n",encoding="utf-8")
    print(json.dumps({key:value for key,value in result.items() if key!="cases"}))


if __name__=="__main__":main()
