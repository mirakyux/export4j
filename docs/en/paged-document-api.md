# Page-at-a-time document export

`DocumentEngine.exportPaged` delivers layout, drawing and backend output one page at a time and returns `PagedExportSummary`. It is intended for callers that do not need a complete retained layout result. Existing `layout`/`export` still return complete results. Inputs may be `Document`, `PageDocument` or `SectionedDocument`.

This API is available from `0.1.0-rc.2`; `0.1.0-rc.1` does not include it. See [RC2 notes (Chinese)](../releases/0.1.0-rc.2.md) for changes and limitations.

## Usage

```java
import java.nio.file.Files;
import java.nio.file.Path;
import com.mirakyux.export4j.backend.pdf.PdfSpoolBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.model.Document;

// fonts 来自调用方显式加载的字体，document 为不可变语义模型。
var engine = DocumentEngine.builder(fonts).build();
var temporaryParent = Files.createDirectories(Path.of("work"));
var summary = engine.exportPaged(document)
        .backend(new PdfSpoolBackend(temporaryParent))
        .to(Path.of("report.pdf"));
System.out.println(summary.pages());
System.out.println(summary.outputBytes());
```

`to` accepts `Path`, `File`, a string local path or a borrowed `OutputStream`. Paths use a same-directory temporary file and atomic replacement. Planning, replay, cancellation, closing or output failures preserve the old destination. Output streams are not closed and may contain partial bytes after failure. The caller owns an existing temporary parent directory; the backend cleans up its own index directory.

`limits`, `componentLimits` and `options` configure layout, component and execution budgets separately. `diagnostics` delivers contextual diagnostics synchronously; summaries retain counts rather than every warning. Requests snapshot configuration when `to` begins, and custom callbacks run on the calling thread.

For large documents, explicitly configure these independent budgets together with backend page, command and output limits. Default `ComponentLimits` allow 10,000 layout placements, 1,000,000 drawing commands and 10,000 interactions. Raising page limits does not automatically raise them. Budget exhaustion fails explicitly and does not imply an out-of-memory error.

## Supply blocks on demand

When a complete model need not exist in advance, `BlockSource` can supply ordinary blocks incrementally:

```java
import java.util.Optional;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.BlockSource;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;

BlockSource.Factory source = () -> new BlockSource() {
    private int index;
    @Override public Optional<Block> next() {
        if (index == 100) { return Optional.empty(); }
        int number = ++index;
        return Optional.of(new Paragraph("record-" + number,
                "Record " + number, ParagraphFormat.of(10)));
    }
};
var summary = engine.exportReplay(PageSettings.A4, source)
        .backend(new PdfSpoolBackend(temporaryParent))
        .to(Path.of("records.pdf"));
```

`exportReplay` explicitly opens the source twice: the first pass determines pagination and lightweight summaries; the second resolves final page fields and writes immediately. Both must read the same business snapshot. A source factory must not requery changing data. Differences in text, formatting, original provenance or layout fail export. The default source `close` is a no-op; implementations must close owned connections, cursors or files, while callers retain responsibility for borrowed resources.

If the application knows the exact final physical page count, `engine.exportSemantic(settings, source).expectedPages(count)` opens and lays out the source once. `count` must be positive and is checked exactly before completion. Over- and under-declarations both fail; content is not shrunk or blank pages added to meet the declaration. Explicitly choose `exportReplay` when the total is unknown.

Both requests support `metadata`, `limits`, `componentLimits`, `options`, `diagnostics` and all four `to` targets. They inherit the engine's fixed `pageTemplate` and preserve configuration snapshots, synchronous cancellation, source-close-before-`finish`, atomic path replacement and borrowed output-stream ownership.

This path supports ordinary paragraphs, headings, lists, captions, tables, columns, grids, components, images, external links and `PAGE`/`PAGES`, retaining only the current page's sources, the largest block and necessary keep chain. Cross-section settings, contents, future references, footnote rearrangement, comments and editable forms still use complete-model APIs; unsupported declarations fail explicitly.

`BlockSource` releases input by block, but an individual `Table` still contains all its rows. Use [TableRowSource](streaming-replay-api.md) when a huge table must release input by row. Global ID indices and replay page summaries still grow with bounded nodes/pages. Low-level `DocumentLayouter.layoutBlocks` borrows the source and callback and delivers original-source snapshots with every page; callers manage atomic output and source closing.

## Complete-model pagination and semantics

The engine plans before replaying output. Ordinary paragraphs, headings, lists, tables, columns, components, images and links retain existing model/layout rules. Section paper settings, displayed numbering, first/even page regions, total-page fields, contents, bookmarks, future references and footnotes use final pagination. Physical page indices and displayed page numbers remain distinct.

Without footnote rearrangement, body layout generally runs three times: pagination planning, output preflight and actual output. Footnote reservations may add bounded planning rounds. This determines final targets early, checks global budgets and rejects nondeterministic replay while reducing retained completed-page objects, at the cost of more layout/painting work. Continue using `layout`/`export` when complete layout results are needed or small-document latency is the priority.

Model page regions in `PageDocument` conflict with a nonempty Java `pageTemplate`. A sectioned document declaring model page regions likewise cannot also use a nonempty Java template. For explicit selection, use overloads accepting per-section `SectionTemplate`, `Navigation` and `Note` lists. Custom components must support deterministic repeated measurement; planning/replay mismatches fail export.

Comments and editable forms are outside `PageWriter`'s representation; nonempty declarations fail before opening the writer. Use complete `export` for those interactions. Standards and certification support depend on the selected backend; page-at-a-time APIs do not expand them.

## Memory boundaries

Completed-page Layout IR, Display Lists and consumed-block measurement caches are released. Image/border caches are bounded and reused across pages. `exportPaged` still retains the complete immutable input model; block-source APIs read and release input on demand. Both still require memory for the largest block, table/component preparation working sets, keep chains and finite indices. Many source nodes and navigation entries increase complete-model index memory. Footnote plans retain measured text slices and grow with footnote text.

Before shaping footnotes, known semantic-source budgets are checked together: their original text and nodes share `DocumentLayoutLimits.maxCodePoints`/`maxParagraphs` with every section's body and expanded endnotes, cumulatively across sections. Component-internal semantic sources are combined with footnotes when discovered during measurement. Exceeding limits fails explicitly. This bounds footnote input size; it does not make the retained footnote plan constant-memory.

Complete APIs also release consumed-block caches and share fully equal ordinary glyph layouts within bounded caches during one layout. Sharing occurs after measurement and budget charging, preserving full fonts, provenance, geometry, styles and diagnostics. Images, fields and outline layouts do not enter that shared cache. Field resolution retains unchanged pages/fragments and releases replaced assembly references. Complete APIs still return all IR/drawing results, so memory grows with the document.

`PdfBackend` accumulates format objects. `PdfSpoolBackend` copies completed-page objects, closes the single-page PDFBox document and constructs the final file using disk indices. It creates no intermediate single-page PDF file, but retains font-subset finalization and single-page serialized-byte checks. Equal fonts with complete matching Unicode/original-glyph-ID mappings can reuse bounded finalized-subset snapshots, rebuilding independent font objects per page to avoid repeated subset generation. Temporary-work budgets conservatively charge the current page's serialization-equivalent size; full atomic-target bytes have separate output limits.

Database and other one-pass row sources continue to use [exportSemantic/exportReplay](streaming-replay-api.md). Page-at-a-time processing of complete models and row-at-a-time reading of external sources are different input contracts; capacity conclusions require separate verification.

See [ADR-0111 (Chinese)](../adr/0111-page-at-a-time-document-export.md), [ADR-0112 (Chinese)](../adr/0112-incremental-block-source.md) and [page-at-a-time verification (Chinese)](../verification/paged-document-export.md) for implemented scope.
