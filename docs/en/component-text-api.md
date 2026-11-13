# Component font roles and fixed rich text

`FontRoleScope` selects a registered font group for an entire component. `RichTextComponent` expresses multiple text styles inside a fixed box. Both use the existing fluent entry points without requiring a custom drawing component. Runnable code is available in [ComponentTextExample](../../examples/src/main/java/com/mirakyux/export4j/examples/ComponentTextExample.java).

## Select fonts for a component

~~~java
var footer = FontRoleScope.builder(
        PageNumberComponent.builder("{page} / {pages}")
                .fontSize(10).lineHeight(16).build())
        .fontRole("brand").build();
var engine = DocumentEngine.builder(regularFonts)
        .fontRole("brand", brandFonts)
        .pageTemplate(page -> page.footer(
                band -> band.height(20).gap(8).content(footer)))
        .build();
~~~

`regularFonts` and `brandFonts` are `FontGroup` instances explicitly loaded by the caller. The latter may include fallback fonts and a placeholder-warning policy. Names match their original values, without trimming or scanning system fonts. An unregistered name fails before the child component is called. Names and components must be non-null, and names cannot contain only whitespace.

The scope changes only the default font group of its descendants. Other roles explicitly selected in rich text continue to resolve independently. For nested font scopes, the innermost scope wins. Font size, line height, color, page numbers, and font-role mappings retain the original context. Ordinary components, pageable components, and continuous-region flows use the same entry point, preserving short initial regions, narrow component widths, semantic provenance, and diagnostics. Entry points accepting specific text types retain their original type requirements; an entire composite component can be wrapped.

To supply a physical font group directly, continue using `FontScope.builder(content).fonts(group)`.

## Author fixed rich text

~~~java
var header = RichTextComponent.builder().lineHeight(20)
        .add("REPORT ", InlineStyle.builder()
                .fontRole("brand").fontSize(14).rgb(0x224488).build())
        .add("DETAIL", InlineStyle.of(10))
        .alignment(TextAlignment.LEFT)
        .build();
~~~

The required types are in the `components`, `model`, and `text` packages under `com.mirakyux.export4j`. `InlineStyle` is a complete explicit value: `of(10)` means 10 pt, black, and the current default font group. Color does not inherit the outer color, and paragraph styles do not rewrite explicit run sizes. Underline, strikethrough, and baseline offsets are also read from each run. Bold/italic require registered physical font variants.

By default, the builder inherits paragraph geometry. `lineHeight`, `alignment`, `firstLineIndent`, `subsequentLineIndent`, and `lineBreakPolicy` can each be set or restored to inheritance individually. A complete `style(value/callback)` declares the entire paragraph style, and `inheritStyle()` restores inheritance. Paragraph font size and `TextInheritance.color` do not rewrite explicit runs, so there is no component-wide font-size or color setter. `toBuilder()` preserves the run snapshot and declaration intent; changes after a synchronous callback returns do not affect the built value.

Coordinates and lengths use pt, with positive baseline offsets moving downwards. Fixed line height must accommodate every font size and offset; the engine does not automatically enlarge line boxes, shrink text, or clip it. Text may wrap to the available width, but the component remains an atomic block. In a header, footer, background, or foreground, it must fit completely inside the fixed viewport. When body space is insufficient, the entire component moves to another region; exceeding a complete region fails explicitly. Continue using `PageNumberComponent` for page numbers.

## Input and resource limits

- At most 4,096 runs, including empty runs, and at most 100,000 Unicode code points of source text in total.
- The run list cannot be empty, but an individual empty run is valid. An unknown role fails even in an empty run.
- Hyperlinks, U+FFFC object markers, images, and fields are rejected; interaction semantics are not silently discarded.
- Construction, append, and replacement validate and charge budgets first. Failure does not change the existing builder. Layout and validation support synchronous cancellation.
- Diagnostic and error indices use UTF-16 offsets in the concatenated source text. Each role retains its own missing-glyph policy.
- Lists are immutable. Fonts and child components are borrowed under the existing contract; the scope neither closes them nor performs I/O.

`rich.flow()` returns a `RegionFlow` using the same run snapshot, suitable for FlowColumn, GridTable, and ContinuousColumns. `flow(PaginationPolicy)` explicitly controls orphan/widow lines and rejects block-adjacency flags. The paragraph is shaped once at the fixed region width, then sliced at complete lines; first-line indentation does not restart in continuation columns. Default `layout` remains an atomic component. Pagination evidence is in [Phase 57 (Chinese)](../verification/phase-57-recursive-tables.md).

Default role/color cascading and PDF/UA retain independent contracts. Shared complex-text handling remains subject to the reader-compatibility limits in the [Text corpus (Chinese)](../text-corpus-api.md). See [ADR-0045 (Chinese)](../adr/0045-component-text-authoring.md) for the fixed-rich-text design and [Phase 38 (Chinese)](../verification/phase-38-component-text.md) for evidence of the atomic subset.
