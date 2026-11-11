# Unified import, editing and conversion

`DocumentConversion` connects supported import, semantic editing and export with common loss policies and result reports. It accepts Markdown/HTML/DOCX and [restricted PDF semantic recovery](pdf-semantic-import-api.md), with DOCX/HTML/Markdown and explicit layout-backend targets. Original PDF object/page editing keeps its own independent entry points.

## Conversion scope

The path is source import → common semantic model → target export, with optional editing. The table shows composable entry points; actual conversions still follow each source and target's supported subsets and loss rules.

| Input | Composable targets | Import boundary |
| --- | --- | --- |
| HTML/CSS | DOCX, HTML, Markdown, PDF | Offline element, attribute and static CSS subsets; resources explicitly supplied by the caller |
| Markdown | DOCX, HTML, Markdown, PDF | General Markdown import subset, independent of strict template syntax |
| DOCX | DOCX, HTML, Markdown, PDF | Semantic subset; simplification of unmapped OOXML requires explicit acceptance; arbitrary original package structure is not retained |
| PDF | DOCX, HTML, Markdown, reflowed PDF | Restricted static-PDF semantic recovery; tables need explicit grids; no OCR or original-layout preservation |
| Static XML template bundle | XML template bundle, DOCX, HTML, Markdown, PDF | `XmlTemplateBundle.conversion()` for templates requiring no data binding, using existing XML compile/bind budgets |

All these semantic inputs can use [XmlTemplateWriter.conversionTarget() (Chinese)](../xml-model-saving-api.md) to save existing XML/ordinary ZIP. The target independently checks unsupported page regions, notes, blocks and properties, rejecting or reporting loss under explicit policy. Fonts/licenses are supplied separately by the caller rather than copied implicitly from the source. Save original dynamic templates as source; explicitly render them first when binding data is required.

PDF targets require explicit fonts and `DocumentEngine`. SVG/PNG can also be layout targets but retain backend restrictions such as page-count and interaction limits. There is no import entry point recovering arbitrary SVG/PNG into editable documents. Loading image resources and importing document semantics are separate capabilities.

## Usage

```java
import com.mirakyux.export4j.adapter.web.HtmlImporter;
import com.mirakyux.export4j.adapter.web.MarkdownAdapter;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.nio.file.Path;

var policy = ProcessingPolicy.builder()
        .defaultRule(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
        .build();
var prepared = new HtmlImporter()
        .conversion("<p id='title'>Old title</p><p style='color:red'>Keep</p>")
        .policy(policy)
        .edit(editor -> editor.replaceText("Old title", "New title"))
        .prepare(new MarkdownAdapter().conversionTarget());

var preview = prepared.preview();
var result = prepared.to(Path.of("report.md"));
assert preview.issues().equals(result.report().issues());
```

Importers' `.conversion(source)`, DOCX `.conversion(packageBytes)` and `DocumentConversion.from(document)` return the same request type. DOCX copies input bytes at the entry point; Web importers retain explicit resource snapshots. Preparation reads the source once, invokes each edit callback once and encodes the target once. Requests and callbacks are confined to the current task thread.

For PDF, use `DocumentEngine.builder(explicitFonts).build().conversionTarget("PDF", new PdfBackend())` as the target. Fonts, roles and runtime configuration come from the engine snapshot. The default engine consumes model page regions and [navigation declarations (Chinese)](../portable-navigation-api.md) automatically, rejecting conflicts with nonempty runtime configuration. Notes come from the conversion input; preconfigured engine notes conflict and are rejected. The caller must truthfully name backend-neutral format keys. Built-in adapter keys are HTML, MARKDOWN, DOCX and XML_TEMPLATE.

## Actions and actual losses

Override order is **operation, feature, format, default**; see the [shared editing policy (Chinese)](../semantic-editing-api.md). Import/export operation keys are `IMPORT` / `EXPORT`; editing uses specific operation keys. ERROR is the default. WARN and IGNORE perform the same supported treatment. IGNORE only omits issues from `warnings()`; they remain in `issues()` / `losses()`.

| Action | On defined incompatible content |
| --- | --- |
| `ERROR` | Reject conversion during preparation without writing the final target |
| `WARN` | Apply explicit simplification/discard rules, continue, and return warnings and actual losses |
| `IGNORE` | Apply the same treatment as WARN, retaining actual-loss records without listing them as warnings |

Action and treatment are independent. `DEGRADE` simplifies, for example retaining text while removing color; `DISCARD` removes content, for example a note without a target mapping. Permission to simplify does not automatically authorize discarding. Configure a treatment supported by the entry point for the relevant feature. Individual properties can be relaxed while everything else remains strictly rejected.

| Target or source | Explicit handling |
| --- | --- |
| HTML/Markdown input | Retains the corresponding [HTML](html-import-api.md) / [Markdown](markdown-import-api.md) source positions and feature keys |
| DOCX input | Unmapped OOXML is `OOXML_MARKUP` / DEGRADE; location region is `PACKAGE_PART:<logical package name>` and UTF-16 is not applicable |
| PDF input | `PdfAdapter.importDocument(input).conversion()`; inferred reading order/line paragraphs require PDF_LAYOUT / DEGRADE acceptance; tables have explicit grids; other losses follow the [recovery matrix](pdf-semantic-import-api.md) |
| Web output | Follows the [Web support table](web-export-api.md); CUSTOM_BLOCK / METADATA / PAGE_NUMBERING require DISCARD, and other existing simplifications use DEGRADE |
| DOCX output | Custom `FONT_ROLE` uses DEGRADE, preserving size, color, links and object positions; standard empty/bold/italic/boldItalic roles remain |
| DOCX captions/standalone references | `CAPTION` + DEGRADE retains ID, text and format while removing caption kind/automatic numbering; `CROSS_REFERENCE` + DEGRADE retains ID, prefix and format and replaces the target with explicit `[unresolved reference]` text, without a dynamic reference |
| DOCX/Web notes | External Note bodies have no target mapping; `NOTES` must explicitly DISCARD, while the source candidate retains notes. DOCX also removes explicit NOTE_MARKER values, reporting `NOTE_MARKER_REMOVED` separately for each original UTF-16 unit. Web markers continue to degrade explicitly through `INLINE_FIELD` to unresolved text |
| Layout output | `MISSING_GLYPH_REPLACED` must explicitly DEGRADE, retaining original UTF-16, block and cell paths; fallback within an explicit font stack is not content loss |

Unedited content is checked in full too. Export locations use known source points or ranges; character ranges are not fabricated when unknown. Unavailable treatments fail, such as PRESERVE without an original-package channel or DISCARD for a style-degradation feature. Invalid structure, macros, security restrictions, permissions, budgets, cancellation, timeout and I/O cannot be suppressed by action policy.

These DOCX treatments apply only to `conversionTarget()`. Direct `DocxAdapter.write` still strictly rejects captions, standalone references and unmapped markers. Native inline PAGEREF/REF retains the [field subset (Chinese)](../docx-fields-api.md). Degrading a caption to text does not preserve its automatic numbering references. Real-file note/reference reimports and all three actions are covered in [phase 89 (Chinese)](../verification/phase-89-navigation-recovery.md).

Model navigation requires a separate NAVIGATION / DISCARD declaration for DOCX/HTML/Markdown and is rejected by default. WARN/IGNORE both report actual removal. Conversion and recovery must not hide discarded navigation inside a recovery package. See [phase 90 (Chinese)](../verification/phase-90-recovery-closure.md) for full combinations and real Word save/read-back evidence.

## Preparation, budgets and output

`prepare` completes full import, editing and encoding, returning an immutable candidate and exact encoded bytes. Preparation can fail on unsupported targets, budgets or encoding errors. `Prepared.preview()` is PREVIEW; successful `to` returns ACTUAL. Failed publication returns no success report. Repeated publication reuses the same encoding without checking the source again.

Defaults cap edit callbacks at 1,000, total diagnostics at 10,000 and encoded output at 64 MiB; adjust them through `DocumentConversion.Limits`. Editing has separate `EditLimits`, and importers/targets keep their own budgets. Encoding uses a bounded in-memory snapshot; layout, parsers and backends may retain their working data, so this is not a hard RSS ceiling.

`.execution(options)` checks cancellation and timeout synchronously during preparation and publication. `.to(Path)` requires an existing parent directory, writes a same-directory temporary file and atomically replaces the target without falling back to copying. Failure preserves the old file and removes the temporary one. `.to(OutputStream)` borrows without closing or flushing; physical write failure can leave partial bytes.

Custom Readers/Writers are trusted code. They must report all actual losses, observe checkpoints and honor resource ownership. A Writer returns an ACTUAL report. The common entry point neither infers custom output formats nor proves them lossless.

See [DocumentConversionExample](../../examples/src/main/java/com/mirakyux/export4j/examples/DocumentConversionExample.java) for a complete runnable call and [ADR-0054 (Chinese)](../adr/0054-prepared-document-conversion.md) for the design.
