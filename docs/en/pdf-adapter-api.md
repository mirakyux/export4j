# PDF processing API

Each API has its own input subset and loss contract. Support for an object in one API does not relax another API's rejection rules.

| Task | API and guide |
| --- | --- |
| Read pages, extract ordinary glyph text, select/merge static pages | `read`/`copy`/`merge`; see below |
| Rotate/crop/scale and explicitly overlay text/images | `editPages`, [page editing](pdf-page-editing-api.md); cropping hides rather than removes content |
| Passive forms, annotations, links, bookmarks, attachments, Info | `readObjects`/`editObjects`, [object editing (Chinese)](../pdf-object-editing-api.md) |
| Select pages and merge sources containing passive objects | `composeObjects`, [object composition (Chinese)](../pdf-object-composition-api.md) |
| Render existing PDFs to PNG/JPEG and extract image resources | `render`/`extractImages`, [rendering and extraction (Chinese)](../pdf-raster-api.md) |
| Compress streams, deduplicate resources, and explicitly resample images | `optimize`, [optimization (Chinese)](../pdf-optimization-api.md); smaller output is not guaranteed |
| Bounded actual text replacement | `editText`, [text editing](pdf-text-editing-api.md); not secure redaction |
| Replace a particular drawing instance of an existing image | `editImages`, [image editing](pdf-image-editing-api.md); shared resources are isolated by invocation |
| Clear area pixels and create a fresh image-only PDF | `redact`, [redaction](pdf-redaction-api.md); loses full-text search and vector editing |
| Selective redaction retaining text/vectors outside selected areas | `redactSelective`, [redaction](pdf-redaction-api.md); reconstructs a complete fresh document and rejects unknown structures |
| Exchange field data | `formData`/`editObjects().importFormData`, [FDF/XFDF (Chinese)](../pdf-form-data-api.md) |
| Read/flatten XFA | `xfa`, [separate XFA subset (Chinese)](../pdf-xfa-api.md); no script execution |
| Recover an editable model and convert to DOCX/HTML/Markdown | `importDocument`, [semantic recovery](pdf-semantic-import-api.md); defaults to line-by-line recovery, with explicit AUTO/grid overrides and no OCR |

The following describes default strict reading/static copying. Explicit `allowPassiveStructures(true)` enables reading and whole-original-document preservation paths for validated layers/tags; see the [passive-structure API (Chinese)](../pdf-passive-structures-api.md). Page selection and cross-source composition still reject these documents.

The reading/static-copying path in `export4j-adapter-pdf` provides page information, text extraction, selected-page copying, and merging. It processes existing objects directly without the layout engine. Semantic recovery uses the separate explicit-loss path above. Public types reside in `com.mirakyux.export4j.adapter.pdf`; signatures do not expose PDFBox.

## Common entry points

```java
var pdf = PdfAdapter.builder()
        .limits(PdfLimits.builder().maxPages(100).build())
        .build();

// 密码始终显式传入。无密码时传空字符串；不获取 URL。
var report = pdf.input(Path.of("report.pdf"), "");
var appendix = pdf.input(Path.of("appendix.pdf"), "");

var read = pdf.read(report);
for (var page : read.pages()) {
    String extracted = page.text();
    // 调用方决定如何使用文字和结构化诊断。
}

// 物理页零基。顺序与重复均有明确含义。
var result = pdf.merge(List.of(report.pages(2, 0), appendix.allPages()), Path.of("combined.pdf"));
pdf.copy(report.pages(0), Path.of("first-page.pdf"));
```
`input(byte[], password)` copies bytes after checking size. `input(InputStream, password)` creates a bounded snapshot, reading at most `maxInputBytes + 1` bytes without closing the borrowed stream. `input(Path, password)` opens only the local file specified by the caller and closes its own stream. Passwords are limited to 1,024 UTF-16 units and remain for the lifetime of `PdfInput`; getters, `toString()`, and default exception messages do not expose them.

`PdfInput` holds no open file or PDFBox document. It is reusable and shareable across threads. Each operation reparses it and checks the current adapter's budgets. `pages(int...)` rejects empty selections and negative indices, then checks upper bounds after parsing. Repeated page indices generate multiple physical pages. `allPages()` retains original order.

## Supported and rejected input

| Item | Initial behavior |
| --- | --- |
| Static pages | Retains content streams, embedded fonts/images and resources, original page boxes, inherited resources, rotation, and UserUnit; no redrawing, wrapping, or pagination |
| Page selection | Zero-based indices, reordering, repetition, and multisource merging; selecting one or more pages splits them into one new file |
| PDF version | Accepts supported static structures in 1.0–2.0; the new file's version is at least the highest input version |
| Compression | No filter or a single Flate filter; images additionally allow a single DCT filter. Unknown filters and multiple-filter chains are rejected |
| Content syntax | Performs bounded checks of brackets/dictionaries/strings/hexadecimal/escapes first; operator allowlist; names or bare tokens are at most 256 bytes. Unknown operators are rejected, including those inside BX/EX |
| Images | Copies ordinary XObject images as encoded resources; rejects inline BI images. DCT is not rasterized; only declared dimensions and encoded-byte budgets are checked |
| Fonts | Page copying retains encoded font resources. Static Type3 declarations and CharProc content are validated first. Copying does not replace fonts; see [font compatibility (Chinese)](../pdf-font-compatibility-api.md) for rendering programs and mappings |
| Interactivity and signatures | Annotations, links, AcroForm, actions, outline bookmarks, signature/permission dictionaries, and attachments are explicitly rejected rather than silently stripped |
| Document semantics | Layers, structure trees, PDF/A OutputIntents, and collections are rejected by default. Ordinary glyphs in passive BMC/BDC are readable; ActualText does not replace them. The explicit passive-structure option permits only validated layers/tags and does not claim PDF/UA, PDF/A, or signature compliance |
| Document properties | New files do not copy Info, XMP, language, or reader display settings; corresponding data produces `DOCUMENT_METADATA_NOT_COPIED` |

Rejection checks cover reachable structures and objects in the cross-reference table. An unsupported object cannot evade checks merely because selected pages do not reference it. The initial path also provides no compression optimization, image resampling, text overlay, OCR, tag reconstruction, preservation of complex interactive objects during merging, or byte-for-byte lossless saving.

## Meaning of extracted text

`ReadResult.pages()` returns every physical page, each with a zero-based index, MediaBox, CropBox, rotation, UserUnit, and text. Box coordinates use the **native PDF bottom-left origin**, with values in default user-space units. Multiplying by `userUnit` gives points. This differs from Export4j layout's top-left coordinates.

Text uses PDFBox's content-order heuristics and normalizes line separators to LF. It does not recover semantic paragraphs, tables, or reliable reading order. Scanned pages and outline-only pages cannot provide ordinary glyph text.

When marked-content `/ActualText` is found, that page reports `ACTUAL_TEXT_NOT_APPLIED`. The adapter retains PDFBox's ordinary-glyph extraction instead of independently concatenating nested BDC or replacing glyphs with logical text. Export4j's invisible `x` anchors for outline text may therefore extract as `x`. Callers must inspect diagnostics and cannot describe the result as complete original text. Page copying still preserves `/ActualText` in original content streams.

## Passwords and permissions

Text reading requires a valid input password and extraction permission. Wrong/missing passwords produce `PASSWORD`; absent extraction permission produces `PERMISSION_DENIED`. Owner passwords have the corresponding permissions.

Copying encrypted PDFs defaults to `ENCRYPTED_COPY_REJECTED`. Only explicit `allowDecryptedCopy(true)` plus an owner password authenticated by the standard password handler permits a new unencrypted file; results report `ENCRYPTION_REMOVED`. A reader password cannot remove output encryption even if all permissions are enabled. Identity uses PDFBox 3.0.8 standard-handler authentication state: user permission objects are always locked read-only, while owner objects remain unlocked. It does not infer identity from permission bits alone or reimplement password normalization. Copying with other security handlers is explicitly rejected. The adapter does not retain original encryption, discover credentials, or bypass permissions.

## Budgets and execution control

`PdfLimits.builder()` defaults are below. Except for per-input/per-stream limits, budgets accumulate within one read or merge operation. Repeated references to the same `PdfInput` are parsed once; separate snapshots count as separate sources.

| Parameter | Default | Measurement scope |
| --- | ---: | --- |
| `maxInputBytes` | 16 MiB | One encoded snapshot; checked before snapshot allocation |
| `maxTotalInputBytes` | 64 MiB | All distinct source snapshots |
| `maxInputs` | 32 | Number of distinct source snapshots |
| `maxPages` | 1,000 | Cumulative source physical pages and selected output pages are limited separately |
| `maxObjects` | 100,000 | Object keys, outer parser nodes, graph nodes, and content tokens/inner elements each have separate cumulative limits |
| `maxDepth` | 64 | Page trees, object graphs, content structures, graphics save stacks, and Form invocation depth during extraction; configurable up to 256 |
| `maxResources` | 10,000 | Font/XObject and other entries in referenced resource dictionaries; repeated dictionary references may be counted repeatedly |
| `maxDecodedStreamBytes` | 16 MiB | Preflight expanded bytes per distinct stream; DCT counts original encoded bytes |
| `maxDecodedBytes` | 64 MiB | Cumulative bytes above for distinct streams, including XRef/ObjStm |
| `maxTextCharacters` | 1,000,000 | Observed glyph text and final output text separately accumulate UTF-16 units, including output whitespace |
| `maxContentOperators` | 1,000,000 | Distinct-stream operators during content preflight; actually executed operators during text extraction have a separate cumulative limit |
| `maxImagePixels` | 16,000,000 | Sum of declared width×height for distinct image streams |
| `maxOutputBytes` | 64 MiB | Final encoded output; also controlled by `ExecutionOptions.maxOutputBytes` |

The internal parser checks XRef streams first, then uses JDK `InflaterInputStream` to bound decompression after ObjStm decryption and before object parsing. Other streams are checked before content interpretation. Content scanning bounds nesting and operator names before PDFBox builds tokens. Array/dictionary elements are counted at parsing entry points; PDFBox must not swallow parse errors as EOF. Deferred object-parse failures likewise survive PDFBox's exception-catching boundaries rather than being replaced with empty objects.

These boundaries **are not a malicious-input sandbox or precise RSS/CPU caps**. PDFBox object-stream internals, font parsing, and some underlying allocations still occur inside third-party calls. ObjStm internal-node budgets are checked during graph validation after expanded-byte limits. Declared DCT dimensions do not constitute full validation by an independent JPEG decoder. The adapter does not decode images and cannot bound external readers' later parsing resource use. Callers handling large untrusted inputs should still use separate processes and operating-system resource limits.

`read(input, ExecutionOptions)` and `merge(selections, output, ExecutionOptions)` propagate cancellation, timeouts, progress, and output budgets on the calling thread without background threads. Stages are `PDF_INSPECT`, `PDF_EXTRACT`, `PDF_COPY`, and `PDF_SAVE`; progress contains only stages and indices. Blocking input streams must supply their own I/O timeouts. Third-party calls observe cancellation upon return or at internal checkpoints.

## Output, exceptions, and resource ownership

Stream output is always borrowed; caller streams are not closed. Inputs and every page selection are preflighted before the first output byte. I/O, cancellation, or output-budget failures during writing may still leave partial bytes; callers should discard failed output.

Path output first creates a temporary file in the target directory, completes all writing and closing, then uses `ATOMIC_MOVE + REPLACE_EXISTING`. The parent directory must exist. Unsupported atomic moves fail without fallback to ordinary copying. Failure or cancellation before commit preserves the original target and cleans the temporary file. The final cooperative cancellation checkpoint is immediately before commit.

`PdfOperationException` supplies stable `code()` and zero-based `inputIndex()`/`pageIndex()` values, or `-1` before reaching the relevant item. Default messages contain no body content, passwords, or paths. Retained causes support trusted diagnostics but may include underlying details and must not be exposed directly to untrusted callers. Execution-control failures use the model's existing `ExecutionException`.

Instances share no mutable documents, caches, or open resources. Every PDFBox document closes when its operation ends. The library reuses the registered Apache-2.0 PDFBox 3.0.8 dependency only, without competing-library implementations.

See [PdfOperationsExample](../../examples/src/main/java/com/mirakyux/export4j/examples/PdfOperationsExample.java), and run `:examples:pdfOperations` plus `python scripts/verify_pdf_operations.py`. Design decisions are in [ADR-0027 (Chinese)](../adr/0027-pdf-operations-adapter.md). Build results, 42 behavior tests, independent selected-page pixels, and isolated-coordinate evidence are in [phase 22 (Chinese)](../verification/phase-22-text-and-adapters.md). This evidence remains limited to the stated static subset.
