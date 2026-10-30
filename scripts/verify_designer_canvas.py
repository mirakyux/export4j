# SPDX-License-Identifier: Apache-2.0
"""Independently check the saved two-page designer canvas fixture described in phase 110."""
from __future__ import annotations

import argparse
from decimal import Decimal
import json
import math
from pathlib import Path
from xml.etree import ElementTree as ET
from zipfile import ZipFile

import pypdfium2 as pdfium
from pypdf import PdfReader
from pypdf.generic import ContentStream

from verify_adr_form import render
from verify_advanced_pdf import difference, require
from verify_production import contact_sheet


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--template', type=Path, required=True)
    parser.add_argument('--data', type=Path, required=True)
    parser.add_argument('--pdf', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=Path('build/verification/designer-canvas'))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    with ZipFile(args.template) as archive:
        source = archive.read('template.xml').decode('utf-8')
        require({'fonts/Regular.ttf', 'fonts/Bold.ttf', 'fonts/Chinese.ttf', 'images/mark.png',
                 'licenses/LiberationSans-OFL.txt', 'licenses/ZCOOLXiaoWei-OFL.txt'}
                <= set(archive.namelist()), 'explicit images/fonts/licenses missing')
    root = ET.fromstring(source)
    require(all(value in source for value in ['${title}', '${summary}', '<list items="items" as="item"', '<if']), 'dynamic source flattened')
    ids = [child.get('id') for child in root if child.get('id')]
    require(ids == ['report-title', 'design-4', 'design-5', 'design-6', 'design-7', 'report-summary',
                    'design-1', 'report-logo', 'design-2', 'design-3', 'report-items', 'report-end'],
            'canvas insertion order changed')
    require(root.find("page-break[@id='design-7']") is not None, 'explicit break missing')
    require(root.find("p[@id='design-1']").text == '新段落', 'dragged paragraph missing')
    logo = root.find("p[@id='report-logo']")
    require(logo is not None and logo.get('line-height') == '58', 'image resize did not update line height')
    image = logo.find('image')
    require(image is not None and image.get('width') == '60' and image.get('height') == '36', 'aspect-ratio properties lost')
    columns = root.find("table[@id='design-6']/columns")
    require(columns is not None and columns[0].attrib == {'width': '72'}
            and columns[1].attrib == {'weight': '1'}, 'mutually exclusive fixed/weight column properties lost')
    data = json.loads(args.data.read_text(encoding='utf-8'), parse_float=Decimal)
    require(data['precision'] == Decimal('12345678901234567890.123456789'), 'formatted decimal rounded')
    reader = PdfReader(args.pdf)
    require(len(reader.pages) == 2, 'explicit break produced incorrect PDF page count')
    dimensions = []
    for index, page in enumerate(reader.pages):
        require(abs(float(page.mediabox.width) - 595) < .01
                and abs(float(page.mediabox.height) - 842) < .01, 'page geometry changed')
        images = []

        def image_operator(operator, operands, cm, tm):
            if operator == b'Do':
                images.append([round(math.hypot(float(cm[0]), float(cm[1])), 2),
                               round(math.hypot(float(cm[2]), float(cm[3])), 2)])

        page.extract_text(visitor_operand_before=image_operator)
        expected = [[72, 48], [72, 48]] if index == 0 else [[60, 36], [72, 48], [72, 48]]
        require(images == expected, 'actual PDF image dimensions/order differ from canvas edits')
        dimensions.append(images)
    rectangles = [list(map(float, operands)) for operands, operator
                  in ContentStream(reader.pages[0].get_contents(), reader).operations if operator == b're']
    require(any(abs(rect[0] - 42) < .01 and abs(rect[2] - 72) < .01 for rect in rectangles),
            'fixed 72 pt first column did not reach the PDF grid')
    texts = [page.extract_text() for page in reader.pages]
    require('Inspection report' in texts[0] and '列一' in texts[0] and '内容二' in texts[0], 'first-page heading/table missing')
    require('REPORT-START' not in texts[0] and texts[1].count('REPORT-START') == 1
            and texts[1].count('REPORT-END') == 1, 'break or body uniqueness failed')
    require(texts[1].index('REPORT-START') < texts[1].index('新段落') < texts[1].index('Findings')
            < texts[1].index('STATUS-APPROVED') < texts[1].index('REPORT-END'), 'body reading order changed')
    with pdfium.PdfDocument(args.pdf) as document:
        for index in range(2):
            page = document[index]
            try:
                textpage = page.get_textpage()
                try:
                    value = textpage.get_text_range()
                    require(('Inspection report' if index == 0 else 'REPORT-END') in value, 'independent text extraction failed')
                finally:
                    textpage.close()
            finally:
                page.close()
    poppler, pdfium_paths = render(args.pdf, args.output)
    comparisons = []
    for a, b in zip(poppler, pdfium_paths, strict=True):
        comparison = difference(a, b, 2)
        require(comparison['meanAbsoluteChannelError'] < 4 and comparison['changedPixelFraction'] < .06,
                'independent PDF renderers differ')
        comparisons.append(comparison)
    contact_sheet([(f'canvas page {n}', page) for n, page in enumerate(pdfium_paths, 1)], args.output)
    (args.output / 'report.json').write_text(json.dumps({'status': 'passed', 'pages': 2,
        'images': dimensions, 'comparisons': comparisons}, indent=2), encoding='utf-8')
    print('Designer canvas verified: dynamic source/data, insertion order, fixed column, image geometry, page break and 2 dual-rendered pages')


if __name__ == '__main__':
    main()
