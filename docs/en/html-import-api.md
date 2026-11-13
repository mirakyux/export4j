# Offline HTML/CSS Import

`HtmlImporter` in `export4j-adapter-web` converts an offline HTML/CSS subset into a `Document` that supports [semantic editing (Chinese)](../semantic-editing-api.md) by default. Explicit `.layoutMode(true)` compiles boxes, Flex, Grid and positioning into runtime layout components. Neither mode executes scripts or accesses files or URLs. The Export4j engine performs pagination.

For input that mixes Markdown and raw HTML tables, such as MinerU output, caller code can explicitly convert it to HTML and import it strictly through this entry point. See [literature PDF integration (Chinese)](../literature-pdf-integration.md) for local image registration, merged-table widths, figure/caption pagination and mathematical-content limits.

```java
import com.mirakyux.export4j.adapter.web.HtmlImporter;
import com.mirakyux.export4j.model.edit.DocumentEditor;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;

var policy = ProcessingPolicy.builder()
        .feature("BLOCK_QUOTE", ProcessingPolicy.Action.WARN,
                ProcessingPolicy.Treatment.DEGRADE)
        .build();
var prepared = HtmlImporter.builder().policy(policy).build()
        .read("<h1>Report</h1><p id='status'>Old</p><blockquote><p>Quote</p></blockquote>")
        .prepare();
var predicted = prepared.preview();
var imported = prepared.execute();
var edited = DocumentEditor.edit(imported.document())
        .replaceText("status", "Old", "Ready").execute();
var actualLosses = imported.report().losses();
```

Input is an immutable String. The Builder takes a snapshot when building. `.execution(ExecutionOptions)` on a Request controls synchronous execution; `prepare()` and `execute()` declare `IOException`. A PreparedImport binds the exact input, configuration and candidate. Successful preflight does not mean that a file has been written successfully. `${name}` is ordinary text.

## Elements and Resources

| Input | Mapping and restrictions |
| --- | --- |
| p, h1–h6 | Paragraphs and headings; retain usable block IDs, decode entities, collapse ordinary whitespace, and preserve br and nonbreaking spaces |
| span, b/strong, i/em, u, s/del/strike | Full inline styles and combined font roles; fonts are not synthesized |
| a | Passive HTTP/HTTPS/mailto links; relative links require an explicit HTTP(S) base; internal anchors report target loss separately |
| img | Resolves the exact src through `.image(src, InlineImage)`; preserves source alt text; integer width/height values are converted from CSS px; a single dimension scales using the registered resource's aspect ratio |
| ol/ul/li | Up to nine levels, positive starting numbers and mixed nesting; ordered children use the model's full dotted numbering. Complex items with multiple blocks require explicit degradation; unsupported structures fail |
| Explicit Export4j list markers | When the first inline element is `span.marker[data-export4j-marker="1"][data-export4j-level]`, validates its plain-text marker and level 1–9. Simple level-one items retain the marker, numbering restarts and independent styles; the marker does not enter body text. Complex markers require explicit `LIST_MARKER` degradation into ordinary paragraphs containing the original marker, or DISCARD of the item's body. An ordinary `class="marker"` does not trigger recognition; invalid versions, empty or hidden markers, and nested marker elements are rejected |
| table/thead/tbody/tfoot/tr/td/th | Positive colspan/rowspan, actual anchors and complete rectangular coverage; the first thead or consecutive leading rows made entirely of th become repeating headers; at least one body row is required |
| colgroup/col | Accepts only bounded column declarations before rows and positive integer span. `TABLE_COLUMNS` explicitly degrades or discards column-group geometry; actual columns receive equal widths. CSS width/calc and similar values still report loss under the CSS subset. Declarations exceeding the actual grid, invalid span and hidden column structures fail |
| A single p in a repeating header | `TABLE_HEADER_PARAGRAPH` explicitly degrades it to inline content and reports lost paragraph geometry and identity; explicit DISCARD removes the content. Multiple blocks and nested tables in headers remain rejected |
| p/table in a cell | Ordered block cells; the root table counts as level one, up to 16 levels. Unwrapped text or other mixed structures fail; hidden content is not flattened |
| div.table-scroll in a cell | Accepts only the exact class, one direct table child and whitespace-only direct text. `TABLE_CELL_CONTAINER` + DEGRADE removes container geometry, preserves p/table/p order and continues counting table depth. DISCARD removes the entire nested table; when the cell has no other blocks, it retains an empty inline cell. Hidden containers, other classes, mixed text and multiple children are rejected |
| div and document grouping containers | Enter the body in source order; container anchors and unmappable box geometry report separate losses |
| blockquote, pre, code and other elements | Unmodeled semantics are rejected by default; explicit degradation can retain indented or preformatted text, or flatten child content; explicit discard can remove the corresponding content |

Unknown attributes report loss by default; events are not executed and resources are not fetched. A standard simple HTML doctype and UTF-8 charset are accepted. Other head metadata does not automatically override caller-provided DocumentMetadata. Duplicate IDs, malformed tables, invalid Unicode and NUL always fail.

Source IDs on paragraphs, headings, tables and li become block IDs. Other anchors are not represented as model targets. Generated IDs use `html-N` and avoid all source IDs. Source positions are half-open UTF-16 ranges in the original input. Table losses include physical rows and columns and nested cell paths. Diagnostics do not echo body text or resource paths.

## CSS Subset

Style elements and inline style are supported. Selectors support element names, `*`, `.class`, `#id`, compounds on the same element, descendants, direct children, adjacent and subsequent siblings, and comma-separated lists. Attribute selectors support existence, `=`, `~=`, `|=`, `^=`, `$=` and `*=`. Static pseudo-classes support first/last/only-child, root, empty, and odd/even/an+b in nth-child/nth-last-child/nth-of-type/nth-last-of-type. `:is()`/`:not()`/`:where()` accept up to 16 nonnested same-element compounds, including attributes; relational selectors are not accepted as function arguments. Each selector is limited to 256 characters and 16 parts. Pseudo-elements, dynamic states, namespaces and CSS escapes are outside this subset.

The cascade compares important first, then inline status/specificity and source order. An ordinary inline declaration cannot override a stylesheet important declaration. `:where()` has zero specificity; `:is()` and `:not()` use the maximum specificity of their arguments. Sibling, ancestor and nth scans count toward the shared work budget.

| Property | Accepted values and behavior |
| --- | --- |
| font-size | Positive pt/px, with `1 px = 0.75 pt`; fonts are never obtained implicitly |
| font-family | One explicitly registered family, mapped to four actual roles through `.fontFamily(family,regular,bold,italic,boldItalic)`; unregistered families fail by default |
| font-weight/font-style | normal/bold/400/700; normal/italic |
| color | `#rgb`/`#rrggbb`, black/white/red/green/blue/gray/grey |
| line-height | normal, a positive multiplier or a pt/px length; multipliers use the inherited font size, while lengths remain fixed |
| text-decoration | none, underline, line-through and a combination of both lines; child none does not silently remove ancestor decorations |
| text-align | left/right/center/justify, mapped to paragraph and cell formatting |
| white-space | normal/pre-wrap; pre's no-wrap behavior is not promised |
| margin-top/margin-bottom | Nonnegative pt/px; nonzero values require `CSS_SPACING` DEGRADE and explicitly become additive paragraph spacing |
| margin-left/text-indent | pt/px; the resulting indent must be nonnegative. Inline geometry and container-box geometry cannot be treated as supported paragraph geometry |
| display | none requires explicit `HIDDEN_CONTENT` DISCARD; declarations retaining the element's original inline/block type are accepted; changing its type or table formatting context reports `CSS_CONTEXT`. See layout mode for Flex/Grid |
| break-before/break-after | auto/page, producing actual breaks between blocks; page-break-before/page-break-after aliases are supported |
| break-inside | auto/avoid/avoid-page, mapped to whole-block constraints; the page-break-inside alias is supported |
| orphans/widows | 1–999, mapped to pagination constraints on complete paragraph lines; this does not scale font size or simulate browser pagination |
| border on td/th | none/0/0pt/0px; only uniform border removal across the whole table maps to `TableFormat.drawBorders(false)`. Mixed overrides report `TABLE_BORDERS` and explicitly degrade to uniform borders |

Unsupported properties, values, selectors and rules use `CSS_PROPERTY`, `CSS_VALUE`, `CSS_SELECTOR` and `CSS_RULE`, respectively. Incompatible geometry contexts use `CSS_CONTEXT`; unknown families use `CSS_FONT_FAMILY`. Degradation/discard removes the corresponding declaration or rule; original CSS source cannot be preserved. Output starts from the caller's page and body configuration and does not have a browser's complete default stylesheet.

### Media and Pages

`.media(HtmlImporter.Media.PRINT)` is the default; SCREEN can be selected explicitly. The pt dimensions of `.viewport(PageSize)` are used only for media queries and default to the caller's original page size. An output-size change through `@page` does not retrigger responsive rules.

Bounded nested `@media` supports print/screen/all, not/only, commas and and, plus min/max-width, min/max-height and orientation:portrait/landscape. Thresholds use pt/px. Queries are limited to 512 characters and eight conditions, with condition nesting up to eight levels. Syntax and security-sensitive constructs in unmatched conditions are still checked.

PRINT accepts size, margin shorthand and four margin longhands in unnamed `@page`: A4, Letter, their portrait/landscape variants, or two positive pt/px dimensions. size:auto, named pages, margin boxes, page selectors and other paper names require explicit loss handling. SCREEN validates these rules without applying page dimensions. Page declarations also follow important/source-order cascading. An insufficient final page area, invalid page dimensions or content that cannot fit still fails.

## Explicit CSS Layout Mode

```java
import com.mirakyux.export4j.adapter.web.HtmlImporter;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;

var document = HtmlImporter.builder()
        .layoutMode(true)
        .media(HtmlImporter.Media.SCREEN)
        .pageSettings(new PageSettings(new PageSize(420, 300), PageMargins.uniform(20)))
        .viewport(new PageSize(420, 300))
        .build().read("""
            <style>p{margin:0;font-size:12pt;line-height:18pt}</style>
            <div style="display:flex;width:360pt;height:70pt;gap:12pt">
              <p style="flex:1 1 60pt;background-color:#d85b36">First</p>
              <p style="flex:2 1 60pt;background-color:#3574bd">Second</p>
            </div>
            """).execute().document();
```

Pass this `Document` to a `DocumentEngine` configured with explicit physical fonts and actual variants, then choose a PDF/SVG/PNG backend. Font sizes, measurement, line breaking and pagination still use the shared engine; the backend never receives original CSS. The two cells in this example are actually 136 pt and 212 pt wide, separated by 12 pt.

| Scope | Implemented bounded layout contract |
| --- | --- |
| Boxes | width accepts pt/px/percentage/auto; height accepts pt/px/auto; corresponding min/max are supported, and max can be none. Four-sided padding and margins, content-box/border-box and bounded RGB background colors are supported. Both dimensions and content must fit the parent area |
| Flex | Explicit block containers; row/row-reverse/column/column-reverse, plus wrap/wrap-reverse for rows; grow/weighted shrink/basis, order, row/column gap, justify-content, align-items/self and align-content. Factors must be zero or at least 1. Explicit min/max freezing is followed by bounded redistribution; column direction requires an explicit container height and nowrap |
| Flex sizing requirements | Each in-flow item requires an explicit basis or main-axis dimension; numeric shorthand such as `flex:1` uses a zero basis. Intrinsic width, automatic minimum-content width and complex browser baselines are outside this algorithm; automatic probing must not be presented as supported |
| Grid | Explicit positive pt/px/percentage/fr column tracks; positive pt/px or auto row tracks; bounded numeric repeat; grid-auto-rows, row/row dense; positive line numbers and span, order, gap and align/justify-items/self. Explicit cells reserve space first; row spans are allocated after measuring individual rows; explicit empty rows still occupy height. Container height is the sum of row tracks; track groups remain aligned to the start |
| Positioning | static/relative/absolute; left/right/top/bottom accept pt/px/percentage. Relative offsets resolve against the parent content box; vertical percentage offsets require an explicit parent height; an offset top-level relative box needs an additional explicit parent box. Absolute requires an explicitly positioned direct parent, at least one explicit offset per axis, and either an explicit width or both left and right constraints; it is removed from normal flow. The importer does not search through static intermediate layers for a browser containing block. Positioned direct child boxes must remain inside the parent without overlapping |
| Text and resources | Paragraphs, headings and semantic tables act as leaves, preserving original UTF-16 text, style roles, registered inline images and passive external links. body can act as a layout root. Visual order/reversal/positioning does not change logical source order. Image CSS width/height use explicit pt/px; setting only one dimension retains the registered aspect ratio |
| Pagination | Each compiled box is atomic and can move as a whole to the next page. Outer break-before/after produces actual page breaks. Flex/Grid is not fragmented across pages inside a box; nested breaks are actually removed through `CSS_PAGINATION_CONTEXT` + DEGRADE. Content exceeding a complete fresh page fails |

The top level in layout mode is a `ComponentBlock`. Its internal semantic placements serve only the current layout and final link resolution. Headings inside runtime layout leaves degrade to paragraphs and do not build PDF/UA structure. This is not a CSS DOM that a general editor can modify node by node. Internal borderless single-cell tables bridge shared semantic measurement; they are not represented as user-imported tables. Prefer the default mode for semantic editing or general HTML/Markdown/DOCX conversion.

Exporting runtime components to semantic formats that cannot represent them fails by default. Only explicit DISCARD removes the component's entire subtree and reports actual target diagnostics such as `CUSTOM_BLOCK`. WARN and IGNORE both retain actual losses; neither can claim that text or box geometry was preserved. SVG/PNG backends retain their own strict restrictions on interactive external links; layout mode does not silently remove them.

Unsupported intrinsic Flex basis or column Flex without an explicit height can actually degrade to ordinary block flow through `CSS_LAYOUT` + DEGRADE; two text blocks then stack vertically. Non-stretch horizontal alignment in Grid and column Flex requires explicit child widths; otherwise the default is rejection, while DEGRADE actually restores stretch. Other degradations under the same feature are reported individually: positive Flex factors below 1 become 1; explicit Grid container heights/height constraints and track-group alignment are removed; root relative offsets without a parent layout box and vertical percentage relative offsets without an explicit parent height are removed; missing static-position axes for absolute positioning become zero; missing intrinsic width is replaced by left and right constraints. All are rejected by default. WARN/IGNORE must explicitly select DEGRADE before execution, and actual loss reports retain the concrete effect.

Lists retain their existing full support in default semantic mode. ul/ol inside compiled boxes have no runtime leaf mapping; `CSS_LAYOUT_LEAF` permits only explicit DISCARD of the entire list subtree. Other unmodeled elements still use their own element-loss policies.

Margins do not use browser collapsing: in-flow Flex/Grid children use actual noncollapsed spacing; other nonzero vertical margins still require `CSS_SPACING` + DEGRADE. Percentage height/min-height/max-height, fixed/sticky positioning, negative margins, overflow clipping, minmax/auto-fit/named grid lines, overlapping grids, filters and complete browser styling are outside the subset. Exceeding a layout box, page area or budget, or text that cannot be fully displayed, always fails. Output is not completed by clipping, shrinking, ignoring exceptions or implicitly selecting system fonts.

Each box permits up to 256 direct child boxes, layout depth 32, 64 tracks, span 256 and 10,000 grid rows. Each root-box measurement/placement permits up to 100,000 bounded work checks. Shared import budgets, synchronous cancellation and atomic protection of an existing destination still apply. See [ADR-0104 (Chinese)](../adr/0104-bounded-html-css-layout.md) for the implementation decision and [Phase 104 (Chinese)](../verification/phase-104-html-css-layout.md) for independent geometry and file evidence.

## Policies and Execution Boundaries

Uses [ProcessingPolicy (Chinese)](../semantic-editing-api.md), with format key `HTML`, operation key `IMPORT` and precedence operation → feature → format → default. The default is ERROR. WARN and IGNORE retain actual `issues()`/`losses()`; IGNORE only hides warnings. HTML_REPAIR permits only explicit DEGRADE; hidden content permits only DISCARD. PRESERVE fails when no original-content preservation path exists.

Scripts, events/srcdoc, active http-equiv, iframe/object/embed, SVG/MathML, external stylesheets, CSS url/import/font-face/expression/escapes and dangerous links are rejected regardless of loss policy. The only permitted http-equiv is the exact restrictive Content-Security-Policy declaration produced by the project's self-contained output: `default-src 'none'; img-src data:; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'`. It is explicitly removed as head metadata through `HTML_METADATA` + DISCARD; it neither executes nor changes the importer's security policy. refresh, reporting endpoints, other policy text and non-meta containers remain rejected. The complete source tree is checked first; hiding or discarding content does not bypass these restrictions.

Shared `ImportLimits` bound input, line length, tree/depth, text, table slots, reports and work. Scripts are not executed and resources do not connect externally. These limits still do not constitute a hard RSS limit, hard interruption inside third-party parsers or a hostile-input sandbox.

See [HtmlImportExample](../../examples/src/main/java/com/mirakyux/export4j/examples/HtmlImportExample.java) for a complete example. Run `:examples:htmlImport` and `scripts/verify_html_import.py`; evidence is in [Phase 46 (Chinese)](../verification/phase-46-html-import.md). Reimport of project-exported list markers, table column groups and single-paragraph headers with explicit loss policies is covered in [Phase 87 (Chinese)](../verification/phase-87-complex-lossy-recovery.md), using the same actual importer as [model recovery after lossy output](model-recovery-api.md#save-the-current-file-model-after-lossy-conversion). Arbitrary exported HTML is not guaranteed to reimport without loss under the default strict input mode.

[Phase 88 (Chinese)](../verification/phase-88-section-recovery.md) adds reimport of exported nested-table scroll containers. Container changes report `CELL_TABLE_WRAPPER_REMOVED` and the actual nested cell path. The default is ERROR; WARN/IGNORE retain actual losses, and PRESERVE has no preservation path. Full-source security checks, the 16-level table-depth limit, budgets and cancellation boundaries remain in force.
