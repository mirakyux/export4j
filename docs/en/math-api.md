# Optional mathematics API

`export4j-math` provides bounded TeX conversion and opt-in Markdown mathematics. Callers can use the library instead of maintaining delimiter regexes, Java2D outline capture or TeX-to-OMML conversion. Core models, ordinary Markdown import and backends do not depend on this optional module. This feature is in the development version; it is absent from published `0.1.0-rc.3`. For one compilation producing PDF and native Word equations, mixed HTML tables and per-target fallbacks, use [MathContent](rich-content-api.md).

## Direct conversion

Use the same development version as the other Export4j modules:

```kotlin
dependencies {
    implementation(platform("com.mirakyux.export4j:export4j-bom:0.1.0-SNAPSHOT"))
    implementation("com.mirakyux.export4j:export4j-math")
}
```

```java
import com.mirakyux.export4j.math.MathOptions;
import com.mirakyux.export4j.math.TexMath;

var options = MathOptions.builder().fontSize(14).display(true).build();
var vector = TexMath.vector("\\frac{a+b}{c}", options);
var nativeMath = TexMath.docx("\\frac{a+b}{c}", options);
```

`vector` returns the existing `InlineVector` for mixed paragraphs, headings, ordered/unordered items, cells and explicit HTML resource registration. PDF/SVG/PNG use shared layout and actual curves. No TeX installation, browser, Office or printing service runs in production. Formulas remain atomic; this direct conversion does not scale them to fit. Callers explicitly supply body fonts and adequate fixed line height.

Coordinates and vector font size are points, relative to the baseline. Reading text retains TeX, with CR/LF/tab changed to spaces. Original Markdown ranges remain available separately. ActualText, search and character selection are distinct capabilities. `docx` returns editable `DocxMath`; its size inherits the paragraph, and this tree does not automatically render through PDF layout.

## Import Markdown

```java
import com.mirakyux.export4j.math.MarkdownMath;
import com.mirakyux.export4j.model.ParagraphFormat;

var math = MarkdownMath.builder().options(options).build();
var pdfInput = math.vector("The result is $x^2$.", importer -> importer.paragraphFormat(
        ParagraphFormat.builder().fontSize(12).lineHeight(48).build()));
var wordInput = math.docx("The result is $x^2$.");
// Lay out pdfInput.document() with explicit fonts.
// Write wordInput.document() through DocxAdapter.writeMath.
```

Each call uses a fresh importer builder and invokes the callback synchronously. Results include the document, ordinary Markdown `importReport`, formula `degradations`, and source `formulas`. Process both diagnostic channels.

Single-dollar delimiters stay on one line, require non-space inner boundaries and cannot close immediately before a digit. Double-dollar blocks must stand on their own lines and may contain multiline TeX. Code, whole links/images, reference definitions, raw HTML tags and HTML blocks are excluded. Backslash parity is respected. This convention reduces currency ambiguity; unmatched delimiters stay ordinary Markdown. It is neither inference nor OCR.

`scan(source)` returns delimiter-inclusive, original UTF-16 half-open ranges. Each imported occurrence occupies one U+FFFC slot. Repeated TeX binds independently in headings, ordered/unordered items, body paragraphs and cells. Mixed images/vectors remain intact; native header equations use actual physical cell anchors. Unsupported source structures still fail explicitly.

## Supported subset and limits

Vector conversion accepts a fixed TeX subset: common Greek/operator commands, braced fractions/roots, scripts, explicit ASCII math styles, bounded large operators/delimiters, `\bar`/`\overline`, and rectangular matrix/pmatrix/bmatrix/cases environments (64 cells maximum). Native conversion supports semantic fractions, indexed roots, scripts, explicit text/styles and common symbols/Unicode. It rejects matrices, large-operator argument inference, stretchy delimiters, bars, primes and forced size modes. The two subsets are intentionally distinct. Neither accepts macros, external resources or arbitrary font/size commands. There is no general LaTeX runtime, bidirectional TeX/OMML, MathML or OCR support.

Supply explicit physical fonts for Unicode text in `\text`/`\textrm`:

```java
import com.mirakyux.export4j.math.MathTextFonts;

var textFonts = MathTextFonts.of(explicitStaticFontBytes);
var withUnit = TexMath.vector("24\\text{岁}", options, textFonts);
// MathContent.builder().textFonts(textFonts) accepts the same configuration.
```

This extends only literal text groups, not ordinary TeX math syntax. Supported text is left-to-right BMP Latin, Han, Kana, Hangul, Greek, Cyrillic and common characters. Combining characters, complex scripts, supplementary characters and controls are rejected. Font order is the explicit per-character missing-glyph fallback order; missing coverage fails. The configuration accepts at most eight static standalone TrueType/OpenType CFF fonts, 32 MiB each and 64 MiB combined. TTC, variable, restricted, bitmap-only and no-subsetting fonts are rejected. Callers still need permission to use their fonts. Without explicit fonts, text groups retain the fixed-font ASCII subset.

Per-formula hard ceilings in `MathOptions` may only be tightened: 1,024 source UTF-16 units, depth 16, 256 commands, 2,048 captured paths, 10,000 captured/final segments, extent 1,024 pt and font size 6–72 pt.

Document totals are separate configurable budgets. `MarkdownMath` defaults to 1,048,576 input UTF-16 units, 256 formulas and 400,000 total vector segments; `maxInputLength`, `maxFormulas` and `maxTotalSegments` may raise these to explicit positive values. Configure the corresponding `MarkdownImporter.limits(...)` in the import callback as well, since raising scanner capacity does not bypass downstream input/output budgets. `MathContent` uses `ImportLimits.DEFAULT`, 1,000 formulas and 1,000,000 vector segments by default, adjustable through `limits`, `maxFormulas` and `maxVectorSegments`. Higher document totals never increase per-formula hard ceilings. Existing native DOCX tree/package limits still apply.

Strict failure is the default. `MarkdownMath.builder().fallback(MarkdownMath.Fallback.KEEP_SOURCE)` explicitly retains an unsupported formula as escaped literal text and reports its original range/reason. `MathException.reason()` distinguishes invalid syntax, unsupported syntax, limits and rendering failures. Messages are redacted; retained causes are for internal diagnostics. Limits and cancellation/timeouts never enter fallback. Wrap the entire call in `ExecutionContext.execute` to propagate cooperative controls, including lock waiting. Third-party parsing and Java2D calls observe cancellation at their next checkpoint; this is not a preemptive hostile-input sandbox.

## Resources and distribution

Vector preparation serializes library calls and retains only five explicit Computer Modern fonts, with no formula/document cache or implicit system fonts. Other code sharing JLaTeXMath in the same JVM must not mutate its global commands, fonts, DPI, size or scaling. Known overrides are rejected; the library cannot isolate arbitrary external concurrent mutation.

`MathTextFonts.of` privately snapshots encoded font bytes for the lifetime of that configuration and any compiler retaining it. Construction validates without shared font-cache retention; each conversion requiring text outlines parses physical fonts locally, without system registration or a cross-document parsed-font cache. Release the configuration and compiler references to release the byte snapshots; this is not an expiring cache.

JLaTeXMath 1.0.7 is a private runtime dependency of the optional module. Its license is **GPL-2.0-or-later with the original upstream linking exception**, not LGPL or a standard Classpath exception. Original library/font terms remain intact. The math JAR embeds the fixed upstream sources JAR and original Maven build POMs; see [dependencies (Chinese)](../dependencies.md). No shaded/replaced upstream classes are introduced.

Run `./gradlew build sbom publishLocalBundle :examples:math`, then `python scripts/verify_math.py` using the pinned verification environment and Poppler. On Windows use `.\gradlew.bat`. See [verification (Chinese)](../verification/math.md) for actual results and [ADR0113 (Chinese)](../adr/0113-optional-bounded-math.md) for boundaries.
