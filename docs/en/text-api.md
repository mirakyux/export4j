# Font and text API

For multiple scripts, source clusters after missing-glyph replacement, bidirectional continuation and real reader search/selection checks, see the [text corpus (Chinese)](../text-corpus-api.md) and [ADR-0026 (Chinese)](../adr/0026-source-clusters-and-shaping-context.md). Query success, logical source text and per-character selection geometry are assessed separately; outline anchors do not guarantee general extraction or precise selection.

The API supports static TrueType/CFF fonts, explicit collection members and adaptive simple/OpenType layout. It provides explicit fallback, measurement, paragraph breaking, indentation, alignment and fixed line heights. Layout results can become PDF drawing commands directly. The caller can create pages or let the [document layout API (Chinese)](../layout-api.md) allocate them automatically.

## Run the example

Run from the repository root:

~~~powershell
.\gradlew.bat build :examples:textPdf
~~~

This generates `examples/build/export4j-text-example.pdf`. See [TextPdfExample](../../examples/src/main/java/com/mirakyux/export4j/examples/TextPdfExample.java) for the full code. The example uses repository [font resources](../../assets/fonts/README.md) with recorded sources and licenses; it requires neither system fonts nor runtime downloads.

## Load, lay out and draw

Run this fragment from the repository root. The caller explicitly reads the font files:

~~~java
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.graphics.core.TextLayoutPainter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.text.ParagraphLayouter;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextAlignment;

var latin = TrueTypeFontLoader.load(Files.readAllBytes(
        Path.of("assets/fonts/LiberationSans-Regular.ttf")));
var chinese = TrueTypeFontLoader.load(Files.readAllBytes(
        Path.of("assets/fonts/LXGWWenKaiTC-Regular.ttf")));
var style = new ParagraphStyle(14, 24, 28, 0, TextAlignment.LEFT);
var layout = new ParagraphLayouter().layout(
        "中文与 English 混排。先测量与换行，再输出 PDF。", List.of(latin, chinese), 523, style);
var page = new DisplayListBuilder(PageSize.A4);
TextLayoutPainter.draw(page, layout, new Point(36, 60), RgbColor.BLACK);
var document = new DisplayDocument(DocumentMetadata.EMPTY, List.of(page.build()));
try (var output = Files.newOutputStream(Path.of("text.pdf"))) {
    new PdfBackend().write(document, output);
}
~~~

The font module provides `FontFace`, `FontData` and metrics in em units; the text module provides glyph advances and line layout in points. The graphics core and PDF backend bring these project modules transitively. Public APIs expose no third-party types.

## Font loading and fallback

`TrueTypeFontLoader.load(byte[])` copies the input bytes, with a default maximum of 32 MiB. The borrowed-stream overload, `load(InputStream, int maxBytes)`, reads at most `maxBytes + 1` bytes to detect overflow and does not close the caller's stream. The caller closes file streams; the loader releases its own parsing resources.

`FontData` stores immutable bytes, a SHA-256 identifier and whether subsetting is permitted. Returned byte arrays are copies too. A loaded `FontFace` retains no open handles and can be shared; custom SPI implementations must meet the thread-safety contract themselves.

PDF output for ordinary TrueType glyph text records source code points separately from glyph IDs. For example, when a font maps “丢” and “丟” to the same glyph, the output retains each character's original Unicode. This applies to subset/full embedding and ordinary, page-by-page and disk-backed streaming PDFs, while preserving caller-specified advances.

The first font supporting a code point is selected in group order. Selecting another font produces a `FONT_FALLBACK` / `INFO` diagnostic with the original UTF-16 range and selected font identifier. `FontGroup.strict(fonts)` fails on missing glyphs by default. `FontGroup.placeholder(fonts, 0x25a1)` explicitly enables a placeholder and `MISSING_GLYPH_REPLACED` / `WARNING`. The entire group is tried first; a missing placeholder glyph still fails. Original UTF-16 source ranges are unchanged. System fonts are not scanned. Callers must check license obligations beyond embedding flags.

`FontCollectionLoader.load(bytes, memberIndex)` explicitly extracts a zero-based TTC/OTC member; both the collection and member have byte budgets. `TrueTypeFontLoader` loads static OTF/CFF. Simple glyph paragraphs preserve native glyph mapping, with Type1C/CIDFontType0C embedded in PDF; complex shaping or explicit `layoutOpenType` still uses measured outlines. CFF cannot be used for editable PDF fields.

Variable axes, CFF2, color fonts and general vertical shaping use the separate optional [export4j-opentype module (Chinese)](../native-opentype-api.md), with explicit font bytes and matching platform binaries. The normal text core keeps its existing Java path and does not load JNI automatically. [Character-cell vertical text (Chinese)](../vertical-text-api.md) retains its original entry point.

## Paragraph geometry and source ranges

All lengths in `ParagraphStyle(fontSize, lineHeight, firstLineIndent, subsequentLineIndent, alignment)` are points. Font size and line height must be finite and positive; indents must be finite and nonnegative, and width must exceed both indents. `ParagraphStyle.of(size)` defaults to left alignment, no indentation and a line height of 1.5 times the font size.

The first line uses the first-line indent; other lines use the subsequent-line indent. Lines following explicit breaks still belong to the same paragraph. LEFT, CENTER and RIGHT align within the area remaining after the corresponding indent.

The maximum ascent and maximum descent of the entire configured font stack define the line box; the complex path also considers JDK physical-font metrics. `ParagraphLayouter.minimumLineHeight(group, size)` gives a lower bound compatible with both paths. Baseline spacing remains fixed even when a line does not use a fallback font. The first baseline uses the selected path's maximum ascent; each subsequent baseline adds `lineHeight`. The line height must fit the line box, and the font's line gap is not added again. Layout height equals line count times line height; extra leading remains below the line box.

`ParagraphLayout` and `TextLine` retain local coordinates, widths, simple-glyph `runs()` or neutral `outlines()`, and diagnostics. Use `atBaseline()` when moving baselines to preserve both representations. `TextLayoutPainter` uses the paragraph's top-left corner as its origin; `drawGlyphRun` takes the left end of the run's baseline.

LF, CR and CRLF create explicit breaks; empty input and trailing breaks preserve empty lines. Line source ranges include U+0020 separator spaces consumed during wrapping but exclude CR/LF. Spaces before an explicit break that were not consumed by soft wrapping remain drawn. Soft wrapping removes trailing separator spaces and consumes separator spaces before the next line. Breaks never split surrogate pairs.

Line breaking uses JDK rules with the ROOT locale, falling back to emergency code-point breaks when no legal break exists. Common Chinese punctuation cases are covered, but extremely narrow areas may violate full Chinese break restrictions. UAX #14 or professional Chinese typesetting consistency is not promised.

## Shaping boundaries

`TextShaper` is an extension interface. `SimpleTextShaper` maps ordinary horizontal Latin, Han, Greek, Cyrillic, kana and precomposed Hangul one code point at a time. It does not implement kerning, ligatures, clusters, glyph offsets, GSUB/GPOS or bidirectional reordering. Explicitly injecting it retains these strict limits. The default `ParagraphLayouter` uses the public JDK OpenType path for complex text. Simple static-CFF text can retain native glyph mappings; complex shaping or explicit `layoutOpenType` uses outlines. Semantic paragraphs, cells and fixed page regions share this routing. Explicit kerning/ligature switches for Latin text still use the `OpenTypeText` component; see the [capability guide](capabilities-api.md).

Each `SimpleTextShaper` instance reuses immutable glyph values through a bounded concurrent cache of at most 4,096 entries. The cache stores only code points, glyph IDs and exact advances, retaining neither fonts nor source text. Each call independently retains its selected fonts, original UTF-16 ranges, line-box metrics and diagnostics. Glyph object identity is not a public contract.

The simple shaper rejects complex or bidirectional scripts, combining marks and multi-code-point sequences. The default public path supports the Arabic, Hebrew and combining-character examples. A cluster must be covered by one explicit physical font; clusters crossing style boundaries fail explicitly. Ordinary supplementary-plane code points can participate in layout if the actual font has their glyphs; this does not imply support for multi-code-point emoji sequences or color emoji. Callers may explicitly normalize Unicode beforehand; the library does not rewrite source text implicitly.

## Budgets and errors

Paragraph layout defaults to at most 100,000 Unicode code points and 10,000 lines per call; the public OpenType path additionally limits outlines to 2,000,000 segments. This example explicitly selects the simple shaper and custom limits:

~~~java
var layouter = new ParagraphLayouter(new com.mirakyux.export4j.text.SimpleTextShaper(),
        new com.mirakyux.export4j.text.TextLayoutLimits(20_000, 2_000));
~~~

| Exception | Stable code | Meaning |
| --- | --- | --- |
| `FontLoadException` (an I/O exception) | `INVALID_FONT` | Invalid or unsupported font format |
| `FontLoadException` | `EMBEDDING_RESTRICTED` | Outline embedding is prohibited |
| `FontLoadException` | `BUDGET_EXCEEDED` | Raw font bytes exceed the limit |
| `TextLayoutException` | `INVALID_TEXT` | Isolated surrogate or unsupported control character |
| `TextLayoutException` | `MISSING_GLYPH` | None of the configured fonts has the glyph |
| `TextLayoutException` | `UNSUPPORTED_SHAPING` | Required shaping is unavailable |
| `TextLayoutException` | `GLYPH_TOO_WIDE` | One glyph exceeds the inline width |
| `TextLayoutException` | `LINE_HEIGHT_TOO_SMALL` | Line height is smaller than the configured font stack's line box |
| `TextLayoutException` | `BUDGET_EXCEEDED` | Code-point or line limit exceeded |

Text exceptions carry a zero-based original UTF-16 index without including body text. Invalid dimensions and similar entry arguments use ordinary `IllegalArgumentException`; null values use `NullPointerException`. See the [graphics API (Chinese)](../graphics-api.md) for PDF output budgets, structured errors and stream ownership.

## Determinism and extension

With fixed font bytes, fallback order, text, width, style and JDK line-break rules, repeated layout produces the same result. Identical PDF bytes are not an acceptance criterion. The basic layouter can be shared; an injected custom shaper must be thread-safe.

Font-byte copying, parsing and text budgets constrain particular input sizes; they are not an untrusted-input sandbox or execution-time limit. The text module does not paginate; the semantic Document and automatic pagination belong to the model and layout modules. Shared shaping reuses base physical fonts by the explicit font program's SHA-256, with a process-wide cache capped at 32 programs and a 64 MiB source-byte weight. It retains no source byte arrays, derived sizes or implicit system fonts. This weight is not a precise measurement of AWT native memory. `StyledSpan`, `InlineText` and `InlineStyle` support inline font roles, sizes, colors, superscript/subscript and decoration; see the [advanced API (Chinese)](../advanced-api.md).

See [ADR-0003 (Chinese)](../adr/0003-font-text-layout.md) for initial public-interface changes and [ADR-0007 (Chinese)](../adr/0007-advanced-layout-and-font-policy.md) for group policies, rich-text interfaces and compatibility overloads. Callers inspect `diagnostics()` and choose how to present warnings; the library does not print logs. Complete code appears under [font groups and missing-glyph policies (Chinese)](../advanced-api.md#字体组与缺字策略).

Inline images participate in shared line breaking through the default adaptive path. `TextLine.images()` stores their geometry, which `atBaseline()` preserves during pagination. The U+FFFC image marker does not request a font glyph. Fixed line boxes must fit fonts and images; explicitly injected text shapers reject images. See the [inline image API](inline-images-api.md) for provenance, budgets and DOCX media rules.
