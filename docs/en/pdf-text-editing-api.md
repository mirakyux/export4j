# Text replacement in PDF content streams

`PdfAdapter.editText(input)` edits actual Tj/TJ strings on existing static pages and re-encodes them with an explicitly embedded font. It uses zero-based page selection and literal matching, supporting consecutive text-show operators and same-line matches across changes in fonts, sizes, colors, spacing, and explicit positioning. It neither repaginates nor automatically shrinks text.

```java
var policy = ProcessingPolicy.builder()
        .feature("TEXT_FONT", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
        .feature("TEXT_SPACING", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
        .feature("TEXT_STYLE", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
        .build();
var candidate = adapter.editText(input)
        .replace(0, "OLD", "NEW", PdfFont.of(fontBytes))
        .policy(policy).prepare();
candidate.to(target);
```
The explicit font replaces only the matched glyph range, retaining font size and color. Original encodings, fonts, spacing, and positions outside the range remain. TEXT_FONT/DEGRADE must be explicitly accepted. Replacing nonzero TJ spacing within a match additionally requires TEXT_SPACING/DEGRADE. Advance is compensated at the end to retain subsequent original-text positions. Replacement width must not exceed the original range's advance; capacity is not inferred from remaining page whitespace. Gaps left by shorter replacements may be interpreted as spaces by text extractors.

For example, `(SEC) Tj [(RET)] TJ` can match `SECRET`. Consecutive sequences combine glyphs according to each font's original encoding; they do not concatenate raw bytes and decode again, or insert spaces between operators. Multiple matches in one sequence are counted separately. Tf, color, Tc/Tw/Tz/Tr/Ts, and explicit same-line Td/Tm may remain within a sequence. Matches spanning segments draw replacement text using the first matched glyph's style and baseline, requiring `TEXT_STYLE / DEGRADE`. Original segment operators are retained, with advance compensation for every text-show segment so unmatched glyphs keep their coordinates.

A same-line sequence requires identical text-matrix basis vectors, collinear baselines, no backward overlap by later segments, and gaps no larger than half the larger adjacent font size. Different baselines, excessive gaps, q/Q, BT/ET, page boundaries, and Form call boundaries end the sequence. It does not guess cross-line words from extractor-composed strings. Nondefault spacing or TJ adjustments across state changes additionally report `TEXT_SPACING / DEGRADE`; encoded-glyph/shaping boundaries and capacity checks still apply.

Multiple `.replace(...)` calls in one request execute in registration order. Each replacement finishes embedding the new font subset before the next reads updated content. Repeated embedding still cumulatively charges input-font bytes. When there are no actual matches or all matches are skipped, original content streams remain, without re-encoding the whole text segment.

ERROR is the default. The following have actual skip paths only. WARN and IGNORE behave identically; IGNORE still records issues:

| Feature | Action | Result |
| --- | --- | --- |
| TEXT_CAPACITY | SKIP_MATCH | Retains the original overwide matched range |
| TEXT_SHAPING | SKIP_MATCH | Retains original operations for complex shaping, vertical text, or nondefault text spacing/rendering states |
| TEXT_BOUNDARY | SKIP_MATCH | Retains a match that splits one encoded glyph's Unicode mapping |
| MARKED_TEXT | SKIP_MATCH | Retains a matched range inside preserved Span marked content or its ActualText, while other ordinary matches remain editable |
| TEXT_STRUCTURE | SKIP_BLOCK | Leaves the page unedited for unsupported marking/properties, transparent Forms, nonordinary ExtGState, or quote text-show operators |
| TEXT_RESOURCE | SKIP_BLOCK | Leaves the page unedited when resources such as supported explicitly embedded fonts are absent |

Source fonts accept [static TrueType, embedded CFF, and traditional Type1 subsets (Chinese)](../pdf-font-compatibility-api.md). Replacement fonts remain explicitly supplied TrueType; there is no system-font scanning or implicit substitution. Invalid Unicode, operators, font mappings, actual missing glyphs, budgets, permissions, cancellation, and I/O cannot be ignored. Input follows existing static-adapter boundaries. Annotations/fields handled by object editing are not text objects for this API.

Bounded, balanced `/Span BMC` and `/Span ... BDC` containing only string ActualText may be retained unchanged, including named Properties and nested marking. These no longer make ordinary text replacement outside the marked ranges fail for the whole page. Displayed and logical text inside marked ranges remain unchanged. An internal match defaults to ERROR; explicit MARKED_TEXT/SKIP_MATCH is required to skip it. ActualText and unmatched images, page numbers, tables, and page regions remain. Unknown tags, MCID, and other properties still trigger TEXT_STRUCTURE rejection or whole-page skipping; malformed nesting, invalid properties, and budget violations always fail. Enabling passive layers/tags still does not permit editing logical values or structural ownership inside marked content. See [passive structures (Chinese)](../pdf-passive-structures-api.md) for whole-document preservation boundaries and [phase 90 (Chinese)](../verification/phase-90-recovery-closure.md) for original combined-preservation evidence.

`prepare()` completes exact encoding; `to` returns the actual replacement count and ACTUAL report. Target paths use atomic replacement; borrowed output is not closed/flushed. No matches may produce zero modifications. Other objects, metadata, or separate copies are not cleaned by this operation. This API does not provide secure redaction; see the [separate API](pdf-redaction-api.md).

See [ADR-0062 (Chinese)](../adr/0062-bounded-pdf-text-replacement.md) for the design, [phase 54 (Chinese)](../verification/phase-54-pdf-text-editing.md) for original-subset evidence, and [phase 76 (Chinese)](../verification/phase-76-pdf-font-compatibility.md) for CFF and consecutive text-show extensions.

## Shared and nested Forms

Ordinary Form XObjects support text replacement at actual call sites, including local/inherited resources, nesting, repetition, and sharing across pages. Each modified call instance gets a separate clone of the stream and required resource layers, rebinding only its corresponding `Do` name. Original shared streams, unmatched calls, and unselected pages remain. Subsequent replacements continue on updated instances; modification counts accumulate actual successful matches.

Matrix, BBox, and explicit clipping remain in the original PDF without semantic reconstruction. The current font object is inherited from call-time state; a same-named font resource inside a Form does not switch it. Page and Form content streams are not rewritten without an actual replacement. Original advance remains after shorter replacements; extractors may display the remainder as spaces.

Ordinary opaque ExtGState can be edited. Transparency groups, Forms requiring marked-content rewrites, or Forms with `StructParents`/`StructParent` use whole-page `TEXT_STRUCTURE / SKIP_BLOCK` handling to avoid breaking tag ownership through cloning. Nested calls and unselected pages do not thereby lose original layers/tags. Invalid references, implicit recursion, execution depth/operator/glyph limits, and cumulative input-font budgets still fail unconditionally.

Original Form-extension evidence is in [compatibility paths (Chinese)](../adr/0096-pdf-compatibility-paths.md) and [phase 93 (Chinese)](../verification/phase-93-pdf-compatibility-closure.md). Extensions spanning positioning/style changes are separately verified by the `pdfInstanceEditing` consumer and `scripts/verify_pdf_instance_editing.py`.
