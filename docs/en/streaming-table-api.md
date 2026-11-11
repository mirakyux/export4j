# Single-pass long-table API

This page describes the fixed-column, single-source-pass path used by the default `export`, for large record sets containing plain or rich text and explicit images, paginated by whole rows or complete text lines. The application supplies body rows incrementally, without constructing a complete Table. Explicit `allowRowSpans(true)` also enables [bounded connected groups of vertical spans (Chinese)](../streaming-row-spans-api.md); the default mode does not combine them with row splitting. For recursive block cells, owners spanning pages, automatic widths, or future information, use the [semantic/replay entry points](streaming-replay-api.md). The initial implementation boundary is described in [ADR-0031 (Chinese)](../adr/0031-single-pass-table-output.md); see the [streaming rich-text API (Chinese)](../streaming-rich-text-api.md) and [ADR-0036 (Chinese)](../adr/0036-streaming-rich-text.md) for actual font roles and local styles. All lengths are in points, the origin is at the top left, and page indexes are zero-based.

## Fluent export

~~~java
var format = CellFormat.builder().fontSize(10).lineHeight(16).build();
var spec = StreamingTable.builder("orders")
        .pageSettings(PageSettings.A4)
        .columns(90, 300, 120)
        .cellFormat(format)
        .header("编号", "说明", "状态")
        .padding(3)
        .build();

var result = engine.export(spec, () -> openOrderRows(format))
        .backend(PdfBackend.builder()
                .options(options -> options.storage(PdfOptions.Storage.TEMP_FILE)).build())
        .limits(limits -> limits.maxCellCodePoints(2_000).maxRowCodePoints(5_000))
        .diagnostics(this::recordWarning)
        .options(options -> options.timeout(Duration.ofSeconds(60)))
        .to(Path.of("orders.pdf"));
~~~

The application implements `openOrderRows`, returning a TableRowSource. Each `next()` returns one row, or Optional.empty() at the end; returning null is an error. Ordinary TableRow/TableCell values retain their own formatting; the specification's `cellFormat` affects only convenience headers subsequently added. Column widths must leave positive text width. Their total may be less than the body width, but must not exceed it. Headers always remain whole. Body rows are atomic by default; explicit allowRowSplit(true) supports [continuation at complete text lines (Chinese)](../streaming-row-split-api.md). An oversized atomic row fails explicitly; an empty source produces exactly one page.

The source factory opens at `to()` and must create a new source for every export. The engine opens the backend before the source; on success, it closes the source before finishing output and closing the writer. The library does not manage database connections borrowed by the source: the application must define connection ownership. At the start of to(), the backend, budgets, callbacks, and execution configuration are snapshotted; changes made by a reentrant callback affect the next export. Requests and sources should be used within one task. Specifications and engines without shared mutable resources can be reused. The library creates no background threads and acquires no resources implicitly.

`StreamingExportSummary` contains only body-row count, physical-page count, source-cell/code-point counts, output lines, top-level drawing commands, diagnostics, and output bytes. Source counts include headers once; output-line and diagnostic counts include repeated headers. Bytes are those actually submitted to the target stream, not backend temporary-disk usage. Diagnostics include FONT_FALLBACK/INFO and MISSING_GLYPH_REPLACED/WARNING and are delivered synchronously to the callback, without retaining an unbounded list. The application owns memory retained by its callback.

## Budgets, failures, and ownership

StreamingTableLimits combines DocumentLayoutLimits with per-cell/per-row code-point and run limits, accumulated diagnostics, actual field presentation, and generated-value limits. `maxCellInlines` and `maxRowInlines` default to 4,096 and 16,384, including empty runs; an ordinary cell without explicit runs is charged as one effective run. Source headers and body share table row/cell/code-point budgets. Repeated headers consume output-line and diagnostic budgets without consuming source-run budgets again. The specification contains no paragraph blocks, so maxParagraphs does not apply to this path. PdfLimits still controls cumulative PDF pages, commands, fonts, text, images, and paths. ExecutionOptions controls cancellation, timeouts, and output bytes.

When the total row/page count is unknown, `Progress.total()` is -1. Check `hasKnownTotal()` before calculating a percentage. Source reads, callbacks, and custom backends are cooperative synchronous calls; blocking code cannot be forcibly preempted.

Cell errors use DocumentLayoutException and include table ID, physical page, source row/column, and the UTF-16 offset within the cell; row indexes include preceding headers. Diagnostics use ContextualDiagnostic, with STREAMING_TABLE as the source-text region. Generated field values use `INLINE_FIELD:<original field UTF16 position>`, with diagnostic ranges relative to the generated value. A diagnostic/page-delivery failure immediately stops source reads. The default path may have read at most one row ahead; explicit vertical spans may have read one budget-limited connected group and do not read the next independent group. Original IOException values are preserved; close exceptions follow try-with-resources suppression order.

The stream entry point borrows the target stream without closing it; failure may leave partial bytes. The path entry point requires an existing parent directory and filesystem support for atomic replacement in that directory. Any read, layout, close, output, cancellation, or byte-budget failure preserves the old target and removes temporary files, without falling back to copying.

## Page-by-page extensions and limits

Advanced callers can use StreamingTableLayouter.layout directly to deliver LayoutPage synchronously to PageConsumer; the core closes neither the source nor the consumer. DocumentPainter.paintPage converts it to DisplayPage for PageWriter.accept. Call finish explicitly, then close; close does not finish output. Failed sessions cannot accept further pages. The SPI does not retain supplied pages, but format objects can grow with page count.

The default mode on this page supports plain-text cells, InlineText rich text, and explicit PNG/JPEG images, preserving the explicit FontGroup's strict missing-glyph policy or replacement diagnostics. DocumentEngine.Builder.fontRole registers actual font variants; advanced layout uses the overload accepting a role Map, while the older overload retains an empty mapping. Passive text/image external links pass through the per-page link channel; see the [media API (Chinese)](../streaming-media-api.md). PAGE and PAGES with an accurately declared total are supported; see the [fields API (Chinese)](../streaming-fields-api.md). Body vertical spans require explicit `allowRowSpans(true)`; see the [bounded vertical-span API (Chinese)](../streaming-row-spans-api.md). The default mode does not combine them with row splitting and still rejects header vertical spans, block cells, automatic widths, and nesting. Configured page templates, navigation, and annotations are also rejected to prevent silent omission. Unknown total pages, contents/future references, group footnotes, and automatic widths use the explicit replay path. Columns/balancing, DOCX, and automatic signing are not thereby connected to the streaming specification.

Ordinary `PdfBackend` still retains the page tree/COS objects; TEMP_FILE moves only stream storage. To release PDF objects page by page, use recursive semantic cells, owners spanning pages, or explicit future semantics, use the [bounded semantic long-table and replay API](streaming-replay-api.md) with `PdfSpoolBackend`. That entry point also provides finite-prefix Auto probing, stable-snapshot digest checks, final total pages, contents/references/bookmarks, and group footnotes. The existing `export` retains its single-row lookahead behavior. Capacity results report layout, actual PDF output, and process/heap metrics separately; finite tests do not establish permanent freedom from leaks. A single-pass source does not replace the existing complete-document API.

## Fixed-column horizontal spans

Headers and incrementally supplied body rows can use `TableCell.columnSpan`. Each row's real anchors cover the fixed columns completely in order; different rows may contain different anchor counts:

~~~java
var row = new TableRow(List.of(
        TableCell.builder().text("合并前两列").format(format).columnSpan(2).build(),
        new TableCell("第三列", format)));
~~~

A span's width is the sum of its covered columns, subtracting left/right padding only once. It creates neither internal placeholder cells nor duplicated text. IR, exceptions, and missing-glyph diagnostics continue to use physical starting columns: the second anchor above has column index 2. Every row is fully validated before measurement. Measurement is reused when an entire row moves; headers are measured only once.

`sourceCells` counts only real anchors; `DocumentLayoutLimits.maxCells` counts all covered slots, so large spans cannot bypass the budget. Original text, runs, and code points are charged once per anchor; repeated headers still consume actual output-line/diagnostic budgets. Horizontal spans alone require no future-row reads and retain the at-most-one-row lookahead contract; explicit vertical spans use bounded connected groups. See the [row-splitting API (Chinese)](../streaming-row-split-api.md) for body continuation at complete lines. Implementation and independent evidence are in [ADR-0072 (Chinese)](../adr/0072-single-pass-horizontal-spans.md) and [phase 64 (Chinese)](../verification/phase-64-streaming-column-spans.md).

AES-256 and static PDF/A-2b are connected to the page-by-page backend; see [streaming protected output (Chinese)](../streaming-protection-api.md). Archival output still rejects links; signatures are applied explicitly to the final file.
