# Fluent builder API

Report structure can also be written as [declarative XML templates](xml-template-api.md). `XmlTemplate.builder()` fluently configures pages, default formats, resource allowlists and two layers of budgets, then reuses the existing semantic-template and export pipeline after compilation.

Public authoring/configuration entry points consistently use `builder()`, named setters and `build()`. Nested structures use synchronous configuration callbacks. Use `Document.builder()` for ordinary semantic reports and `Documents.builder()` for mixed component reports; both ultimately produce the same `Document`.

See [`AdrFormExample`](../../examples/src/main/java/com/mirakyux/export4j/examples/AdrFormExample.java) for a complete dense form. All lengths in this guide are points; pages and components use a top-left origin. Callers still supply fonts, resources and backends explicitly.

## Authoring structure

~~~java
var document = Documents.builder()
        .title("检查报告")
        .pageSettings(page -> page.size(595.3, 841.9).margins(36))
        .heading("基本信息", 1)
        .column(column -> column.gap(8)
                .text("病例编号：DEMO-001")
                .grid(table -> table.columns(1, 2, 2)
                        .defaults(cell -> cell.padding(4))
                        .row(row -> row.minHeight(32)
                                .cell("患者", cell -> cell.rowSpan(2))
                                .cells("姓名", "年龄"))
                        .row(row -> row.cells("示例患者", "75 岁"))))
        .pageBreak()
        .paragraph("补充说明。")
        .build();
~~~

Nested callbacks execute on the current thread. Callers need not calculate cell start rows/columns or assign block IDs to ordinary components. Explicitly name referenced components with `.component("patient", component)`. Low-level extensions can obtain an automatic ID through `Document.Builder.block(id -> ...)`; the factory must retain the received ID.

`Documents.Builder` is a component-layer convenience entry point, not another document type, and adds no component dependency to the semantic model. Advanced semantic values can still be added with `.add(block)`. Existing pagination, table and mixed-content traversal contracts remain.

Inside a column, `.text()` continues naturally by complete lines and configures a `FlowText.Builder`; use `.fixedText()` for minimum height or atomic movement. `.grid()` explicitly denotes a merged component table. The old column `.table()` remains compatible, while top-level `.table()` still denotes a semantic table. See the [continuation guide (Chinese)](../grid-continuation-api.md) for migration and boundaries.

Static forms have direct `.formText(...)`, `.choices(...)`, `.labelValue(...)` and `.fillLine(...)` entry points. Markers are vectors and do not require checkbox glyphs in the font. See the [static-form guide](static-forms-api.md) for authoring and continuation boundaries. `Documents.bodyFormat` provides layout defaults for subsequently added components: unset nested properties inherit, and explicit styles take precedence. See the [style-default guide](style-defaults-api.md) for local scopes, snapshots and complete-style overrides.

Text, static-form and `StyleScope` builders support `.fontSize(14)`, `.lineHeight(24)`, `.firstLineIndent(0)`, `.subsequentLineIndent(0)`, `.alignment(...)` and `.lineBreakPolicy(...)`, each overriding only its property. `GridTable.CellBuilder` has the same named setters. Per-property restoration such as `.inheritFontSize()` clears only that declaration. Old `.style(value/callback)` and cell `.textStyle(value)` remain complete explicit styles; later named changes do not automatically restore other properties' inheritance. Changing size alone does not adjust an inherited fixed line height.

Grid rows can embed semantic content with `.semanticTable(table, cell -> cell.rowSpan(2))`. Final fields, image links and provenance continue with long merged cells. The outer grid and inner Table have independent split switches; see the [semantic-cell guide (Chinese)](../grid-semantic-flow-api.md).

Column `.semanticTable(table)` retains the same semantic cells and final fields/links as a top-level table, with region continuation, per-region headers and explicit complete-line splitting. Component grids still use `.grid(...)`. Fixed boxes still require one viewport. Component compositions have no native DOCX mapping; DOCX nested tables use ordinary semantic `Table` values. See [semantic composition](semantic-components-api.md) and the [block-cell API (Chinese)](../block-cells-api.md).

## Tables

Semantic tables retain rectangular cells, provenance and DOCX mappings:

~~~java
var document = Document.builder()
        .bodyFormat(format -> format.fontSize(11).lineHeight(17))
        .table(table -> table
                .header("指标", "数值")
                .row("收入", "120")
                .format(format -> format.padding(5).allowRowSplit(true)))
        .build();
~~~

Without explicit column widths, the first row determines equal proportional columns. `.columns(ColumnWidth...)` sets fixed, proportional or bounded automatic widths. `.row(TableCell...)` accepts rich text, images or fields. `.header(...)` must precede body rows, and at least one body row must remain.

Use TableCell.builder().columnSpan(n), or mixed(...).toBuilder().columnSpan(n), with Table.Builder.headerCells(...) / row(...) for semantic column spans; see the [column-span API](column-spans-api.md). DOCX writes standard gridSpan. Ordinary inline cells may span bounded Auto columns; a table containing block cells still rejects Auto columns of its own. See [automatic merged columns (Chinese)](../automatic-merged-columns-api.md).

Semantic row spans use `.rowSpan(n)`. Fully covered body/header rows use `.coveredRow()` / `.coveredHeaderRow()`, and new anchors automatically skip slots occupied from earlier rows. Query the two-dimensional grid with `table.grid()`. Vertically connected groups paginate atomically by default; explicit `allowRowSplit(true)` continues content from original owners. Standard DOCX vMerge and static XML share the same model. See the [row-span API](row-spans-api.md).

Use `GridTable.Builder` for component tables with row/column spans:

- `.columns(double...)` sets relative weights, defaulting to one column. `.row(...)` declares one physical row.
- `.cell(...)` places cells left-to-right in the current row, skipping earlier row spans. `.rowSpan(...)` / `.columnSpan(...)` declare spans.
- `.cells(String...)` is shorthand for consecutive ordinary text cells.
- `.defaults(...)` snapshots defaults for subsequent rows; individual cells override them.
- `.minHeight(...)` sets a row minimum while allowing content to increase it. Cell `.verticalAlignment(...)` uses the final row height.
- `.headerRows(...)` sets repeated-header count. Every covered physical row must still be declared with `.row(...)`; an empty callback may represent a fully occupied row.

Default cells have 12 pt text, 4 pt padding, white backgrounds, black 0.5 pt borders and top alignment. Set body text with `.textStyle(...)` and `.textColor(...)`. `.cell(component, ...)` inserts a custom component. `.textFactory(...)` replaces the standard text renderer entirely; its font, color, diagnostics and thread safety are the custom implementation's responsibility.

Empty slots, bounds violations, overlapping spans, spans crossing the header boundary and invalid dimensions fail explicitly rather than being padded automatically. Row heights first satisfy single-row constraints, then merged constraints in fixed span/row/column order. Each child is measured once. Geometry for the same cell set no longer depends on declaration order; drawing still retains input order.

Connected row groups paginate atomically by default, and oversized groups fail explicitly. `.allowRowSplit(true)` lets standard text cells and supported mixed flows continue across pages/columns by complete lines. Images and fixed components remain atomic. See the [continuation guide (Chinese)](../grid-continuation-api.md) for the full contract.

## Page regions and export

~~~java
var fonts = FontGroup.builder()
        .font(chineseFace)
        .font(latinFace)
        .missingGlyphPolicy(MissingGlyphPolicy.ERROR)
        .build();
var engine = DocumentEngine.builder(fonts)
        .pageTemplate(template -> template
                .footer(footer -> footer.height(18).gap(6)
                        .content(PageNumberComponent.builder("{page} / {pages}").build())))
        .build();

var result = engine.export(document)
        .backend(PdfBackend.builder()
                .options(options -> options.storage(PdfOptions.Storage.TEMP_FILE))
                .build())
        .options(options -> options
                .timeout(Duration.ofSeconds(30))
                .maxOutputBytes(10_000_000))
        .to(Path.of("report.pdf"));
~~~

Import the corresponding project types and JDK `Duration` / `Path`. `chineseFace` and `latinFace` are caller-loaded physical fonts.

Engine default page templates, navigation and notes apply to short `layout`, `write` and `export` entry points. Without configuration, empty regions/navigation retain old behavior. Advanced `layout` overloads with explicit templates/navigation/notes keep using their arguments. Short sectioned entry points apply the same default template to each section; use existing advanced overloads for different section templates.

`PageBand.content(component)` fills the region viewport without requiring proportional positioning. Backgrounds, foregrounds and complex placement still accept short `Placement` / `Position` values. Page regions are reserved before body pagination; final page numbers are composed afterward.

`export` stores configuration only; layout/output starts at `.to(...)`, and a backend must be explicit. Path output uses same-directory temporary files and atomic replacement. Failure preserves the old file and removes temporary files. The parent must exist; unsupported atomic moves fail explicitly. Stream output borrows the caller's stream and may leave partial bytes on failure. Timeout/cancellation cover synchronous layout/output without background threads.

When full layout results are unnecessary, [`exportPaged(document)`](paged-document-api.md) sends pages through `PageBackend` and returns a lightweight summary. It retains the input model and uses planning/replay for totals, contents and future references. With `PdfSpoolBackend`, completed pages' layout, drawing and PDF-format objects can all be released. The largest individual block and indices such as navigation still have their own memory boundaries.

## File and stream convenience entry points

Ordinary-document, single-pass long-table, semantic long-table and explicit-replay exports accept `Path`, `File`, string paths or `OutputStream`. `String` and `File` only convert to `Path`, preserving atomic output, budgets, cancellation and exception contracts. Parent directories are not created; URLs and system-font resources are not expanded. Streams remain caller-owned and are not closed by export.

```java
engine.export(document).backend(new PdfBackend()).to("output/report.pdf");
engine.export(document).backend(new PdfBackend()).to(new File("output/report.pdf"));
engine.write(document, new PdfBackend(), "output/report.pdf");
```

`DocumentEngine.write` provides these destination types and explicit `ExecutionOptions` overloads for `Document`, `PageDocument` and `SectionedDocument`. `PageDocument` retains declared headers, footers and layers without extracting its body. Conflicts between a configured Java page template and semantic regions remain rejected.

Existing path outputs for HTML/Markdown export, unified conversion, DOCX editing, PDF editing/optimization/form data/image ZIPs, XML template saving and model recovery also accept `String` and `File`. File `PdfAdapter.input` keeps its explicit password parameter. `PdfSpoolBackend` accepts a `Path`, `File` or string temporary-parent directory, which must exist. Low-level PDF standards/signature tools requiring explicit sources/destinations, signing identities or trust configuration retain their named types and `Path` contracts.

Fonts can also be loaded directly from local paths:

```java
var face = TrueTypeFontLoader.load("fonts/regular.ttf");
var bounded = TrueTypeFontLoader.load(Path.of("fonts/regular.ttf"), 8 * 1024 * 1024);
var collectionFace = FontCollectionLoader.load(new File("fonts/family.ttc"), 1);
```

`TrueTypeFontLoader` and `FontCollectionLoader` file entry points read within bounds and close owned streams; borrowed `InputStream` entry points do not close them. Omitting a byte budget uses `TrueTypeFontLoader.DEFAULT_MAX_BYTES` (32 MiB). Collection members remain explicitly zero-indexed. `String` in existing HTML/Markdown readers still means body content.

Import JDK `File`, `Path` and `OutputStream` as shown; font types belong to `com.mirakyux.export4j.font`. See the complete isolated Maven [`IoConvenienceConsumer`](../../verification/consumer/src/main/java/consumer/IoConvenienceConsumer.java), task `ioConvenience`. These entry points are available from `0.1.0-rc.2`; `0.1.0-rc.1` lacks these new overloads.

## Short entry points for common tasks

These APIs are available from `0.1.0-rc.2`, not `0.1.0-rc.1`. Project types come from their corresponding modules. See runnable [`ErgonomicsConsumer`](../../verification/consumer/src/main/java/consumer/ErgonomicsConsumer.java), task `ergonomics`, and its [verification record (Chinese)](../verification/api-ergonomics.md) for scope and reproduction commands.

Direct DOCX read/write accepts `Path`, `File`, string paths and borrowed streams. Reads default to strict policy; explicit loss acceptance returns diagnostics. File output uses same-directory atomic replacement and supports `ExecutionOptions`; its parent must exist.

```java
var docx = new DocxAdapter();
docx.write(document, "report.docx");
var imported = docx.read("report.docx").document();
```

The component-layer `Images` helper reads image files; the model still stores validated resources only. File/stream input reads at most the budget plus one byte, defaulting to `ImageResource.MAX_BYTES` (32 MiB); pixel/format restrictions still apply. Owned file streams close; borrowed streams remain open. `atWidth` and `fit` explicitly derive point dimensions from pixel aspect ratios and allow upscaling. Both `fit` bounds must lie in `(0, 14400]`. Font size, line boxes and available regions remain explicit; layout does not shrink images automatically.

```java
var image = Images.load("assets/logo.png");
var logo = InlineImage.atWidth(image, 72, "公司标志");
var fitted = InlineImage.fit(image, 72, 36, "公司标志");
```

Font groups accept varargs or explicit files loaded through a builder. `FontSet` stores default fonts and named roles for reuse across engines. `bold`, `italic` and `boldItalic` use supplied real groups. Duplicate roles fail; missing variants are not synthesized. A font-file loading failure leaves the builder unchanged.

```java
var regular = FontGroup.builder().fontFile("fonts/regular.ttf").build();
var bold = FontGroup.strict(TrueTypeFontLoader.load("fonts/bold.ttf"));
var fonts = FontSet.builder(regular).bold(bold).build();
var engine = DocumentEngine.builder(fonts).build();
```

`Units.pt/mm/cm/inches` converts finite values to points, allowing negative offsets; actual geometry still validates positive dimensions. `PageSize` provides millimeter/inch factories and idempotent `landscape()` / `portrait()`.

```java
var page = PageSettings.builder()
        .size(PageSize.A4.landscape())
        .margins(Units.mm(20))
        .build();
```

`TableRow.text(...)` creates ordinary text cells with default 12 pt formatting or an explicit `CellFormat`. `TableRowSources.fromIterable` obtains iterators lazily and returns a reopenable factory; `fromIterator` wraps a borrowed iterator as a single-use source. `fromStream` takes ownership of one stream; `fromStreams` owns a newly opened stream on every pass. Owned sources must close, and export releases sources transferred by factories. Adapters do not cache all data; replay still requires the same caller-supplied business snapshot.

```java
var rows = TableRowSources.fromIterable(List.of("A", "B"), value -> TableRow.text(value));
engine.export(spec, rows).backend(new PdfSpoolBackend(temporaryParent)).to("rows.pdf");
```

Unified conversion can call `convertTo(target, destination)` directly, returning a `Result` with actual losses. It is equivalent to `prepare(target).to(destination)`; preparation and output use their existing execution scopes separately. Keep `prepare` when inspecting candidates or preview reports first. DOCX file/stream and HTML/Markdown file/stream conversion sources are read during preparation. HTML/Markdown `read(Path/File/InputStream)` and `readFile(String)` read immediately within bounds; subsequently configured execution options cover parsing only. Text files use strict UTF-8 and reject invalid encodings. `read(String)` and `conversion(String)` always mean body content; string paths use `readFile` and `conversionFile`.

```java
var target = engine.conversionTarget("PDF", new PdfBackend());
var result = docx.conversion(Path.of("input.docx")).convertTo(target, "converted.pdf");
new HtmlImporter().conversionFile("input.html")
        .convertTo(docx.conversionTarget(), "converted.docx");
```

## Coverage

| Layer | Fluent entry points |
| --- | --- |
| Semantic authoring | `Document`, `Table`, `TableCell`, `Section`, `SectionedDocument`, `DocumentTemplate` |
| Component authoring | `Documents`, `FlowColumn`, `RowComponent`, `GridTable` |
| Static forms | `FormText`, `ChoiceGroup`, `CheckMark`, `LabelValue`, `FillLine` |
| Pages and pagination | `PageSettings`, `PageBand`, `PageTemplate`, `SectionTemplate`, `PaginationPolicy` |
| Text and fonts | `ParagraphFormat`, `CellFormat`, `TableFormat`, `InlineStyle`, `ParagraphStyle`, `FontGroup`, `TextComponent`, `FlowText`, `OpenTypeText`, `FontScope` |
| Images and graphics | `InlineImage`, `ImageComponent`, `ShapeComponent`, `LineComponent`, `BoxComponent`, `WatermarkComponent` |
| Layout composition | `ContinuousColumns`, `MultiColumn`, `FloatText`, `MultiFloatText` |
| Page numbers and interaction collections | `InlineField`, `PageNumberComponent`, `TableOfContents`, `Navigation` |
| Offline text templates | `XmlTemplate`, `MarkdownTemplate`; structure remains in template files while Java configures data, explicit resources and output |
| Existing static PDF | Independent `PdfAdapter`, `PdfInput.pages(...)`, `PdfLimits`; page selection/reordering/merging without third-party public types |
| Execution and backends | `DocumentEngine`, `ExecutionOptions`, `PdfBackend`, `PdfOptions`, `PdfLimits`, `PdfEncryption`, `RasterBackend`, `DocxAdapter`, `DocxLimits` |

`DocumentTemplate` offers `.block(...)`, `.when(...)` and `.each(...)`, with nested branch callbacks using `Nodes`. Only the existing safe binding expressions are supported. Dynamic rows use `DocumentTemplate.Rows.builder()`. `Navigation` accumulates bookmarks, comments and fields fluently; text fields, choice fields, radio groups and checkboxes each have builders too. Location targets remain short value objects. `MultiFloatText.Builder.floating(...)` supports nested floating-rectangle configuration. PDF permissions can use `PdfEncryption.Permissions.builder()`. `Section.Builder.blocks(...)` accepts ordinary/component document blocks; IDs must remain globally unique across sections.

Existing simple constructors, factories and `withXxx` methods remain. Short values such as `PageSize`, `PageMargins`, `Point` and `Rect` still use direct construction. Layout results, drawing commands, exceptions and SPIs do not gain builders merely for syntactic uniformity.

## Defaults, snapshots and compatibility

Builders are task-confined; `build()` creates immutable structural snapshots. Collections are defensively copied at setter/build boundaries. Mutating retained row, cell, defaults or branch builders after nested callbacks return does not change content already added to the parent. Custom components, callbacks and fonts remain borrowed under their own thread-safety and resource contracts.

New paragraph/cell formats default to 12/18 pt. Setting font size updates line height to 1.5 times that size until line height is explicitly set; later size changes preserve explicit line height. `toBuilder()` treats the original line height as explicit, so changing size retains it. The engine still rejects fixed line boxes that cannot fit fonts/images.

`GridTable` gained `minimumRowHeights`, and `TextComponent` gained `minimumHeight`. Old constructor signatures remain with zero minima. Record reflection components and string representations include these properties. The `GridTable` row-height ordering fix may reduce excess allocation in old calls and therefore change pagination; the original change did not refresh existing visual baselines.

`0.1.0-rc.2` is on Maven Central; no stable release exists yet. File/stream conveniences and common-task short forms identified here are available from that version; see [RC2 notes (Chinese)](../releases/0.1.0-rc.2.md). Isolated Maven consumption and formal publication are recorded separately. DSLs, footers, budgets, temporary PDF storage and atomic output retain their own contracts.

See [XML](xml-template-api.md) and [Markdown](markdown-template-api.md) for templates. Static `read` / `copy` / `merge` in the [PDF adapter](pdf-adapter-api.md) do not recover semantic models. Explicitly use `importDocument(PdfInput)` for lossy geometric recovery, configuring the subset and loss policy from the [semantic recovery guide](pdf-semantic-import-api.md); no OCR is performed. The adapter's PdfLimits lives in `adapter.pdf` and differs from the PDF output backend's same-named limit type. Import the type for the entry point used.

## Single-pass long-table export

`StreamingTable.builder(id)` configures fixed columns, bounded headers, page settings and padding without accepting a body collection. `engine.export(spec, sourceFactory).backend(pageBackend).limits(...).diagnostics(...).options(...).to(target)` opens a single-use source only during output and returns flat counts. Applications implement `TableRowSource.next()` and their resource cleanup.

The source closes before finish; borrowed destination streams stay open. Path failures preserve old targets. Named options snapshot at to; reentrant changes affect the next export only. The ordinary single-pass path supports fixed-column plain/rich text, explicit images/passive links, horizontal spans and known-page-count fields. Vertical spans explicitly opt into bounded atomic connected groups and cannot combine with splitting on that path. Recursive cells, cross-page owners, unknown totals and future references use the [semantic/replay entry points](streaming-replay-api.md), without automatically gaining DOCX support. Ordinary `PdfBackend` accumulates its page tree; `PdfSpoolBackend` can release each page's object graph. See the [long-table guide](streaming-table-api.md) for budgets/resources and [phase 24 (Chinese)](../verification/phase-24-streaming-tables.md) for the original plain-text path's historical evidence.

Semantic and component documents both support `.theme(DocumentTheme)`. Complete format values hold theme definitions while body chains preserve heading/paragraph/table structure. Named lookups return existing immutable ParagraphFormat/InlineStyle values, avoiding repeated per-component parameters. See the [theme guide](themes-api.md) for precedence.

Components can select a complete registered group with `FontRoleScope.builder(content).fontRole(name)`. Fixed rich text uses `RichTextComponent.builder().add(text, inlineStyle)`. Each InlineStyle is complete and explicit; paragraph inheritance controls geometry only. See [component text](component-text-api.md).

`NumberedItem` / `BulletItem` have named builders, `.markerStyle(value/callback)` and `.defaultMarkerStyle()`. Marker/body configuration is separate, and `toBuilder` retains resolved values; see [list markers (Chinese)](../list-markers-api.md). XML independently configures role/color through `.bodyText(...)`, `.headingText(level, ...)` and `.cellText(...)`, regardless of geometry call order; see [XML defaults (Chinese)](../xml-text-defaults-api.md).
