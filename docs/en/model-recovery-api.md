# Recovering standard files with companion XML bundles

`XmlModelRecovery` saves the current editable model, notes, and explicit resources from an existing `DocumentConversion.Prepared` into an ordinary ZIP, binding it to the exact bytes of this standard output. Documents remain PDF, DOCX, HTML, Markdown, SVG, single-page PNG, or PNG page bundles. Recovery uses an ordinary `.zip` containing the existing `template.xml`, images, fonts, and licenses plus a `recovery.xml` manifest. There is no new proprietary document extension. Companion bundles leave standard-file structures unchanged; embedding adds only nondisplay information permitted by each standard format.

The module is `export4j-adapter-template` and depends only on the model module. Layout backends and adapters still provide their own standard output. The recovery layer does not depend back on them, execute scripts, or automatically discover companion files or system fonts.

Single-file embedding is always explicit: HTML, Markdown, and SVG use trailing comments; [single-page PNG](#embedded-recovery-in-single-page-png) uses a private ancillary chunk; [PDF/DOCX/PNG page ZIP](#optional-embedded-recovery-in-pdf-docx-and-png-page-zip) use their standard containers. `writeDocument` retains an ordinary file, `writeCompanion` separately saves ZIP, and only `writeEmbedded` adds recovery information. Reading likewise requires choosing an embedded API explicitly.

## Save, recover, and edit

```java
var prepared = DocumentConversion.from(document, notes)
        .prepare(docxAdapter.conversionTarget());
var saved = XmlModelRecovery.capture(prepared)
        .resources(explicitResourceBundle)
        .prepare();
saved.writeDocument(Path.of("report.docx"));
saved.writeCompanion(Path.of("report.docx.recovery.zip"));

var opened = XmlModelRecovery.open("DOCX", Files.readAllBytes(Path.of("report.docx")))
        .companion(Files.readAllBytes(Path.of("report.docx.recovery.zip")))
        .prepare();
// opened.document()/notes(): 已验证的一致模型；resources(): 显式字体和许可。

var edited = XmlModelRecovery.open("DOCX", saved.documentBytes())
        .companion(saved.companionBytes().orElseThrow())
        .conversion()
        .edit(editor -> editor.replaceText("草稿", "已复核"))
        .prepare(docxAdapter.conversionTarget());
var next = XmlModelRecovery.capture(edited)
        .resources(opened.resources().orElseThrow())
        .prepare();
```
`capture` reads the encoding fixed by preflight and the edited candidate, without rerunning import, edits, or target encoding. The complete semantic subset follows [XML model saving (Chinese)](../xml-model-saving-api.md): body content, sections, page regions/variants/overlays, tables, lists, fields, IDs/references, notes, captions, images, and [declarative navigation (Chinese)](../portable-navigation-api.md). Without edits, model and resources can remain consistent. Matching relayout appearance still requires the same fonts, role mappings, engine, and configuration. Recovery does not invent unregistered roles, missing fonts, or unsupported layouts.

`resources(bundle)` copies only explicit font bytes and licenses; saving images traverses the current model again. Old templates, fragments, and deleted images do not enter new recovery bundles. `XmlTemplateWriter.from(...).resources(bundle)` and `conversionTarget(bundle)` provide the same resource transfer. Preserve template authoring intent by saving original XML/fragments directly with `XmlTemplateBundle`; an expanded model cannot reconstruct themes, original variables, conditions, or loops. Runtime component code, font handles, and configuration callbacks are not persisted. Custom blocks retain verified ERROR or WARN/IGNORE + DISCARD handling.

<a id="注释编辑与目录重建"></a>

### Note editing and contents recovery

Matching recovery information retains saved Note IDs, anchors, footnote/endnote types, and text. Explicit NOTE_MARKER, Caption, and standalone CrossReference retain XML model contracts too. Body `replaceText` does not modify external note collections. To edit notes, explicitly supply `editor.notes(revisedNotes)`, then capture recovery from the new model/notes.

To save a table of contents, use model-level `DocumentNavigation`. After recovery, the default engine automatically resolves bookmarks, contents page numbers, and links from current headings:

```java
var declaration = DocumentNavigation.builder().headings(true).maxHeadingLevel(3)
        .contents(DocumentNavigation.Contents.of("Contents", 10)).build();
// 初次保存前将声明加入 SectionedDocument；此后随模型保存和恢复。
var source = document.withNavigation(declaration);
var target = DocumentEngine.builder(explicitFonts).build()
        .conversionTarget("PDF", new PdfBackend());
var edited = openRequest.conversion()
        .edit(editor -> editor.notes(revisedNotes)
                .replaceText("heading-a", "Draft", "Final"))
        .prepare(target);
```
Model declarations save automatic heading selection/maximum level, manual bookmarks, and contents title/formatting/color intent. Edited headings do not reuse old titles. Conflicts between nonempty runtime `Navigation` and model declarations are rejected. Comments, forms, and arbitrary runtime callbacks remain caller-managed; saving does not infer them backward from objects. Older models without declared navigation continue not to generate contents automatically. [RecoveryClosureExample](../../examples/src/main/java/com/mirakyux/export4j/examples/RecoveryClosureExample.java) verifies the full combination and actual application saving. Manual reconstruction from [phase 89 (Chinese)](../verification/phase-89-navigation-recovery.md) remains available as existing runtime usage.

PDF recovery can retain this combination's complete declared model. DOCX must explicitly discard navigation, notes/markers, and degrade captions/standalone references. Web output additionally loses original pagination and dynamic fields. Recovery captured after reimporting the actual current file contains only degraded results and does not restore discarded declarations or notes; see [phase 90 (Chinese)](../verification/phase-90-recovery-closure.md).

## Save the current-file model after lossy conversion

`capture(prepared)` rejects retaining a richer candidate for lossy targets by default. After accepting losses, explicitly choose `MODEL_RECOVERY` + DISCARD to produce ordinary output only. Alternatively, choose DEGRADE with `reimportOnLoss` to rebuild recovery from **the actual standard bytes generated in this operation**.

```java
var policy = ProcessingPolicy.builder()
        .defaultRule(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
        .feature("METADATA", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD)
        .feature("PAGE_NUMBERING", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD)
        .feature("NOTES", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD)
        .feature("NAVIGATION", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD)
        .build();
var prepared = DocumentConversion.from(document, notes).policy(policy)
        .prepare(htmlAdapter.conversionTarget());
var saved = XmlModelRecovery.capture(prepared).policy(policy)
        .resources(explicitResourceBundle)
        .reimportOnLoss((bytes, shared) -> {
            var imported = HtmlImporter.builder().policy(shared).build()
                    .read(new String(bytes, StandardCharsets.UTF_8)).execute();
            return DocumentConversion.Input.of(imported.document(), imported.report());
        }).prepare();
saved.writeEmbedded(Path.of("converted.html"));
```
Markdown uses `MarkdownImporter`. For other formats, the caller must provide the corresponding current-file reader; no implicit cross-format fallback exists. Readers are trusted caller code and must read only supplied current bytes rather than reconstructing content from old models. They return actual models, notes, and ACTUAL reports; preflight reports cannot replace actual results.

| `MODEL_RECOVERY` policy | Behavior when this target export reports losses |
| --- | --- |
| ERROR | Rejects before reading; writes no files |
| WARN/IGNORE + DISCARD | Retains ordinary output only, without invoking a configured reader |
| WARN/IGNORE + DEGRADE | Calls an explicit reader once and saves the current model from limited import; missing readers fail |
| PRESERVE or another unimplemented treatment | Rejects rather than hiding an old candidate in recovery |

Original standard-output bytes remain unchanged. Reports accumulate original import/edit/export losses, `TARGET_EXPORT_LOSS / CURRENT_FILE_REIMPORT`, and actual losses from this reimport. IGNORE retains queryable losses as well. Historical losses do not trigger reimport of a currently lossless encoding or get counted again in later lossless XML rounds. Parsing, I/O, permissions, exceeded budgets, and cancellation propagate directly. A current model not expressible in XML also fails. `prepare()` never touches final paths.

For example, a Web importer without registered images reduces them to alternative text and reports that loss. New recovery no longer contains those images, while original HTML/Markdown retains the actual exported bytes. Explicit fonts/licenses follow existing retention contracts; old XML sources, fragments, and unused images are not copied. To retain images, explicitly register exact resources in the selected importer rather than silently injecting old images into the imported result. Existing XML normalizes equivalent ordinary-text/explicit-run representations; Java construction forms are not promised to match field by field.

See [ComplexRecoveryExample](../../examples/src/main/java/com/mirakyux/export4j/examples/ComplexRecoveryExample.java), [ADR-0092 (Chinese)](../adr/0092-reimport-lossy-output-for-recovery.md), and [phase 87 (Chinese)](../verification/phase-87-complex-lossy-recovery.md) for implementation and complex seven-format examples.

[SectionRecoveryExample](../../examples/src/main/java/com/mirakyux/export4j/examples/SectionRecoveryExample.java) and [phase 88 (Chinese)](../verification/phase-88-section-recovery.md) further cover two sections/four pages, shared page regions, FIRST/EVEN/explicitly empty variants, display-number restarts, overlapping overlays, and nested tables/vertically merged cells. Among 36 A→B→A combinations across six multipage targets, the nine combinations between PDF/SVG/PNG page ZIP preserve the complete model. DOCX explicitly discards overlays; HTML/Markdown rebuild flow content from current files and accumulate actual losses. Single PNG rejects multipage input. Native DOCX import normalizes default-style cell runs to plain text; normalized XML, sections, and actual content are compared separately. This is not a guarantee of lossless saving by arbitrary applications.

## Single-file HTML recovery

```java
var saved = XmlModelRecovery.capture(
        DocumentConversion.from(document, notes).prepare(htmlAdapter.conversionTarget()))
        .resources(explicitResourceBundle)
        .prepare();
saved.writeEmbeddedHtml(Path.of("report.html"));

var request = XmlModelRecovery.openEmbeddedHtml(Files.readAllBytes(Path.of("report.html")));
var opened = request.prepare();
var edited = request.conversion()
        .edit(editor -> editor.replaceText("草稿", "已复核"))
        .prepare(htmlAdapter.conversionTarget());
XmlModelRecovery.capture(edited).resources(opened.resources().orElseThrow())
        .prepare().writeEmbeddedHtml(Path.of("reviewed.html"));
```
`embeddedHtmlBytes()` returns a defensive copy. `writeEmbeddedHtml(OutputStream)` borrows output; the `Path` overload publishes atomically. Only HTML captures are accepted. Ordinary `documentBytes()`/`writeDocument` still produce the original standard file. Lossy captures without recovery produce ordinary HTML and cannot hide discarded content in comments.

One unique trailing `export4j-model-recovery` comment carries a Base64 ZIP; the manifest remains version 1. Length/SHA-256 bind **all HTML bytes** after removing this exact wrapper. Model/resource hashes are checked independently. The digest is not calculated from a normalized DOM. Duplicate, truncated, malformed wrappers, invalid Base64, damaged ZIP, and digest conflicts always fail. The three actions cannot allow them, and `companion(...)` cannot override embedded provenance.

External text edits/reformatting cause rejection by default. When comments are missing, manifest versions unknown, or bindings stale, explicitly configure the same `MODEL_RECOVERY` + DEGRADE policy and fallback used for companion bundles. The fallback receives only current HTML bytes with the exact wrapper removed, returns actual import losses, and does not read old models. Ordinary HTML import does not automatically enter this recovery path.

The complete HTML **including the Base64 comment** is limited by `maxDocumentBytes`. Decoded ZIP separately consumes bundle encoding/expansion budgets. Base64 increases ZIP size by approximately one third. Embedded content and font licenses remain directly readable, without encryption or author authentication. Browser display does not use recovery-bundle fonts; recovered layout uses those resources through explicit loading. External tools may remove/rewrite comments; recovery after saving in arbitrary editors is not promised.

See [ADR-0088 (Chinese)](../adr/0088-embedded-html-model-recovery.md), [EmbeddedHtmlRecoveryExample](../../examples/src/main/java/com/mirakyux/export4j/examples/EmbeddedHtmlRecoveryExample.java), and [phase 83 (Chinese)](../verification/phase-83-embedded-html-recovery.md) for design, example, and verification.

## Single-file Markdown and SVG recovery

`openEmbedded(format, bytes)`, `Saved.embeddedBytes()`, and both `writeEmbedded` overloads provide a unified API accepting `HTML`, `MARKDOWN`, `SVG`, and `PNG` described below. The first three use trailing comments. Original HTML convenience methods remain, with compatible wrapper bytes, and still reject other formats.

```java
var saved = XmlModelRecovery.capture(
        DocumentConversion.from(document, notes).prepare(markdownAdapter.conversionTarget()))
        .resources(explicitResourceBundle)
        .prepare();
saved.writeEmbedded(Path.of("report.md"));

var request = XmlModelRecovery.openEmbedded("MARKDOWN", Files.readAllBytes(Path.of("report.md")));
var opened = request.prepare();
var edited = request.conversion().edit(editor -> editor.replaceText("草稿", "已复核"))
        .prepare(markdownAdapter.conversionTarget());
XmlModelRecovery.capture(edited).resources(opened.resources().orElseThrow())
        .prepare().writeEmbedded(Path.of("reviewed.md"));

var svg = XmlModelRecovery.capture(
        DocumentConversion.from(document, notes).prepare(engine.conversionTarget("SVG", new SvgBackend())))
        .resources(explicitResourceBundle).prepare();
svg.writeEmbedded(Path.of("report.svg"));
var recovered = XmlModelRecovery.openEmbedded("SVG", svg.embeddedBytes()).prepare();
```
The same version 1 manifest binds every standard-file byte after removing the exact trailing comment; Markdown whitespace and SVG trees are not normalized. CommonMark recognizes the wrapper as an HTML comment block; SVG recognizes it as an XML comment after the root element. Original file bytes remain unchanged. Comments do not contribute body text or painted outlines, but remain visible in source. Standard readers need not understand recovery data.

| Format | Matching recovery information | External edits or missing recovery information |
| --- | --- | --- |
| HTML | Restores saved model, notes, and resources | Explicit WARN/IGNORE + DEGRADE, using `HtmlImporter` or another reader for current bytes |
| MARKDOWN | Same; standard `.md` renders directly | Explicit WARN/IGNORE + DEGRADE, using `MarkdownImporter` or another reader for current bytes; subsequent saves contain current content only |
| SVG | Same; standard `.svg` retains its original outlines | No built-in SVG→semantic-model import. None of the three actions invents recovery; an explicit current-file reader satisfying the contract is required, otherwise it fails |

Comment wrappers retain HTML's total-file/Base64/ZIP/expansion budgets. All embedded formats retain synchronous cancellation, resource/old-content validation, borrowed streams, and atomic path contracts. Damaged wrappers cannot be ignored. Lossy captures may discard recovery or explicitly reimport current files as described above; neither hides a source model. Embedded APIs cannot additionally receive `companion(...)`. Format conflicts follow existing three-action policies; a model for one format is not silently assumed to belong to another.

Arbitrary SVG without embedded recovery cannot thereby become editable text, paragraphs, or tables. If external tools delete comments or rewrite any bytes, original bindings no longer remain valid. See [ADR-0089 (Chinese)](../adr/0089-markdown-svg-embedded-recovery.md), [EmbeddedCommentRecoveryExample](../../examples/src/main/java/com/mirakyux/export4j/examples/EmbeddedCommentRecoveryExample.java), and [phase 84 (Chinese)](../verification/phase-84-embedded-comment-recovery.md).

## Embedded recovery in single-page PNG

`PNG` means one standard single-page `.png`, distinct from the original `RasterBackend` multipage ZIP/`PNG_ZIP` format key. Pass the `singlePage()` backend to the engine; complete layout must contain exactly one page. Multipage content, interactive extras, or excess pixels are rejected before output. The backend does not repaginate.

```java
var target = engine.conversionTarget("PNG", new RasterBackend(96).singlePage());
var saved = XmlModelRecovery.capture(DocumentConversion.from(document, notes).prepare(target))
        .resources(explicitResourceBundle).prepare();
saved.writeEmbedded(Path.of("report.png"));

var request = XmlModelRecovery.openEmbedded("PNG", Files.readAllBytes(Path.of("report.png")));
var opened = request.prepare();
var edited = request.conversion().edit(editor -> editor.replaceText("草稿", "已复核"))
        .prepare(target);
XmlModelRecovery.capture(edited).resources(opened.resources().orElseThrow())
        .prepare().writeEmbedded(Path.of("reviewed.png"));
```
The wrapper inserts one unique `exJM` chunk before `IEND`. Its payload directly contains the existing recovery ZIP without Base64 expansion. Flags identify an ancillary, private chunk with a valid reserved bit that should not be copied after pixel changes. Standard image readers can skip it. Removing the exact chunk restores all original PNG bytes; the version 1 manifest still binds their length and SHA-256. IDAT data and decoded pixels remain unchanged. Recovery restores embedded semantics rather than recognizing text/tables from pixels.

PNG wrapping performs bounded chunk-structure, signature, IHDR/PLTE/consecutive-IDAT/IEND ordering, and CRC checks only; it does not decompress and validate arbitrary pixel data. Animation, unknown critical chunks, duplicate/misplaced recovery chunks, trailing bytes, and truncation are rejected. Damaged ZIP/hashes, exceeded budgets, and cancellation likewise cannot be allowed by WARN/IGNORE. Independent verification additionally decodes every sample's pixels with Pillow.

Total PNG including recovery is limited by `maxDocumentBytes`. Chunk count uses `limits.bundle().maxEntries()`, including the added recovery chunk; dimension products use `maxImagePixels()`. ZIP encoding/expansion/entry/resource limits remain under existing bundle budgets. `singlePage()` uses explicit DPI/fonts and retains the Raster backend's per-operation 100-million-pixel cap. Recovery wrapping separately defaults to 40 million pixels; both are checked. Borrowed streams are neither closed nor flushed, paths remain atomic, and lossy captures do not carry richer source models.

External pixel or metadata edits invalidate exact-byte bindings even if old recovery chunks remain. Ordinary image saving may remove the chunk entirely. Missing/stale recovery defaults to ERROR. WARN/IGNORE + DEGRADE still requires an explicit trusted fallback reading current PNG bytes; otherwise it fails. There is no built-in PNG→semantic-model reader, automatic recovery discovery, or general editor save/readback support. See [ADR-0090 (Chinese)](../adr/0090-embedded-png-model-recovery.md), [EmbeddedPngRecoveryExample](../../examples/src/main/java/com/mirakyux/export4j/examples/EmbeddedPngRecoveryExample.java), and [phase 85 (Chinese)](../verification/phase-85-embedded-png-recovery.md).

## Optional embedded recovery in PDF, DOCX, and PNG page ZIP

PDF uses an `EmbeddedFiles` attachment, DOCX uses `customXml/export4j-recovery.xml`, and multipage PNG ZIP uses `export4j/recovery.zip`. The PDF encoder resides in the PDF adapter and must be supplied explicitly. The other containers use the template module's embedded APIs directly.

```java
var saved = XmlModelRecovery.capture(conversion.prepare(target))
        .resources(explicitResourceBundle).prepare();
saved.writeDocument(Path.of("plain.docx"));            // 普通文件，不增加恢复数据
saved.writeCompanion(Path.of("recovery.zip"));          // 可选的独立恢复包
saved.writeEmbedded(Path.of("editable.docx"));         // 显式单文件模式
var reopened = XmlModelRecovery.openEmbedded("DOCX", Files.readAllBytes(Path.of("editable.docx"))).prepare();

var pdfCodec = new PdfRecoveryCodec();
var pdfSaved = XmlModelRecovery.capture(pdfConversion.prepare(pdfTarget))
        .resources(explicitResourceBundle).prepare();
pdfSaved.writeEmbedded(pdfCodec, Path.of("editable.pdf"));
var pdfReopened = XmlModelRecovery.openEmbedded("PDF", Files.readAllBytes(Path.of("editable.pdf")), pdfCodec).prepare();
```
All three containers retain exact standard-file bytes and model/resource hash bindings. When third-party software modifies, recompresses, or removes embedded information, old models do not override current visible content. Missing/stale recovery requires explicit WARN/IGNORE + DEGRADE and a fallback reading **the current file**, otherwise it is rejected. Corruption, conflicts, exceeded budgets, and cancellation fail directly. Attachments are not added to signed, encrypted, or output-intent PDFs. PDF/A/PDF/UA require independent compliance checks. [Phase 90 (Chinese)](../verification/phase-90-recovery-closure.md) actually verifies editing, Save, close/reopen in Word 16.0 and current-file fallback. Arbitrary application saving is not guaranteed to retain recovery information. When Word removes fixed-page-region metadata, callers must supply `DocxAdapter.RegionGeometry` explicitly. Its node-ID normalization is also reported as loss, followed by reimport from current new XML. Phase 86's incomplete SaveAs2 record describes evidence from that time only.

Embedded recovery increases file size and carries plaintext models, fonts, and licenses. The small examples in that batch added 212,367 B for PDF, 285,771 B for DOCX, and 214,281 B for PNG page ZIP. Values vary with model, resources, and compression. Ordinary files have no such overhead; companion bundles put it in separate ZIP files. See [ADR-0091 (Chinese)](../adr/0091-optional-standard-container-recovery.md) and [phase 86 (Chinese)](../verification/phase-86-standard-container-recovery.md).

## Consistency and the three-action policy

Version 1 manifests record format key, file length, SHA-256, and independent hashes of model/resource entries. Format keys have at most 64 uppercase letters, digits, and underscores, starting with a letter. Identical models with changed file metadata are still stale. This conservative rule avoids guessing whether external edits affect visible content. Hashes detect inconsistency rather than authenticate providers. Applications still decide whether to trust supplied documents and companion bundles.

| Situation | Default ERROR | Actual WARN/IGNORE handling |
| --- | --- | --- |
| Current file exactly matches a supported manifest | Restores the complete saved model | Same recovery, retaining historical actual losses |
| Missing companion, conflicting format, modified file, or unknown recovery version | Rejects recovery | Only `MODEL_RECOVERY` + DEGRADE with an explicit current-file `FallbackReader`; returns `recovered=false`, empty resources, and limited-import losses |
| This target export degrades, discards, or skips content | Rejects saving recovery information | `MODEL_RECOVERY` + DISCARD retains standard output only without a companion; alternatively, DEGRADE with `reimportOnLoss` saves only the actual current-file reimported model and actual losses |
| Damaged manifest/ZIP, conflicting model/resource hashes, invalid syntax, damaged resources, exceeded limits, cancellation, or I/O failure | Fails directly | Same failure; no fallback invocation or policy bypass |

WARN and IGNORE behave identically. IGNORE suppresses current warnings without removing actual issues/losses. Historical issues retain their original actions; stricter policies in a later round neither delete nor count them again. `DocumentConversion.Prepared.exportReport()` returns diagnostics only for this target encoding, helping distinguish historical losses from current export losses. Successful encoding does not mean successful final-path publication.

```java
var policy = ProcessingPolicy.builder()
        .feature("MODEL_RECOVERY", ProcessingPolicy.Action.WARN,
                ProcessingPolicy.Treatment.DEGRADE)
        .build();
var current = XmlModelRecovery.open("DOCX", currentDocxBytes)
        .companion(previousCompanionBytes)
        .policy(policy)
        .fallback((bytes, sharedPolicy) -> {
            // 此处选用既有严格 DOCX 导入；若改用允许损失导入，须完整转换并返回诊断。
            var result = docxAdapter.read(new ByteArrayInputStream(bytes), DocxAdapter.Policy.STRICT);
            return new DocumentConversion.Input(result.document(), List.of(),
                    new ProcessingReport(ProcessingReport.Phase.ACTUAL, List.of()));
        })
        .prepare();
```
Fallback must parse the supplied current-byte snapshot while respecting the selected adapter's permission, security, and loss contracts. Without fallback, WARN/IGNORE cannot invent a document. SVG/single PNG can recover models from matching embedded or companion bundles; PNG page bundles use matching companions. Without matching information, there is no general vector/pixel semantic import. Explicit rejection is required rather than treating an original image or OCR as an equivalent model.

## Original packages/pages and deletion

The existing DOCX package path retains original bytes when saving without edits, so original bindings remain valid. Original-package text changes, original-PDF-page text replacement, page selection, cropping, redaction, optimization, or external saving invalidate bindings whenever file bytes change. Old models do not override current visible content. After recovery followed by semantic editing and saving, new bundles contain only the edited model and current images.

Companion bundles are independent files. The library does not search for or delete user-retained old versions. After replacing a standard file, applications should save its new companion or remove old companions. Even retained old bundles are rejected by recovery when hashes mismatch. The two path publications are individually atomic, without a cross-path transaction. Mismatches caused by failure of the second write are detectable and rejected.

Companions store content as plaintext XML/resources and do not inherit standard-file encryption, signatures, or access permissions. Applications must separately manage distribution and access control for equivalent content/resources. Recovery reads explicitly supplied model bundles without decrypting or parsing standard files.

Recovery does not expand original-package/page editing capabilities, turn PDF text replacement into redaction, or guarantee exact-model retention after standard-reader saving. After external saving, applications can explicitly limited-import current content, export it, and create new bindings. Lost information is not filled back in from old bundles.

## Budgets and verification

Defaults limit the current standard file to 64 MiB, the manifest to 1 MiB, and cumulative diagnostics to 10,000 entries. Ordinary ZIP uses `XmlTemplateBundle.Limits` encoding/expansion/entry/text/pixel budgets. Manifests use secure XML and reject DTDs, external entities, unknown structures, and excessively deep trees. Source XML still compiles and binds completely. SHA-256 checks cancellation in chunks; `execution` supports synchronous cancellation/timeouts. Borrowed streams are neither closed nor flushed. Paths use same-directory temporary files and atomic replacement, preserving old targets on failure without non-atomic fallback.

See [ModelRecoveryExample](../../examples/src/main/java/com/mirakyux/export4j/examples/ModelRecoveryExample.java), [ADR-0087 (Chinese)](../adr/0087-bound-standard-output-recovery.md), and [phase 82 (Chinese)](../verification/phase-82-model-recovery.md) for runnable usage, design, and evidence. Three rounds over seven outputs, 49 A→B→A combinations, actual edits, and independent rewrites prove only the registered finite corpus. [Phase 90 (Chinese)](../verification/phase-90-recovery-closure.md) adds declarative navigation, combined features, and real Word save/readback, completing the goals in [GAP-24](capability-gaps.md#recovery-and-round-trip-editing-of-self-generated-documents). Arbitrary-application compatibility and semantic recognition of SVG/PNG without matching information remain separate gaps.
