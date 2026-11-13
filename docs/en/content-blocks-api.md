# Source content blocks and inherited options

`ContentOptions` declares font roles, color, paragraph geometry and parsing choices for each source block. Undeclared properties inherit independently from global defaults or an enclosing scope. `ContentImporter` compiles explicitly selected text, Markdown or HTML into the existing semantic model before layout.

This API belongs to the development version and is not included in published `0.1.0-rc.3`. See the [verification record (Chinese)](../verification/content-blocks.md) for the current acceptance status. [Mixed content and cross-format mathematics](rich-content-api.md) covers mixed Markdown/HTML and one compilation for PDF and native Word equations.

## Modules and entry points

| Entry point | Responsibility |
| --- | --- |
| `export4j-model`: `ContentOptions`, `DocumentTemplate.Source`, `SourceContentParser` | Authoring declarations, whole-field source binding and synchronous parsing contracts; no Markdown, HTML or formula implementation dependency |
| `export4j-adapter-web`: `ContentImporter` | Explicit source formats, existing offline import subsets and final semantic blocks |
| `export4j-adapter-template`: `XmlTemplate` | XML root defaults, `scope` and `content`, using an explicitly supplied parser during binding |
| Optional `export4j-math`: `TexMath.processor()` | The built-in bounded formula processor; applications need not implement formula recognition or outline rendering |

Align these modules with the same development BOM version. Formula-free inputs do not require `export4j-math`. When formulas are enabled, explicitly install `.formulaProcessor(TexMath.processor())`. Output still requires physical fonts and named roles in `DocumentEngine`; there is no system-font discovery or synthetic bold/italic.

## Java defaults and local overrides

```java
import com.mirakyux.export4j.adapter.web.ContentImporter;
import com.mirakyux.export4j.math.TexMath;
import com.mirakyux.export4j.model.ContentOptions;
import com.mirakyux.export4j.model.DocumentTheme;

var theme = DocumentTheme.builder()
        .fontRole("body").rgb(0x203040)
        .bodyFormat(p -> p.fontSize(12).lineHeight(28))
        .build();
var importer = ContentImporter.builder()
        .defaults(ContentOptions.fromTheme(theme))
        .defaults(ContentOptions.builder()
                .format(ContentOptions.Format.MARKDOWN)
                .formulas(true).tables(true).build())
        .formulaProcessor(TexMath.processor())
        .build();

var rich = importer.read("summary", "Result: $x^2$.\n\n| Value |\n|---|\n| 42 |")
        .options(ContentOptions.builder().rgb(0x224466).spaceAfter(8).build())
        .execute();
var literal = importer.read("source", "**Literal** <b>text</b> $x^2$")
        .options(ContentOptions.builder().format(ContentOptions.Format.TEXT)
                .formulas(false).tables(false).fontRole("").rgb(0).build())
        .execute();
```

Both results expose the existing `Document` through `.document()`. `read(String)` always receives source text, never a path. Requests are independent: local options do not alter the importer or sibling defaults. Defaults and compiled sources are snapshots; later theme or builder changes do not alter existing content.

An empty font role explicitly selects the output caller's default font group. RGB `0`, zero spacing, `false` and zero rotation are also explicit declarations. Defaults are not used as omission sentinels. `inheritRgb()`, `inheritFontRole()` and the other `inheritXxx()` methods remove a declaration from a local options builder; its next `resolve(parent)` uses that parent's property.

`base.overlay(overrides)` combines declarations without resolving inheritance; only properties explicitly declared in `overrides` replace `base`. The unified entry point can therefore combine global and request options while preserving theme defaults for headings, cells and lexical scopes.

## Available properties

| Property | Java declaration | XML attribute |
| --- | --- | --- |
| Font role and text color | `fontRole`, `rgb` | `role`, `color="#224466"` |
| Point size and fixed line height | `fontSize`, `lineHeight` | `size`, `line-height` |
| Alignment and indents | `alignment`, `firstLineIndent`, `subsequentLineIndent` | `align`, `first-indent`, `subsequent-indent` |
| Paragraph spacing | `spaceBefore`, `spaceAfter` | `space-before`, `space-after` |
| Keep together, keep with next and break before | `keepTogether`, `keepWithNext`, `pageBreakBefore` | `keep-together`, `keep-with-next`, `page-break-before` |
| Minimum lines around a page break | `minLinesBeforeBreak`, `minLinesAfterBreak` | `min-lines-before`, `min-lines-after` |
| Line breaking and rotation | `lineBreakPolicy`, `rotation` | `line-break`, `rotation` |
| Complete cell text format | `cellFormat` | `cell-size`, `cell-line-height`, `cell-align`, `cell-line-break` |
| Complete table format | `tableFormat` | `table-padding`, `table-draw-borders`, `table-allow-row-split`, `table-space-before`, `table-space-after` |
| Source format | `format` | `content-format` |
| Formula and table parsing | `formulas`, `tables` | `formulas`, `tables` |
| Raw HTML inside Markdown | `rawHtml` | `raw-html` |

Java geometry uses points; rotation uses degrees. `ParagraphAlignment` is `LEFT`, `CENTER`, `RIGHT` or `JUSTIFY`. `LineBreakPolicy` is `STANDARD` or the bounded Chinese punctuation policy `CJK_STRICT`. Changing only font size preserves inherited fixed line height; content that cannot fit still fails through the existing layout contract.

`paragraphFormat(...)` explicitly declares every paragraph property; `pagination(...)` declares the complete pagination group. `inheritParagraphFormat()` and `inheritPagination()` restore their respective groups. `cellFormat` and `tableFormat` replace complete values. Backgrounds, borders and layout containers remain part of the existing [table](table-api.md) and [component](components-api.md) APIs, rather than arbitrary semantic paragraph properties.

## Formats and parsing switches

Defaults are `TEXT`, formulas disabled, tables enabled and Markdown raw HTML interpretation disabled. The table option applies only when the selected grammar supports tables; it does not infer tables from plain text.

| Option | Behavior |
| --- | --- |
| `Format.INHERIT / TEXT / MARKDOWN / HTML` | Inherit or select one grammar explicitly. Source appearance never selects a grammar; `Resolved` cannot retain `INHERIT` |
| `Toggle.INHERIT / ENABLED / DISABLED` | Formulas, tables and raw HTML inherit or override independently; Java also has boolean overloads |
| Formulas disabled | Preserve recognized delimited source fragments, including backslashes and underscores that Markdown would otherwise interpret |
| Tables disabled | Preserve original Markdown/HTML table source fragments, including escapes and line endings; do not render formulas inside them |
| Formulas enabled | Require an explicit `FormulaProcessor`, even for formula-free input; missing assembly does not silently fall back to text |
| `rawHtml(true)` | In MARKDOWN, enable the existing offline HTML subset, including table spans; unsupported semantics, attributes and active content still fail strictly |

XML format values use the uppercase enum names. Switches accept `true`/`false`, `ENABLED`/`DISABLED` or `INHERIT`; omitted attributes inherit.

Formula recognition uses explicit `$...$` and standalone-line `$$...$$` delimiters, not OCR or mathematical inference. Code, links, image destinations, reference definitions and HTML attributes are protected; raw HTML is also protected unless `rawHtml` is enabled. With that explicit option, supported HTML body/table text can contain formulas. Formula islands are isolated before HTML parsing, so their `<`/`>` characters cannot become tags. Each formula receives its final resolved point size and color, including cell overrides; body roles do not alter the processor's math fonts.

Code blocks map to literal text retaining their parsed spaces and line breaks. Inline code follows CommonMark's newline and boundary-space rules; formulas inside it stay literal. HTML `pre`/`code` retain their whitespace. This source-block entry point uses the inherited font role without requiring a separate `code` role. Language metadata and syntax highlighting are not represented. Headings and ordered/unordered items retain mixed images and vectors like paragraphs and cells. A first row of ordinary `td` cells is not inferred to be a header. Header-only tables retain their source rows and header appearance with repetition disabled, without an invented empty row.

`ContentImporter` uses `ProcessingPolicy.STRICT`. Unsupported semantics, lossy HTML repair, unavailable resources and invalid input fail explicitly. Use the original [Markdown importer](markdown-import-api.md) or [HTML importer](html-import-api.md) when explicit loss policies are required; this source-block API does not expand their supported subsets.

## Java templates and whole-field sources

```java
import com.mirakyux.export4j.model.DocumentTemplate;
import java.util.Map;

var sourceOptions = ContentOptions.builder()
        .format(ContentOptions.Format.MARKDOWN).formulas(true).build()
        .resolve(ContentOptions.fromTheme(theme));
var template = DocumentTemplate.builder()
        .node(DocumentTemplate.Source.value("detail", "detail", sourceOptions, importer))
        .node(importer.source("footer", "Literal ${notInterpolated}",
                ContentOptions.builder().format(ContentOptions.Format.TEXT).formulas(false).build()))
        .build();
var document = template.render(Map.of("detail", "Result: $x^2$."));
```

`Source.value` selects one complete string by dot path. Missing or non-string values fail; rich syntax is not assembled from mixed `${...}` interpolation. Literal source strings and selected field values are never interpolated again. The same applies to final block text, links and formula reading text.

## XML root, scope and source blocks

```java
import com.mirakyux.export4j.adapter.template.XmlTemplate;

var xml = XmlTemplate.builder()
        .theme(theme)
        .contentOptions(o -> o.formulas(true))
        .contentParser(importer)
        .build();
var template = xml.compile("""
        <document version="1" content-format="MARKDOWN" role="body" color="#203040"
                  size="12" line-height="28">
          <content id="intro" source="intro"/>
          <scope content-format="HTML" formulas="false" color="#224466">
            <content id="html"><![CDATA[<p>Literal ${name} &amp; HTML text.</p>]]></content>
          </scope>
          <content id="plain" content-format="TEXT" formulas="false" tables="false"><![CDATA[**Literal** $x^2$]]></content>
          <p id="legacy">${name}</p>
        </document>
        """);
var document = template.render(Map.of("intro", "Result: $x^2$.", "name", "Reader"));
```

Precedence is theme/compiler defaults → `document` → outer-to-inner `scope` → explicit block attributes. Scopes are lexical and do not survive as layout containers. Existing complete named inline styles and local declarations retain their contracts. Native paragraphs, headings, lists, captions and cross-references can inherit appearance and paragraph defaults; parsing switches apply to `content` and do not automatically reinterpret legacy `p`/`span` interpolation as rich syntax.

`<content source="field.path"/>` must be empty. Static source accepts text or CDATA, not mixed XML children such as `span`. `${name}` inside CDATA is passed literally to the selected parser. Without a supplied `contentParser`, XML supports plain source text only and rejects Markdown, HTML or enabled formulas.

`content` and `scope` are body authoring nodes, not direct header/footer children. Page regions retain their existing paragraph and table declarations. For rich page-region content, compile supported semantic blocks in Java before assembling the region.

## Source content in body cells

Place this fragment inside `document`; it shares parser assembly, lexical defaults and loop bindings:

```xml
<rows id="details" items="items" as="item" role="body" cell-size="11">
  <columns><column weight="1"/></columns>
  <row>
    <cell color="#000000">
      <scope space-after="0">
        <content id="detail" source="item.text" content-format="MARKDOWN"/>
      </scope>
    </cell>
  </row>
</rows>
```

Body cells may contain source nodes, scopes, conditions and loops, expanding into existing block cells while retaining spans and distinct loop namespaces. **Block source content in repeated headers is unsupported.** A dynamic source cell also cannot combine `style-if` rules. Final paragraphs/nested tables retain existing [block-cell limits (Chinese)](../block-cells-api.md), including depth, pagination and placement constraints.

`Caption` and `CrossReference` now accept an explicit `TextAppearance`; old constructors keep the default font role and black. Final numbering preserves that appearance. These types do not become source-string parsing entry points.

## Resources, budgets and ownership

Register images/vectors by exact source key with `.image(...)`/`.vector(...)`; callers provide image bytes and point dimensions. `.fontFamily(family, regular, bold, italic, boldItalic)` maps HTML families to explicit roles, which must also exist in the output engine. Hyperlinks use existing passive-link validation and do not authorize file or network fetching.

Each template render opens one synchronous `SourceContentParser.Session` per parser identity. Input, AST/work, table, formula and vector budgets accumulate across chunks, loops, sections and dynamic cells. `ImportLimits`, `maxFormulas`, `maxFormulaUtf16` and `maxVectorSegments` do not reset at nested sources. Each call must also honor the template's `Remaining` allowance. Input/work checks guard parser boundaries; AST nodes are charged as actually traversed, including work consumed by failed traversals, rather than estimated from text length. Mixed-content diagnostics map back to original Markdown UTF-16 ranges. Finite budgets and cooperative checkpoints are not a hard memory sandbox.

Sessions close on success, failure and cancellation, preserving primary failures. Final documents contain no `Source` node or deferred parsing. Existing semantic blocks, complete inline styles and `Document.Builder.add(Block)` inputs are neither reparsed nor overwritten by guessing default values. See [ADR-0115 (Chinese)](../adr/0115-source-content-options.md).
