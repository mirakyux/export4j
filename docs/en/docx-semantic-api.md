# Native DOCX semantics and editing

`export4j-adapter-docx` provides two semantic layers. `SectionedDocument` represents cross-format body content, tables, page regions, images and fields. `DocxSemanticDocument` wraps it with typed Word positioning, comments, revisions, controls, forms and contents. It stores immutable project models, not original ZIPs, XML fragments or third-party objects.

## Choose an entry point

| Goal | Entry point | Behavior |
| --- | --- | --- |
| Read the general model | `read(input, Policy)` | Rejects proprietary structures under `STRICT` or returns actual diagnostics under `ALLOW_LOSS` |
| Read native semantics | `readSemantic(input, Policy)` | Returns `SemanticReadResult` containing the native model and unsupported-structure diagnostics |
| Create/rebuild native semantics | `writeSemantic(document, output)` | Generates a new standard OOXML package from typed models, writing to caller streams only after full validation |
| Read/rebuild native math | `readMath` / `writeMath` | Uses separate `DocxMathDocument` and typed mathematical trees; see the [math guide](docx-math-api.md) for units, anchors, budgets and diagnostics |
| Native import, edit and output | `semanticConversion(bytes)` | Imports, applies ordered edits and encodes once; after `prepare()`, provides a preview and reusable encoded publication |
| Enter general conversion | `project(document, policy)` or native conversion's `project()` | Projects under actual-loss policies, then uses `DocumentConversion` editing and target backends |
| Ordinary file conversion | `conversion(bytes)` | Uses native reading and explicit projection; unedited objects must report losses too |
| Local text edits in the original package | `editPackage(bytes)` | Preserves unmodified package structure; see [package editing (Chinese)](../docx-editing-api.md) for boundaries |

Callers close input/output streams. Conversion entry points snapshot input bytes. `Prepared.writeTo(Path)` creates a temporary file in the target directory and publishes with an atomic move after completion. Failure/cancellation removes temporary files. Final I/O failures on ordinary streams can still leave bytes already written.

Native math uses separate `readMath` / `writeMath` without changing existing `DocxSemanticDocument` constructors. Ordinary `read`, `readSemantic`, `semanticConversion` and unified conversion imported through them require the math entry point upon encountering OMML, even under `ALLOW_LOSS`. They neither discard equations nor flatten them to TeX. Math documents do not enter the general projection's automatic-layout path either.

Ordinary export and native-semantic rebuilding explicitly declare `compatibilityMode=15` in `word/settings.xml`, with corresponding relationships and content types. This selects modern layout for Word 2013 and later. Without it, Word opens DOCX under legacy layout rules, displays “Compatibility Mode” and may request a format upgrade on Save As; that differs from file-corruption warnings. Local `editPackage` edits retain source compatibility settings without upgrading documents automatically. This setting does not guarantee page-for-page agreement across Word versions.

Real Word open/edit/save/reopen results for new files appear in [phase 107 (Chinese)](../verification/phase-107-windows-reader-compatibility.md).

## Font families and object line spacing

The development version can explicitly declare all four Word font slots and associate a complete script-family profile with semantic roles:

```java
import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.docx.DocxFontFamilies;

var adapter = DocxAdapter.builder()
        .defaultFonts(new DocxFontFamilies("Liberation Serif", "Noto Serif SC"))
        .fontFamily(new DocxFontFamilies("Liberation Sans", "Noto Sans CJK SC"),
                new DocxAdapter.FontRoles("sans", "sansBold", "sansItalic", "sansBoldItalic"))
        .lineSpacing(DocxAdapter.LineSpacing.OBJECTS_AT_LEAST)
        .build();
```

The two-argument `DocxFontFamilies(latin, eastAsia)` uses Latin for ASCII, high ANSI and complex scripts. The four-argument constructor declares `ascii`, `highAnsi`, `eastAsia` and `complexScript` separately; `uniform(family)` uses one family for every slot. Names only declare presentation for the receiving application. They do not discover, load or embed physical fonts, or replace the PDF engine's explicit font configuration.

`defaultFonts` writes document defaults and the regular/bold/italic/bold-italic role keys `""`, `bold`, `italic` and `boldItalic`. `fontFamily(DocxFontFamilies, FontRoles)` supports both role-based output and strict reading. A role must map to exactly one family profile and one bold/italic variant; ambiguous configurations fail at build time. The original `fontFamily(String, FontRoles)` remains a single-family import mapping.

`fontRole("body-alt", families, false, false)` registers additional named role aliases; several aliases may use the same family profile and bold/italic variant. Strict reading preserves registered output aliases whose actual family and variant match. Duplicate aliases and collisions with existing profile roles fail explicitly; arbitrary named style graphs remain unsupported.

| `LineSpacing` | Word line boxes |
| --- | --- |
| `EXACT` (default) | Preserve the existing fixed-height mapping |
| `AT_LEAST` | Write paragraphs with `w:lineRule="atLeast"` |
| `OBJECTS_AT_LEAST` | Use minimum-height lines for paragraphs containing images or native equations; keep other paragraphs exact |

These options affect OOXML output without remeasuring the portable model or guaranteeing Word/PDF pagination parity. An `atLeast` value can be read as the model's line height; the original rule is not a separate portable attribute, so later output uses the current adapter's configuration. Native equation insertion also applies the selected object-spacing policy to avoid clipping tall equations.

Strict reading accepts explicitly registered family profiles and supported flat declarations. Theme-font slots, unknown font attributes and arbitrary named style graphs do not become losslessly supported. Ordinary `write` still rejects unprocessed `InlineVector` objects: bind native equations through `writeMath`, or explicitly report degradation through document conversion. Existing strict/loss handling remains in force; declaring default fonts or object spacing cannot bypass validation. See [mixed content](rich-content-api.md) for one-source equation compilation and actual fallback reports.

## Typed objects

| Object | Actual mapping |
| --- | --- |
| `Drawing` / `Picture` | Embedded PNG/JPEG, inline or `wp:anchor` floating pictures; page, margin, column, paragraph and character positioning bases; alignment or point offsets; wrapping, polygons, z-order, overlap and four-side distances; crop, rotation and horizontal/vertical flips |
| `TextBox` | Real paragraphs/tables in `wps:wsp` / `wps:txbx`; dimensions, four-side padding, vertical alignment, RGB fill/borders, shape rotation/flips |
| `Comment` | Author, initials, date, paragraph/table comment bodies and actual ranges that can span paragraphs |
| `Revision` | Insertion, deletion, move-from, move-to, run formatting and paragraph formatting; moves pair through standard named ranges, with old formats stored as `InlineStyle` / `ParagraphFormat` |
| `Control` | Rich/plain text, checkbox, dropdown, combo box, date, picture, group, repeating section and repeating item; real values are body ranges, and options/native properties are typed |
| Legacy forms | Real complex fields and `ffData` for `FORMTEXT`, `FORMCHECKBOX`, `FORMDROPDOWN`; defaults, maximum length, text type/format, help/status text, enabled state, calculate-on-exit, checkbox size and default dropdown item |
| `Contents` | Native TOC declarations, heading levels, hyperlink/page-number options, cache ranges and update flags; derived page fields inside caches are read as contents-cache text |

Body/textbox paragraph IDs share one namespace. `Point` uses original UTF-16 offsets. `Range` has inclusive-start/exclusive-end points and can span paragraphs within one Word story. Pictures refer to real `InlineImage` markers in the body; textboxes have zero-width anchors and their own block-model content. Ordinary paragraphs in side stories normalize to explicit default-style runs. Drawing lists sort by source paragraph and UTF-16 anchor.

Picture controls must cover one real inline picture. Word does not accept a picture control wrapping a floating picture, so the model rejects it. When both are required, create a floating object and a separate inline picture control. Comment ranges can enter controls; output preserves one complete control and real comment endpoints instead of splitting controls and duplicating identities.

Grouped controls use Word's required `contentLocked`: ordinary group text cannot be rewritten directly, but nested independent value controls remain editable. `replaceText` honors this boundary and cannot bypass group locks with cross-range replacement. Other content/control locks and data binding remain rejected.

Four-level and deeper `p → tbl → p` block cells, merged tables and default/first/even regions across more than two sections can be combined within the existing 16-table-level ceiling, complete-grid checks and region-source deduplication rules. `TableFormat.drawBorders(false)` writes six `w:tblBorders` sides as `nil`, preserving the borderless value on read-back. See the [capability matrix](capabilities-api.md), [page regions (Chinese)](../docx-page-regions-api.md), [fields (Chinese)](../docx-fields-api.md) and [style import (Chinese)](../docx-style-import-api.md).

## Ordered editing

```java
var adapter = new DocxAdapter();
var prepared = adapter.semanticConversion(sourceBytes)
        .edit(edit -> edit
                .setControlText("customer-name", "Updated customer")
                .setChecked("approved", true)
                .replaceText("textbox-paragraph", 0, 3, "New")
                .acceptRevision("review-deletion")
                .rejectRevision("review-insertion"))
        .prepare();

var preview = prepared.preview();
prepared.writeTo(targetPath);
```

IDs must come from the native model or be assigned when the caller creates objects. `drawing`, `comment`, `revision`, `control` and `contents` add/replace typed records. `removeComment` removes comments. `updateContents(id, headingPages)` rebuilds caches from headings; page numbers must come from actual caller pagination, not guessed Word pages. Word-final pagination and linked contents still require updating contents/fields in Word.

Text edits update body/textbox UTF-16 anchors in order. Positions in deleted ranges collapse to the replacement boundary; subsequent positions shift by the real length difference. Replacement cannot split surrogates or atomic images/fields. Covering drawing anchors requires explicit `DRAWING_REMOVED` / `DISCARD` acceptance; pictures/textboxes are not silently removed. Assigning controls checks dropdown choices, enabled state and maximum length.

Accepting deletion removes real source text; rejecting insertion removes inserted text. Moves are paired, and rejecting format revisions restores typed old formats. Output contains no original XML, original package or recovery copy hiding deleted content. Cross-paragraph text deletion preserves model paragraph boundaries; structural revisions of paragraph marks or table rows/columns are outside mapped syntax.

Old font size in `PARAGRAPH_FORMAT` must equal the current paragraph size, with old paragraph attributes inheriting that size. Size changes belong to `RUN_FORMAT`. OOXML's old-paragraph-properties container does not accept run properties; size differences cannot be written into invalid `pPrChange/pPr/rPr`.

## Current form values and field updates

Legacy text-form current values live in body ranges; defaults live in `ControlOptions.defaultText`, and they may differ. Legacy dropdown output includes both current and default selection indices. The current range must be within one paragraph and match an existing option. Replacing the default with the current value must not hide form resets.

Native output does not add global `updateFields` or unconditionally mark ordinary computed fields such as PAGE `dirty`. Broad automatic updates on Word open can reset legacy inputs to defaults; that differs from updating selected TOC/computed fields. After `updateContents(id, headingPages)` creates a cache from actual caller page numbers, it marks that TOC clean. Reads preserve real update flags. Explicit caller `Contents.dirty=true` still writes the standard field; consider Word recalculation side effects when choosing it.

Ordinary text edits, revision decisions and range relocation preserve existing caller update flags. They neither enable Word recalculation automatically nor compute Word-final pages. After editing bodies/headings, call `updateContents` again or explicitly refresh contents in Word. Keeping an old cache does not mean it was updated.

To apply final page numbers, update contents and computed fields such as PAGE selectively in Word, skipping user-input `FORMTEXT`, `FORMCHECKBOX` and `FORMDROPDOWN`. Updating all fields is not input preservation. Existing ordinary `write` defaults for field updating remain unchanged. Native verification separately checks actual current values and independent defaults after open, field update, save and reopen, without field locks blocking normal form editing.

## Conversion loss policies

```java
var policy = ProcessingPolicy.builder()
        .defaultRule(ProcessingPolicy.Action.WARN,
                ProcessingPolicy.Treatment.DEGRADE)
        .feature("COMMENTS", ProcessingPolicy.Action.WARN,
                ProcessingPolicy.Treatment.DISCARD)
        .build();

var prepared = adapter.semanticConversion(sourceBytes)
        .edit(edit -> edit.setControlText("customer-name", "Updated customer"))
        .project()
        .policy(policy)
        .prepare(target);
```

| Feature key | Treatment | Actual effect |
| --- | --- | --- |
| `REVISIONS` | `DEGRADE` | Accept revisions, retain final content, remove review behavior and author/date |
| `COMMENTS` | `DISCARD` | Remove comment body, author/date and range |
| `CONTENT_CONTROLS` | `DEGRADE` | Retain current value content, remove interaction/constraints |
| `TABLE_OF_CONTENTS` | `DEGRADE` | Retain cached contents, remove Word update declarations |
| `FLOATING_DRAWING` | `DEGRADE` | Convert floating pictures to inline, losing positioning, wrapping and z-order |
| `IMAGE_TRANSFORM` | `DEGRADE` | Actually crop, flip and rotate into PNG; retain visible transforms but lose native transform editability |
| `TEXTBOX` | `DEGRADE` | Insert real blocks after the anchor paragraph, losing frame, positioning and rotation |
| `TAB_LAYOUT` | `DEGRADE` | Replace tabs with one space, retaining text, styles, links and UTF-16 length but losing Word tab-stop alignment; covers body, regions, textboxes and cells |
| `INLINE_IMAGE_LINE_HEIGHT` | `DEGRADE` | When fixed line height cannot fit an inline image, expand to image height + absolute baseline shift + text size; modifies only genuinely insufficient paragraph/cell heights and may change pagination |
| `OOXML_MARKUP` | `DEGRADE` | Remove or simplify unmapped OOXML during import |
| `STYLE_GRAPH` | `DEGRADE` | Expand named styles/inheritance graphs to concrete formatting, retaining actual fonts/colors/paragraph attributes while removing graph/name identity |

`ERROR` blocks preparation; `WARN` applies treatment with warnings; `IGNORE` applies treatment while retaining queryable actual-loss records. Actions do not change treatment, and `IGNORE` is not lossless. `PRESERVE` cannot be selected without a verified preservation path. Target backends still add their own actual-loss reports.

## Explicit boundaries

All DOCX inputs share [complete ZIP validation (Chinese)](../docx-editing-api.md#安全、预算和输出). Entire compressed input and expanded bytes have separate limits; central/local records and actual entries are checked for consistency before decompression. Classic single-disk STORED/DEFLATED packages are supported. ZIP64, encryption, multiple disks, truncation, directory conflicts and trailing data fail regardless of loss policy. Ordinary/native semantics, conversion and package editing share these boundaries.

Input remains a finite offline OOXML syntax, not a general interpreter for arbitrary Word features. Macros, ActiveX, OLE/embedded packages, external resources, dangerous/unknown field instructions, signatures and protected documents always fail; loss policies cannot bypass this. Safe links store declarations only, without execution or network loading.

After full-package safety checks, Word-saved `mc:AlternateContent` selects only a `Choice` mapped to supported `wps` / `w14` / `w15` namespaces and never duplicates Fallback content into the body. No supported branch still fails; active objects in alternate branches cannot bypass safety checks. Valid eight-digit hexadecimal `wp14:anchorId` / `editId` are regenerable editing identities normalized during reading; typed drawing identity comes from `docPr`.

Word saves may add checkbox glyphs/fonts, canonical date values/calendars, named style graphs and other presentation properties. Under `ALLOW_LOSS`, unmapped properties produce individual diagnostics; strict mode still rejects them. Preserving visible content does not make these properties lossless. Word may also remove Export4j's fixed-region-height metadata. For external regions, explicitly supply actual geometry with `DocxAdapter.Builder.regionGeometry(...)`; heights are not guessed.

Grouped shapes, arbitrary paths/effects, theme/system colors, nonzero group-local coordinates, text rotation/multicolumn textboxes, standalone VML textboxes, data binding, locked controls outside the grouping exception above, active form callbacks, arbitrary TOC switches, structural revisions and unmapped extension properties are not claimed as supported. Known degradable presentation properties return diagnostics; unsafe/invalid structures fail outright. Declarations, unchanged original-package bytes or opening successfully do not replace semantic-editing and reader verification.

Native DOCX semantics do not guarantee pixel-identical layout after Word reflow. Opening, updating fields, editing controls/textboxes, saving, reopening and exporting PDF need separate verification. A two-section/four-page combination passed these application actions, native Word page rendering, dual-engine PDF rendering and individual page review. See the [specialized verification (Chinese)](../verification/phase-103-native-docx-semantic-editing.md) for reproduction and evidence. Word is used only for independent compatibility verification; Export4j generation/import/editing/conversion does not depend on Office, printer drivers or printing services.
