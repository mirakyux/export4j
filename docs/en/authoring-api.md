# Common authoring APIs and Chinese typography

Write ordinary reports with `Document.Builder`, then export them with `DocumentEngine.write`. Complex sections, page regions, contents, footnotes and custom components retain explicit entry points. See the complete runnable [`AuthoringExample`](../../examples/src/main/java/com/mirakyux/export4j/examples/AuthoringExample.java).

Named builders for pages, styles, components, merged tables and backends, with visible nesting, are covered in the [fluent API](fluent-api.md). The dense-form example uses these entry points; the older short forms remain compatible.

## Minimal call

This code uses project APIs and the JDK. The caller supplies the font files; the library neither scans system fonts nor bundles the example fonts.

~~~java
import java.util.List;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.model.Document;

// 在可抛出 IOException 的调用方法中：
var latin = TrueTypeFontLoader.load("fonts/latin.ttf");
var chinese = TrueTypeFontLoader.load("fonts/chinese.ttf");
var engine = DocumentEngine.builder(FontGroup.strict(List.of(latin, chinese))).build();
var document = Document.builder()
        .heading("季度报告", 1)
        .paragraph("这里是正文。")
        .bullet("关键结论")
        .numbered("steps", "准备数据")
        .numbered("steps", "导出报告")
        .table(List.of("指标", "数值"), List.of(List.of("收入", "120")))
        .build();
var result = engine.write(document, new PdfBackend(), "report.pdf");
~~~

These local-file convenience overloads are available from `0.1.0-rc.2`; `0.1.0-rc.1` does not include them. They use the existing file/stream implementations and budgets. See [file and stream convenience entry points](fluent-api.md#file-and-stream-convenience-entry-points).

`Document.builder()` defaults to A4, 54 pt margins on all sides, empty metadata and 12 pt body text. The body has a fixed 18 pt line height and 6 pt space after each paragraph. Heading sizes depend on the level, and each heading stays together and with the following block. `metadata(...)` and `pageSettings(...)` change document settings; `bodyFormat(...)` affects only subsequently added body paragraphs, lists and convenience tables. Body styles do not override heading defaults.

The default line height does not fit every font. Fonts with tall line boxes, such as those used for Arabic, need a sufficient fixed line height. Measure the minimum with `ParagraphLayouter.minimumLineHeight(group, size)`. Font size, line height and image height remain caller-controlled; the engine does not shrink or clip content automatically.

Simple tables use equal proportional columns, one repeated header and default padding. They require at least one body row and the same number of columns in every row. Use the full `Table` API for automatic widths, rich cells and split-row continuation; see the [table guide](table-api.md).

## IDs and naming

An ID uniquely identifies a block within the current document and is not displayed in the body. It is used for bookmarks, page references, number references, note binding and diagnostic locations.

Convenience entry points generate IDs such as `auto-1` and `auto-2`, skipping matching explicitly added IDs. The same call sequence produces the same IDs, but inserting or removing content can change their numbers. Explicitly name content that will be a reference target:

~~~java
var document = Document.builder()
        .heading("overview", "概览", 1)
        .paragraph("summary", "汇总正文")
        .pageBreak()
        .paragraph("下一页正文")
        .build();
~~~

In `numbered("steps", ...)`, `steps` is the list-group ID used to continue the same list's counter; every item still has its own block ID. Use `NumberedItem` for nesting or restarted numbering. `build()` fails explicitly if an explicit name conflicts with an automatic ID. The result is an immutable snapshot; use a builder within one task only.

## Justification and strict CJK line breaking

~~~java
var format = ParagraphFormat.of(12)
        .withAlignment(ParagraphAlignment.JUSTIFY)
        .withLineBreakPolicy(LineBreakPolicy.CJK_STRICT);
var document = Document.builder().bodyFormat(format).paragraph("需要排版的正文……").build();
~~~

`JUSTIFY` expands non-final lines ending at a soft wrap. It distributes extra width across ASCII spaces first; when there are none, it uses valid boundaries between adjacent Chinese, Japanese and Korean characters. Final lines, lines ending at an explicit break and lines with no expandable gaps retain their natural width. The target is the width remaining after indentation. Glyphs are not scaled, image and field reservation widths do not change, and UTF-16 provenance and pagination remain consistent. A small number of gaps in a narrow column can become large; there is no automatic maximum expansion or whole-paragraph break optimization yet.

`STANDARD` preserves the previous default behavior. `CJK_STRICT` checks the source at normal and emergency breaks: specified opening punctuation cannot end a line, closing punctuation cannot start one, paired ellipses and dashes stay together, and combinations around NBSP, narrow no-break space and word joiner are preserved. Explicit line breaks remain the caller's choice. If a complete combination cannot fit, layout fails explicitly instead of overflowing or moving to new pages repeatedly.

These rules are a limited Chinese typography subset, not full UAX 14, publisher-specific rules or Word layout compatibility. Missing-glyph replacement does not disable source-text break restrictions; original code points and UTF-16 warnings remain traceable. `CellFormat` and `ParagraphStyle` provide corresponding `withAlignment` and `withLineBreakPolicy` methods; semantic tables, `FlowText` and `OpenTypeText` can use the same policies.

## Rich headings and lists

`Heading.rich`, `BulletItem.rich` and `NumberedItem.rich` accept `List<InlineText>`, retaining font roles, size, color, baseline shift, underline, strikethrough and external links. Generated list numbers and bullets use the paragraph's default style; links cover only their corresponding content.

~~~java
var runs = List.of(new InlineText("重点", new InlineStyle("bold", 12, 0x285080, 0, true, false)));
var document = Document.builder()
        .heading(runs, 1)
        .bullet(runs)
        .numbered("steps", runs)
        .build();
~~~

Register font roles explicitly with `DocumentEngine.Builder.fontRole`. Real bold requires a real font variant; synthetic bold is not generated. For explicit IDs, formats or levels, create a value with a rich-text factory and then call `add(...)`. Headings and lists currently support text and external links; use paragraphs or cells for images and fields.

Template binding preserves rich runs, and navigation derives names from headings' logical text. DOCX preserves these semantic blocks and runs and maps justification to standard `w:jc="both"`. Strict CJK breaking round-trips through Word's kinsoku hint and an ignorable project policy marker. Word performs its own layout and may remove the marker. An external document's enabled kinsoku setting without the engine marker cannot be assumed to represent the same rules: strict import rejects it, and loss-allowing import reports diagnostics.

## Output and compatibility

`write(document, backend, Path)` uses a temporary file in the same directory and atomic replacement. The parent directory must exist. Failure or cancellation preserves the old file and removes the temporary file. The filesystem must support atomic moves. `write(..., OutputStream)` borrows the caller's stream without closing it; failure may leave partial content. Both entry points have `ExecutionOptions` overloads. Defaults impose neither a timeout nor an output-byte limit; servers should configure budgets explicitly.

`SectionedDocument` also has short `layout`, stream-output and path-output entry points, defaulting to empty page regions and navigation for every section. For contents, bookmarks, page regions and notes, use the existing full `layout(document, templates, navigation, notes)` entry point and send its result to the chosen backend. Engine entry points do not depend on a specific PDF backend.

Old constructors remain available; added record members default to the previous policies. Exhaustive caller `switch` expressions must handle the new `JUSTIFY` enum value. There is no stable release yet; callers need recompilation for the record additions, and the original phase did not promise binary compatibility.

Ordinary reports now have short entry points, while precise control still requires understanding semantic, layout and output boundaries. Complex component composition has more parameters and remains an advanced API. Verification evidence and remaining scope are recorded in [phase 13 (Chinese)](../verification/phase-13-authoring.md).

Apply a [DocumentTheme](themes-api.md) at the authoring entry point to share body, six-level heading and semantic-table defaults across reports. Specialized paragraphs can use full-format overloads with automatic IDs; named inline styles retain complete sizes and roles. Values are resolved when content is added. See [phase 30 (Chinese)](../verification/phase-30-authoring-themes.md).

List markers can now have their own complete `markerStyle` through `NumberedItem.builder()` / `BulletItem.builder()`; the body's first rich run does not change the marker. Old defaults and constructors remain. See the [list-marker guide (Chinese)](../list-markers-api.md) for record-shape changes, explicit inheritance restoration and DOCX boundaries.
