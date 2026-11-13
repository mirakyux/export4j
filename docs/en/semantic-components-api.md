# Semantic component composition API

Use this API for semantic tables and region continuation inside component containers. All lengths are in pt, with the origin at the component's top-left corner. The API is in `com.mirakyux.export4j.components`.

## Authoring

```java
var table = Table.builder("nested-summary")
        .header("项目", "值")
        .row("状态", "完成")
        .build();
var content = FlowColumn.builder()
        .padding(8)
        .text("前面的文字")
        .column(column -> column.padding(6).semanticTable(table))
        .text("后面的文字")
        .build();
```

Render with DocumentEngine or ComponentDocumentRenderer. Callers still load fonts explicitly, and Chinese content requires fonts supporting Chinese. `semanticTable(Table)` accepts the same semantic model as a top-level table; continue using `.grid(...)` for merged grids.

Rich text, inline images, PAGE/PAGES, page-number references, and numbering references in TableCell.mixed survive through final layout. Nested table IDs can be bookmark or reference targets. Contents-page offsets, displayed section page numbers, FontScope, and named font roles are preserved during final resolution. Cell note markers retain the semantic table's existing rejection boundaries.

## Long tables and columns

`SemanticTable` implements `WidthAwareRegionFlow` and can also serve an equal-width `RegionFlow`. The `.semanticTable(table)` call above uses the current page's remaining height and naturally continues at complete rows by default. Semantic rowSpan groups move together by transitive row connectivity; explicit row-splitting rules are described below and in the [Row spans API](row-spans-api.md). Every occupied region repeats the full header. A short initial region is skipped only when it cannot hold the header plus the minimum body row/group, without creating an empty fragment. Complete continuation regions must make progress or fail explicitly.

```java
var content = ContinuousColumns.builder(FlowColumn.builder()
        .padding(4)
        .semanticTable(table)
        .text("表格后的正文")
        .build())
        .columns(2).gap(16).build();
```

Headers in two columns on the same physical page have the same PAGE value. Later physical pages, contents offsets, and section restarts resolve against actual pages. Bookmarks and page-number references point to the table's first actual fragment. Fixed RowComponent, BoxComponent, and the default whole-grid mode still call single-viewport `layout()` and fail when the long table does not fit. Grids with explicit row splitting can prepare semantic flows inside long merged cells; see the [Merged-cell semantic flow API (Chinese)](../grid-semantic-flow-api.md).

Explicit `table.format().allowRowSplit()` permits body cells to split at complete text lines and also supports vertical owners and recursive continuation of bounded Paragraph/Table block cells. Each nested-table level declares its row-splitting switch independently. Fields, inline images, text hyperlinks, image hyperlinks, and source UTF-16 positions move with the actual lines. Fixed line boxes must still accommodate images; images and fields cannot split. Ordinary inline cells can span bounded Auto columns; see [Automatic merged columns (Chinese)](../automatic-merged-columns-api.md). Auto widths on a table containing block cells, repeated block-cell headers, and cell note markers remain rejected. Maximum table depth is 16; see the [Block cells API (Chinese)](../block-cells-api.md). Equal-width allocation reuses measured complete lines.

When column widths differ, column widths, headers, and remaining body content are prepared for each actual region width. This supports vertical owners in inline cells, recursive block cells, and complete-line continuation without scaling or cropping results from an earlier width. A width sequence may contain at most 16 entries, with cumulative preparation work limited to 4,000,000 source UTF-16 units. Other source, line, and fragment budgets remain in effect. A complete continuation region that cannot make progress fails explicitly. See [Variable-width semantic tables (Chinese)](../variable-tables-api.md) and [Variable-width continuation (Chinese)](../variable-table-continuation-api.md) for calls and limits.

## Composition and provenance

FlowColumn, RowComponent, BoxComponent, GridTable, MultiColumn, ContinuousColumns, and MultiFloatText preserve semantic slots and drawing order. When a table moves to a later region, fields use the displayed numbering of the physical page where they actually appear. Field resolution does not change measurement results.

A ComponentFragment in `ComponentDocumentLayout.body()` exposes its TableFragment and source Table through `layout().semanticPlacements()`. Original cell row/column coordinates, complete-line provenance, and UTF-16 ranges remain intact. `contextualDiagnostics()` returns the nested table's own ID, physical page, row, column, and source position. Generated values still use `INLINE_FIELD:<位置>`.

`SemanticPlacement.fragmentIndex()` is the zero-based fragment index within one measured flow, and `fragmentCount()` is its complete fragment count. Neither is a page or column number. Composition and field resolution must preserve both. The source, host block, total fragment count, and actual font configuration must agree, and indices must be complete and consecutive. The old five-argument constructor represents a complete table with one fragment.

Custom composers must use the core `ComponentSemantics.append`. Reading only `commands()` fails explicitly to avoid discarding semantic contents. Reading static commands also requires passing the slots. Slots support only translation and fully visible rectangular clipping; rotation, scaling, and partial clipping fail explicitly.

## Current boundaries

- A table can continue through the body's region flow. Nested table IDs remain unique throughout the document. Placing a second complete table with the same ID is not allowed, including reuse across sections.
- A semantic table's own header can repeat in each region. GridTable can also repeat a cached semantic header it owns and has validated. The grid determines the final presentation sequence locally; independently inserting the complete source again still fails.
- Semantic tables are not used in headers, footers, or foreground/background decorations because those paths do not yet have a final semantic-resolution context.
- Spacing before and after a table is controlled by the parent container's gap, padding, or Spacer.
- The old TableComponent remains a static single-viewport wrapper, rejecting fields, hyperlinks, and font roles. Its existing contract does not change automatically.
- Component composition has no native DOCX mapping. Recursive semantic cells and nested tables in DOCX use the ordinary `Table` path; see the [Block cells API (Chinese)](../block-cells-api.md). Arbitrary Word documents are not guaranteed to convert losslessly or paginate identically to PDF.

Structure, source-text, and image budgets are checked together with the top-level body, counting each source once. Actual fragments, output lines, repeated headers, generated fields, and interactions count on every occurrence. `maxParagraphs` includes top-level blocks and nested semantic sources. Final drawn text remains subject to the composed-output budget. Components prepare under inner default limits first, then the caller's smaller global limits are checked together; this does not mean every global limit was inherited before measurement.

Single-table preparation defaults to at most 1,000 occupied regions. Component documents currently also check global physical-page and fragment counts; continuous columns may reach the single-table region limit first. Core `DocumentLayouter.layoutTableRegions` accepts explicit `DocumentLayoutLimits` and has a separate hard limit of 100,000 occupied fragments. `FlowRegions` permits at most 100,016 finite short initial regions, independently of the variable-width region cycle's limit of 16 distinct widths. All measurement remains in memory; this is not end-to-end streaming. Custom components remain trusted, deterministic, synchronous code. Budget validation does not establish safe execution of arbitrary untrusted callbacks.

See [ADR-0021 (Chinese)](../adr/0021-component-semantic-insertions.md) and the [Region-flow decision (Chinese)](../adr/0022-semantic-table-region-flow.md) for design decisions. Fixed-composition evidence is in [Phase 17 (Chinese)](../verification/phase-17-semantic-components.md), and region-continuation evidence is in [Phase 18 (Chinese)](../verification/phase-18-semantic-table-flow.md).
