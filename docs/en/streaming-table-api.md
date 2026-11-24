# Single-pass long-table API

This page describes the fixed-column, single-source-pass path used by the default `export`, for large record sets containing plain or rich text and explicit images, paginated by whole rows or complete text lines. The application supplies body rows incrementally, without constructing a complete Table. Explicit `allowRowSpans(true)` also enables [bounded connected groups of vertical spans (Chinese)](../streaming-row-spans-api.md); the default mode does not combine them with row splitting. For recursive block cells, owners spanning pages, automatic widths, or future information, use the [semantic/replay entry points](streaming-replay-api.md). The initial implementation boundary is described in [ADR-0031 (Chinese)](../adr/0031-single-pass-table-output.md); see the [streaming rich-text API (Chinese)](../streaming-rich-text-api.md) and [ADR-0036 (Chinese)](../adr/0036-streaming-rich-text.md) for actual font roles and local styles. All lengths are in points, the origin is at the top left, and page indexes are zero-based.

## Fluent export

~~~java
var format = CellFormat.builder().fontSize(10).lineHeight(16).build();
var spec = StreamingTable.builder("orders")
        .pageSettings(PageSettings.A4)
        .columns(90, 260, 120)
        .cellFormat(format)
        .header("ID", "Description", "Status")
        .padding(3)
        .build();

var rows = TableRowSources.fromStreams(() -> openOrderStream(),
        order -> TableRow.text(format, order.id(), order.description(), order.status()));
var temporaryParent = Files.createDirectories(Path.of("work"));
var result = engine.export(spec, rows)
        .backend(new PdfSpoolBackend(temporaryParent))
        .to(Path.of("orders.pdf"));
~~~

The engine uses the font group provided by the application. Implement `openOrderStream` to open a fresh order stream for each export. The row source closes that stream. For an existing `Iterable`, use `TableRowSources.fromIterable`. With a custom `TableRowSource`, `next()` returns one row or `Optional.empty()` at the end. Ordinary TableRow/TableCell values retain their own formatting. The specification's `cellFormat` affects only convenience headers added after it. Column widths must leave room for text, and their sum must fit the page body. Headers always remain whole. Body rows are atomic by default. Enable `allowRowSplit(true)` for [continuation at complete text lines (Chinese)](../streaming-row-split-api.md). An oversized atomic row fails explicitly. An empty source produces one page.

The source factory opens at `to()` and must create a new source for every export. The engine opens the backend before the source; on success, it closes the source before finishing output and closing the writer. The library does not manage database connections borrowed by the source: the application must define connection ownership. At the start of to(), the backend, budgets, callbacks, and execution configuration are snapshotted; changes made by a reentrant callback affect the next export. Requests and sources should be used within one task. Specifications and engines without shared mutable resources can be reused. The library creates no background threads and acquires no resources implicitly.

`StreamingExportSummary` contains only body-row count, physical-page count, source-cell/code-point counts, output lines, top-level drawing commands, diagnostics, and output bytes. Source counts include headers once; output-line and diagnostic counts include repeated headers. Bytes are those actually submitted to the target stream, not backend temporary-disk usage. Diagnostics include FONT_FALLBACK/INFO and MISSING_GLYPH_REPLACED/WARNING and are delivered synchronously to the callback, without retaining an unbounded list. The application owns memory retained by its callback.

## Budgets, failures, and ownership

The default layout budgets allow 10,000 source rows per table (including headers) and 1,000 physical pages, with separate limits on source cells, source text, and output lines. Set them on the streaming request with `limits`. They are independent of the engine's `layoutLimits`. This example uses `allowTableSize` and `documentLimits` from the current `develop` version to configure the table above for 100,000 short body rows.

~~~java
var result = engine.export(spec, rows)
        .limits(limits -> limits.allowTableSize(spec, 100_000)
                .documentLimits(layout -> layout.maxPages(4_000)
                        .maxLines(1_000_000).maxCodePoints(4_000_000))
                .maxDiagnostics(100_000))
        .backend(new PdfSpoolBackend(temporaryParent))
        .to(Path.of("orders.pdf"));
~~~

`allowTableSize` raises the source row, column, and grid slot limits based on the headers, body row count, and fixed columns. The example needs 100,001 source rows and 300,003 grid slots. `documentLimits` adjusts specific settings in the current budget. Choose text, output line, page, diagnostic, backend, and execution budgets based on the actual data, font size, and deployment resources. Font fallback in a repeated header counts toward the diagnostic budget each time the header is rendered.

With 0.1.0-rc.3, build the budget with `DocumentLayoutLimits.builder()` and pass it to `limits.document(layout)`. For the example above, set `maxTableRows(100_001)` and `maxCells(300_003)` along with the page, line, and code point limits shown.

StreamingTableLimits combines DocumentLayoutLimits with per-cell/per-row code-point and run limits, accumulated diagnostics, actual field presentation, and generated-value limits. `maxCellInlines` and `maxRowInlines` default to 4,096 and 16,384, including empty runs. An ordinary cell without explicit runs is charged as one effective run. Source headers and body share table row/cell/code-point budgets. Repeated headers consume output-line and diagnostic budgets without consuming source-run budgets again. The specification contains no paragraph blocks, so maxParagraphs does not apply to this path. `PdfSpoolBackend` uses `PdfLimits` for single-page work and `PdfSpoolLimits` for bounds such as total pages, commands, temporary work bytes, and output bytes. `ExecutionOptions` controls cancellation, timeouts, and the request's output-byte limit.

When the total row/page count is unknown, `Progress.total()` is -1. Check `hasKnownTotal()` before calculating a percentage. Source reads, callbacks, and custom backends are cooperative synchronous calls; blocking code cannot be forcibly preempted.

Cell errors use DocumentLayoutException and include table ID, physical page, source row/column, and the UTF-16 offset within the cell. Row indexes include preceding headers. In the current development version, layout budget errors give the setting name, attempted count, and limit, for example `maxTableRows exceeded (actual=10001, limit=10000)`. Diagnostics use ContextualDiagnostic, with STREAMING_TABLE as the source-text region. Generated field values use `INLINE_FIELD:<original field UTF16 position>`, with diagnostic ranges relative to the generated value. A diagnostic/page-delivery failure immediately stops source reads. The default path may have read at most one row ahead; explicit vertical spans may have read one budget-limited connected group and do not read the next independent group. Original IOException values are preserved; close exceptions follow try-with-resources suppression order.

The stream entry point borrows the target stream without closing it; failure may leave partial bytes. The path entry point requires an existing parent directory and filesystem support for atomic replacement in that directory. Any read, layout, close, output, cancellation, or byte-budget failure preserves the old target and removes temporary files, without falling back to copying.

## Page-by-page extensions and limits

Advanced callers can use StreamingTableLayouter.layout directly to deliver LayoutPage synchronously to PageConsumer; the core closes neither the source nor the consumer. DocumentPainter.paintPage converts it to DisplayPage for PageWriter.accept. Call finish explicitly, then close; close does not finish output. Failed sessions cannot accept further pages. The SPI does not retain supplied pages, but format objects can grow with page count.

The default mode on this page supports plain-text cells, InlineText rich text, and explicit PNG/JPEG images, preserving the explicit FontGroup's strict missing-glyph policy or replacement diagnostics. DocumentEngine.Builder.fontRole registers actual font variants; advanced layout uses the overload accepting a role Map, while the older overload retains an empty mapping. Passive text/image external links pass through the per-page link channel; see the [media API (Chinese)](../streaming-media-api.md). PAGE and PAGES with an accurately declared total are supported; see the [fields API (Chinese)](../streaming-fields-api.md). Body vertical spans require explicit `allowRowSpans(true)`; see the [bounded vertical-span API (Chinese)](../streaming-row-spans-api.md). The default mode does not combine them with row splitting and still rejects header vertical spans, block cells, automatic widths, and nesting. Configured page templates, navigation, and annotations are also rejected to prevent silent omission. Unknown total pages, contents/future references, group footnotes, and automatic widths use the explicit replay path. Columns/balancing, DOCX, and automatic signing are not thereby connected to the streaming specification.

The `PdfSpoolBackend` used above releases PDF objects after each page. With ordinary `PdfBackend`, `TEMP_FILE` moves stream storage to disk while the page tree and COS objects remain in memory. See the [bounded semantic long-table and replay API](streaming-replay-api.md) for recursive semantic cells, vertical spans continuing across pages, automatic column width probing, unknown total pages, and forward references.

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
