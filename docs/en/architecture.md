# Architecture

Export4j centers on a semantic document model and shares text measurement, layout, and drawing. PDF/SVG/PNG consume completed layout; DOCX/HTML/Markdown map semantic structures for the target reader to reflow. The configured entry point, templates, and designer assemble these capabilities.

This document describes the current development implementation. See [current status](current-status.md) for release differences, [capabilities](capabilities-api.md) for supported scope, [architecture decisions (Chinese)](../adr/README.md) for tradeoffs, and the [verification index](verification/README.md) for checks actually performed.

## Layers and data flow

~~~mermaid
flowchart TD
    S[Java authoring / Source content / XML and data] --> A[Authoring and input compilation]
    A --> M[Document / SectionedDocument semantic model]
    M --> P[Semantic preparation and layout orchestration]
    P --> L[Layout Engine]
    L --> IR[Layout IR]
    IR --> DL[Display List + Resolved semantics]
    DL --> PDF[PDF Backend]
    DL --> SVG[SVG Backend]
    DL --> R[Raster Backend]
    M --> E[Semantic format adapters]
    E --> W[DOCX / HTML / Markdown]
    G[Precise drawing API] --> DL
    F[Existing files] --> I[Format reading and specialized editing]
    I -->|Explicit semantic import and loss reports| M
    I --> O[Original-format results]
~~~

The arrows show data flow. Compile-time module dependencies appear separately below. Responsibilities remain directed through the layers:

| Layer | Main types or entry points | Responsibility | Boundary |
| --- | --- | --- | --- |
| Semantic model | `Document`, `SectionedDocument`, `Block`, `InlineContent` | Content, styles, source identities, resource identifiers, and model validation | No backend handles, PDFBox objects, or laid-out coordinates |
| Fonts and text | `FontSource`, `FontFace`, `FontGroup`, `ParagraphLayouter` | Explicit fonts, glyphs/outlines, metrics, and local line layout | Does not decide document page numbers or expose parser-library types in public APIs |
| Semantic preparation and orchestration | `DocumentEngine`, internal `PreparedSemanticDocument` | Semantic expansion, source validation, navigation, page regions, and final fields | Does not replace core pagination or become a global document cache |
| Layout | `DocumentLayouter`, `DocumentLayout`, page fragments | Constraints, line bands, line breaking, pagination, and source mappings | Does not write target files; results retain accepted geometry |
| Drawing | `DocumentPainter`, `DisplayDocument` | Convert final positions to neutral commands and bind interaction/accessibility semantics | Does not repeat line breaking or pagination |
| Backends | `DocumentBackend`, PDF/SVG/Raster implementations | Coordinate conversion, resource embedding, and encoding | Do not reshape or change layout; unrepresentable semantics require rejection or explicit loss reports |
| Format adapters | DOCX, Web, template, and PDF adapters | Map semantics, process original formats, or explicitly recover models | Each has a supported subset, without a promise of arbitrary lossless conversion |

Generation, import, editing, and conversion do not depend on printer drivers, print services, Office, or browser processes. Ordinary paths work in a headless JVM. Word and Chromium serve independent compatibility verification and are outside the libraries' production call paths.

## Modules and dependencies

The repository has 32 Gradle subprojects, including 29 libraries, a BOM, examples, and an optional designer. The table omits the `export4j-` prefix and lists direct production project dependencies. `A` means Gradle `api`, `I` means `implementation`, and `R` means `runtimeOnly`. [Module settings](../../settings.gradle.kts) · [Dependency record](dependencies.md)

| Group | Module | Responsibility | Direct project dependencies |
| --- | --- | --- | --- |
| Foundation | `model` | Semantic documents, template binding, styles, execution, and processing contracts | None |
| Foundation | `font-core` | Explicit font sources, immutable faces, and bounded caching | None |
| Foundation | `font-fontbox` | Static TrueType and CFF font-file parser | I: `font-core` |
| Foundation | `text-core` | Shaping, measurement, and local paragraph lines | A: `font-core`, `model` |
| Drawing and layout | `graphics-api` | Geometry, drawing types, Display List, and output SPI | A: `model`, `text-core` |
| Drawing and layout | `graphics-core` | Graphics primitives, recording, and command execution | A: `graphics-api` |
| Drawing and layout | `layout-model` | Pages, fragments, source mappings, and Layout IR | A: `graphics-api` |
| Drawing and layout | `layout-core` | Pagination, region flow, core component SPI, and drawing conversion | A: `layout-model`, `graphics-core` |
| Drawing and layout | `components` | Standard components, semantic orchestration, and `DocumentEngine` | A: `layout-core` · I: `graphics-core` · R: `font-fontbox` |
| Backends | `backend-pdf` | PDF encoding, document-local fonts, and parser leases | A: `graphics-api` · I: `graphics-core` · R: `font-fontbox` |
| Backends | `backend-svg` | Static SVG output | A: `graphics-api`; I: `graphics-core` |
| Backends | `backend-raster` | Image and preview output | A: `graphics-api`; I: `graphics-core` |
| Adapters | `adapter-docx` | `DocxExport`, native mathematics, and typed OOXML semantic reading/writing | A: `model` |
| Adapters | `adapter-template` | Offline XML/strict Markdown template compilation | A: `model` |
| Adapters | `adapter-web` | HTML/Markdown semantic reading/writing and source-content sessions | A: `model` |
| Adapters | `adapter-pdf` | Bounded original-object operations, rendering, redaction, and semantic extraction | A: `model` |
| Extensions and assembly | `web-layout` | HTML CSS box layout | A: `adapter-web` · I: `components`, `graphics-core` |
| Extensions and assembly | `pdf-signatures` | PDF signing, certification, and trust verification | I: `model` |
| Extensions and assembly | `pdf-recovery` | Embedded PDF model recovery | A: `adapter-template` · I: `adapter-pdf` |
| Extensions and assembly | `math-api` | Shared math options and failure categories | None |
| Extensions and assembly | `math-core` | Bounded TeX vector outlines | A: `math-api`, `model` · I: `font-core` · R: `font-fontbox` |
| Extensions and assembly | `math-web` | Vector formulas in semantic source imports | A: `math-core`, `adapter-web` |
| Extensions and assembly | `math-docx` | Bounded TeX conversion to Word mathematics | A: `math-api`, `adapter-docx` |
| Extensions and assembly | `math-docx-web` | Editable Word formulas from semantic source imports | A: `math-docx`, `adapter-web` |
| Extensions and assembly | `math` | Dual-target math compilation and occurrence identity | A: `math-web`, `math-docx` |
| Extensions and assembly | `opentype` | Native shaping and variable/color fonts | A: `graphics-api`; I: `graphics-core` |
| Extensions and assembly | `opentype-layout` | Native OpenType pagination and vertical layout | A: `opentype`, `layout-core` |
| Extensions and assembly | `starter-pdf` | Write an authored Java `Document` to PDF | A: `components`, `backend-pdf` |
| Extensions and assembly | `starter` | One-time configuration for source/XML input and PDF/DOCX output | A: `components`, `math`, `adapter-template`, `backend-pdf`, `font-core`, `adapter-docx` |

`export4j-bom` constrains the versions of these libraries without automatically adding all modules. The Gradle version catalog and lockfiles manage third-party versions. `examples` combines public APIs to demonstrate usage. `export4j-designer` is a separate local application that retains XML source, data, and resources and uses the Java engine for previews and exports. Neither the designer nor examples participate in Maven library publication.

Use `starter-pdf` or `adapter-docx` for an authored `Document`. Add the template, Web, or math adapter required by the input. The combined `starter` assembles sources and both outputs. Applications that use `font-core` directly to parse font files need `font-fontbox` at runtime. [Optional feature boundaries](../adr/0119-independent-feature-coordinates.md) · [Starter guide](easy-export-api.md)

PDFBox document types stay inside PDF implementation modules; FontBox and pdfbox-io may be encapsulated in the font layer. JLaTeXMath, CommonMark, jsoup, and LWJGL/HarfBuzz implementation types likewise stay out of public APIs, exceptions, and SPIs. Third-party types in specialized tests do not change production boundaries.

## Main execution paths

### Source-content export with one-time configuration

[Export4j](../../export4j-starter/src/main/java/com/mirakyux/export4j/Export4j.java) retains font sources, global configuration, and a bounded cache. Each mutable `Request` enters an independent synchronous execution scope when an output is selected:

~~~mermaid
flowchart TD
    Q[Request.pdf / Request.docx] --> X[ExecutionContext]
    X --> F[OperationFonts binds fonts per operation]
    F --> C[MathContent + ContentImporter]
    C --> S[Source text or XML compile + render]
    S --> V[compileVisual]
    S --> W[compileDocx]
    V --> D[DocumentEngine]
    D --> P[PdfBackend]
    W --> A[DocxAdapter.writeMath]
~~~

[OperationFonts](../../export4j-starter/src/main/java/com/mirakyux/export4j/OperationFonts.java) projects one declaration into input-parser roles, PDF font groups, and Word font slots. It loads each source object once per operation. A business role's regular/bold/italic/boldItalic entries map to explicit physical variants.

PDF and DOCX each run their own projection. XML uses `renderComplete` to bind sections, page regions, and notes before projection. Exporting both targets from the same `Request` reads sources and binds data twice. For both body models from one parse, use the dual-target [MathContent](rich-content-api.md) entry directly.

### XML syntax preparation and data binding

[XmlTemplate.Syntax](../../export4j-adapter-template/src/main/java/com/mirakyux/export4j/adapter/template/XmlTemplate.java) is an immutable static syntax plan owned by the caller. `prepare` safely parses structure and expands the fragments configured at that time, without loading fonts, opening a source-content parser, or binding business data. A later `compile(syntax)` creates a `DocumentTemplate` using the current theme, resources, and parsing configuration, rechecking input/expansion budgets and semantic declarations. `render(data)` then snapshots and binds a Map, record, or JavaBean. Successful static preparation does not establish that all semantics or resources are valid.

`DocumentTemplate` belongs to the JDK-only model layer. Property access follows depth, collection, block, row, and text budgets; expressions cannot call arbitrary methods. Public getters remain trusted application code, and cooperative cancellation cannot preempt a blocked getter.

Source content expands into existing semantic blocks at the authoring/binding boundary. One render shares a `SourceContentParser.Session` per parser identity, accumulating budgets across loops, sections, and dynamic cells, and closes it on completion or failure. XML/strict Markdown templates and general HTML/Markdown import are separate entry points that ultimately share the semantic model and layout. See the [template API](xml-template-api.md) and [source-content blocks](content-blocks-api.md).

### Semantic preparation, complete layout, and page-at-a-time output

[DocumentEngine](../../export4j-components/src/main/java/com/mirakyux/export4j/components/DocumentEngine.java) first brings semantic documents, notes, and navigation into common preparation. Internal [PreparedSemanticDocument](../../export4j-components/src/main/java/com/mirakyux/export4j/components/PreparedSemanticDocument.java) retains only semantic preparation results and a source-block snapshot. It is neither a public cache API nor a container for complete Layout IR.

Complete layout retains the pages' IR and paints it into a Display List. Page-at-a-time export of ordinary documents shares semantic preparation and pagination rules, uses compact planning/validation followed by replay, and paints/releases each page without first collecting all pages. The single-pass path opens an on-demand block source once. When multiple passes are required, the caller supplies the same reopenable snapshot with stable content.

`PdfSpoolBackend` handles one-page PDF objects and a disk index without changing ordinary `PdfBackend` whole-document retention. Backend temporary stream buffers, IR retention, source-block windows, and output objects are separate memory boundaries. See [page-at-a-time output](paged-document-api.md) and [streaming replay](streaming-replay-api.md).

### Semantic formats and existing files

- DOCX/HTML/Markdown exports map the semantic model directly for the target reader to lay out; they do not consume Display Lists. Immutable `DocxSemanticDocument`/`DocxMathDocument` hold DOCX-specific structures and connect them to the body through block IDs and UTF-16 anchors. Projection to the common model reports losses individually.
- Default HTML import maps semantic blocks. Explicit offline CSS layout resolves cascading during import and dimensions/positions during layout. Semantic text slots preserve fields and links. Backends do not parse CSS.
- Existing-PDF original-object editing, page composition, rendering/extraction, selective rebuilding, and semantic recovery have separate entry points. Original-object processing does not invoke the layout engine; callers choose whether to relayout after recovering a model. An overlay rectangle or crop does not delete source content.
- The Graphics API creates Display Lists directly for precise drawing. It does not require a semantic document, and this path alone implies neither semantic export nor accessibility relationships.

## Configuration and content inheritance

[DocumentTheme](themes-api.md) supplies semantic styles; [ContentOptions](content-blocks-api.md) retains per-property declaration intent. Source content resolves in this order: theme/compiler defaults → global/request defaults → XML document → outer-to-inner scopes → explicit block declarations. Available levels depend on the entry point's API. Black, an empty font role, and `false` are valid explicit overrides; their values must not be used to guess inheritance.

Font, color, and paragraph defaults can apply to native semantic blocks. Format and formula/HTML/table switches govern source-content parsing; they do not reinterpret already-built blocks or ordinary bound text in legacy `p`/`span` nodes. Markdown list markers use the block's final default appearance; emphasis on the first body run affects that run alone.

Compilation binds every formula occurrence separately, without deduplicating by formula text. Visual vectors, native OMML, IMAGE, and TEXT are distinct target representations. The default candidate list is empty; representation changes require explicit configuration. TEXT preserves the original TeX, delimiters, that occurrence's complete `InlineStyle`, and its final hyperlink. Budgets, cancellation, and deadlines cannot become successful fallbacks. See [mixed content and cross-format mathematics](rich-content-api.md).

Model-layer `RelativeWeights` shares finite-positive weight snapshots and allocation. Tables, columns, and target formats still own fixed sizes, padding, unit rounding, and budgets. Sharing a pure rule does not make adapters depend on layout-internal runtime objects.

## State and resource lifecycles

| State or resource | Owner and lifetime | Release and sharing boundary |
| --- | --- | --- |
| `Export4j` configuration | Created/closed by the application and reusable across independent requests | Closes its own `FontFaceCache`; borrowed caches and custom PDF backends are not closed by the exporter |
| `Request` | Mutable configuration within an authoring task | Not a concurrently edited object; every export creates execution state and snapshots XML data when it executes |
| `XmlTemplate.Syntax` | Static plan explicitly retained by the caller | Holds no open parser, loaded font, or output document; no implicit global template cache |
| Source-content `Session` | One synchronous render/compilation | Accumulates source/parsing budgets and clears state on close; the built-in `ContentImporter` reserves no namespace on preflight failure, while failure after parsing starts retains the name and spent budgets; name count is bounded |
| `OperationFonts`, engines, and formula text-font snapshots | Current export operation | Engines are not cached across operations; text fonts for formulas are prepared on demand, and local references are released when the operation ends |
| `FontSource.bytes` | Caller retaining the source | Isolates encoded bytes and reuses their content identifier; the source retains its private snapshot independently of cache expiry |
| `FontFaceCache` | Exporter-owned or explicitly supplied application cache | Content-addressed, bounded by entry/source-byte limits and idle expiry; shares immutable faces and bounded same-key pending loads, with parsing outside the lock |
| `PdfFontCache` | Default or caller-owned pool | Horizontal TrueType parsed programs are borrowed exclusively; clear/close removes idle entries and destroys active entries when returned; PDF font objects are never shared across documents, and this pooling does not extend to all font formats |
| Layout IR, Display List, and returned results | Caller retaining the result | Complete/page-at-a-time paths retain different amounts; TTL does not invalidate font, layout, or result objects already held by callers |
| Input/output streams and paths | Caller closes borrowed streams; the operation closes streams it opens | A failed stream may contain an output prefix; path output protects the previous target under its atomic-commit contract |

DOCX declares font families for the receiving application. PDF measures and embeds the same physical font and fails on missing glyphs by default. The no-argument `Export4j.builder()` selects fonts from known local paths at the starter boundary. The explicit entry point and core engine use the fonts supplied by the caller.

Font expiry releases cache references only. Caller-held faces, source snapshots, and active parser leases can extend object lifetimes. Production code does not call `System.gc()` or promise an RSS drop at the TTL deadline. The formula library's own fonts and internal caches have separate lifecycles. See the [font-cache API](font-cache-api.md) and its verification record.

## Contracts across layers

| Contract | Shared rule |
| --- | --- |
| Geometry | Points, top-left origin, X right/Y down; finite dimensions; line boxes separate from baselines; backends convert target coordinates |
| Source | Original UTF-16 ranges; preserve nodes/owners, fragment order, original rows/columns, and anchors; glyph counts are not character counts |
| Measurement/output | Share fonts, glyphs, and advances; accepted lines retain their actual widths; static backends do not reshape |
| Pagination progress | Every iteration consumes content or advances state; an indivisible block that cannot fit fails explicitly rather than generating blank pages indefinitely |
| Page regions/fields | Reserve headers/footers before pagination; distinguish physical pages from displayed numbers; resolve navigation after contents offsets are known; fixed-width dynamic fields cannot change pagination |
| Components | Core defines `Component`/`PageableComponent`, implemented by the standard module; ordinary components move atomically; pageable components return ordered, bounded fragments; trial measurement must be deterministic and repeatable |
| Semantic attachments | `DocumentExtras` retains interactions; `AccessibilityTree` retains logical order and final command bindings; changed commands require rebuilt bindings rather than silent loss |
| Budgets | Accumulate source and actual-presentation budgets separately; charge repeated headers, fragments, fields, and links under their respective contracts; finite budgets are not a process sandbox for malicious input |
| Execution | `ExecutionContext` synchronously propagates cancellation, deadlines, progress, and output limits, restoring or removing its thread-local execution context when the scope ends; the font layer uses neutral `Runnable` checkpoints without depending on model |
| Failure/output | Propagate parameter, resource, permission, budget, and I/O failures; Path replaces atomically only after temporary-file completion and final checks; borrowed streams provide no rollback guarantee |
| Losses | Separate PREVIEW from ACTUAL; ERROR/WARN/IGNORE share an explicit handling decision; IGNORE still reports actual loss and cannot suppress invalid input or execution failure |
| Determinism | Fix inputs, fonts, resources, and configuration to verify layout; assess visual, structural, and file-byte consistency separately |

Starter and `DocumentEngineProvider` pass `ExecutionContext::checkpoint` to built-in font sources. Same-key waits check cooperatively every 25 ms, subject to JVM scheduling. The provider also checks the caller's current execution scope before starting the operation callback. Cancelling a waiter does not cancel shared parsing or other callers. Legacy load methods do not gain an automatic execution context. A custom source's default overload checks before/after the call only; internal waits must explicitly propagate checkpoints. This mechanism cannot preempt font parsing, caller I/O, or getters.

## Extension and maintenance entry points

| Behavior to change | First location | Further reading |
| --- | --- | --- |
| Authoring structures, source traversal, pure value rules | `model`, `SemanticTree`, `RelativeWeights` | [Development](development.md), [model/layout API (Chinese)](../layout-api.md) |
| Font sources, caches, metrics, or shaping | `font-core`, `text-core`; native capabilities in `opentype` | [Text API](text-api.md), [font caching](font-cache-api.md), [native OpenType (Chinese)](../native-opentype-api.md) |
| Line breaking, pagination, region flow, and sources | `layout-core`, `layout-model` | [Layout (Chinese)](../layout-api.md), [tables](table-api.md), [components](components-api.md) |
| Common semantic preparation, page regions, and navigation orchestration | `components`: `DocumentEngine`/`PreparedSemanticDocument` | [Page-at-a-time output](paged-document-api.md), [fields (Chinese)](../inline-fields-api.md) |
| Source parsing, templates, and appearance inheritance | `adapter-web`, `adapter-template`; formula representations in `math` | [Source-content blocks](content-blocks-api.md), [XML](xml-template-api.md), [mixed content](rich-content-api.md) |
| Configured entry-point settings and target assembly | `starter`: `Export4j`/`OperationFonts` | [Configured export API](easy-export-api.md) |
| Format-specific encoding or existing-file operations | Corresponding backend/adapter | [Capabilities](capabilities-api.md), [conversion](document-conversion-api.md) |
| Template editing and preview | `export4j-designer`, retaining source and explicit resources | [Designer](designer.md) |
| API, runtime artifacts, and publication boundaries | Root build, BOM, verification consumers | [API compatibility (Chinese)](../api-compatibility.md), [releases](releasing.md) |

Record core architecture changes in ADRs. Public call contracts belong in the corresponding API guide, supported scope in the capability matrix, executed results in verification records, and engineering tradeoffs in project notes. The overview does not duplicate every implementation rule.

## Key decisions and evidence

| Topic | Decisions and contracts |
| --- | --- |
| Layers, drawing, text, and pagination | [ADR-0001 (Chinese)](../adr/0001-layered-architecture.md), [0003 (Chinese)](../adr/0003-font-text-layout.md), [0004 (Chinese)](../adr/0004-paragraph-pagination.md), [0006 (Chinese)](../adr/0006-components-and-pdf-interactions.md) |
| Final fields and page regions | [ADR-0014 (Chinese)](../adr/0014-inline-semantic-fields.md), [0015 (Chinese)](../adr/0015-semantic-table-fields.md), [0049 (Chinese)](../adr/0049-semantic-page-regions-and-sections.md) |
| Semantic adaptation, DOCX, and CSS layout | [ADR-0050 (Chinese)](../adr/0050-semantic-web-exports.md), [0103 (Chinese)](../adr/0103-typed-native-docx-semantics.md), [0104 (Chinese)](../adr/0104-bounded-html-css-layout.md), [0108 (Chinese)](../adr/0108-native-docx-math-and-example-typesetting.md) |
| Original PDF objects and explicit recovery | [ADR-0096 (Chinese)](../adr/0096-pdf-compatibility-paths.md), [0097 (Chinese)](../adr/0097-explicit-pdf-editing-recovery-and-standards.md) |
| Recursive layout, graphics, and native fonts | [ADR-0099 (Chinese)](../adr/0099-variable-recursive-grid-and-contour-flow.md), [0100 (Chinese)](../adr/0100-native-paints-and-offline-svg-resources.md), [0101 (Chinese)](../adr/0101-explicit-native-opentype-and-cff-embedding.md) |
| Page-at-a-time output, on-demand sources, and replay | [ADR-0102 (Chinese)](../adr/0102-bounded-semantic-streaming-replay-and-pdf-spool.md), [0111 (Chinese)](../adr/0111-page-at-a-time-document-export.md), [0112 (Chinese)](../adr/0112-incremental-block-source.md) |
| Mathematics, source inheritance, and the configured entry point | [ADR-0113 (Chinese)](../adr/0113-optional-bounded-math.md), [0115 (Chinese)](../adr/0115-source-content-options.md), [0117 (Chinese)](../adr/0117-single-source-math-representations.md), [0118 (Chinese)](../adr/0118-configured-export-entry.md) |
| Font caching | [ADR-0114 (Chinese)](../adr/0114-idle-expiring-font-caches.md) and the [cache API](font-cache-api.md) |
| Shared rules, operation bindings, and syntax plans | [Engineering decision (Chinese)](../../.agents/notes/implemented/architecture/2026-11-19-reference-architecture-convergence.md) and [architecture verification (Chinese)](../verification/architecture-convergence.md) |

Early-phase background remains in the [project proposal (Chinese)](../project-proposal.md), ADRs, and [phase verification records](verification/README.md). It does not define the current structure or backlog.
