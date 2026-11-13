# Bounded semantic long tables, replay, and page-by-page PDF

`DocumentEngine.exportSemantic` reads a semantic row source once, supporting `Paragraph`/`Table` block cells, recursive nesting, and vertical owners split at complete lines. `exportReplay` adds explicit snapshot replay to the same bounded layout, allowing unknown total pages, future references, contents, bookmarks, and group footnotes. `PdfSpoolBackend` confines PDF objects to a single-page working set and completes the file using disk indexes.

Existing ordinary immutable documents can use [`exportPaged`](paged-document-api.md) for page-by-page planning and output. That entry point retains the complete source model, with different ownership from the external row sources in this guide.

## Reading a semantic row source once

```java
var spec = StreamingTable.builder("orders")
        .columns(80, 240, 120)
        .header("批次", "内容", "金额")
        .semanticCells(true)
        .allowRowSpans(true)
        .allowRowSplit(true)
        .build();
Path temporaryParent = Files.createDirectories(Path.of("work"));
engine.exportSemantic(spec, () -> openOrderSnapshotRows())
        .limits(limits -> limits.maxBufferedRows(32).maxBufferedCodePoints(100_000))
        .backend(new PdfSpoolBackend(temporaryParent))
        .to(Path.of("orders.pdf"));
```

`engine` uses an explicit font group covering both source text and generated digits. The application supplies `openOrderSnapshotRows()`, returning a newly owned `TableRowSource` each time. Cells use the existing `TableCell.builder().blocks(...)`; subtree rules match ordinary semantic tables, with at most 16 table levels. Each level controls row splitting independently; atomic subtrees still fail explicitly when they cannot fit. Body column/row spans, actual physical columns, original UTF-16 positions, child-block indexes, inline media, and external links are preserved.

A single-pass source supports actual `PAGE`. `PAGES` requires an accurate `expectedPages(n)`, checked against the actual total before completion; use `exportReplay` when it is unknown. The source opens only once and closes before the backend finishes. Headers repeated after a page break count only against actual output budgets; later independent groups neither insert extra headers nor force a new page.

## Explicit replay and automatic widths

```java
var replay = StreamingReplayPlan.builder()
        .columns(List.of(new ColumnWidth.Auto(80, 120),
                         new ColumnWidth.Fraction(1),
                         new ColumnWidth.Fixed(80)), 64, 1_000_000)
        .rowTarget("summary", 120, "121")
        .blockTarget("details", "details-paragraph", "1")
        .bookmark("明细", "details", 1)
        .bookmark("汇总", "summary", 1)
        .contents("目录 / Contents")
        .footnote("price-note", 0, "金额按本次快照计算。")
        .build();
engine.exportReplay(spec, () -> openOrderSnapshotRows())
        .plan(replay)
        .backend(new PdfSpoolBackend(temporaryParent))
        .to(Path.of("orders.pdf"));
```

Without automatic-width probing, the source opens twice: the first pass determines pagination and declared targets; the second writes output. Configuring `columns` adds an explicit prefix-probing pass, limited by the stated body-row count and canonical serialized bytes. Probing must include complete vertical connected groups and cannot exceed the limits to finish a group. For an empty body, widths can be calculated from headers and explicit minimums. The resulting point widths are fixed: subsequent content wraps to them without readjusting earlier pages. The probing column count must match the specification.

The row-source factory must reopen the same business snapshot. Use a database snapshot or an application-owned stable file rather than querying changing data again. The library compares the probe prefix and complete semantic SHA-256 digests, including text, formatting, runs, spans, block trees, and image content identifiers; any change fails the export. Factory calls, reads, and closes are synchronous, and every pass closes before the next begins. There are no automatically created database transactions, implicit file reads, or complete-body caches.

`rowTarget` uses zero-based body physical-row indexes, excluding headers. A target in a fully covered row points to the covering owner's first presentation. `blockTarget` points to the first fragment of the specified semantic child block. `REFERENCE_PAGE`/`REFERENCE_NUMBER` fields use declared target IDs; the target's `number` supplies numbering text. Undeclared or absent targets, missing numbers, and duplicate source-block IDs fail explicitly. A field still occupies one U+FFFC in the source, with a fixed point-sized reserved box. Resolution does not change line boundaries; values that do not fit fail.

`PAGES` uses the final physical total including contents pages; contents, bookmarks, and reference targets receive a consistent offset. Optional `expectedPages` also checks the final total. Contents are generated only from a finite set of explicit bookmarks, without scanning and caching the entire body to infer headings.

## Group footnotes and budgets

`footnote(id, bodyRow, text)` anchors to the first body row of a vertical connected group. It reserves room for the complete note on the group's first presentation page and renders it once. An ordinary independent row is a one-row group. Source text can explicitly contain a `REFERENCE_NUMBER` field targeting the footnote ID to show a generated `[n]`; the application supplies its reserved width. Footnotes cannot implicitly anchor to later rows within a group and are not silently moved to its beginning: such declarations fail. A note too tall to leave body space also fails, without clipping or shrinking.

`StreamingTableLimits` bounds code points and runs per leaf/source row, connected-group rows/code points, all source nodes/covered slots/code points, physical pages, output lines, fields, generated text, links, and diagnostics. Real anchors count as source only once; nested nodes count against `maxParagraphs`, while repeated headers count against actual presentation. Contents text, footnotes, and field values share the generated-text budget; rendered footnote/contents lines also consume the total output-line budget. Diagnostics are delivered synchronously without an unbounded warning list; nested paths and source UTF-16 positions are preserved.

Cross-group uniqueness checks retain seen semantic-block IDs, with their count bounded by `maxParagraphs`. This does not retain the whole body, but it is also outside the single-page object graph. Large numbers of independent child blocks require memory planned for actual ID counts and lengths; ordinary-row capacity examples do not imply identical memory use for arbitrary semantic trees.

## PDF objects and temporary disk

`PdfSpoolBackend` retains only one rendered page's PDFBox object graph at a time and releases it after acceptance. Xref and page indexes live in a unique owned temporary directory; each page-tree leaf retains at most 256 page references. The renderer first saves to a bounded discard stream to finish font subsetting and measure page bytes, then copies live page objects directly, without creating or reparsing intermediate PDFs. `PdfSpoolLimits` separately bounds pages, cumulative commands, per-page/total objects, serialized bytes per page, temporary work, and output bytes. Navigation declarations are finite lists; future page objects are reserved in the disk index. Format objects do not grow by retaining every PDFBox page.

Font resources are generated independently per page. Already written font objects are reused when their complete object graphs are equivalent. Comparison includes font programs, subsets, encodings, Unicode mappings, widths, and all dictionary properties; it does not merge by font name. A session retains at most 256 SHA-256 fingerprints and output-object numbers, without retaining PDFBox documents, PDF fonts, or COS objects across pages. Resources encountered again after eviction may be embedded again. Per-page source-object budgets still charge the complete reused font graph; total-object and output-byte budgets charge actual output.

To reduce repeated parsing across pages, a session also retains at most 8 FontBox TrueType font programs, weighted by a combined 32 MiB of source bytes. Parsed tables can occupy more memory than this source-byte weight, so the limit is not an exact heap or process-memory bound. Programs borrowed by the current page remain pinned until its PDF document closes; eviction closes only unborrowed programs. If a single program exceeds the limit or the current page fills the cache, parsing continues with a program independently owned and closed by that page, without expanding the cache. The cache is not shared across sessions and is released when the session closes.

Horizontal TrueType fonts permitting subset embedding can also reuse finalized subset snapshots. A hit requires identical font sources, subset permissions, and all Unicode/original-glyph-ID mappings on the page. Each page independently rebuilds font dictionaries and streams and still saves and measures accurate page bytes. The cache has separate entry, glyph-count, and byte-weight limits. Oversized or unknown structures use the original generation path; no PDFBox fonts or documents are retained across pages, and object, output, and cancellation checks still apply.

Images likewise reuse complete equivalent object graphs. Dimensions, color spaces, transparency masks, dictionary properties, and encoded streams all participate in fingerprints. The separate image index is limited to 256 fingerprints/object numbers and released on close; it stores only those values. A sharing hit still charges the complete source graph against the per-page object budget. Eviction reduces reuse opportunities without changing image content.

A session also reuses generated simple lossless images and transparency masks in a cache of at most 64 entries and 32 MiB of compressed-stream bytes, avoiding repeated PNG compression on each page. It retains only owned descriptive values and compressed bytes, rebuilding independent PDF image objects per page without cross-page PDFBox/COS objects. Reuse is limited to fully describable DeviceRGB/DeviceGray, 8-bit Flate images. Unknown properties/structures and oversized resources use the original generation path. Source-image, page-object, intermediate-file, and output budgets are checked every time; closing the session clears the cache.

Ordinary PDF readers still receive standard PDF 1.7 objects and xref. This backend does not automatically produce PDF/A/PDF/UA, encryption, or signatures; use their dedicated configuration and verification procedures. Different subsets and evicted fonts can still be embedded repeatedly, so file sizes are not guaranteed to match the ordinary backend. More declared resources, concurrent sessions, or higher budgets increase actual memory; finite-heap examples do not establish fixed process memory for arbitrary inputs.

`PdfSpoolLimits.maxTemporaryBytes` conservatively charges the serialized-equivalent size of the current page plus disk indexes, retaining the original admission boundary; current-page bytes are not actually written to disk. This budget excludes the complete atomic target being written by the path entry point, which is controlled by `maxOutputBytes` and execution byte budgets. Capacity sampling includes both actual indexes and the atomic target in temporary-disk measurements. Applications must also reserve space for the old target and final new file; the index budget is not the whole export's disk requirement.

A backend must accept `PageNavigation` to retain internal links and bookmarks for contents/future references. Unsupported writers explicitly reject nonempty navigation by default. Ordinary `PdfBackend` remains available for existing configurations; its accumulating document-object behavior does not become bounded automatically.

The temporary parent directory must exist and belong to the application. The export releases its sources, writer, and indexes; the target stream is always borrowed. Path output uses atomic replacement in the same directory. Layout, input-change, close, budget, cancellation, and output failures preserve the old target and clean owned temporary items. Failure on a borrowed stream may leave written bytes; applications should treat whole-export success as the commit condition. There are no automatic retries.

`StreamingTable` remains a dedicated streaming specification and is not serialized as an ordinary `Block` for XML, DOCX, or model recovery. Repeated headers still do not support vertical spans. Configured global page templates and complete-document navigation/annotations must be expressed through this entry point's explicit plan instead.

The reproducible consumer task is `streamingReplay`; independent structure and dual-renderer checks use `python scripts/verify_streaming_replay.py --consumer-build-directory <current-build-directory>`. Capacity and repeated-exit checks use `scripts/measure_streaming_replay.ps1`, followed by `scripts/verify_streaming_replay_capacity.py` to check every row and sampled renders. See [ADR-0102 (Chinese)](../adr/0102-bounded-semantic-streaming-replay-and-pdf-spool.md) for the design.

The complete implementation, combined 11-page example, two independent capacity rounds, and sampling limits are described in [phase 102 verification (Chinese)](../verification/phase-102-semantic-streaming-replay-and-pdf-spool.md). Reports distinguish sampled heap usage, process working set, private bytes, handles, and temporary disk. The 100,000-row example proves only that fixed font/input size; cross-platform and indefinite-running verification were not performed.
