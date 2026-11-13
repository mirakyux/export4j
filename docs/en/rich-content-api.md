# Mixed content and cross-format mathematics

The current development version adds `MathContent`: one explicit source compilation produces both a visual `Document` and a native `DocxMathDocument`. This API is not part of published RC3.

Use the [configured-once entry](easy-export-api.md) to declare fonts and global styles once and export directly. For one target, `readVisual`/`compileVisual` return the visual document, while `readDocx`/`compileDocx` return native Word mathematics; only the selected projection executes. Existing `read`/`compile` keep their single-parse, dual-target contract. DOCX-only still uses the shared bounded TeX, outline preparation and geometry budgets; it does not accept arbitrary Word mathematics.

```java
var compiler = MathContent.builder()
        .textFonts(MathTextFonts.of(latinFontBytes, chineseFontBytes))
        .defaults(ContentOptions.builder().format(ContentOptions.Format.MARKDOWN)
                .rawHtml(true).formulas(true).tables(true).build())
        .fallbacks(List.of(MathContent.Fallback.IMAGE, MathContent.Fallback.TEXT))
        .build();
var result = compiler.read(markdown);
engine.write(result.document(), new PdfBackend(), pdfOutput);
var word = DocxAdapter.builder()
        .defaultFonts(new DocxFontFamilies("Liberation Serif", "Noto Serif SC"))
        .lineSpacing(DocxAdapter.LineSpacing.OBJECTS_AT_LEAST).build();
word.writeMath(result.docx(), docxOutput);
```

Provide physical fonts, passive resources and streams explicitly. Word font families are declarations for the receiving application, not system-font discovery or embedding. Review every entry of `result.degradations()`.

Headings, ordered/unordered lists, paragraphs and table cells retain mixed inline images, vectors and equations. Native repeated-header equations use physical cell anchors. Display equations use separate centered paragraphs and standard `m:oMathPara`. Repeated TeX occurrences bind separately; no global document cache or source-text matching identifies them.

Use `Document.Builder.headingContent(content, level)`, `bulletContent(content)` and `numberedContent(listId, content)` for `List<? extends InlineContent>`. They snapshot content and resolve the current format when added. The existing `List<InlineText>` signatures and behavior of `heading`/`bullet`/`numbered` remain unchanged. Corresponding block `mixed(...)` factories/builders provide explicit IDs, nesting and numbering restarts.

Source formats remain explicit. Markdown's inherited `rawHtml` flag defaults to false; XML uses `raw-html`. Enabling it maps the supported offline HTML subset without scripts or implicit resource access. Code preserves content, spaces and line breaks and excludes dollar-delimited formulas; syntax highlighting and language metadata are not provided. Unsupported active structures and attributes still fail strictly.

`MathContent` defaults to MARKDOWN with formulas enabled; ordinary `ContentImporter` defaults to TEXT with formulas disabled. Formula point size comes from the final resolved `ContentOptions` at each position, including cell and local appearance overrides. `MathContent.Builder.options(MathOptions)` supplies formula budgets and other options; its `fontSize`/`display` do not override the source position. Native DOCX requires half-point sizes. An appearance such as 12.3pt uses explicit candidates with reason `UNSUPPORTED_APPEARANCE`, or fails when none is configured; it is not silently rounded.

The default fallback list is empty. Explicit IMAGE uses validated outlines at four pixels per point by default. Placement scales proportionally, with a configurable minimum scale of 0.5; TEXT retains full TeX and its delimiters. Reports identify each occurrence, target, actual representation and reason without document content. Limits, cancellation and deadlines cannot become fallbacks. See the distinct [TeX subsets](math-api.md); arbitrary LaTeX, macros, MathML and OCR remain outside them.

Object line boxes expand by default. Without the body physical font stack or baseline metrics, the compiler reserves the original text frame on either side of the object baseline; spacing can increase. Point size is not assumed to equal text ascent, and text descent is retained. `expandObjectLines(false)` retains the original fixed geometry. Formula fitting reserves the narrower first/subsequent line after paragraph and nested-list indentation. Continuous columns use their narrowest width conservatively; grids use actual column spans and gaps. These semantic containers remain intact, and unsupported DOCX layouts still fail explicitly.

Tables containing compiled formulas require fixed/fractional columns and use real spans/padding. Resolve automatic widths for those tables explicitly; ordinary automatic-column tables without compiled formulas retain their behavior. This entry does not guess Word's metrics. Native Word geometry and pagination still require reader validation.

`MathTextFonts.of(byte[]... fonts)` supplies ordered explicit physical fonts for literal `\text{...}`/`\textrm{...}` groups. The limits are eight fonts, 32 MiB per font and 64 MiB total. Construction checks byte budgets before snapshots and validates standalone static TrueType/OpenType CFF with retention disabled. Variable/collection fonts, restricted or bitmap-only embedding, and fonts requiring whole-program embedding are rejected.

Literal text is limited to BMP left-to-right Latin, Han, Kana, Hangul, Greek and Cyrillic with common digits/punctuation. Combining marks, complex scripts, supplementary characters and direction controls are unsupported. Missing glyphs fail. Mathematical glyphs retain the bundled fonts; raw Unicode outside literal text does not gain new math syntax. No system-font discovery, global registration or upstream font-map mutation occurs.

For example, `(\bar{x} \pm s) / \text{岁}` produces actual outlines with explicit Chinese font bytes. `bar`/`overline` currently belong to the vector subset; Word requires an explicit IMAGE/TEXT candidate or fails strictly. Direct entries are `TexMath.vector(tex, options, textFonts)` and `TexMath.processor(options, textFonts)`.

`MathTextFonts` retains only private encoded-byte snapshots. A vector invocation requiring text parses each supplied font at most once and releases its local parsed-font references afterward; this is not a cache across equations. `MathContent` and processors retain their configuration, whose bytes remain until those caller references are released. Expiry of the separate body-font cache does not release these snapshots.

`MathTextFonts.deferred(...)` synchronously obtains explicit fonts only when a literal text group is used. It does not memoize globally and rejects another deferred configuration. Returning ordinary `EMPTY` keeps the original default mathematical text path. The configured-once entry memoizes its encoded snapshot only within the current operation, avoiding mathematical font validation and copying for ordinary text and symbol-only equations.

Use `compiler.compile(importer -> ...)` to build Java templates or compile XML with that same importer. The synchronous callback is not retained. Every compiled equation must occur exactly once in the returned document. The math module does not depend on the template module. Source/work/formula/segment budgets remain cumulative and configurable; the older convenience entry's default 256 formulas is no longer an unchangeable total ceiling.

`DocumentEngineProvider` retains explicit `FontSource` configuration and a bounded cache while creating engines inside synchronous `withEngine` operations. It does not retain parsed faces or engines itself. Caller-retained layouts/engines may still retain fonts; expiry does not force garbage collection or immediate RSS reduction. See [font caching](font-cache-api.md) and the [acceptance record](../verification/rich-content.md).
