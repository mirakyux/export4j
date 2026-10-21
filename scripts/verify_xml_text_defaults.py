# SPDX-License-Identifier: Apache-2.0
"""Check finite XML font/color scopes, final fields, native styles and all rendered pages."""
from __future__ import annotations

import argparse
from importlib import metadata
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from PIL import ImageFont
from pypdf import PdfReader
from verify_adr_form import render
from verify_advanced_pdf import difference, require
from verify_component_text import close, path_contours, rgb, run
from verify_continuous_columns import font_ascent
from verify_multi_floats import compose, pdf_geometry, point
from verify_production import actual_text, contact_sheet
from verify_semantic_tables import annotations
from verify_streaming_rich_text import outlined_paths, painted_spans, physical_fonts, same_color

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / 'build/verification/xml-text-defaults'
SVG = '{http://www.w3.org/2000/svg}'
W = '{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
BLUE, PURPLE, GREEN, RED, BLACK = ((34/255, 68/255, 136/255), (136/255, 51/255, 102/255),
                                  (51/255, 102/255, 51/255), (136/255, 68/255, 34/255), (0, 0, 0))
FIELD = 'FIELD-PAGE 1 COUNT 2 NUMBER 2 TARGET 2'


def check_package(path):
    with ZipFile(path) as package:
        main = ET.fromstring(package.read('word/document.xml'))
        numbering = ET.fromstring(package.read('word/numbering.xml'))
    def style(properties, role, color, size):
        require(properties is not None, 'native style missing')
        actual_role = properties.find(W+'rStyle')
        require(('' if actual_role is None else actual_role.get(W+'val')) == role, 'native font role differs')
        require(properties.find(W+'color').get(W+'val') == color, 'native RGB differs')
        close(float(properties.find(W+'sz').get(W+'val'))/2, size, 'native point size')
    fields = main.findall('.//'+W+'fldSimple')
    require([value.get(W+'instr').split()[0] for value in fields] == ['PAGE', 'NUMPAGES', 'REF', 'PAGEREF'],
            'native field kind/order differs')
    for value in fields: style(value.find(W+'r/'+W+'rPr'), 'italic', '883366', 10)
    wanted = {'XML-TEXT-DEFAULTS': ('bold', '224488', 16), 'BODY-DEFAULT': ('italic', '883366', 10),
              'BLACK': ('italic', '000000', 10), 'REGULAR': ('', '000000', 10),
              'NAMED': ('bold', '884422', 10), 'CELL-DEFAULT': ('bold', '336633', 10),
              'BLOCK-INHERITED': ('italic', '883366', 10), 'BLOCK-RESET': ('', '000000', 10),
              'INNER-CELL-DEFAULT': ('bold', '336633', 10)}
    for text, expected in wanted.items():
        runs = [value for value in main.iter(W+'r') if ''.join(value.itertext()).strip() == text]
        require(len(runs) == 1, 'native run missing/repeated: '+text)
        style(runs[0].find(W+'rPr'), *expected)
    marker = numbering.find(W+'abstractNum/'+W+'lvl/'+W+'rPr')
    style(marker, 'italic', '883366', 10)
    return {'fields': 4, 'nativeStyleRuns': len(wanted), 'markerStyle': 'italic/883366/10pt'}


def check_svg(path, spans, field_paths):
    root = ET.parse(path).getroot()
    require(root.tag == SVG+'svg' and root.get('transform') is None, 'XML SVG root transformed')
    require(tuple(map(float,root.get('viewBox','').split())) == (0,0,420,860), 'XML SVG root size differs')
    require(float(root.get('width').removesuffix('pt')) == 420 and float(root.get('height').removesuffix('pt')) == 860,
            'XML SVG displayed size differs')
    for node in root.iter():
        require(not any(node.get(key) is not None for key in ('opacity', 'fill-opacity', 'display', 'visibility', 'style', 'mask', 'filter')),
                'XML SVG gained hidden/indirect paint state')
    pages = root.findall(SVG+'svg')
    require(len(pages) == 2, 'XML SVG page count differs')
    for index, page in enumerate(pages):
        require(tuple(map(float,page.get('viewBox','').split())) == (0,0,420,420)
                and page.get('data-page') == str(index+1), 'XML SVG page geometry differs')
        close(float(page.get('x')), 0, 'SVG page x')
        close(float(page.get('y')), index*440, 'SVG page y')
        labels = []
        def walk(node, matrix):
            if node.get('transform') is not None:
                match = re.fullmatch(r'matrix\(([^)]+)\)', node.get('transform'))
                require(match is not None, 'XML SVG transform syntax differs')
                values = tuple(map(float, match[1].split()))
                require(len(values) == 6 and values[:4] == (1, 0, 0, 1), 'XML SVG scale/rotation differs')
                matrix = compose(matrix, values)
            label = node.get('aria-label', '').strip()
            if label:
                paths = node.findall(SVG+'path')
                if label == FIELD:
                    require(not paths and node.get('transform') is None, 'XML field logical anchor unexpectedly paints')
                    paths = page.findall(SVG+'path')
                    require(index == 0 and len(paths) == len(field_paths) == 8, 'XML SVG field painting differs')
                    for path, ink in zip(paths, field_paths, strict=True):
                        require(path.get('transform') is None, 'XML SVG field ink transformed')
                        require(same_color(rgb(path.get('fill')), PURPLE), 'XML SVG inherited field RGB differs')
                        points = [point(matrix, *p) for contour in path_contours(path.get('d')) for p in contour]
                        xs, ys = zip(*points, strict=True)
                        for actual, expected in zip((min(xs), max(xs), min(ys), max(ys)),
                                                    (ink['left'], ink['right'], ink['top'], ink['bottom']), strict=True):
                            close(actual, expected, 'XML SVG field actual ink', tolerance=.04)
                else:
                    require(paths, 'XML SVG logical text has no actual glyph path')
                    matches = [s for s in spans if s['page'] == index and s['text'] == label]
                    require(len(matches) == 1, 'XML SVG run missing/repeated in PDF: '+label)
                    span = matches[0]
                    for path in paths:
                        require(path.get('transform') is None and same_color(rgb(path.get('fill')), span['color']), 'XML SVG glyph style differs')
                        points = [point(matrix, *p) for contour in path_contours(path.get('d')) for p in contour]
                        require(points and all(span['x']-.6 <= x <= span['right']+.6
                                and span['baseline']-span['size']*1.2 <= y <= span['baseline']+span['size']*.5 for x,y in points),
                                'XML SVG actual glyph leaves PDF advance/frame')
                labels.append(label)
            for child in node: walk(child, matrix)
        walk(page, (1,0,0,1,0,0))
        expected = [s['text'] for s in spans if s['page'] == index]
        if index == 0: expected.append(FIELD)
        require(labels == expected, 'XML SVG full source order/count differs')


def check(path, caller):
    reader = PdfReader(path, strict=True)
    pages, width, height = (1,320,220) if caller else (2,420,420)
    require(len(reader.pages) == pages and not reader.get_fields(), 'XML text pages/forms differ')
    for page in reader.pages:
        require(list(map(float,page.mediabox)) == list(map(float,page.cropbox)) == [0,0,width,height]
                and page.get('/Rotate',0) == 0 and page.get('/UserUnit',1) == 1, 'XML text page size/origin differs')
        require(not any(operator == b'gs' for _,operator in page.get_contents().operations), 'XML text gained indirect paint state')
    fonts = physical_fonts(reader, {'LiberationSans','LiberationSans-Bold'} if caller else
                           {'LiberationSans','LiberationSans-Bold','LiberationSans-Italic'})
    spans = painted_spans(reader)
    ascent = font_ascent(ROOT/'assets/fonts/LiberationSans-Regular.ttf')
    if caller:
        first = run(spans,0,'CALLER-DEFAULT','-Bold',10,BLUE,18,18+10*ascent)
        run(spans,0,'RESET','',10,BLACK,first['right'],18+10*ascent)
        number = run(spans,0,'1.','-Bold',10,BLUE,18,36+10*ascent)
        run(spans,0,'CALLER-NUMBER','-Bold',10,BLUE,number['right'],36+10*ascent)
        require(len(spans) == 4 and not actual_text(reader) and not annotations(reader), 'caller source/extras differ')
    else:
        expected = [('XML-TEXT-DEFAULTS','-Bold',16,BLUE,0,24,24),
                    ('BODY-DEFAULT','-Italic',10,PURPLE,0,24,48), ('BLACK','-Italic',10,BLACK,0,None,48),
                    ('REGULAR','',10,BLACK,0,None,48), ('NAMED','-Bold',10,RED,0,None,48), ('LINK','-Italic',10,PURPLE,0,None,48),
                    ('2.','-Italic',10,PURPLE,0,24,66), ('NUMBER-BODY','-Italic',10,PURPLE,0,None,66),
                    ('FIRST-RUN-RESET','',10,BLACK,0,None,66), ('PAGE-TWO-REGULAR','',10,BLACK,1,24,24),
                    ('CELL-DEFAULT','-Bold',10,GREEN,1,30,48), ('BLOCK-INHERITED','-Italic',10,PURPLE,1,30,78),
                    ('BLOCK-RESET','',10,BLACK,1,30,96), ('INNER-CELL-DEFAULT','-Bold',10,GREEN,1,36,120)]
        require([s['text'] for s in spans] == [e[0] for e in expected], 'XML source order differs')
        for text,variant,size,color,page,x,top in expected:
            run(spans,page,text,variant,size,color,x,top+size*ascent)
        require(actual_text(reader) == [FIELD], 'final inherited fields differ')
        geometry = pdf_geometry(reader,18)[1]
        require(len(geometry) == 1 and geometry[0]['source'] == FIELD, 'field logical geometry differs')
        close(geometry[0]['x'],24,'field origin'); close(geometry[0]['y'],84,'field top')
        ink = outlined_paths(reader)
        require(len(ink) == 8 and all(same_color(p['color'],PURPLE) and p['page'] == 0 for p in ink),
                'inherited field outline count/color differs')
        font = ImageFont.truetype(str(ROOT/'assets/fonts/LiberationSans-Italic.ttf'),640)
        for digit, painted in zip('1222',ink[1::2],strict=True):
            mask,offset = font.getmask2(digit,anchor='ls'); x0,y0,x1,y1 = mask.getbbox()
            close(painted['right']-painted['left'],(x1-x0)/64,'independent italic field ink width',tolerance=.04)
            close(painted['top'],84+10*ascent+(offset[1]+y0)/64,'independent italic field ink top',tolerance=.04)
            close(painted['bottom'],84+10*ascent+(offset[1]+y1)/64,'independent italic field ink bottom',tolerance=.04)
        links = annotations(reader)
        require(len(links) == 3 and [v.get('targetPageIndex') for v in links if 'targetPageIndex' in v] == [0,1]
                and [v.get('uri') for v in links if 'uri' in v] == ['https://example.com/defaults'], 'XML final link destinations differ')
        expected_links = [(230.14,48,22.23,18,None), (223.49414,84,24,18,(0,24,66)),
                          (292.86523,84,24,18,(1,24,24))]
        for link,(x,y,w,h,destination) in zip(links,expected_links,strict=True):
            require(link['pageIndex'] == 0, 'XML link moved to another source page')
            for key,value in zip(('x','y','width','height'),(x,y,w,h),strict=True):
                close(link[key],value,'XML link '+key)
            if destination is not None:
                require(link['targetPageIndex'] == destination[0], 'XML reference target page differs')
                close(link['destinationX'],destination[1],'XML reference target x')
                close(link['destinationY'],destination[2],'XML reference target y')
        linked = next(value for value in spans if value['text'] == 'LINK')
        close(links[0]['x'],linked['x'],'external link text origin')
        close(links[0]['width'],linked['right']-linked['x'],'external link text advance')
        check_svg(path.with_suffix('.svg'),spans,ink)
        check_package(path.with_suffix('.docx'))
    return {'pages':pages,'paintedRuns':len(spans),'physicalFonts':fonts}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--consumer-build-directory',type=Path)
    args=parser.parse_args()
    versions={n:metadata.version(n) for n in ('pypdf','pypdfium2','Pillow')}
    require(versions=={'pypdf':'6.10.0','pypdfium2':'5.13.0','Pillow':'12.3.0'},'use pinned verification tools')
    consumer=args.consumer_build_directory or Path((ROOT/'build/verification/consumer-build-directory.txt').read_text().strip())
    if not consumer.is_absolute(): consumer=ROOT/consumer
    OUTPUT.mkdir(parents=True,exist_ok=True)
    report,contacts={'tools':versions,'consumerBuildDirectory':str(consumer)},[]
    for name,path,caller in [('example',ROOT/'examples/build/xml-text-defaults/report.pdf',False),
                             ('consumer',consumer/'output/consumer-xml-text-defaults.pdf',True)]:
        value=check(path,caller)
        first,second=render(path,OUTPUT/name)
        require(len(first)==len(second)==value['pages'],'XML defaults renderer missed pages')
        metrics=[difference(a,b,2) for a,b in zip(first,second,strict=True)]
        require(all(m['meanAbsoluteChannelError']<4 and m['changedPixelFraction']<.06 for m in metrics),'XML defaults renderers disagree')
        value['rendererDifferences']=metrics; report[name]=value
        contacts.extend((name+' '+str(i),p) for i,p in enumerate(first,1))
    contact_sheet(contacts,OUTPUT)
    (OUTPUT/'report.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'xmlTextDefaults':'passed','pagesIncludingConsumer':len(contacts)},indent=2))


if __name__=='__main__': main()
