# Inline images and DOCX media

Inline PNG/JPEG images share line-breaking and pagination results with text in ordinary paragraphs and semantic-table cells. PDF, SVG and PNG use the same image rectangles; DOCX reads and writes semantic content and standard DrawingML directly. See the [inline-image verification record (Chinese)](../verification/phase-6-inline-images.md).

## Create mixed content

~~~java
var resource = ImageResource.load(Files.readAllBytes(imagePath));
var image = new InlineImage(resource, 24, 24, 2,
        "审核状态图标", "https://example.com/help");
var format = new ParagraphFormat(12, 36, 0, 0,
        ParagraphAlignment.LEFT, 0, 6, false);
var paragraph = Paragraph.mixed("status", List.of(
        new InlineText("状态：", InlineStyle.of(12)), image,
        new InlineText(" 已确认", InlineStyle.of(12))), format);
var cell = TableCell.mixed(paragraph.content(),
        new CellFormat(12, 36, ParagraphAlignment.LEFT));
~~~

`InlineContent` is the common interface of `InlineText` and `InlineImage`. Image dimensions and baseline shift use points; a positive shift moves the image's bottom edge below the baseline. The specified rectangle determines scaling. To preserve the source aspect ratio, calculate the dimensions in caller code. Dimensions must be positive and at most 14,400 pt. Alternative text is limited to 4,096 UTF-16 units and rejects control characters or invalid Unicode. URIs use the same HTTP/HTTPS/mailto allowlist as text links.

`ImageResource.load(byte[])` copies and validates encoded bytes; `bytes()` returns a copy. Resources have SHA-256 identities and retain no open handles. Only static PNG/JPEG is accepted; APNG, multi-image resources, other formats, damaged input and excessive declared dimensions are rejected. Each resource is limited to 32 MiB encoded bytes and 20,000,000 pixels. Distinct resources in one `Document` layout input total at most 64 MiB and 50,000,000 pixels; DOCX applies these totals to images across the entire package too. Sectioned-rendering layout checks apply per section, not as a whole-process memory ceiling. Loading uses JDK ImageIO/java.desktop and works headlessly; it reads no files, URLs or system resources itself.

## Provenance and fixed line boxes

Each image occupies one U+FFFC in `Paragraph.text()`. `Paragraph.images()` records its original UTF-16 position, and `content()` returns ordered text/images. `TableCell` positions are cell-local. Prefer `mixed(...)`; old text constructors retain source compatibility. Manually constructed image markers must be unique and match the source text. If text runs are supplied, each image marker must use the paragraph's default `InlineStyle.of(format.fontSize())` and the image's own link. `InlineImage` expresses the image shift; do not add marker-style shifts on top. A paragraph supports at most 1,024 images.

Images do not require a font glyph for U+FFFC and do not trigger body-text missing-glyph substitution. The default adaptive `ParagraphLayouter` measures images and text using physical fonts and `GraphicAttribute`, retaining `ImageBox` values. Explicitly injected text shapers reject image input. Baseline spacing stays fixed throughout the paragraph, and the common line box must fit the entire font stack and all images. Insufficient line height or an image wider than the line after indentation fails explicitly; images are not shrunk, clipped or split automatically.

Pagination moves complete lines only; `TextLine.atBaseline()` retains outlines and image rectangles together. Splittable table rows continue by complete cell lines. Body images appear once; repeated-header images and links repeat with their header. Automatic-width measurement includes images. Image links use final image rectangles; backends do not repeat line breaking or pagination.

After substituting template text, binding recalculates image source positions and binds alternative text and URIs while preserving explicit resources and dimensions. Adding footnote markers to mixed paragraphs does not discard images. Alternative text is retained in the model and DOCX. The original inline-image path did not add PDF/UA image tags or an alternative-text tree; inline alternative text alone is not accessibility compliance. Standard-specific support has its own [PDF standards contract](pdf-standards-api.md).

## Plain DOCX entry-point scope

| Content | Behavior |
| --- | --- |
| Body and rectangular cells | Standard `w:drawing/wp:inline`, `pic:pic`, EMU dimensions and `docPr` alternative text |
| Media | Explicit embedding under `word/media/*.png/.jpg`, content-hash deduplication, consistent relationships and Content Types |
| Image links | `a:hlinkClick` with passive external relationships; import also recognizes outer `w:hyperlink` |
| Baseline | Standard `w:position` with half-point precision; dimensions quantized to 1 EMU, or 1/12,700 pt; values quantizing to zero are rejected on export |
| Budgets | Media counts toward ZIP entry/expanded limits; image-marker expansion into XML is bounded too; validation finishes before output |
| Drawing outside this plain subset | Floating anchors, cropping, rotation, offset transforms, effects, nonrectangular geometry, linked images, unknown drawing attributes or children |

`STRICT` rejects unsupported or damaged images; `ALLOW_LOSS` omits the corresponding image and returns diagnostics. External image relationships are never loaded. Out-of-range/missing internal media paths and duplicate relationship IDs are rejected. When the heading/list model cannot represent an image-containing heading or list, strict mode rejects it; loss mode preserves mixed content and explicitly degrades it to an ordinary paragraph.

Dimensions use the project's common rectangle. Imported images must have consistent DrawingML display and transform extents. This plain image path does not read arbitrary media directories or handle VML, SVG images or the full Word default-style cascade. General Word files can contain additional structures and need diagnostic review. Independent python-docx reads these standard images. The original inline-image batch did not perform real Word/LibreOffice appearance and click checks; later mapped native drawing behavior and application evidence are documented separately in the [DOCX semantic guide](docx-semantic-api.md).

SVG/PNG still reject nonempty interactive extras. Callers can explicitly construct a static preview without interaction; links are not discarded automatically. The example's `preview` is constructed this way.

## Run and compatibility

~~~powershell
.\gradlew.bat -g build/gradle-validation build :examples:inlineImages --no-daemon --console=plain
python scripts/verify_inline_images.py
~~~

Outputs under `examples/build/inline-images` include PDF, DOCX, DOCX round-trip PDF, SVG, a 144 DPI PNG ZIP and a geometry manifest. The example code generates its images under the project's Apache-2.0 license; no extra Maven dependencies or external image assets were added.

`Paragraph`, `TableCell` and `TextLine` gained record components while preserving old constructor source calls. Consumers need recompilation; this development-version change was not a binary API freeze. Dynamic inline fields, inline cross-references, inline footnote anchors and complete DOCX resource/page-region/merged-table/interaction mappings were later work at the time of the original batch. Their current individual contracts are separate from this inline-image verification record; see the [capability matrix](capabilities-api.md) and [DOCX semantic guide](docx-semantic-api.md).
