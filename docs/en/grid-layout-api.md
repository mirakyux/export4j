# Editable grid layout

`GridLayout` places text, tables and images on explicit tracks. `GridItem` retains each item's row, starting track and track span. The designer inserts, selects and edits these regions; XML/ZIP saving preserves their structure. Use [ColumnFlow](column-flow-api.md) for continuous body text across columns. Both containers can appear sequentially in the body.

## Use in the designer

In a blank document, choose Insert → Independent text/image grid, then choose full-width, two equal parts, three equal parts, wide-left/narrow-right or narrow-left/wide-right in Choose grid layout. The designer creates the corresponding 12-track layout with real empty paragraphs in each cell, without sample text. It selects the first cell after insertion; double-click it or choose Edit this cell to write, or insert paragraphs, tables and images.

Existing content can be placed side by side: select at least two adjacent direct paragraphs/tables under the same document or section, then choose Independent text/image grid. The layout picker defaults to Place selected content side by side, moving the original content in reading order while preserving paragraph/table IDs, images, fields and external links. When there are more blocks than cells, extras enter the last cell in order; when fewer, remaining cells contain empty paragraphs. Uncheck the option to insert a new empty grid and leave the original selection in place. Selections crossing containers, nonadjacent blocks, dynamic scopes or fixed boxes show the restriction instead of expanding or flattening content.

Select a grid or cell to display the current row proportions and five presets in the inspector. Select a cell in the proportions diagram, then use Add row, Split this cell, Merge with right, Narrower or Wider. Adding a row first chooses its layout and inserts after the current row. Splitting keeps content in the left cell and creates an empty paragraph on the right. Reducing cell count or merging appends original text, images and tables to the retained final cell in order, without deletion. Each operation writes complete same-row coordinates and is undone as one change.

Canvas actions and the inspector edit the same XML:

| Action | Behavior and alternative |
| --- | --- |
| Drag the divider between neighboring cells in the current row | The divider appears only within that row; it snaps to tracks and changes both widths, retaining at least one track per cell. Focus it and use left/right arrow keys, or use Narrower/Wider |
| Drag the handle outside a cell boundary | Drop on another cell in the same grid to swap positions with content; Swap forward/backward in More provides buttons |
| Context toolbar above the canvas | Shows the selected cell width and offers edit, split, merge-right and add-row. Row layout opens the same picker; More contains width/order operations. The toolbar does not cover body text |
| Press Esc or cancel the pointer action | Abandons the drag; XML changes only after release at a valid position |

Full row numbers, starting tracks, spans, track count and point gaps are under Advanced track settings; ordinary authoring requires no coordinates. The canvas displays actual item boundaries, while the structure tree retains containers, items and original children. Built-in example 14, `grid-editor`, combines partial-column spans, intentional offsets and continuous body columns.

The tallest item's content determines row height. Changing a span rewraps at the actual width while other items retain their positions; text edits recalculate heights and pagination. Overlap, overflow or overly tall content produces an error rather than shrinking text, clipping images or covering adjacent regions.

Visual layout operations require items directly inside the same static grid. Item-level if/each/include nodes display the reason layout is read-only, while XML editing remains available. Dynamic content inside items keeps its scope. Merging relocates static `target` references to a removed item onto that item's first direct paragraph/table with a stable ID. Unsafe relocation or dynamic targets fail and retain the original source. Selected content with rotation, footnote/endnote anchors, explicit page breaks or keep-with-next also cannot become a grid.

## XML and Java

```xml
<document version="1">
  <grid id="feature" tracks="12" column-gap="12" row-gap="16">
    <item id="article" row="1" column-start="1" column-span="8">
      <p id="summary">横跨八条轨道的说明文字。</p>
    </item>
    <item id="figure" row="1" column-start="9" column-span="4">
      <p id="picture" line-height="84"><image ref="chart" width="120" height="80" alt="示意图"/></p>
    </item>
    <item id="full-width" row="2" column-start="1" column-span="12">
      <table id="details">
        <columns><column weight="1"/><column weight="2"/></columns>
        <row><cell>项目</cell><cell>通栏说明</cell></row>
      </table>
    </item>
  </grid>
</document>
```

Callers explicitly provide fonts, the `chart` image and output. Track gaps use plain numeric points: `12pt` suffixes and data interpolation are rejected. `row`, `column-start` and `column-span` are required static integers.

```java
var grid = GridLayout.builder("feature")
        .tracks(12).columnGap(12).rowGap(16)
        .add(GridItem.builder("article").row(1).columnStart(1).columnSpan(8)
                .add(new Paragraph("summary", "An editable feature.", ParagraphFormat.of(11)))
                .build())
        .add(GridItem.builder("sidebar").row(1).columnStart(9).columnSpan(4)
                .add(new Paragraph("note", "Supporting text.", ParagraphFormat.of(11)))
                .build())
        .build();
```

The model belongs to `export4j-model`, layout to `export4j-layout-core`; the template adapter does not depend on component implementations. Containers, items and paragraphs/tables have independent IDs. Final fragments retain real source UTF-16, fields, images, external links and host root indices. The canvas uses actual final-IR regions; backends do not recompute tracks or pagination.

## Geometry and pagination

| Property | Contract |
| --- | --- |
| `tracks` | 1–12 equal-width tracks, default 12 |
| `column-gap`/`row-gap` | Nonnegative finite points, each defaulting to 12; column gaps must leave positive track widths |
| Row/starting track | One-based; span is positive and cannot exceed the track range |
| Order and whitespace | Source items are ordered by row and starting track, without same-row overlap. Empty tracks are allowed; skipped row numbers create no empty physical rows |
| Item content | Paragraph/Table; images use ordinary image paragraphs. XML supports p, table, rows and their legal conditions/loops/fragments |
| Static/dynamic empty content | Static empty grids/items with no blocks fail. Authored cells with real empty paragraphs are legal. Entirely empty dynamic results are omitted but still charge binding visits |
| Row height | Maximum actual content height among items in the row; all top-aligned. Row gaps apply only between actual neighboring rows in the same region |
| Pagination | Rows are atomic; insufficient remaining space moves the whole row. An overly tall row fails without shrinking or clipping |
| Composition | `ColumnFlow → GridLayout → ColumnFlow` can continue on the same page using actual occupied heights |

An item's width includes its tracks plus internal track gaps. For 420 pt body width, 12 tracks and 12 pt gaps, each track is 24 pt; six tracks occupy 204 pt and eight occupy 276 pt. Empty tracks retain real geometry without fake table cells.

This subset does not support row-spanning items, automatic placement/backfilling, arbitrary overlap, fixed heights, grid/continuous-column mutual nesting, or grids in cells/page regions. Item headings/lists/captions, explicit page breaks, keep-with-next, rotation and footnote/endnote anchors are also absent. Unsupported combinations fail explicitly. `GridItem` belongs only to `GridLayout` and cannot be an independent body block.

Item minimum-line, keep-together and table row-split settings can be saved but do not split atomic grid rows. Shared source budgets count the grid, items and every descendant. Occupied row count times track count charges `maxCells`; skipped row numbers do not create fictitious rows. Grids and continuous columns share a 4,000,000-unit per-document measurement budget; page-move trials do not charge it twice.

## Saving, conversion and boundaries

Save dynamic source through `XmlTemplateBundle`, preserving if/each/include and expressions. `XmlTemplateWriter` saves the rendered model's actual items and ordered children. The three coordinates remain static; dynamic overlap or disorder fails rather than triggering automatic placement. Non-XML identifiers created by loops follow explicit `IDENTIFIER` renaming and target relocation.

DOCX/HTML/Markdown currently have no native mapping for this semantic grid. Direct export fails explicitly. Unified conversion with explicit `GRID_LAYOUT`/`DEGRADE` retains all item content in source reading order and reports loss of tracks, spans, gaps and side-by-side layout. WARN and IGNORE both record actual losses; deleting the body is not degradation.

For DOCX degradation, `REFERENCE_PAGE` targeting a grid or item redirects to that container's first retained paragraph/table in source reading order and records `PAGE_REFERENCE_TARGET_REPLACED_WITH_FIRST_RETAINED_BLOCK` per field. Real empty paragraphs are valid targets; static containers with no children still fail.

Java `DocumentEditor` can move or replace a whole grid, but explicitly treats item descendants as opaque for location/search/modification. Validation recursively checks all provenance, references and budgets. XML/designer item editing does not expand the Java editing API.

Existing component `GridTable` handles component cells and merged continuation; HTML/CSS Grid is another explicitly bounded import subset. This API does not inherit their complete capabilities or claim CSS Grid, arbitrary Word table or PDF/UA compatibility. See [ADR-0110 (Chinese)](../adr/0110-editable-semantic-grid.md), [engine verification (Chinese)](../verification/editable-grid-layout.md) and [grid authoring/interaction verification (Chinese)](../verification/designer-grid-ux.md).
