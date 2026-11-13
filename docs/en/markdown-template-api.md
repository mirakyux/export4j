# Markdown templates

`MarkdownTemplate` compiles lightweight text into the existing `DocumentTemplate` and suits reports with simple structure. It belongs to `export4j-adapter-template` and depends only on the model and JDK. Compiled results retain headings, lists, real font roles, and inline images; the engine still handles layout. This is a strict subset, rather than a general CommonMark or GitHub Markdown importer.

## Minimal call

~~~java
var template = MarkdownTemplate.builder()
        .bodyFormat(ParagraphFormat.of(12))
        .build()
        .compile("""
                # ${title}

                客户：**${customer.name}**
                本行与上一行属于同一段落。

                - 已完成资料核对
                - 已完成结果复核
                """);
var document = template.render(Map.of(
        "title", "巡检报告",
        "customer", Map.of("name", "东门仓")));
engine.export(document).backend(new PdfBackend()).to(outputPath);
~~~
Types reside in `com.mirakyux.export4j.adapter.template`. The caller explicitly configures the engine's fonts, real bold/italic roles, output backend, and outputPath. The compiler does not scan fonts or read files or URLs. For file input, the caller opens a Reader and passes it to `compile(reader)`; the caller retains Reader ownership.

## Strict syntax

| Syntax | Meaning and constraints |
| --- | --- |
| Plain text | Blank lines separate paragraphs; adjacent plain lines join with one space, without an explicit line break |
| `# 标题`–`###### 标题` | Unindented ATX headings, with at least one space after the hashes; optional trailing hashes must be separated by spaces; content must be nonempty |
| `- 条目`, `+ 条目`, `* 条目` | Unindented, single-line, first-level unordered items; all use the `•` marker |
| `*文字*` | Real `italic` font role |
| `**文字**` | Real `bold` font role |
| `***文字***` | Real `boldItalic` font role |
| `${customer.name}` | A path of ASCII identifiers and dots only, with at most 256 UTF-16 units |
| `![替代文字](logo)` | An inline body image that selects an explicitly registered resource name; dimensions are configured in Java |

Headings use `Heading.defaultFormat(level)`; ordinary paragraphs and items use the configured bodyFormat, defaulting to 12 pt text and an 18 pt fixed line height. Emphasis cannot nest, span source lines, or start/end with a space inside its delimiters. The parser does not synthesize bold/italic glyphs. Register real font variants for the corresponding roles when using emphasis.

Ordinary paragraphs can span multiple lines. Each list item occupies one source line. A normal paragraph after a list item must have a preceding blank line, to avoid interpreting Markdown list continuation as a new paragraph. Headings, items, and paragraphs receive deterministic `markdown-N` IDs. This syntax declares neither reference targets nor explicit IDs. Empty documents are valid.

CRLF, LF, and CR are supported. Nonempty lines must start at the left margin; tabs are rejected. A single trailing space is removed as Markdown whitespace. Two or more trailing spaces would mean a hard line break and are rejected. U+2028, U+2029, raw U+FFFC, and control characters are also rejected. Invalid surrogate pairs and U+FFFE/U+FFFF produce Unicode diagnostics.

Backslashes can escape `\`, `*`, `_`, `[`, `]`, `!`, `<`, `>`, backticks, `~`, `|`, `#`, `-`, `+`, `.`, `(`, `)`, and `&`. For example, `\*普通星号\*` displays ordinary asterisks. Trailing backslashes, unknown escapes, and escapes of dollar signs or braces are rejected. `${...}` always denotes a variable; insert an expression through a data value when it must appear literally.

Variables do not invoke methods, indexing, arithmetic, reflection, or scripts. Data values expand only once; embedded Markdown, HTML, and `${other}` remain ordinary text. The caller formats dates, amounts, and localized values in advance.

## Explicit image resources

~~~java
var compiler = MarkdownTemplate.builder()
        .bodyFormat(ParagraphFormat.builder().fontSize(12).lineHeight(20).build())
        .image("logo", imageResource, 12, 14)
        .build();
var template = compiler.compile("${name} ![标记 ${name}](logo)");
~~~
imageResource is an immutable ImageResource already decoded by the caller within limits. width/height are positive points and must not exceed 14,400 pt. The compiler does not decode images. Resource names match `[A-Za-z_][A-Za-z_0-9]{0,255}`. Registering the same name replaces its configuration; a compiler produced by build retains its prior snapshot.

Template image targets accept resource names only, without paths, URLs, variables, titles, or sizing syntax. alt accepts plain text, escapes, and variables, without rich text. Headings, items, and emphasis ranges cannot contain images. A paragraph may contain at most 1,024 images, matching existing model limits. Image U+FFFC positions are recalculated after binding; supplementary-plane characters in the original string do not corrupt indices.

The fixed line box must accommodate both images and fonts; insufficient space fails explicitly during layout. Alternative text follows InlineImage constraints both at compilation and after binding: at most 4,096 UTF-16 units and no control characters. Invalid bound alt still produces the existing model's IllegalArgumentException, without mapping it back to Markdown line/column positions.

## Budgets and diagnostics

`MarkdownTemplate.Limits(maxInputCharacters, maxLines, maxBlocks, maxInlineContents)` defaults to 1,000,000, 20,000, 20,000, and 100,000 respectively. Total input length counts UTF-16 units, including line terminators. lines counts blank physical lines but not an empty line after the final terminator; empty input has 0 lines. inlines counts actual text runs, images, and spaces inserted for paragraph soft breaks.

A borrowed Reader is read up to the input limit plus 1 UTF-16 unit and is not closed on success, compilation failure, or I/O failure. Reader IOException propagates unchanged. Custom Readers and containers are trusted caller Java code and must cooperate with cancellation; this is not a script sandbox.

`.bindingLimits(DocumentTemplate.Limits)` separately limits data snapshots, accesses, generated blocks, code points, and related work. Source parsing is nonrecursive, with execution checkpoints during scanning and reading. It can run inside `ExecutionContext.execute(options, ...)` for synchronous cancellation and timeouts. Templates and documents remain in memory; streaming layout is not promised.

| Stage | Diagnostics |
| --- | --- |
| Markdown compilation | MarkdownTemplateException.code/line/column/offset |
| Data binding | DocumentTemplate.BindingException.code/path |
| Layout, fonts, and output | Existing diagnostics of the corresponding module |

Compilation positions refer to original source: line/column are one-based; column and zero-based offset count UTF-16 units. Both CRLF units count toward offset. Input-limit errors locate the first excessive unit; an error at the end may have an offset equal to source length. Diagnostics contain only stable codes and positions, without echoing body text, data, or resource paths. No partial template is returned. Binding errors are not mapped back to source lines/columns.

## Rejected features

HTML/comments/angle-bracket autolinks, ordinary or reference links, linked images, HTML entities, code blocks, inline code, block quotes, tables, thematic breaks, Setext headings, ordered/task/nested/multiline lists, underscore emphasis, strikethrough, and hard line breaks are rejected. Some reserved punctuation must also be escaped in ordinary prose. The parser does not guess whether unknown syntax should be shown literally. Bare URLs remain ordinary text rather than automatic links.

Conditions, loops, dynamic table rows, fields, pagination, and includes have no Markdown syntax. `{{...}}` and `{%...%}` directive forms are rejected. Use the [strict XML API](xml-template-api.md) or existing DocumentTemplate Java API for more complex templates. DOCX, SVG, and PDF exports retain their corresponding support matrices. Arbitrary lossless Markdown, identical Word/PDF pagination, and PDF/UA compliance are not promised.

This implementation was independently designed, without adding a Markdown parsing library or new resources. See [MarkdownTemplateTest](../../export4j-adapter-template/src/test/java/com/mirakyux/export4j/adapter/template/MarkdownTemplateTest.java) for boundary tests and [ADR-0028 (Chinese)](../adr/0028-markdown-templates.md) for design rationale.

See [summary.md (Chinese)](../../examples/src/main/resources/templates/summary.md) for the full template and [MarkdownTemplateExample](../../examples/src/main/java/com/mirakyux/export4j/examples/MarkdownTemplateExample.java) for resource and real-font configuration. Run `:examples:markdownTemplates`, then execute `python scripts/verify_markdown_templates.py` using the successful isolated consumer from that batch. Evidence and limits are in [phase 22 (Chinese)](../verification/phase-22-text-and-adapters.md).
