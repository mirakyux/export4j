# HTML and Markdown export

`export4j-adapter-web` directly converts `Document`, `PageDocument` or `SectionedDocument` to HTML5 or Markdown. Both are UTF-8, requiring no browser, font files or third-party Java runtime dependencies. Browsers/Markdown readers handle display and wrapping; the adapter neither reuses PDF coordinates nor promises identical pagination.

This direction is independent of [Markdown template input](markdown-template-api.md). Exported GFM tables, images and safely generated tags are not guaranteed to reimport through strict template syntax. General document input uses the [Markdown importer](markdown-import-api.md) or [offline HTML/CSS importer](html-import-api.md); see [unified conversion](document-conversion-api.md) for composition and full loss reports. See [ADR-0050 (Chinese)](../adr/0050-semantic-web-exports.md), [WebExportsExample](../../examples/src/main/java/com/mirakyux/export4j/examples/WebExportsExample.java) and [phase 43 (Chinese)](../verification/phase-43-web-exports.md).

## Dependency and usage

Align versions with published Maven Central `com.mirakyux.export4j:export4j-bom:0.1.0-rc.2`, then add `com.mirakyux.export4j:export4j-adapter-web`. This is a release candidate, not a stable release. See [publication](releasing.md) for build, signing and remote isolated-consumer evidence.

```java
import com.mirakyux.export4j.adapter.web.ExportFeature;
import com.mirakyux.export4j.adapter.web.ExportPolicy;
import com.mirakyux.export4j.adapter.web.HtmlAdapter;
import com.mirakyux.export4j.adapter.web.MarkdownAdapter;
import com.mirakyux.export4j.adapter.web.UnsupportedAction;
import com.mirakyux.export4j.model.Document;
import java.nio.file.Path;
import java.util.List;

var document = Document.builder()
        .heading("Quarterly report / 季度报告", 1)
        .paragraph("Text, links and table content remain searchable.")
        .table(List.of("Metric", "Value"), List.of(List.of("Revenue", "120")))
        .build();

var html = new HtmlAdapter().export(document).to(Path.of("report.html"));
var policy = ExportPolicy.builder()
        .defaultAction(UnsupportedAction.ERROR)
        .on(ExportFeature.TABLE_STRUCTURE, UnsupportedAction.WARN)
        .on(ExportFeature.TABLE_GEOMETRY, UnsupportedAction.IGNORE)
        .build();
var markdown = MarkdownAdapter.builder().policy(policy).build()
        .export(document).to(Path.of("report.md"));
// markdown.diagnostics() 返回 WARN 的结构化诊断，由应用决定如何展示或记录。
```

Place this code in a method that can throw `IOException`. File parents must exist. `to(OutputStream)` borrows without closing or flushing. `ExportResult.bytesWritten()` is the actual UTF-8 byte count; `diagnostics()` is immutable. Adapters, policies and requests are immutable and reusable concurrently when their callbacks are safe; builders are confined to the current thread.

## Actions for unsupported content

All features default to `ERROR`. `ExportPolicy.builder().defaultAction(action)` sets the default; `.on(feature, action)` overrides a feature. Policies control content the format cannot express, not invalid input, execution failures or I/O.

| Action | Observable behavior |
| --- | --- |
| `ERROR` | Throws `ExportException` with `code()` `UNSUPPORTED`; all semantic checks and complete UTF-8 assembly finish before writing to caller streams, and old path targets remain unchanged |
| `WARN` | Applies the explicit degradation below and continues; returns `ExportDiagnostic` for every encountered loss location without printing to standard output |
| `IGNORE` | Uses the same degraded content as WARN without adding diagnostics for that loss |

Exceeding diagnostic limits fails rather than switching to IGNORE. Invalid Unicode/control characters, missing list parents, numbering overflow, budgets, cancellation, timeout and write failures always report errors. `ExportException.Code` distinguishes `UNSUPPORTED`, `LIMIT_EXCEEDED`, `INVALID_TEXT` and `INVALID_NUMBERING`. Execution control separately throws model `ExecutionException`; I/O preserves original exceptions and causes.

Diagnostics include feature key, section index, `BODY/HEADER/FOOTER/DOCUMENT` region, original root-block index, block ID, physical cell path and UTF-16 offset. Indices are zero-based; `-1` means not applicable. Messages do not embed body text, URLs or file paths. IDs are caller data and should follow the application's logging policy.

## Support and degradation matrix

Content without loss exports directly. Every degradation below requires WARN or IGNORE authorization.

| Feature/content | HTML | Markdown |
| --- | --- | --- |
| Headings, paragraphs, ordinary text | h1–h6/p, preserving source text | ATX headings/paragraphs, escaping Markdown and HTML metacharacters |
| Text/image links | Standard a, preserving explicit passive links | Standard links, preserving existing percent encoding and syntax-encoding delimiters in destinations |
| `METADATA` | Standard title/author | Degrades by omitting metadata without duplicating it into the body |
| `PAGE_GEOMETRY` | Default A4 means Web-flow defaults; other paper/margins degrade to flow defaults | Same; no physical-paper geometry |
| `PAGE_NUMBERING` | Nonzero starting display numbers degrade by omitting numbering settings | Same |
| `PAGE_REGIONS` | Degrades to one header/body/footer sequence per section, without fixed or repeated regions | Degrades to retaining region content once in original order per section |
| `PAGE_BREAK` | hr and print-page-break hint; browser decides pagination | Degrades to a thematic break |
| `INLINE_FIELD` | Degrades to `[unresolved KIND]`, without fabricated final page/reference values | Same |
| `CAPTION` | Degrades to unnumbered caption text | Same |
| `CROSS_REFERENCE` | Degrades to the prefix plus `[unresolved reference]` | Same |
| `CUSTOM_BLOCK` | Degrades by omitting the block; does not run layout/drawing or call toString | Same; drawing components cannot recover semantic content |
| `FONT_ROLE` | Explicit FontStyle maps to CSS; unmapped roles degrade to default styles | Maps bold/italic semantics; unmapped roles degrade to ordinary text |
| `TEXT_STYLE` | Size, color, baseline shift, underline and strikethrough map to CSS | Retains strikethrough and mapped emphasis; omits size, color, underline and baseline shift as degradation |
| `PARAGRAPH_STYLE` | Alignment, indents, spacing, fixed line height, pagination constraints and CJK breaking map to CSS hints | Accepts default body/corresponding heading levels; nondefault paragraph/cell geometry degrades to ordinary flow |
| `LIST_MARKER` | Retains explicit markers, level indentation and independent marker styles | Level-one default bullets/decimal numbers are native lists; restart numbering uses empty-comment separators. Custom/multilevel or over-nine-digit native numbers degrade to explicit marker text; independent marker styles degrade to defaults |
| Tables and `TABLE_STRUCTURE` | Native colspan/rowspan, thead, real anchors and block-cell nested tables; the initial export batch covered two table levels | Rectangular GFM tables with one header row; no-header tables gain an empty header, later header rows become body rows. Merged/block cells degrade to each anchor's content once, empty covered slots, and inner-table text expanded in row/column order |
| `TABLE_GEOMETRY` | Fixed/proportional/Auto widths, padding and row-pagination constraints map to CSS hints | Equal-weight columns/default formats work directly; other widths, padding and pagination degrade by omission, with inner tables checked too |
| `IMAGE_GEOMETRY` | Embeds original PNG/JPEG bytes, retaining size, baseline, alt and links | Images/alt/links remain as data URIs; size and baseline degrade to reader defaults |

HTML CSS provides display hints: browsers determine actual fonts, widths, shaping, CJK breaks and print pagination. System fonts are neither embedded nor scanned; network fonts are not fetched. `FontStyle("Arial", 700, false)` is an explicit Web-family/bold mapping, not the real font bytes used by PDF. Markdown uses only `weight >= 600` and `italic` from the mapping, not the family.

Explicit HTML list markers use `span.marker` with `data-export4j-marker="1"` and `data-export4j-level="1"` through `"9"`. These passive attributes do not change display; the [HTML importer](html-import-api.md) uses them to distinguish markers from bodies accurately. Simple level-one items can preserve markers and independent styles; complex items still follow explicit loss policies. General lossless list round-trips are not promised.

Markdown targets CommonMark plus GFM tables/strikethrough. Fixed `br/strong/em/del` inline tags preserve line breaks and adjacent-style boundaries; source HTML is always escaped. Readers disabling raw HTML may omit these styles; readers disabling data URIs may omit images. Embedded images increase file size; no sidecar resource directory is generated.

## Safety, budgets and execution

HTML is a self-contained static file. Its CSP prohibits scripts/external resources and permits only data images and inline CSS. Body text, metadata, attributes and families are escaped for their respective contexts. Links reuse the model HTTP/HTTPS/mailto allowlist and only emit destinations without visiting them. Markdown also treats source text as plain text rather than executing templates or source HTML. Export scans no files and sends no network requests.

`ExportLimits.builder()` configures these independent budgets:

| Budget | Default maximum |
| --- | --- |
| Sections/semantic blocks/table slots | 1,000 / 20,000 / 200,000 |
| Source code points/inline items | 2,000,000 / 100,000 |
| Unique images/original encoded-image bytes | 1,024 / 64 MiB |
| UTF-8 output/diagnostics | 64 MiB / 10,000 |

Text accounting includes bodies, IDs, roles, markers, URLs, image alt text, field targets and metadata. Real table anchors count once; block-cell descendants still count. Shared region sources are deduplicated by region, while every actual occurrence counts toward output bytes. Images deduplicate original bytes by resource digest, but each emitted data URI is charged separately. Output assembles within bounds in memory first; this is not streaming export, a hard RSS ceiling or a hostile-input sandbox.

Requests support `.execution(ExecutionOptions)`, observing cancellation/timeout with outer synchronous scopes and creating no background threads. Streams are written only after full semantic/size checks. Physical I/O failures or cancellation during writing can still leave partial stream content; discard failed outputs. Paths use same-directory temporary files and atomic replacement, without a non-atomic copy fallback. Failure removes temporary files and preserves old targets.

## Run and verify

```powershell
.\gradlew.bat build sbom publishLocalBundle :examples:webExports
powershell -NoProfile -File scripts/verify_local_consumer.ps1
python scripts/verify_web_exports.py
```

Python must use the project's isolated environment installed from `scripts/requirements-verification.txt`. Independent CommonMark/GFM and HTML parsers check unique content, merged/nested order, numbering restarts, emphasis boundaries, original image bytes, links and diagnostic locations. The script writes a Markdown-rendered preview and report under `build/verification/web-exports`. Linux/macOS use `./gradlew`; see [publication](releasing.md) for isolated-consumer build commands.
