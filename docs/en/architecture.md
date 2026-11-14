# Architecture

This document turns the [project proposal (Chinese)](../project-proposal.md) into implementation constraints. The repository currently has 19 modules: 16 libraries, a BOM, examples, and an optional designer application. The optional `export4j-opentype` module encapsulates native shaping so that the core does not require JNI. Production entry points remain in the components module. See [capabilities](capabilities-api.md), [ADR-0025 (Chinese)](../adr/0025-safe-declarative-templates.md) for declarative templates, and [ADR-0010 (Chinese)](../adr/0010-inline-image-layout-and-docx-media.md) for inline images and DOCX media.

## Layers and data flow

~~~mermaid
flowchart TD
    M[Document Model] --> L[Layout Engine]
    L --> IR[Layout IR]
    IR --> DL[Display List]
    DL --> PDF[PDF Backend]
    DL --> SVG[SVG Backend]
    DL --> R[Raster Backend]
    DOCX[DOCX / OOXML] --> I[DOCX Importer]
    I --> M
    I --> DS[Typed DOCX Semantics]
    DS -->|Explicit loss projection| M
    M <--> Web[HTML / Markdown Adapter]
    M --> E[DOCX Exporter]
    DS --> E
    E --> DOCX
    XML[Offline XML / Markdown Template] --> T[Template Compiler]
    T --> B[DocumentTemplate + Explicit Data]
    B --> M
    CSS[Offline HTML/CSS Layout] --> C[Immutable Components]
    C --> L
    Existing[Existing static PDF] --> PA[PDF Adapter]
    PA --> Combined[Checked extraction / selected PDF pages]
    PA --> Recovery[Explicit-loss semantic recovery]
    Recovery --> M
~~~

The document model describes meaning; layout results describe position; display lists describe drawing operations; backends map those operations to a target format. DOCX uses a separate semantic mapping path.

Generation, import, editing, and conversion do not depend on printer drivers, print spoolers, Office, or browser processes. Callers supply fonts and resources explicitly, and ordinary exports work in a headless JVM. Word and Chromium are used only for independent compatibility verification. `scripts/verify_driverless.py` runs a published-coordinate consumer in a JVM that refuses to load all JDK printing classes, and also checks production bytecode and runtime JAR hashes.

The adapter's immutable `DocxSemanticDocument` owns DOCX-specific semantics. Project types represent floating positioning, image transformations, text boxes, comment ranges, revisions, controls, and table-of-contents declarations. It contains common section bodies and explicit anchors, without retaining the original package or XML fragments. Editing rebuilds standard OOXML. Projection into the common model reports each unrepresentable feature; an ordinary text edit must not silently discard unrelated structures.

Default HTML import maps to the common semantic model. Explicit CSS layout compiles offline styles into an immutable component tree. Selectors and media cascading resolve during import; dimensions, Flex/Grid, and positioning resolve during layout. Text leaves use borderless semantic slots, preserving links and fields until final page resolution. Backends consume completed layout. When a semantic target cannot represent component layout, the explicit loss policy applies; retaining CSS source does not count as laying it out.

Variable-width recursive tables and grids remeasure remaining content from its complete source in the layout layer. Accepted lines keep their actual widths, original UTF-16 ranges are not cut, and owners are not renumbered. Contour floats also resolve line bands and anchors during layout. Native OpenType resolves direction, axes, and color glyphs before drawing, then emits project-owned glyph, outline, or image commands. Backends neither own nor invoke a shaping engine.

Backend-neutral types represent advanced colors, paints, isolated groups, and clipping. Each exporter preserves native resources or reports exact losses through the shared handling policy. A single semantic stream and explicit snapshot replay are independent of a complete document snapshot. Applications provide reopenable replay sources whose digests are verified. `PdfSpoolBackend` uses one-page objects and a disk index without changing ordinary `PdfBackend` document retention. See [ADR-0099 (Chinese)](../adr/0099-variable-recursive-grid-and-contour-flow.md), [0100 (Chinese)](../adr/0100-native-paints-and-offline-svg-resources.md), [0101 (Chinese)](../adr/0101-explicit-native-opentype-and-cff-embedding.md), and [0102 (Chinese)](../adr/0102-bounded-semantic-streaming-replay-and-pdf-spool.md).

A backend-neutral `AccessibilityTree` retains logical reading order and bindings to final commands. `DocumentPainter.paintAccessible` uses source model IDs and existing Layout IR to establish supported heading, paragraph, list, image, and table relationships without measuring or paginating again. Callers explicitly bind more complex sources. The PDF backend creates PDF/UA-1 tags from this tree; other backends reject a nonempty semantic tree they cannot preserve. Original-object editing, selective rebuilding, automatic geometric inference, and offline standards validation retain their own resource and loss contracts; see [ADR-0097 (Chinese)](../adr/0097-explicit-pdf-editing-recovery-and-standards.md).

## Core responsibilities

| Layer | Input and output | Responsibility | Boundary |
| --- | --- | --- | --- |
| Document model | Semantic nodes, styles, resource references | Structure, construction, traversal, validation | No backend objects or format handles |
| Fonts and text | Font resources, text, shaping configuration → glyphs and metrics | Font abstraction, measurement, shaping interfaces | No parser-library types in public contracts |
| Layout engine | Model, constraints, resource environment → Layout IR | Line breaking, sizing, pagination, diagnostics | Does not write target files |
| Layout results | Pages, fragments, bounds, source mappings | Final positions, baselines, traceable relationships | No open backend resources |
| Precise drawing | Layout IR or caller drawing requests → Display List | Graphics state, transforms, clipping, drawing commands | Does not break paragraphs or paginate |
| Backends | Display List, resources → target output | Coordinate mapping, resource embedding, writing | Does not modify layout |
| Format adapters | Format structures ↔ common or specialized model | Import, export, capability mapping, loss diagnostics | No promise of lossless conversion between all formats |

The Graphics API can create a Display List directly for precise drawing, without constructing a semantic document.

## Modules and dependencies

Modules are created when needed. This table defines responsibilities and main production dependency directions; build files contain the actual dependency lists. Core modules may depend on the JDK and reviewed internal abstractions, never on backends or test modules.

| Module | Responsibility | Main internal dependencies |
| --- | --- | --- |
| `export4j-model` | Semantic nodes, styles, resource identifiers | Independent foundation |
| `export4j-font-core` | Font resources, metric abstractions, lifecycle | Independent foundation |
| `export4j-text-core` | Adaptive simple/OpenType shaping, measurement, paragraph lines | Fonts and model execution checkpoints |
| `export4j-opentype` | Optional native direction/features, variable outlines, color glyphs, vertical region flow | Layout and graphics cores; LWJGL/HarfBuzz are internal |
| `export4j-graphics-api` | Drawing interfaces, geometry, output SPI | Model and text glyph data |
| `export4j-graphics-core` | Primitives, recording, execution coordination | Drawing API |
| `export4j-layout-model` | Layout IR, fragments, source mappings | Graphics geometry and transitive model, glyph, line data |
| `export4j-layout-core` | Constraints, line breaking, pagination, painting conversion | Layout results, text, graphics |
| `export4j-components` | Standard drawing components, contents, navigation orchestration | Component interfaces from layout core and graphics core |
| `export4j-backend-pdf` | PDF output | Graphics and fonts |
| `export4j-backend-svg` | SVG output | Graphics |
| `export4j-backend-raster` | Image output and preview | Graphics |
| `export4j-adapter-docx` | Common and typed OOXML semantics, editing, explicit projection | Model and resource abstractions |
| `export4j-adapter-template` | Offline XML/Markdown subset → DocumentTemplate | Model and JDK only |
| `export4j-adapter-web` | Semantic HTML5/CommonMark-GFM, explicit offline CSS layout, per-feature loss policies | Model, components, graphics core; CommonMark/jsoup parsers are internal |
| `export4j-adapter-pdf` | Bounded PDF reading, page/passive-object editing and composition, rendering/extraction, optimization, redaction, recovery | Model and execution control; PDFBox is internal |
| `export4j-bom` | Version constraints for published modules | Published artifact coordinates |
| `examples` | Executable use cases and integrations | Public APIs and selected backends |
| `export4j-designer` | Optional local HTML template editor/preview application, outside Maven library publication | Components, template/DOCX/Web adapters, PDF/SVG/Raster backends; core never depends on it |

The designer retains XML source and explicit resources, and uses the Java engine for previews and exports. Data is stored separately; a bound model does not replace source. Its canvas uses React/TypeScript. Rich-text editing and the two display modes are described in [ADR-0107 (Chinese)](../adr/0107-interactive-template-canvas.md). See [ADR-0106 (Chinese)](../adr/0106-optional-html-template-designer.md) and the [designer guide](designer.md) for application boundaries.

PDFBox document and drawing APIs stay inside PDF implementation modules. FontBox and pdfbox-io may be encapsulated inside the font layer, but their types must not enter public signatures. DOCX implementation dependencies stay inside that adapter. Specialized tests may use them, but they do not replace independent tools.

Original-object PDF processing does not invoke the layout engine. [Semantic recovery (Chinese)](../adr/0064-bounded-pdf-semantic-recovery.md) produces a model through a separate explicit-loss channel; callers then choose DOCX/Web or a layout backend. Input snapshots, preflight, permissions, and atomic output from the original strict-copy entry point remain in force; see [ADR-0027 (Chinese)](../adr/0027-pdf-operations-adapter.md). PDFBox parsing is not a process resource sandbox. XML/Markdown templates share model binding, while general Markdown/HTML import remains separate from template syntax and creates no parallel layout engine.

Layout core defines component extension interfaces, implemented by the components module, avoiding cycles. Public entry points accept backends through interfaces; examples and callers choose implementations.

`export4j-bom` aligns public module versions. The Gradle version catalog manages third-party versions; these are separate responsibilities.

## Historical starting point

Phase one began with the document model, graphics API/core, PDF backend, and executable examples. Tests live in their modules; shared test utilities should be extracted only when reuse requires them.

The initial `export4j-model` supplied page sizes and metadata, referenced by the drawing API to support direct Display List generation. Phase three added semantic documents, paragraphs, formatting, and margins while keeping the model free of external dependencies.

Phase two added font abstractions, ordinary line breaking, and line layout. Phase three established paragraph pagination and basic tables. See [capabilities](capabilities-api.md) for later semantic layout, recursive continuation, and streaming; these historical descriptions are not a current backlog. `DocumentEngine` in the components module remains the public entry point; no separate API or test-utility module has been introduced.

## Phase-two text path

~~~text
Explicit TrueType data → FontFace / FontData → SimpleTextShaper
                      → ParagraphLayouter → ParagraphLayout
                      → TextLayoutPainter → GlyphText → PDF
~~~

The font layer parses Unicode mappings and horizontal metrics in advance, returns immutable snapshots, and closes parser resources. Text uses fixed fallback and bounded line breaking, retaining original UTF-16 ranges, baselines, glyph IDs, and advances. Line layout describes paragraph-local coordinates; layout core assigns pages.

Graphics core translates existing lines. The PDF backend embeds the same font bytes, validates glyph IDs, and uses `TJ` adjustments to retain established advances. Complex text consumes neutral outlines directly; pagination uses `atBaseline()` to preserve every drawing result. Font caching is output-local, reusing identical SHA-256 font bytes within a document. See [ADR-0003 (Chinese)](../adr/0003-font-text-layout.md).

## Phase-three mixed-content pagination

~~~text
Document.blocks / Paragraph / Table / PageSettings + explicit font stack
    → DocumentLayouter → DocumentLayout / LayoutPage.elements / LayoutFragment
    → DocumentPainter → DisplayDocument → PDF Backend
~~~

Model `ParagraphFormat` retains semantic formatting without text or drawing dependencies. Layout core maps it to text-layer `ParagraphStyle`, breaks each paragraph once, and places complete lines in page content areas. Cross-page fragments retain paragraph IDs, indices, original line numbers, and UTF-16 ranges.

Pagination advances by lines, or by paragraphs for blocks kept together. Content that cannot fit fails explicitly. Immutable results are not remeasured, rebroken, or repaginated during output conversion. Font fallback diagnostics live in their page fragments and can be located by page and node.

Paragraphs and tables share ordered blocks and page fragments. Tables paginate between complete rows by default. Explicit `allowRowSplit` splits cells at complete text lines, preserving start-line and continuation markers. Continuation pages reuse measured headers and count them against output budgets. A header group plus minimum body fragment that cannot fit a fresh page fails, preventing endless header-only pages.

`Document` uses uniform page settings; `SectionedDocument` supports section paper, margins, and initial displayed page numbers. Font groups and inline roles are passed explicitly at layout entry points. Core `PageTemplate` reserves header/footer regions, and final page-number components run after pagination. See [layout API (Chinese)](../layout-api.md) and [ADR-0004 (Chinese)](../adr/0004-paragraph-pagination.md).

## Components and interactions

~~~text
Document + PageTemplate + Navigation + explicit font stack
    → reserve page regions → paginate body, including ComponentBlock
    → measure front matter → resolve final offsets and interaction targets
    → PageComposer: background, body, headers/footers, foreground
    → DisplayDocument + DocumentExtras → PDF Backend
~~~

Core defines `Component`, `ComponentContext`, positioning, and page-region composition; the components module provides standard implementations. Components measure and return local immutable commands and diagnostics. Body adapters paginate ordinary components atomically and consume ordered `PageableComponent` fragments; semantic paragraphs and tables remain splittable. Core does not import components.

`MultiFloatText` validates edge rectangles in the components layer, computes continuous available intervals for each fixed line band, and breaks text once through existing OpenType width/offset interfaces. Float rectangles occur only in the first fragment; continuation pages restore full width. Closed bands are skipped or produce a bounded float-only fragment. Layout core and backends do not recalculate avoidance; see [ADR-0011 (Chinese)](../adr/0011-multiple-rectangular-floats.md).

Core `RegionFlow`/`FlowRegions` represent a finite prefix of shorter regions followed by full continuation regions of equal width, returning local fragments with region indices. `FlowText`/`OpenTypeText` break once and distribute complete lines. `ContinuousColumns` combines first-group remaining page height with full continuation columns into physical fragments, retaining the tallest column's actual height in the last fragment. Existing `MultiColumn` whole-page/bounded-balancing contracts remain; see [ADR-0012 (Chinese)](../adr/0012-continuous-text-regions.md).

`FlowColumn` passes children the remaining prefix after padding, consumed height, and region-local gaps; nesting is supported. Ordinary components and older pageable fragments move whole. `GridTable` measures cells once and distributes connected row groups formed by spans. Every occupied column repeats headers and contains at least one body group. Shared validation checks counts, order, dimensions, and continuous progress. Backends do not relayout; see [ADR-0013 (Chinese)](../adr/0013-mixed-content-regions.md).

`DocumentExtras` stores resolved bookmarks, internal links, external URIs, comments, and fields without PDFBox objects. A fixed page-number column keeps contents layout independent of final numbers; resolution uses real page targets. The PDF backend only converts coordinates and creates outlines, annotations, and AcroForm fields.

Comments and forms are interaction objects separate from drawing; readers usually show them above page content. Forms support text, checkboxes, dropdowns, and radio groups. Chinese fields fully embed one explicit font, preserving current/default values and appearance streams, without scripts or automatic flattening. See [components API](components-api.md) and [ADR-0006 (Chinese)](../adr/0006-components-and-pdf-interactions.md).

## Geometry and coordinates

New interfaces follow these conventions; changes require an ADR:

- Logical lengths use points: `1 pt = 1/72 inch`; core calculations use `double`.
- The page origin is top left, X increases rightward, and Y downward.
- Page/content dimensions must be finite and valid for their contracts. Negative positions can mean off-page locations; they are not negative dimensions.
- Interfaces and tests specify path behavior, matrix composition order, and clipping.
- Text distinguishes line boxes from baselines; images distinguish layout dimensions from source pixels.
- Backends consistently convert target coordinate differences, including rotation, crop regions, and transforms.

The semantic model retains settings and styles, not laid-out absolute positions. Project geometry types prevent Layout IR from retaining PDFBox or AWT implementation objects.

## Text and resources

Text processing distinguishes UTF-16 code units, Unicode code points, glyphs, and clusters. Measurement and output share identical fonts and shaping results.

Font resolution is explicit, with fixed, diagnosable fallback order. `FontGroup` fails on missing glyphs by default; explicit placeholder mode preserves source text and returns warnings. Basic `SimpleTextShaper` still rejects complex scripts. Default paragraph layout uses JDK OpenType with explicit static TrueType/CFF fonts when needed; `TextOutline` retains neutral paths and logical text. Explicit `OpenTypeText` offers controls such as kerning without system-font fallback. See [production API (Chinese)](../production-api.md).

`OutlineText` resolves paths during layout and retains logical text through PDF ActualText; static backends consume the same paths. Static rendering of `GlyphText` extracts outlines for established glyph IDs and must not reshape. SVG/Raster reject interactive extras; DOCX maps semantics without coordinate commands. See [ADR-0008 (Chinese)](../adr/0008-production-capabilities.md).

Resource references use stable identifiers, not arbitrary backend objects. A separate document context owns document resources and releases those it owns on close. Global caches need capacity, concurrency, and eviction policies.

## Templates and controlled export

JDK-only model `DocumentTemplate` snapshots and binds dotted-path data. Maps, records/JavaBeans, and nested Lists share one bounded snapshot. Conditions, loops, and collection tables obey access, depth, block, row, and text budgets. Object binding reflects record components or public JavaBean properties without reading fields; template expressions expose no arbitrary method calls, scripts, or network entry points. Getters remain trusted application code rather than a sandbox; see [Java objects](xml-template-api.md#java-objects) for the full boundaries.

`ExecutionContext` propagates cancellation, deadlines, progress, and output-byte limits within synchronous calls, restoring or removing its ThreadLocal in `finally`. Atomic writes complete a same-directory temporary file before atomically replacing the target. PDF temporary storage moves backend stream buffers only, not IR, Display Lists, or resources. PDF/A, encryption, and external CMS signing are encapsulated in PDF modules; see [ADR-0009 (Chinese)](../adr/0009-shared-text-templates-and-controlled-export.md).

## Diagnostics and failure

Diagnostics identify at least the stage, severity, stable code, and cause, with node, page, or resource identifiers where applicable. Contracts specify page-number origins.

Invalid input, missing required fonts, unrecoverable layout, and output failures explicitly fail their operations. Format downgrades report concrete losses and continue only when explicitly accepted by the caller.

Every pagination iteration must consume content or change pagination state. An indivisible item larger than available space uses an explicit overflow policy or fails; it must never create pages indefinitely.

## DOCX and PDF boundaries

DOCX export preserves paragraph, table, and style semantics for the reader to lay out. PDF uses completed page drawing results.

DOCX inline fields map to native PAGE/NUMPAGES/PAGEREF/numbering REF fields updated by Word. Ignorable extensions retain engine reserved width without constraining Word layout. Import recognizes allowlisted simple/flat complex fields; absent widths require explicit input. Unrepresentable semantics fail by policy or return cached text and diagnostics. PAGE fields in repeated headers are explicitly rejected on export. See [ADR-0016 (Chinese)](../adr/0016-native-docx-fields.md).

PDF import cannot inherently recover original paragraphs, tables, or Word structure. PDF analysis and editing capabilities are defined separately and do not promise lossless reconstruction from arbitrary PDFs.

Each adapter declares supported, downgraded, and unsupported features, with loss diagnostics.

## Reproducibility

Authoring conveniences produce the existing semantic model; automatic IDs do not replace explicit reference targets. Justification modifies glyph advances or physical outlines during layout, never in backends. Strict Chinese line-break rules use original UTF-16 boundaries. Rich headings/list runs survive template processing and semantic downgrades; see [ADR-0017 (Chinese)](../adr/0017-authoring-and-justification.md).

Identical inputs, fonts, resources, and configuration should yield identical layout. Tests fix font versions, resource identifier order, locale, and relevant configuration.

Layout, visual, and byte consistency are verified separately. Files may contain timestamps or object-order metadata; byte stability applies only to a specifically declared deterministic-output mode.

## Key decisions

Public fluent builders, components-layer `Documents` conveniences, declarative grid positioning, and deterministic row heights are covered in [ADR-0018 (Chinese)](../adr/0018-fluent-authoring.md). Conveniences produce the existing model without changing module direction or backend layout boundaries.

[ADR-0001 (Chinese)](../adr/0001-layered-architecture.md) defines layering, DOCX flow, and third-party boundaries. Font implementations, publication coordinates, and dependency versions record verification when adopted.

[ADR-0002 (Chinese)](../adr/0002-initial-pdf-output.md) covers the initial PDF scope and standard-font policy. [ADR-0003 (Chinese)](../adr/0003-font-text-layout.md) covers phase-two fonts, line boxes, and public interfaces. [ADR-0004 (Chinese)](../adr/0004-paragraph-pagination.md) covers semantic paragraphs, source fragments, and pagination progress.

[ADR-0005 (Chinese)](../adr/0005-basic-tables.md) covers mixed blocks, column widths, and repeated headers; see the [table API](table-api.md). [ADR-0006 (Chinese)](../adr/0006-components-and-pdf-interactions.md) covers extension, regions, positioning, and PDF interactions. [ADR-0007 (Chinese)](../adr/0007-advanced-layout-and-font-policy.md) and the [advanced API (Chinese)](../advanced-api.md) cover advanced layout, missing-glyph policy, and bounded footnote reservation.

Dynamic inline fields use fixed-width atomic boxes. After final pagination, outlines and logical text are replaced while preserving source and geometry. Semantic cells reuse field boxes; repeated headers resolve against their actual page, while row splitting retains original row/column and UTF-16 source. See [ADR-0015 (Chinese)](../adr/0015-semantic-table-fields.md). Explicit footnote markers belong to their actual line, using existing monotonic reservation and continuation. Model, text, layout, and backend duties are described in [ADR-0014 (Chinese)](../adr/0014-inline-semantic-fields.md) and the [inline fields guide (Chinese)](../inline-fields-api.md).

Semantic `PageRegion` retains ordered paragraphs/tables, and `Section` carries default repeated regions. XML sections share binding budgets; the component bridge measures fixed regions and resolves fields on final pages. DOCX maps standard region parts and local media relationships directly, with no model dependency on components or backends. See [ADR-0049 (Chinese)](../adr/0049-semantic-page-regions-and-sections.md) for fixed geometry metadata and explicit fill-in policies for ordinary Word files.

HTML/Markdown exports use an independent semantic adapter and reflow in the target reader; they do not consume Display Lists. ERROR/WARN/IGNORE controls losses per feature without suppressing invalid input or execution failures. See [ADR-0050 (Chinese)](../adr/0050-semantic-web-exports.md) for shared source/output budgets and atomic output.
