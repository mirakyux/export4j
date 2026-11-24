# Configure once, export directly

The development module `export4j-starter` provides the `Export4j` entry point. Choose text, Markdown, HTML, or an XML template and a PDF/DOCX destination. Applications can use installed fonts or configure fonts, styles, and shared images at startup. Published `0.1.0-rc.3` does not contain this module.

## One dependency and a minimal call

A single dependency with an explicit version is sufficient for this entry point. `<development-version>` identifies the actual development build:

```kotlin
dependencies {
    implementation("com.mirakyux.export4j:export4j-starter:<development-version>")
}
```

Maven applications can use the same coordinate and set `export4j.version` to the actual development version. The starter works without a separately declared BOM. See the [release guide](releasing.md) for building a local Maven repository.

```xml
<dependency>
  <groupId>com.mirakyux.export4j</groupId>
  <artifactId>export4j-starter</artifactId>
  <version>${export4j.version}</version>
</dependency>
```

```java
import com.mirakyux.export4j.Export4j;
import java.nio.file.Path;

try (var export = Export4j.builder().build()) {
    export.text("Export report").pdf(Path.of("report.pdf"));
    export.markdown("Report **summary**").docx(Path.of("report.docx"));
}
```

The no-argument builder checks a fixed set of system font paths during export. Windows prefers Arial and Microsoft YaHei. macOS tries Arial and Hiragino Sans GB first. On Linux, Latin candidates include Liberation Sans, DejaVu Sans, and Noto Sans, with Noto Sans CJK SC for Chinese. The actual choice depends on installed fonts, supported formats, and embedding permissions. Minimal containers may need font packages. PDF export reports missing glyphs. Use explicit fonts as shown below when layout must match across hosts.

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

Create the output parent directory first. PDF measures and draws with the same physical fonts and honors their embedding flags. DOCX declares font families and styles for the reader to resolve. An application can reuse the exporter and close it at shutdown. Use `FontSource.resource(App.class, "/fonts/body.ttf")` for a classpath resource or `FontSource.collection(path, postScriptName)` for a named font collection member.

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

## Stable business font roles

`fontRole(name, family)` registers one business name for Java defaults, XML `role`, and HTML `font-family`, without relying on internal `family_N` names:

```java
import com.mirakyux.export4j.model.ContentOptions;

var report = FontFamily.builder("Liberation Serif",
        FontSource.file(Path.of("fonts/LiberationSerif-Regular.ttf")))
        .bold(FontSource.file(Path.of("fonts/LiberationSerif-Bold.ttf")))
        .build();
try (var export = Export4j.builder(latin).fontRole("report", report).build()) {
    export.markdown("Body and **bold**")
            .options(ContentOptions.builder().fontRole("report").build()).pdf(pdfPath);
    export.html("<p style='font-family:report'>Body and <strong>bold</strong></p>").docx(docxPath);
    export.xml("<document version='1'><p><span role='report_bold'>Bold</span></p></document>",
            java.util.Map.of()).pdf(pdfPath);
}
```

Variants use `report_bold`, `report_italic`, and `report_boldItalic`, and are registered only when their physical variants were supplied. Markdown/HTML emphasis selects variants within the current registered business family. Missing variants fail for both PDF and DOCX. Ordinary DOCX text still declares its family and style without requiring local glyph coverage. Themes, requests, and XML retain their existing property inheritance scopes.

The unified entry validates source roles before parsing, so entirely emphasized content cannot hide an undeclared body role. Markdown/HTML require resolvable defaults for body, all six headings, and cells even when a domain is absent from that source. Literal text checks only its current body role. XML source blocks validate their actual lexical configuration while keeping the same cumulative parser session; native XML nodes are checked through their actual model roles.

Business families do not automatically join the default missing-glyph stack or change Word's default script slots or formula text's default font stack. Use `eastAsia`/`complexScript`/`fallbackFont` separately when needed. Role names, including generated suffixes, must satisfy the static role identifier rules. Default roles, `bold`/`italic`/`boldItalic`, the `family_` prefix, case-insensitive alias collisions, and overlapping base/variant names are rejected. An alias matching a physical family name must reference the same `FontFamily` instance.

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

Explicitly retain a syntax plan when repeating the same XML structure:

```java
var syntax = export.prepareXml(xml);
export.xml(syntax, new GreetingData("First", "Result: $x^2$.")).pdf(firstPath);
export.xml(syntax, new GreetingData("Second", "Result: $y^2$.")).docx(secondPath);
```

`prepareXml` checks safe XML, root/include structure, and parsing/expansion limits. It loads no fonts, parses no source content, and renders no formulas; it does not validate every attribute, expression, or resource reference. The user owns the plan's lifetime. It contains statically expanded fragments, strings, and budget counts, with no global template cache. Each execution applies current themes, resources, and configuration with a fresh compiler and content session, rechecks parsing/expansion limits, and snapshots data. The plan captures neither `MathContentSource` nor a runtime `DocumentTemplate`. Another exporter's fragment registry cannot alter its expansion. See [reusing parsed syntax](xml-template-api.md#reusing-parsed-syntax).

Existing String overloads remain available. Adding syntax-plan overloads makes `xml(null, data)` ambiguous at compile time; null-rejection tests must select `xml((String) null, data)` or an explicit `XmlTemplate.Syntax` type. Valid nonnull String calls remain available.

The same `xml(...)` entry supports [sections, headers, and footers (Chinese)](../xml-sections-api.md). PDF also supports XML footnotes and endnotes. DOCX explicitly rejects templates with footnotes or endnotes. Template binding snapshots the data during execution. Keep the data unchanged until the request completes.

## Targets and strict failures

PDF compiles only the visual target; DOCX compiles only the native Word target. A formula drawable in PDF does not fail a PDF request because of an unrelated native Word limitation. Each output execution compiles independently; separate exports do not promise a shared intermediate document.

Formula fallback candidates are empty by default. Unsupported syntax, unreadable font/resource sources, unregistered roles, and unsupported target structures fail; PDF continues to reject missing glyphs. Fonts missing on a DOCX reader's system are outside export-time physical glyph validation. There is no automatic conversion to images or text, and existing budgets are unchanged. Declare image candidates explicitly during startup when needed:

```java
import com.mirakyux.export4j.math.MathContent;

var export = Export4j.builder(latin).eastAsia(chinese)
        .formulaFallbacks(java.util.List.of(MathContent.Fallback.IMAGE))
        .build();
```

`Result.degradations()` reports actual changes by occurrence index, target, candidate, and reason; `formulas()` counts occurrences. Font-count, individual-font-byte, and aggregate formula font limits report `LIMIT_EXCEEDED`, including deferred font preparation; an explicit `TEXT` candidate cannot consume those failures. Limits, cancellation, and timeouts cannot fall back. DOCX defaults to standard script font slots and `atLeast` spacing for object paragraphs. Java writes production output directly without Word, LibreOffice, or print services. See [mixed content](rich-content-api.md) for the supported subsets and [local fixes (Chinese)](../verification/easy-export.md#2026-11-19-修复结果) for the existing repair evidence.

## Lifetime, budgets, and output

The instance owns a bounded idle-expiring `FontFaceCache` by default. Inspect or clear it with `cacheStats()`/`clearCache()`. `fontCache(cache)` borrows an external cache and does not close it; its owner must do so. One `FontSource` identity loads at most once per operation. The shared instance retains no short-lived engine or compiled document. Formula text bytes are prepared only when a formula actually needs a text group, rather than eagerly for ordinary literal exports.

Expiration releases cache references without invalidating active fonts or promising an immediate RSS decrease. Caller-supplied byte sources retain their encoded snapshots. See [font caching](font-cache-api.md). Concurrent calls also require font source implementations and execution callbacks suitable for that concurrency.

`pdfBackend(...)` exposes existing PDF storage, backend budgets, and parsed-font cache configuration:

```java
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.pdf.PdfFontCache;
import com.mirakyux.export4j.backend.pdf.PdfOptions;

try (var programs = PdfFontCache.builder().maxEntries(4).build()) {
    var backend = PdfBackend.builder().fontCache(programs)
            .options(PdfOptions.storage(PdfOptions.Storage.TEMP_FILE)).build();
    try (var export = Export4j.builder(latin).pdfBackend(backend).build()) {
        export.text("Body").pdf(pdfPath);
    }
}
```

The exporter borrows the immutable backend; every output still creates its own PDF session and document. Closing the exporter does not close a caller-owned `PdfFontCache`; its owner must close it. `TEMP_FILE` moves PDF stream buffers to temporary storage and does not promise constant-memory layout or a constant-size PDF object tree. DOCX does not use this backend configuration.

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
