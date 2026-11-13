# Inline vector resources

`InlineVector` places an explicit caller-built outline in paragraphs, headings, both list kinds, semantic-table cells and fixed page regions. Text and vectors share line breaking and pagination; PDF, SVG and PNG use the same result. This API parses no TeX, SVG or MathML and adds no formula library. For TeX, use the optional [math module](math-api.md) instead of maintaining a parser and outline capture.

## Create atomic content

```java
import java.util.List;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineVector;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.VectorResource;

var triangle = VectorResource.of(18, 10, 2, List.of(
        new VectorResource.Segment(VectorResource.Operation.MOVE, List.of(0.0, 2.0)),
        new VectorResource.Segment(VectorResource.Operation.LINE, List.of(9.0, -10.0)),
        new VectorResource.Segment(VectorResource.Operation.LINE, List.of(18.0, 2.0)),
        new VectorResource.Segment(VectorResource.Operation.CLOSE, List.of())));
var formula = new InlineVector(triangle, "\\triangle", 0x223344, "");
var format = new ParagraphFormat(12, 24, 0, 0,
        ParagraphAlignment.LEFT, 0, 6, false);
var paragraph = Paragraph.mixed("formula", List.of(
        new InlineText("Symbol: ", InlineStyle.of(12)), formula), format);
var cell = TableCell.mixed(paragraph.content(),
        new CellFormat(12, 24, ParagraphAlignment.LEFT));
```

Coordinates are points relative to the baseline, positive downward. All control points stay inside `[0,width] × [-ascent,descent]`. Width is positive, ascent/descent nonnegative and their sum positive. Width and total height are each at most 14,400 pt. Paths use the nonzero winding fill rule and an explicit RGB color in `0..0xffffff`. Stroke, gradients, transparency and configurable fill rules are outside this API.

MOVE/LINE have two coordinates, cubic CURVE six, and CLOSE none. Every subpath starts with MOVE; a new MOVE is required after CLOSE. A resource has 1–10,000 segments. Nonfinite numbers, invalid operation order and out-of-box coordinates fail immediately.

Resource lists and coordinates are immutable snapshots with no open handles or implicit global cache. `id()` hashes the versioned canonical geometry, including dimensions and segment order, with SHA-256. Signed zero is normalized. `byteSize()` reports canonical encoding length, not heap usage. The two-argument `InlineVector(resource, logicalText)` constructor uses black and no link.

## Source positions, editing and budgets

An object occupies one U+FFFC and one UTF-16 source unit, even if its reading text is longer. `Paragraph.vectors()` stores `VectorSpan(sourceIndex, vector)`; `content()` and `TableCell` preserve object order. Image, field and vector markers cannot overlap. A paragraph supports at most 1,024 vector markers. Manual construction requires complete inline runs with the paragraph's default style and each vector's own link at its marker; prefer `mixed(...)` to create them. No font glyph is required for the marker.

`logicalText` is limited to 1,024 UTF-16 units and rejects controls, invalid surrogates, U+FFFE/U+FFFF and line/paragraph separators. Template binding preserves it together with geometry: `${...}` inside reading text remains literal. Changed ordinary text relocates markers. Rebuild a whole `InlineVector` for a dynamic formula, or use `DocumentEditor.vector(id, sourceIndex, value)` to replace an existing atom. Ordinary text edits cannot consume the atom. Links bind separately and use the passive HTTP/HTTPS/mailto allowlist.

Fixed line height must fit the font stack and all vector ascents/descents. Oversized objects fail rather than being scaled or clipped. Auto-column atomic minimums include vector width; pagination moves complete lines.

Layout charges reading text and rendered work. Distinct vector geometry in one ordinary layout input is limited to 5,000,000 segments and 64 MiB canonical bytes. Each paragraph shaping pass additionally rejects more than 2,000,000 vector segments, counted per occurrence before conversion. Identical immutable paths share storage only within that conversion; generated text/vector outlines still obey the text layer's combined segment cap. Streaming tables conservatively charge geometry on every occurrence across the stream without retaining an unbounded ID set. Fixed regions independently allow at most 1,000,000 segments and 16 MiB canonical bytes, per occurrence, and reject links. Templates charge reading text to `maxCodePoints` and segments to `maxVisitedNodes`; editing charges reading text to `maxTextUtf16` and canonical bytes per occurrence to `maxWork`. These are work budgets, not RSS guarantees.

## Register HTML and Markdown resources

```java
import com.mirakyux.export4j.adapter.web.HtmlImporter;
import com.mirakyux.export4j.adapter.web.MarkdownImporter;

var html = HtmlImporter.builder().paragraphFormat(format)
        .vector("formula-1", formula).build()
        .read("<p>Symbol: <img src=\"formula-1\" alt=\"triangle\"></p>")
        .execute().document();
var markdown = MarkdownImporter.builder().paragraphFormat(format)
        .vector("formula-1", formula).build()
        .read("Symbol: ![triangle](formula-1)")
        .execute().document();
```

Keys exactly match `img src` or Markdown image destinations. Images and vectors cannot share a key. No file loading, remote fetching, automatic `$...$` recognition or implicit parsing occurs. Reading text comes from the registered vector, not image alt text. Explicit HTML/CSS dimensions must match intrinsic point dimensions; conflicts fail. Regenerate the resource to change its size.

Paragraphs, headings, both list kinds and semantic cells retain vectors through `Paragraph.mixed`, `Heading.mixed`, `BulletItem.mixed`, `NumberedItem.mixed` and `TableCell.mixed`. List prefixes shift UTF-16 object anchors without expanding formulas or rebuilding outlines. List `Builder.content(...)` snapshots its input immediately; the final font size determines synthetic object marker styles while explicit text styles retain their values. Automatic heading bookmarks combine text, image alt text and vector reading text. Headings with generated fields require explicit bookmark titles. The separate `MarkdownTemplate` syntax has no new vector feature.

`Document.Builder` exposes `headingContent(content, level)`, `bulletContent(content)` and `numberedContent(listId, content)` for mixed content. Existing `List<InlineText>` signatures and behavior of `heading`/`bullet`/`numbered` remain unchanged.

## XML saving and recovery

XML v1 encodes bounded static paths alongside text, images and fields. `XmlTemplateWriter` saves them in the ordinary resource ZIP:

```xml
<document version="1">
  <heading level="1" size="12" line-height="24">Symbol:
    <vector width="18" ascent="10" descent="2" logical="\triangle" color="#223344">
      <segment operation="MOVE" points="0 2"/>
      <segment operation="LINE" points="9 -10"/>
      <segment operation="LINE" points="18 2"/>
      <segment operation="CLOSE"/>
    </vector>
  </heading>
</document>
```

Dimensions, coordinates, operation order, color and segment limits use the same model validation. `logical` remains literal: `${...}` cannot change reading text while keeping stale geometry. Rebuild the complete resource for a dynamic formula or use the math module. Paths cannot reference external resources. Compilation, binding, saving and ZIP encoding retain their finite input, node, text and output budgets. Heading/list saving preserves object order, links and UTF-16 anchors. Headings reject note markers; both list kinds accept explicit note markers with matching supplied notes.

## Output and extraction limits

| Target | Behavior |
| --- | --- |
| PDF | Draws the actual paths and stores reading text in `ActualText`; passive links follow final geometry |
| SVG/PNG | Draws the same paths; interactive links still fail under static-backend rules |
| HTML/Markdown export | STRICT rejects; explicit `INLINE_VECTOR`/DEGRADE emits escaped logical text and reports lost paths, color and geometry |
| DOCX | Plain lossless writes reject; unified conversion can explicitly degrade to logical text with a loss report; no automatic OMML or rasterization |
| XML model saving/resource ZIP | v1 static paths preserve geometry, color, links and reading text; invalid paths and exceeded budgets fail |

`ActualText` is separate from native font glyphs. Readers supporting it may search original text; extractors ignoring it may return only the invisible anchor. It does not establish per-character coordinates, editable equations, TeX↔OMML conversion or PDF/UA compliance.

Safe text/image-only lines can retain native TTF glyphs for ordinary extraction: left-to-right text, static TrueType, verified one-to-one code point/GID mapping, no decorations, fields or vector objects. Existing shaping positions remain authoritative; any failed condition keeps the entire line on the outline path. Complex scripts, combining clusters, bidi text, ligatures, CFF/variable/color fonts and text beside vector formulas retain their own contracts. See [inline images](inline-images-api.md) and [literature integration (Chinese)](../literature-pdf-integration.md).

## Verification

[InlineVectorTest](../../export4j-model/src/test/java/com/mirakyux/export4j/model/InlineVectorTest.java) covers model behavior. The [complete example](../../examples/src/main/java/com/mirakyux/export4j/examples/InlineVectorsExample.java) reuses the existing example-only `ManuWeaveMath` converter for three restricted TeX formulas. It adds no production parsing dependency. From the repository root:

```powershell
.\gradlew.bat :examples:inlineVectors --console=plain
python scripts/verify_inline_vectors.py
```

Use `./gradlew` on Linux/macOS. Run Python in an isolated environment with `scripts/requirements-verification.txt` installed; Poppler is also required. The default sample directory is `examples/build/inline-vectors/`, and the independent report goes to `build/verification/inline-vectors/`.

The one-page specimen contains three resources and eight placements. Verification passed for actual PDF/SVG paths, baselines, atomic markers, original-TeX extraction/search/selection in PDFium, and full-page comparisons using two PDF engines, SVG rendering and exported PNG. All five mutation controls were rejected, and the complete page was visually reviewed. See the [verification record (Chinese)](../verification/inline-vectors.md). The current 17-page literature checks are recorded separately in [literature integration (Chinese)](../literature-pdf-integration.md#本次真实文献); its 29 formulas remain the original PNG images and were not retroactively converted into vectors.
