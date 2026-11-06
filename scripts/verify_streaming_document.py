# SPDX-License-Identifier: Apache-2.0
"""Inspect lazy block exports and compare every page with the complete document output."""
import argparse
from contextlib import closing
import json
from pathlib import Path
import re

from PIL import Image, ImageChops
from pypdf import PdfReader
import pypdfium2 as pdfium
from verify_advanced_pdf import difference, require
from verify_paged_document import image_records
from verify_pdf_raster import references
from verify_production import contact_sheet

ROOT = Path(__file__).resolve().parents[1]


def inspect(path, counts):
    texts, links, pictures = [], [], []
    with pdfium.PdfDocument(path) as document:
        require(len(document) == counts['pages'], f'{path.name}: page count differs')
        for index in range(len(document)):
            with closing(document[index]) as page, closing(page.get_textpage()) as text:
                compact = ''.join(text.get_text_range().split())
                require(f'STREAMPAGE{index + 1}OF{len(document)}' in compact, 'final footer field differs')
                if re.search(r'SR-\d-\d+-END', compact):
                    require(f'STREAMHEADER{index + 1}/{len(document)}' in compact, 'repeated header field differs')
                for segment in range(1, counts['segments'] + 1):
                    if f'INTRO-{segment}' in compact:
                        require(f'INTRO-{segment}Page{index + 1}of{len(document)}' in compact, 'inline page field differs')
                texts.append(compact)
    combined = ''.join(texts)
    for segment in range(1, counts['segments'] + 1):
        for marker in (f'STREAMSEGMENT{segment}', f'INTRO-{segment}', f'TAIL-{segment}-END'):
            require(combined.count(marker) == 1, f'{path.name}: missing or repeated {marker}')
        actual = re.findall(rf'SR-{segment}-(\d+)-END', combined)
        require(actual == [str(row) for row in range(1, counts['rowsPerSegment'] + 1)], 'table rows lost, repeated or reordered')
    with closing(PdfReader(path, strict=True)) as document:
        for index, page in enumerate(document.pages):
            require(tuple(float(v) for v in page.mediabox) == (0, 0, 300, 360), 'page geometry differs')
            resources = page['/Resources'].get_object()
            pictures.append(image_records(resources))
            font_objects = resources['/Font'].get_object()
            embedded = False
            for reference in font_objects.values():
                font = reference.get_object()
                descendants = font.get('/DescendantFonts')
                descriptor = descendants[0].get_object().get('/FontDescriptor') if descendants else font.get('/FontDescriptor')
                if descriptor:
                    embedded |= '/FontFile2' in descriptor.get_object()
            require(embedded, 'explicit TrueType font not embedded')
            for annotation in page.get('/Annots', []):
                obj = annotation.get_object()
                require(obj.get('/Subtype') == '/Link', 'unexpected annotation type')
                require(str(obj['/A'].get_object()['/URI']) == counts['uri'], 'external link destination differs')
                bounds = tuple(round(float(value), 3) for value in obj['/Rect'])
                require(0 <= bounds[0] < bounds[2] <= 300 and 0 <= bounds[1] < bounds[3] <= 360, 'link outside page')
                links.append((index, bounds))
    require(len(links) == 9, 'text and image external links differ')
    require(sum(bool(images) for images in pictures) == 3, 'image pages differ')
    require(sum(len(images) for images in pictures) == 6, 'PNG/JPEG resources differ')
    return {'text': texts, 'links': links, 'images': pictures}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--consumer-build-directory', type=Path, required=True)
    parser.add_argument('--output-directory', type=Path, default=ROOT / 'build/verification/streaming-document')
    args = parser.parse_args()
    source = args.consumer_build_directory.resolve() / 'output/streaming-document'
    output = args.output_directory.resolve()
    output.mkdir(parents=True, exist_ok=True)
    counts = json.loads((source / 'counts.json').read_text())
    require((counts['singleOpens'], counts['singleCloses'], counts['replayOpens'], counts['replayCloses']) == (1, 1, 2, 2),
            'source ownership or pass count differs')
    require(counts['diagnostics'] == 0, 'unexpected diagnostics')
    structures = {name: inspect(source / f'{name}.pdf', counts) for name in ('full', 'single', 'replay')}
    require(structures['full'] == structures['single'] == structures['replay'], 'complete/single/replay content or geometry differs')
    rendered = {name: references(source / f'{name}.pdf', output / name) for name in structures}
    metrics, tiles = [], []
    for index in range(counts['pages']):
        for engine in (0, 1):
            with Image.open(rendered['full'][engine][index]) as original:
                for name in ('single', 'replay'):
                    with Image.open(rendered[name][engine][index]) as actual:
                        require(original.size == actual.size and ImageChops.difference(original.convert('RGB'), actual.convert('RGB')).getbbox() is None,
                                f'{name}: page {index + 1} pixels differ')
        delta = difference(rendered['single'][0][index], rendered['single'][1][index], blur_radius=1)
        require(delta['meanAbsoluteChannelError'] < 3 and delta['changedPixelFraction'] < .07, 'independent rendering differs')
        metrics.append({'page': index + 1, **delta})
        tiles.extend([(f'Poppler {index + 1}', rendered['single'][0][index]),
                      (f'PDFium {index + 1}', rendered['single'][1][index])])
    contact_sheet(tiles, output)
    report = {'counts': counts, 'allPageContentAndStructure': 'passed', 'sameRendererPixelsIdentical': True,
              'crossRenderer': metrics, 'visualReview': 'separate manual review required'}
    (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
