# SPDX-License-Identifier: Apache-2.0
"""Verify the documented designer UI editing fixture from its actual saved ZIP, JSON and PDF."""
from __future__ import annotations

import argparse
from decimal import Decimal
import json
from pathlib import Path
import re
from xml.etree import ElementTree as ET
from zipfile import ZipFile

import pypdfium2 as pdfium
from pypdf import PdfReader
from pypdf.generic import ContentStream

from verify_adr_form import render
from verify_advanced_pdf import difference, require
from verify_production import actual_text, contact_sheet


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--template', type=Path, required=True)
    parser.add_argument('--data', type=Path, required=True)
    parser.add_argument('--pdf', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=Path('build/verification/designer-editing'))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    with ZipFile(args.template) as archive:
        source = archive.read('template.xml').decode('utf-8')
        require({'fonts/Regular.ttf', 'fonts/Bold.ttf', 'fonts/Chinese.ttf', 'images/mark.png'} <= set(archive.namelist()), 'explicit resources missing')
    root = ET.fromstring(source)
    require(all(value in source for value in ['${title}', '${summary}', '<list items="items" as="item"', '<if']), 'dynamic source flattened')
    page = root.find('page')
    require(page is not None and page.get('width') == '148mm' and page.get('height') == '210mm'
            and page.get('orientation') == 'LANDSCAPE', 'A5 landscape declaration missing')
    require(page.get('margin') == '12mm' and page.get('margin-left') == '18mm', 'asymmetric margins lost')
    rich = root.find(".//p[@id='report-end']/span")
    require(rich is not None and rich.text == ' UI-RICH' and rich.get('role') == 'bold'
            and rich.get('color') == '#224488' and rich.get('underline') == 'true' and rich.get('size') == '12', 'rich run declaration lost')
    table = root.find('.//table')
    require(table is not None and table.get('draw-borders') == 'false' and table.get('padding-top') == '7'
            and table.get('space-before') == '11', 'table controls did not reach saved source')
    require(all(table.get('padding-' + side) == '3' for side in ['right', 'bottom', 'left']), 'compact padding lost')
    require(all(cell.get('size') == '9' and cell.get('line-height') == '15' for cell in table.findall('./header/cell') + table.findall('./row/cell')), 'compact cell styles lost')
    data = json.loads(args.data.read_text(encoding='utf-8'), parse_float=Decimal)
    require(data['precision'] == Decimal('12345678901234567890.123456789'), 'JSON formatting rounded the decimal')
    reader = PdfReader(args.pdf)
    require(len(reader.pages) == 2, 'expected the edited two-page fixture')
    all_text = ''.join(page.extract_text() for page in reader.pages)
    require(all_text.count('UI-RICH') == 1 and all_text.count('REPORT-END') == 1, 'rich content duplicated or missing')
    require(all_text.count('UI-HEADER') == all_text.count('UI-EVEN') == 1, 'page variant text lost or duplicated')
    # Resolved field lines use standard ActualText around their drawn glyphs; raw pypdf ignores it.
    field_lines = [re.sub(r'\s+', '', value) for value in actual_text(reader)]
    require('第1/2页' in field_lines and 'EVEN2/2' in field_lines, 'resolved field ActualText missing')
    bold_run = False
    colored = False
    for index, page in enumerate(reader.pages):
        width, height = float(page.mediabox.width), float(page.mediabox.height)
        require(abs(width - 210 * 72 / 25.4) < .01 and abs(height - 148 * 72 / 25.4) < .01, 'actual PDF page size differs')
        text = page.extract_text()
        require(('UI-HEADER' if index == 0 else 'UI-EVEN') in text, 'wrong header variant')
        if index == 1:
            require(all(item in text for item in ['列一', '列二', '内容一', '内容二']), 'table text missing')
            require(not any(operator == b'S' for _, operator in ContentStream(page.get_contents(), reader).operations), 'borderless page still contains stroked grid')
        runs = []
        page.extract_text(visitor_text=lambda value, cm, tm, font, size: runs.append((value, font, size)))
        bold_run |= any('UI-RICH' in value and 'Bold' in str(font.get('/BaseFont', '')) and abs(size - 12) < .01 for value, font, size in runs if font)
        for operands, operator in ContentStream(page.get_contents(), reader).operations:
            if operator == b'rg' and len(operands) == 3:
                colored |= all(abs(float(a) - b) < .0001 for a, b in zip(operands, [0x22/255, 0x44/255, 0x88/255]))
    require(bold_run and colored, 'actual bold font or rich color missing')
    with pdfium.PdfDocument(args.pdf) as document:
        independent = []
        for index in range(len(document)):
            page = document[index]
            try:
                text = page.get_textpage()
                try:
                    independent.append(text.get_text_range())
                    width, height = page.get_size()
                    for n in range(text.count_chars()):
                        if not text.get_text_range(n, 1).strip():
                            continue
                        left, bottom, right, top = text.get_charbox(n)
                        require(18*72/25.4-1 <= left <= right <= width-12*72/25.4+1
                                and 12*72/25.4-1 <= bottom <= top <= height-12*72/25.4+1, 'visible character outside asymmetric page margins')
                finally:
                    text.close()
            finally:
                page.close()
        require(''.join(independent).count('UI-RICH') == 1, 'independent rich text extraction failed')
        require('EVEN2/2' in re.sub(r'\s+', '', independent[1]), 'independent final field extraction failed')
    poppler, pdfium_paths = render(args.pdf, args.output)
    comparisons = []
    for a, b in zip(poppler, pdfium_paths, strict=True):
        comparison = difference(a, b, 2)
        require(comparison['meanAbsoluteChannelError'] < 4 and comparison['changedPixelFraction'] < .06, 'independent PDF renderers differ')
        comparisons.append(comparison)
    contact_sheet([(f'edited page {n}', page) for n, page in enumerate(pdfium_paths, 1)], args.output)
    (args.output / 'report.json').write_text(json.dumps({'status': 'passed', 'pages': 2, 'comparisons': comparisons}, indent=2), encoding='utf-8')
    print('Designer editing independently verified: source/data retention, A5/margins, rich font/color, page variants, fields, table style and 2 dual-rendered pages')


if __name__ == '__main__':
    main()
