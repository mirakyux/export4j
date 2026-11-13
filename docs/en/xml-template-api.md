# Declarative XML templates

Template source is directly editable. To save the main source, fragments, images, and fonts together, use [XmlTemplateBundle](xml-template-bundle-api.md), an ordinary ZIP. Reopening still compiles to the same `DocumentTemplate`, without changing XML syntax or binding behavior.

Existing Java semantic documents can use [XmlTemplateWriter (Chinese)](../xml-model-saving-api.md) to save the supported subset into the same XML/ZIP and reopen it for editing. Unmappable content uses shared actions and loss policies.

Root-level [navigation (Chinese)](../portable-navigation-api.md) saves table-of-contents and bookmark declarations after page/page regions and before notes/body/section. Retain it with `renderSections` or `renderComplete`; the default engine automatically resolves it from edited headings. Narrower legacy binding APIs reject navigation loss.

The main source supports fixed header/footer, [first/odd/even variants](page-variants-api.md), and separate themes after the optional page and before body content. `DocumentTemplate.renderWithPageRegions` and `DocumentEngine.export(PageDocument)` retain page regions and final page numbers; legacy render explicitly rejects page regions. Images/semantic tables and final fields remain. Sections use renderSections, including per-section paper/page numbering, page-region inheritance/overrides, and clearing. See the [page-region guide (Chinese)](../xml-page-regions-api.md), [section guide (Chinese)](../xml-sections-api.md), and [fixed page overlays (Chinese)](../page-overlays-api.md) for syntax, limits, and complete calls.

Body/lists, six heading levels, and cells can each have default font roles and colors. Local `role`/`color` override by presence; links and the four field types inherit their enclosing scope. Use `.bodyText(...)`/`.headingText(...)`/`.cellText(...)`; see the [text-default guide (Chinese)](../xml-text-defaults-api.md) for full rules. Font resources remain explicitly registered by Java. Templates do not load system or network fonts.

The first optional `<page>` in the main source can declare A4/LETTER, custom dimensions, orientation, and four margins. Page lengths accept pt/mm/cm/in; unspecified properties retain Java `pageSettings`. See the [paper and margin guide](xml-page-settings-api.md) for placement, override order, and DOCX twip limits.

`XmlTemplate.Builder.theme(DocumentTheme)` configures body text, six heading levels, cell/table formatting, and static named styles. See the [XML theme guide (Chinese)](../xml-themes-api.md) for `p`/`span` `style` rules and explicit overrides, verified in [phase 31 (Chinese)](../verification/phase-31-xml-themes.md).

Register repeated report blocks explicitly with `.fragment(name, source)`, then reuse them through static `<include ref="panel" prefix="first"/>`. Up to 16 levels of noncyclic fragment references are supported, combining parent/child prefixes. See the [offline-fragment guide (Chinese)](../xml-includes-api.md) for namespaces, provenance, and budgets.

Version 1 templates place report structure in XML, while Java supplies data, resources, and output. The parser belongs to `export4j-adapter-template`, rooted at `com.mirakyux.export4j.adapter.template`. It compiles into existing DocumentTemplate without separately deciding wrapping or pagination. See the [template file](../../examples/src/main/resources/templates/report.xml), [calling code](../../examples/src/main/java/com/mirakyux/export4j/examples/XmlTemplateExample.java), and [verification (Chinese)](../verification/phase-21-xml-templates.md) for a complete example.

## Minimal call

~~~java
var template = XmlTemplate.builder().build().compile("""
        <document version="1" title="${title}">
          <heading level="1">${title}</heading>
          <each items="items" as="item">
            <p>${item.name}：${item.result}</p>
          </each>
        </document>
        """);
var document = template.render(Map.of("title", "巡检报告", "items", List.of(
        Map.of("name", "东门仓", "result", "已复核"))));
engine.export(document).backend(new PdfBackend()).to(outputPath);
~~~
The example assumes explicit Chinese fonts on engine and a supplied outputPath. For file loading, the caller opens a Reader and passes it to `compile(reader)`. The compiler does not close the borrowed Reader and has no file-path or URL-loading API. Prepare dates, amounts, and localized formatting before supplying data.

## Structural syntax

All elements have no namespace. The root is `<document version="1">`, with optional `title`, `author`, and `literal-text`. Structural whitespace and comments are permitted. Whitespace inside body text, spans, and cells is retained exactly, so avoid adding indentation/newlines merely to prettify XML body content.

| Element | Content and attributes |
| --- | --- |
| `page` | Optional empty first element under the main root only; static paper/dimensions/orientation/margins, without a body block; see the page guide |
| `p` | Plain text, span, image, link, four page/number field types, and body note-marker; ordinary paragraphs may naturally span pages |
| `columns` | Continuous body columns: count 1–3, pt gap, balanced, optional weights; paragraphs/tables and dynamic children, including intermediate full-width figures/tables; see the [column guide](column-flow-api.md) |
| `grid`/`item` | Body grid with 1–12 tracks; explicit row/start-track/span and pt row/column gaps, with atomic whole-row pagination; see the [grid guide](grid-layout-api.md) |
| `heading` | Required `level="1"`–`"6"`; text, span, text link; no images/fields |
| `bullet` | Unordered lists with 1–9 levels; optional level (default 1), marker (default `•`); text, span, text link |
| `numbered` | Decimal lists with 1–9 levels; required static list-id, optional level (default 1), restart; text, span, text link |
| `caption` | Required nonempty kind; raw text only; retains paragraph formatting and numbers independently by kind during layout |
| `cross-reference` | Separate block; required kind="PAGE"/"NUMBER" and target; body is a plain-text prefix; layout resolves final page/number and link |
| `notes` | At most once after main-root page settings/page regions and before body or sections; contains only note; see notes and captions |
| `page-break` | Empty element; explicit page break |
| `if` | Required `test="flag"`; optional `operator`/`value`, defaulting to boolean TRUE; exactly one then first, followed by optional else; branches may nest if |
| `each` | Required `items="items"`, `as="item"`; contains blocks and may nest |
| `list` | Dynamic list container; required `items`, `as`; exactly one `bullet` or `numbered` item template expands per item; empty arrays produce no items |
| `include` | Required static ref/prefix; selects registered fragments in valid main-template/fragment block lists; cycles and inline/cell placement rejected |
| `table` | columns, zero or more header elements, and one or more row elements, in that order |
| `rows` | Like table, but with exactly one template row; required items/as; expansion to an empty collection fails |

`if` reads boolean, string, or numeric values according to its operator. each/list/rows values must be List. `list` and `each` share lexical variables, ID suffixes, and budgets; an empty `rows` array still fails explicitly. Map/List are structural and cannot be directly inserted into text. Data accepts only nonnull strings, booleans, standard finite numbers, Map, and List. Caller-defined containers and Readers are trusted code outside script-sandbox protection. Large numbers must be exact BigInteger/BigDecimal classes; subclasses are rejected.

`${customer.name}` is the only interpolation syntax. Paths use ASCII identifiers and dots, with at most 256 UTF-16 units. Condition/loop attributes contain bare paths without `${}`. Every branch is syntax-checked at compilation, but only selected branches perform data lookups. Data containing `<p>`, `${x}`, or similar is inserted as plain text rather than reparsed as XML or expressions.

With explicit root `literal-text="true"`, title/author, body text, alternative text, and external links remain literal: `${...}` and unclosed `${` do not interpolate. Default false retains existing behavior. if/each/rows control paths still read supplied data. Static field targets must still meet ID syntax; resource names/style attributes remain statically validated. This mode does not disable security, budgets, or type checks. The writer enables it automatically when saving existing models; dynamic templates normally use the default mode.

p, heading, bullet, numbered, caption, cross-reference, table, rows, and page-break accept optional id. Explicit IDs match `[A-Za-z_][A-Za-z_0-9-]{0,127}`, cannot interpolate or contain `@`, and must be unique across both branches and loop prototypes. Required note IDs also participate in collision avoidance. Omitted IDs become `xml-N`, avoiding all explicit IDs; loop rendering appends indices such as `@0`.

## Comparison conditions and multiple branches

`if` `operator` defaults to `TRUE`, retaining legacy boolean behavior. Supported operators are `TRUE`/`FALSE`, `EQ`/`NE`, `GT`/`GE`/`LT`/`LE`, and `CONTAINS`. Boolean operations require boolean data and an empty comparison value; ordering requires numbers; containment requires strings. Equality interprets literal `value` by actual data type: exact string comparison, boolean `true`/`false` only, and exact decimal numeric comparison. `value` uses no JSON string quotes and executes no interpolation or code. It is limited to 256 UTF-16 units; numeric exponents have at most four digits.

The following maps `status` values 1, 2, and 3 to three branches, with other values entering other:

```xml
<if test="status" operator="EQ" value="1">
  <then><p>分支 1</p></then>
  <else>
    <if test="status" operator="EQ" value="2">
      <then><p>分支 2</p></then>
      <else>
        <if test="status" operator="EQ" value="3">
          <then><p>分支 3</p></then>
          <else><p>other</p></else>
        </if>
      </else>
    </if>
  </else>
</if>
```
Conditions execute in declaration order; after the first match, later branch data paths are not read. The final `else` is the fallback. Branches can nest further conditions. Missing fields and incompatible data types still return `MISSING_VALUE`/`TYPE_MISMATCH` respectively, rather than silently becoming other. Every branch's structure, static IDs, and expressions are always checked at compilation. Condition chains consume existing XML/binding-depth and work budgets without raising defaults.

In Java, use `DocumentTemplate.When(path, TemplateCondition.Operator.EQ, "1", yes, no)` or `Nodes`/`Builder.when(path, operator, value, yes, no)`. The legacy three-argument `When` and `when(path, yes, no)` retain boolean TRUE defaults. Binding produces ordinary semantic documents; backends do not execute conditions.

## Styles, tables, and images

p, heading, bullet, and numbered support `size`, `line-height`, `align`, `keep-together`, `space-before`, and `space-after`. Lengths are points; align is LEFT/CENTER/RIGHT/JUSTIFY; booleans accept true/false only. bodyFormat supplies paragraph/list defaults, while headings use existing Heading defaults. Explicit size without line-height sets line height to 1.5 times font size; otherwise, the corresponding default line height remains.

Paragraphs/headings/lists also support `first-indent`, `subsequent-indent` (nonnegative pt), `line-break` (STANDARD/CJK_STRICT), `keep-with-next`, `page-break-before`, and `min-lines-before`/`min-lines-after` (positive integers). Unspecified properties retain the current complete format. These map to existing ParagraphFormat/PaginationPolicy without expanding layout capabilities.

`p`, `heading`, `bullet`, `numbered`, `caption`, `table`, `rows`, and inline `image` accept `rotation` (clockwise degrees, `-180` to `180`). Painting uniformly fits rotated content into its existing layout box without changing wrapping or pagination. Each fragment of a multipage table rotates separately. PDF/SVG/PNG use the transformed display list; other formats apply their target adapter's loss policy and do not guarantee angle preservation.

bullet/numbered `marker-role`, `marker-size`, `marker-color`, `marker-shift`, `marker-underline`, and `marker-strike` individually override the marker's full style. Unspecified properties inherit the enclosing block's default text appearance; body spans do not affect markers. marker-shift is in pt, positive downward. Custom bullets must be nonblank, contain no control characters, and have at most 8 Unicode code points.

`<span role="bold" color="#224488" size="12" underline="true" strike="false">文字</span>` contains text only. All attributes are optional. Defaults inherit block font size, use black, an empty role, and no decoration. role selects only real roles in the caller's font group, without loading or synthesizing fonts.

span and the four field types also support `baseline-shift` in pt, positive downward. image supports it too, defaulting to 0. The fixed line box must accommodate font/image size and displacement; it does not shrink automatically.

~~~xml
<list items="items" as="item">
  <bullet>${item.name}：${item.result}</bullet>
</list>

<rows items="records" as="record" row-split="true">
  <columns><column width="72"/><column weight="1"/></columns>
  <header><cell>编号</cell><cell>说明</cell></header>
  <row><cell>${record.code}</cell><cell>${record.notes}</cell></row>
</rows>
~~~
column specifies exactly one of width (positive finite fixed pt), weight (positive finite ratio), or paired min-width/max-width (bounded Auto limits in pt). These forms are mutually exclusive and use existing ColumnWidth.Auto validation. Auto does not relax layout restrictions for tables with block cells. Each header/row must completely cover the grid after column-span/row-span resolution; real cells are declared only in their anchor row. cell supports size, line-height, align, line-break, column-span, row-span, plus text, span, image, link, and four inline field types. table/rows may explicitly set padding-top/right/bottom/left and space-before/after (nonnegative pt); unspecified values inherit the theme. Ordinary inline-content table/rows accept optional `row-split="true"`, default false. This uses existing semantic-table pagination with repeated headers and complete text-line continuation for explicit row splitting. See the relevant guides for vertical/recursive boundaries.

The caller first decodes images within limits into ImageResource, then registers them through `.image("logo", resource)`. Templates contain only `<image ref="logo" width="12" height="12" alt="标记"/>`. ref cannot interpolate, and names use ASCII identifiers. width/height are positive points; alt may interpolate. Images are allowed only in p/cell. The fixed line box must fit them or layout fails explicitly. The compiler does not decode images, scan fonts, or access files/networks.

## Conditional cell styles

Inline-content `cell` can declare empty `style-if` nodes. Conditions compile into `DocumentTemplate.CellRule` and `CellStyleRule`, execute in the current lexical scope each time the cell binds, and become ordinary `CellFormat`/inline appearance. Rules do not pass to layout or backends, and static-model exports carry no executable conditions.

```xml
<rows items="records" as="record">
  <columns><column weight="1"/><column weight="1"/></columns>
  <header><cell>名称</cell><cell>分数</cell></header>
  <row>
    <cell>${record.name}</cell>
    <cell>${record.score}<style-if test="record.score" operator="GE" value="90" color="#C0392B" role="bold"/></cell>
  </row>
</rows>
```
`test` is a required dotted path without `${}`. `operator` defaults to `TRUE` and supports `TRUE`/`FALSE` (boolean data, no value), `EQ`/`NE` (actual string/boolean/numeric type), `GT`/`GE`/`LT`/`LE` (numbers), and `CONTAINS` (strings). Numeric comparisons use exact decimals without floating-point conversion. Comparison values have at most 256 UTF-16 units; numeric exponents have at most four digits. Missing values and incompatible types produce binding diagnostics rather than unmatched conditions.

At least one appearance override is required: `size`, `line-height`, `align`, `line-break`, `role`, `color`, `underline`, or `strike`. Unspecified properties retain original formatting. Explicit size without line-height sets line height to 1.5 times size. Matching rules override in XML order while retaining links, inline images, fields, and spans. Callers must still provide real fonts for roles. Each cell allows at most 64 rules; every execution consumes template-work budget. Unselected branches also have rule syntax checked. Block cells do not allow conditional styles; backgrounds, borders, and arbitrary expressions are unsupported. Java can associate exact prototype cells using `DocumentTemplate.Builder.cellRules`; `toBuilder` preserves associations. Distinct prototype cells with equal values do not affect each other.

## Multiparagraph cells and atomic inner tables

`cell` accepts directly ordered `p`/static `table`/`p`, producing existing `TableCell.blocks`. See [phase 28 (Chinese)](../verification/phase-28-xml-block-cells.md) for the initial subset; later work extends it to [recursive tables (Chinese)](../block-cells-api.md) up to 16 levels including the root table. Full readable examples, defaults, and copy provenance are in the [XML block-cell guide (Chinese)](../xml-block-cells-api.md).

~~~xml
<table id="report">
  <columns><column weight="1"/></columns>
  <row>
    <cell size="10" line-height="16">
      <p id="summary">${summary}</p>
      <table id="details">
        <columns><column weight="1"/></columns>
        <row><cell>${detail}</cell></row>
      </table>
      <p id="conclusion" size="11" line-height="18">明细见第 <page-reference target="details" width="24"/> 页。</p>
    </cell>
  </row>
</table>
~~~
The example belongs inside `document` or a valid enclosing control node. Direct cell `p` inherits cell font size, line height, alignment, and line-breaking policy, with paragraph spacing defaulting to 0. Explicit `p` attributes win. Inner-table cells use the compiler's `cellFormat` again rather than inheriting outer-cell local text settings.

Any direct `p`/`table` enters block mode, allowing only those nodes and structural whitespace. Inline text, span, images, fields, and link must be inside `p` rather than mixed alongside blocks. Cells reject if/each/rows, headings, lists, and page breaks. Existing enclosing each/rows can deep-copy the full subtree and relocate static field targets in the same lexical copy scope.

By default, block cells and their parent connected row groups move together to the next page/region. Explicit `row-split="true"` supports recursive paragraph/inner-table and vertical-owner continuation, with independent switches at each level. Block cells in repeated parent headers remain rejected; ordinary inline-content parent headers remain supported. Every conditional branch validates geometry, depth, and content alternatives. Semantic tables have at most 16 levels; see the [block-cell API (Chinese)](../block-cells-api.md).

## Final fields, numbering, and external links

The four page/number field types are empty elements inside p/cell. Each occupies one U+FFFC source position and requires an explicitly reserved positive finite width (points, at most Float.MAX_VALUE). Fields also support span role, color, size, underline, and strike, defaulting to block font size. Values, arbitrary instructions, automatic shrinking, and cached text are unsupported. Body note markers use the separate note-marker below.

| Element | Final value and additional attributes |
| --- | --- |
| `page-number` | Display page number of this rendering occurrence; no target |
| `page-count` | Final physical page count, including preceding contents pages; no target |
| `page-reference` | Required target; resolves the target block's display page number; data expressions supported |
| `number-reference` | Required target; resolves the target NumberedItem/Caption numbering label; data expressions supported |

Static target uses explicit-ID syntax. Dynamic targets such as `${record.target}` have expressions checked at compilation and bind to complete final IDs without automatically adding include prefixes or each suffixes. Compilation checks syntax, width, and styles even in unselected if branches. Target existence and numbering are checked during final document layout. A static field inside a loop that references a declaration in the same lexical copy scope relocates to the current copy, for example `loop` to `loop@0`. Inner loops can reference the current outer copy; static targets outside loops retain their original IDs. Conditional branches participate in declaration collection, but unselected targets do not exist and final layout fails explicitly. References outside loops to loop-prototype IDs do not guess an iteration. Values exceeding reserved boxes fail through existing layout diagnostics.

`numbered` maps to existing NumberedItem. Required list-id uses static-ID syntax; items sharing list-id continue numbering in document order. level is a static integer 1–9, default 1. restart defaults to 0 (continue); positive values reset the current level and clear every deeper level. Only nonnegative int is accepted. Expanded numbering must have complete parents. Missing levels and increment overflow fail during existing semantic layout/DOCX export; compile/render does not predict dynamic parent counts. See the [multilevel numbering guide (Chinese)](../xml-numbering-api.md) for full dotted references, loop/fragment list identity, and nine-level limits. numbered/heading/bullet accept text links but reject fields, images, and nested blocks.

~~~xml
<numbered id="check" list-id="checks" restart="3">复核记录</numbered>
<p>本页 <page-number width="24"/>／<page-count width="24"/>，参见第 <page-reference target="check" width="24"/> 页、第 <number-reference target="check" width="24"/> 项。</p>
<p><link href="https://example.com/report?a=1&amp;b=2">${label}<span role="bold" underline="true">详情</span><image ref="logo" width="12" height="12" alt="详情入口"/></link></p>
~~~
`link` is valid in p/cell/heading/bullet/numbered, with required href and plain text/span content. Links in p/cell additionally accept registered images. Linked images still use the resource allowlist; href never becomes an image source. href can be static or a pure data expression such as `${record.url}`, binding independently in each copy's lexical scope. Final destinations reuse ExternalDestination: HTTP, HTTPS, and mailto only, with length, control-character, host, and user-info checks. Invalid dynamic destinations fail with sanitized `BindingException.Code.INVALID_VALUE`, without a cause exposing the original address. Missing values, types, budgets, and cancellation retain separate errors. There is no implicit URL encoding or network access. Write XML `&` as `&amp;`. Nested links, fields in links, empty links, and click scripts are unsupported.

## Standalone cross-reference blocks

```xml
<cross-reference id="page-ref" kind="PAGE" target="figure">参见第 </cross-reference>
<caption id="figure" kind="Figure">检查结果</caption>
<cross-reference id="number-ref" kind="NUMBER" target="figure">参见 </cross-reference>
```
`cross-reference` maps to existing `CrossReference` and remains separate from fixed-width inline fields. Its body is prefix text, allowing empty prefixes and data expressions. kind is static PAGE/NUMBER and cannot be an expression. It supports complete paragraph formatting with current bodyFormat defaults, but no span, role, color, images, fields, or width. Only body-block positions are valid, including sections, if/each, and include. Page regions, overlays, and table cells remain rejected.

Static target follows the same ID syntax. Fragment-local targets receive include prefixes; each targets relocate to the current copy. Expression-containing targets bind to complete final IDs without further fragment/loop prefixes and may explicitly point to another copy. literal-text disables interpolation in prefixes and targets. IDs, targets, prefixes, and expansion share budgets and cancellation.

Compilation and binding retain reference declarations. Semantic editing/unified conversion and final layout validate complete target relationships. PAGE uses the target section's display page number; NUMBER points to the complete NumberedItem/Caption label. Final pagination generates text and internal links without saving cached values back to XML. Missing or incompatible targets fail explicitly, without warn/ignore bypasses. Longer prefixes reuse existing bounded multiline reservation; saving does not expand layout limits. See [phase 81 (Chinese)](../verification/phase-81-xml-cross-reference-saving.md) for saving, reopening, and retargeting.

## Notes and captions

```xml
<document version="1">
  <notes>
    <note id="n1" paragraph="summary" kind="FOOTNOTE">${noteText}</note>
    <note id="n2" paragraph="figure" kind="ENDNOTE">附注正文</note>
  </notes>
  <p id="summary">正文<note-marker target="n1" width="12" size="8" baseline-shift="-3"/>后续文字</p>
  <caption id="figure" kind="Figure">原始题注文字</caption>
  <p>参见 <number-reference target="figure" width="64"/>。</p>
</document>
```
notes may appear only under the main root, after optional page/all page regions and before body or section. It cannot occur in fragments, sections, if/each, or cells. note requires static id, paragraph, and FOOTNOTE/ENDNOTE kind. Content is text only and binds in root data scope. Declaration order determines numbering. Loop-copy notes, dynamic IDs, and per-iteration notes are not inferred. paragraph must target an actually expanded top-level body paragraph, list item, or Caption.

Use `var bound = template.renderComplete(data)` to obtain both the sectioned document and notes, then export with `DocumentConversion.from(bound.document(), bound.notes())` or explicit engine notes APIs. The three legacy render APIs reject nonempty note declarations. note-marker is allowed only in ordinary body p and reuses inline-field width, style, and UTF-16 contracts. It must uniquely match a Note anchored to the same paragraph. Without an explicit marker, legacy paragraph-end markers remain. Note fields are still disallowed in tables/page regions/headings/lists. Invalid final relationships throw IllegalArgumentException without loss-policy bypass or XML-position mapping.

Caption contains only raw text and required nonempty kind, uses complete ParagraphFormat, and rejects span, role, color, images, and fields. kind and body can interpolate; literal-text keeps them literal. Existing layout resolves per-kind counts and number references. include/each ID copying and same-copy number references are supported without concatenating generated numbers back into plain text. Note accesses, IDs/anchors, and body text count toward shared binding budgets. All new syntax retains secure parsing, cancellation, and source budgets. See [phase 80 (Chinese)](../verification/phase-80-xml-semantic-saving.md).

Compilation and data binding save passive links only, without visiting destinations. Existing model/layout handles fonts, UTF-16 source positions, final link rectangles, and field values. Field replacement does not change line width, wrapping, or pagination. Page numbers in repeated headers retain semantic-table contracts. DOCX repeated-header PAGE cannot preserve per-occurrence semantics and must still reject explicitly. Static SVG/PNG backends still reject nonempty interaction data rather than silently dropping links.

## Budgets and errors

`XmlTemplate.Limits(maxInputCharacters, maxElements, maxDepth, maxTextCodePoints)` defaults to 1,000,000, 20,000, 32, and 1,000,000 respectively. Input counts total UTF-16 units across the main source and every registered source. Cumulative parsed elements/text and cumulative expanded elements/text each use the same respective quota; repeated references still incur expansion cost. Elements include root and include work. Text includes structural whitespace, excluding attributes; total input length bounds attributes. Root depth is 1; final depth is configurable up to 64, with a separate input-tree hard cap of 65 levels. Reader reads at most the remaining allowance after registered sources plus 1 unit. With no fragments, the original length contract remains.

`.bindingLimits(DocumentTemplate.Limits)` independently controls snapshot/expansion accesses, blocks, rows, code points, and depth. Text accumulation includes Map keys, data strings, and all generated text, including static link addresses, image alt, and field target. Template literals and replacement values are charged before appending. Each expanded field/text/image incurs inline-access work; link wrappers do not create another binder. Images and fields also retain separate existing model caps of 1,024 per paragraph. Loops, cells, paths, and text scans have execution checkpoints; use `ExecutionContext.execute(options, ...)` for cancellation/timeouts. Compilation and rendering themselves write no files.

| Stage | Diagnostics |
| --- | --- |
| XML compilation | TemplateException.code/line/column and optional fragmentName; positions are one-based; message contains no registered names, source text, values, or resource paths |
| Data binding | DocumentTemplate.BindingException.code/path; paths may be safely truncated locators, without XML line/column mapping |
| Layout and export | Existing layout, font, resource, backend, and execution diagnostics |

Unknown elements/attributes, out-of-order structures, duplicate IDs, invalid dimensions, missing resources, and invalid expressions fail immediately. DTDs, custom/external entities, namespaces and declarations, processing instructions, and scripts are unconditionally rejected. Predefined/numeric XML entities are allowed. include loads no paths or URLs and accepts only explicitly registered strings. There is no silent degradation or partial-result mode.

Expanded content must still pass semantic-model validation. For example, image alt exceeding 4,096 UTF-16 units or containing control characters after interpolation produces existing InlineImage IllegalArgumentException. This is not an expression error and has no XML-position mapping.

## Support boundaries

cell can declare semantic rectangular merges through fixed positive-integer column-span/row-span. Static tables validate the two-dimensional grid; empty header/row elements are valid only when fully covered by previous rows. Dynamic single-row prototypes support column spans; the initial subset rejects vertical spans to avoid loop instances occupying other data rows. See [column spans](column-spans-api.md) and [row spans](row-spans-api.md). This is an independently defined declarative subset. Markdown has a separate API. HTML/CSS, dynamic note declarations, and component grids are outside the XML subset. Arbitrary lossless XML/Word input, identical PDF/Word pagination, streaming layout, and accessibility compliance are not promised. DOCX/SVG output retains its own support matrix.

Run `:examples:xmlTemplates` to generate existing PDF, DOCX, and SVG outputs; independent checks use `scripts/verify_xml_templates.py`. New fields/external links require separate verification under the backend boundaries above and cannot inherit success from old examples. This module and independently developed samples use Apache-2.0, relying only on JDK XML and the existing model without new third-party runtime dependencies. See [ADR-0025 (Chinese)](../adr/0025-safe-declarative-templates.md) and [ADR-0029 (Chinese)](../adr/0029-xml-fields-and-links.md).

Complete field and text/image-link examples are in [references.xml](../../examples/src/main/resources/templates/references.xml), the [native Word field template](../../examples/src/main/resources/templates/references-word.xml), and [XmlReferencesExample](../../examples/src/main/java/com/mirakyux/export4j/examples/XmlReferencesExample.java). Run `:examples:xmlReferences` and `python scripts/verify_xml_references.py` to check contents-page offsets, four actual header values, static number targets, passive-link rectangles, and native OOXML. See [phase 22 (Chinese)](../verification/phase-22-text-and-adapters.md) for evidence.

Page regions also support same-kind static shared sources through `source-id`/`source-ref`, preserving reuse across sections, variants, and multiple positions. See [shared sources (Chinese)](../xml-page-regions-api.md#共享来源) and [model saving (Chinese)](../xml-model-saving-api.md).
