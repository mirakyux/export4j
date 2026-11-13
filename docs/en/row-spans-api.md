# Semantic table row spans

`TableCell.rowSpan` and `columnSpan` describe real semantic rectangles shared by PDF and standard DOCX. Lengths remain in points with a top-left origin. Component `GridTable` retains separate complex-component and explicit-continuation behavior.

## Declare rows

~~~java
var format = CellFormat.builder().fontSize(10).lineHeight(16).build();
var table = Table.builder("report")
        .columns(new ColumnWidth.Fixed(80), new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1))
        .cellFormat(format)
        .headerCells(TableCell.builder().text("报告\n指标汇总").format(format)
                .columnSpan(3).rowSpan(2).build())
        .coveredHeaderRow()
        .row(TableCell.builder().text("共同分类").format(format).rowSpan(2).build(),
                new TableCell("指标", format), new TableCell("结果", format))
        .row(new TableCell("指标二", format), new TableCell("结果二", format))
        .build();
~~~

Each row declares only new anchors, filling the next available slots from left to right. In the final row above, the two cells begin at physical columns 1 and 2; column 0 belongs to the preceding row. Use `coveredRow()` for fully covered body rows and `coveredHeaderRow()` for headers; they are legal only when the complete `Table` confirms full coverage. `new TableRow(List.of())` also requires table context: an isolated empty row does not establish validity.

Both spans must be positive integers. The complete grid must have no overflow, overlap, holes or span crossing the header/body boundary. Without explicit columns, coverage in the first row determines equal-weight columns, still limited to 4,096.

## Provenance, row heights and pagination

`TableRow.cells()` lists real anchors starting in that row. Its `columnCount()`/`cellAtColumn()` describe only their local contiguous spans and cannot resolve occupancy from preceding rows. Prepare and reuse a two-dimensional grid within an operation:

~~~java
var grid = table.grid();
var owner = grid.ownerAt(3, 0);       // 原始锚点在第 2 行、第 0 列
var start = grid.anchorAt(3, 1);      // 本行真实锚点，Optional<Anchor>
var rows = grid.anchorRows();        // 每个物理行的有序锚点，全覆盖行列表为空
~~~

Rows and columns are zero-based. The owner provides the original row, physical starting column and source cell. Out-of-range access fails; `anchorAt` is empty for covered slots. The immutable grid stores no font or layout state, and each `table.grid()` creates a new instance. `ownerAt` scans source anchors/rows in the worst case; `anchorAt` uses binary search in the selected row. Layout and link processing reuse preparation within the current synchronous operation.

Merged fixed/proportional cells wrap once at their full width and deduct padding once. Cells with `rowSpan=1` establish base row heights; a row without such cells starts at zero. Remaining height constraints are distributed equally over covered rows in span/row/column order, with rounding remainder in the last row. Covered rows do not add padding again, and some fully covered rows may stay at zero height. Fixed line heights remain; shared text/image paragraphs may have different image ascent and neighboring baselines.

Vertical spans form transitive connected groups that move between pages/regions together by default. In atomic mode, repeated headers and at least one complete body group must fit a new region. Short initial regions may be skipped; excessively tall groups fail. Explicit `allowRowSplit(true)` splits owner content: the original anchor row is consumed before covered rows, completed owners extend borders without repeating text, and adjacent empty continuation rectangles on the same page merge. Split mode still repeats complete headers and requires room to advance body content; it does not promise atomic height distribution. Ordinary text merges support [bounded Auto columns (Chinese)](../automatic-merged-columns-api.md), and [inline nested-table Auto columns (Chinese)](../nested-automatic-tables-api.md) are supported. Tables containing block cells retain separate limits. Existing complete-line splitting without vertical spans remains unchanged.

IR retains only real anchors. Fully covered rows do not create duplicate cells or text, but their heights contribute to real cell rectangles and table height. Row, column and UTF-16 positions belong to original anchors. Fields resolve on the final page; images and links appear once; header fields resolve for every actual presentation.

## Templates and DOCX

Static Java `ValueBlock` binding retains spans, rich text, images and fields. Static XML cells accept fixed positive `row-span`/`column-span`; empty `<header/>` or `<row/>` denotes a fully covered row, subject to whole-table validation. The initial dynamic single-row `Rows` prototype rejects `rowSpan>1` to prevent spans crossing repeated data rows.

DOCX uses native `w:vMerge` restart/continue and `w:gridSpan`. Covered rows write standard empty continuation cells. Import normalizes them to unique real anchors without requiring private metadata. Orphan continuations, mismatched intervals, duplicate/invalid properties, incorrect parent chains, missing slots, overflow, header-boundary crossings and nonempty continuation content fail under both import policies. Text, images, fields and links are not silently discarded. A standalone restart may normalize to `rowSpan=1`.

DOCX export still rejects repeated-header PAGE. Native field display width and Word saving follow the [field guide (Chinese)](../docx-fields-api.md). Standard structure, Word reading/field updates, save/readback and PDF export require separate acceptance; arbitrary lossless Word round trips and identical PDF pagination are not promised.

## Compatibility and boundaries

The old two- through six-argument `TableCell` constructors remain, defaulting `rowSpan` to 1. Builder/`toBuilder` retain both spans. The record changed from six to seven components, affecting deconstruction and reflection; complete binary compatibility is not claimed. Empty `TableRow` validity depends on the complete table.

Single-pass `StreamingTable` supports fixed-column horizontal spans. Its ordinary path accepts [bounded atomic vertical groups (Chinese)](../streaming-row-spans-api.md) only with explicit `allowRowSpans(true)`; repeated-header vertical spans and combining this mode with row splitting remain rejected. Recursive block cells and cross-page owner splitting use the explicit [semantic/replay API](streaming-replay-api.md), without automatically expanding XML, DOCX or model-recovery support.

For ordinary semantic tables, see [block-cell continuation (Chinese)](../block-cells-api.md) and [phase 57 (Chinese)](../verification/phase-57-recursive-tables.md) for vertical splitting and recursion, and [variable-width continuation (Chinese)](../variable-table-continuation-api.md) for owner continuation across widths. Arbitrary lossless DOCX round trips remain unpromised. See [ADR-0033 (Chinese)](../adr/0033-semantic-row-spans.md); [phase 26 (Chinese)](../verification/phase-26-row-spans.md) records the initial row-span subset.
