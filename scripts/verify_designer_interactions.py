# SPDX-License-Identifier: Apache-2.0
"""Check the saved direct-body and list-editing designer fixture."""
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

from verify_adr_form import render
from verify_advanced_pdf import difference, require
from verify_production import contact_sheet


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--template', required=True, type=Path)
    parser.add_argument('--data', required=True, type=Path)
    parser.add_argument('--pdf', required=True, type=Path)
    parser.add_argument('--output', type=Path, default=Path('build/verification/designer-interactions'))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)

    with ZipFile(args.template) as archive:
        source = archive.read('template.xml').decode('utf-8')
        require({'fonts/Regular.ttf', 'fonts/Bold.ttf', 'fonts/Chinese.ttf', 'images/mark.png'}
                <= set(archive.namelist()), 'explicit resources missing')
    root = ET.fromstring(source)
    require('e4j-preview-' not in source, 'preview-only IDs leaked into saved template')
    intro = root.find("p[@id='intro']")
    require(intro is not None and intro.get('line-height') == '26', 'body properties missing')
    require(intro.find("span[@role='bold']").text == 'BOLD ${title}', 'original bold variable changed')
    require(intro.find("span[@role='bold'][@size='14']").text == 'UI-RICH', 'direct rich text missing')
    require(intro.find('link').get('href') == 'https://example.com', 'link changed')
    image = intro.find('image')
    require(image is not None and image.get('ref') == 'mark'
            and image.get('width') == '16' and image.get('height') == '10', 'inline image changed')
    require(intro.find('page-number') is not None, 'page-number field changed')
    children = list(root)
    require([node.text for node in children if node.tag == 'bullet'] ==
            ['LIST-A', 'LIST-B', 'LIST-C'], 'static list order changed')
    require([node.text for node in children if node.tag == 'numbered'] ==
            ['NUMBER-A', 'NUMBER-B'], 'numbered list order changed')
    require(next(i for i, node in enumerate(children) if node.tag == 'each') >
            next(i for i, node in enumerate(children) if node.get('id') == 'end'),
            'loop source order changed')
    require(root.find("heading[@id='design-1']").text == '新标题', 'added heading missing')
    data = json.loads(args.data.read_text(encoding='utf-8'), parse_float=Decimal)
    require(data['precision'] == Decimal('12345678901234567890.123456789'),
            'formatted JSON number rounded')
    require([item['name'] for item in data['items']] == ['ROW-A', 'ROW-B', 'ROW-C'],
            'binding array order changed')

    reader = PdfReader(args.pdf)
    require(len(reader.pages) == 1, 'PDF page count changed')
    page = reader.pages[0]
    require(abs(float(page.mediabox.width) - 595) < .01
            and abs(float(page.mediabox.height) - 842) < .01, 'PDF page size changed')
    images = []

    def image_operator(operator, operands, cm, tm):
        if operator == b'Do':
            images.append([round(math.hypot(float(cm[0]), float(cm[1])), 2),
                           round(math.hypot(float(cm[2]), float(cm[3])), 2)])

    page.extract_text(visitor_operand_before=image_operator)
    require(images == [[16, 10]], 'inline image geometry changed')
    annotations = [ref.get_object() for ref in page.get('/Annots', [])]
    require(len(annotations) == 1 and annotations[0].get('/Subtype') == '/Link'
            and annotations[0]['/A'].get_object().get('/URI') == 'https://example.com',
            'link annotation missing')
    with pdfium.PdfDocument(args.pdf) as document:
        rendered_page = document[0]
        try:
            text_page = rendered_page.get_textpage()
            try:
                text = text_page.get_text_range()
            finally:
                text_page.close()
        finally:
            rendered_page.close()
    ordered = ['INTRO', 'BOLD BINDING', 'LINK', 'UI-RICH', 'LIST-A', 'LIST-B',
               'LIST-C', 'NUMBER-A', 'NUMBER-B', 'END', 'ROW-A', 'ROW-B', 'ROW-C', '新标题']
    positions = [text.index(value) for value in ordered]
    require(positions == sorted(positions) and all(text.count(value) == 1 for value in ordered),
            'PDF content order or uniqueness changed')
    require('1 TAI' in text, 'page-number field or surrounding text changed')
    poppler, pdfium_pages = render(args.pdf, args.output)
    comparison = difference(poppler[0], pdfium_pages[0], 2)
    require(comparison['meanAbsoluteChannelError'] < 4
            and comparison['changedPixelFraction'] < .06, 'independent PDF renderers differ')
    contact_sheet([('designer interactions', pdfium_pages[0])], args.output)
    (args.output / 'report.json').write_text(json.dumps({'status': 'passed', 'pages': 1,
        'images': images, 'comparison': comparison}, indent=2), encoding='utf-8')
    print('Designer interactions verified: source/data, list order, rich text, link, image and dual-rendered PDF')


if __name__ == '__main__':
    main()
