# Basic table API

The `GridTable` component also supports repeated headers per column and allocation of merged row groups in continuous columns; see [mixed columns (Chinese)](../mixed-columns-api.md). The row-splitting and source contracts of the semantic `Table` below remain separate.

Tables support fixed, proportional and bounded automatic column widths; text, rich text, inline images and dynamic fields; row and column spans; padding; automatic row heights; pagination by complete rows or connected row groups; and repeated headers. Spans also pass through static templates and standard DOCX `gridSpan`/`vMerge`; see [column spans](column-spans-api.md) and [row spans](row-spans-api.md). Merges covering Auto columns are described in [automatic merged columns (Chinese)](../automatic-merged-columns-api.md); block cells and nested tables retain separate limits. Tables and paragraphs share an ordered block model, and callers explicitly supply the font stack. The model depends only on the JDK, layout performs no file I/O, and the PDF backend does not calculate dimensions.

## Run the example

Run from the repository root:

~~~powershell
.\gradlew.bat build :examples:flowPdf :examples:tablePdf
~~~

The table example writes `examples/build/export4j-table-example.pdf`, with a title, 32 body records, repeated headers and a paragraph after the table. See [TablePdfExample](../../examples/src/main/java/com/mirakyux/export4j/examples/TablePdfExample.java). Font configuration and output follow the [document layout API (Chinese)](../layout-api.md).

## Create a mixed document

~~~java
import java.util.List;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.model.TableRow;

var format = CellFormat.of(12);
var table = new Table("results",
        List.of(new ColumnWidth.Fixed(80), new ColumnWidth.Fraction(1)),
        List.of(new TableRow(List.of(new TableCell("编号", format), new TableCell("说明", format))),
                new TableRow(List.of(new TableCell("01", format), new TableCell("单元格会按列宽自动换行。", format)))),
        1, TableFormat.DEFAULT);
var source = new Document(DocumentMetadata.EMPTY, PageSettings.A4,
        List.of(new Paragraph("intro", "结果表格", ParagraphFormat.of(14)), table));
~~~

`Document.blocks()` is the complete sequence. Top-level paragraph and table IDs must be unique within the document. A table needs at least one column and one body row, and real anchor rectangles must exactly cover its two-dimensional grid; row and column spans default to 1. Without vertical spans, each row's `columnSpan` sum equals the column count. When preceding rows occupy slots, declare only new anchors and use `coveredRow` for fully covered rows. The complete `Table` validates coverage, boundaries and the header/body boundary; `Table.grid` provides two-dimensional provenance. `headerRows` is the number of consecutive physical header rows at the beginning: it may be 0 but must be smaller than the total row count. Empty tables and tables containing only headers fail construction.

Row, column and block indices start at 0. Cells are identified by block ID, row index and column index rather than extra string IDs. Cells may be empty, but empty text still occupies one line; explicit blank lines and trailing newlines follow the text layer's contract.

## Column widths, padding and row heights

Vertical spans first satisfy single-row cell heights, then satisfy merged constraints in span/row/column order; covered rows do not add padding again. Connected vertical groups move together by default. Explicit `allowRowSplit` continues the original owner; complete-line splitting without vertical spans remains available. See [row spans](row-spans-api.md) for zero-height base rows, omission of covered rows from IR, source identity and migration.

| Configuration | Behavior |
| --- | --- |
| `ColumnWidth.Fixed(points)` | Fixed positive width in points |
| `ColumnWidth.Fraction(weight)` | Positive weight sharing the content width left after fixed columns |
| `ColumnWidth.Auto(minPoints, maxPoints)` | Estimates a preferred width from content and shrinks toward the effective minimum when space is insufficient; without proportional columns, also preserves indivisible inline-image and field widths; see [automatic-column constraints (Chinese)](../automatic-merged-columns-api.md). Probing does not charge output-line budgets twice |
| All columns fixed | Retains their total width, aligns left and does not stretch |
| `CellFormat(fontSize, lineHeight, alignment)` | Independent font size, fixed baseline spacing and left/center/right alignment |
| `TableFormat(padding, spaceBefore, spaceAfter)` | Table-wide cell padding and spacing before/after the table block |
| `TableFormat.builder().drawBorders(false)` | Disables cell outlines while preserving geometry, text, header backgrounds and semantic relationships |

For tables with at least one Auto column and no Fraction columns, each real cell also requires its covered columns to fit the widest indivisible image or field plus left/right padding once. Overlapping merged spans share the widths needed to satisfy these lower bounds. Insufficient `Auto.max` or available content width fails explicitly; images and fields are not shrunk, and callers do not need to scan images to raise `Auto.min` manually. Plain text retains its existing minimum/preferred-width rules. If any proportional column exists, the existing final-remainder allocation applies without this additional lower-bound inference.

The sum of fixed widths must not exceed page content width. When proportional columns exist, the remaining width must be positive. Weights are normalized before summation to avoid overflow; unrepresentably small widths fail explicitly. All lengths must be finite. Widths, weights and text dimensions are positive; padding and spacing are nonnegative.

Padding uses `PageMargins(top, right, bottom, left)` in points. A cell's text width is the sum of covered column widths minus left/right padding once, and must remain positive. Row height is the greatest cell text-layout height plus top/bottom padding. Cells align at the top and share their row height; fixed row heights, content clipping and automatic shrinking are not provided.

`CellFormat.of(size)` defaults to left alignment and line height equal to 1.5 times the font size. `TableFormat.DEFAULT` uses 6 pt padding on each side, 0 pt before and 6 pt after the table. Explicit font fallback, line-box and complex-shaping limits still apply.

Borders default to enabled, including the existing three- and four-argument constructors. Disabled outlines survive ordinary pagination, nested cells, variable-width regions and final field resolution in components; each nested table independently controls its outlines. XML saves `draw-borders="false"`, omitting the attribute when enabled. DOCX uses `nil` on all six standard `w:tblBorders` edges; HTML writes the corresponding explicit border styles. This switch does not provide arbitrary line styles, colors or per-edge styling.

## Dynamic cell fields

`TableCell.mixed(content, format)` accepts four `InlineField` kinds: page number, total pages, target page and number reference. Automatic-width probing uses reserved widths; final values do not change column widths or repaginate. Repeated headers resolve fields at each actual page occurrence, and fields in split rows follow their complete text line. `fields()` retains the original cell positions.

`DocumentEngine` or `ComponentDocumentRenderer` performs final composition. Generated-value diagnostics retain the block, final page, original row/column and field-marker position. Unknown targets, missing glyphs and insufficient width fail explicitly. Cell note markers are not supported. DOCX native fields are mapped, but exporting PAGE in a repeated header fails because Word reuses cached values; see [DOCX fields (Chinese)](../docx-fields-api.md). The complete contract is in [inline fields (Chinese)](../inline-fields-api.md) and [phase 11 verification (Chinese)](../verification/phase-11-table-fields.md).

## Pagination rules

1. Each cell is wrapped once; subsequent pages reuse line layouts and glyphs without changing column widths or breakpoints.
2. Body rows are indivisible by default and move to the next page when necessary. Explicit `TableFormat.allowRowSplit=true` splits at complete cell text lines; see [production capabilities (Chinese)](../production-api.md).
3. On first placement, every header and the first body row must fit together; otherwise the table moves to the next page.
4. Each continuation page repeats all headers and consumes at least one body line. No header-only pages or trailing blank pages are generated.
5. By default, a body row plus headers exceeding a fresh content region fails. With splitting enabled, the headers and minimum text fragment must fit a fresh page or fail.
6. Table spacing adds to adjacent paragraph spacing and is discarded at page edges. Continuation pages do not reapply spacing before the table.

These rules allow blank space at page ends but preserve body text exactly once and in source order. Semantic `Table` supports column spans, row spans and [bounded recursive block cells/nested tables (Chinese)](../block-cells-api.md). Whole-table keeping, minimum body-row counts and horizontal pagination are not provided. `TableCell.rich(inlines, format)` supports inline roles, colors, superscripts/subscripts and decorations; `TableCell.mixed(content, format)` supports inline PNG/JPEG. External links use final line fragments; see [images](inline-images-api.md) for image continuation and repeated headers. For component row spans and arbitrary text/image block cells, use `GridTable`; see [advanced components (Chinese)](../advanced-api.md#可跨页组件与合并表格). Its source identity differs from semantic tables, and complete-line continuation requires explicit opt-in. `CellFragment.firstLineIndex` and `TableRowFragment.continuedRow` preserve continuation identity; existing constructors still start at line 0 with a non-continuation row.

## Layout provenance and painting

`LayoutPage.elements()` retains all `LayoutFragment` instances in semantic-block and continuation order. `ParagraphFragment` and `TableFragment` implement this sealed hierarchy.

| Type | Source and coordinates |
| --- | --- |
| `TableFragment` | Original block ID/index, page-local top-left position, fragment dimensions and ordered rows |
| `TableRowFragment` | Original row index, header flag, repeated-header flag and complete cells |
| `CellFragment` | Starting physical grid column, rather than the compact source-list index; border bounds spanning all covered columns; text origin and cell-local text layout |

`repeatedHeader=true` identifies an additional presentation of the original header on a continuation page, not new body content. The first header has `header=true` and `repeatedHeader=false`. Original row numbers and cell UTF-16 ranges never change; fallback diagnostics remain inside cell layouts.

`DocumentPainter` paints paragraphs and tables in `elements()` order. The default appearance uses black text, thin black grid lines and light-gray headers; rich text uses explicit inline colors. There is no configurable border theme. Strokes lie inside cell bounds, and backends execute the existing drawing commands.

## Budgets and failures

`DocumentLayoutLimits` retains its original four-argument constructor and adds the full seven-argument configuration:

~~~java
new com.mirakyux.export4j.layout.core.DocumentLayoutLimits(
        10_000, 1_000_000, 100_000, 1_000, 10_000, 256, 100_000);
~~~

| Field | Default | Counted scope |
| --- | --- | --- |
| `maxParagraphs` | 10,000 | Historical field name; now limits all top-level blocks, including tables |
| `maxCodePoints` | 1,000,000 | All original Unicode code points, shared by paragraphs and cells; header source counts once |
| `maxLines` | 100,000 | Generated paragraph/cell lines plus lines in every repeated header |
| `maxPages` | 1,000 | Pages in the complete document |
| `maxTableRows` | 10,000 | Original rows per table, including headers |
| `maxTableColumns` | 256 | Columns per table |
| `maxCells` | 100,000 | Total original table cells throughout the document |

Each cell also remains subject to independent text-layer budgets. These are structural budgets; [execution control](capabilities-api.md#cancellation-timeouts-and-file-output) separately configures time, cancellation and output bytes. There is no hard limit on total process memory.

`DocumentLayoutException` adds `INVALID_GEOMETRY` for columns exceeding page width or padding leaving no positive text width. Excessively tall rows or header/body combinations use `CONTENT_TOO_TALL`; budget failures use `BUDGET_EXCEEDED`. Shaping, missing-glyph and line-box failures use `TEXT_LAYOUT_FAILED` and retain the cause.

Exceptions expose `blockId()`, `blockIndex()`, `pageIndex()`, `rowIndex()`, `columnIndex()` and `sourceIndex()`. Source positions are UTF-16 offsets in the relevant cell; inapplicable indices are `-1`. During table measurement, the page index is the processing page on entry to the block, not a prediction of the failing cell's final page. Page-budget failures identify the page that could not be created and the next body row. Messages do not include body text by default.

## Compatibility

Paragraph construction, including `List<Paragraph>`, remains supported. `Document.paragraphs()` and `LayoutPage.fragments()` are immutable compatibility views containing only top-level paragraphs. Complete mixed-content processing must use `blocks()` and `elements()`.

`ParagraphFragment.paragraphIndex()` is the original document block index and may be nonconsecutive in mixed documents; `blockId()` and `blockIndex()` are the unified accessors. The original exception accessors `paragraphId()`/`paragraphIndex()` alias block identity and also return table identity for table failures.

These changes were introduced during development before candidate publication. `0.1.0-rc.2` is now published; public declarations follow the [published API baseline (Chinese)](../api-compatibility.md). Migration from early snapshots still requires checking record components and generic-type changes; arbitrary reflection code and existing binaries are not guaranteed compatible. See [ADR-0005 (Chinese)](../adr/0005-basic-tables.md) and [table verification (Chinese)](../verification/phase-3-tables.md).

Inline-content nested tables support [bounded automatic columns (Chinese)](../nested-automatic-tables-api.md) at the actual parent-cell width. Tables that themselves contain block cells still reject Auto; this does not provide arbitrary block-tree preferred widths or automatic columns for single-pass sources.
