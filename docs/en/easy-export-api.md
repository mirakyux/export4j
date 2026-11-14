# Configure once, export directly

The development module `export4j-starter` provides the `Export4j` entry point. Declare fonts, global styles, and shared images at application startup. Business calls choose text, Markdown, HTML, or a body XML template and a PDF/DOCX destination. They do not need to assemble an importer, formula processor, layout engine, and DOCX adapter separately. Published `0.1.0-rc.3` does not contain this module.

## One dependency and a minimal call

A single dependency with an explicit version is sufficient for this entry point. `<development-version>` identifies the actual development build:

```kotlin
dependencies {
    implementation("com.mirakyux.export4j:export4j-starter:<development-version>")
}
```

Applications directly using other Export4j modules can also import the matching BOM to align versions. See [verification (Chinese)](../verification/easy-export.md) for both metadata modes with only the starter dependency.

```java
import com.mirakyux.export4j.Export4j;
import com.mirakyux.export4j.FontFamily;
import com.mirakyux.export4j.font.FontSource;
import java.nio.file.Path;

var body = FontFamily.of("Liberation Sans",
        FontSource.file(Path.of("fonts/LiberationSans-Regular.ttf")));
try (var export = Export4j.builder(body).build()) {
    export.markdown("Hello $x^2$.").pdf(Path.of("output/report.pdf"));
    export.text("Literal **text** $x^2$.").docx(Path.of("output/literal.docx"));
}
```

The current entry still reads the declared font sources, so the files must be valid. Create the output parent directory first. PDF uses explicit physical fonts and strictly checks required glyphs. DOCX preserves ordinary body text, list markers, font families, and style declarations without requiring those local fonts to contain every character. Installed fonts and missing-font or missing-glyph handling belong to the reader's system. The library does not scan system fonts or embed fonts in DOCX. A service can reuse the exporter and close it during application shutdown rather than recreate it for every request.

The starter transitively includes the existing mathematical module and its reviewed JLaTeXMath dependency. Applications needing fewer dependencies can continue selecting individual modules. See [dependencies and licenses (Chinese)](../dependencies.md).

## Chinese, Latin, and global style

```java
import com.mirakyux.export4j.model.DocumentTheme;

var latin = FontFamily.builder("Liberation Sans",
        FontSource.file(Path.of("fonts/LiberationSans-Regular.ttf")))
        .bold(FontSource.file(Path.of("fonts/LiberationSans-Bold.ttf")))
        .italic(FontSource.file(Path.of("fonts/LiberationSans-Italic.ttf")))
        .boldItalic(FontSource.file(Path.of("fonts/LiberationSans-BoldItalic.ttf")))
        .build();
var chinese = FontFamily.of("LXGW WenKai TC",
        FontSource.file(Path.of("fonts/LXGWWenKaiTC-Regular.ttf")));
var theme = DocumentTheme.builder().rgb(0x223344)
        .bodyFormat(p -> p.fontSize(12).lineHeight(32).spaceAfter(8))
        .build();

try (var export = Export4j.builder(latin).eastAsia(chinese).theme(theme).build()) {
    export.markdown("中文正文与 formula $x + \\text{年}$.")
            .pdf(Path.of("output/report.pdf"));
    export.markdown("中文正文与 formula $x + \\text{年}$.")
            .docx(Path.of("output/report.docx"));
}
```

The same family declaration supplies the physical body stack, explicit text fonts for formula `\text{...}`, and Word's four script slots. The initial family supplies `ascii`/`hAnsi`; `eastAsia(...)` and `complexScript(...)` select the other slots and otherwise default to the initial family. `fallbackFont(...)` adds another explicit physical candidate without scanning directories or names. It does not rewrite DOCX font slots character by character according to physical glyph coverage.

PDF does not synthesize missing physical bold, italic, or bold italic variants. This example supplies only Chinese regular and does not promise physical Chinese emphasis variants. DOCX writes the font family and bold/italic declarations associated with a registered role; the reader renders them. Registered HTML `font-family` declarations select their specific family and variant roles instead of collapsing all families into the default role. Both targets continue to reject unregistered roles. Named theme roles must match roles actually registered by the instance; arbitrary names are not inferred automatically.

## Business calls and local overrides

Each call creates an independent request. Reuse the exporter; do not share a mutable request between threads:

```java
import com.mirakyux.export4j.model.ContentOptions;

var result = export.markdown(markdownSource)
        .options(ContentOptions.builder().rgb(0x006688).spaceAfter(6).build())
        .pdf(pdfPath);
var count = result.formulas();
var changes = result.degradations();
```

Global `defaults(ContentOptions)` and request `options(ContentOptions)` preserve property declarations and inherit undeclared values. Zero, black, and `false` are valid overrides. Markdown, HTML, and XML receive separate theme defaults for body text, six heading levels, cells, and tables; body styles do not replace independent heading or cell settings. Explicit content properties, XML root/scope/block/inline declarations, and HTML CSS override the applicable defaults through their existing cascade. Undeclared properties continue to inherit. See [source blocks](content-blocks-api.md).

For parser extensions, `ContentOptions.Resolved.styles()` exposes immutable `SemanticStyles` containing six `SemanticStyle` heading values and the cell text appearance. The original eight- and nine-argument constructors and existing accessors remain. Those constructors retain their historical uniform body/heading/cell text appearance; use theme resolution for independent semantic defaults. The additional `styles` component changes the record component list and participates in value equality. Consumers relying on reflective components or a particular record deconstruction shape need to adapt.

| Entry | Default behavior |
| --- | --- |
| `text(source)` | Literal text with formulas disabled; Markdown/HTML syntax remains literal |
| `markdown(source)` | Explicit Markdown with formulas enabled; tables use inherited settings |
| `html(source)` | Explicit offline HTML subset with formulas enabled |
| `xml(template, data)` | Compile and bind a body template; XML/global declarations choose source syntax without guessing data formats |

Raw HTML inside Markdown is a separate switch, disabled by default; explicitly choose `rawHtml(true)` when needed. The `html(...)` entry does not require that switch. Strings are always source content, never file paths or URLs.

Register shared images with global `image(key, InlineImage)` or add/override an exact key on one request. The caller supplies encoded bytes and point dimensions. Links do not cause network or filesystem resource access. Requests also accept `metadata(...)`, `pageSettings(...)`, and `execution(...)` without rebuilding the shared instance.

## XML and data

```java
var xml = """
        <document version="1" content-format="MARKDOWN">
          <p id="greeting">Hello ${name}</p>
          <content id="detail" source="detail"/>
        </document>
        """;
export.xml(xml, java.util.Map.of("name", "Reader", "detail", "Result: $x^2$."))
        .pdf(pdfPath);
```

Java callers can also pass a record or JavaBean instance directly, without a JSON conversion or a manually constructed Map:

```java
record GreetingData(String name, String detail) {}
export.xml(xml, new GreetingData("Reader", "Result: $x^2$."))
        .pdf(pdfPath);
```

The root accepts a Map, record, or JavaBean; nested properties can also contain Lists. They enter the same bounded snapshot at execution. Records expose components and JavaBeans expose public property getters; fields are not read. Every property read must contain the existing finite data types, including properties the template does not reference. Null, dates, enums, arrays, and Sets are not converted automatically. Getters are trusted application code; see [Java objects](xml-template-api.md#java-objects) for access, failures, cycles, and budgets. Existing Map overloads remain available.

`<content source="..."/>` passes an entire field to the selected parser without evaluating `${...}` again inside that content. `fragment(key, xml)` registers an existing safe template fragment without introducing disk includes or external access.

The unified entry currently accepts body XML only. Headers/footers, sections, notes, and other structures not covered by this entry's mathematical projection remain explicit failures; they are not discarded. Use the existing [XML](xml-template-api.md) and [document engine](fluent-api.md) entries for those model capabilities. This entry does not replace every XML capability. Data is borrowed until execution and snapshotted by template binding; do not mutate it concurrently during execution.

## Targets and strict failures

PDF compiles only the visual target; DOCX compiles only the native Word target. A formula drawable in PDF does not fail a PDF request because of an unrelated native Word limitation. Each output execution compiles independently; separate exports do not promise a shared intermediate document.

Formula fallback candidates are empty by default. Unsupported syntax, unreadable font/resource sources, unregistered roles, and unsupported target structures fail; PDF continues to reject missing glyphs. Fonts missing on a DOCX reader's system are outside export-time physical glyph validation. There is no automatic conversion to images or text, and existing budgets are unchanged. Declare image candidates explicitly during startup when needed:

```java
import com.mirakyux.export4j.math.MathContent;

var export = Export4j.builder(latin).eastAsia(chinese)
        .formulaFallbacks(java.util.List.of(MathContent.Fallback.IMAGE))
        .build();
```

`Result.degradations()` reports actual changes by occurrence index, target, candidate, and reason; `formulas()` counts occurrences. The contract prohibits fallback for limits, cancellation, and timeouts. The current development build still misclassifies an aggregate formula text font budget violation, allowing an explicitly configured `TEXT` candidate to consume that failure. The default empty candidate list still rejects it; see [known limitations (Chinese)](../verification/easy-export.md#已知限制). DOCX defaults to standard script font slots and `atLeast` spacing for object paragraphs. Java writes production output directly without Word, LibreOffice, or print services. The supported formula/format subsets remain documented in [mixed content](rich-content-api.md).

## Lifetime, budgets, and output

The instance owns a bounded idle-expiring `FontFaceCache` by default. Inspect or clear it with `cacheStats()`/`clearCache()`. `fontCache(cache)` borrows an external cache and does not close it; its owner must do so. One `FontSource` identity loads at most once per operation. The shared instance retains no short-lived engine or compiled document. Formula text bytes are prepared only when a formula actually needs a text group, rather than eagerly for ordinary literal exports.

Expiration releases cache references without invalidating active fonts or promising an immediate RSS decrease. Caller-supplied byte sources retain their encoded snapshots. See [font caching](font-cache-api.md). Concurrent calls also require font source implementations and execution callbacks suitable for that concurrency.

Structural parsing, mathematical, and layout budgets have defaults. There is no default timeout or effective output byte cap. Configure execution globally or per request:

```java
import com.mirakyux.export4j.model.ExecutionOptions;
import java.time.Duration;

var controls = ExecutionOptions.builder().timeout(Duration.ofSeconds(30))
        .maxOutputBytes(16L * 1024 * 1024).build();
export.markdown(markdownSource).execution(controls).pdf(pdfPath);
```

Timeouts/cancellation are synchronous cooperative checks, not a hostile-input memory sandbox. Path output uses a temporary file in the same parent and atomically replaces the destination after final checks; failures preserve the previous target. A filesystem without atomic moves causes an explicit failure. Streams stay caller-owned and open; failures can leave a partial stream and provide no rollback.

See the complete [`EasyExportExample`](../../examples/src/main/java/com/mirakyux/export4j/examples/EasyExportExample.java) and the independent [`EasyExportConsumer`](../../verification/consumer/src/main/java/consumer/EasyExportConsumer.java). Design and results are recorded in [ADR-0118 (Chinese)](../adr/0118-configured-export-entry.md) and [acceptance (Chinese)](../verification/easy-export.md).
