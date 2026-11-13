# Replacing PDF image drawing instances

`PdfAdapter.editImages(input)` replaces an image instance according to its actual drawing order on a page. Shared image resources, repeated Form calls, and cross-page references are handled separately. Only the matched `Do` call and its Form path receive private copies.

```java
var editor = adapter.editImages(input);
var instances = editor.instances();
var image = ImageResource.load(pngOrJpegBytes);
editor.replace(0, 2, image).prepare().to(target);
```
Page and `occurrence` indices are zero-based. `instances()` returns pixel dimensions and mask presence in page order and actual depth-first invocation order. A resource drawn four times has four instances. Later replacements read the document after the previous edit; instance order remains unchanged. Inline `BI` images remain outside this API.

The new PNG/JPEG occupies the original image's unit square, retaining original `cm`, Form Matrix/BBox, clipping, and drawing order. Pixel dimensions may differ; the original content stream determines final page dimensions. Explicit PNG alpha is preserved; JPEG has no alpha. New resources use their own validated color encoding rather than inheriting source color spaces or masks. There is no automatic color management or system-resource lookup.

When passive-layer compatibility is explicitly enabled, replacement resources retain validated source-image `/OC` membership references. Images hidden in the default view remain hidden after replacement.

Source images with stencil, Mask, or SMask are rejected by default. The caller may explicitly accept `IMAGE_MASK / DISCARD`, with actual effect `SOURCE_MASK_REPLACED_BY_EXPLICIT_IMAGE_ALPHA`. WARN and IGNORE produce the same replacement; IGNORE still returns actual losses. Images or Form call paths with structure owners support only `IMAGE_STRUCTURE / SKIP_MATCH` and remain unchanged, avoiding tag-ownership damage from private cloning.

Replacement requests, actual invocations, resource recursion, execution depth, operators, cumulative image pixels, and total input bytes are controlled by `PdfLimits`. Loss policies cannot allow invalid indices, broken references, cyclic Forms, permissions, signatures, budgets, or cancellation. `prepare()` generates and preflights the complete candidate. Path output uses atomic replacement, preserving the old target on failure.

This operation retains the original document and its unused resources; it cannot replace [secure redaction](pdf-redaction-api.md). Independent fixtures use `scripts/create_pdf_instance_edit_fixtures.py`; the consumer task is `pdfInstanceEditing`, with checks in `scripts/verify_pdf_instance_editing.py`.
