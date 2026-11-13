# Semantic table column spans

Semantic `Table` supports contiguous physical column spans through PDF layout, final fields, image/text external links, Java/XML templates and standard DOCX `gridSpan`. See [ADR-0032 (Chinese)](../adr/0032-semantic-column-spans.md) and [phase 25 verification (Chinese)](../verification/phase-25-column-spans.md). These spans belong to the semantic model; component `GridTable` remains a separate API.

## Fluent construction

~~~java
var cell = CellFormat.builder().fontSize(10).lineHeight(18).build();
var table = Table.builder("results")
        .columns(new ColumnWidth.Fixed(80), new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1))
        .cellFormat(cell)
        .headerCells(TableCell.builder().text("检查结果").format(cell).columnSpan(3).build())
        .row(TableCell.builder().text("说明占两列").format(cell).columnSpan(2).build(),
                new TableCell("通过", cell))
        .row(new TableCell("编号", cell),
                TableCell.builder().text("另一种合并结构").format(cell).columnSpan(2).build())
        .build();
~~~

`headerCells` accepts real header cells; `header(String...)` remains available. Plain, rich and mixed cells can retain content through `toBuilder().columnSpan(n).build()`. Existing constructors and factories default to 1. Zero/negative spans, incomplete coverage and row overflow fail construction.

Without explicit columns, the first row's total span determines equal proportional columns, up to `Table.MAX_INFERRED_COLUMNS` (4,096). More columns require explicit configuration within layout budgets; one huge span does not allocate an enormous implicit grid.

## Provenance and layout

`TableRow.cells()` is the compact list of real content. In `[A(span=2), B(span=1)]`, B has list index 1 but physical starting column 2. `columnCount()` returns covered columns; `cellAtColumn(1)` returns A, `cellAtColumn(2)` returns B, and out-of-range access throws `IndexOutOfBoundsException`. IR `CellFragment.columnIndex`, exceptions and diagnostics use the physical starting column; text positions remain cell-local UTF-16 offsets.

A merged width is the sum of covered widths, with horizontal padding deducted once and no fake internal borders. Fixed, proportional and bounded Auto columns may be merged; see [automatic merged columns (Chinese)](../automatic-merged-columns-api.md) for preferred-width probing and explicit minimum/maximum constraints. Source code points, images and measurement are processed once per real anchor. `DocumentLayoutLimits.maxCells` still counts every covered grid slot, so merging cannot bypass structural limits.

Rows move whole by default. Explicit `allowRowSplit` splits at complete text lines and retains `firstLineIndex`, `continuedRow`, images and original field positions. Adjacent rows may have different spans. `SemanticTable`, short regions, continuous columns and semantic tables inside grids share the core algorithm. Fields resolve against actual physical pages, and external links use final rectangles.

## Templates and DOCX

Ordinary Java `DocumentTemplate` tables and dynamic `Rows` prototypes retain spans. XML cells accept fixed `column-span="2"`: a positive decimal integer without expressions, signs, whitespace or overflow. Each static row, header and dynamic prototype must completely cover the columns grid.

DOCX writes standard `w:gridSpan`, retaining physical `tblGrid` columns and one `w:tc` per anchor. Import validates positive integers, unique attributes/elements, parent containers and complete row coverage; spans determine columns when no grid exists. Merging does not depend on project-private metadata. Native field widths, images and links follow the [DOCX field contract (Chinese)](../docx-fields-api.md); repeated-header PAGE export still fails.

## Boundaries and migration

Phase 25's column-span slice did not include semantic `rowSpan`; see [row spans](row-spans-api.md) for subsequent standard `vMerge` and two-dimensional provenance, and [block cells (Chinese)](../block-cells-api.md) for recursion. Horizontal pagination remains unsupported. `StreamingTable` supports [fixed-column horizontal spans](streaming-table-api.md#fixed-column-horizontal-spans), and its ordinary single-pass path can explicitly enable [bounded atomic vertical groups (Chinese)](../streaming-row-spans-api.md). Repeated-header vertical spans and combining vertical spans with row splitting in that path still fail. Cross-page owner splitting uses the separate [semantic/replay path](streaming-replay-api.md). Old `hMerge`, omitted slots and unknown complex Word structures follow strict loss boundaries; guessed geometry or flattened content is not lossless preservation.

Phase 25 changed `TableCell` from a five-component to a six-component record while retaining old constructor calls and accessors; subsequent `rowSpan` expanded it to seven components. Record-deconstruction arity and reflective shape changed, so callers must recompile and update patterns. No dependency, font or implicit resource access was added.
