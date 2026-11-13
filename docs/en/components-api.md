# Components API

The initial component implementation targets PDF and covers pages, headers and footers, text, RGB images, tables, rectangles, ellipses, lines, watermarks, contents, bookmarks, comment annotations, and basic fillable forms. The root package is `com.mirakyux.export4j`. All lengths use points, with the page origin at the top left.

New authoring code can use `Documents.builder()`, component builders, and synchronous nested callbacks; see the [Fluent API](fluent-api.md). The explicit-constructor examples in this guide remain compatible.

For dense static forms, use `FormText`, `ChoiceGroup`, `CheckMark`, `LabelValue`, and `FillLine`; see [Static forms](static-forms-api.md). A positioned `TableComponent` supports only a static, single-viewport subset and explicitly rejects fields, text/image hyperlinks, and named font roles. For these contents inside component containers, use `SemanticTable`/`.semanticTable(Table)`. Long tables can continue through a region flow, with fields and interactions resolved by the final document; see [Semantic composition](semantic-components-api.md).

Text-component builders preserve unset properties and can inherit the complete style and color from `Documents.bodyFormat`/`StyleScope`. Explicit settings take precedence; direct constructors still fix the supplied values. Grid row splitting and float wrapping are resolved before measurement, and scopes preserve child-component widths; see [Inherited component styles](style-defaults-api.md).

## Modules and entry points

Callers depend on `export4j-components` and select `export4j-backend-pdf` separately for PDF output. The component module does not depend on the PDF backend, and the layout core does not depend on component implementations.

| Type | Responsibility |
| --- | --- |
| `Component` | Core SPI that measures and returns local immutable drawing commands and diagnostics |
| `ComponentContext` | Viewport dimensions, explicit font stack, and page context |
| `ComponentLayout` | Measured dimensions, text-line count, independently balanced commands, and a diagnostic snapshot |
| `ComponentBlock` / `PageableComponent` | Ordinary whole-block flow; the pageable SPI supplies ordered fragments |
| `Placement` / `Position` | Component positioning and viewport resolution |
| `PageBand` / `PageTemplate` | Reserved header/footer regions and backgrounds/foregrounds |
| `PageComposer` | Final page composition, optionally returning placement traces and diagnostics |
| `Navigation` / `TableOfContents` | Unresolved bookmarks, contents, comments, and fields |
| `ComponentDocumentRenderer` | Body pagination, prepended contents pages, final target resolution, and page-region composition |
| `DocumentExtras` | Resolved interaction information consumed by the backend |

## Minimal composition example

The following method accepts a font stack explicitly loaded by the caller. See the [Text API](text-api.md) for font loading and [ComponentPdfExample](../../examples/src/main/java/com/mirakyux/export4j/examples/ComponentPdfExample.java) for a complete runnable example.

~~~java
import java.util.List;
import com.mirakyux.export4j.components.BoxComponent;
import com.mirakyux.export4j.components.ComponentDocumentLayout;
import com.mirakyux.export4j.components.ComponentDocumentRenderer;
import com.mirakyux.export4j.components.Navigation;
import com.mirakyux.export4j.components.PageNumberComponent;
import com.mirakyux.export4j.components.TableOfContents;
import com.mirakyux.export4j.components.TextComponent;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.layout.core.ComponentBlock;
import com.mirakyux.export4j.layout.core.PageBand;
import com.mirakyux.export4j.layout.core.PageTemplate;
import com.mirakyux.export4j.layout.core.Placement;
import com.mirakyux.export4j.layout.core.Position;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.text.ParagraphStyle;

public final class ComponentExample {
    private ComponentExample() {
    }

    public static ComponentDocumentLayout compose(List<FontFace> fonts) {
        var document = new Document(new DocumentMetadata("组件示例", "Export4j"),
                PageSettings.A4, List.of(
                        new Paragraph("intro", "文档内容", ParagraphFormat.of(18)),
                        new ComponentBlock("form", new BoxComponent(300, 80,
                                new TextComponent("Name:", ParagraphStyle.of(12), RgbColor.BLACK)), 8, 8)));
        var footer = new PageBand(20, 8, List.of(new Placement(
                new PageNumberComponent("{page} / {pages}", ParagraphStyle.of(10), RgbColor.BLACK),
                new Position.Relative(Position.Anchor.BOTTOM_RIGHT, 80, 18, 0, 0))));
        var template = new PageTemplate(PageBand.EMPTY, footer, List.of(), List.of());
        var navigation = new Navigation(
                List.of(new Navigation.Bookmark("正文", "intro", 1)),
                new TableOfContents(true, "目录", ParagraphStyle.of(12)), List.of(),
                List.of(new Navigation.TextField(
                        new Navigation.BlockTarget("form", new Rect(60, 28, 200, 24)),
                        "name", "Ada", false)));
        return new ComponentDocumentRenderer().layout(document, template, navigation, fonts);
    }
}
~~~

Pass `result.display()` to `PdfBackend.write` for output. Layout performs no file I/O; the caller creates and closes the output stream when writing a file.

~~~powershell
.\gradlew.bat :examples:componentPdf
~~~

The output is `examples/build/export4j-components-example.pdf`. A custom path is also supported:

~~~powershell
.\gradlew.bat :examples:componentPdf --args="examples/build/custom-components.pdf"
~~~

## Standard components

| Component | Current behavior |
| --- | --- |
| `TextComponent` | Wraps inside the viewport, uses measured height, and preserves font-fallback diagnostics |
| `BoxComponent` | Reserves fixed dimensions for its child and aligns it to the top left |
| `ImageComponent` | Explicit `RgbImage`; CONTAIN preserves the entire image's aspect ratio, COVER crops proportionally, and STRETCH stretches |
| `TableComponent` | Measures a table in one viewport and cannot span pages; use a semantic `Table` when pagination is required |
| `SemanticTable` | A semantic table in a body-region flow, supporting per-region headers and explicit splitting at complete text lines; preserves fields, hyperlinks, font roles, and cell provenance; fixed layout calls still require a single fragment |
| `ShapeComponent` | Rectangles and cubic-Bézier ellipses, opaque fills, and optional inset strokes |
| `LineComponent` | Endpoints use viewport-relative ratios from 0 to 1, with butt caps and strokes kept inside the viewport |
| `WatermarkComponent` | Pale RGB text, rotation around the center, and explicit clipping; no opacity parameter |
| `PageNumberComponent` | Substitutes `{page}` and `{pages}` only after the final page count is known |

Image components consume an explicit `RgbImage`, which the drawing core's `ImageDecoder` can decode from bounded PNG/JPEG bytes while retaining alpha. They do not access files or URLs. `SvgComponent` supports an offline vector subset. Callers always supply resources; there are no implicit downloads or system-font scans.

## Four layout modes

| Mode | Example | Reference frame |
| --- | --- | --- |
| Absolute coordinates | `new Position.Absolute(new Rect(48, 90, 200, 80))` | Current page coordinates |
| Relative anchor | `new Position.Relative(Position.Anchor.BOTTOM_RIGHT, 200, 80, -10, -10)` | The same anchor in the parent region, plus offsets in points |
| Fractional coordinates | `new Position.Fractional(0.1, 0.2, 0.8, 0.3)` | Parent-region width and height |
| Body flow | `new ComponentBlock("image", component, 8, 8)` | Document order, moving whole blocks or explicit fragments between pages |

The nine anchors cover left/center/right and top/middle/bottom. Absolute coordinates must also fall inside the placement's parent region. Absolute coordinates in a header still use page coordinates rather than offsets local to the header.

A body component is measured once against a complete content region. If it does not fit the current page, it moves to the next page; exceeding a fresh page's content area fails explicitly. `BoxComponent` can constrain an image, shape, or custom composition. Body paragraphs and tables continue to paginate at complete text lines/table rows through the original semantic nodes.

These whole-block rules apply to ordinary `Component` implementations. `FlowText`, `FlowColumn`, `GridTable`, and `SemanticTable` implement the pagination SPI and can return ordered continuation fragments, with the first fragment using the current page's remaining height. A fixed region still requires exactly one fragment. `RowComponent` supports equal-width arrangement, but the entire row cannot split across pages. See [Advanced API: pageable components and merged tables (Chinese)](../advanced-api.md#可跨页组件与合并表格) for the specific constraints.

`ContinuousColumns` uses the core `RegionFlow` to begin columns in the remaining body region, then uses complete continuation columns and resumes the body at the actual final height. It supports text, mixed `FlowColumn` flows, and `GridTable` tables. Other components can participate through a mixed flow as whole blocks or their existing pagination fragments; see [Continuous columns (Chinese)](../continuous-columns-api.md) and [Mixed columns (Chinese)](../mixed-columns-api.md).

Body paragraphs use `InlineField` for the current page number, total pages, target page numbers/numbers, and inline note markers. Fields first participate in line breaking with a fixed width, then the composer resolves their final values. Footnotes follow the actual line containing their marker. Semantic tables inside component containers use `SemanticTable` to preserve and resolve fields; the fixed `TableComponent` still rejects them. See [Semantic composition](semantic-components-api.md) and the [Fields API (Chinese)](../inline-fields-api.md) for the exact boundaries of tables, continuous columns, and note markers.

`ComponentContext.pageCount() == 0` indicates measurement before pagination, and `pageIndex()` is only the candidate page at measurement time. Body components must be independent of the page index and final page count; moving to another page does not measure them again. Final page fields receive the actual zero-based page index and total page count during page-region composition; displayed numbers start at 1.

## Page regions and layers

`PageSettings` defines the shared page size and margins. A header occupies `height` downwards from the top margin, and a footer occupies `height` upwards from the bottom margin. Each region's `gap` separates it from the body. The corresponding region height and gap are added to the body's original top/bottom margins.

The composition entry point reserves regions before paginating. When calling the core directly, first lay out the body with `template.bodySettings(settings)`, then pass the original `settings` to `PageComposer`; do not reserve the space twice.

Drawing order is background, body, headers/footers, then foreground. Backgrounds and foregrounds use the entire page as their reference frame, while headers and footers use their own regions. The single-section entry point uses one template. The sectioned entry point's `SectionTemplate` supports first-page and odd/even templates and reserves the largest page region in advance. Contents pages use the first section's paper and odd/even templates. Body sections can change paper size and restart displayed page numbers; see [Advanced API: sections and displayed page numbers (Chinese)](../advanced-api.md#分节与显示页码).

`PageComposition.Placed` preserves the final page index, region, placement index within the region, resolved viewport, and `ComponentLayout`. Composition results also retain body, contents, and decoration diagnostics without losing font-fallback information.

Viewports must lie inside their parent region, and measured dimensions must not exceed the viewport. Custom components can generate arbitrary commands; the composer does not automatically clip their contents. Implementations must issue Clip explicitly when drawing needs to be constrained. Components are trusted Java code; budgets are not an execution sandbox.

## Contents, bookmarks, and targets

`Navigation.Bookmark(title, blockId, level)` points to an original body block. Levels start at 1, and the next entry cannot skip an intermediate level. The target is the block's first fragment.

Contents are generated in bookmark order as separate prepended pages. The title and entries are measured first, a 48 pt page-number column is reserved, the contents page count and body offset are calculated, and actual page numbers and internal links are emitted last. Entries may wrap but cannot split across pages, and the contents title repeats on continuation pages. Missing bookmarks, entries that are too tall, or an insufficient page-number column fail explicitly.

Page indices in `ComponentDocumentLayout.body()` are local to the body. The final page index is the body page index plus `bodyPageOffset()`; `display()` and `decorations()` already use final coordinates. `bodySectionIndices()` returns the zero-based source-section index for each body page in body-page order, including blank body pages and excluding prepended contents pages. The old constructor remains available; when section ownership is omitted, all body pages belong to section 0.

| Target type | Resolution rule |
| --- | --- |
| `BlockTarget(blockId, relativeBounds)` | Follows the first body fragment, with rectangle offsets relative to the fragment's top-left corner |
| `PageTarget(pageIndex, position)` | Selects a final page, including contents pages; position is relative to the entire page |

Target rectangles must be inside the page. Block targets do not automatically reserve body space for annotations or fields, and their rectangles need not lie inside the fragment. Callers should reserve a fillable region to avoid covering other content. Unknown blocks, invalid pages, out-of-bounds rectangles, and duplicate field names all fail.

## Comments and forms

Comments are emitted as PDF Text annotations with their author, content, and icon appearance; they are not written into body text. Bookmark and contents links use real page objects and XYZ destinations, without scripts.

AcroForm supports editable single-line/multiline text fields, checkboxes, closed dropdowns, and radio groups. Flat field names must match `[A-Za-z_][A-Za-z0-9_-]{0,127}` and be unique throughout the document. Text fields with an empty font list allow only printable ASCII; multiline fields may contain LF. Supplying one explicit `FontData` supports Chinese initial values and subsequent editing.

Forms default to standard Helvetica at 10 pt; explicit fonts are embedded in full. `/V`, `/DV`, and `/AP` are saved. Checkboxes save `/Off`, `/Yes`, and the current `/AS`. Radio groups use one canonical parent field and multiple child widgets, with ASCII option export names. The field tree remains consistent with page widgets, `NeedAppearances=false`, and fields are not flattened. Long text may require scrolling in the reader; complete values are not guaranteed to fit in the visible box. Missing glyphs in initial values continue to fail, without applying the body placeholder policy. See [Advanced API: Chinese forms, dropdowns, and radio groups (Chinese)](../advanced-api.md#中文表单、下拉框与单选组) for a complete example.

PDF readers differ in their support for filling, annotation panels, and saving. The verification includes independent filling, saving, reopening, and rendering; it does not establish support in every browser or reader.

## Budgets, diagnostics, and compatibility

The current source supports engine budgets via `DocumentEngine.Builder.layoutLimits(...)` / `componentLimits(...)`; see the [fluent API](fluent-api.md#layout-and-component-budgets). These entry points are not included in the published `0.1.0-rc.2`.

`DocumentLayoutLimits` continues to constrain body blocks, source code points, text lines, table structure, and pages. GlyphText glyph code points and Text code points generated by body components count toward the same text budget. The component's reported line count counts toward the line budget. These counts are collected after the custom component returns.

The contents title and entries share an independent `maxCodePoints` budget, checked before measuring entries. Contents output lines use an independent `maxLines` budget, including repeated titles and page-number lines. The body and contents share the total-page and final-drawing-command budgets.

`ComponentLimits.DEFAULT` permits 10,000 page-region placements, 1,000,000 final commands, and 10,000 interactions. Page-region placements count each repetition on each page. Final commands include the body, contents, and composed Save/Transform/Restore commands. Body layout also checks the total component-command count separately. Interactions include bookmarks, contents links, comments, and fields. Total pages include contents pages. The PDF backend has separate budgets for text, images, paths, fonts, and interactions; see the [Drawing API (Chinese)](../graphics-api.md).

Invalid body-component results or argument failures are wrapped as `COMPONENT_LAYOUT_FAILED`, retaining the block, page, and cause. An unrecognized Block returns `UNSUPPORTED_COMPONENT`. Argument/budget problems in page-region, contents, and target resolution use `IllegalArgumentException`; underlying text exceptions retain their original types. Other runtime exceptions from custom components propagate unchanged rather than being reported as success.

The public-contract changes introduced in this increment were:

- `Block` changed from sealed to an open interface. Third parties adapt through `ComponentBlock`; existing exhaustive switches need their default branch reviewed.
- `LayoutFragment` gained `ComponentFragment`. Continue using `elements()` for complete traversal.
- `DisplayDocument` gained `extras`, retaining its original two-argument constructor.
- `PdfLimits` gained interaction-count and text budgets, retaining its original six-/seven-argument constructors.
- `DocumentLayouter` gained a three-argument constructor including `ComponentLimits`; the original constructors remain valid.

These contract changes were introduced before the release candidate. The current published version is `0.1.0-rc.2`, and the [published API baseline (Chinese)](../api-compatibility.md) governs public declarations. Callers migrating from early development snapshots must still recheck record component counts, reflective serialization, and exhaustive switches over enums/sealed types.

Advanced capabilities now include pagination constraints, adjacency retention, sections, vertically nested flows, rich text, merged tables, Chinese forms, and dropdowns; see the [Advanced API (Chinese)](../advanced-api.md). Later additions include rectangular wrapping, multiple columns, shared complex-text handling, and external CMS signing entry points; see [Capabilities](capabilities-api.md). Transparent watermarks and script actions are not supported. See [ADR-0006 (Chinese)](../adr/0006-components-and-pdf-interactions.md) for the initial architecture, [ADR-0007 (Chinese)](../adr/0007-advanced-layout-and-font-policy.md) for extensions, and [Advanced acceptance (Chinese)](../verification/phase-3-advanced.md) for evidence.
