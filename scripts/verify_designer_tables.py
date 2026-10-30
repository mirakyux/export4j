# SPDX-License-Identifier: Apache-2.0
"""Check the saved designer table fixture, native merges, bound styles and independent PDF renders."""
from __future__ import annotations

import argparse
import ctypes
from importlib import metadata
import json
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
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    with ZipFile(args.source / 'table-check.zip') as archive:
        source = ET.fromstring(archive.read('template.xml'))
    merged = source.find(".//table[@id='merge']/row/cell")
    require(merged is not None and merged.get('column-span') == merged.get('row-span') == '2', 'saved merge lost')
    table = source.find(".//table[@id='merge']")
    require(table.get('padding-top') == '7', 'ordinary table property lost during range edits')
    require(all(cell.get('color') == '#224488' and cell.get('size') == '10' and cell.get('line-height') == '16'
                for cell in table.findall('header/cell')), 'consecutive range styles did not compose')
    rule = source.find('.//rows/row/cell/style-if')
    require(rule is not None and rule.get('value') == '95' and rule.get('size') == '14'
            and rule.get('line-height') == '22', 'rule edits or body replacement lost the condition')
    require(len(source.findall('.//style-if')) == 1, 'rule deletion did not reach saved source')

    reader = PdfReader(args.source / 'table-check.pdf')
    require(len(reader.pages) == 1, 'unexpected table pagination')
    expected = ['TABLE-CHECK', 'ONE', 'RICH', 'TWO', 'THREE', 'FOUR', 'FIVE', 'SIX', 'HIGH', 'LOW', '100 pts', '40 pts']
    extracted = ''.join(page.extract_text() for page in reader.pages)
    require(all(extracted.count(text) == 1 for text in expected), 'PDF text lost or duplicated')
    runs = []
    reader.pages[0].extract_text(visitor_text=lambda text, cm, tm, font, size: runs.append((text, str(font.get('/BaseFont', '')) if font else '', size)))
    require(any('100 pts' in text and 'Bold' in font and size == 14 for text, font, size in runs), 'conditional font/size not encoded')
    require(any('40 pts' in text and 'Bold' not in font and size == 12 for text, font, size in runs), 'unmatched record acquired conditional style')
    with pdfium.PdfDocument(args.source / 'table-check.pdf') as document:
        page = document[0]
        text = page.get_textpage()
        try:
            characters = [text.get_text_range(i, 1) for i in range(text.count_chars())]
            joined = ''.join(characters)
            require(all(joined.count(value) == 1 for value in expected), 'independent PDF extraction differs')
            for label, rgb in [('100 pts', (192, 57, 43)), ('40 pts', (0, 0, 0)), ('H1', (34, 68, 136))]:
                index = joined.index(label)
                components = [ctypes.c_uint() for _ in range(4)]
                require(pdfium.raw.FPDFText_GetFillColor(text.raw, index, *(ctypes.byref(n) for n in components)), 'PDFium fill color unavailable')
                require(all(abs(a.value - b) <= 1 for a, b in zip(components, rgb)), f'actual color differs for {label}')
        finally:
            text.close()
            page.close()

    ns = {'w': 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'}
    val = '{' + ns['w'] + '}val'
    with ZipFile(args.source / 'table-check.docx') as archive:
        root = ET.fromstring(archive.read('word/document.xml'))
        styles = ET.fromstring(archive.read('word/styles.xml'))
    tables = root.findall('.//w:body/w:tbl', ns)
    require(len(tables) == 2, 'DOCX tables missing')
    require(any(n.get(val) == '2' for n in tables[0].findall('.//w:gridSpan', ns)), 'standard OOXML column span missing')
    merges = tables[0].findall('.//w:vMerge', ns)
    require(any(n.get(val) == 'restart' for n in merges) and any(n.get(val) in (None, 'continue') for n in merges), 'standard OOXML vertical merge missing')
    native_text = ''.join(root.itertext())
    require(all(native_text.count(value) == 1 for value in expected), 'DOCX text lost or duplicated')
    high = [run for run in tables[1].findall('.//w:r', ns) if '100 pts' in ''.join(run.itertext())]
    require(len(high) == 1, 'bound DOCX score missing')
    properties = high[0].find('w:rPr', ns)
    role = properties.find('w:rStyle', ns)
    role_id = '{' + ns['w'] + '}styleId'
    bold_style = next((style for style in styles.findall('w:style', ns) if role is not None and style.get(role_id) == role.get(val)), None)
    require(properties.find('w:color', ns).get(val) == 'C0392B' and properties.find('w:sz', ns).get(val) == '28'
            and bold_style is not None and bold_style.find('w:rPr/w:b', ns) is not None, 'native DOCX conditional properties missing')
    html = (args.source / 'table-check.html').read_text(encoding='utf-8')
    require('colspan="2"' in html and 'rowspan="2"' in html, 'HTML spans lost')
    require('#c0392b' in html.lower() and '100 pts' in html and '40 pts' in html, 'HTML bound styles missing')
    for format in ['pdf', 'docx']:
        report = json.loads((args.source / f'report-{format}.json').read_text(encoding='utf-8'))
        require(report['losses'] == 0, f'{format} strict export had a loss')
    html_report = json.loads((args.source / 'report-html.json').read_text(encoding='utf-8'))
    require({issue['feature'] for issue in html_report['issues']} == {'PAGE_GEOMETRY', 'FONT_ROLE'}, 'HTML losses differ from declared boundaries')

    poppler, pdfium_paths = render(args.source / 'table-check.pdf', args.output)
    comparisons = [difference(a, b, 2) for a, b in zip(poppler, pdfium_paths, strict=True)]
    require(all(item['meanAbsoluteChannelError'] < 4 and item['changedPixelFraction'] < .06 for item in comparisons), 'independent renderers differ')
    contact_sheet([(f'Page {i + 1}', path) for i, path in enumerate(poppler)], args.output)
    report = {'pages': len(reader.pages), 'formats': ['PDF', 'DOCX', 'HTML'], 'renders': comparisons,
              'versions': {name: metadata.version(name) for name in ['pypdf', 'pypdfium2', 'Pillow']}}
    (args.output / 'report.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
