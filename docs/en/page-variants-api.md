# First-page and even-page regions

`PageRegions` adds complete `FIRST` and `EVEN` header/footer pairs to default headers and footers. The model, XML, final PDF/SVG/PNG and DOCX share the same declarations. Regions still have fixed heights and accept only the existing bounded paragraphs, images, tables and page-number fields. See [ADR-0069 (Chinese)](../adr/0069-semantic-page-variants.md) and [phase 61 (Chinese)](../verification/phase-61-page-variants.md).

## Java declarations

```java
var regions = PageRegions.builder()
        .header(defaultHeader).footer(defaultFooter)
        .variant(PageRegions.Variant.FIRST, PageRegions.Bands.EMPTY)
        .header(PageRegions.Variant.EVEN, evenHeader)
        .footer(PageRegions.Variant.EVEN, evenFooter)
        .build();
var document = new PageDocument(body, regions);
engine.export(document).backend(new PdfBackend()).to(pdfPath);
```

The caller supplies `body`, all regions and explicit fonts. Every variant is a complete header/footer pair. When creating a variant, an unset side is empty rather than filled from the default pair. `toBuilder().inherit(variant)` removes the entire pair declaration; explicit `Bands.EMPTY` retains the declaration and clears both sides. These meanings differ.

| Condition | Selection |
| --- | --- |
| First page of the section, with FIRST declared | FIRST, before page-number parity |
| FIRST does not match, displayed page number is even, and EVEN is declared | EVEN |
| Otherwise | Default pair, including odd pages |

Parity uses the section's final displayed page number. Physical page index, contents offset and displayed number are handled separately. If a section without FIRST starts at an even number, its first page uses EVEN. Before paginating the body, layout reserves the largest header and footer extents across all declarations, each calculated as height + gap. Switching actual regions does not repaginate the body. Unselected variants still participate in binding, ID/budget checks and missing-data validation.

Equal sources share binding and editing within the same region kind. `headers()` / `footers()` return deduplicated sources in default, FIRST, EVEN order. `map` preserves declarations and empty pairs; locations use names such as `HEADER_FIRST`. Callers needing shared accounting should cache input sources. The model owns neither fonts nor I/O resources.

## XML

```xml
<document version="1">
  <header height="24" gap="4"><p>Default</p></header>
  <header variant="first" height="0"/>
  <footer variant="first" height="0"/>
  <header variant="even" height="32" gap="4"><p>Even</p></header>
  <section id="report" first-page-number="2"><p>Report</p></section>
</document>
```

`variant` must be static `default`, `first` or `even`, defaulting to `default`. Each root/section may declare a given kind and variant at most once, after optional page settings and before body content. Fragments, loops, conditionals and dynamic attributes cannot declare page regions.

Sections inherit root declarations. Overriding one side of a variant preserves its other side from the root; if the root has no such variant, the undeclared side is empty. An empty declaration with `height="0"` explicitly clears content and is not inheritance. XML currently has no syntax to remove the root FIRST selector and restore parity selection; Java can use `inherit(FIRST)`. Call `renderWithPageRegions` or `renderSections`; old body-only `render` does not discard regions.

Static `source-id` / `source-ref` can share complete regions across variants of one kind or across sections. See [shared sources (Chinese)](../xml-page-regions-api.md#共享来源). References neither fill undeclared variant pairs nor change FIRST precedence. `XmlTemplateWriter` saves these declarations and explicit empty pairs; see [model saving (Chinese)](../xml-model-saving-api.md).

## Adapters and compatibility

DOCX uses standard default/first/even references, per-section `titlePg` and global `evenAndOddHeaders`. With the global even-page switch enabled, sections without EVEN explicitly reference their default pair too, preventing inheritance of a previous section's even-page content. Ignorable extensions preserve only the intent of an undeclared override; import first verifies that it is actually equal to the default pair. Ordinary Word regions without fixed heights still require explicit `RegionGeometry`. Inactive explicit variants are rejected under STRICT and reported as actual loss under ALLOW_LOSS. Invalid relationships, duplicate references and invalid geometry always fail. See [DOCX page regions (Chinese)](../docx-page-regions-api.md).

HTML/Markdown have no equivalent fixed-page selection. Region policies report loss and put content from all variants into ordinary flow. Explicit empty selectors also produce loss records. Strict policy fails before output.

Old `PageRegions(header, footer)` and `SectionTemplate(first, odd, even)` source entry points remain; changed record components require recompilation. The old `SectionTemplate.Builder.first` sets both first-page kinds, after which `firstEven` can specialize an even first page. `PageRegionTemplates.from` accepts only values without variants; use `sectionFrom` for variants to prevent silent selector loss. Arbitrary Word lossless round-trips or identical PDF pagination are not guaranteed.
