# Editable body columns

`ColumnFlow` stores paragraphs and semantic tables as a continuous flow filling 1–3 columns from top to bottom, then left to right. Editing text causes the layout engine to recalculate columns and pages; PDF, SVG and PNG consume final layout without backend reflow. Java component `ContinuousColumns` retains its separate contract; see [component columns (Chinese)](../continuous-columns-api.md).

## XML and Java

```xml
<document version="1">
  <heading level="1">通栏标题</heading>
  <columns id="before" count="2" gap="18" balanced="true" weights="1 1">
    <p id="intro">第一段正文。</p>
    <each items="paragraphs" as="paragraph"><p>${paragraph}</p></each>
  </columns>
  <table id="wide-table">
    <columns><column weight="1"/><column weight="2"/></columns>
    <row><cell>项目</cell><cell>横跨正文宽度的说明</cell></row>
  </table>
  <p id="wide-figure" line-height="106"><image ref="figure" width="400" height="100" alt="通栏示意图"/></p>
  <columns id="after" count="2" gap="18" balanced="true" weights="1 1">
    <p id="conclusion">通栏图表下方继续分栏。</p>
  </columns>
</document>
```

Callers explicitly provide `figure` through `XmlTemplate.Builder.image`, together with the required fonts. Ordinary root-level tables and image paragraphs occupy the full body width. The preceding column flow returns control at its highest occupied column bottom; the next flow resumes in the remaining height or continues to the next page. No implicit page breaks separate the three containers.

```java
var columns = ColumnFlow.builder("article")
        .columns(3).gap(18).balanced(true)
        .columnWeights(List.of(1.0, 2.0, 1.0))
        .add(new Paragraph("intro", "Continuous body text.", ParagraphFormat.of(11)))
        .build();
```

The model belongs to `export4j-model`; the template adapter still depends only on the semantic model, without a reverse dependency on components. Lists are snapshotted on configuration and construction. Block IDs, source UTF-16, fields, images and links retain their real paragraph/table provenance. The following table defines the initial supported subset.

| Configuration | Contract |
| --- | --- |
| `count`/`columns` | Integer 1–3; XML defaults to 2 |
| `gap` | Nonnegative finite points, default 18; must leave positive actual column widths |
| `balanced` | Defaults to true in XML and Builder; at most 24 deterministic trials reduce the common column height |
| `weights`/`columnWeights` | Empty means equal widths; otherwise exactly one positive finite ratio per column, separated by whitespace in XML |
| Child content | Paragraph and Table; XML supports p, table, rows, if, each and include |
| Empty content | Static empty containers fail; a container whose dynamic branches/loops produce nothing is omitted, while binding visits still count |
| Placement | Body or section body; cannot nest inside cells, fixed page regions or another column container |
| Not supported | In-column Heading/list/caption/footnote or endnote anchors/explicit page breaks, page-break-before or keep-with-next chains; unsupported combinations fail explicitly |

Headings may appear as full-width root blocks; in-column subheadings can use bold ordinary paragraphs. Source formatting, ID limits, dynamic variables and fragment scopes follow [XML templates](xml-template-api.md).

Tables whose total Fixed width or minimum Auto width exceeds the current column may move to a later wider column. Invalid geometry involving complex padding or recursive fixed-width nested tables still fails. Arbitrary tables are not guaranteed to recover automatically from overly narrow columns. In-column subheadings currently have no keep-with-next guarantee.

When columns begin after existing mid-page content, the preceding block's trailing spacing and the first child's leading spacing are retained. Real page starts, column continuations and page continuations suppress boundary spacing. After the flow, body content resumes at the highest actual column bottom; the final logical child's trailing spacing adds to the next block's leading spacing. Spacing alone never creates a blank page.

## Editing and saving

The designer's Body columns component configures column count, gaps, proportions and balancing. Paragraphs and tables within columns can be edited, inserted and reordered on the canvas. Selecting a direct paragraph exposes Insert full-width table/image in the canvas toolbar without opening the structure tree. Direct paragraphs/tables can also be copied and deleted explicitly. Finish active text editing first, and keep at least one content block in a column flow.

While editing a direct paragraph, Full-width table/image at caret splits it at the current ProseMirror caret, inserts the full-width object and resumes the same column settings. Splitting retains paragraph attributes, nested spans, links, literal variables, inline images and fields. Variables, images and fields are positioned as single editing objects; text positions use UTF-16. Collapse nonempty selections and finish IME composition first; insertion does not silently delete selected text.

At paragraph start/end, insertion uses the block boundary without creating extra empty paragraphs or column flows. The preceding part retains its ID; a genuinely split trailing paragraph and subsequent column flow receive unique IDs. Column properties, source comments and following node order remain. Canceling image selection commits no split or structure change; choosing the resource commits the object and split as one undoable change. A changed template or editing session invalidates stale caret positions.

Children inside loops/conditions cannot be split directly into full-width objects. Select the entire dynamic node as the insertion boundary to preserve expressions and scope. Designer two- and three-column examples each contain a full-width table/image followed by resumed column text.

This editing operation creates the semantic structure preceding columns → full-width object → following columns. It does not wrap body text around floating objects that span only some columns. See the [designer guide](designer.md#convenient-editing-and-precise-layout) and [editing workflow verification (Chinese)](../verification/designer-workflow-v2.md) for interaction and scope.

Save the original `XmlTemplateBundle` source to preserve variables, conditions and loops. Use `XmlTemplateWriter` for an already bound model: it preserves real column containers, properties, children and images, but does not infer the original dynamic template from bound data. Both paths can save ordinary ZIP packages for continued editing after reopening.

IDs generated by loop binding, such as `id@0`, cannot be written directly as static XML identifiers. The writer uses explicit `IDENTIFIER`/`DEGRADE` renaming and target relocation; strict saving rejects them by default. Legal static IDs remain unchanged. Saving the original dynamic template source does not need this renaming.

In-column editing in this release uses XML source or the designer. Java `DocumentEditor` does not yet support locating, searching or structurally editing column descendants; template-editing verification does not establish that API's descendant-editing support.

## Conversion boundaries and grids

DOCX, HTML and Markdown currently have no native column-flow mapping. Direct export fails explicitly. Unified conversion requires explicit `DEGRADE` for `COLUMN_FLOW`, emits every child paragraph/table in reading order and reports loss of column count, proportions, gaps and balancing. WARN/IGNORE do not hide actual losses; DISCARD does not delete the body as a substitute for degradation. Native Word math is a separate [math-adapter](docx-math-api.md) capability and does not add native Word columns here.

When degrading to DOCX, `REFERENCE_PAGE` targeting the column container redirects to its first retained child block and records `PAGE_REFERENCE_TARGET_REPLACED_WITH_FIRST_RETAINED_BLOCK` per field. Atomic block IDs, field styling and reference order remain. The first child may be a real empty paragraph or table; static containers with no children still fail.

Body columns and grids address different needs. Continuous articles do not require a cell assignment for each paragraph; full-width figures use adjacent body blocks. To span two of three columns or place text/images explicitly side by side, use [editable GridLayout](grid-layout-api.md) adjacent to ColumnFlow. Grid items use explicit tracks and atomic row pagination; the initial subset does not nest these containers. Existing Java `GridTable` and HTML/CSS Grid retain separate APIs.

See [ADR-0109 (Chinese)](../adr/0109-semantic-body-columns.md), the published-artifact caller [ColumnFlowConsumer](../../verification/consumer/src/main/java/consumer/ColumnFlowConsumer.java), and [body-column verification (Chinese)](../verification/semantic-body-columns.md).
