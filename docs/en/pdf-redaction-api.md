# Secure PDF area redaction

`PdfAdapter.redact(input)` converts every page of a supported PDF into a fresh image PDF, clearing pixels in specified areas before encoding. Output copies no original text, fonts, attachments, metadata, annotation objects, hidden resources, or incremental history. Image content inside selected areas is actually removed as well.

```java
var policy = ProcessingPolicy.builder()
        .defaultRule(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD)
        .feature("RASTERIZATION", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
        .feature("PAGE_GEOMETRY", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
        .feature("FORMS", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
        .feature("COMMENTS", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
        .build();
adapter.redact(input).area(0, 18, 25, 68, 22).dpi(144).fill(0)
        .policy(policy).prepare().to(target);
```
In `area(page,x,y,width,height)`, page is zero-based. Coordinates are **physical points from the top-left of the CropBox after viewing rotation**, unlike page editing's unrotated MediaBox coordinates and object editing's native bottom-left coordinates. Areas must lie entirely inside the visible page. Bounds are rounded using actual pixel ratios and expanded outward by one pixel. Default fill is black; `fill` is 24-bit RGB. DPI defaults to 144 and accepts `(0,1200]`; cumulative pixels are checked before allocation.

The entire document becomes RGB images, including text and vectors outside selected areas. Search, copy, and semantic editing are lost. RASTERIZATION/DEGRADE must be explicitly accepted. Normalizing source CropBox, rotation, or UserUnit requires PAGE_GEOMETRY/DEGRADE. Other fields, comments, links, bookmarks, attachments, and metadata follow the [rendering loss policy (Chinese)](../pdf-raster-api.md). Encrypted input still requires owner-authorized decrypted copying; unencrypted output also requires ENCRYPTION_REMOVED/DEGRADE.

For supported unembedded simple fonts or horizontal Type0 source fonts, explicitly supply static TrueType with `.sourceFont(exactBaseFontName, PdfFont.of(fontBytes))` and accept `RENDER_FONT_MAPPING / DEGRADE`. Mapping reuses the [rendering subset (Chinese)](../pdf-font-compatibility-api.md#渲染与脱敏的显式源字体映射). It neither restores searchable text nor relaxes the requirement that every page render successfully. Valid traditional Type1 and controlled Type3 use their actual programs, without implicit system fonts.

For source layers or static tags, first enable `PdfAdapter.builder().allowPassiveStructures(true)` explicitly. Redaction uses complete-page pixels in the default VIEW. The new file copies no disabled layers, original tag tree, or hidden resources. `PDF_LAYERS / DEGRADE` and `PDF_TAGS / DISCARD` still describe their separate capability losses. See the [passive-structure guide (Chinese)](../pdf-passive-structures-api.md) for default-view rendering, reading, and original-object preservation boundaries.

Strict failure is the default. If any page cannot be rendered, no redaction candidate can be produced, even when policy requests SKIP_BLOCK. There is no fallback that retains original objects or silently omits pages. Invalid structures, signatures, permissions, budgets, cancellation, and I/O cannot be ignored.

`prepare()` finishes all cleaning, final validation, and exact encoding before allowing atomic path output. Borrowed streams are not closed or flushed. The caller must locate **every visible occurrence that must be removed**. Sensitive text in unselected areas remains readable from images. There is no automatic detection or OCR.

See [ADR-0063 (Chinese)](../adr/0063-fresh-raster-pdf-redaction.md) for the design and [phase 55 (Chinese)](../verification/phase-55-pdf-redaction.md) for evidence.

## Retaining text and vectors outside selected areas

`PdfAdapter.redactSelective(input)` reconstructs supported opaque static content item by item. It retains searchable filled text, cleaned vector outlines, and image instances outside selected areas, rather than rasterizing every page. `area`, `fill`, `policy`, `execution`, and `prepare` share the candidate and atomic-output contracts above. Areas likewise use physical points from the rotated CropBox's top-left.

```java
var policy = ProcessingPolicy.builder()
        .defaultRule(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD)
        .feature("PDF_CONTENT_RECONSTRUCTION", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
        .feature("PDF_CLIPPED_TEXT", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
        .feature("PDF_IMAGE_BOUNDARY", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
        .feature("PDF_COLOR_MODEL", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
        .build();
var candidate = adapter.redactSelective(input)
        .area(0, 18, 25, 68, 22).fill(0)
        .policy(policy).prepare();
var preview = candidate.preview();
var actual = candidate.to(target);
assert preview.issues().equals(actual.report().issues());
```
| Content | Actual cleaning and retention |
| --- | --- |
| Text | Deletes the complete glyph encoding when a visible glyph bounding box intersects an area; edges may extend beyond the area. Other glyphs retain actual positions, sizes, and fill colors. Retained text requires valid embedded horizontal static TrueType simple/Type0 fonts. Real font subsets, widths, and ToUnicode containing only retained glyphs are regenerated; source font dictionaries and CMaps are not copied |
| Clipped text | Discards text fully hidden by source clipping or invisible text modes. Glyphs partly cut by source clipping retain visible outlines only, without hidden Unicode, requiring `PDF_CLIPPED_TEXT / DEGRADE`. Retaining stroked text is unsupported; text modes that alter clipping are rejected outright |
| Vectors | Executes actual fills/strokes and clipping, subtracts selected areas from actual geometry, and writes fresh outlines. Removing final covering rectangles cannot restore original paths within selected areas |
| Images | Independently decodes, cleans, and re-encodes RGB pixels for each actual invocation. Pixels intersecting selected areas are filled. Pixels outside source clipping or partially crossing boundaries are conservatively cleared, while visible clipping is rebuilt. Unselected instances of a shared source image retain their own pixels. Boundary pixels or antialiasing may change, requiring `PDF_IMAGE_BOUNDARY / DEGRADE` |
| Form XObjects | Executes actual transforms, resources, and BBox of ordinary opaque nested and repeated instances. Results expand into cleaned page content; original Form streams are not copied |
| Pages and auxiliary structures | Numerically rebuilds MediaBox, CropBox, Rotate, and UserUnit, preserving the original visible page view. The fresh document copies no original content streams, historical revisions, metadata, attachments, bookmarks, forms, annotations, tags, or alternative text. These report separate DISCARD losses according to actual presence |

Strict failure is the default. WARN/IGNORE accept only the reconstruction, discards, and boundary changes listed in reports. Both generate the same candidate; IGNORE retains complete actual losses. Converting CMYK fills to RGB requires `PDF_COLOR_MODEL / DEGRADE`. Encrypted input remains controlled by owner permissions; unencrypted output requires `ENCRYPTION_REMOVED / DEGRADE`.

Transparent drawing, transparency groups, layers, patterns/gradients, soft masks, non-axis-aligned image transforms, unsupported fonts, and unsupported drawing operators all prevent candidate generation. There is no policy that preserves original objects or leaves some content unprocessed. PDF operands, BT/ET, BMC/BDC/EMC, and graphics stacks are checked independently of reader error tolerance. Nested Form, graphics-stack, and marked-content depths are charged cumulatively. Source glyphs, UTF-16 lengths of each retained Unicode mapping, image pixels, decoded bytes, operations, objects, and final output are budgeted. Cancellation or failure preserves the old target.

Both APIs require callers to locate every sensitive occurrence. The selective API does not infer whether text is occluded by objects drawn later; text outside selected areas may remain extractable. It rebuilds font subsets, but ordinary source-font program names and other identifiers may remain in new fonts. It does not guarantee removal of arbitrary private payloads or steganographic information inside font programs, or automatically recognize sensitive content. For unsupported content, separately choose the supported whole-page image API and explicitly accept its capability losses.

## Selective API verification

The 10 `PdfSelectiveRedactorTest` tests passed. Independent [pypdf inputs](../../scripts/create_pdf_selective_redaction_fixtures.py) include 18 PDFs: 9 successful inputs and 9 rejected inputs covering transparency, unknown operators, cumulative depth, five malformed text/marking structures, and Unicode-expansion budgets. The [public consumer](../../verification/consumer/src/main/java/consumer/PdfSelectiveRedactionConsumer.java), using that batch's locally published artifacts in a fresh isolated Maven build, produced 18 WARN/IGNORE outputs and 22 rejections. Old targets and source hashes remained unchanged.

The [independent verifier](../../scripts/verify_pdf_selective_redaction.py) checks reachable objects and decoded streams, actual text extraction, fresh resources, per-invocation image pixels, source-page views, and actual losses. Removing final overlays still leaves original text, paths, and image content within selected areas deleted. The origins, font sizes, angles, and actual ink bounds from two rendering engines remain for 164 sample glyphs outside selected areas. All 80 page images and 19 contact sheets from Poppler/PDFium were inspected, including four rotation directions and cropping. The final Maven outputs' 99 PNG hashes all match the visually inspected artifacts. This evidence is not an assurance for arbitrary inputs or readers.

To reproduce, run from the repository root with `scripts/requirements-verification.txt` and Poppler. On other platforms, use `./gradlew` for the Wrapper. `<consumer-build-directory>` is relative to `verification/consumer` and should be a fresh directory.

```powershell
python scripts/create_pdf_selective_redaction_fixtures.py
.\gradlew.bat :export4j-adapter-pdf:test --tests '*PdfSelectiveRedactorTest'
.\gradlew.bat build sbom publishLocalBundle
.\gradlew.bat -p verification/consumer "-PconsumerBuildDirectory=<consumer-build-directory>" clean build resolvedLibraries pdfSelectiveRedaction --refresh-dependencies
python scripts/verify_pdf_selective_redaction.py --consumer-build-directory verification/consumer/<consumer-build-directory>
```
