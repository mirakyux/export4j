# SPDX-License-Identifier: Apache-2.0
"""Independently inspect all designer business exports and render every generated page."""
from __future__ import annotations

import argparse
from io import BytesIO
import json
from pathlib import Path
import re
from xml.etree import ElementTree as ET
from zipfile import ZipFile

from markdown_it import MarkdownIt
from PIL import Image
import pypdfium2 as pdfium
from pypdf import PdfReader
import resvg_py

from verify_adr_form import render
from verify_advanced_pdf import difference, require
from verify_production import actual_text, contact_sheet
from verify_web_exports import Tree

ROOT = Path(__file__).resolve().parents[1]
W = '{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
MARKERS = ['REPORT', 'FORM', 'LEDGER', 'REFERENCE']


def content(text, index, label):
    if index == 4:
        compact = re.sub(r'\s+', '', text)
        for value in ['上市许可持有人药品不良反应报告表', '示例患者甲', '示例皮疹', '所有病例、药品、机构和时间均为虚构']:
            require(compact.count(value) == 1, label + ': adverse reaction form content missing or duplicated: ' + value)
        require('报告编号*' in compact and '病例编号*' not in compact, label + ': source edit lost')
        return
    prefix = MARKERS[index]
    require(text.count(prefix + '-EDITED') == 1, label + ': edited marker missing or duplicated')
    require(text.count(prefix + '-END') == 1, label + ': end marker missing or duplicated')
    require(prefix + '-START' not in text, label + ': old marker survives')
    if index == 0:
        require(text.count('STATUS-APPROVED') == 1 and 'STATUS-PENDING' not in text, label + ': conditional branch differs')
        require(all(text.count(s) == 1 for s in ['记录完整', '运行正常', '按期复核']), label + ': loop content differs')
    if index == 1:
        require(re.findall(r'FORM-(\d{2})', text) == [f'{n:02}' for n in range(1, 15)], label + ': form rows differ')
    if index == 2:
        require(re.findall(r'ROW-(\d{3})', text) == [f'{n:03}' for n in range(1, 121)], label + ': inventory rows lost, repeated or reordered')


def sources(directory, index):
    with ZipFile(directory / 'template.zip') as original, ZipFile(directory / 'edited.zip') as edited:
        require(set(original.namelist()) == set(edited.namelist()), 'source resource inventory changed')
        for name in original.namelist():
            if name != 'template.xml':
                require(original.read(name) == edited.read(name), 'unedited resource changed: ' + name)
        source = edited.read('template.xml').decode('utf-8')
        ET.fromstring(source)
        require(original.read('template.xml') == (directory / 'template.xml').read_bytes(), 'saved source differs')
        require({'fonts/Regular.ttf', 'fonts/Bold.ttf', 'fonts/Chinese.ttf',
                 'licenses/LiberationSans-OFL.txt', 'licenses/ZCOOLXiaoWei-OFL.txt'} <= set(edited.namelist()), 'explicit resources missing')
        if index < 4:
            require('images/mark.png' in edited.namelist(), 'original sample image resource missing')
        if index == 0:
            require(all(value in source for value in ['${title}', '${summary}', '<list items="items" as="item"', '<if']), 'binding intent flattened')
            require(json.loads((directory / 'data.json').read_text(encoding='utf-8'))['summary'].startswith('REPORT-EDITED'), 'data edit lost')
        elif index == 4:
            require('报告编号*' in source and '病例编号*' not in source, 'form source edit lost')
            require('row-span="2"' in source and 'column-span="3"' in source and '${drug.name}' in source,
                    'editable merged headers or data binding lost')
            require('<page-break' not in source and 'page-break-before' not in source,
                    'form sample must paginate from content')
        else:
            require(MARKERS[index] + '-EDITED' in source, 'source edit lost')


def form_geometry(reader):
    require(len(reader.pages) == 3, 'default form should naturally occupy three pages')
    # Stroked grid edges in points, measured from the final PDF rather than layout IR.
    # The stroke is centered 0.25pt inside the cell bounds. Tolerance is 0.05pt.
    expected_edges = [
        [183.25, 380.75, 462.45, 480.45, 575.06, 762.56, 791.36],
        [42.75, 90.15, 106.25, 204.56, 252.86, 284.56, 498.46, 576.96, 613.34, 633.59, 649.69, 768.89],
        [42.75, 83.05],
    ]
    for page, expected in zip(reader.pages, expected_edges):
        edges, previous = [], [None]

        def visit(operator, operands, matrix, _text_matrix):
            def point(x, y):
                return (float(x) * matrix[0] + float(y) * matrix[2] + matrix[4],
                        float(page.mediabox.height) - (float(x) * matrix[1] + float(y) * matrix[3] + matrix[5]))
            if operator == b'm':
                previous[0] = point(*operands)
            elif operator == b'l' and previous[0] is not None:
                end, start = point(*operands), previous[0]
                if abs(end[1] - start[1]) < 0.01 and abs(end[0] - start[0]) > 10:
                    edges.append(end[1])
                previous[0] = end

        page.extract_text(visitor_operand_before=visit)
        require(all(any(abs(edge - value) < 0.05 for edge in edges) for value in expected),
                'form grid positions changed')
    compact_pages = [re.sub(r'\s+', '', page.extract_text()) for page in reader.pages]
    require('注1：1-假药' in compact_pages[0] and '7-用药错误' in compact_pages[1],
            'form note did not continue across pages')
    require('其他需说明的情况' in compact_pages[2] and '报告来源' not in compact_pages[2],
            'form remarks did not move naturally to the final page')


def inspect(directory, index, expected_pages, output):
    sources(directory, index)
    reader = PdfReader(directory / 'document.pdf')
    require(len(reader.pages) == expected_pages, 'preview/PDF page counts differ')
    if index == 4:
        form_geometry(reader)
    # Ordinary mapped text is extracted directly; ActualText here only marks inline image anchors.
    if index == 4:
        require([re.sub(r'\s+', '', value) for value in actual_text(reader)]
                == [f'-{page}-' for page in range(1, expected_pages + 1)], 'final footer page fields differ')
    else:
        require(all(value == '\ufffc' for value in actual_text(reader)), 'unexpected PDF replacement text')
    content(''.join(p.extract_text() for p in reader.pages), index, 'pypdf extraction')
    if index == 2:
        require(all('编号' in p.extract_text() and '名称与说明' in p.extract_text() for p in reader.pages), 'PDF continuation header missing')
    if index == 3:
        require('Appendix page: 3' in ''.join(p.extract_text() for p in reader.pages), 'PDF final page reference differs')
    with pdfium.PdfDocument(directory / 'document.pdf') as document:
        values = []
        for page in document:
            try:
                text = page.get_textpage()
                try:
                    values.append(text.get_text_range())
                    for n in range(text.count_chars()):
                        if not text.get_text_range(n, 1).strip():
                            continue
                        left, bottom, right, top = text.get_charbox(n)
                        inset = 26 if index == 4 else 40
                        require(inset <= left <= right <= 595.3 - inset and inset <= bottom <= top <= 842 - inset,
                                'visible character outside page margins')
                finally:
                    text.close()
            finally:
                page.close()
        content(''.join(values), index, 'PDFium extraction')
    with ZipFile(directory / 'document.docx') as archive:
        main = ET.fromstring(archive.read('word/document.xml'))
        content(''.join(main.itertext()), index, 'DOCX XML')
        if index in (1, 2):
            require(main.find('.//' + W + 'tblHeader') is not None, 'DOCX repeated table header missing')
        if index == 3:
            require('[unresolved reference]' in ''.join(main.itertext()), 'DOCX reference loss hidden')
    html = Tree((directory / 'document.html').read_text(encoding='utf-8'))
    markdown = Tree(MarkdownIt('commonmark').enable('table').render((directory / 'document.md').read_text(encoding='utf-8')))
    for label, tree in [('HTML', html), ('Markdown', markdown)]:
        content(tree.root.text(), index, label)
        if index == 0:
            require(len(tree.root.find('img')) == 1, label + ': image lost')
    poppler, pdfium_paths = render(directory / 'document.pdf', output)
    comparisons = []
    with ZipFile(directory / 'document.svg.zip') as svgs, ZipFile(directory / 'document.png.zip') as pngs:
        require(len(svgs.namelist()) == len(pngs.namelist()) == expected_pages, 'page ZIP count differs')
        require(set(svgs.namelist()) == {f'page-{n}.svg' for n in range(1, expected_pages + 1)}, 'SVG members differ')
        for n, (a, b) in enumerate(zip(poppler, pdfium_paths), 1):
            dual = difference(a, b, 2)
            # Use the existing dense-form 192-DPI blur2 limits: renderer grid stroke/hinting differences
            # affect many pixels on full table pages. Content and character geometry are checked above.
            require(dual['meanAbsoluteChannelError'] < 4 and dual['changedPixelFraction'] < .06, 'PDF dual rendering differs')
            svg = ET.fromstring(svgs.read(f'page-{n}.svg'))
            with Image.open(b) as reference:
                size = reference.size
            svg.set('width', str(size[0])); svg.set('height', str(size[1]))
            vector = output / f'resvg-{n}.png'
            vector.write_bytes(resvg_py.svg_to_bytes(svg_string=ET.tostring(svg, encoding='unicode'),
                width=size[0], height=size[1], background='#ffffff', skip_system_fonts=True))
            vector_diff = difference(b, vector, 2)
            require(vector_diff['meanAbsoluteChannelError'] < 5 and vector_diff['changedPixelFraction'] < .05, 'SVG independent pixels differ')
            name = next(name for name in pngs.namelist() if int(re.search(r'(\d+)\.png$', name).group(1)) == n)
            with Image.open(BytesIO(pngs.read(name))) as image:
                require(image.size == (794, 1123), 'PNG dimensions differ from 595x842pt at 96dpi')
                raster = output / f'raster-{n}.png'
                image.convert('RGB').resize(size).save(raster)
            raster_diff = difference(b, raster, 2)
            require(raster_diff['meanAbsoluteChannelError'] < 5 and raster_diff['changedPixelFraction'] < .05, 'PNG independent pixels differ')
            comparisons.append({'page': n, 'pdf': dual, 'svg': vector_diff, 'png': raster_diff})
    return comparisons, poppler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, default=ROOT / 'export4j-designer/build/scenarios')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/verification/designer')
    args = parser.parse_args()
    report = json.loads((args.source / 'report.json').read_text(encoding='utf-8'))
    require(report['status'] == 'passed' and len(report['scenarios']) == 5 and len(report['failureControls']) == 11, 'scenario harness incomplete')
    args.output.mkdir(parents=True, exist_ok=True)
    results, sheets = [], []
    for index, sample in enumerate(report['scenarios']):
        target = args.output / sample['directory']; target.mkdir(exist_ok=True)
        comparisons, pages = inspect(args.source / sample['directory'], index, sample['pages'], target)
        require(len(sample['exports']) == 6 and all(e['bytes'] > 0 for e in sample['exports']), 'six exports incomplete')
        for export in sample['exports']:
            require(export['bytes'] == (args.source / sample['directory'] / export['file']).stat().st_size, 'reported size differs')
            require(export['losses'] == export['warnings'], 'WARN report counts differ')
        if index == 3:
            require(all(e['losses'] > 0 for e in sample['exports'] if e['format'] != 'PDF'), 'navigation loss missing')
            require(reader_navigation(args.source / sample['directory'] / 'document.pdf'), 'PDF navigation lost')
        results.append({'scenario': sample['directory'], 'pages': sample['pages'], 'comparisons': comparisons})
        sheets.extend((f'{sample["directory"]} page {n}', page) for n, page in enumerate(pages, 1))
    contact_sheet(sheets, args.output)
    export_count = sum(len(sample['exports']) for sample in report['scenarios'])
    (args.output / 'report.json').write_text(json.dumps({'status': 'passed', 'scenarios': results, 'exports': export_count,
        'failureControls': report['failureControls']}, indent=2), encoding='utf-8')
    print(f'Designer independently verified: {len(results)} scenarios, {export_count} exports, {len(sheets)} pages in PDF/SVG/PNG, 11 failure controls')


def reader_navigation(path):
    reader = PdfReader(path)
    return bool(reader.outline) and any(p.get('/Annots') for p in reader.pages)


if __name__ == '__main__':
    main()
