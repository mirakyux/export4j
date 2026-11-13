# General Markdown Import

`MarkdownImporter` in `export4j-adapter-web` converts a CommonMark/GFM subset into a `Document` that can be edited further with [DocumentEditor (Chinese)](../semantic-editing-api.md) and exported. It is independent of [strict Markdown templates](markdown-template-api.md): `${name}` is ordinary text here and does not perform variable substitution.

```java
import com.mirakyux.export4j.adapter.web.MarkdownImporter;
import com.mirakyux.export4j.model.edit.DocumentEditor;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;

var policy = ProcessingPolicy.builder()
        .feature("BLOCK_QUOTE", ProcessingPolicy.Action.WARN,
                ProcessingPolicy.Treatment.DEGRADE)
        .build();
var prepared = MarkdownImporter.builder().policy(policy).build()
        .read("# Report\n\n> Pending\n\nOld status").prepare();
var predicted = prepared.preview();
var imported = prepared.execute();
var edited = DocumentEditor.edit(imported.document())
        .replaceText("Old status", "Ready").execute();
var losses = imported.report().losses();
```

`prepare()` and `execute()` declare `IOException` and execute synchronously with `.execution(ExecutionOptions)`; this entry point performs no file I/O itself. The Builder takes a snapshot when building. A PreparedImport fixes the input, configuration, candidate and preflight report; later Request changes do not affect it.

## Mapping Scope

| Input | Model behavior |
| --- | --- |
| Paragraphs, ATX/Setext headings | Paragraph/Heading; soft breaks become spaces and hard breaks are preserved |
| strong/emphasis/their combination, strikethrough | Full inline styles; default roles are `bold`/`italic`/`boldItalic`; strikethrough is preserved |
| Links and reference links | Passive HTTP/HTTPS/mailto; reference definitions do not enter body text; link titles report separate losses |
| Ordered/unordered and mixed nested lists | NumberedItem/BulletItem, up to nine levels, retaining starting numbers and parent-list relationships; ordered children use the model's full dotted numbering |
| GFM tables | Semantic Table, headers, cell alignment, escaped pipes and spaces; columns have equal proportions. Header-only tables retain their rows and bold appearance with repetition disabled, without an added empty body row |
| Explicitly registered images/vectors | Mixed inline objects in paragraphs, headings, ordered/unordered items and cells; images retain source alternative text and enclosing links |
| Fenced/indented blocks and inline code | Literal text with the code role without a loss-policy requirement. Blocks retain parsed whitespace/newlines; inline code follows CommonMark whitespace rules |
| Quotes/task items | Still require explicit loss handling; quote containers and checkbox semantics are not claimed to be preserved |

Font roles are specified through `.fontRoles(strong,emphasis,combined,code)`; PDF export still requires explicit registration of corresponding physical fonts. The default code role is `code`; fonts are not obtained automatically. Metadata, page size and default body formatting can be supplied through the Builder.

Body formatting through `.paragraphFormat(...)` and table formatting through `.cellFormat(...)` are independent. When cell formatting is omitted, building resolves it to `CellFormat.of(the final body font size)`, retaining the default fixed line height of 1.5 times that size. Body line height, alignment and line-break policy are not inherited. An explicit cell format controls both cell and inline text font sizes, line height and line-break policy. Explicit GFM column alignment takes precedence; columns without alignment markers use the configured cell alignment.

```java
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ParagraphFormat;

var importer = MarkdownImporter.builder()
        .paragraphFormat(ParagraphFormat.of(12))
        .cellFormat(CellFormat.builder().fontSize(12).lineHeight(64).build())
        .build();
```

Tall images or inline equations must still fit the fixed cell line box; reserve their height through `cellFormat`. The importer does not scale resources automatically. The format is snapshotted at `build()` and shared by `conversion(...)`. Language metadata and syntax highlighting are not represented, and code content is never executed.

## Resources and Policies

`.image(destination, inlineImage)` registers encoded resources and pt dimensions using an exact source-destination key; it never accesses the network or reads files. Missing images fail by default and can explicitly become alternative text or be discarded. `.baseUri("https://example.org/docs/")` only resolves passive relative links without fetching content. Relative/internal targets without a base fail by default; model anchors are not inferred.

The shared policy uses format key `MARKDOWN`, operation key `IMPORT` and precedence operation → feature → format → default. The default is ERROR. WARN/IGNORE execute the selected treatment; IGNORE only hides `warnings()`, without removing `issues()`/`losses()`. Import provides only actually available DEGRADE/DISCARD paths; it does not support fake preservation or editing skip ranges.

| Feature key | DEGRADE | DISCARD |
| --- | --- | --- |
| `BLOCK_QUOTE` | Remove the container and indent its body | Remove the entire quote subtree |
| `THEMATIC_BREAK` | Five literal hyphens | Remove the separator |
| `RAW_HTML` | Emit safe HTML as literal text; simple br becomes a line break directly | Remove that raw HTML node |
| `TASK_LIST` | Retain the literal task marker | Remove that task item |
| `LIST_ITEM_STRUCTURE` | Flatten multiple blocks while retaining indentation | Remove the unsupported sub-block/item |
| `IMAGE_RESOURCE` | Convert an unregistered image to alternative text | Remove the image |
| `LINK_TITLE`/`IMAGE_TITLE`/`RELATIVE_LINK` | Remove the unsupported title/target and retain other content | Likewise remove only that attribute/target |
| `NUMBERING_START` | Change a zero starting number to one | Remove that list |

Unknown nodes permit only DISCARD; no degradation representation is invented. Scripts or dangerous links are rejected even inside subtrees that will be discarded. Safe raw HTML is not interpreted for layout here.

This `MarkdownImporter` entry point still rejects `RAW_HTML` by default; DEGRADE produces literal HTML and does not preserve merged cells. For mixed Markdown/HTML tables, use `ContentImporter` with explicit `ContentOptions.builder().format(ContentOptions.Format.MARKDOWN).rawHtml(true)`. The library maps its offline subset, preserves spans and maps diagnostics to original Markdown ranges. [Mixed content and cross-format mathematics](rich-content-api.md) covers formula/code protection and PDF/native Word targets without a caller-maintained CommonMark-to-HTML bridge. The older RC3 consumer integration remains documented in [literature PDF integration (Chinese)](../literature-pdf-integration.md).

## Positions and Budgets

Reports use half-open UTF-16 ranges in the original input, generated block IDs and physical cell paths. They do not echo body text or resource paths. Generated IDs use `markdown-N`; they are not guaranteed to be consecutive because containers also receive IDs.

Default `ImportLimits` are 1,000,000 UTF-16 units of input, 16,384 per line, 100,000 nodes, depth 64, 2,000,000 UTF-16 units of output text, 100,000 table slots, 10,000 reports and 20,000,000 work units. Invalid Unicode/NUL, exceeded limits, cancellation and timeout always fail. Checkpoints run before and after third-party parsing and during tree traversal. This is not a hostile-input sandbox or a promise of hard RSS limits or hard interruption.

See [MarkdownImportExample](../../examples/src/main/java/com/mirakyux/export4j/examples/MarkdownImportExample.java) for a complete invocation. After running `:examples:markdownImport`, use `scripts/verify_markdown_import.py` to verify independent syntax, multiple output targets and actual losses. Evidence is in [Phase 45 (Chinese)](../verification/phase-45-markdown-import.md).
