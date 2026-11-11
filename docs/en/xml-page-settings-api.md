# XML paper size, orientation, and margins

XML can declare static page geometry in the first element under the root. Java continues to supply defaults, font resources, and the output backend. Compilation uses the existing `PageSettings`, without introducing another layouter. See [ADR-0043 (Chinese)](../adr/0043-xml-page-settings.md).

## Minimal example

```xml
<document version="1" title="季度报告">
  <page size="A4" orientation="LANDSCAPE"
        margin="15mm" margin-top="20mm" margin-left="22mm"/>
  <heading level="1" size="18" line-height="28">季度报告</heading>
  <p size="12" line-height="20">纸张与页边距由模板声明。</p>
</document>
```

```java
var compiler = XmlTemplate.builder()
        .pageSettings(PageSettings.A4) // XML 未声明的项使用这里的默认值
        .build();
var document = compiler.compile(xmlSource).render(Map.of());
engine.export(document).backend(new PdfBackend()).to(outputPath);
```
`engine` must have explicit font resources registered in advance. XML `span role` still selects only registered, real font groups; it does not scan the system by font name or load files. For a complete runnable example, see the [template](../../examples/src/main/resources/templates/page-settings.xml) and [calling code](../../examples/src/main/java/com/mirakyux/export4j/examples/XmlPageSettingsExample.java).

## Attributes and override order

| Attribute | Meaning |
| --- | --- |
| `size` | Static `A4` or `LETTER`; names are case-sensitive |
| `width`, `height` | Custom dimensions, required together; cannot be combined with `size` |
| `orientation` | Static `PORTRAIT` or `LANDSCAPE`; reorders the short and long sides of the base dimensions |
| `margin` | Overrides all four margins |
| `margin-top`, `margin-right`, `margin-bottom`, `margin-left` | Override the corresponding margins, taking precedence over `margin` |

Starting from the Java compiler's `pageSettings`, the compiler selects paper size or paired custom dimensions, then applies orientation. It applies the uniform margin first, followed by individual margins. Unspecified properties keep their Java values. Orientation changes only width and height; it does not swap margins. Without an orientation, explicit dimensions retain their declared order.

Omitting `page` and using `<page/>` both retain all Java defaults. Without configured Java defaults, the defaults are A4 with 54 pt margins on every side. One compiler can compile templates with different paper sizes. Compiled templates, later compilations, and compilations after a failure do not modify each other's configuration.

For example, custom dimensions can be written as:

```xml
<page width="148mm" height="210mm" margin="12mm" margin-bottom="14mm"/>
```
## Lengths and validation

Only length attributes on `page` add absolute units: no suffix or `pt` means points; `mm`, `cm`, and `in` use `1 in = 72 pt = 25.4 mm`. Other XML attributes retain their existing point-based rules, such as `<p size="12">` and `<column width="72"/>`.

Lengths use nonnegative ASCII decimals, accepting `12`, `12.5`, `.5`, `1.`, and lowercase unit suffixes. Whitespace, signs, scientific notation, hexadecimal, NaN/Infinity, relative units, calculations, and data interpolation are rejected. Page width and height must be strictly positive; margins may be zero. Converted values must be finite, and margins must leave positive body width and height. This addition imposes no further page-model size cap; layout and backends still enforce their own limits, so arbitrary sizes are not guaranteed to export.

Unknown attributes produce `UNSUPPORTED_ATTRIBUTE`; conflicting dimensions, unpaired width/height, invalid placement, and nonempty content produce `INVALID_STRUCTURE`; invalid values produce `INVALID_VALUE`; `${...}` produces `INVALID_EXPRESSION`. Errors retain sanitized, one-based XML line/column positions and optional fragment provenance, without exposing original values, body content, or resource paths.

## Placement, budgets, and compatibility

`page` is valid only directly under the main XML root, must be its first element, and may occur at most once. Leading structural whitespace and comments are ignored. It must be empty; a self-closing element is recommended. Whitespace text, CDATA, and child elements inside it are rejected; comments do not count as content.

`page` cannot appear in either conditional branch, zero-iteration loop prototypes, cells, or referenced offline fragments. An `include` occupies the first main-source element position even when it expands to nothing, so it cannot make a later `page` valid. Unreferenced fragments retain existing lazy parsing rules, but their registered strings still count toward the total input budget.

The page node counts toward input-character, parsed/expanded-element, and depth budgets. It creates no semantic block, consumes no block budget, and does not change automatic IDs. Compilation and rendering retain synchronous cancellation/timeout and borrowed Reader contracts, without accessing files, networks, or fonts. Existing templates without `page` retain their behavior.

## Output verification and remaining limits

```powershell
.\gradlew.bat :examples:xmlPageSettings
build/verification/python-phase13/Scripts/python.exe scripts/verify_xml_page_settings.py
```
Independent checks cover PDF MediaBox, asymmetric body bounds of actual text/tables, repeated headers and source-row uniqueness, per-page SVG sizes and stacking, and standard OOXML `pgSz`/`pgMar`. All example and isolated-consumer pages are rendered with two engines. Native DOCX dimensions are integer twips (1/20 pt); readback is quantized to that precision and does not promise exact equality with original floating-point values or identical pagination to PDF.

Individual sections can override root page settings; see [XML sections (Chinese)](../xml-sections-api.md). [Fixed headers and footers (Chinese)](../xml-page-regions-api.md) and [scoped default roles/colors (Chinese)](../xml-text-defaults-api.md) have separate APIs. Full theme definitions, relative units, and CSS `@page` remain open. A3/A5 and other sizes can use explicit dimensions; no undefined paper names are added. Font files remain explicitly registered by the caller.
