# PDF semantic recovery and conversion

`PdfAdapter.importDocument(input)` recovers supported static PDFs as `SectionedDocument`. Recoverable glyphs become editable text. Pages the model cannot express but can deterministically render may become whole-page flow images under explicit policy. Unified conversion can then edit and output DOCX, HTML, Markdown, or reflowed PDF. It recovers content and a defined structural subset, without promising original pagination, absolute positions, or arbitrary lossless PDF round trips. It performs no OCR.

## Usage

```java
import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfSemanticImporter;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.nio.file.Path;
import java.util.List;

var adapter = new PdfAdapter();
var input = adapter.input(Path.of("source.pdf"), "");
var policy = ProcessingPolicy.builder()
        .feature("PDF_LAYOUT", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
        .feature("PDF_FONT_MAPPING", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
        .feature("PDF_VECTOR_GRAPHICS", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD)
        .feature("DOCUMENT_METADATA", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD)
        .build();
var candidate = adapter.importDocument(input)
        .analysis(PdfSemanticImporter.Analysis.AUTO)
        // 可选：零基页，已知网格覆盖该区域的自动表格推断，单位 pt。
        .table(0, List.of(20d, 140d, 260d), List.of(80d, 120d, 150d), 1)
        .conversion().policy(policy)
        .edit(editor -> editor.replaceText("ALPHA", "EDITED-CELL"))
        .prepare(new DocxAdapter().conversionTarget());
var preview = candidate.preview();
var actual = candidate.to(Path.of("recovered.docx"));
assert preview.issues().equals(actual.report().issues());
```
Omit `.table(...)` when automatic detection needs no override. `analysis(AUTO)` explicitly enables bounded geometric analysis; default `LINES` retains line-by-line recovery and explicit grids, suitable when callers handle lines and provenance themselves. Both modes report layout inference rather than promising the source author's semantics. `HtmlAdapter`/`MarkdownAdapter` `conversionTarget()` can replace the example target. Regenerating PDF uses `DocumentEngine` and explicit fonts. See [PdfSemanticImportExample](../../examples/src/main/java/com/mirakyux/export4j/examples/PdfSemanticImportExample.java) for the complete call.

For standalone import, call `.policy(policy).prepare()`, inspect the candidate through `document()`, `sources()`, and `preview()`, then obtain ACTUAL results with `execute()`. No open input resources escape; candidates and collections are immutable. Requests are confined to the current task thread. `conversion()` snapshots analysis mode, grids, font roles, raster resolution, and execution options, using the conversion request's shared policy.

## Recovery rules

| Content | Current behavior |
| --- | --- |
| Pages | One section per source page with recovered content; retains paper size, uses flow layout and explicit default margins. Blank source pages create no empty section |
| Reading order | `LINES` forms lines by baseline/X and sorts by Y/X. `AUTO` first divides vertical regions using isolated headings, then columns using clear whitespace gutters, reading top-to-bottom within columns and left-to-right between columns. Content-stream operator order does not replace geometric order |
| Paragraphs and headings | `LINES` makes one paragraph per text line. `AUTO` joins consecutive lines in the same column when font sizes and line spacing are compatible, replacing original line breaks with spaces. Sentence terminators, headings, and new list markers split paragraphs. Font sizes of 1.3/1.6 times the page median infer level-two/level-one headings respectively |
| Lists | `AUTO` recovers explicit bullets and decimal numbers as editable lists; numeric nesting requires existing parents. Switching bullets/numbers at matching indentation/left edges starts independent lists. Mixed indentation, skipped numeric levels, or zero numbers retain complete markers and body text as ordinary Paragraphs and report uncertain structure |
| Headers and footers | `AUTO` moves short text repeated across pages near top/bottom edges into PageRegions without duplicating it in the body. Page numbers remain literal text, without inferred dynamic fields |
| Styles | Retains visible text, font size, and RGB color. Exact BaseFont names can map to model roles with `.fontRole(pdfName, role)`; corresponding fonts must be supplied for target layout. It does not infer original font families, bold/italic styles, or paragraph styles |
| Explicit tables | Both modes accept increasing X/Y grids and header-row counts supplied by the caller. Known grids override automatic inference in the same region. Native Table/TableCell and multiline text remain; invalid grids or boundaries cutting glyphs still fail |
| Automatic tables | `AUTO` infers rows/columns from connected horizontal/vertical lines. Missing internal boundaries recover row/column spans when they form rectangles; independent inner grids become nested Tables. Borderless tables require stable alignment across at least three rows and two to eight columns, plus numeric or compact-matrix evidence. Borderless merged headers are supported where text actually crosses established column boundaries. Whitespace alone is not merge evidence |
| Tables across pages | `AUTO` joins tables at adjacent source-page bottoms/tops when geometry and column widths agree. Identical single-line text headers keep one semantic copy marked for repetition, retaining original source boxes from each page. When a safely repeatable header cannot be formed, complete data rows remain |
| Images | Images actually invoked by Do with positive, unsheared transforms decode to PNG, retaining pixels and display dimensions as separate flow images, without absolute floating positions |
| Form XObjects | Ordinary opaque Forms recover at actual Do invocations, supporting nesting/repetition, local or inherited resources, positive uniform scaling/translation. Visible text also requires uniform transforms. Implicit BBox checks visibility |
| Complex static drawing | Explicit `PDF_STRUCTURE / DEGRADE` can recover a fully renderable page as one flow image. Text, vectors, Type3 CharProc, and complex clipping become pixels and lose independent editing |
| Layers and static tags | First explicitly enable `allowPassiveStructures(true)` on the adapter. Layered documents become per-page flow images in default VIEW. Supported text in tag-only documents can still recover; the tag tree and accessibility structures do not enter the model |
| Provenance | `Source` identifies the source page and a physical-point box from its top-left. `AUTO` leaf paragraphs retain each physical line box; tables additionally have region boxes. A block may have multiline or multipage provenance. Table-cell content uses zero-based physical row/column pairs in `cellPath`; an empty path denotes the block itself, and nested pairs descend into nested cells. These are not character offsets or precise glyph outlines and must not be used as redaction areas |

List inference does not invent missing numeric parents. For example, an indented `1. 正文` following a bullet cannot derive a numeric parent from that bullet alone. The line stays a `Paragraph` including `1. `, reporting `MIXED_LIST_INDENT_RETAINED_AS_TEXT_UNCERTAIN_LIST`. A numeric list jumping directly from level one to level three likewise retains its original line, reporting `NUMBERED_LIST_LEVEL_GAP_RETAINED_AS_TEXT_UNCERTAIN_LIST`. These are `PDF_LAYOUT / DEGRADE` diagnostics. WARN/IGNORE candidates both retain original text and source boxes.

Aligned switches between bullet and numeric markers report `ALIGNED_MIXED_MARKERS_INFERRED_AS_INDEPENDENT_LIST`. Valid consecutive numeric levels retain original positive integers as `NumberedItem.restartAt`. Explicit restarts after returning to a parent remain valid; subsequent children must satisfy parent requirements again. Zero numbers stay ordinary text and end the list context. Recovered results can proceed to semantic layout or Markdown export; nested styles unsupported by the target produce separate target-policy losses.

Only pages with zero origin, CropBox equal to MediaBox, zero rotation, and UserUnit 1 are accepted. Other page views can only be rejected or skipped using `PDF_PAGE_VIEW / SKIP_BLOCK`; whole-page image degradation does not expand geometric support. All font resources, including unused ones, must pass [actual font-program checks (Chinese)](../pdf-font-compatibility-api.md). Editable glyph paths use valid embedded static TrueType, CFF, and traditional Type1 subsets; controlled Type3 can only use whole-page images. This API neither scans system fonts nor supplies rendering/redaction's explicit source-font mappings. A PDF with unembedded standard-font resources cannot recover merely because a viewer can display it.

Ordinary opaque ExtGState may recover under an explicit allowlist: ordinary line parameters are supported; `CA`/`ca` must equal 1, `BM` must be Normal/Compatible, and `SMask` must be None. Dictionary types, numeric ranges, `gs` operands, and resource existence are checked. States execute in the actual content engine, retaining color, text positions, and q/Q restoration. Lines and other paths still report `PDF_VECTOR_GRAPHICS / DISCARD` rather than becoming semantic graphics.

ExtGState fonts, transparency, other blend modes, soft masks, transfer functions, and other states outside the semantic allowlist do not recover as editable blocks. `PDF_STRUCTURE / SKIP_BLOCK` can reject or skip the entire page. If the whole page passes rendering-resource and execution checks, explicit `PDF_STRUCTURE / DEGRADE` can retain visible pixels. Malformed states, missing resources, and incorrect operands always fail; WARN/IGNORE cannot allow them. See the [initial decision (Chinese)](../adr/0094-explicit-pdf-source-fonts-and-passive-graphics-state.md) for the semantic-state allowlist.

Ordinary Form Matrix, resource scope, and state restoration upon return execute through the actual content engine. Repeated calls to one object recover per instance and accumulate budgets rather than deduplicating away body content. Local Resources fully replaces the calling environment and inherits only when absent; the two dictionaries are not merged. An uninvoked Form no longer causes a whole-page skip merely by existing, but embedded-font checks on all resources remain.

Implicit Form BBox is checked against actually transformed glyph outlines and image rectangles. Fully hidden text is discarded as `PDF_HIDDEN_TEXT / DISCARD`; fully hidden images are skipped as `PDF_IMAGE_TRANSFORM / SKIP_MATCH`. Partial clipping sends the whole page through `PDF_STRUCTURE`, choosing page skipping or supported whole-page-image degradation. Already collected content from that page is not returned as partial success. Explicit W/W*, transparency groups, and other inexpressible drawing follow the same whole-page boundary. Cycles, invalid BBox/Matrix, missing resources, budgets, and cancellation cannot be allowed by policy. See [ADR-0095 (Chinese)](../adr/0095-bounded-form-xobject-semantic-recovery.md) for ordinary Form recovery. [Original Form text editing](pdf-text-editing-api.md) separately clones and modifies matched call chains without semantic reflow; Forms with structure owners are not cloned in ways that break tag references.

Editable text must have valid Unicode and geometry, with nonoverlapping horizontal glyphs. Unrecoverable pages use actually supported `SKIP_BLOCK` or `DEGRADE` handling. If all pages are skipped and nothing recoverable remains, the operation fails rather than reporting a false empty-document success.

## Whole-page visible-image degradation

```java
var adapter = PdfAdapter.builder().allowPassiveStructures(true).build();
var input = adapter.input(Path.of("source.pdf"), "");
var policy = ProcessingPolicy.builder()
        .defaultRule(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
        .feature("PDF_TAGS", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD)
        .feature("DOCUMENT_METADATA", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD)
        .build();
var candidate = adapter.importDocument(input)
        .rasterDpi(144)
        .policy(policy).prepare();
var recovered = candidate.document();
var report = candidate.execute().report();
```
The example accepts `PDF_STRUCTURE / DEGRADE`, `PDF_LAYOUT / DEGRADE`, and actually present `PDF_LAYERS / DEGRADE`. Source forms, annotations, bookmarks, or attachments additionally require the corresponding DISCARD permissions below. Ordinary input without layers/tags needs no passive-structure option. Layers and tags must first pass full dedicated validation; neither options nor policies allow broken references, active content, or cycles.

`.rasterDpi(...)` defaults to 144, accepts `(0,1200]`, and affects only explicitly selected whole-page image degradation. Each source page's default VIEW renders as white-background RGB PNG. Disabled layers and content outside clipping do not enter the image; source text is not separately stored as hidden model body text. Tag trees and accessibility information are discarded as `PDF_TAGS / DISCARD`, without PDF/UA promises. See the [passive-structure guide (Chinese)](../pdf-passive-structures-api.md) for original-layer/tag preservation conditions.

The model uses source paper sizes and zero margins, placing one flow-image paragraph per page. PNG retains complete page pixels at the chosen resolution. Display dimensions scale uniformly to 95% of the source page to reserve line-box space. `sources()` boxes still correspond to full source pages. Export reflows content; absolute image positions, final pagination, and original-size fidelity are not round-trip guarantees. Body text, tables, paths, and original objects inside images are no longer independently editable; selecting this path accepts these losses.

Degradation applies only to valid pages with supported rendering paths. Missing actual font programs, unsupported drawing resources or appearances, actual missing glyphs, and corrupted programs cannot become successful image conversions. `SKIP_BLOCK` can still skip a whole page for explicitly permitted resource losses. Invalid structures, permissions, signatures, budgets, and cancellation remain unconditional failures.

## Why the original document cannot be restored directly

PDF stores page drawing results, usually glyphs, paths, images, and coordinates. Table borders may be indistinguishable from decorative lines; aligned text may come from columns rather than cells. `AUTO` infers structure from validated geometry. Actual diagnostics record multicolumn order, paragraph joining, headings/lists, repeated headers/footers, ruled/borderless tables, merged cells, nested tables, and cross-page joins. These inferences may still differ from author intent. Callers with authoritative grids should override automatic decisions using `.table(page, xEdges, yEdges, headerRows)`.

Automatic analysis uses explicit thresholds: large gaps split text fragments within a line; ruled grids cluster axis-aligned lines; short breaks do not create new rows. Internal boundaries with less than 70% coverage merge adjacent cells; nonrectangular merges are not inferred. Borderless tables require repeated alignment and content evidence. Aligned text columns without table evidence stay ordinary body content. Conflicting grids, glyph-cutting boundaries, or nonrectangular results retain original text and record `PDF_LAYOUT` effects containing `UNCERTAIN`. Rejecting policy can prevent candidate generation. WARN/IGNORE accept this explicitly reported candidate.

Blank cells in borderless tables do not prove vertical merges. Geometry cannot uniquely restore author intent for arbitrary irregular layouts, slanted borders, decorative frames, rotated text, footnote references, or floating text/images. The current automatic subset performs no OCR, natural-language semantic analysis, or replacement extraction from untrusted ActualText. Original images can become separate flow images or fit entirely inside automatic cells. Original absolute positions and precise paragraph styles are not recovery guarantees. Markdown output remains limited by its ability to express merged cells and nested tables, with separate target losses.

Readers often substitute local fonts for unembedded PDF fonts. Opening a PDF on one computer therefore does not prove deterministic Unicode, width, or position recovery. Recovery preflight checks every font resource, including unused resources or those referenced by hidden text. Defined subsets of embedded static TrueType, Type1C, CIDFontType0C, static OTF/CFF, and traditional Type1 are accepted; controlled Type3 can recover only as whole-page pixels. System-font fallback does not bypass checks. Unsupported fonts may cause `PDF_STRUCTURE / SKIP_BLOCK` page skipping. DEGRADE cannot create missing font programs. Corrupt programs and actual missing glyphs cannot be skipped by policy.

Export4j inline images and outline text use invisible text anchors for ActualText and selection ranges. Ordinary PDF output now reuses fixed Liberation Sans from the PDFBox dependency, embedding only glyph subsets actually used by anchors. It is neither a missing-glyph fallback for visible body text nor a system-font scan. Full and paged output use the same rule. Original font bytes count toward `PdfLimits.maxFontBytes`, counting identical bytes once. Archival output continues to use the caller's explicit anchor font. These invisible anchors therefore no longer cause whole-page rejection through unembedded Helvetica.

Acceptable fonts and recoverable text are separate checks. When text has been drawn as outlines, the current recoverer does not treat paths or unverified ActualText as editable body text. Outline lines mixed with images follow the same rule, reporting `PDF_VECTOR_GRAPHICS`/`PDF_LOGICAL_TEXT` losses separately; hidden anchors report `PDF_HIDDEN_TEXT`. To retain original editable text, prefer conversion from the semantic model or DOCX/HTML/Markdown source.

WARN/IGNORE can use only existing explicit degradation paths. They cannot turn uncertain structures into authoritative semantics, perform OCR, or bypass font checks. Table appearance inside a whole-page image does not mean editable cells. If policy skips all pages and nothing recoverable remains, conversion still fails and preserves the old target. For supported input, IGNORE retains actual losses and only suppresses warning presentation.

## Policies and losses

ERROR is the default, overridden by operation `RECOVER_PDF`, feature, target format `MODEL`, and defaults. WARN and IGNORE behave identically; IGNORE still returns `issues()`/`losses()`. Only corresponding treatments below are supported. PRESERVE cannot be configured without a path that retains original structure.

| Feature key | Required treatment and effect |
| --- | --- |
| PDF_LAYOUT | DEGRADE: always reports geometric recovery and reflow. `AUTO` additionally reports actual effects of columns, paragraph joins, headings/lists, headers/footers, tables/merges/nesting/cross-page joins, and uncertain structures |
| PDF_FONT_MAPPING | DEGRADE: source fonts without explicit mappings use the model's default role |
| PDF_VECTOR_GRAPHICS | DISCARD: path drawing does not recover as semantic blocks; the new model regenerates table appearance |
| PDF_LOGICAL_TEXT | DISCARD: ActualText/Alt/E does not replace verified glyph text |
| PDF_HIDDEN_TEXT | DISCARD: text in invisible modes, outside pages, or fully hidden by Form BBox is not recovered |
| PDF_PAGE_VIEW | SKIP_BLOCK: unsupported origins, CropBox, rotation, or UserUnit prevent recovery of the whole page |
| PDF_STRUCTURE | SKIP_BLOCK: inexpressible fonts or structures prevent whole-page recovery; alternatively, explicit DEGRADE makes a fully renderable page a flow image, losing independent text/path/object editing |
| PDF_LAYERS | DEGRADE: layered sources recover as per-page default-VIEW flow images without layer switching |
| PDF_TAGS | DISCARD: tag trees, accessibility structures, and tagged reading order are not recovered |
| PDF_IMAGE_TRANSFORM | SKIP_MATCH: unsupported masked, rotated/sheared, or fully Form-BBox-hidden images are not recovered |
| PDF_IMAGE_COLOR/PDF_IMAGE_PRECISION | DEGRADE: colors convert to RGB and precision above 8 bits reduces to 8 bits |
| FORMS/ANNOTATIONS/BOOKMARKS/ATTACHMENTS/DOCUMENT_METADATA | DISCARD: corresponding source objects are not mapped into the recovered model |
| ENCRYPTION_REMOVED | DEGRADE: the model does not carry source encryption; extraction permissions still apply |

Diagnostics include stage, feature, treatment, and source-page location without echoing body text or paths by default. Signatures, security structures, permissions, invalid Unicode, budgets, cancellation, and I/O failures cannot be ignored. Actual glyphs, operators, grid lookups, model nodes, image pixels, and encoded data have cumulative limits. Whole-page rendering after semantic checks uses the remaining budget rather than resetting it per page or drawing instance. This is not a malicious-input sandbox or hard RSS cap.

Automatic analysis also cumulatively charges segment intersections, candidate matching, glyph assignment, and sorting against `maxContentOperators`; new model IDs use `maxObjects`. Recursion is depth-limited and responds synchronously to cancellation. Exhausted budgets cannot be bypassed with IGNORE or return partial structures.

Conversion after import must also accept actual target losses, such as Web page geometry or simplified Markdown styles. Unified-conversion `prepare()` has already imported, edited, and encoded the exact candidate. Path output uses a temporary file in the same directory plus atomic replacement; borrowed streams are not closed. See the [conversion contract](document-conversion-api.md), [ADR-0064 (Chinese)](../adr/0064-bounded-pdf-semantic-recovery.md), [initial verification (Chinese)](../verification/phase-56-pdf-semantic-import.md), and [automatic-structure recovery verification (Chinese)](../verification/phase-95-pdf-automatic-recovery.md).
